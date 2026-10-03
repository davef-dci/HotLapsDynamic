package com.hotlaps.dynamic.data

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Automatic calibration: finds the phone's forward axis from the first clean straight-line
 * acceleration (or braking) while driving, e.g. pit exit. Pure Kotlin, unit-tested.
 *
 * Fed every engine tick (~20 Hz). Over a sliding [windowMs] window it requires:
 *  - GPS speed changing by at least [minSpeedChangeMps] (rising = accelerating, falling = braking;
 *    the sign tells which way the measured acceleration points: no button timing, no sign error)
 *  - the car moving (max speed >= [minSpeedMps]) and going straight (GPS heading change
 *    <= [maxHeadingChangeDeg])
 *  - the phone's horizontal acceleration (levelled with gravity) consistent in direction and
 *    roughly matching the GPS speed change in size
 * Then forward = mean horizontal acceleration direction (negated when braking), in the phone frame.
 * Works for any mount, including phones twisted to one side.
 */
class AutoCalibrator(
    private val windowMs: Long = 3_000L,
    private val minSpeedChangeMps: Double = 4.0,
    private val minSpeedMps: Double = 8.0,
    private val maxHeadingChangeDeg: Double = 10.0,
    private val minHorizontalG: Double = 0.08,
    private val minConsistency: Double = 0.75
) {
    data class Tick(
        val timeMs: Long,
        val accel: FloatArray,   // linear acceleration, phone frame, m/s^2
        val gravity: FloatArray, // gravity vector, phone frame
        val speedMps: Double?,
        val lat: Double,
        val lon: Double
    )

    data class Lock(
        val forward: FloatArray,
        val gravity: FloatArray,
        val meanG: Double,
        val braking: Boolean
    )

    private companion object {
        const val G = 9.80665
    }

    private val ticks = ArrayDeque<Tick>()

    fun reset() = ticks.clear()

    /** Adds one tick; returns a [Lock] when the last window was a clean straight-line pull. */
    fun add(t: Tick): Lock? {
        ticks.addLast(t)
        while (ticks.isNotEmpty() && t.timeMs - ticks.first().timeMs > windowMs) ticks.removeFirst()
        if (t.timeMs - ticks.first().timeMs < windowMs * 9 / 10) return null
        return evaluate()?.also { ticks.clear() }
    }

    private fun evaluate(): Lock? {
        val w = ticks.toList()

        // ---- GPS: speeding up or slowing down by enough, while moving --------------------
        val speeds = w.mapNotNull { it.speedMps }
        if (speeds.size < 2) return null
        val dv = speeds.last() - speeds.first()
        if (abs(dv) < minSpeedChangeMps) return null
        if (speeds.max() < minSpeedMps) return null
        val braking = dv < 0

        // ---- GPS: going straight -------------------------------------------------------------
        val fixes = ArrayList<Pair<Double, Double>>()
        for (x in w) {
            if (x.lat == 0.0 && x.lon == 0.0) continue
            val p = x.lat to x.lon
            if (fixes.isEmpty() || fixes.last() != p) fixes.add(p)
        }
        if (fixes.size < 3) return null
        val mid = fixes[fixes.size / 2]
        val h1 = heading(fixes.first(), mid) ?: return null
        val h2 = heading(mid, fixes.last()) ?: return null
        var dh = abs(h2 - h1) % 360.0
        if (dh > 180.0) dh = 360.0 - dh
        if (dh > maxHeadingChangeDeg) return null

        // ---- Phone: horizontal acceleration, consistent and the right size ----------------
        val horiz = w.mapNotNull { x -> horizontal(x.accel, x.gravity) }
        if (horiz.size < w.size / 2) return null
        val mean = floatArrayOf(
            horiz.map { it[0] }.average().toFloat(),
            horiz.map { it[1] }.average().toFloat(),
            horiz.map { it[2] }.average().toFloat()
        )
        val meanG = norm(mean) / G
        if (meanG < minHorizontalG) return null
        val consistent = horiz.count { h -> norm(h) > 0.3 && CalibrationMath.angleDeg(h, mean) < 35.0 }
        if (consistent.toDouble() / horiz.size < minConsistency) return null

        // GPS says |dv/dt|; the phone should agree within a factor of 2
        val spanS = (w.last().timeMs - w.first().timeMs) / 1000.0
        val gpsG = abs(dv) / spanS / G
        if (meanG > gpsG * 2.0 || meanG < gpsG * 0.5) return null

        val dir = CalibrationMath.normalize(mean) ?: return null
        val forward = if (braking) floatArrayOf(-dir[0], -dir[1], -dir[2]) else dir
        val gMean = floatArrayOf(
            w.map { it.gravity[0] }.average().toFloat(),
            w.map { it.gravity[1] }.average().toFloat(),
            w.map { it.gravity[2] }.average().toFloat()
        )
        return Lock(forward, gMean, meanG, braking)
    }

    /** [a] minus its component along gravity; null if gravity is unknown. */
    private fun horizontal(a: FloatArray, gravity: FloatArray): FloatArray? {
        val g = CalibrationMath.normalize(gravity) ?: return null
        val d = a[0] * g[0] + a[1] * g[1] + a[2] * g[2]
        return floatArrayOf(a[0] - d * g[0], a[1] - d * g[1], a[2] - d * g[2])
    }

    /** Compass heading in degrees from [a] to [b]; null if they're (nearly) the same point. */
    private fun heading(a: Pair<Double, Double>, b: Pair<Double, Double>): Double? {
        val dy = (b.first - a.first) * 111_320.0
        val dx = (b.second - a.second) * 111_320.0 * cos(Math.toRadians(a.first))
        if (sqrt(dx * dx + dy * dy) < 1.0) return null
        return Math.toDegrees(atan2(dx, dy))
    }

    private fun norm(a: FloatArray) = sqrt((a[0] * a[0] + a[1] * a[1] + a[2] * a[2]).toDouble())
}
