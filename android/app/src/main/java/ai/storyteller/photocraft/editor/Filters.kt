package ai.storyteller.photocraft.editor

import androidx.annotation.StringRes
import ai.storyteller.photocraft.R

/**
 * The filters the lightweight app offers (destructive, on the selected layer, inside the selection
 * when there is one). Ranges follow the engine's params (`photocraft-cli commands`); the mobile
 * sliders stop well short of the extremes, which take seconds on a phone.
 */
enum class FilterKind(val id: String, @param:StringRes val label: Int, val params: List<AdjustParam>) {
    GaussianBlur(
        "filter.blur.gaussianBlur", R.string.f_gaussian_blur,
        listOf(AdjustParam("radius", R.string.p_radius, 0.1f, 100f, 4f)),
    ),
    MotionBlur(
        "filter.blur.motionBlur", R.string.f_motion_blur,
        listOf(
            AdjustParam("angle", R.string.p_angle, -180f, 180f, 0f),
            AdjustParam("distance", R.string.p_distance, 1f, 300f, 20f),
        ),
    ),
    SurfaceBlur(
        "filter.blur.surfaceBlur", R.string.f_surface_blur,
        listOf(
            AdjustParam("radius", R.string.p_radius, 1f, 50f, 5f),
            AdjustParam("threshold", R.string.p_threshold, 2f, 255f, 15f),
        ),
    ),
    UnsharpMask(
        "filter.sharpen.unsharpMask", R.string.f_unsharp_mask,
        listOf(
            AdjustParam("amount", R.string.p_amount, 1f, 500f, 80f),
            AdjustParam("radius", R.string.p_radius, 0.1f, 50f, 1.5f),
            AdjustParam("threshold", R.string.p_threshold, 0f, 255f, 0f),
        ),
    ),
    SmartSharpen(
        "filter.sharpen.smartSharpen", R.string.f_smart_sharpen,
        listOf(
            AdjustParam("amount", R.string.p_amount, 1f, 500f, 100f),
            AdjustParam("radius", R.string.p_radius, 0.1f, 64f, 1f),
            AdjustParam("reduceNoise", R.string.p_reduce_noise, 0f, 100f, 10f),
        ),
    ),
    ReduceNoise(
        "filter.noise.reduceNoise", R.string.f_reduce_noise,
        listOf(
            AdjustParam("strength", R.string.p_strength, 0f, 10f, 6f),
            AdjustParam("preserveDetails", R.string.p_preserve_details, 0f, 100f, 60f),
            AdjustParam("reduceColorNoise", R.string.p_reduce_color_noise, 0f, 100f, 45f),
        ),
    ),
    AddNoise(
        "filter.noise.addNoise", R.string.f_add_noise,
        listOf(AdjustParam("amount", R.string.p_amount, 0.1f, 100f, 12.5f)),
    ),
    Median(
        "filter.noise.median", R.string.f_median,
        listOf(AdjustParam("radius", R.string.p_radius, 1f, 50f, 2f)),
    );

    fun defaults(): Map<String, Float> = params.associate { it.key to it.default }
}
