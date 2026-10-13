package ai.storyteller.photocraft.ui

import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import ai.storyteller.photocraft.editor.CropBox
import ai.storyteller.photocraft.editor.EditorState
import ai.storyteller.photocraft.editor.EditorViewModel
import ai.storyteller.photocraft.editor.SelectMode
import ai.storyteller.photocraft.editor.Tool
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The document view. Two fingers always pan and zoom (1×-32×); one finger does what the tool
 * does. Everything is drawn in document pixels under one transform, so the preview, the sharp
 * zoomed region, the selection and the crop box stay aligned.
 */
@Composable
fun CanvasView(state: EditorState, vm: EditorViewModel, modifier: Modifier = Modifier) {
    val info = state.info ?: return
    val docW = info.width.toFloat()
    val docH = info.height.toFloat()
    val density = LocalDensity.current
    val handleRadiusPx = with(density) { 28f * this.density }

    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    // A different document size (open, crop, rotate) resets the view.
    remember(info.width, info.height) { zoom = 1f; pan = Offset.Zero; vm.clearRegion(); Unit }

    // In-progress gestures, drawn until the engine's result arrives.
    var livePoints by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var liveRect by remember { mutableStateOf<Rect?>(null) }
    var pendingStrokeRevision by remember { mutableLongStateOf(-1L) }
    LaunchedEffect(info.revision) {
        if (pendingStrokeRevision >= 0 && info.revision.toLong() != pendingStrokeRevision) {
            livePoints = emptyList()
            pendingStrokeRevision = -1
        }
    }

    val current by rememberUpdatedState(state)
    // Functions, not values: the gesture loop outlives compositions and must see the live view.
    fun fitNow() = if (viewSize.width == 0) 1f else min(viewSize.width / docW, viewSize.height / docH) * 0.94f
    fun scaleNow() = fitNow() * zoom
    fun centerNow() = Offset(viewSize.width / 2f, viewSize.height / 2f)
    fun toDoc(s: Offset) = (s - centerNow() - pan) / scaleNow() + Offset(docW / 2, docH / 2)
    fun toScreen(d: Offset) = centerNow() + pan + (d - Offset(docW / 2, docH / 2)) * scaleNow()
    val scale = scaleNow()
    val center = centerNow()

    fun requestSharpRegion() {
        if (zoom <= 1.25f || viewSize.width == 0) {
            vm.clearRegion()
            return
        }
        val a = toDoc(Offset.Zero)
        val b = toDoc(Offset(viewSize.width.toFloat(), viewSize.height.toFloat()))
        val r = RectF(max(0f, a.x), max(0f, a.y), min(docW, b.x), min(docH, b.y))
        if (r.width() < 1 || r.height() < 1) return
        vm.requestRegion(r, (r.width() * scaleNow()).toInt().coerceAtMost(2048), (r.height() * scaleNow()).toInt().coerceAtMost(2048))
    }
    LaunchedEffect(info.revision) { requestSharpRegion() }

    Canvas(
        modifier
            .fillMaxSize()
            .onSizeChanged { viewSize = it }
            .pointerInput(info.width, info.height) {
                var lastTapAt = 0L
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val s = current
                    val tool = s.tool
                    val startDoc = toDoc(down.position)
                    var multiTouch = false
                    var moved = false
                    val points = mutableListOf(floatArrayOf(startDoc.x, startDoc.y, down.pressure.coerceIn(0.05f, 1f)))
                    // Which part of the crop box the finger grabbed: 0..3 corners, 4 = inside.
                    val cropGrab = s.crop?.let { grabCrop(it, down.position, ::toScreen, handleRadiusPx) }
                    val cropStart = s.crop

                    do {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.size >= 2) {
                            if (!multiTouch) {
                                multiTouch = true
                                livePoints = emptyList()
                                liveRect = null
                            }
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            val centroid = event.calculateCentroid()
                            val newZoom = (zoom * zoomChange).coerceIn(1f, 32f)
                            val factor = newZoom / zoom
                            pan = pan + panChange + (centroid - centerNow() - pan) * (1 - factor)
                            zoom = newZoom
                            if (zoom == 1f) pan = Offset.Zero
                            event.changes.forEach { it.consume() }
                            continue
                        }
                        if (multiTouch) continue
                        val change = event.changes.firstOrNull { it.id == down.id } ?: continue
                        if (!change.positionChanged()) continue
                        if ((change.position - down.position).getDistance() > 6f) moved = true
                        val p = toDoc(change.position)
                        when {
                            tool == Tool.Move -> if (zoom > 1f) pan += change.position - change.previousPosition
                            tool.isPaint -> {
                                points += floatArrayOf(p.x, p.y, change.pressure.coerceIn(0.05f, 1f))
                                livePoints = points.map { Offset(it[0], it[1]) }
                            }
                            tool == Tool.Lasso -> {
                                points += floatArrayOf(p.x, p.y, 1f)
                                livePoints = points.map { Offset(it[0], it[1]) }
                            }
                            tool == Tool.Marquee || tool == Tool.Ellipse -> liveRect = Rect(startDoc, p).normalized()
                            tool == Tool.Crop && cropGrab != null ->
                                cropStart?.let { vm.setCrop(dragCrop(it, cropGrab, p - startDoc, docW, docH, vm.cropRatio())) }
                        }
                        change.consume()
                    } while (event.changes.any { it.pressed })

                    if (multiTouch) {
                        requestSharpRegion()
                        return@awaitEachGesture
                    }
                    when {
                        tool == Tool.Move -> {
                            if (!moved) {
                                val now = System.currentTimeMillis()
                                if (now - lastTapAt < 300) {
                                    if (zoom > 1f) { zoom = 1f; pan = Offset.Zero } else {
                                        zoom = 3f
                                        pan = (centerNow() - down.position) * 2f
                                    }
                                    lastTapAt = 0
                                } else lastTapAt = now
                            }
                            requestSharpRegion()
                        }
                        tool.isPaint -> {
                            if (!moved) points += floatArrayOf(startDoc.x + 0.5f, startDoc.y + 0.5f, points[0][2])
                            livePoints = points.map { Offset(it[0], it[1]) }
                            pendingStrokeRevision = s.info?.revision?.toLong() ?: -1
                            vm.paintStroke(points.toList())
                        }
                        tool == Tool.Lasso -> {
                            livePoints = emptyList()
                            if (points.size >= 3) vm.selectLasso(points.toList())
                        }
                        tool == Tool.Marquee || tool == Tool.Ellipse -> {
                            val r = liveRect
                            liveRect = null
                            if (r != null && moved) {
                                val c = r.intersect(Rect(0f, 0f, docW, docH))
                                vm.selectRect(RectF(c.left, c.top, c.right, c.bottom), tool == Tool.Ellipse)
                            } else if (s.selectMode == SelectMode.Replace) vm.run("select.deselect")
                        }
                        tool == Tool.Wand -> if (!moved && inDoc(startDoc, docW, docH)) vm.magicWand(startDoc.x, startDoc.y)
                        tool == Tool.Eyedropper -> {
                            val p = toDoc(down.position)
                            if (inDoc(p, docW, docH)) vm.sampleColor(p.x, p.y)
                        }
                    }
                }
            },
    ) {
        if (viewSize.width == 0) return@Canvas
        withTransform({
            translate(center.x + pan.x, center.y + pan.y)
            scale(scale, scale, Offset.Zero)
            translate(-docW / 2, -docH / 2)
        }) {
            checkerboard(Size(docW, docH), cell = 16f / scale)
            state.preview?.let { img ->
                drawImage(
                    img,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(img.width, img.height),
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(info.width.toInt(), info.height.toInt()),
                    filterQuality = if (zoom > 2f) FilterQuality.None else FilterQuality.Low,
                )
            }
            state.region?.takeIf { it.revision == info.revision }?.let { r ->
                drawImage(
                    r.image,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(r.image.width, r.image.height),
                    dstOffset = IntOffset(r.rect.left.toInt(), r.rect.top.toInt()),
                    dstSize = IntSize(r.rect.width().toInt(), r.rect.height().toInt()),
                )
            }
            state.selectionOverlay?.let { ov ->
                if (state.tool != Tool.Crop) {
                    drawImage(
                        ov,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(ov.width, ov.height),
                        dstOffset = IntOffset.Zero,
                        dstSize = IntSize(info.width.toInt(), info.height.toInt()),
                    )
                }
            }
            val hair = 1.5f / scale
            if (livePoints.isNotEmpty()) {
                val path = Path().apply {
                    moveTo(livePoints[0].x, livePoints[0].y)
                    livePoints.drop(1).forEach { lineTo(it.x, it.y) }
                }
                if (state.tool.isPaint) {
                    val c = if (state.tool == Tool.Eraser) Color.White.copy(alpha = 0.5f) else Color(state.brush.color).copy(alpha = state.brush.opacity)
                    drawPath(path, c, style = Stroke(width = state.brush.size, cap = StrokeCap.Round, join = StrokeJoin.Round))
                } else {
                    drawPath(path, Color.White, style = Stroke(width = hair, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f / scale, 4f / scale))))
                }
            }
            liveRect?.let { r ->
                val dash = Stroke(width = hair, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f / scale, 4f / scale)))
                if (state.tool == Tool.Ellipse) drawOval(Color.White, r.topLeft, r.size, style = dash)
                else drawRect(Color.White, r.topLeft, r.size, style = dash)
            }
            state.crop?.let { drawCrop(it, docW, docH, scale) }
        }
    }
}

