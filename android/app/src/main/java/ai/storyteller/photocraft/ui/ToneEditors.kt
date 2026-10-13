package ai.storyteller.photocraft.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ai.storyteller.photocraft.R
import ai.storyteller.photocraft.editor.CurvePoint
import ai.storyteller.photocraft.editor.CurvesEdit
import ai.storyteller.photocraft.editor.Histogram
import ai.storyteller.photocraft.editor.LevelsEdit
import ai.storyteller.photocraft.editor.LevelsValues
import ai.storyteller.photocraft.editor.ToneChannel
import java.util.Locale
import kotlin.math.abs

@Composable
fun ChannelChips(selected: ToneChannel, onSelect: (ToneChannel) -> Unit) {
    Row(Modifier.padding(vertical = 2.dp)) {
        ToneChannel.entries.forEach { ch ->
            FilterChip(
                selected = ch == selected,
                onClick = { onSelect(ch) },
                label = { Text(stringResource(ch.label)) },
                modifier = Modifier.padding(end = 6.dp),
            )
        }
    }
}

/**
 * Curves: tap to add a point, drag to move it, drag it out of the graph to remove it (2-19
 * points, like Photoshop). The histogram of the chosen channel sits behind.
 */
@Composable
fun CurvesEditor(edit: CurvesEdit, histogram: Histogram?, onChange: (List<CurvePoint>) -> Unit) {
    val current by rememberUpdatedState(edit)
    val color = Color(edit.channel.color)
    Canvas(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 220.dp)
            .aspectRatio(1f, matchHeightConstraintsFirst = true)
            .padding(vertical = 4.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    fun toValue(o: Offset) = CurvePoint((o.x / w * 255f).coerceIn(0f, 255f), ((1 - o.y / h) * 255f).coerceIn(0f, 255f))
                    var points = current.curve().sortedBy { it.x }.toMutableList()
                    val touch = toValue(down.position)
                    val radius = 18f * 255f / w * density
                    var index = points.indices.minByOrNull { abs(points[it].x - touch.x) + abs(points[it].y - touch.y) }
                        ?.takeIf { abs(points[it].x - touch.x) + abs(points[it].y - touch.y) < radius * 2 }
                    if (index == null && points.size < 19) {
                        val at = points.indexOfFirst { it.x > touch.x }.let { if (it < 0) points.size else it }
                        points.add(at, CurvePoint(touch.x, touch.y))
                        index = at
                        onChange(points.toList())
                    }
                    val i = index ?: return@awaitEachGesture
                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val pos = change.position
                        val outside = pos.y < -48f * density || pos.y > h + 48f * density
                        val isEnd = i == 0 || i == points.lastIndex
                        if (outside && !isEnd && points.size > 2) {
                            points.removeAt(i)
                            onChange(points.toList())
                            change.consume()
                            // The point is gone; awaitEachGesture waits for the finger to lift.
                            return@awaitEachGesture
                        }
                        val v = toValue(pos)
                        // A point can't pass its neighbours.
                        val minX = if (i > 0) points[i - 1].x + 1f else 0f
                        val maxX = if (i < points.lastIndex) points[i + 1].x - 1f else 255f
                        points[i] = CurvePoint(v.x.coerceIn(minX, maxX), v.y)
                        onChange(points.toList())
                        change.consume()
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        drawRect(Color(0xFF1A1A1A))
        histogram?.of(edit.channel)?.let { drawHistogram(it, color.copy(alpha = 0.25f)) }
        val grid = Color.White.copy(alpha = 0.12f)
        for (k in 1..3) {
            drawLine(grid, Offset(size.width * k / 4, 0f), Offset(size.width * k / 4, size.height))
            drawLine(grid, Offset(0f, size.height * k / 4), Offset(size.width, size.height * k / 4))
        }
        drawLine(Color.White.copy(alpha = 0.2f), Offset(0f, size.height), Offset(size.width, 0f))
        val pts = edit.curve().sortedBy { it.x }
        val path = Path()
        for (x in 0..255) {
            val y = evalCurve(pts, x.toFloat())
            val o = Offset(x / 255f * size.width, (1 - y / 255f) * size.height)
            if (x == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
        }
        drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
        pts.forEach { p ->
            val o = Offset(p.x / 255f * size.width, (1 - p.y / 255f) * size.height)
            drawCircle(Color.Black, 6.dp.toPx(), o)
            drawCircle(color, 6.dp.toPx(), o, style = Stroke(2.dp.toPx()))
        }
    }
}

/** Levels: input black / gamma / white and output black / white, over the histogram. */
@Composable
fun LevelsEditor(edit: LevelsEdit, histogram: Histogram?, onChange: (LevelsValues) -> Unit) {
    val v = edit.current()
    val color = Color(edit.channel.color)
    Canvas(Modifier.fillMaxWidth().heightIn(max = 110.dp).aspectRatio(2.6f).padding(vertical = 4.dp)) {
        drawRect(Color(0xFF1A1A1A))
        histogram?.of(edit.channel)?.let { drawHistogram(it, color.copy(alpha = 0.35f)) }
        val xb = v.inBlack / 255f * size.width
        val xw = v.inWhite / 255f * size.width
        // Gamma's midtone marker: where 50% output lands between the input points.
        val mid = xb + (xw - xb) * Math.pow(0.5, 1.0 / v.gamma).toFloat()
        drawLine(Color.Black, Offset(xb, 0f), Offset(xb, size.height), 2.dp.toPx())
        drawLine(Color.Gray, Offset(mid, 0f), Offset(mid, size.height), 2.dp.toPx())
        drawLine(Color.White, Offset(xw, 0f), Offset(xw, size.height), 2.dp.toPx())
    }
    LevelSlider(R.string.lv_in_black, v.inBlack, 0f, 253f) { onChange(v.copy(inBlack = it.coerceAtMost(v.inWhite - 2))) }
    LevelSlider(R.string.lv_gamma, v.gamma, 0.1f, 3f, decimals = true) { onChange(v.copy(gamma = it)) }
    LevelSlider(R.string.lv_in_white, v.inWhite, 2f, 255f) { onChange(v.copy(inWhite = it.coerceAtLeast(v.inBlack + 2))) }
    LevelSlider(R.string.lv_out_black, v.outBlack, 0f, 255f) { onChange(v.copy(outBlack = it)) }
    LevelSlider(R.string.lv_out_white, v.outWhite, 0f, 255f) { onChange(v.copy(outWhite = it)) }
}

@Composable
private fun LevelSlider(label: Int, value: Float, min: Float, max: Float, decimals: Boolean = false, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), Modifier.width(104.dp), style = MaterialTheme.typography.bodySmall)
        Slider(value = value.coerceIn(min, max), onValueChange = onChange, valueRange = min..max, modifier = Modifier.weight(1f))
        Text(
            if (decimals) "%.2f".format(Locale.ROOT, value) else value.toInt().toString(),
            Modifier.width(44.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun DrawScope.drawHistogram(bins: IntArray, color: Color) {
    // Square-root scale keeps a dominant tone from flattening the rest.
    val peak = bins.maxOrNull()?.takeIf { it > 0 } ?: return
    val barW = size.width / 256f
    val root = Math.sqrt(peak.toDouble())
    bins.forEachIndexed { i, n ->
        if (n == 0) return@forEachIndexed
        val h = (Math.sqrt(n.toDouble()) / root * size.height).toFloat()
        drawRect(color, Offset(i * barW, size.height - h), Size(barW + 0.5f, h))
    }
}

/**
 * A monotone cubic (Fritsch-Carlson) through the points: a close preview of the engine's curve
 * (the image itself always shows the engine's exact result).
 */
private fun evalCurve(pts: List<CurvePoint>, x: Float): Float {
    if (pts.isEmpty()) return x
    if (x <= pts.first().x) return pts.first().y
    if (x >= pts.last().x) return pts.last().y
    val n = pts.size
    val d = FloatArray(n - 1) { (pts[it + 1].y - pts[it].y) / (pts[it + 1].x - pts[it].x).coerceAtLeast(1e-3f) }
    val m = FloatArray(n) { i ->
        when (i) {
            0 -> d[0]
            n - 1 -> d[n - 2]
            else -> if (d[i - 1] * d[i] <= 0) 0f else (d[i - 1] + d[i]) / 2
        }
    }
    for (i in 0 until n - 1) {
        if (d[i] == 0f) { m[i] = 0f; m[i + 1] = 0f; continue }
        val a = m[i] / d[i]
        val b = m[i + 1] / d[i]
        val s = a * a + b * b
        if (s > 9) {
            val t = 3f / Math.sqrt(s.toDouble()).toFloat()
            m[i] = t * a * d[i]
            m[i + 1] = t * b * d[i]
        }
    }
    val k = pts.indexOfLast { it.x <= x }.coerceIn(0, n - 2)
    val h = pts[k + 1].x - pts[k].x
    val t = (x - pts[k].x) / h
    val t2 = t * t
    val t3 = t2 * t
    val y = (2 * t3 - 3 * t2 + 1) * pts[k].y + (t3 - 2 * t2 + t) * h * m[k] +
        (-2 * t3 + 3 * t2) * pts[k + 1].y + (t3 - t2) * h * m[k + 1]
    return y.coerceIn(0f, 255f)
}
