package ai.storyteller.photocraft.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ai.storyteller.photocraft.R
import ai.storyteller.photocraft.editor.CropAspect
import ai.storyteller.photocraft.editor.EditorState
import ai.storyteller.photocraft.editor.EditorViewModel
import ai.storyteller.photocraft.editor.SelectMode
import ai.storyteller.photocraft.editor.Tool
import kotlin.math.roundToInt

private val SWATCHES = listOf(
    0xFFFFFFFF, 0xFF000000, 0xFF808080, 0xFFE53935, 0xFFFB8C00, 0xFFFDD835,
    0xFF43A047, 0xFF00ACC1, 0xFF1E88E5, 0xFF5E35B1, 0xFFD81B60, 0xFF6D4C41,
).map { it.toInt() }

/** The options bar of the active tool (Photoshop's Options bar, docked at the bottom). */
@Composable
fun ToolPanel(state: EditorState, vm: EditorViewModel) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            Text(stringResource(state.tool.label), style = MaterialTheme.typography.titleSmall)
            when {
                state.tool.isPaint -> BrushOptionsRow(state, vm)
                state.tool.isSelection -> SelectionOptions(state, vm)
                state.tool == Tool.Crop -> CropOptions(state, vm)
                state.tool == Tool.Eyedropper -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Swatch(state.brush.color, selected = true) {}
                        Text(stringResource(R.string.eyedropper_hint), Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun BrushOptionsRow(state: EditorState, vm: EditorViewModel) {
    val b = state.brush
    LabeledSlider(R.string.p_size, b.size, 1f, 400f, "${b.size.roundToInt()} px") { vm.setBrush(b.copy(size = it)) }
    LabeledSlider(R.string.p_hardness, b.hardness, 0f, 1f, "${(b.hardness * 100).roundToInt()}%") { vm.setBrush(b.copy(hardness = it)) }
    LabeledSlider(R.string.opacity, b.opacity, 0.01f, 1f, "${(b.opacity * 100).roundToInt()}%") { vm.setBrush(b.copy(opacity = it)) }
    if (state.tool == Tool.Brush) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            SWATCHES.forEach { c -> Swatch(c, selected = c == b.color) { vm.setBrush(b.copy(color = c)) } }
            if (b.color !in SWATCHES) Swatch(b.color, selected = true) {}
        }
    }
}

@Composable
private fun SelectionOptions(state: EditorState, vm: EditorViewModel) {
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        SelectMode.entries.forEach { m ->
            FilterChip(
                selected = state.selectMode == m,
                onClick = { vm.setSelectMode(m) },
                label = { Text(stringResource(m.label)) },
                modifier = Modifier.padding(end = 6.dp),
            )
        }
    }
    if (state.tool == Tool.Wand) {
        LabeledSlider(R.string.p_tolerance, state.wandTolerance, 0f, 255f, state.wandTolerance.roundToInt().toString()) { vm.setWandTolerance(it) }
    }
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        AssistChip(onClick = vm::selectSubject, label = { Text(stringResource(R.string.select_subject)) }, modifier = Modifier.padding(end = 6.dp))
        AssistChip(onClick = { vm.run("select.all") }, label = { Text(stringResource(R.string.select_all)) }, modifier = Modifier.padding(end = 6.dp))
        AssistChip(
            onClick = { vm.run("select.inverse") },
            enabled = state.doc?.hasSelection == true,
            label = { Text(stringResource(R.string.select_inverse)) },
            modifier = Modifier.padding(end = 6.dp),
        )
        AssistChip(
            onClick = { vm.run("select.deselect") },
            enabled = state.doc?.hasSelection == true,
            label = { Text(stringResource(R.string.deselect)) },
        )
    }
}

@Composable
private fun CropOptions(state: EditorState, vm: EditorViewModel) {
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        CropAspect.entries.forEach { a ->
            FilterChip(
                selected = state.cropAspect == a,
                onClick = { vm.setCropAspect(a) },
                label = { Text(stringResource(a.label)) },
                modifier = Modifier.padding(end = 6.dp),
            )
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val box = state.crop
        if (box != null) {
            Text("${box.width.roundToInt()} × ${box.height.roundToInt()} px", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        } else Box(Modifier.weight(1f))
        TextButton(onClick = { vm.selectTool(Tool.Move) }) { Text(stringResource(R.string.cancel)) }
        Button(onClick = vm::applyCrop) { Text(stringResource(R.string.crop_apply)) }
    }
}

@Composable
private fun LabeledSlider(label: Int, value: Float, min: Float, max: Float, text: String, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), Modifier.width(88.dp), style = MaterialTheme.typography.bodySmall)
        Slider(value = value.coerceIn(min, max), onValueChange = onChange, valueRange = min..max, modifier = Modifier.weight(1f))
        Text(text, Modifier.width(56.dp), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Swatch(argb: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(end = 8.dp)
            .size(30.dp)
            .background(Color(argb), CircleShape)
            .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else Color(0xFF666666), CircleShape)
            .clickable(onClick = onClick),
    )
}
