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
    private fun eventsDir(context: Context): File? =
        FileHelper.eventsDir(context)


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
// Append a sample (CSV per event)
// -----------------------
    fun appendSample(context: Context, sample: EventSample) {
        val dir = eventsDir(context) ?: return

        // One CSV file per event:
        //   event_<eventId>.csv
        val file = File(dir, "event_${sample.eventId}.csv")
        val isNewFile = !file.exists()

        try {
            // If it's a brand-new file, write a header row first.
            if (isNewFile) {
                file.appendText(
                    "intervalMs,utcMs,trackName,cornerIndex,visitNumber,longG,latG,zG,gSum\n"
                )
            }

            // Write one CSV line for this sample
            val line = buildString {
                append(sample.intervalMs)
                append(',')
                append(sample.utcMs)
                append(',')
                append(sample.trackName)   // NEW
                append(',')
                append(sample.cornerIndex)
                append(',')
                append(sample.visitNumber)
                append(',')
                append(sample.longG)
                append(',')
                append(sample.latG)
                append(',')
                append(sample.zG)
                append(',')
                append(sample.gSum)
                append('\n')
            }

            file.appendText(line)
        } catch (e: Exception) {
            Log.e(TAG, "appendSample: error writing sample for event ${sample.eventId}", e)
        }
    }


    // -----------------------
    // (Future) Load an event file
    // -----------------------
    fun loadEvent(context: Context, eventId: Long): List<EventSample> {
        // Implementation comes later
        return emptyList()
    }


    // -----------------------
    // Housekeeping helpers
    // -----------------------

    /**
     * Delete all files in the events directory.
     *
     * @return number of files successfully deleted.
     */
    fun deleteAllEvents(context: Context): Int {
        val dir = eventsDir(context) ?: return 0
        val files = dir.listFiles() ?: return 0

        var deleted = 0
        for (f in files) {
            if (f.isFile && f.delete()) {
                deleted++
            }
        }
        Log.d(TAG, "deleteAllEvents: deleted $deleted file(s) from ${dir.absolutePath}")
        return deleted
    }

    /**
     * Copies all event files from the app-private directory into:
     *    /Download/HotLapsDynamic/events/
     *
     * Returns the number of files successfully copied.
     */
    fun exportAllEventsToPublicDownloads(context: Context): Int {
        val srcDir = eventsDir(context) ?: return 0
        val dstDir = FileHelper.publicEventsExportDir() ?: return 0

        val files = srcDir.listFiles() ?: return 0
        var copied = 0

        for (src in files) {
            if (!src.isFile) continue

            val dst = java.io.File(dstDir, src.name)
            try {
                src.copyTo(dst, overwrite = true)
                copied++
            } catch (e: Exception) {
                Log.e(TAG, "exportAllEvents: failed copying ${src.name}", e)
            }
        }

        Log.d(
            TAG,
            "exportAllEvents: copied $copied file(s) to ${dstDir.absolutePath}"
        )
        return copied
    }


}
