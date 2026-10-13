package ai.storyteller.photocraft.editor

import android.app.Application
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ai.storyteller.photocraft.R
import ai.storyteller.photocraft.engine.DocInfo
import ai.storyteller.photocraft.engine.MobileException
import ai.storyteller.photocraft.engine.PhotoSession
import ai.storyteller.photocraft.engine.engineVersion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

/** A message for the snackbar: a string resource (resolved by the UI) or engine text. */
sealed interface UiMessage {
    data class Res(@param:StringRes val id: Int, val args: List<Any> = emptyList()) : UiMessage
    data class Text(val text: String) : UiMessage
}

enum class Panel { None, Layers, Adjust, Filter, Tool }

/** A sharp render of the visible area when zoomed in ([PhotoSession.renderRegion]). */
data class RegionImage(val rect: RectF, val image: ImageBitmap, val revision: ULong)

/** Per-channel histograms of the preview (luminance, R, G, B), for Curves and Levels. */
class Histogram(val lum: IntArray, val red: IntArray, val green: IntArray, val blue: IntArray) {
    fun of(ch: ToneChannel) = when (ch) {
        ToneChannel.Rgb -> lum
        ToneChannel.Red -> red
        ToneChannel.Green -> green
        ToneChannel.Blue -> blue
    }
}

data class EditorState(
    val info: DocInfo? = null,
    val preview: ImageBitmap? = null,
    val region: RegionImage? = null,
    val doc: DocModel? = null,
    val selectionOverlay: ImageBitmap? = null,
    val thumbnails: Map<Long, ImageBitmap> = emptyMap(),
    val busy: Boolean = false,
    val tool: Tool = Tool.Move,
    val panel: Panel = Panel.None,
    val brush: BrushOptions = BrushOptions(),
    val selectMode: SelectMode = SelectMode.Replace,
    val wandTolerance: Float = 32f,
    val crop: CropBox? = null,
    val cropAspect: CropAspect = CropAspect.Free,
    val adjust: AdjustEdit? = null,
    val filter: FilterEdit? = null,
    val histogram: Histogram? = null,
    val message: UiMessage? = null,
) {
    val selectedLayer: LayerItem? get() = doc?.layers?.firstOrNull { it.selected }
}

