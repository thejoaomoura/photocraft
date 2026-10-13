package ai.storyteller.photocraft.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.storyteller.photocraft.R
import ai.storyteller.photocraft.editor.AdjustKind
import ai.storyteller.photocraft.editor.EditorState
import ai.storyteller.photocraft.editor.EditorViewModel
import ai.storyteller.photocraft.editor.ExportFormat
import ai.storyteller.photocraft.editor.FilterKind
import ai.storyteller.photocraft.editor.Panel
import ai.storyteller.photocraft.editor.Tool
import ai.storyteller.photocraft.editor.UiMessage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(vm: EditorViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::open)
    }
    val launchOpen = { openLauncher.launch(arrayOf("image/*", "application/octet-stream")) }

    // One SAF "Create document" launcher per format: the MIME type is fixed at registration.
    val exportLaunchers = ExportFormat.entries.associateWith { format ->
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(format.mime)) { uri ->
            if (uri != null) vm.export(uri, format)
        }
    }
    fun export(format: ExportFormat) {
        val base = state.info?.name?.substringBeforeLast('.')?.ifBlank { null } ?: context.getString(R.string.untitled)
        exportLaunchers.getValue(format).launch("$base.${format.extension}")
    }

    LaunchedEffect(state.message) {
        val m = state.message ?: return@LaunchedEffect
        val text = when (m) {
            is UiMessage.Res -> context.getString(m.id, *m.args.toTypedArray())
            is UiMessage.Text -> m.text
        }
        snackbar.showSnackbar(text)
        vm.messageShown()
    }

    // Back closes what is open before it leaves the app.
    BackHandler(enabled = state.adjust != null || state.filter != null || state.panel != Panel.None || state.tool != Tool.Move) {
        when {
            state.adjust != null -> vm.cancelAdjust()
            state.filter != null -> vm.cancelFilter()
            state.panel == Panel.Layers -> vm.showPanel(Panel.Layers)
            else -> vm.selectTool(Tool.Move)
        }
    }

    val info = state.info
    val editing = state.adjust != null || state.filter != null
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                title = {
                    Column {
                        Text(
                            info?.name ?: stringResource(R.string.app_name),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (info != null) {
                            Text(
                                "${info.width} × ${info.height} · " + pluralStringResource(R.plurals.layers_count, info.layerCount.toInt(), info.layerCount.toInt()),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = vm::undo, enabled = info?.canUndo == true && !editing) {
                        Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.undo))
                    }
                    IconButton(onClick = vm::redo, enabled = info?.canRedo == true && !editing) {
                        Icon(Icons.AutoMirrored.Filled.Redo, stringResource(R.string.redo))
                    }
                    if (info == null) {
                        IconButton(onClick = launchOpen) { Icon(Icons.Filled.FolderOpen, stringResource(R.string.open)) }
                    } else {
                        Menu(Icons.Filled.Save, stringResource(R.string.export), enabled = !editing) { close ->
                            ExportFormat.entries.forEach { f ->
                                DropdownMenuItem(text = { Text(stringResource(f.label)) }, onClick = { close(); export(f) })
                            }
                        }
                        Menu(Icons.Filled.MoreVert, stringResource(R.string.more), enabled = !editing) { close ->
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.open)) },
                                leadingIcon = { Icon(Icons.Filled.FolderOpen, null) },
                                onClick = { close(); launchOpen() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.flatten)) },
                                enabled = info.layerCount > 1u,
                                onClick = { close(); vm.run("layer.flattenImage") },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.close_document)) },
                                leadingIcon = { Icon(Icons.Filled.Close, null) },
                                onClick = { close(); vm.closeDocument() },
                            )
                            HorizontalDivider()
                            Text(
                                stringResource(R.string.engine_version, vm.version),
                                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (info != null) {
                Column {
                    when {
                        state.adjust != null -> AdjustPanel(state, vm)
                        state.filter != null -> FilterPanel(state, vm)
                        state.panel == Panel.Layers -> LayersPanel(state, vm)
                        state.panel == Panel.Tool -> ToolPanel(state, vm)
                    }
                    if (!editing) ToolStrip(state, vm)
                }
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background),
        ) {
            if (info != null) {
                CanvasView(state, vm, Modifier.padding(4.dp))
            } else if (!state.busy) {
                EmptyState(onOpen = launchOpen, onNew = { vm.newDocument(2048, 1536) })
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
    }
}