private fun inDoc(p: Offset, w: Float, h: Float) = p.x >= 0 && p.y >= 0 && p.x < w && p.y < h

private fun Rect.normalized() = Rect(min(left, right), min(top, bottom), max(left, right), max(top, bottom))

private fun grabCrop(box: CropBox, at: Offset, toScreen: (Offset) -> Offset, radius: Float): Int? {
    val corners = listOf(
        Offset(box.left, box.top), Offset(box.right, box.top),
        Offset(box.right, box.bottom), Offset(box.left, box.bottom),
    ).map(toScreen)
    corners.forEachIndexed { i, c -> if ((c - at).getDistance() <= radius) return i }
    val tl = corners[0]
    val br = corners[2]
    return if (at.x in tl.x..br.x && at.y in tl.y..br.y) 4 else null
}

/** The crop box after dragging corner `grab` (or the whole box, 4) by `delta` document pixels. */
private fun dragCrop(start: CropBox, grab: Int, delta: Offset, docW: Float, docH: Float, ratio: Float?): CropBox {
    val minSide = 16f
    if (grab == 4) {
        val dx = delta.x.coerceIn(-start.left, docW - start.right)
        val dy = delta.y.coerceIn(-start.top, docH - start.bottom)
        return CropBox(start.left + dx, start.top + dy, start.right + dx, start.bottom + dy)
    }
    // The corner opposite the grabbed one stays put.
    val ax = if (grab == 0 || grab == 3) start.right else start.left
    val ay = if (grab == 0 || grab == 1) start.bottom else start.top
    val gx = (if (grab == 0 || grab == 3) start.left else start.right) + delta.x
    val gy = (if (grab == 0 || grab == 1) start.top else start.bottom) + delta.y
    val sx = if (gx < ax) -1f else 1f
    val sy = if (gy < ay) -1f else 1f
    val maxW = if (sx < 0) ax else docW - ax
    val maxH = if (sy < 0) ay else docH - ay
    var w = abs(gx - ax).coerceIn(minSide, maxW.coerceAtLeast(minSide))
    var h = abs(gy - ay).coerceIn(minSide, maxH.coerceAtLeast(minSide))
    if (ratio != null) {
        // Keep the ratio, shrinking whichever side would leave the canvas.
        if (w / h > ratio) w = h * ratio else h = w / ratio
        if (w > maxW) { w = maxW; h = w / ratio }
        if (h > maxH) { h = maxH; w = h * ratio }
    }
    val x2 = ax + sx * w
    val y2 = ay + sy * h
    return CropBox(min(ax, x2), min(ay, y2), max(ax, x2), max(ay, y2))
}

