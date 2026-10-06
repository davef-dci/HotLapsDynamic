package com.hotlaps.dynamic.data

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class PullCalibratorTest {

    private val G = 9.80665

    /** Phone mounted with yaw/lean. Car frame x = right, y = forward, z = up; returns (fwd, right, up) in phone frame. */
    private fun mount(yawDeg: Double, leanDeg: Double): Triple<DoubleArray, DoubleArray, DoubleArray> {
        val l = Math.toRadians(leanDeg)
        val y = Math.toRadians(yawDeg)
        fun toPhone(v: DoubleArray): DoubleArray {
            val x1 = v[0] * cos(-y) - v[1] * sin(-y)
            val y1 = v[0] * sin(-y) + v[1] * cos(-y)
            val z1 = v[2]
            val y2 = y1 * cos(-l) - z1 * sin(-l)
            val z2 = y1 * sin(-l) + z1 * cos(-l)
            return doubleArrayOf(x1, y2, z2)
        }
        return Triple(toPhone(doubleArrayOf(0.0, 1.0, 0.0)), toPhone(doubleArrayOf(1.0, 0.0, 0.0)), toPhone(doubleArrayOf(0.0, 0.0, 1.0)))
    }

    /** The 4 s pull window at 20 Hz: longitudinal [longG], lateral [latG], turn rate, GPS at [gpsHz]. */
    private fun pull(
        fwd: DoubleArray, right: DoubleArray, up: DoubleArray,
        startSpeed: Double, longG: Double, latG: Double = 0.0, yawRateDegS: Double = 0.0,
        gpsHz: Double = 10.0, noise: Double = 0.8, seed: Int = 1
    ): PullCalibrator.Result {
        val rnd = Random(seed)
        var speed = startSpeed
        var x = 0.0; var y = 0.0; var heading = 0.0
        var lastFix = -1e9
        var fixLat = 43.0; var fixLon = -89.0; var fixSpeed = startSpeed
        val dt = 0.05
        val ticks = ArrayList<PullCalibrator.Tick>()
        var t = 0.0
        while (t < PullCalibrator.PULL_MS / 1000.0) {
            speed = (speed + longG * G * dt).coerceAtLeast(0.0)
            heading += yawRateDegS * dt
            x += speed * dt * sin(Math.toRadians(heading)); y += speed * dt * cos(Math.toRadians(heading))
            if (t - lastFix >= 1.0 / gpsHz - 1e-9) {
                lastFix = t
                fixLat = 43.0 + y / 111_320.0
                fixLon = -89.0 + x / (111_320.0 * cos(Math.toRadians(43.0)))
                fixSpeed = speed
            }
            val a = DoubleArray(3) { i -> fwd[i] * longG * G + right[i] * latG * G + (rnd.nextDouble() - 0.5) * noise }
            ticks.add(
                PullCalibrator.Tick(
                    timeMs = (t * 1000).toLong(),
                    accel = FloatArray(3) { a[it].toFloat() },
                    gravity = FloatArray(3) { (up[it] * G).toFloat() },
                    speedMps = fixSpeed, lat = fixLat, lon = fixLon
                )
            )
            t += dt
        }
        return PullCalibrator.evaluate(ticks)
    }

    private fun assertForward(expected: DoubleArray, r: PullCalibrator.Result, braking: Boolean = false) {
        if (r !is PullCalibrator.Result.Ok) fail("expected Ok, got $r") else {
            val err = CalibrationMath.angleDeg(FloatArray(3) { expected[it].toFloat() }, r.forward)
            assertTrue("forward off by $err deg", err < 5.0)
            assertTrue(r.braking == braking)
        }
    }

    private fun assertFail(r: PullCalibrator.Result, contains: String) {
        assertTrue("expected Fail containing '$contains', got $r", r is PullCalibrator.Result.Fail && r.reason.contains(contains))
    }

    @Test fun pullAwayFromAStop_flatTunnelMount() {
        val (f, r, u) = mount(0.0, 0.0)
        assertForward(f, pull(f, r, u, startSpeed = 0.0, longG = 0.3))
    }

    @Test fun uprightDashMount_twisted() {
        val (f, r, u) = mount(yawDeg = 20.0, leanDeg = 70.0)
        assertForward(f, pull(f, r, u, startSpeed = 3.0, longG = 0.25))
    }

    @Test fun firmBrakingWorksToo_withTheRightSign() {
        val (f, r, u) = mount(yawDeg = -10.0, leanDeg = 70.0)
        assertForward(f, pull(f, r, u, startSpeed = 15.0, longG = -0.4), braking = true)
    }

    @Test fun phoneGps1Hz() {
        val (f, r, u) = mount(0.0, 70.0)
        assertForward(f, pull(f, r, u, startSpeed = 2.0, longG = 0.25, gpsHz = 1.0))
    }

    @Test fun parkedOrCruising_isRejectedWithAReason() {
        val (f, r, u) = mount(0.0, 70.0)
        assertFail(pull(f, r, u, startSpeed = 0.0, longG = 0.0), "only changed")
        assertFail(pull(f, r, u, startSpeed = 20.0, longG = 0.0), "only changed")
    }

    @Test fun turning_isRejected() {
        val (f, r, u) = mount(0.0, 70.0)
        assertFail(pull(f, r, u, startSpeed = 6.0, longG = 0.2, latG = 0.4, yawRateDegS = 15.0), "turning")
    }

    @Test fun phoneLooseInMount_isRejected() {
        // Phone says 0.3 g but GPS only saw 0.06 g of speed change: not a trustworthy pull
        val (f, r, u) = mount(0.0, 70.0)
        val ticks = (0 until 80).map { i ->
            PullCalibrator.Tick(
                timeMs = i * 50L,
                accel = FloatArray(3) { (f[it] * 0.3 * G).toFloat() },
                gravity = FloatArray(3) { (u[it] * G).toFloat() },
                speedMps = 5.0 + i * 0.03, lat = 43.0 + i * 1e-6, lon = -89.0
            )
        }
        assertFail(PullCalibrator.evaluate(ticks), "disagree")
    }
}
