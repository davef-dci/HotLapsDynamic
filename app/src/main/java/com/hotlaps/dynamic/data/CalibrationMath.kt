package com.hotlaps.dynamic.data

import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Calibration presets and the maths behind the calibration screen (pure Kotlin, unit-tested).
 *
 * Phone sensor frame: +X right edge, +Y top edge, +Z out of the screen.
 *
 * The calibration is the phone's FORWARD axis. RecordingEngine levels it against live gravity,
 * so any lean (pitch/roll) of the mount is handled; what a preset can't know is a twist of the
 * phone away from straight ahead when seen from above: that needs a measured calibration.
 */
object CalibrationMath {

    data class Preset(val id: String, val label: String, val shortLabel: String, val vec: FloatArray)

    val PRESETS = listOf(
        Preset("top", "Top of phone faces forward", "Top edge", floatArrayOf(0f, 1f, 0f)),
        Preset("bottom", "Bottom of phone faces forward", "Bottom edge", floatArrayOf(0f, -1f, 0f)),
        Preset("back", "Back of phone faces forward", "Back (upright)", floatArrayOf(0f, 0f, -1f)),
        Preset("left", "Left edge faces forward", "Left edge", floatArrayOf(-1f, 0f, 0f)),
        Preset("right", "Right edge faces forward", "Right edge", floatArrayOf(1f, 0f, 0f)),
        Preset("screen", "Screen faces forward", "Screen", floatArrayOf(0f, 0f, 1f)),
    )

    const val SOURCE_PRESET = "preset"
    const val SOURCE_MEASURED = "measured"
    const val SOURCE_AUTO = "auto"
    const val SOURCE_MANUAL = SOURCE_PRESET

    /**
     * Best guess at forward before any calibration, from gravity alone: an upright phone
     * (dash/windscreen, screen to the driver) faces forward with its back; a flat one is assumed
     * top-edge-forward (the race car's tunnel mount).
     */
    fun guessForward(gravity: FloatArray?): FloatArray {
        if (gravity == null) return floatArrayOf(0f, 1f, 0f)
        return if (isUpright(gravity)) floatArrayOf(0f, 0f, -1f) else floatArrayOf(0f, 1f, 0f)
    }

    /**
     * True if the phone is tilted more than [FLAT_TOLERANCE_DEG] from flat, i.e. a dash/windscreen
     * mount with the screen toward the driver (even a strongly reclined one), where the back of the
     * phone faces forward. Only a (nearly) flat phone, like the race car's tunnel mount, is "flat".
     * (A reclined dash mount used to count as flat at < 45 deg, so its pre-calibration guess was
     * top-edge-forward, which on a reclined phone points BACK toward the driver: G looked reversed.)
     */
    fun isUpright(gravity: FloatArray): Boolean {
        val g = normalize(gravity) ?: return false
        return kotlin.math.abs(g[2]) < kotlin.math.cos(Math.toRadians(FLAT_TOLERANCE_DEG)).toFloat()
    }

    const val FLAT_TOLERANCE_DEG = 20.0

    /** Phone moved more than this since calibration: warn. */
    const val MOUNT_CHANGE_DEG = 15.0

    /** A chosen forward axis within this many degrees of vertical can't be levelled reliably. */
    const val NEAR_VERTICAL_DEG = 25.0

    /** Preset whose vector equals [vec] (also identifies calibrations saved before labels existed). */
    fun matchPreset(vec: FloatArray?): Preset? =
        vec?.let { v -> PRESETS.firstOrNull { p -> angleDeg(p.vec, v) < 1.0 } }

    /** Angle between two vectors in degrees (0..180); 180 if either is ~zero. */
    fun angleDeg(a: FloatArray, b: FloatArray): Double {
        val na = norm(a)
        val nb = norm(b)
        if (na < 1e-6 || nb < 1e-6) return 180.0
        val c = (dot(a, b) / (na * nb)).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(c))
    }

    /** How far the phone's orientation has moved: angle between gravity directions. */
    fun mountChangeDeg(savedGravity: FloatArray?, currentGravity: FloatArray?): Double? {
        if (savedGravity == null || currentGravity == null) return null
        if (norm(savedGravity) < 1e-3 || norm(currentGravity) < 1e-3) return null
        return angleDeg(savedGravity, currentGravity)
    }

    /** True if [forward] points within [NEAR_VERTICAL_DEG] of straight up/down for this gravity. */
    fun isNearVertical(forward: FloatArray, gravity: FloatArray?): Boolean {
        if (gravity == null || norm(gravity) < 1e-3) return false
        val a = angleDeg(forward, gravity)
        return a < NEAR_VERTICAL_DEG || a > 180.0 - NEAR_VERTICAL_DEG
    }

    /** [v] with its component along [gravity] removed, normalized; null if (nearly) vertical. */
    fun level(v: FloatArray, gravity: FloatArray): FloatArray? {
        val g = normalize(gravity) ?: return null
        val d = dot(v, g).toFloat()
        return normalize(floatArrayOf(v[0] - d * g[0], v[1] - d * g[1], v[2] - d * g[2]))
    }

    /**
     * For a measured forward axis: the nearest preset and the angle between them seen from above
     * (both levelled), e.g. ("Top edge", 12.0). Null if gravity is unknown.
     */
    fun nearestPresetFromAbove(forward: FloatArray, gravity: FloatArray?): Pair<Preset, Double>? {
        if (gravity == null) return null
        val f = level(forward, gravity) ?: return null
        return PRESETS.mapNotNull { p -> level(p.vec, gravity)?.let { p to angleDeg(it, f) } }
            .minByOrNull { it.second }
    }

    // ---- small vector helpers ---------------------------------------------------------------

    private fun dot(a: FloatArray, b: FloatArray): Double =
        a[0].toDouble() * b[0] + a[1].toDouble() * b[1] + a[2].toDouble() * b[2]

    private fun norm(a: FloatArray): Double = sqrt(dot(a, a))

    fun normalize(a: FloatArray): FloatArray? {
        val n = norm(a)
        if (n < 1e-6) return null
        return floatArrayOf((a[0] / n).toFloat(), (a[1] / n).toFloat(), (a[2] / n).toFloat())
    }
}
