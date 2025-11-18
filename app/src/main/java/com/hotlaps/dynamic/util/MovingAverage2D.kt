package com.hotlaps.dynamic.util

class MovingAverage2D(private var maxSamples: Int) {

    private val buffer = ArrayDeque<Pair<Float, Float>>()
    private var sumX = 0f
    private var sumY = 0f

    fun setWindowSize(newSize: Int) {
        maxSamples = newSize.coerceAtLeast(1)
        // Trim if needed when window shrinks
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

    fun clear() {
        buffer.clear()
        sumX = 0f
        sumY = 0f
    }
}
