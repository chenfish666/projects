package io.github.chenfish666.podspanel.core

import kotlin.math.sqrt

/** The equalizer's band layout and presets. Kept free of Android types so it can be unit tested. */
object EqMath {

    /** Centre frequency of each band, in Hz. */
    val CENTERS = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

    const val BAND_COUNT = 10
    const val MAX_GAIN_DB = 12f

    /**
     * Upper edge of each band, which is how the platform's DynamicsProcessing effect defines a
     * band. Edges sit at the geometric mean of neighbouring centres; the last band runs to 20 kHz.
     */
    val CUTOFFS: FloatArray = FloatArray(BAND_COUNT) { i ->
        if (i == BAND_COUNT - 1) 20_000f
        else sqrt(CENTERS[i].toFloat() * CENTERS[i + 1].toFloat())
    }

    data class Preset(val id: String, val label: String, val gains: FloatArray)

    val PRESETS: List<Preset> = listOf(
        Preset("flat", "原音", floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
        Preset("bass", "重低音", floatArrayOf(7f, 6f, 4.5f, 2.5f, 0.5f, 0f, 0f, 0f, 0f, 0f)),
        Preset("vocal", "人聲", floatArrayOf(-2f, -1.5f, -1f, 0.5f, 2.5f, 4f, 4f, 2.5f, 0.5f, -0.5f)),
        Preset("treble", "高音", floatArrayOf(0f, 0f, 0f, 0f, 0f, 1f, 2.5f, 4.5f, 6f, 6.5f)),
        Preset("vshape", "流行", floatArrayOf(5f, 4f, 2.5f, 0.5f, -1.5f, -1.5f, 0.5f, 2.5f, 4f, 5f)),
        Preset("podcast", "Podcast", floatArrayOf(-4f, -3f, -1.5f, 1f, 3f, 4f, 3.5f, 2f, 0f, -2f)),
    )

    fun presetById(id: String?): Preset? = PRESETS.firstOrNull { it.id == id }

    fun clampGain(db: Float): Float = db.coerceIn(-MAX_GAIN_DB, MAX_GAIN_DB)

    /**
     * Gain to apply ahead of the equalizer so that a boosted band cannot push the signal past
     * full scale. Cutting needs no compensation.
     */
    fun preampDb(gains: FloatArray): Float {
        val peak = gains.maxOrNull() ?: 0f
        return if (peak > 0f) -peak else 0f
    }

    fun encode(gains: FloatArray): String = gains.joinToString(",")

    fun decode(text: String?): FloatArray {
        val parts = text?.split(',')?.mapNotNull { it.trim().toFloatOrNull() } ?: emptyList()
        return FloatArray(BAND_COUNT) { i -> clampGain(parts.getOrElse(i) { 0f }) }
    }

    fun formatFrequency(hz: Int): String = if (hz >= 1000) "${hz / 1000}k" else hz.toString()
}

/**
 * Gain of a ten-band curve at an arbitrary frequency, interpolated between band centres on a
 * logarithmic frequency axis. Used to fit the curve onto an effect with fewer, fixed bands.
 */
fun EqMath.gainAt(hz: Float, gains: FloatArray): Float {
    if (hz <= CENTERS.first()) return gains.first()
    if (hz >= CENTERS.last()) return gains.last()
    val upper = CENTERS.indexOfFirst { it >= hz }
    val lower = upper - 1
    val span = kotlin.math.ln(CENTERS[upper].toFloat() / CENTERS[lower])
    val t = kotlin.math.ln(hz / CENTERS[lower]) / span
    return gains[lower] + (gains[upper] - gains[lower]) * t
}
