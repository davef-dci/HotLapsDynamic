package com.hotlaps.dynamic.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class CalibState(val vec: Triple<Float, Float, Float>?)

class CalibRepo {
    // null = not calibrated yet
    private val _state = MutableStateFlow(CalibState(vec = null))
    val state: StateFlow<CalibState> = _state

    fun saveCalibration(x: Float, y: Float, z: Float) {
        _state.value = CalibState(Triple(x, y, z))
    }
}
