
package com.hotlaps.dynamic.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class PrefsRepo {
    // default 0.30g just as a visible example
    private val _brakeThreshG = MutableStateFlow(0.30f)
    val brakeThreshG: StateFlow<Float> = _brakeThreshG

    fun setBrakeThreshG(value: Float) {
        _brakeThreshG.value = value
    }
}
