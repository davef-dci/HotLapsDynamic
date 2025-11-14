package com.hotlaps.dynamic.viewmodel

import androidx.lifecycle.ViewModel
import com.hotlaps.dynamic.model.Event
import com.hotlaps.dynamic.model.EventSample
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Holds all Drive-mode state:
 *  - Current Event (if any)
 *  - Lap/corner tracking
 *  - Buffers of recent G-samples
 *  - GPS location
 *  - Logic for corner detection (later)
 *  - Logic for writing EventSamples (later)
 *
 * For now: empty skeleton.
 */
class DriveViewModel : ViewModel() {

    // Active driving event (null if not recording)
    private val _currentEvent = MutableStateFlow<Event?>(null)
    val currentEvent: StateFlow<Event?> get() = _currentEvent

    // Placeholder startEvent() and stopEvent()
    fun startEvent(event: Event) {
        _currentEvent.value = event
    }

    fun stopEvent() {
        _currentEvent.value = null
    }

    // Placeholder for receiving new samples (later)
    fun addSample(sample: EventSample) {
        // Will save to storage later
    }
}
