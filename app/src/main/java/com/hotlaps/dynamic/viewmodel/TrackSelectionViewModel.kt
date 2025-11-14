package com.hotlaps.dynamic.viewmodel

import androidx.lifecycle.ViewModel
import com.hotlaps.dynamic.model.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class TrackSelectionViewModel : ViewModel() {

    // Backing field that we can change inside the ViewModel
    private val _selectedTrack = MutableStateFlow<Track?>(null)

    // Public read-only view of the selected track
    val selectedTrack: StateFlow<Track?> get() = _selectedTrack

    // Call this when the user taps "Use Track"
    fun selectTrack(track: Track) {
        _selectedTrack.value = track
    }

    // Call this if you ever want to clear the active track
    fun clear() {
        _selectedTrack.value = null
    }
}
