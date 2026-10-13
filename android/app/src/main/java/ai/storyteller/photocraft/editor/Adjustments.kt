package ai.storyteller.photocraft.editor

import androidx.annotation.StringRes
import ai.storyteller.photocraft.R

/** One slider of an adjustment layer: the engine's param key and its range (`layer.setAdjustment`). */
data class AdjustParam(
    val key: String,
    @param:StringRes val label: Int,
    val min: Float,
    val max: Float,
    val default: Float = 0f,
)

/**
 * The adjustment layers the lightweight app offers. [id] is the engine's adjustment kind:
 * `layer.newAdjustmentLayer.<id>` creates the layer, `layer.setAdjustment` edits it.
 * Ranges follow `crates/engine/src/adjust_params.rs`.
 */
enum class AdjustKind(val id: String, @param:StringRes val label: Int, val params: List<AdjustParam>) {
    BrightnessContrast(
        "brightnessContrast", R.string.adj_brightness_contrast,
        listOf(
            AdjustParam("brightness", R.string.p_brightness, -150f, 150f),
            AdjustParam("contrast", R.string.p_contrast, -50f, 100f),
        ),
    ),
    HueSaturation(
        "hueSaturation", R.string.adj_hue_saturation,
        listOf(
            AdjustParam("hue", R.string.p_hue, -180f, 180f),
            AdjustParam("saturation", R.string.p_saturation, -100f, 100f),
            AdjustParam("lightness", R.string.p_lightness, -100f, 100f),
        ),
    ),
    Vibrance(
        "vibrance", R.string.adj_vibrance,
        listOf(
            AdjustParam("vibrance", R.string.p_vibrance, -100f, 100f),
            AdjustParam("saturation", R.string.p_saturation, -100f, 100f),
        ),
    ),
    Exposure(
        "exposure", R.string.adj_exposure,
        listOf(
            AdjustParam("exposure", R.string.p_exposure, -5f, 5f),
            AdjustParam("offset", R.string.p_offset, -0.5f, 0.5f),
            AdjustParam("gamma", R.string.p_gamma, 0.1f, 3f, default = 1f),
        ),
    ),
    BlackWhite("blackWhite", R.string.adj_black_white, emptyList());

    fun defaults(): Map<String, Float> = params.associate { it.key to it.default }
}

/** File formats offered by Export, with the MIME type the Storage Access Framework needs. */
enum class ExportFormat(val extension: String, val mime: String, @param:StringRes val label: Int) {
    Jpeg("jpg", "image/jpeg", R.string.export_jpeg),
    Png("png", "image/png", R.string.export_png),
    Psd("psd", "image/vnd.adobe.photoshop", R.string.export_psd),
    Pcraft("pcraft", "application/octet-stream", R.string.export_pcraft),
}
