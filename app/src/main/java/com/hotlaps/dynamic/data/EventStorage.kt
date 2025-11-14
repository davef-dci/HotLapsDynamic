package com.hotlaps.dynamic.data

import android.content.Context
import android.util.Log
import com.hotlaps.dynamic.model.Event
import com.hotlaps.dynamic.model.EventSample
import java.io.File

/**
 * Responsible for saving and loading Event sessions.
 *
 * This mirrors TrackStorage:
 *    /Android/data/com.hotlaps.dynamic/files/events/
 */
object EventStorage {

    private const val TAG = "EventStorage"

    // -----------------------
    // Directory management
    // -----------------------
    private fun eventsDir(context: Context): File? {
        val base = context.getExternalFilesDir(null)
        if (base == null) {
            Log.e(TAG, "eventsDir: getExternalFilesDir(null) returned null")
            return null
        }

        val dir = File(base, "events")
        if (!dir.exists()) {
            if (!dir.mkdirs()) {
                Log.e(TAG, "eventsDir: failed to create ${dir.absolutePath}")
                return null
            }
        }
        return dir
    }

    // -----------------------
    // Event creation
    // -----------------------
    fun createEvent(context: Context, name: String, trackId: Long, trackName: String): Event {
        val id = System.currentTimeMillis()  // simple unique ID
        val now = System.currentTimeMillis()

        // Placeholder Event object
        return Event(
            id = id,
            name = name,
            trackId = trackId,
            trackName = trackName,
            createdUtcMs = now
        )
    }

    // -----------------------
    // Append a sample (not implemented yet)
    // -----------------------
    fun appendSample(context: Context, sample: EventSample) {
        // Implementation comes later
        // (write a row of CSV or binary to event_<id>.csv)
    }

    // -----------------------
    // (Future) Load an event file
    // -----------------------
    fun loadEvent(context: Context, eventId: Long): List<EventSample> {
        // Implementation comes later
        return emptyList()
    }
}