private fun DrawScope.drawCrop(box: CropBox, docW: Float, docH: Float, scale: Float) {
    val shade = Color.Black.copy(alpha = 0.55f)
    drawRect(shade, Offset(0f, 0f), Size(docW, box.top))
    drawRect(shade, Offset(0f, box.bottom), Size(docW, docH - box.bottom))
    drawRect(shade, Offset(0f, box.top), Size(box.left, box.height))
    drawRect(shade, Offset(box.right, box.top), Size(docW - box.right, box.height))
    val line = 1.5f / scale
    drawRect(Color.White, Offset(box.left, box.top), Size(box.width, box.height), style = Stroke(line))
    // Rule of thirds.
    for (i in 1..2) {
        val x = box.left + box.width * i / 3
        val y = box.top + box.height * i / 3
        drawLine(Color.White.copy(alpha = 0.5f), Offset(x, box.top), Offset(x, box.bottom), line * 0.7f)
        drawLine(Color.White.copy(alpha = 0.5f), Offset(box.left, y), Offset(box.right, y), line * 0.7f)
    }
    val len = 22f / scale
    val thick = 4f / scale
    listOf(
        Triple(box.left, box.top, Offset(1f, 1f)), Triple(box.right, box.top, Offset(-1f, 1f)),
        Triple(box.right, box.bottom, Offset(-1f, -1f)), Triple(box.left, box.bottom, Offset(1f, -1f)),
    ).forEach { (x, y, d) ->
        drawLine(Color.White, Offset(x, y), Offset(x + d.x * len, y), thick, cap = StrokeCap.Square)
        drawLine(Color.White, Offset(x, y), Offset(x, y + d.y * len), thick, cap = StrokeCap.Square)
    }
}

/** Transparency checkerboard over the document area; cells keep a constant on-screen size. */
private fun DrawScope.checkerboard(size: Size, cell: Float) {
    clipRect(0f, 0f, size.width, size.height) {
        drawRect(Color(0xFFFFFFFF), Offset.Zero, size)
        val step = cell.coerceAtLeast(size.maxDimension / 160f)
        val dark = Color(0xFFCCCCCC)
        var y = 0f
        var row = 0
        while (y < size.height) {
            var x = if (row % 2 == 0) 0f else step
            while (x < size.width) {
                drawRect(dark, Offset(x, y), Size(step, step))
                x += step * 2
            }
            y += step
            row++
        }
    }
}
