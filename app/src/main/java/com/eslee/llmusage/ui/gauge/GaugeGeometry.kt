package com.eslee.llmusage.ui.gauge

import kotlin.math.roundToInt

/**
 * The one description of the battery gauge that the app screens and the home
 * screen widget both draw: a ring open at the bottom that fills clockwise with
 * the capacity left, the provider mark in its centre and the number in the gap.
 * Keeping the rules here means a widget and a card never disagree on what full,
 * low or unknown look like.
 */
object GaugeGeometry {
    /** The ring starts at the lower left and sweeps clockwise, leaving the bottom open. */
    const val START_ANGLE = 135f
    const val SWEEP_ANGLE = 270f

    /** Fractions of the gauge diameter. */
    const val STROKE_FRACTION = 0.1f
    const val MARK_FRACTION = 0.34f
    const val NUMBER_FRACTION = 0.26f

    /** The ring is drawn slightly above centre so the number can sit in the gap on the bottom edge. */
    const val RING_FRACTION = 0.94f
    const val RING_CENTER_Y = 0.47f

    enum class Level { HIGH, MEDIUM, LOW, UNKNOWN }

    fun fraction(remainingPercent: Double?): Float? =
        remainingPercent?.takeIf { it.isFinite() }?.let { (it / 100.0).coerceIn(0.0, 1.0).toFloat() }

    fun sweep(remainingPercent: Double?): Float = (fraction(remainingPercent) ?: 0f) * SWEEP_ANGLE

    /** Like a phone battery: green while there is plenty, amber when it is getting low, red when nearly spent. */
    fun level(remainingPercent: Double?): Level {
        val fraction = fraction(remainingPercent) ?: return Level.UNKNOWN
        return when {
            fraction > 0.35f -> Level.HIGH
            fraction > 0.15f -> Level.MEDIUM
            else -> Level.LOW
        }
    }

    /** The figure in the gap: the whole percent left, or a dash when nothing is known. */
    fun numberText(remainingPercent: Double?): String =
        fraction(remainingPercent)?.let { (it * 100).roundToInt().toString() } ?: "—"

    fun fillArgb(level: Level, dark: Boolean): Int = when (level) {
        Level.HIGH -> if (dark) 0xFF3DD86E.toInt() else 0xFF22A75A.toInt()
        Level.MEDIUM -> if (dark) 0xFFFFB92E.toInt() else 0xFFE39A00.toInt()
        Level.LOW -> if (dark) 0xFFFF6459.toInt() else 0xFFDE3F35.toInt()
        Level.UNKNOWN -> 0
    }

    fun trackArgb(dark: Boolean): Int = if (dark) 0x30FFFFFF else 0x17000000

    /** A ring whose value can no longer be trusted is drawn in the warning colour whatever its level. */
    fun warningArgb(dark: Boolean): Int = fillArgb(Level.MEDIUM, dark)
}
