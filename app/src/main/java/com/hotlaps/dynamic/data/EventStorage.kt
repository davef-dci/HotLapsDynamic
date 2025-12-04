package com.hotlaps.dynamic.data

import android.content.Context
import android.util.Log
import com.hotlaps.dynamic.model.Event
import com.hotlaps.dynamic.model.EventSample
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import androidx.core.content.FileProvider
import android.content.Intent


/**
 * Responsible for saving and loading Event sessions.
 *
 * This mirrors TrackStorage:
 *    /Android/data/com.hotlaps.dynamic/files/events/
 */
object EventStorage {

    private const val TAG = "EventStorage"

    // Single lock for all event CSV file access.
    // We only write one event at a time, so a global lock is fine.
    private val fileLock = Any()

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
    fun appendSample(
        context: Context,
        sample: EventSample,
        cornerTriggerRadiusM: Double
    ) {
        synchronized(fileLock) {
            val dir = eventsDir(context) ?: return

            // One CSV file per event:
            //   event_<eventId>.csv
            val file = File(dir, "event_${sample.eventId}.csv")
            val isNewFile = !file.exists()

            try {
                // If it's a brand-new file, write a header row first.
                if (isNewFile) {
                    file.appendText(
                        "timestampMs,deltaMs,localTime,trackName,eventName," +
                                "gpsLat,gpsLon,closestCornerIndex,distanceToClosestCornerM," +
                                "rawLatG,rawLongG,latG,longG,gSum," +
                                "speed," +
                                "cornerIndex,cornerName,visitNumber,Apex\n"
                    )
                }


                val localTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    .apply { timeZone = TimeZone.getDefault() }
                    .format(Date(sample.utcMs))


                val speedStr = sample.speedMps?.toString() ?: ""


                // Write one CSV line for this sample
                val line = buildString {
                    // Time: timestampMs (UTC), deltaMs (interval)
                    append(sample.utcMs); append(',')       // timestampMs
                    append(sample.intervalMs); append(',')  // deltaMs

                    // Local time (human-readable)
                    append(localTime); append(',')

                    // Event metadata
                    append(sample.trackName); append(',')
                    append(sample.eventName); append(',')

                    // Position / corner proximity
                    append(sample.gpsLat); append(',')
                    append(sample.gpsLon); append(',')
                    append(sample.closestCornerIndex); append(',')
                    append(sample.distanceToClosestCornerM); append(',')

                    // Raw G then smoothed G
                    append(sample.rawLatG); append(',')
                    append(sample.rawLongG); append(',')
                    append(sample.latG); append(',')
                    append(sample.longG); append(',')
                    append(sample.gSum); append(',')

                    // Speed
                    append(speedStr); append(',')

                    // Corner / apex metadata (apex-only, usually)
                    append(sample.cornerIndex); append(',')
                    append(sample.cornerName); append(',')
                    append(sample.visitNumber); append(',')

                    // Apex column: "True" only on apex sample, blank otherwise
                    append(if (sample.isApexSample) "True" else "")

                    append('\n')
                }






                file.appendText(line)
            } catch (e: Exception) {
                Log.e(TAG, "appendSample: error writing sample for event ${sample.eventId}", e)
            }
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

            file.writeText(lines.joinToString("\n") + "\n")
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
            file.isFile && file.name.endsWith(".csv", ignoreCase = true)
        }.sortedBy { it.name.lowercase(Locale.getDefault()) }

    }


