package ai.storyteller.photocraft.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.storyteller.photocraft.R
import ai.storyteller.photocraft.editor.BLEND_MODES
import ai.storyteller.photocraft.editor.EditorState
import ai.storyteller.photocraft.editor.EditorViewModel
import ai.storyteller.photocraft.editor.LayerItem
import kotlin.math.roundToInt

/** Window › Layers: the stack (top first), and the selected layer's opacity, blend mode and actions. */
@Composable
fun LayersPanel(state: EditorState, vm: EditorViewModel) {
    val doc = state.doc ?: return
    val selected = state.selectedLayer
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.layers), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = vm::newLayer) { Icon(Icons.Filled.Add, stringResource(R.string.new_layer)) }
            }
            if (selected != null) SelectedLayerControls(selected, vm)
            LazyColumn(Modifier.heightIn(max = 240.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(doc.layers, key = { it.id }) { layer ->
                    LayerRow(layer, state, vm)
                }
            }
        }
    }
}

@Composable
private fun SelectedLayerControls(layer: LayerItem, vm: EditorViewModel) {
    // The slider shows the finger's value while dragging, the document's otherwise.
    var dragging by remember(layer.id) { mutableStateOf(false) }
    var opacity by remember(layer.id) { mutableFloatStateOf(layer.opacity) }
    if (!dragging) opacity = layer.opacity
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.opacity), style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(64.dp))
        Slider(
            value = opacity,
            onValueChange = { dragging = true; opacity = it },
            onValueChangeFinished = { dragging = false; vm.setLayerOpacity(layer.id, opacity) },
            modifier = Modifier.weight(1f),
        )
        Text("${(opacity * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(40.dp))
        BlendMenu(layer, vm)
    }
    Row {
        IconButton(onClick = { vm.layerCommand("layer.arrange.bringForward", layer.id) }) { Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.move_up)) }
        IconButton(onClick = { vm.layerCommand("layer.arrange.sendBackward", layer.id) }) { Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.move_down)) }
        IconButton(onClick = { vm.layerCommand("layer.duplicate", layer.id) }) { Icon(Icons.Filled.ContentCopy, stringResource(R.string.duplicate)) }
        IconButton(onClick = { vm.layerCommand("layer.mergeDown", layer.id) }) { Icon(Icons.AutoMirrored.Filled.MergeType, stringResource(R.string.merge_down)) }
        IconButton(onClick = { vm.layerCommand("layer.delete", layer.id) }, enabled = !layer.isBackground) {
            Icon(Icons.Filled.Delete, stringResource(R.string.delete))
        }
        if (layer.isAdjustment) {
            IconButton(onClick = { vm.editAdjustmentLayer(layer) }) { Icon(Icons.Filled.Edit, stringResource(R.string.edit_adjustment)) }
        }
    }
}

@Composable
private fun BlendMenu(layer: LayerItem, vm: EditorViewModel) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text(blendLabel(layer.blend), maxLines = 1) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            BLEND_MODES.forEach { m ->
                DropdownMenuItem(text = { Text(blendLabel(m)) }, onClick = { open = false; vm.setLayerBlend(layer.id, m) })
            }
        }
    }
}

/** "ColorBurn" → "Color Burn". */
private fun blendLabel(m: String) = m.replace(Regex("(?<=[a-z])(?=[A-Z])"), " ")

@Composable
private fun LayerRow(layer: LayerItem, state: EditorState, vm: EditorViewModel) {
    val bg = if (layer.selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f) else Color.Transparent
    Row(
        Modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(6.dp))
            .clickable { vm.selectLayer(layer.id) }
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { vm.setLayerVisible(layer.id, !layer.visible) }, modifier = Modifier.size(36.dp)) {
            Icon(
                if (layer.visible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                stringResource(R.string.visibility),
                tint = if (layer.visible) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(
            Modifier
                .size(40.dp)
                .background(Color(0xFF3A3A3A), RoundedCornerShape(4.dp))
                .border(1.dp, Color(0xFF555555), RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center,
        ) {
            val thumb = state.thumbnails[layer.id]
            when {
                layer.isAdjustment -> Icon(Icons.Filled.Tune, null, tint = MaterialTheme.colorScheme.secondary)
                thumb != null -> Image(thumb, null, Modifier.size(38.dp), contentScale = ContentScale.Fit)
            }
        }
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(layer.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            val detail = buildString {
                if (layer.blend != "Normal") append(blendLabel(layer.blend)).append(" · ")
                append("${(layer.opacity * 100).roundToInt()}%")
            }
            Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (layer.isAdjustment) {
            IconButton(onClick = { vm.editAdjustmentLayer(layer) }) { Icon(Icons.Filled.Edit, stringResource(R.string.edit_adjustment)) }
        }
    }
}
