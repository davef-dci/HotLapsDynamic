package com.hotlaps.dynamic.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class AutoCalibratorTest {

    private val G = 9.80665

    /**
     * A phone mounted with arbitrary yaw/lean. Car frame: x = right, y = forward, z = up.
     * Returns (forward, right, up) expressed in the phone frame.
     */
    private fun mount(yawDeg: Double, leanDeg: Double): Triple<DoubleArray, DoubleArray, DoubleArray> {
        // Start with phone flat, top forward: phone axes == car axes. Lean back about phone X,
        // then yaw about car up. Express car axes in phone coordinates via the inverse rotation.
        val l = Math.toRadians(leanDeg)
        val y = Math.toRadians(yawDeg)
        // car->phone rotation R = Rx(-lean) * Rz(-yaw)
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

    /**
     * Simulate driving north for [seconds] at 20 Hz with longitudinal accel [longG] (and lateral
     * [latG], plus a turn rate [yawRateDegS] for the GPS path), GPS at [gpsHz].
     */
    private fun drive(
        cal: AutoCalibrator, fwd: DoubleArray, right: DoubleArray, up: DoubleArray,
        seconds: Double, startSpeed: Double, longG: Double, latG: Double = 0.0,
        yawRateDegS: Double = 0.0, gpsHz: Double = 10.0, noise: Double = 0.5, seed: Int = 1
    ): AutoCalibrator.Lock? {
        val rnd = Random(seed)
        var speed = startSpeed
        var x = 0.0; var y = 0.0; var heading = 0.0
        var lastFix = -1e9
        var fixLat = 0.0; var fixLon = 0.0; var fixSpeed = startSpeed
        val dt = 0.05
        var lock: AutoCalibrator.Lock? = null
        var t = 0.0
        while (t < seconds && lock == null) {
            speed += longG * G * dt
            heading += yawRateDegS * dt
            x += speed * dt * sin(Math.toRadians(heading)); y += speed * dt * cos(Math.toRadians(heading))
            if (t - lastFix >= 1.0 / gpsHz - 1e-9) {
                lastFix = t
                fixLat = 43.0 + y / 111_320.0
                fixLon = -89.0 + x / (111_320.0 * cos(Math.toRadians(43.0)))
                fixSpeed = speed
            }
            val a = DoubleArray(3) { i ->
                fwd[i] * longG * G + right[i] * latG * G + (rnd.nextDouble() - 0.5) * noise
            }
            val g = DoubleArray(3) { i -> up[i] * G }
            lock = cal.add(
                AutoCalibrator.Tick(
                    timeMs = (t * 1000).toLong(),
                    accel = FloatArray(3) { a[it].toFloat() },
                    gravity = FloatArray(3) { g[it].toFloat() },
                    speedMps = fixSpeed, lat = fixLat, lon = fixLon
                )
            )
            t += dt
        }
        return lock
    }

    private fun assertForward(expected: DoubleArray, lock: AutoCalibrator.Lock?) {
        assertNotNull("expected a lock", lock)
        val e = FloatArray(3) { expected[it].toFloat() }
        val err = CalibrationMath.angleDeg(e, lock!!.forward)
        assertTrue("forward off by $err deg", err < 3.0)
    }

    @Test
    fun flatTunnelMount_pitExitAcceleration() {
        val (f, r, u) = mount(yawDeg = 0.0, leanDeg = 0.0)
        assertForward(f, drive(AutoCalibrator(), f, r, u, seconds = 6.0, startSpeed = 5.0, longG = 0.3))
    }

    @Test
    fun uprightDashMountLeaning70_twisted20() {
        val (f, r, u) = mount(yawDeg = 20.0, leanDeg = 70.0)
        assertForward(f, drive(AutoCalibrator(), f, r, u, seconds = 6.0, startSpeed = 5.0, longG = 0.3))
    }

    @Test
    fun brakingAlsoCalibrates_withTheRightSign() {
        val (f, r, u) = mount(yawDeg = -35.0, leanDeg = 10.0)
        val lock = drive(AutoCalibrator(), f, r, u, seconds = 6.0, startSpeed = 35.0, longG = -0.6)
        assertForward(f, lock)
        assertTrue(lock!!.braking)
    }

    @Test
    fun phoneGps1Hz_stillLocks() {
        val (f, r, u) = mount(yawDeg = 0.0, leanDeg = 0.0)
        assertForward(f, drive(AutoCalibrator(), f, r, u, seconds = 8.0, startSpeed = 5.0, longG = 0.3, gpsHz = 1.0))
    }

    @Test
    fun cornering_doesNotLock() {
        val (f, r, u) = mount(yawDeg = 0.0, leanDeg = 0.0)
        // Accelerating out of a corner while still turning 15 deg/s with 0.8 g lateral
        assertNull(drive(AutoCalibrator(), f, r, u, seconds = 6.0, startSpeed = 10.0, longG = 0.3, latG = 0.8, yawRateDegS = 15.0))
    }

    @Test
    fun cruisingOrParked_doesNotLock() {
        val (f, r, u) = mount(yawDeg = 0.0, leanDeg = 0.0)
        assertNull(drive(AutoCalibrator(), f, r, u, seconds = 10.0, startSpeed = 25.0, longG = 0.0))
        assertNull(drive(AutoCalibrator(), f, r, u, seconds = 10.0, startSpeed = 0.0, longG = 0.0))
    }

    @Test
    fun gentleAcceleration_doesNotLock() {
        val (f, r, u) = mount(yawDeg = 0.0, leanDeg = 0.0)
        assertNull(drive(AutoCalibrator(), f, r, u, seconds = 6.0, startSpeed = 10.0, longG = 0.05))
    }

    @Test
    fun lockReportsStrengthAndGravity() {
        val (f, r, u) = mount(yawDeg = 0.0, leanDeg = 30.0)
        val lock = drive(AutoCalibrator(), f, r, u, seconds = 6.0, startSpeed = 5.0, longG = 0.3)!!
        assertEquals(0.3, lock.meanG, 0.05)
        val up = FloatArray(3) { u[it].toFloat() }
        assertTrue(CalibrationMath.angleDeg(up, lock.gravity) < 1.0)
    }
}