    fun loadSamplesFromCsv(file: File): List<EventSample> {
        val result = mutableListOf<EventSample>()

        try {
            val lines = file.readLines()
            if (lines.isEmpty()) return emptyList()

            // First line is the header
            for (i in 1 until lines.size) {
                val line = lines[i]
                if (line.isBlank()) continue

                val parts = line.split(',')
                // We expect the full 22-column format written by appendSample()
                if (parts.size < 19) continue

                // Column indices must match the NEW header:
// 0 timestampMs (utcMs)
// 1 deltaMs (intervalMs)
// 2 localTime (ignored here)
// 3 trackName
// 4 eventName
// 5 gpsLat
// 6 gpsLon
// 7 closestCornerIndex
// 8 distanceToClosestCornerM
// 9 rawLatG
// 10 rawLongG
// 11 latG
// 12 longG
// 13 gSum
// 14 speed
// 15 cornerIndex
// 16 cornerName
// 17 visitNumber
// 18 Apex ("True" or "")

                val utcMs = parts[0].toLongOrNull() ?: continue
                val intervalMs = parts[1].toLongOrNull() ?: 0L
// parts[2] = localTime (ignored)

                val trackName = parts[3]
                val eventName = parts[4]

                val gpsLat = parts[5].toDoubleOrNull() ?: 0.0
                val gpsLon = parts[6].toDoubleOrNull() ?: 0.0

                val closestCornerIndex = parts[7].toIntOrNull() ?: 0
                val distanceToClosestCornerM = parts[8].toDoubleOrNull() ?: 0.0

                val rawLatG = parts[9].toFloatOrNull() ?: 0f
                val rawLongG = parts[10].toFloatOrNull() ?: 0f
                val latG = parts[11].toFloatOrNull() ?: 0f
                val longG = parts[12].toFloatOrNull() ?: 0f
                val gSum = parts[13].toFloatOrNull() ?: 0f

                val speedMps = parts[14].toDoubleOrNull()

                val cornerIdx = parts[15].toIntOrNull() ?: 0
                val cornerName = parts[16]
                val visitNum = parts[17].toIntOrNull() ?: 0

                val isApexSample = parts[18].equals("true", ignoreCase = true)


                val sample = EventSample(
                    eventId = 0L,   // arbitrary when loading loose CSV
                    cornerIndex = cornerIdx,
                    visitNumber = visitNum,
                    cornerName = cornerName,
                    intervalMs = intervalMs,
                    utcMs = utcMs,
                    longG = longG,
                    latG = latG,
                    zG = 0f,  // zG is no longer stored in CSV; set to 0f or drop from model later
                    gSum = gSum,
                    trackName = trackName,
                    eventName = eventName,
                    gpsLat = gpsLat,
                    gpsLon = gpsLon,
                    speedMps = speedMps,
                    closestCornerIndex = closestCornerIndex,
                    distanceToClosestCornerM = distanceToClosestCornerM,
                    rawLatG = rawLatG,
                    rawLongG = rawLongG,
                    isApexSample = isApexSample
                )


                result.add(sample)
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadSamplesFromCsv: error reading ${file.name}", e)
            return emptyList()
        }

        return result
    }


    fun renameEventFile(context: Context, eventId: Long, newName: String) {
        val dir = eventsDir(context) ?: return
        val oldFile = File(dir, "event_${eventId}.csv")

        if (!oldFile.exists()) {
            Log.w(TAG, "renameEventFile: old file not found for eventId=$eventId")
            return
        }

        // Turn the name into a filesystem-safe filename
        val safe = newName
            .replace("[^A-Za-z0-9 _-]".toRegex(), "_")
            .replace(" +".toRegex(), " ")
            .trim()

        val newFile = File(dir, "${safe}_$eventId.csv")

        try {
            oldFile.renameTo(newFile)
            Log.d(TAG, "renameEventFile: renamed to ${newFile.name}")
        } catch (e: Exception) {
            Log.e(TAG, "renameEventFile: error renaming file", e)
        }
    }

    fun deleteEvents(context: Context, filesToDelete: List<File>): Int {
        val dir = eventsDir(context) ?: return 0
        var deleted = 0

        for (f in filesToDelete) {
            // Safety: only delete files in our eventsDir
            if (f.parentFile == dir && f.isFile && f.delete()) {
                deleted++
            }
        }

        Log.d(TAG, "deleteEvents: deleted $deleted of ${filesToDelete.size} requested")
        return deleted
    }

    fun shareEventCsv(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            file
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(
            Intent.createChooser(intent, "Share CSV")
        )

    }

    fun shareMultipleEventCsv(context: Context, files: List<File>) {
        if (files.isEmpty()) return

        // Turn files into URIs using the same FileProvider config
        val uris = ArrayList<android.net.Uri>()
        files.forEach { file ->
            val uri = FileProvider.getUriForFile(
                context,
                context.packageName + ".fileprovider",
                file
            )
            uris.add(uri)
        }

        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/csv"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(
            Intent.createChooser(intent, "Share CSV files")
        )
    }


    fun tagApexSampleInCsv(
        context: Context,
        eventId: Long,
        cornerIndex: Int,
        visitNumber: Int,
        apexUtcMs: Long,
        cornerName: String
    ) {
        val dir = eventsDir(context) ?: return
        val file = File(dir, "event_${eventId}.csv")
        if (!file.exists()) {
            Log.w(TAG, "tagApexSampleInCsv: CSV not found for eventId=$eventId")
            return
        }

        try {
            val lines = file.readLines()
            if (lines.size <= 1) return

            val dataLines = lines.toMutableList()

            var bestLineIndex = -1
            var bestError = Long.MAX_VALUE

            // NEW CSV layout (19 columns):
            // 0  timestampMs (utcMs)
            // 1  deltaMs
            // 2  localTime
            // 3  trackName
            // 4  eventName
            // 5  gpsLat
            // 6  gpsLon
            // 7  closestCornerIndex
            // 8  distanceToClosestCornerM
            // 9  rawLatG
            // 10 rawLongG
            // 11 latG
            // 12 longG
            // 13 gSum
            // 14 speed
            // 15 cornerIndex
            // 16 cornerName
            // 17 visitNumber
            // 18 Apex ("True" or "")

            // Remember: line 0 is header, data starts at 1
            for (i in 1 until dataLines.size) {
                val line = dataLines[i]
                if (line.isBlank()) continue

                val parts = line.split(',')
                if (parts.size < 19) continue   // width check

                val utcStr = parts[0]           // timestampMs is column 0
                val utcMs = utcStr.toLongOrNull() ?: continue

                val err = kotlin.math.abs(utcMs - apexUtcMs)
                if (err < bestError) {
                    bestError = err
                    bestLineIndex = i
                }
            }

            if (bestLineIndex == -1) {
                Log.w(TAG, "tagApexSampleInCsv: no matching row for apexUtcMs=$apexUtcMs")
                return
            }

            // Update that one row
            val originalParts = dataLines[bestLineIndex].split(',').toMutableList()
            if (originalParts.size < 19) return

            // Write the corner + visit + Apex flag into the NEW columns
            originalParts[15] = cornerIndex.toString()  // cornerIndex
            originalParts[16] = cornerName              // cornerName
            originalParts[17] = visitNumber.toString()  // visitNumber
            originalParts[18] = "True"                  // Apex column

            dataLines[bestLineIndex] = originalParts.joinToString(",")

            // Rewrite file (keep header + updated data)
            file.writeText(dataLines.joinToString("\n") + "\n")

            Log.d(
                TAG,
                "tagApexSampleInCsv: tagged apex at line=$bestLineIndex " +
                        "for eventId=$eventId, corner=$cornerIndex, visit=$visitNumber"
            )
        } catch (e: Exception) {
            Log.e(TAG, "tagApexSampleInCsv: error updating CSV for eventId=$eventId", e)
        }
    }

}
