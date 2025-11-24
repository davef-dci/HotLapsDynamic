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
                    "intervalMs,utcMs,localTime,trackName,eventName," +
                            "cornerIndex,cornerName,visitNumber,latG,longG,zG,gSum,gpsLat,gpsLon," +
                            "insideCornerTrigger,closestCornerIndex,distanceToClosestCornerM\n"
                )
            }


            val localTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .apply { timeZone = TimeZone.getDefault() }
                .format(Date(sample.utcMs))


            // Write one CSV line for this sample
            val line = buildString {
                append(sample.intervalMs); append(',')
                append(sample.utcMs); append(',')
                append(localTime); append(',')
                append(sample.trackName); append(',')
                append(sample.eventName); append(',')
                append(sample.cornerIndex); append(',')
                append(sample.visitNumber); append(',')
                append(sample.latG); append(',')
                append(sample.longG); append(',')
                append(sample.zG); append(',')
                append(sample.gSum); append(',')
                append(sample.gpsLat); append(',')
                append(sample.gpsLon)
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
            file.isFile && file.name.endsWith(".csv", ignoreCase = true)
        }.sortedBy { it.name.lowercase(Locale.getDefault()) }

    }



    fun loadSamplesFromCsv(file: File): List<EventSample> {
        val result = mutableListOf<EventSample>()

        try {
            val lines = file.readLines()
            if (lines.isEmpty()) return emptyList()

            // First line is the header: skip it
            for (i in 1 until lines.size) {
                val line = lines[i]
                if (line.isBlank()) continue

                val parts = line.split(',')
                // We expect at least 13 columns with the new format
                if (parts.size < 13) continue

                val intervalMs = parts[0].toLongOrNull() ?: continue
                val utcMs      = parts[1].toLongOrNull() ?: continue
                // parts[2] = localTime (we can ignore or store separately if you like)
                val trackName  = parts[3]
                val eventName  = parts[4]
                val cornerIdx  = parts[5].toIntOrNull() ?: 0
                val visitNum   = parts[6].toIntOrNull() ?: 0
                val latG       = parts[7].toFloatOrNull() ?: 0f
                val longG      = parts[8].toFloatOrNull() ?: 0f
                val zG         = parts[9].toFloatOrNull() ?: 0f
                val gSum       = parts[10].toFloatOrNull() ?: 0f
                val gpsLat     = parts[11].toDoubleOrNull() ?: 0.0
                val gpsLon     = parts[12].toDoubleOrNull() ?: 0.0

                val sample = EventSample(
                    eventId = 0L,   // <<< keep whatever you were using before here
                    trackName = trackName,
                    eventName = eventName,
                    cornerIndex = cornerIdx,
                    visitNumber = visitNum,
                    intervalMs = intervalMs,
                    utcMs = utcMs,
                    longG = longG,
                    latG = latG,
                    zG = zG,
                    gSum = gSum,
                    gpsLat = gpsLat,
                    gpsLon = gpsLon
                )

                result.add(sample)
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadSamplesFromCsv: error reading ${file.name}", e)
            return emptyList()
        }

        return result
    }

    /**
     * Later we will use this to retroactively tag CSV rows for samples that
     * occurred just BEFORE the apex, so they get cornerIndex/visitNumber set.
     *
     * For now this is just a stub so the call site can compile.
     */
    fun backfillCornerSamplesInCsv(
        context: Context,
        eventId: Long,
        cornerIndex: Int,
        visitNumber: Int,
        startUtcMs: Long,
        apexUtcMs: Long
    ) {
        val dir = eventsDir(context) ?: run {
            Log.w(TAG, "backfillCornerSamplesInCsv: eventsDir is null")
            return
        }

        val file = File(dir, "event_${eventId}.csv")
        if (!file.exists()) {
            Log.w(
                TAG,
                "backfillCornerSamplesInCsv: no CSV file found for eventId=$eventId " +
                        "(corner=$cornerIndex visit=$visitNumber)"
            )
            return
        }

        Log.d(
            TAG,
            "backfillCornerSamplesInCsv: will backfill pre-apex rows for " +
                    "eventId=$eventId corner=$cornerIndex visit=$visitNumber, " +
                    "window=[$startUtcMs .. $apexUtcMs], file=${file.name}"
        )

        Log.d(
            TAG,
            "backfillCornerSamplesInCsv: will backfill pre-apex rows for " +
                    "eventId=$eventId corner=$cornerIndex visit=$visitNumber, " +
                    "window=[$startUtcMs .. $apexUtcMs], file=${file.name}"
        )

        val lines: MutableList<String> = try {
            file.readLines().toMutableList()
        } catch (e: Exception) {
            Log.e(
                TAG,
                "backfillCornerSamplesInCsv: error reading CSV for eventId=$eventId",
                e
            )
            return
        }

        if (lines.isEmpty()) {
            Log.w(
                TAG,
                "backfillCornerSamplesInCsv: CSV for eventId=$eventId is empty"
            )
            return
        }

        Log.d(
            TAG,
            "backfillCornerSamplesInCsv: loaded ${lines.size} line(s) from ${file.name}"
        )

        Log.d(
            TAG,
            "backfillCornerSamplesInCsv: loaded ${lines.size} line(s) from ${file.name}"
        )

        // We know the header is:
        // intervalMs,utcMs,localTime,trackName,eventName,
        // cornerIndex,visitNumber,latG,longG,zG,gSum,gpsLat,gpsLon
        val UTC_MS_INDEX = 1
        val CORNER_INDEX_INDEX = 5
        val VISIT_NUMBER_INDEX = 6

        var candidateCount = 0
        var updatedCount = 0

        // Skip header at index 0; data rows start at 1
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isBlank()) continue

            val parts = line.split(',')
            if (parts.size <= VISIT_NUMBER_INDEX) continue

            val utcMs = parts[UTC_MS_INDEX].toLongOrNull() ?: continue
            val cornerVal = parts[CORNER_INDEX_INDEX].toIntOrNull() ?: 0
            val visitVal = parts[VISIT_NUMBER_INDEX].toIntOrNull() ?: 0

            // Only consider rows in our window [startUtcMs, apexUtcMs]
            if (utcMs < startUtcMs || utcMs > apexUtcMs) continue

            // Only interested in rows that do NOT already belong to a corner
            if (cornerVal == 0 && visitVal == 0) {
                candidateCount++

                // Make a mutable copy so we can edit fields
                val cols = parts.toMutableList()
                cols[CORNER_INDEX_INDEX] = cornerIndex.toString()
                cols[VISIT_NUMBER_INDEX] = visitNumber.toString()

                // Re-join into a CSV line and store back
                lines[i] = cols.joinToString(",")

                updatedCount++
            }
        }

        Log.d(
            TAG,
            "backfillCornerSamplesInCsv: found $candidateCount CSV row(s) in pre-apex " +
                    "window; updated $updatedCount row(s) for corner=$cornerIndex visit=$visitNumber"
        )

        // If we changed anything, write the updated lines back to the file
        if (updatedCount > 0) {
            try {
                file.writeText(lines.joinToString("\n"))
                Log.d(
                    TAG,
                    "backfillCornerSamplesInCsv: wrote updated CSV for eventId=$eventId"
                )
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "backfillCornerSamplesInCsv: error writing updated CSV for eventId=$eventId",
                    e
                )
            }
        }


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


}
