package com.hotlaps.dynamic.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class CalibrationMathTest {

    private val G = 9.80665f

    /** Gravity reading for a phone leaning back [leanDeg] from flat (0 = flat, 90 = upright). */
    private fun gravityForLean(leanDeg: Double): FloatArray {
        val r = Math.toRadians(leanDeg)
        // Flat: gravity along +Z. Upright (top up): along +Y.
        return floatArrayOf(0f, (G * sin(r)).toFloat(), (G * cos(r)).toFloat())
    }

    @Test
    fun presetsAreRecognisedFromTheSavedVector() {
        assertEquals("back", CalibrationMath.matchPreset(floatArrayOf(0f, 0f, -1f))?.id)
        assertEquals("top", CalibrationMath.matchPreset(floatArrayOf(0f, 1f, 0f))?.id)
        assertNull(CalibrationMath.matchPreset(floatArrayOf(0.2f, 0.98f, 0f))) // measured, not a preset
        assertNull(CalibrationMath.matchPreset(null))
    }

    @Test
    fun topEdgeIsFineFlatButNearVerticalWhenUpright() {
        val top = floatArrayOf(0f, 1f, 0f)
        assertFalse(CalibrationMath.isNearVertical(top, gravityForLean(0.0)))   // tunnel, flat
        assertFalse(CalibrationMath.isNearVertical(top, gravityForLean(45.0)))  // 45 deg lean OK
        assertTrue(CalibrationMath.isNearVertical(top, gravityForLean(75.0)))   // near upright: no
        val back = floatArrayOf(0f, 0f, -1f)
        assertFalse(CalibrationMath.isNearVertical(back, gravityForLean(70.0))) // dash: use Back
        assertTrue(CalibrationMath.isNearVertical(back, gravityForLean(0.0)))   // flat: Back is vertical
    }

    @Test
    fun mountChangeIsTheAngleBetweenGravityReadings() {
        val tunnel = gravityForLean(0.0)
        val dash = gravityForLean(70.0)
        assertEquals(0.0, CalibrationMath.mountChangeDeg(tunnel, tunnel)!!, 1e-6)
        assertEquals(70.0, CalibrationMath.mountChangeDeg(tunnel, dash)!!, 0.01)
        assertTrue(CalibrationMath.mountChangeDeg(tunnel, gravityForLean(10.0))!! < CalibrationMath.MOUNT_CHANGE_DEG)
        assertNull(CalibrationMath.mountChangeDeg(null, dash))
    }

    @Test
    fun measuredForwardIsDescribedRelativeToNearestPresetFromAbove() {
        // Flat phone, twisted 12 deg toward the right edge: forward = rotate +Y toward +X
        val r = Math.toRadians(12.0)
        val fwd = floatArrayOf(sin(r).toFloat(), cos(r).toFloat(), 0f)
        val (preset, deg) = CalibrationMath.nearestPresetFromAbove(fwd, gravityForLean(0.0))!!
        assertEquals("top", preset.id)
        assertEquals(12.0, deg, 0.01)
    }






    @Test
    fun guessForwardFromGravity() {
        assertEquals(0f, CalibrationMath.guessForward(gravityForLean(0.0))[2])          // flat: top edge
        assertEquals(-1f, CalibrationMath.guessForward(gravityForLean(70.0))[2])        // dash: back
        assertTrue(CalibrationMath.isUpright(gravityForLean(60.0)))
        // A reclined dash mount (30-45 deg) counts as upright: back of phone faces forward
        assertTrue(CalibrationMath.isUpright(gravityForLean(30.0)))
        assertEquals(-1f, CalibrationMath.guessForward(gravityForLean(35.0))[2])
        // Only a (nearly) flat phone is flat: top edge forward
        assertFalse(CalibrationMath.isUpright(gravityForLean(10.0)))
    }

    @Test
    fun levellingRemovesTilt() {
        // Dash mount leaning 70 deg, Back preset: levelled forward stays horizontal
        val g = gravityForLean(70.0)
        val lv = CalibrationMath.level(floatArrayOf(0f, 0f, -1f), g)
        assertNotNull(lv)
        assertEquals(90.0, CalibrationMath.angleDeg(lv!!, g), 0.01)
    }
}
