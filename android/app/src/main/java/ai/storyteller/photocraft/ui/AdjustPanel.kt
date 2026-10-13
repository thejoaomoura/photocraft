package ai.storyteller.photocraft.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ai.storyteller.photocraft.R
import ai.storyteller.photocraft.editor.AdjustParam
import ai.storyteller.photocraft.editor.CurvesEdit
import ai.storyteller.photocraft.editor.EditorState
import ai.storyteller.photocraft.editor.EditorViewModel
import ai.storyteller.photocraft.editor.IDENTITY_CURVE
import ai.storyteller.photocraft.editor.LevelsEdit
import ai.storyteller.photocraft.editor.LevelsValues
import ai.storyteller.photocraft.editor.SliderEdit
import java.util.Locale

/** A docked panel under the canvas: title, content, and Reset / Cancel / Apply. */
@Composable
fun DockedPanel(
    title: String,
    onReset: (() -> Unit)?,
    onCancel: () -> Unit,
    onApply: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) { content() }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (onReset != null) TextButton(onClick = onReset) { Text(stringResource(R.string.reset)) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
                Button(onClick = onApply) { Text(stringResource(R.string.apply)) }
            }
        }
    }
}

/** The Properties panel of the adjustment layer being edited. */
@Composable
fun AdjustPanel(state: EditorState, vm: EditorViewModel) {
    when (val a = state.adjust) {
        is SliderEdit -> DockedPanel(
            stringResource(a.kind.label),
            onReset = { vm.updateAdjust { (it as SliderEdit).copy(values = a.kind.defaults()) } },
            onCancel = vm::cancelAdjust,
            onApply = vm::applyAdjust,
        ) {
            a.kind.params.forEach { p ->
                ParamSlider(p, a.values[p.key] ?: p.default) { v -> vm.updateAdjust { e -> (e as SliderEdit).copy(values = e.values + (p.key to v)) } }
            }
        }
        is CurvesEdit -> DockedPanel(
            stringResource(R.string.adj_curves),
            onReset = { vm.updateAdjust { e -> (e as CurvesEdit).copy(curves = e.curves + (e.channel to IDENTITY_CURVE)) } },
            onCancel = vm::cancelAdjust,
            onApply = vm::applyAdjust,
        ) {
            ChannelChips(a.channel, vm::setToneChannel)
            CurvesEditor(a, state.histogram) { pts -> vm.updateAdjust { e -> (e as CurvesEdit).copy(curves = e.curves + (e.channel to pts)) } }
        }
        is LevelsEdit -> DockedPanel(
            stringResource(R.string.adj_levels),
            onReset = { vm.updateAdjust { e -> (e as LevelsEdit).copy(levels = e.levels + (e.channel to LevelsValues())) } },
            onCancel = vm::cancelAdjust,
            onApply = vm::applyAdjust,
        ) {
            ChannelChips(a.channel, vm::setToneChannel)
            LevelsEditor(a, state.histogram) { v -> vm.updateAdjust { e -> (e as LevelsEdit).copy(levels = e.levels + (e.channel to v)) } }
        }
        null -> {}
    }
}

/** A filter's settings; the preview re-renders when a slider is released. */
@Composable
fun FilterPanel(state: EditorState, vm: EditorViewModel) {
    val f = state.filter ?: return
    DockedPanel(
        stringResource(f.kind.label),
        onReset = { f.kind.params.forEach { vm.setFilterValue(it.key, it.default) }; vm.previewFilter() },
        onCancel = vm::cancelFilter,
        onApply = vm::applyFilter,
    ) {
        f.kind.params.forEach { p ->
            ParamSlider(p, f.values[p.key] ?: p.default, onFinished = vm::previewFilter) { v -> vm.setFilterValue(p.key, v) }
        }
    }
}

@Composable
fun ParamSlider(p: AdjustParam, value: Float, onFinished: (() -> Unit)? = null, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(p.label), Modifier.width(104.dp), style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = value.coerceIn(p.min, p.max),
            onValueChange = onChange,
            onValueChangeFinished = onFinished,
            valueRange = p.min..p.max,
            modifier = Modifier.weight(1f),
        )
        Text(format(value, p.max - p.min), Modifier.width(52.dp), style = MaterialTheme.typography.bodySmall)
    }
}

/** Whole numbers for wide ranges (−150…150), two decimals for narrow ones (gamma, offset). */
private fun format(v: Float, span: Float): String =
    if (span >= 20f) "%d".format(Locale.ROOT, v.toInt()) else "%.2f".format(Locale.ROOT, v)
