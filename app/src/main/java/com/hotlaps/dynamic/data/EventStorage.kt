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

        return Event(
            id = id,
            name = name,
            trackId = trackId,
            trackName = trackName,
            startTime = now,          // 👈 NEW: event start time
            displayName = name,       // 👈 NEW: human-friendly name (same as name for now)
            createdUtcMs = now,       // when event began
            notes = null
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
                    "intervalMs,utcMs,trackName,eventName,cornerIndex,visitNumber,latG,longG,zG,gSum\n"
                )
            }


            // Write one CSV line for this sample
            val line = buildString {
                append(sample.intervalMs)
                append(',')
                append(sample.utcMs)
                append(',')
                append(sample.trackName)
                append(',')
                append(sample.eventName)
                append(',')
                append(sample.cornerIndex)
                append(',')
                append(sample.visitNumber)
                append(',')
                append(sample.latG)   // lat first
                append(',')
                append(sample.longG)  // then long
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


    fun updateEventNameInCsv(context: Context, eventId: Long, newName: String) {
        val dir = eventsDir(context) ?: return
        val file = File(dir, "event_${eventId}.csv")
        if (!file.exists()) {
            Log.w(TAG, "updateEventNameInCsv: no CSV found for eventId=$eventId")
            return
        }

        try {
            val lines = file.readLines()
            if (lines.isEmpty()) return

            val header = lines[0]
            val updatedLines = mutableListOf<String>()
            updatedLines.add(header)

            // We know header is:
            // intervalMs,utcMs,trackName,eventName,cornerIndex,visitNumber,latG,longG,zG,gSum
            val EVENT_NAME_INDEX = 3

            for (i in 1 until lines.size) {
                val line = lines[i]
                if (line.isBlank()) {
                    updatedLines.add(line)
                    continue
                }

                val parts = line.split(',')
                if (parts.size <= EVENT_NAME_INDEX) {
                    // malformed row, keep as-is
                    updatedLines.add(line)
                    continue
                }

                val mutable = parts.toMutableList()
                mutable[EVENT_NAME_INDEX] = newName
                updatedLines.add(mutable.joinToString(","))
            }

            file.writeText(updatedLines.joinToString("\n"))
            Log.d(TAG, "updateEventNameInCsv: updated eventName for eventId=$eventId")
        } catch (e: Exception) {
            Log.e(TAG, "updateEventNameInCsv: error updating CSV for eventId=$eventId", e)
        }
    }


    // listing files to see what events exist

    fun listEventFiles(context: Context): List<File> {
        val dir = eventsDir(context) ?: return emptyList()
        val files = dir.listFiles() ?: return emptyList()

        return files.filter { file ->
            file.isFile &&
                    file.name.startsWith("event_") &&
                    file.name.endsWith(".csv")
        }
    }




}
