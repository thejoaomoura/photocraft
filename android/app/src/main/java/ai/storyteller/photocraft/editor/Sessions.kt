package ai.storyteller.photocraft.editor

import org.json.JSONArray
import org.json.JSONObject

/**
 * An adjustment layer open in the Properties panel. Its edits share one coalesced history step
 * (keyed by [nonce]), so Cancel undoes them, and the layer too when the session created it.
 */
sealed interface AdjustEdit {
    val layerId: Long
    val isNew: Boolean
    val edited: Boolean
    val nonce: Int

    /** `layer.setAdjustment` params (without `layer` and `coalesce`). */
    fun params(): JSONObject
    fun markEdited(): AdjustEdit

    /** History steps Cancel must undo. */
    val undoSteps get() = (if (isNew) 1 else 0) + (if (edited) 1 else 0)
}

data class SliderEdit(
    val kind: AdjustKind,
    override val layerId: Long,
    val values: Map<String, Float>,
    override val isNew: Boolean,
    override val nonce: Int,
    override val edited: Boolean = false,
) : AdjustEdit {
    override fun params() = JSONObject().also { o -> values.forEach { (k, v) -> o.put(k, v.toDouble()) } }
    override fun markEdited() = copy(edited = true)
}

data class CurvesEdit(
    override val layerId: Long,
    val channel: ToneChannel,
    val curves: Map<ToneChannel, List<CurvePoint>>,
    override val isNew: Boolean,
    override val nonce: Int,
    override val edited: Boolean = false,
) : AdjustEdit {
    fun curve(ch: ToneChannel = channel) = curves[ch] ?: IDENTITY_CURVE

    override fun params(): JSONObject {
        val o = JSONObject()
        ToneChannel.entries.forEach { ch ->
            val arr = JSONArray()
            curve(ch).forEach { p -> arr.put(JSONArray().put(p.x.toDouble()).put(p.y.toDouble())) }
            o.put(if (ch == ToneChannel.Rgb) "points" else ch.key, arr)
        }
        return o
    }

    override fun markEdited() = copy(edited = true)
}

data class LevelsEdit(
    override val layerId: Long,
    val channel: ToneChannel,
    val levels: Map<ToneChannel, LevelsValues>,
    override val isNew: Boolean,
    override val nonce: Int,
    override val edited: Boolean = false,
) : AdjustEdit {
    fun current(ch: ToneChannel = channel) = levels[ch] ?: LevelsValues()

    override fun params(): JSONObject {
        val o = current(ToneChannel.Rgb).toJson()
        listOf(ToneChannel.Red, ToneChannel.Green, ToneChannel.Blue).forEach { o.put(it.key, current(it).toJson()) }
        return o
    }

    override fun markEdited() = copy(edited = true)
}

/** A filter previewed on the layer: re-applied (after an undo) when a slider is released. */
data class FilterEdit(val kind: FilterKind, val values: Map<String, Float>, val applied: Boolean = false)

/** Reads an adjustment layer's settings back from `document.inspect` (`{"Curves": {...}}`). */
object AdjustParse {
    fun edit(layer: LayerItem, nonce: Int): AdjustEdit? {
        val adj = layer.adjustment ?: return null
        val name = layer.adjustmentKind ?: return null
        val body = adj.optJSONObject(name) ?: JSONObject()
        return when (name) {
            "Curves" -> CurvesEdit(layer.id, ToneChannel.Rgb, curves(body), isNew = false, nonce = nonce)
            "Levels" -> LevelsEdit(layer.id, ToneChannel.Rgb, levels(body), isNew = false, nonce = nonce)
            else -> {
                val kind = AdjustKind.entries.firstOrNull { it.name == name && it.params.isNotEmpty() } ?: return null
                val values = kind.params.associate { p -> p.key to body.optDouble(p.key, p.default.toDouble()).toFloat() }
                SliderEdit(kind, layer.id, values, isNew = false, nonce = nonce)
            }
        }
    }

    private fun curves(body: JSONObject): Map<ToneChannel, List<CurvePoint>> {
        fun read(arr: JSONArray?): List<CurvePoint> {
            if (arr == null || arr.length() < 2) return IDENTITY_CURVE
            return (0 until arr.length()).mapNotNull { i ->
                val p = arr.optJSONObject(i) ?: return@mapNotNull null
                CurvePoint((p.optDouble("input") * 255).toFloat(), (p.optDouble("output") * 255).toFloat())
            }.ifEmpty { IDENTITY_CURVE }
        }
        val per = body.optJSONArray("per_channel")
        return mapOf(
            ToneChannel.Rgb to read(body.optJSONArray("master")),
            ToneChannel.Red to read(per?.optJSONArray(0)),
            ToneChannel.Green to read(per?.optJSONArray(1)),
            ToneChannel.Blue to read(per?.optJSONArray(2)),
        )
    }

    private fun levels(body: JSONObject): Map<ToneChannel, LevelsValues> {
        fun read(o: JSONObject?): LevelsValues {
            if (o == null) return LevelsValues()
            val inWhite = o.optDouble("in_white", 1.0)
            // The document stores 0..1; older files may hold 0..255.
            val k = if (inWhite <= 1.0001) 255.0 else 1.0
            return LevelsValues(
                inBlack = (o.optDouble("in_black", 0.0) * k).toFloat(),
                gamma = o.optDouble("gamma", 1.0).toFloat(),
                inWhite = (inWhite * k).toFloat(),
                outBlack = (o.optDouble("out_black", 0.0) * k).toFloat(),
                outWhite = (o.optDouble("out_white", 1.0) * k).toFloat(),
            )
        }
        val per = body.optJSONArray("per_channel")
        return mapOf(
            ToneChannel.Rgb to read(body.optJSONObject("master")),
            ToneChannel.Red to read(per?.optJSONObject(0)),
            ToneChannel.Green to read(per?.optJSONObject(1)),
            ToneChannel.Blue to read(per?.optJSONObject(2)),
        )
    }
}
