package com.hotlaps.dynamic.data

/**
 * User-facing smoothing presets.
 *
 * index:
 *   0 = Off
 *   1 = Low
 *   2 = Medium
 *   3 = Heavy
 */
enum class SmoothingLevel(
    val index: Int,
    val displayName: String,
    val tauMs: Int,
    val windowSize: Int
) {
    Off(
        index = 0,
        displayName = "Off (Raw Values)",
        tauMs = 0,
        windowSize = 1
    ),
    Low(
        index = 1,
        displayName = "Low",
        tauMs = 100,
        windowSize = 3
    ),
    Medium(
        index = 2,
        displayName = "Medium",
        tauMs = 200,
        windowSize = 10
    ),
    Heavy(
        index = 3,
        displayName = "Heavy",
        tauMs = 500,
        windowSize = 15
    );

    companion object {
        /**
         * Safely map an Int (from DataStore) to a SmoothingLevel.
         */
        fun fromIndex(index: Int): SmoothingLevel = when (index) {
            0 -> Off
            1 -> Low
            2 -> Medium
            3 -> Heavy
            else -> Low // fallback, should not happen thanks to coerceIn
        }
    }
}
