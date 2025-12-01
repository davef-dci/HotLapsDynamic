package com.hotlaps.dynamic.util

import kotlin.math.exp

/**
 * Final smoothed G-sample: includes both raw (clamped) and smoothed values.
 */
data class GSmoothedSample(
    val rawLatG: Float,
    val rawLongG: Float,
    val latG: Float,
    val longG: Float
)

/**
 * Simple 2D moving average with a fixed window size.
 * (Copied from GGUi, plus a reset() helper.)
 */
class MovingAverage2D(private var maxSamples: Int) {

    private val buffer = ArrayDeque<Pair<Float, Float>>()
    private var sumX = 0f
    private var sumY = 0f

    fun reset() {
        buffer.clear()
        sumX = 0f
        sumY = 0f
    }

    fun setWindowSize(newSize: Int) {
        maxSamples = newSize.coerceAtLeast(1)
        // Trim if the window shrinks
        while (buffer.size > maxSamples) {
            val (ox, oy) = buffer.removeFirst()
            sumX -= ox
            sumY -= oy
        }
    }

    fun add(x: Float, y: Float): Pair<Float, Float> {
        if (buffer.size == maxSamples) {
            val (ox, oy) = buffer.removeFirst()
            sumX -= ox
            sumY -= oy
        }
        buffer.addLast(x to y)
        sumX += x
        sumY += y

        val size = buffer.size.coerceAtLeast(1)
        return (sumX / size) to (sumY / size)
    }
}

/**
 * Shared EMA + moving-average smoother for G-forces.
 *
 * Both GGUi (live driving) and desk simulation will use this so
 * there is a single “master” for smoothing behaviour.
 */
class GForceSmoother(
    private val tauMs: Float?,   // null = EMA off (just MA on raw)
    maWindowSize: Int           // number of samples in the moving average
) {
    private var longEma = 0f
    private var latEma = 0f
    private var lastTimeMs: Long? = null

    private val ma = MovingAverage2D(maWindowSize.coerceAtLeast(1))
    fun reset() {
        longEma = 0f
        latEma = 0f
        lastTimeMs = null
        ma.reset()
    }

    fun setWindowSize(newSize: Int) {
        ma.setWindowSize(newSize)
    }

    /**
     * Add one raw (already clamped) lat/long G sample at the given time.
     *
     * @param rawLatG  lateral G (right +, left -)
     * @param rawLongG longitudinal G (accel +, brake -)
     * @param sampleTimeMs monotonic-ish time for this sample (ms)
     */
    fun addSample(
        rawLatG: Float,
        rawLongG: Float,
        sampleTimeMs: Long
    ): GSmoothedSample {
        val dtMs = lastTimeMs?.let { (sampleTimeMs - it).coerceAtLeast(1L) } ?: 1L
        lastTimeMs = sampleTimeMs

        // 1) EMA (or pass-through if tauMs == null)
        val (longDb, latDb) = if (tauMs == null) {
            longEma = rawLongG
            latEma = rawLatG
            rawLongG to rawLatG
        } else {
            val alpha = 1f - exp(-dtMs.toFloat() / tauMs)
            val longEmaNew = longEma + alpha * (rawLongG - longEma)
            val latEmaNew  = latEma  + alpha * (rawLatG  - latEma)
            longEma = longEmaNew
            latEma  = latEmaNew
            longEmaNew to latEmaNew
        }

        // 2) Moving average on top of EMA output
        val (latMa, longMa) = ma.add(latDb, longDb)

        return GSmoothedSample(
            rawLatG = rawLatG,
            rawLongG = rawLongG,
            latG = latMa,
            longG = longMa
        )
    }
}


