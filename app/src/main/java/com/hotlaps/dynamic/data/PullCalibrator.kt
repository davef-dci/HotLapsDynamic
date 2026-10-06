package com.hotlaps.dynamic.data

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Calibration from one deliberate straight-line pull: the driver taps Calibrate, a 3-2-1
 * countdown runs, then they accelerate (or brake) firmly in a straight line for [PULL_MS].
 * The phone's mean horizontal acceleration over that window is "forward".
 *
 * This is the original HotLaps method (the driver chooses a clean pull), with checks against GPS
 * so a bad pull is rejected with a reason instead of being saved:
 *  - GPS speed must change by at least [MIN_SPEED_CHANGE_MPS] (the sign of the change tells
 *    accelerating from braking, so either works)
 *  - the car must be going straight (GPS heading)
 *  - the phone's acceleration must be strong enough, steady in direction, and roughly the size
 *    GPS says it was
 * The forward axis is levelled against gravity by the engine, so a tilted mount is fine.
 *
 * Replaces the automatic detector (2026-10-05): spotting a pull in normal driving either never
 * triggered (strict) or locked onto weak, noisy data and saved a wrong axis (relaxed).
 */
object PullCalibrator {

    const val COUNTDOWN_MS = 3_000L
    const val PULL_MS = 4_000L

    const val MIN_SPEED_CHANGE_MPS = 2.2       // 5 mph
    const val MIN_HORIZONTAL_G = 0.06
    const val MAX_HEADING_CHANGE_DEG = 15.0
    const val MIN_CONSISTENCY = 0.6
    private const val G = 9.80665

    data class Tick(
        val timeMs: Long,
        val accel: FloatArray,   // linear acceleration, phone frame, m/s^2
        val gravity: FloatArray, // gravity vector, phone frame
        val speedMps: Double?,
        val lat: Double,
        val lon: Double
    )

    sealed class Result {
        data class Ok(
            val forward: FloatArray,
            val gravity: FloatArray,
            val meanG: Double,
            val braking: Boolean,
            val speedChangeMph: Double
        ) : Result()

        data class Fail(val reason: String) : Result()
    }

    fun evaluate(ticks: List<Tick>): Result {
        if (ticks.size < 10) return Result.Fail("No sensor data during the pull. Try again.")

        // ---- GPS: the car's speed changed by enough -------------------------------------------
        val speeds = ticks.mapNotNull { it.speedMps }
        if (speeds.size < 2) return Result.Fail("No GPS speed. Wait for a GPS fix and try again.")
        val dv = speeds.last() - speeds.first()
        val dvMph = dv * 2.23694
        if (abs(dv) < MIN_SPEED_CHANGE_MPS) {
            return Result.Fail(
                "GPS speed only changed %.0f mph. Accelerate (or brake) harder: at least 5 mph during the pull.".format(abs(dvMph))
            )
        }
        val braking = dv < 0

        // ---- GPS: going straight (checked where the car moved far enough to measure heading) ---
        val fixes = ArrayList<Pair<Double, Double>>()
        for (t in ticks) {
            if (t.lat == 0.0 && t.lon == 0.0) continue
            val p = t.lat to t.lon
            if (fixes.isEmpty() || fixes.last() != p) fixes.add(p)
        }
        if (fixes.size >= 3) {
            val mid = fixes[fixes.size / 2]
            val h1 = heading(fixes.first(), mid)
            val h2 = heading(mid, fixes.last())
            if (h1 != null && h2 != null) {
                var dh = abs(h2 - h1) % 360.0
                if (dh > 180.0) dh = 360.0 - dh
                if (dh > MAX_HEADING_CHANGE_DEG) {
                    return Result.Fail("The car was turning (%.0f°). Do the pull in a straight line.".format(dh))
                }
            }
        }

        // ---- Phone: horizontal acceleration, strong, steady, and the size GPS saw -------------
        val horiz = ticks.mapNotNull { t -> horizontal(t.accel, t.gravity) }
        if (horiz.size < ticks.size / 2) return Result.Fail("No gravity reading from the phone. Try again.")
        val mean = floatArrayOf(
            horiz.map { it[0] }.average().toFloat(),
            horiz.map { it[1] }.average().toFloat(),
            horiz.map { it[2] }.average().toFloat()
        )
        val meanG = norm(mean) / G
        if (meanG < MIN_HORIZONTAL_G) {
            return Result.Fail("The pull was too gentle (%.2f g). Accelerate or brake more firmly.".format(meanG))
        }
        val steady = horiz.count { h -> norm(h) > 0.3 && CalibrationMath.angleDeg(h, mean) < 35.0 }
        if (steady.toDouble() / horiz.size < MIN_CONSISTENCY) {
            return Result.Fail("The acceleration wasn't steady. Try one smooth, firm pull in a straight line.")
        }
        val spanS = (ticks.last().timeMs - ticks.first().timeMs) / 1000.0
        val gpsG = abs(dv) / spanS / G
        if (meanG > gpsG * 2.5 || meanG < gpsG * 0.4) {
            return Result.Fail("The phone and GPS disagree. Make sure the phone is held firmly in its mount and try again.")
        }

        val dir = CalibrationMath.normalize(mean) ?: return Result.Fail("Couldn't measure a direction. Try again.")
        val forward = if (braking) floatArrayOf(-dir[0], -dir[1], -dir[2]) else dir
        val gMean = floatArrayOf(
            ticks.map { it.gravity[0] }.average().toFloat(),
            ticks.map { it.gravity[1] }.average().toFloat(),
            ticks.map { it.gravity[2] }.average().toFloat()
        )
        return Result.Ok(forward, gMean, meanG, braking, dvMph)
    }

    /** [a] minus its component along gravity; null if gravity is unknown. */
    private fun horizontal(a: FloatArray, gravity: FloatArray): FloatArray? {
        val g = CalibrationMath.normalize(gravity) ?: return null
        val d = a[0] * g[0] + a[1] * g[1] + a[2] * g[2]
        return floatArrayOf(a[0] - d * g[0], a[1] - d * g[1], a[2] - d * g[2])
    }

    /** Compass heading in degrees from [a] to [b]; null if they're less than 3 m apart. */
    private fun heading(a: Pair<Double, Double>, b: Pair<Double, Double>): Double? {
        val dy = (b.first - a.first) * 111_320.0
        val dx = (b.second - a.second) * 111_320.0 * cos(Math.toRadians(a.first))
        if (sqrt(dx * dx + dy * dy) < 3.0) return null
        return Math.toDegrees(atan2(dx, dy))
    }

    private fun norm(a: FloatArray) = sqrt((a[0] * a[0] + a[1] * a[1] + a[2] * a[2]).toDouble())
}