/** The toolbar: tools on the left, then Rotate, Adjustments, Filters and Layers. */
@Composable
private fun ToolStrip(state: EditorState, vm: EditorViewModel) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf(
                Tool.Move to Icons.Filled.PanTool,
                Tool.Brush to Icons.Filled.Brush,
                Tool.Eraser to Icons.Filled.CleaningServices,
                Tool.Marquee to Icons.Filled.CropSquare,
                Tool.Ellipse to Icons.Filled.RadioButtonUnchecked,
                Tool.Lasso to Icons.Filled.Gesture,
                Tool.Wand to Icons.Filled.AutoFixHigh,
                Tool.Eyedropper to Icons.Filled.Colorize,
                Tool.Crop to Icons.Filled.Crop,
            ).forEach { (tool, icon) -> ToolButton(icon, stringResource(tool.label), state.tool == tool) { vm.selectTool(tool) } }

            Spacer(Modifier.width(8.dp))
            Menu(Icons.AutoMirrored.Filled.RotateRight, stringResource(R.string.rotate)) { close ->
                listOf(
                    R.string.rotate_cw to "image.imageRotation.90cw",
                    R.string.rotate_ccw to "image.imageRotation.90ccw",
                    R.string.rotate_180 to "image.imageRotation.180",
                    R.string.flip_h to "image.imageRotation.flipCanvasHorizontal",
                    R.string.flip_v to "image.imageRotation.flipCanvasVertical",
                ).forEach { (label, id) ->
                    DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { close(); vm.run(id) })
                }
            }
            Menu(Icons.Filled.Tune, stringResource(R.string.adjust)) { close ->
                DropdownMenuItem(text = { Text(stringResource(R.string.adj_curves)) }, onClick = { close(); vm.startCurves() })
                DropdownMenuItem(text = { Text(stringResource(R.string.adj_levels)) }, onClick = { close(); vm.startLevels() })
                AdjustKind.entries.forEach { k ->
                    DropdownMenuItem(text = { Text(stringResource(k.label)) }, onClick = { close(); vm.startAdjust(k) })
                }
            }
            Menu(Icons.Filled.BlurOn, stringResource(R.string.filters)) { close ->
                FilterKind.entries.forEach { f ->
                    DropdownMenuItem(text = { Text(stringResource(f.label)) }, onClick = { close(); vm.startFilter(f) })
                }
            }
            ToolButton(Icons.Filled.Layers, stringResource(R.string.layers), state.panel == Panel.Layers) { vm.showPanel(Panel.Layers) }
        }
    }
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else androidx.compose.ui.graphics.Color.Transparent
    Box(Modifier.background(bg, RoundedCornerShape(10.dp))) {
        IconButton(onClick = onClick) {
            Icon(icon, label, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun Menu(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    items: @Composable (close: () -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, enabled = enabled) { Icon(icon, label) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) { items { open = false } }
    }
}

@Composable
private fun EmptyState(onOpen: () -> Unit, onNew: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.empty_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onOpen) {
            Icon(Icons.Filled.FolderOpen, null)
            Text(stringResource(R.string.open_photo), Modifier.padding(start = 8.dp))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onNew) {
            Icon(Icons.Filled.Add, null)
            Text(
                stringResource(R.string.new_document) + " · " + stringResource(R.string.new_document_size, 2048, 1536),
                Modifier.padding(start = 8.dp),
            )
        }
    }
}
