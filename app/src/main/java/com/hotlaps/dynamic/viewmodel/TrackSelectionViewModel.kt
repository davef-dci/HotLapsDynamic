package com.hotlaps.dynamic.viewmodel

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import android.content.Context
import android.content.SharedPreferences
import com.hotlaps.dynamic.data.TrackStorage
import com.hotlaps.dynamic.model.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue


class TrackSelectionViewModel : ViewModel() {

    // Backing field that we can change inside the ViewModel
    private val _selectedTrack = MutableStateFlow<Track?>(null)

    // Public read-only view of the selected track
    val selectedTrack: StateFlow<Track?> get() = _selectedTrack

    private var prefs: SharedPreferences? = null

    /**
     * Restores the last selected track. At Gingerman (Apr 2026) the app restarted after a
     * freeze with no track selected, and the next session recorded no corners at all.
     */
    fun restore(context: Context) {
        val p = context.applicationContext.getSharedPreferences("track_selection", Context.MODE_PRIVATE)
        prefs = p
        if (_selectedTrack.value != null) return
        val id = p.getLong(KEY_TRACK_ID, -1L)
        if (id == -1L) return
        _selectedTrack.value = TrackStorage.listTracks(context).firstOrNull { it.id == id }?.track
    }

    // Call this when the user taps "Use Track"
    fun selectTrack(track: Track) {
        _selectedTrack.value = track
        prefs?.edit()?.putLong(KEY_TRACK_ID, track.id)?.apply()
    }

    // Call this if you ever want to clear the active track
    fun clear() {
        _selectedTrack.value = null
        prefs?.edit()?.remove(KEY_TRACK_ID)?.apply()
    }

    private companion object {
        const val KEY_TRACK_ID = "selected_track_id"
    }


    var trackBeingEdited: Track? by mutableStateOf(null)

    fun startEditingTrack(track: Track) {
        trackBeingEdited = track
    }

    fun clearEditingTrack() {
        trackBeingEdited = null
    }



}
