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
    // speedInterpolator is no longer used in appendSample, but we can keep
    // the class around if we want to reuse it later.
    private val speedInterpolator = SpeedInterpolator()

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
            startTime = now,          // event start time
            displayName = name,       // human-friendly name (same as name for now)
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

                // For now, just write whatever speed was present in the sample.
                // (Offline interpolation pass will refine this later.)
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
            // timestampMs,deltaMs,localTime,trackName,eventName,...
            val EVENT_NAME_INDEX = 4  // NOTE: index in our new header if needed

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

            file.writeText(updatedLines.joinToString("\n") + "\n")
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
                // We expect the full 19-column format written by appendSample()
                if (parts.size < 19) continue

                // Column indices must match the header:
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
            Log.w(TAG, "renameEventFile: old file not found for eventId=$eventId at ${oldFile.absolutePath}")
            return
        }

        // 1) Sanitize the user-entered name for filesystem safety
        val safeBase = newName
            .replace("[^A-Za-z0-9 _-]".toRegex(), "_")
            .replace(" +".toRegex(), " ")
            .trim()
            .ifBlank { "Event" }

        // 2) Start with "<safeBase>.csv"
        var targetName = "$safeBase.csv"
        var newFile = File(dir, targetName)

        // 3) If a file with that name already exists, add "(2)", "(3)", etc.
        var suffix = 2
        while (newFile.exists()) {
            targetName = "$safeBase ($suffix).csv"
            newFile = File(dir, targetName)
            suffix++
        }

        try {
            val ok = oldFile.renameTo(newFile)
            if (ok) {
                Log.d(TAG, "renameEventFile: renamed to ${newFile.name}")
            } else {
                Log.e(TAG, "renameEventFile: renameTo() failed for ${oldFile.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "renameEventFile: exception while renaming", e)
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

            // CSV layout (19 columns):
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

            // Write the corner + visit + Apex flag into the columns
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

    /**
     * Offline pass: recompute the `speed` column in an event CSV so that it is
     * linearly interpolated between "anchor" speeds (rows where speed changes).
     *
     * This operates directly on the CSV file:
     *   - reads all lines
     *   - finds spans between speed changes
     *   - rewrites the speed column (index 14) for each row in those spans
     *
     * Returns true on success, false on any error.
     */
    /**
     * Offline pass: recompute the `speed` column in an event CSV so that it is
     * linearly interpolated between GPS "anchor" points.
     *
     * We treat each change in (gpsLat,gpsLon) as an anchor. For each consecutive
     * pair of anchors [i0, i1], we linearly interpolate speed for ALL rows in
     * the inclusive range i0..i1 based on their timestamps.
     *
     * Returns true on success, false on any error.
     */
    fun recomputeInterpolatedSpeedForEvent(context: Context, eventId: Long): Boolean {
        val dir = eventsDir(context) ?: return false
        val file = File(dir, "event_${eventId}.csv")
        if (!file.exists()) {
            Log.w(TAG, "recomputeInterpolatedSpeedForEvent: no CSV found for eventId=$eventId")
            return false
        }

        try {
            val lines = file.readLines()
            if (lines.size <= 1) {
                Log.w(TAG, "recomputeInterpolatedSpeedForEvent: file has no data rows")
                return false
            }

            val header = lines[0]
            val dataLines = lines.subList(1, lines.size)

            // Parse data rows into mutable token lists so we can overwrite speed.
            val rows = mutableListOf<MutableList<String>>()
            val timestamps = mutableListOf<Long>()
            val speeds = mutableListOf<Double?>()
            val gpsLat = mutableListOf<Double?>()
            val gpsLon = mutableListOf<Double?>()

            // Column indices (must match header written by appendSample)
            val IDX_TIMESTAMP = 0
            val IDX_GPS_LAT = 5
            val IDX_GPS_LON = 6
            val IDX_SPEED = 14

            for (line in dataLines) {
                if (line.isBlank()) continue

                val parts = line.split(',').toMutableList()
                if (parts.size <= IDX_SPEED) {
                    // Malformed row, keep as-is and skip from interpolation
                    rows.add(parts)
                    timestamps.add(0L)
                    speeds.add(null)
                    gpsLat.add(null)
                    gpsLon.add(null)
                    continue
                }

                val utcMs = parts[IDX_TIMESTAMP].toLongOrNull()
                val lat = parts[IDX_GPS_LAT].toDoubleOrNull()
                val lon = parts[IDX_GPS_LON].toDoubleOrNull()
                val speed = parts[IDX_SPEED].toDoubleOrNull()

                rows.add(parts)
                timestamps.add(utcMs ?: 0L)
                speeds.add(speed)
                gpsLat.add(lat)
                gpsLon.add(lon)
            }

            if (rows.isEmpty()) {
                Log.w(TAG, "recomputeInterpolatedSpeedForEvent: no parsable rows")
                return false
            }

            // Helper: compare doubles with small tolerance
            fun approxEqual(a: Double?, b: Double?, eps: Double = 1e-9): Boolean {
                if (a == null && b == null) return true
                if (a == null || b == null) return false
                return kotlin.math.abs(a - b) <= eps
            }

            // 1) Build list of "anchor" indices where GPS changes
            val anchorIndices = mutableListOf<Int>()

            // Always treat the first row as an anchor if it has a GPS fix
            if (gpsLat[0] != null && gpsLon[0] != null) {
                anchorIndices.add(0)
            }

            for (i in 1 until rows.size) {
                val latPrev = gpsLat[i - 1]
                val lonPrev = gpsLon[i - 1]
                val latCur = gpsLat[i]
                val lonCur = gpsLon[i]

                // If GPS is missing, skip
                if (latCur == null || lonCur == null) continue
                if (latPrev == null || lonPrev == null) {
                    // First valid GPS after a gap -> new anchor
                    anchorIndices.add(i)
                    continue
                }

                // If either lat or lon changed beyond epsilon, treat as new anchor
                val latChanged = !approxEqual(latCur, latPrev)
                val lonChanged = !approxEqual(lonCur, lonPrev)

                if (latChanged || lonChanged) {
                    anchorIndices.add(i)
                }
            }

            if (anchorIndices.size < 2) {
                // Not enough distinct GPS points to interpolate between; nothing to do.
                Log.w(
                    TAG,
                    "recomputeInterpolatedSpeedForEvent: only ${anchorIndices.size} GPS anchor(s); skipping"
                )
                return false
            }

            // Make sure the last row is included as an anchor if it has GPS
            val lastIdx = rows.lastIndex
            if (!anchorIndices.contains(lastIdx) &&
                gpsLat[lastIdx] != null && gpsLon[lastIdx] != null
            ) {
                anchorIndices.add(lastIdx)
            }

            // 2) Interpolate speed between each consecutive pair of anchors
            val newSpeeds = speeds.toMutableList()

            for (a in 0 until anchorIndices.size - 1) {
                val i0 = anchorIndices[a]
                val i1 = anchorIndices[a + 1]
                if (i0 < 0 || i1 <= i0 || i1 >= rows.size) continue

                val v0 = speeds[i0]
                val v1 = speeds[i1]
                val t0 = timestamps[i0].toDouble()
                val t1 = timestamps[i1].toDouble()

                if (v0 == null || v1 == null) {
                    // Can't interpolate this span; leave it as-is
                    continue
                }
                if (t1 <= t0) {
                    // Degenerate / out-of-order timestamps; skip this span
                    continue
                }

                val denom = t1 - t0
                for (i in i0..i1) {
                    val ti = timestamps[i].toDouble()
                    val u = ((ti - t0) / denom).coerceIn(0.0, 1.0)
                    newSpeeds[i] = v0 + (v1 - v0) * u
                }
            }

            // 3) Write interpolated speeds back into the rows.
            for (i in rows.indices) {
                val s = newSpeeds[i]
                rows[i][IDX_SPEED] = s?.toString() ?: ""
            }

            // 4) Rebuild file with header + updated data rows.
            val newContent = buildString {
                append(header); append('\n')
                for (row in rows) {
                    append(row.joinToString(",")); append('\n')
                }
            }

            file.writeText(newContent)
            Log.d(TAG, "recomputeInterpolatedSpeedForEvent: updated speeds for eventId=$eventId")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "recomputeInterpolatedSpeedForEvent: error processing CSV for eventId=$eventId", e)
            return false
        }
    }

}

// Simple helper class; currently unused by EventStorage, but kept for future use.
class SpeedInterpolator {

    private var lastGpsUtc: Long? = null
    private var lastGpsSpeed: Double? = null

    private var nextGpsUtc: Long? = null
    private var nextGpsSpeed: Double? = null

    fun registerGpsFix(utc: Long, speed: Double) {
        // Move next → last
        if (nextGpsUtc != null) {
            lastGpsUtc = nextGpsUtc
            lastGpsSpeed = nextGpsSpeed
        }

        nextGpsUtc = utc
        nextGpsSpeed = speed
    }

    fun interpolateSpeed(sampleUtc: Long): Double? {
        val t0 = lastGpsUtc
        val v0 = lastGpsSpeed
        val t1 = nextGpsUtc
        val v1 = nextGpsSpeed

        if (t0 == null || v0 == null || t1 == null || v1 == null) {
            // Not enough anchors to interpolate
            return v1
        }

        if (t1 == t0) return v1

        val t = (sampleUtc - t0).toDouble() / (t1 - t0).toDouble()
        return v0 + t * (v1 - t0)
    }
}