/**
 * Owns the [PhotoSession]. Every engine call runs on [engine], a single background thread, so
 * calls never overlap and the UI thread never blocks on Rust.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditorViewModel(app: Application) : AndroidViewModel(app) {
    private val session = PhotoSession()
    private val engine = Dispatchers.Default.limitedParallelism(1)
    private val autosaveFile = File(app.filesDir, "autosave.pcraft")
    private val previewSide: UInt = app.resources.displayMetrics.let { maxOf(it.widthPixels, it.heightPixels) }
        .coerceIn(1024, 2048).toUInt()

    private val _state = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = _state.asStateFlow()

    /** Slider moves: conflated, so a fast drag renders only the latest values. */
    private val adjustChanges = Channel<Unit>(Channel.CONFLATED)
    private val regionRequests = Channel<Pair<RectF, Pair<Int, Int>>>(Channel.CONFLATED)
    private var renderedRevision: ULong? = null
    private var nonce = 0
    private var autosaveJob: Job? = null

    val version: String = engineVersion()

    init {
        viewModelScope.launch(engine) { for (unit in adjustChanges) pushAdjust() }
        viewModelScope.launch(engine) { for ((rect, size) in regionRequests) renderRegion(rect, size.first, size.second) }
        restoreAutosave()
    }

    // -- Files --------------------------------------------------------------------------------

    fun open(uri: Uri) = launchEngine {
        val resolver = getApplication<Application>().contentResolver
        val (name, size) = withContext(Dispatchers.IO) { displayNameAndSize(uri) }
        if (size != null && size > MAX_FILE_BYTES) {
            message(UiMessage.Res(R.string.error_too_big, listOf(MAX_FILE_BYTES / (1024 * 1024))))
            return@launchEngine
        }
        val bytes = withContext(Dispatchers.IO) { resolver.openInputStream(uri)?.use { it.readBytes() } }
        if (bytes == null) {
            message(UiMessage.Res(R.string.error_read))
            return@launchEngine
        }
        val opened = session.open(name, bytes)
        if (opened.warnings.isNotEmpty()) {
            message(UiMessage.Res(R.string.warnings, listOf(opened.warnings.size, opened.warnings.first())))
        }
        _state.update { it.copy(tool = Tool.Move, panel = Panel.None, crop = null, adjust = null, filter = null) }
        refresh()
    }

    fun newDocument(width: Int, height: Int) = launchEngine {
        session.newDocument(width.toUInt(), height.toUInt())
        refresh()
    }

    fun export(uri: Uri, format: ExportFormat) = launchEngine {
        val encoded = session.export(format.extension, JPEG_QUALITY)
        withContext(Dispatchers.IO) {
            getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { it.write(encoded.bytes) }
                ?: throw MobileException.Io("cannot write $uri")
        }
        message(
            if (encoded.warnings.isEmpty()) UiMessage.Res(R.string.saved)
            else UiMessage.Res(R.string.warnings, listOf(encoded.warnings.size, encoded.warnings.first())),
        )
        refresh()
    }

    fun closeDocument() = launchEngine {
        session.closeActive()
        withContext(Dispatchers.IO) { autosaveFile.delete() }
        _state.update { it.copy(tool = Tool.Move, panel = Panel.None, crop = null, adjust = null, filter = null) }
        refresh()
    }

    /** Crash and process-death recovery: the open document as `.pcraft` in app storage. */
    fun autosave() {
        autosaveJob?.cancel()
        viewModelScope.launch(engine + NonCancellable) { writeAutosave() }
    }

    private fun writeAutosave() {
        runCatching {
            if (session.documentCount() == 0u) {
                autosaveFile.delete()
                return
            }
            val bytes = session.export("pcraft", null).bytes
            val tmp = File(autosaveFile.parentFile, autosaveFile.name + ".tmp")
            tmp.writeBytes(bytes)
            tmp.renameTo(autosaveFile)
        }
    }

    /** Autosave a few seconds after the last edit, so even a killed app loses little. */
    private fun scheduleAutosave() {
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch(engine) {
            delay(AUTOSAVE_DELAY_MS)
            writeAutosave()
        }
    }

    private fun restoreAutosave() = launchEngine {
        val bytes = withContext(Dispatchers.IO) { autosaveFile.takeIf { it.isFile }?.readBytes() } ?: return@launchEngine
        session.open(autosaveFile.name, bytes)
        message(UiMessage.Res(R.string.restored))
        refresh()
    }

    // -- Commands and history -----------------------------------------------------------------

    /** Run an engine command by id (see `crates/mobile-ffi` › `CORE_COMMANDS`). */
    fun run(id: String, params: JSONObject = JSONObject()) = launchEngine {
        session.execute(id, params.toString())
        refresh()
    }

    fun undo() = launchEngine { session.undo(); refresh() }

    fun redo() = launchEngine { session.redo(); refresh() }

    // -- Tools --------------------------------------------------------------------------------

    fun selectTool(tool: Tool) {
        val s = _state.value
        if (s.adjust != null || s.filter != null) return
        val info = s.info
        _state.update {
            it.copy(
                tool = tool,
                panel = if (tool == Tool.Move) Panel.None else Panel.Tool,
                crop = if (tool == Tool.Crop && info != null) {
                    CropBox(0f, 0f, info.width.toFloat(), info.height.toFloat())
                } else null,
                cropAspect = if (tool == Tool.Crop) CropAspect.Free else it.cropAspect,
            )
        }
    }

    fun setBrush(brush: BrushOptions) = _state.update { it.copy(brush = brush) }

    fun setSelectMode(mode: SelectMode) = _state.update { it.copy(selectMode = mode) }

    fun setWandTolerance(t: Float) = _state.update { it.copy(wandTolerance = t) }

    /** A finished brush or eraser stroke, in document pixels: `[x, y, pressure]` per point. */
    fun paintStroke(points: List<FloatArray>) {
        if (points.isEmpty()) return
        val s = _state.value
        val arr = JSONArray()
        points.forEach { p -> arr.put(JSONArray().put(p[0].toDouble()).put(p[1].toDouble()).put(p[2].toDouble())) }
        val params = JSONObject()
            .put("points", arr)
            .put("size", s.brush.size.toDouble())
            .put("hardness", s.brush.hardness.toDouble())
            .put("opacity", s.brush.opacity.toDouble())
            .put("color", hex(s.brush.color))
            .put("erase", s.tool == Tool.Eraser)
        run("paint.stroke", params)
    }

    /** Marquee or elliptical marquee, in document pixels. */
    fun selectRect(rect: RectF, ellipse: Boolean) {
        val x = rect.left.roundToInt()
        val y = rect.top.roundToInt()
        val w = rect.width().roundToInt()
        val h = rect.height().roundToInt()
        if (w < 1 || h < 1) return
        run(
            "select.rect",
            JSONObject().put("x", x).put("y", y).put("width", w).put("height", h)
                .put("ellipse", ellipse).put("mode", _state.value.selectMode.id),
        )
    }

    fun selectLasso(points: List<FloatArray>) {
        if (points.size < 3) return
        val arr = JSONArray()
        points.forEach { arr.put(JSONArray().put(it[0].toDouble()).put(it[1].toDouble())) }
        run("select.lasso", JSONObject().put("points", arr).put("mode", _state.value.selectMode.id))
    }

    fun magicWand(x: Float, y: Float) {
        val s = _state.value
        run(
            "select.magicWand",
            JSONObject().put("x", x.roundToInt()).put("y", y.roundToInt())
                .put("tolerance", s.wandTolerance.roundToInt()).put("mode", s.selectMode.id),
        )
    }

    fun selectSubject() = run("select.subject", JSONObject().put("mode", _state.value.selectMode.id))

    /** Eyedropper: the composite colour under the finger becomes the brush colour. */
    fun sampleColor(x: Float, y: Float) = launchEngine {
        val rgba = JSONArray(session.execute("document.sampleColor", JSONObject().put("x", x.toDouble()).put("y", y.toDouble()).put("size", 3).toString()))
        fun c(i: Int) = (rgba.optDouble(i, 0.0).coerceIn(0.0, 1.0) * 255).roundToInt()
        val argb = (0xFF shl 24) or (c(0) shl 16) or (c(1) shl 8) or c(2)
        _state.update { it.copy(brush = it.brush.copy(color = argb)) }
    }

    // -- Crop ---------------------------------------------------------------------------------

    fun setCrop(box: CropBox) = _state.update { it.copy(crop = box) }

    fun setCropAspect(aspect: CropAspect) {
        val s = _state.value
        val info = s.info ?: return
        val ratio = when (aspect) {
            CropAspect.Free -> null
            CropAspect.Original -> info.width.toFloat() / info.height.toFloat()
            else -> aspect.ratio
        }
        val w = info.width.toFloat()
        val h = info.height.toFloat()
        val box = if (ratio == null) CropBox(0f, 0f, w, h) else {
            // The largest box of that ratio, centred.
            val bw = minOf(w, h * ratio)
            val bh = bw / ratio
            CropBox((w - bw) / 2, (h - bh) / 2, (w + bw) / 2, (h + bh) / 2)
        }
        _state.update { it.copy(cropAspect = aspect, crop = box) }
    }

    fun cropRatio(): Float? {
        val s = _state.value
        val info = s.info ?: return null
        return when (s.cropAspect) {
            CropAspect.Free -> null
            CropAspect.Original -> info.width.toFloat() / info.height.toFloat()
            else -> s.cropAspect.ratio
        }
    }

    fun applyCrop() {
        val box = _state.value.crop ?: return
        val params = JSONObject()
            .put("x", box.left.roundToInt()).put("y", box.top.roundToInt())
            .put("width", box.width.roundToInt().coerceAtLeast(1)).put("height", box.height.roundToInt().coerceAtLeast(1))
        _state.update { it.copy(tool = Tool.Move, panel = Panel.None, crop = null) }
        run("image.crop", params)
    }

    // -- Panels -------------------------------------------------------------------------------

    fun showPanel(panel: Panel) {
        if (_state.value.adjust != null || _state.value.filter != null) return
        _state.update { it.copy(panel = if (it.panel == panel) Panel.None else panel, tool = if (panel == Panel.Layers) Tool.Move else it.tool) }
        if (panel == Panel.Layers) launchEngine { loadThumbnails() }
    }

    // -- Layers -------------------------------------------------------------------------------

    fun selectLayer(id: Long) = run("layer.select", JSONObject().put("layer", id))

    fun setLayerVisible(id: Long, visible: Boolean) = run("layer.setProps", JSONObject().put("layer", id).put("visible", visible))

    fun setLayerOpacity(id: Long, opacity: Float) =
        run("layer.setProps", JSONObject().put("layer", id).put("opacity", opacity.toDouble()).put("coalesce", "opacity-$id"))

    fun setLayerBlend(id: Long, blend: String) = run("layer.setProps", JSONObject().put("layer", id).put("blend", blend))

    fun layerCommand(id: String, layer: Long) = run(id, JSONObject().put("layer", layer))

    fun newLayer() = run("layer.new.layer")

    /** Open an existing adjustment layer's settings in the Properties panel. */
    fun editAdjustmentLayer(layer: LayerItem) = launchEngine {
        val edit = AdjustParse.edit(layer, ++nonce) ?: return@launchEngine
        session.execute("layer.select", JSONObject().put("layer", layer.id).toString())
        _state.update { it.copy(adjust = edit, panel = Panel.Adjust, tool = Tool.Move, histogram = histogramIfTone(edit, it)) }
        refresh()
    }

    // -- Adjustment layers --------------------------------------------------------------------

    fun startAdjust(kind: AdjustKind) = launchEngine {
        val layer = newAdjustmentLayer(kind.id)
        if (kind.params.isNotEmpty() && layer >= 0) {
            _state.update { it.copy(adjust = SliderEdit(kind, layer, kind.defaults(), isNew = true, nonce = ++nonce), panel = Panel.Adjust, tool = Tool.Move) }
        }
        refresh()
    }

    fun startCurves() = launchEngine {
        val layer = newAdjustmentLayer("curves")
        if (layer >= 0) {
            val edit = CurvesEdit(layer, ToneChannel.Rgb, ToneChannel.entries.associateWith { IDENTITY_CURVE }, isNew = true, nonce = ++nonce)
            _state.update { it.copy(adjust = edit, panel = Panel.Adjust, tool = Tool.Move, histogram = histogramIfTone(edit, it)) }
        }
        refresh()
    }

    fun startLevels() = launchEngine {
        val layer = newAdjustmentLayer("levels")
        if (layer >= 0) {
            val edit = LevelsEdit(layer, ToneChannel.Rgb, ToneChannel.entries.associateWith { LevelsValues() }, isNew = true, nonce = ++nonce)
            _state.update { it.copy(adjust = edit, panel = Panel.Adjust, tool = Tool.Move, histogram = histogramIfTone(edit, it)) }
        }
        refresh()
    }

    private fun newAdjustmentLayer(kind: String): Long =
        JSONObject(session.execute("layer.newAdjustmentLayer.$kind", "{}")).optLong("layer", -1L)

    /** Any change to the open adjustment: slider values, curve points, levels, channel. */
    fun updateAdjust(change: (AdjustEdit) -> AdjustEdit) {
        _state.update { s -> s.copy(adjust = s.adjust?.let(change)) }
        adjustChanges.trySend(Unit)
    }

    /** Switch the Curves/Levels channel shown (no engine change). */
    fun setToneChannel(ch: ToneChannel) = _state.update { s ->
        s.copy(
            adjust = when (val a = s.adjust) {
                is CurvesEdit -> a.copy(channel = ch)
                is LevelsEdit -> a.copy(channel = ch)
                else -> a
            },
        )
    }

    fun applyAdjust() = launchEngine { _state.update { it.copy(adjust = null, panel = Panel.None, histogram = null) } }

    /** Undo the session's edits, and the layer when the session created it. */
    fun cancelAdjust() = launchEngine {
        val a = _state.value.adjust ?: return@launchEngine
        _state.update { it.copy(adjust = null, panel = Panel.None, histogram = null) }
        repeat(a.undoSteps) { session.undo() }
        refresh()
    }

    private suspend fun pushAdjust() {
        val a = _state.value.adjust ?: return
        val params = a.params().put("layer", a.layerId).put("coalesce", "adjust-${a.layerId}-${a.nonce}")
        guard {
            session.execute("layer.setAdjustment", params.toString())
            // The edit is in history now: Cancel undoes it along with the layer.
            _state.update { s -> s.copy(adjust = s.adjust?.takeIf { it.nonce == a.nonce }?.markEdited() ?: s.adjust) }
            refresh()
        }
    }

    // -- Filters ------------------------------------------------------------------------------

    fun startFilter(kind: FilterKind) {
        if (_state.value.adjust != null) return
        _state.update { it.copy(filter = FilterEdit(kind, kind.defaults()), panel = Panel.Filter, tool = Tool.Move) }
        previewFilter()
    }

    fun setFilterValue(key: String, value: Float) =
        _state.update { s -> s.copy(filter = s.filter?.let { it.copy(values = it.values + (key to value)) }) }

    /** Re-run the filter with the current values over the unfiltered pixels (undo the last preview). */
    fun previewFilter() = launchEngine {
        val f = _state.value.filter ?: return@launchEngine
        if (f.applied) session.undo()
        _state.update { s -> s.copy(filter = s.filter?.copy(applied = false)) }
        val params = JSONObject().also { o -> f.values.forEach { (k, v) -> o.put(k, v.toDouble()) } }
        session.execute(f.kind.id, params.toString())
        _state.update { s -> s.copy(filter = s.filter?.copy(applied = true)) }
        refresh()
    }

    fun applyFilter() = launchEngine { _state.update { it.copy(filter = null, panel = Panel.None) } }

    fun cancelFilter() = launchEngine {
        val f = _state.value.filter ?: return@launchEngine
        _state.update { it.copy(filter = null, panel = Panel.None) }
        if (f.applied) session.undo()
        refresh()
    }

    // -- View ---------------------------------------------------------------------------------

    /** The canvas settled on a zoomed-in view: render that area at screen resolution. */
    fun requestRegion(rect: RectF, outWidth: Int, outHeight: Int) {
        regionRequests.trySend(rect to (outWidth to outHeight))
    }

    fun clearRegion() = _state.update { it.copy(region = null) }

    private fun renderRegion(rect: RectF, outW: Int, outH: Int) {
        val info = _state.value.info ?: return
        val x = rect.left.toInt().coerceAtLeast(0)
        val y = rect.top.toInt().coerceAtLeast(0)
        val w = (rect.right.toInt() + 1).coerceAtMost(info.width.toInt()) - x
        val h = (rect.bottom.toInt() + 1).coerceAtMost(info.height.toInt()) - y
        if (w <= 0 || h <= 0 || outW <= 0 || outH <= 0) return
        runCatching {
            val img = session.renderRegion(x, y, w.toUInt(), h.toUInt(), outW.toUInt(), outH.toUInt())
            val region = RegionImage(RectF(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat()), img.toBitmap().asImageBitmap(), info.revision)
            _state.update { it.copy(region = region) }
        }
    }

    // -- Plumbing -----------------------------------------------------------------------------

    fun messageShown() = _state.update { it.copy(message = null) }

    private fun launchEngine(block: suspend CoroutineScope.() -> Unit) = viewModelScope.launch(engine) {
        _state.update { it.copy(busy = true) }
        guard { block() }
        _state.update { it.copy(busy = false) }
    }

    private suspend fun guard(block: suspend CoroutineScope.() -> Unit) {
        try {
            withContext(engine) { block() }
        } catch (e: MobileException) {
            message(UiMessage.Text(e.message ?: e.toString()))
        } catch (e: Exception) {
            // UniFFI's InternalException (a caught Rust panic) and I/O failures: report, keep going.
            message(UiMessage.Text(e.message ?: e.toString()))
        }
    }

    /** Re-read the document and re-render only when its revision moved. */
    private fun refresh() {
        val info = if (session.documentCount() == 0u) null else session.info()
        if (info == null) {
            renderedRevision = null
            _state.update { it.copy(info = null, preview = null, region = null, doc = null, selectionOverlay = null, thumbnails = emptyMap()) }
            return
        }
        val changed = info.revision != renderedRevision || _state.value.preview == null
        val preview = if (changed) session.render(previewSide).toBitmap().asImageBitmap() else _state.value.preview
        val doc = DocModel.parse(session.execute("document.inspect", "{}"))
        val overlay = if (!doc.hasSelection) null
        else if (changed || _state.value.selectionOverlay == null) session.selectionOverlay(previewSide)?.toBitmap()?.asImageBitmap()
        else _state.value.selectionOverlay
        if (changed && renderedRevision != null && info.dirty) scheduleAutosave()
        renderedRevision = info.revision
        _state.update {
            it.copy(
                info = info,
                preview = preview,
                region = it.region?.takeIf { r -> r.revision == info.revision },
                doc = doc,
                selectionOverlay = overlay,
            )
        }
        if (changed && _state.value.panel == Panel.Layers) loadThumbnails()
    }

    private fun loadThumbnails() {
        val doc = _state.value.doc ?: return
        val thumbs = doc.layers.filter { !it.isAdjustment && it.kind != "Group" }.mapNotNull { l ->
            runCatching { l.id to session.layerThumbnail(l.id.toULong(), THUMB_SIDE).toBitmap().asImageBitmap() }.getOrNull()
        }.toMap()
        _state.update { it.copy(thumbnails = thumbs) }
    }

    private fun histogramIfTone(edit: AdjustEdit, s: EditorState): Histogram? =
        if (edit is CurvesEdit || edit is LevelsEdit) s.preview?.let { histogram(it.asAndroidBitmap()) } else null

    private fun histogram(bitmap: Bitmap): Histogram {
        val lum = IntArray(256)
        val r = IntArray(256)
        val g = IntArray(256)
        val b = IntArray(256)
        val w = bitmap.width
        val h = bitmap.height
        val row = IntArray(w)
        // Every other row and column: plenty for a 256-bin histogram.
        for (y in 0 until h step 2) {
            bitmap.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w step 2) {
                val c = row[x]
                if ((c ushr 24) == 0) continue
                val rr = (c shr 16) and 0xFF
                val gg = (c shr 8) and 0xFF
                val bb = c and 0xFF
                r[rr]++
                g[gg]++
                b[bb]++
                lum[((rr * 299 + gg * 587 + bb * 114) / 1000).coerceIn(0, 255)]++
            }
        }
        return Histogram(lum, r, g, b)
    }

    private fun message(m: UiMessage) = _state.update { it.copy(message = m) }

    private fun displayNameAndSize(uri: Uri): Pair<String, Long?> {
        val resolver = getApplication<Application>().contentResolver
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val name = c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let(c::getString)
                val size = c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let(c::getLong)
                return (name ?: uri.lastPathSegment ?: "image") to size
            }
        }
        return (uri.lastPathSegment ?: "image") to null
    }

    private fun hex(argb: Int) = "#%06x".format(argb and 0xFFFFFF)

    private companion object {
        const val MAX_FILE_BYTES = 300L * 1024 * 1024
        const val AUTOSAVE_DELAY_MS = 4_000L
        val JPEG_QUALITY: UByte = 92u
        val THUMB_SIDE: UInt = 96u
    }
}
