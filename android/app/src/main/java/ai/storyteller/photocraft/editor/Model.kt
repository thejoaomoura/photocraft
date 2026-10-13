package ai.storyteller.photocraft.editor

import androidx.annotation.StringRes
import ai.storyteller.photocraft.R
import org.json.JSONArray
import org.json.JSONObject

/** A layer as `document.inspect` reports it (top of the stack first). */
data class LayerItem(
    val id: Long,
    val name: String,
    val kind: String,
    val visible: Boolean,
    val opacity: Float,
    val blend: String,
    val selected: Boolean,
    val isBackground: Boolean,
    /** `{"Curves": {...}}` and the like for adjustment layers. */
    val adjustment: JSONObject?,
) {
    val isAdjustment get() = kind == "Adjustment"
    val adjustmentKind: String? get() = adjustment?.keys()?.asSequence()?.firstOrNull()
}

/** What the editor needs from `document.inspect`. */
data class DocModel(
    val layers: List<LayerItem>,
    val hasSelection: Boolean,
    val selectionBounds: IntArray?,
) {
    companion object {
        fun parse(json: String): DocModel {
            val o = JSONObject(json)
            val arr = o.optJSONArray("layers") ?: JSONArray()
            val layers = (0 until arr.length()).mapNotNull { i ->
                val l = arr.optJSONObject(i) ?: return@mapNotNull null
                LayerItem(
                    id = l.optLong("id"),
                    name = l.optString("name"),
                    kind = l.optString("kind"),
                    visible = l.optBoolean("visible", true),
                    opacity = l.optDouble("opacity", 1.0).toFloat(),
                    blend = l.optString("blend", "Normal"),
                    selected = l.optBoolean("selected"),
                    isBackground = i == arr.length() - 1 && l.optString("name") == "Background",
                    adjustment = l.optJSONObject("adjustment"),
                )
            }
            val b = o.optJSONArray("selectionBounds")
            val bounds = if (b != null && b.length() == 4) IntArray(4) { b.optInt(it) } else null
            return DocModel(layers, o.optBoolean("hasSelection"), bounds)
        }
    }
}

enum class Tool(@param:StringRes val label: Int) {
    Move(R.string.tool_move),
    Brush(R.string.tool_brush),
    Eraser(R.string.tool_eraser),
    Crop(R.string.tool_crop),
    Marquee(R.string.tool_marquee),
    Ellipse(R.string.tool_ellipse),
    Lasso(R.string.tool_lasso),
    Wand(R.string.tool_wand),
    Eyedropper(R.string.tool_eyedropper);

    val isSelection get() = this == Marquee || this == Ellipse || this == Lasso || this == Wand
    val isPaint get() = this == Brush || this == Eraser
}

enum class SelectMode(val id: String, @param:StringRes val label: Int) {
    Replace("replace", R.string.mode_replace),
    Add("add", R.string.mode_add),
    Subtract("subtract", R.string.mode_subtract),
    Intersect("intersect", R.string.mode_intersect),
}

data class BrushOptions(
    val size: Float = 40f,
    val hardness: Float = 0.8f,
    val opacity: Float = 1f,
    /** ARGB. */
    val color: Int = 0xFFFFFFFF.toInt(),
)

/** Crop proportions; `null` ratio = free, [Original] = the document's own. */
enum class CropAspect(@param:StringRes val label: Int, val ratio: Float?) {
    Free(R.string.crop_free, null),
    Original(R.string.crop_original, null),
    Square(R.string.crop_square, 1f),
    FourThree(R.string.crop_4_3, 4f / 3f),
    ThreeFour(R.string.crop_3_4, 3f / 4f),
    SixteenNine(R.string.crop_16_9, 16f / 9f),
    NineSixteen(R.string.crop_9_16, 9f / 16f),
}

/** The crop box in document pixels. */
data class CropBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
}

/** The Photoshop blend modes offered in the Layers panel (engine names). */
val BLEND_MODES = listOf(
    "Normal", "Dissolve", "Darken", "Multiply", "ColorBurn", "LinearBurn", "Lighten", "Screen",
    "ColorDodge", "LinearDodge", "Overlay", "SoftLight", "HardLight", "VividLight", "LinearLight",
    "PinLight", "HardMix", "Difference", "Exclusion", "Subtract", "Divide", "Hue", "Saturation",
    "Color", "Luminosity",
)

/** Curves and Levels channels: the engine's param key ("" = the composite). */
enum class ToneChannel(val key: String, @param:StringRes val label: Int, val color: Long) {
    Rgb("", R.string.ch_rgb, 0xFFE6E6E6),
    Red("red", R.string.ch_red, 0xFFFF5252),
    Green("green", R.string.ch_green, 0xFF69F0AE),
    Blue("blue", R.string.ch_blue, 0xFF448AFF),
}

/** One Levels channel, 0..255 like the dialog. */
data class LevelsValues(
    val inBlack: Float = 0f,
    val gamma: Float = 1f,
    val inWhite: Float = 255f,
    val outBlack: Float = 0f,
    val outWhite: Float = 255f,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("inBlack", inBlack.toDouble()).put("gamma", gamma.toDouble()).put("inWhite", inWhite.toDouble())
        .put("outBlack", outBlack.toDouble()).put("outWhite", outWhite.toDouble())
}

/** A curve point, 0..255. */
data class CurvePoint(val x: Float, val y: Float)

val IDENTITY_CURVE = listOf(CurvePoint(0f, 0f), CurvePoint(255f, 255f))
