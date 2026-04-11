package com.hotlaps.dynamic.data

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import com.hotlaps.dynamic.model.Event
import com.hotlaps.dynamic.model.EventSample
import com.hotlaps.dynamic.util.GForceSmoother
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.sqrt

/**
 * EventStorage
 *
 * Responsible for writing/reading telemetry "event" CSV files.
 *
 * Key design rules:
 *  1) The *event CSV* is the source of truth for analysis inside the app.
 *  2) Share/export must NEVER overwrite the event CSV (export creates a separate cache file).
 *  3) Parsing must be tolerant of Apex encodings ("True", "1", "yes", etc.) for backward compatibility.
 */
object EventStorage {

    private const val TAG = "EventStorage"

    /**
     * Single lock for event CSV access. Recording appends should be serialized.
     */
    private val fileLock = Any()

    // ---------------------------------------------------------------------------------------------
    // Write buffer (Option 4: buffered writes + periodic backup)
    // ---------------------------------------------------------------------------------------------

    /** How many samples to accumulate before flushing to disk (~5 seconds at 10 Hz). */
    private const val FLUSH_INTERVAL_SAMPLES = 50

    /** In-memory write buffers keyed by eventId. Guarded by fileLock. */
    private val writeBuffers = mutableMapOf<Long, StringBuilder>()

    /** Running sample count per eventId since last flush. Guarded by fileLock. */
    private val sampleCounts = mutableMapOf<Long, Int>()

    // ---------------------------------------------------------------------------------------------
    // CSV schema (single source of truth)
    // ---------------------------------------------------------------------------------------------

    // NOTE: If you add/reorder columns, update BOTH the header and indices below.
    private const val CSV_HEADER =
        "timestampMs,deltaMs,localTime,trackName,eventName," +
                "gpsLat,gpsLon,closestCornerIndex,distanceToClosestCornerM," +
                "rawLatG,rawLongG,latG,longG,gSum," +
                "speed," +
                "cornerIndex,cornerName,visitNumber,Apex\n"

    // Column indices for parsing (must match CSV_HEADER above).
    private const val IDX_TIMESTAMP_MS = 0
    private const val IDX_DELTA_MS = 1
    // IDX_LOCAL_TIME = 2 (stored but not parsed)
    private const val IDX_TRACK_NAME = 3
    private const val IDX_EVENT_NAME = 4
    private const val IDX_GPS_LAT = 5
    private const val IDX_GPS_LON = 6
    private const val IDX_CLOSEST_CORNER_INDEX = 7
    private const val IDX_DIST_TO_CLOSEST_CORNER_M = 8
    private const val IDX_RAW_LAT_G = 9
    private const val IDX_RAW_LONG_G = 10
    private const val IDX_LAT_G = 11
    private const val IDX_LONG_G = 12
    private const val IDX_GSUM = 13
    private const val IDX_SPEED = 14
    private const val IDX_CORNER_INDEX = 15
    private const val IDX_CORNER_NAME = 16
    private const val IDX_VISIT_NUMBER = 17
    private const val IDX_APEX = 18

    private const val EXPECTED_COLS = 19

    // ---------------------------------------------------------------------------------------------
    // Directory helpers
    // ---------------------------------------------------------------------------------------------

    private fun eventsDir(context: Context): File? = FileHelper.eventsDir(context)

    // ---------------------------------------------------------------------------------------------
    // Event creation (model only)
    // ---------------------------------------------------------------------------------------------

    /**
     * Creates an Event model. (Does not write any files.)
     * Note: `context` currently unused but kept for signature stability.
     */
    fun createEvent(context: Context, name: String, trackId: Long, trackName: String): Event {
        val id = System.currentTimeMillis()
        val now = System.currentTimeMillis()
        return Event(
            id = id,
            name = name,
            trackId = trackId,
            trackName = trackName,
            startTime = now,
            displayName = name,
            createdUtcMs = now,
            notes = null
        )
    }

    // ---------------------------------------------------------------------------------------------
    // CSV append (recording path)
    // ---------------------------------------------------------------------------------------------

    /**
     * Append one telemetry sample to the per-event CSV.
     *
     * IMPORTANT:
     *  - Called during recording; keep fast.
     *  - We store whatever GPS values exist at record time.
     *  - We do NOT do interpolation here.
     *
     * Note: `cornerTriggerRadiusM` currently unused but kept for signature stability.
     */
    fun appendSample(
        context: Context,
        sample: EventSample,
        cornerTriggerRadiusM: Double
    ) {
        synchronized(fileLock) {
            val dir = eventsDir(context) ?: return
            val file = File(dir, "event_${sample.eventId}.csv")
            val isNewFile = !file.exists()

            try {
                // Write CSV header immediately on file creation so the file is always valid
                // on disk even before the first buffer flush.
                if (isNewFile) {
                    file.appendText(CSV_HEADER)
                }

                val localTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    .apply { timeZone = TimeZone.getDefault() }
                    .format(Date(sample.utcMs))

                val speedStr = sample.speedMps?.toString() ?: ""
                val apexStr = if (sample.isApexSample) "1" else "0"

                val line = buildString {
                    append(sample.utcMs); append(',')
                    append(sample.intervalMs); append(',')
                    append(localTime); append(',')
                    append(sample.trackName); append(',')
                    append(sample.eventName); append(',')
                    append(sample.gpsLat); append(',')
                    append(sample.gpsLon); append(',')
                    append(sample.closestCornerIndex); append(',')
                    append(sample.distanceToClosestCornerM); append(',')
                    append(sample.rawLatG); append(',')
                    append(sample.rawLongG); append(',')
                    append(sample.latG); append(',')
                    append(sample.longG); append(',')
                    append(sample.gSum); append(',')
                    append(speedStr); append(',')
                    append(sample.cornerIndex); append(',')
                    append(sample.cornerName); append(',')
                    append(sample.visitNumber); append(',')
                    append(apexStr)
                    append('\n')
                }

                // Accumulate into buffer
                val buf = writeBuffers.getOrPut(sample.eventId) { StringBuilder() }
                buf.append(line)
                val count = (sampleCounts[sample.eventId] ?: 0) + 1
                sampleCounts[sample.eventId] = count

                // Flush to disk every FLUSH_INTERVAL_SAMPLES (~5 seconds at 10 Hz)
                if (count >= FLUSH_INTERVAL_SAMPLES) {
                    file.appendText(buf.toString())
                    buf.setLength(0)
                    sampleCounts[sample.eventId] = 0
                }

            } catch (e: Exception) {
                Log.e(TAG, "appendSample: error writing sample for event ${sample.eventId}", e)
            }
        }
    }

    /**
     * Flushes any buffered samples for [eventId] to disk immediately.
     * Call this before any operation that reads the CSV (apex tagging, speed
     * interpolation, export) and when recording stops.
     */
    fun flushBuffer(context: Context, eventId: Long) {
        synchronized(fileLock) {
            val buf = writeBuffers[eventId] ?: return
            if (buf.isEmpty()) return
            val dir = eventsDir(context) ?: return
            val file = File(dir, "event_${eventId}.csv")
            try {
                file.appendText(buf.toString())
                buf.setLength(0)
                sampleCounts[eventId] = 0
                Log.d(TAG, "flushBuffer: flushed buffer for event $eventId")
            } catch (e: Exception) {
                Log.e(TAG, "flushBuffer: error flushing event $eventId", e)
            }
        }
    }

    /**
     * Flushes the buffer then copies the event CSV to a timestamped backup file.
     * Backup name: event_<id>_backup.csv  (overwritten each time — only the latest is kept).
     * Safe to call from a background coroutine every N minutes during recording.
     */
    fun flushAndBackup(context: Context, eventId: Long) {
        flushBuffer(context, eventId)
        synchronized(fileLock) {
            val dir = eventsDir(context) ?: return
            val src = File(dir, "event_${eventId}.csv")
            if (!src.exists()) return
            val backup = File(dir, "event_${eventId}_backup.csv")
            try {
                src.copyTo(backup, overwrite = true)
                Log.d(TAG, "flushAndBackup: backup written for event $eventId (${src.length() / 1024} KB)")
            } catch (e: Exception) {
                Log.e(TAG, "flushAndBackup: failed for event $eventId", e)
            }
        }
    }

    /**
     * Cleans up in-memory buffer state for a finished event.
     * Call after stopEvent() once all flushes are complete.
     */
    fun clearBuffer(eventId: Long) {
        synchronized(fileLock) {
            writeBuffers.remove(eventId)
            sampleCounts.remove(eventId)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Listing / housekeeping
    // ---------------------------------------------------------------------------------------------

    fun listEventFiles(context: Context): List<File> {
        val dir = eventsDir(context) ?: return emptyList()
        val files = dir.listFiles() ?: return emptyList()

        return files
            .filter { it.isFile && it.name.endsWith(".csv", ignoreCase = true) }
            .sortedBy { it.name.lowercase(Locale.getDefault()) }
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

    fun deleteAllEvents(context: Context): Int {
        val dir = eventsDir(context) ?: return 0
        val files = dir.listFiles() ?: return 0

        var deleted = 0
        for (f in files) {
            if (f.isFile && f.delete()) deleted++
        }

        Log.d(TAG, "deleteAllEvents: deleted $deleted file(s) from ${dir.absolutePath}")
        return deleted
    }

    /**
     * Copies all event files from app-private events dir into:
     *   /Download/HotLapsDynamic/events/
     */
    fun exportAllEventsToPublicDownloads(context: Context): Int {
        val srcDir = eventsDir(context) ?: return 0
        val dstDir = FileHelper.publicEventsExportDir() ?: return 0

        val files = srcDir.listFiles() ?: return 0
        var copied = 0

        for (src in files) {
            if (!src.isFile) continue
            val dst = File(dstDir, src.name)
            try {
                src.copyTo(dst, overwrite = true)
                copied++
            } catch (e: Exception) {
                Log.e(TAG, "exportAllEventsToPublicDownloads: failed copying ${src.name}", e)
            }
        }

        Log.d(TAG, "exportAllEventsToPublicDownloads: copied $copied file(s) to ${dstDir.absolutePath}")
        return copied
    }

    // ---------------------------------------------------------------------------------------------
    // Editing existing event CSV (mutates event files by design)
    // ---------------------------------------------------------------------------------------------

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
            val updatedLines = ArrayList<String>(lines.size)
            updatedLines.add(header)

            for (i in 1 until lines.size) {
                val line = lines[i]
                if (line.isBlank()) {
                    updatedLines.add(line)
                    continue
                }

                val parts = line.split(',')
                if (parts.size <= IDX_EVENT_NAME) {
                    updatedLines.add(line) // keep malformed row
                    continue
                }

                val mutable = parts.toMutableList()
                mutable[IDX_EVENT_NAME] = newName
                updatedLines.add(mutable.joinToString(","))
            }

            file.writeText(updatedLines.joinToString("\n") + "\n")
            Log.d(TAG, "updateEventNameInCsv: updated eventName for eventId=$eventId")
        } catch (e: Exception) {
            Log.e(TAG, "updateEventNameInCsv: error updating CSV for eventId=$eventId", e)
        }
    }

    fun renameEventFile(context: Context, eventId: Long, newName: String) {
        val dir = eventsDir(context) ?: return
        val oldFile = File(dir, "event_${eventId}.csv")

        if (!oldFile.exists()) {
            Log.w(TAG, "renameEventFile: old file not found for eventId=$eventId at ${oldFile.absolutePath}")
            return
        }

        // Sanitize the name for filesystem safety
        val safeBase = newName
            .replace("[^A-Za-z0-9 _-]".toRegex(), "_")
            .replace(" +".toRegex(), " ")
            .trim()
            .ifBlank { "Event" }

        // Attempt "<safeBase>.csv", then "<safeBase> (2).csv", etc.
        var targetName = "$safeBase.csv"
        var newFile = File(dir, targetName)
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

    /**
     * Tags a single "best matching" row as the apex sample for a given corner visit.
     * Writes cornerIndex/cornerName/visitNumber, and sets Apex column to "True".
     */
    fun tagApexSampleInCsv(
        context: Context,
        eventId: Long,
        cornerIndex: Int,
        visitNumber: Int,
        apexUtcMs: Long,
        cornerName: String
    ) {
        // Flush any buffered samples first so this read sees the full dataset
        flushBuffer(context, eventId)

        val dir = eventsDir(context) ?: return
        val file = File(dir, "event_${eventId}.csv")
        if (!file.exists()) {
            Log.w(TAG, "tagApexSampleInCsv: CSV not found for eventId=$eventId")
            return
        }

        try {
            val lines = file.readLines()
            if (lines.size <= 1) return

            val dataLines = lines.toMutableList() // includes header at index 0
            var bestLineIndex = -1
            var bestError = Long.MAX_VALUE

            // Search for nearest timestamp row
            for (i in 1 until dataLines.size) {
                val line = dataLines[i]
                if (line.isBlank()) continue

                val parts = line.split(',')
                if (parts.size < EXPECTED_COLS) continue

                val utcMs = parts[IDX_TIMESTAMP_MS].toLongOrNull() ?: continue
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

            val parts = dataLines[bestLineIndex].split(',').toMutableList()
            if (parts.size < EXPECTED_COLS) return

            parts[IDX_CORNER_INDEX] = cornerIndex.toString()
            parts[IDX_CORNER_NAME] = cornerName
            parts[IDX_VISIT_NUMBER] = visitNumber.toString()
            parts[IDX_APEX] = "True"

            dataLines[bestLineIndex] = parts.joinToString(",")

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

    // ---------------------------------------------------------------------------------------------
    // CSV load (analysis)
    // ---------------------------------------------------------------------------------------------

    /**
     * Load samples from a CSV file. Used by EventViewerScreen and export smoothing.
     *
     * Apex parsing accepts multiple encodings for backwards compatibility:
     *   - "True"/"true"
     *   - "1"
     *   - "yes"/"y"
     *   - blank = false
     */
    fun loadSamplesFromCsv(file: File): List<EventSample> {
        val result = mutableListOf<EventSample>()

        try {
            val lines = file.readLines()
            if (lines.isEmpty()) return emptyList()

            for (i in 1 until lines.size) {
                val line = lines[i]
                if (line.isBlank()) continue

                val parts = line.split(',')
                if (parts.size < EXPECTED_COLS) continue

                val utcMs = parts[IDX_TIMESTAMP_MS].toLongOrNull() ?: continue
                val intervalMs = parts[IDX_DELTA_MS].toLongOrNull() ?: 0L

                val trackName = parts[IDX_TRACK_NAME]
                val eventName = parts[IDX_EVENT_NAME]

                val gpsLat = parts[IDX_GPS_LAT].toDoubleOrNull() ?: 0.0
                val gpsLon = parts[IDX_GPS_LON].toDoubleOrNull() ?: 0.0

                val closestCornerIndex = parts[IDX_CLOSEST_CORNER_INDEX].toIntOrNull() ?: 0
                val distanceToClosestCornerM = parts[IDX_DIST_TO_CLOSEST_CORNER_M].toDoubleOrNull() ?: 0.0

                val rawLatG = parts[IDX_RAW_LAT_G].toFloatOrNull() ?: 0f
                val rawLongG = parts[IDX_RAW_LONG_G].toFloatOrNull() ?: 0f
                val latG = parts[IDX_LAT_G].toFloatOrNull() ?: 0f
                val longG = parts[IDX_LONG_G].toFloatOrNull() ?: 0f
                val gSum = parts[IDX_GSUM].toFloatOrNull() ?: 0f

                val speedMps = parts[IDX_SPEED].toDoubleOrNull()

                val cornerIdx = parts[IDX_CORNER_INDEX].toIntOrNull() ?: 0
                val cornerName = parts[IDX_CORNER_NAME]
                val visitNum = parts[IDX_VISIT_NUMBER].toIntOrNull() ?: 0

                val apexToken = parts[IDX_APEX].trim()
                val isApexSample =
                    apexToken.equals("true", ignoreCase = true) ||
                            apexToken == "1" ||
                            apexToken.equals("yes", ignoreCase = true) ||
                            apexToken.equals("y", ignoreCase = true)

                result.add(
                    EventSample(
                        eventId = 0L, // arbitrary when loading loose CSV
                        cornerIndex = cornerIdx,
                        visitNumber = visitNum,
                        cornerName = cornerName,
                        intervalMs = intervalMs,
                        utcMs = utcMs,
                        longG = longG,
                        latG = latG,
                        zG = 0f, // not stored in CSV
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
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadSamplesFromCsv: error reading ${file.name}", e)
            return emptyList()
        }

        return result
    }

    // ---------------------------------------------------------------------------------------------
    // Share/export (creates derived CSV in cache, never overwrites event file)
    // ---------------------------------------------------------------------------------------------

    /**
     * Create a shareable CSV derived from an existing event CSV:
     *  1) Load samples
     *  2) Interpolate speed in-memory (anchor-based)
     *  3) Smooth G values per SmoothingLevel
     *  4) Interpolate GPS lat/lon in-memory (anchor-based) for a smoother replay path (desktop use)
     *  5) Stream-write CSV to cache/event_share/
     *
     * IMPORTANT: This function MUST NOT modify the original event CSV.
     */
    fun createSmoothedCsvForSharing(
        context: Context,
        file: File,
        smoothingLevel: SmoothingLevel
    ): File? {

        // 1) Load original samples
        val samples = loadSamplesFromCsv(file)
        if (samples.isEmpty()) return null

        // 2) Interpolate speeds before smoothing Gs
        val withInterpolatedSpeeds = interpolateSpeedsInSamples(samples)

        // 3) Apply desired smoothing
        val smoothedSamples = applySmoothingForExport(withInterpolatedSpeeds, smoothingLevel)
        if (smoothedSamples.isEmpty()) return null

        // 4) Interpolate GPS lat/lon between GPS fixes (export-only)
        val gpsInterpolatedSamples =
            if (GPS_INTERP_MODE == "CatmullRom") interpolateGpsCatmullRom(smoothedSamples)
            else interpolateGpsInSamples(smoothedSamples) // existing linear version


        // 5) Output directory (cache)
        val shareDir = File(context.cacheDir, "event_share")
        if (!shareDir.exists()) shareDir.mkdirs()

        val baseName = file.nameWithoutExtension
        val suffix = when (smoothingLevel) {
            SmoothingLevel.Off -> "raw"
            SmoothingLevel.Low -> "low"
            SmoothingLevel.Medium -> "medium"
            SmoothingLevel.Heavy -> "heavy"
        }

        val outFile = File(shareDir, "${baseName}_$suffix.csv")
        if (outFile.exists()) outFile.delete()

        // 6) Stream-write (avoid huge StringBuilder allocations)
        val localTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).apply {
            timeZone = TimeZone.getDefault()
        }

        try {
            outFile.bufferedWriter().use { writer ->
                writer.write(CSV_HEADER)

                for (sample in gpsInterpolatedSamples) {
                    val localTime = localTimeFormat.format(Date(sample.utcMs))
                    val speedStr = sample.speedMps?.toString() ?: ""
                    val apexStr = if (sample.isApexSample) "True" else ""

                    // Note: We overwrite gpsLat/gpsLon in the exported copy only.
                    // Note: We overwrite gpsLat/gpsLon in the exported copy only.
                    writer.append(sample.utcMs.toString()).append(',')
                    writer.append(sample.intervalMs.toString()).append(',')
                    writer.append(localTime).append(',')
                    writer.append(sample.trackName).append(',')
                    writer.append(sample.eventName).append(',')
                    writer.append(sample.gpsLat.toString()).append(',')
                    writer.append(sample.gpsLon.toString()).append(',')
                    writer.append(sample.closestCornerIndex.toString()).append(',')
                    writer.append(sample.distanceToClosestCornerM.toString()).append(',')
                    writer.append(sample.rawLatG.toString()).append(',')
                    writer.append(sample.rawLongG.toString()).append(',')
                    writer.append(sample.latG.toString()).append(',')
                    writer.append(sample.longG.toString()).append(',')
                    writer.append(sample.gSum.toString()).append(',')
                    writer.append(speedStr).append(',')
                    writer.append(sample.cornerIndex.toString()).append(',')
                    writer.append(sample.cornerName).append(',')
                    writer.append(sample.visitNumber.toString()).append(',')
                    writer.append(apexStr)
                    writer.newLine()

                }
            }

            debugLogToFile(
                context,
                "EXPORT share CSV created (rows=${gpsInterpolatedSamples.size}, level=$smoothingLevel, gpsInterp=true)"
            )
            return outFile
        } catch (e: Exception) {
            Log.e(TAG, "createSmoothedCsvForSharing: failed writing ${outFile.name}", e)
            return null
        }
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

        context.startActivity(Intent.createChooser(intent, "Share CSV"))
    }

    fun shareMultipleEventCsv(context: Context, files: List<File>) {
        if (files.isEmpty()) return

        val uris = ArrayList<android.net.Uri>(files.size)
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

        context.startActivity(Intent.createChooser(intent, "Share CSV files"))
    }

    // ---------------------------------------------------------------------------------------------
    // Smoothing + interpolation helpers (used only for share/export right now)
    // ---------------------------------------------------------------------------------------------

    /**
     * Apply smoothing to G values for export/display.
     * - Off: outputs "raw" G if present; otherwise uses stored smoothed G
     * - Low/Medium/Heavy: uses GForceSmoother (EMA + moving average)
     */
    private fun applySmoothingForExport(
        samples: List<EventSample>,
        level: SmoothingLevel
    ): List<EventSample> {
        if (samples.isEmpty()) return samples

        fun pickRaw(sample: EventSample): Pair<Float, Float> {
            val hasRaw = (sample.rawLatG != 0f || sample.rawLongG != 0f)
            return if (hasRaw) sample.rawLatG to sample.rawLongG else sample.latG to sample.longG
        }

        if (level == SmoothingLevel.Off) {
            return samples.map { s ->
                val (rawLat, rawLong) = pickRaw(s)
                val gSum = sqrt(rawLat * rawLat + rawLong * rawLong)
                s.copy(latG = rawLat, longG = rawLong, gSum = gSum)
            }
        }

        val tauMsOrNull: Float? = level.tauMs.coerceAtLeast(1).toFloat()

        val smoother = GForceSmoother(
            tauMs = tauMsOrNull,
            maWindowSize = level.windowSize
        ).also { it.reset() }

        return samples.map { s ->
            val (rawLat, rawLong) = pickRaw(s)
            val smoothed = smoother.addSample(
                rawLatG = rawLat,
                rawLongG = rawLong,
                sampleTimeMs = s.utcMs
            )
            val gSum = sqrt(smoothed.latG * smoothed.latG + smoothed.longG * smoothed.longG)
            s.copy(latG = smoothed.latG, longG = smoothed.longG, gSum = gSum)
        }
    }

    /**
     * Linear speed interpolation between "anchor" speed changes.
     * This does NOT write back into the event CSV; it only creates derived samples for export.
     */
    private fun interpolateSpeedsInSamples(samples: List<EventSample>): List<EventSample> {
        if (samples.isEmpty()) return samples

        val timestamps = samples.map { it.utcMs }
        val speeds = samples.map { it.speedMps }

        fun approxEqual(a: Double?, b: Double?, eps: Double = 1e-9): Boolean {
            if (a == null && b == null) return true
            if (a == null || b == null) return false
            return kotlin.math.abs(a - b) <= eps
        }

        // Anchor indices = points where speed changes (or first valid speed)
        val anchorIndices = mutableListOf<Int>()
        var lastAnchorSpeed: Double? = null
        var lastAnchorIndex: Int? = null

        for (i in samples.indices) {
            val s = speeds[i] ?: continue
            if (lastAnchorIndex == null) {
                lastAnchorIndex = i
                lastAnchorSpeed = s
                anchorIndices.add(i)
            } else if (!approxEqual(s, lastAnchorSpeed)) {
                lastAnchorIndex = i
                lastAnchorSpeed = s
                anchorIndices.add(i)
            }
        }

        // Ensure last row with speed is included as anchor
        val lastIndexWithSpeed = (samples.indices).lastOrNull { speeds[it] != null }
        if (lastIndexWithSpeed != null && !anchorIndices.contains(lastIndexWithSpeed)) {
            anchorIndices.add(lastIndexWithSpeed)
        }

        val newSpeeds = speeds.toMutableList()

        for (a in 0 until anchorIndices.size - 1) {
            val i0 = anchorIndices[a]
            val i1 = anchorIndices[a + 1]

            val v0 = speeds[i0]
            val v1 = speeds[i1]
            val t0 = timestamps[i0].toDouble()
            val t1 = timestamps[i1].toDouble()

            if (v0 == null || v1 == null) continue
            if (t1 <= t0) continue

            val denom = t1 - t0
            for (i in i0..i1) {
                val ti = timestamps[i].toDouble()
                val u = ((ti - t0) / denom).coerceIn(0.0, 1.0)
                newSpeeds[i] = v0 + (v1 - v0) * u
            }
        }

        return samples.mapIndexed { idx, sample ->
            val s = newSpeeds[idx]
            if (s != null) sample.copy(speedMps = s) else sample
        }
    }

    // GPS interpolation mode used ONLY for export.
// Keep Linear as the safe default for phone GPS (1 Hz).
    private enum class GpsInterpMode { Linear, CatmullRom }

    // Flip this one line to try CatmullRom.
    private const val GPS_INTERP_MODE = "CatmullRom"



    // ---------------------------------------------------------------------------------------------
    // GPS interpolation (export-only)
    // ---------------------------------------------------------------------------------------------

    // Local "flat earth" conversion constants (good for race-track-sized regions)
    private const val METERS_PER_DEG_LAT = 111_320.0

    private data class ENMeters(val eastM: Double, val northM: Double)

    /**
     * Convert lat/lon degrees into local East/North meters relative to an origin (lat0, lon0).
     */
    private fun latLonToENMeters(lat: Double, lon: Double, lat0: Double, lon0: Double): ENMeters {
        val lat0Rad = Math.toRadians(lat0)
        val metersPerDegLon = METERS_PER_DEG_LAT * kotlin.math.cos(lat0Rad)

        val east = (lon - lon0) * metersPerDegLon
        val north = (lat - lat0) * METERS_PER_DEG_LAT
        return ENMeters(eastM = east, northM = north)
    }

    /**
     * Convert local East/North meters back into lat/lon degrees using the same origin.
     */
    private fun enMetersToLatLon(
        eastM: Double,
        northM: Double,
        lat0: Double,
        lon0: Double
    ): Pair<Double, Double> {
        val lat0Rad = Math.toRadians(lat0)
        val metersPerDegLon = METERS_PER_DEG_LAT * kotlin.math.cos(lat0Rad)

        val lat = lat0 + (northM / METERS_PER_DEG_LAT)
        val lon = lon0 + (eastM / metersPerDegLon)
        return lat to lon
    }



    /**
     * Interpolate gpsLat/gpsLon between anchor GPS fixes (export-only).
     *
     * Anchor definition: a row where (gpsLat,gpsLon) changes relative to the previous row.
     * This matches your current CSV behavior where many accel samples reuse the last GPS fix.
     */
    private fun interpolateGpsInSamples(samples: List<EventSample>): List<EventSample> {
        if (samples.isEmpty()) return samples
        if (samples.size < 3) return samples

        // We’ll build an output list of copies to avoid mutating the input list.
        val out = samples.toMutableList()

        var anchorStart = 0
        var lastLat = samples[0].gpsLat
        var lastLon = samples[0].gpsLon

        // Walk forward looking for anchor changes
        for (i in 1 until samples.size) {
            val lat = samples[i].gpsLat
            val lon = samples[i].gpsLon

            val isAnchorChange = (lat != lastLat) || (lon != lastLon)
            if (!isAnchorChange) continue

            // We have an anchor pair: anchorStart .. i
            val start = samples[anchorStart]
            val end = samples[i]

            val t0 = start.utcMs.toDouble()
            val t1 = end.utcMs.toDouble()
            if (t1 > t0) {
                val originLat = start.gpsLat
                val originLon = start.gpsLon
                val startEN = latLonToENMeters(start.gpsLat, start.gpsLon, originLat, originLon)
                val endEN = latLonToENMeters(end.gpsLat, end.gpsLon, originLat, originLon)

                // Fill all rows in the segment (including endpoints)
                for (k in anchorStart..i) {
                    val tk = samples[k].utcMs.toDouble()
                    val u = ((tk - t0) / (t1 - t0)).coerceIn(0.0, 1.0)

                    val east = startEN.eastM + u * (endEN.eastM - startEN.eastM)
                    val north = startEN.northM + u * (endEN.northM - startEN.northM)

                    val (interpLat, interpLon) = enMetersToLatLon(east, north, originLat, originLon)
                    out[k] = out[k].copy(gpsLat = interpLat, gpsLon = interpLon)
                }
            }

            // Move to next segment
            anchorStart = i
            lastLat = lat
            lastLon = lon
        }

        // Trailing rows after last anchor: keep last known GPS position (no look-ahead)
        // (They are already set to that value in the recorded data.)
        return out
    }

    /**
     * Export-only GPS smoothing: centripetal Catmull–Rom spline through GPS anchor points.
     *
     * Safeguards for phone GPS (≈1 Hz):
     *  - Only applies when we have 4 anchors (P0,P1,P2,P3).
     *  - Falls back to linear when spacing is too large (avoids inventing huge arcs).
     *
     * Returns a list where gpsLat/gpsLon are overwritten in the export copy only.
     */
    private fun interpolateGpsCatmullRom(samples: List<EventSample>): List<EventSample> {
        if (samples.size < 5) return samples

        // 1) Extract GPS anchors: indices where GPS changes
        val anchorIdx = mutableListOf<Int>()
        anchorIdx.add(0)
        var lastLat = samples[0].gpsLat
        var lastLon = samples[0].gpsLon
        for (i in 1 until samples.size) {
            val lat = samples[i].gpsLat
            val lon = samples[i].gpsLon
            if (lat != lastLat || lon != lastLon) {
                anchorIdx.add(i)
                lastLat = lat
                lastLon = lon
            }
        }
        if (anchorIdx.size < 4) {
            // Not enough anchors for spline → linear
            return interpolateGpsInSamples(samples)
        }

        // 2) Convert anchors into local meters (east/north) relative to first anchor
        val originLat = samples[anchorIdx[0]].gpsLat
        val originLon = samples[anchorIdx[0]].gpsLon

        data class Anchor(val idx: Int, val e: Double, val n: Double)
        val anchors = anchorIdx.map { idx ->
            val s = samples[idx]
            val en = latLonToENMeters(s.gpsLat, s.gpsLon, originLat, originLon)
            Anchor(idx = idx, e = en.eastM, n = en.northM)
        }

        // Output list (copies) so we don't mutate input
        val out = samples.toMutableList()

        // Rule of thumb: if anchors are far apart, spline can invent too much curvature at 1 Hz.
        val MAX_SEGMENT_METERS = 40.0

        // 3) For each segment between P1->P2, fill samples between anchor indices
        // using P0,P1,P2,P3 for centripetal Catmull–Rom.
        for (a in 1 until anchors.size - 2) {
            val p0 = anchors[a - 1]
            val p1 = anchors[a]
            val p2 = anchors[a + 1]
            val p3 = anchors[a + 2]

            val startIdx = p1.idx
            val endIdx = p2.idx
            if (endIdx <= startIdx) continue

            val segDist = hypot(p2.e - p1.e, p2.n - p1.n)
            if (segDist > MAX_SEGMENT_METERS) {
                // Too sparse → linear fill for this segment
                fillLinearSegmentMeters(out, samples, startIdx, endIdx, originLat, originLon)
                continue
            }

            val tStart = samples[startIdx].utcMs.toDouble()
            val tEnd = samples[endIdx].utcMs.toDouble()
            val denom = (tEnd - tStart)
            if (denom <= 0.0) continue

            for (k in startIdx..endIdx) {
                val tk = samples[k].utcMs.toDouble()
                val u = ((tk - tStart) / denom).coerceIn(0.0, 1.0)

                val (e, n) = catmullRomCentripetal2D(
                    u,
                    p0.e, p0.n,
                    p1.e, p1.n,
                    p2.e, p2.n,
                    p3.e, p3.n
                )

                val (lat, lon) = enMetersToLatLon(e, n, originLat, originLon)
                out[k] = out[k].copy(gpsLat = lat, gpsLon = lon)
            }
        }

        // 4) End regions (before first spline segment & after last) still linear
        // Fill from first anchor to second anchor
        fillLinearSegmentMeters(out, samples, anchors[0].idx, anchors[1].idx, originLat, originLon)
        // Fill from last-1 anchor to last anchor
        fillLinearSegmentMeters(out, samples, anchors[anchors.size - 2].idx, anchors.last().idx, originLat, originLon)

        return out
    }

    private fun hypot(x: Double, y: Double): Double = kotlin.math.sqrt(x * x + y * y)

    /**
     * Linear fill in local meters between two anchor indices.
     * Used as fallback when spline would be unsafe.
     */
    private fun fillLinearSegmentMeters(
        out: MutableList<EventSample>,
        samples: List<EventSample>,
        startIdx: Int,
        endIdx: Int,
        originLat: Double,
        originLon: Double
    ) {
        if (endIdx <= startIdx) return
        val s0 = samples[startIdx]
        val s1 = samples[endIdx]
        val t0 = s0.utcMs.toDouble()
        val t1 = s1.utcMs.toDouble()
        if (t1 <= t0) return

        val p0 = latLonToENMeters(s0.gpsLat, s0.gpsLon, originLat, originLon)
        val p1 = latLonToENMeters(s1.gpsLat, s1.gpsLon, originLat, originLon)

        for (k in startIdx..endIdx) {
            val u = ((samples[k].utcMs - t0) / (t1 - t0)).coerceIn(0.0, 1.0)
            val e = p0.eastM + u * (p1.eastM - p0.eastM)
            val n = p0.northM + u * (p1.northM - p0.northM)
            val (lat, lon) = enMetersToLatLon(e, n, originLat, originLon)
            out[k] = out[k].copy(gpsLat = lat, gpsLon = lon)
        }
    }

    /**
     * Centripetal Catmull–Rom in 2D.
     * This avoids many overshoot problems compared to uniform Catmull–Rom.
     */
    private fun catmullRomCentripetal2D(
        u: Double,
        x0: Double, y0: Double,
        x1: Double, y1: Double,
        x2: Double, y2: Double,
        x3: Double, y3: Double
    ): Pair<Double, Double> {
        // Parameterization (alpha = 0.5 for centripetal)
        fun tj(ti: Double, xa: Double, ya: Double, xb: Double, yb: Double): Double {
            val dx = xb - xa
            val dy = yb - ya
            val dist = kotlin.math.sqrt(dx * dx + dy * dy)
            return ti + kotlin.math.sqrt(dist) // dist^(alpha) where alpha=0.5
        }

        val t0 = 0.0
        val t1 = tj(t0, x0, y0, x1, y1)
        val t2 = tj(t1, x1, y1, x2, y2)
        val t3 = tj(t2, x2, y2, x3, y3)

        // Map u in [0,1] to t in [t1,t2]
        val t = t1 + u * (t2 - t1)

        fun lerp(ax: Double, ay: Double, bx: Double, by: Double, ta: Double, tb: Double, t: Double): Pair<Double, Double> {
            if (tb - ta == 0.0) return ax to ay
            val s = (t - ta) / (tb - ta)
            return (ax + s * (bx - ax)) to (ay + s * (by - ay))
        }

        val a1 = lerp(x0, y0, x1, y1, t0, t1, t)
        val a2 = lerp(x1, y1, x2, y2, t1, t2, t)
        val a3 = lerp(x2, y2, x3, y3, t2, t3, t)

        val b1 = lerp(a1.first, a1.second, a2.first, a2.second, t0, t2, t)
        val b2 = lerp(a2.first, a2.second, a3.first, a3.second, t1, t3, t)

        val c = lerp(b1.first, b1.second, b2.first, b2.second, t1, t2, t)
        return c
    }



    // ---------------------------------------------------------------------------------------------
    // Offline speed rewrite (mutates the event CSV by design)
    // ---------------------------------------------------------------------------------------------

    /**
     * Offline pass: rewrite the SPEED column (index 14) in the event CSV by linearly
     * interpolating between speed-change anchors. This function DOES modify the event file.
     *
     * NOTE: This is separate from Share/export. Share/export should not call this.
     */
    fun recomputeInterpolatedSpeedForEvent(context: Context, eventId: Long): Boolean {
        // Flush any buffered samples before reading the full file
        flushBuffer(context, eventId)

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

            val rows = mutableListOf<MutableList<String>>()
            val timestamps = mutableListOf<Long>()
            val speeds = mutableListOf<Double?>()

            for (line in dataLines) {
                if (line.isBlank()) continue

                val parts = line.split(',').toMutableList()
                if (parts.size <= IDX_SPEED) {
                    rows.add(parts)
                    timestamps.add(0L)
                    speeds.add(null)
                    continue
                }

                val utcMs = parts[IDX_TIMESTAMP_MS].toLongOrNull()
                val speed = parts[IDX_SPEED].toDoubleOrNull()

                rows.add(parts)
                timestamps.add(utcMs ?: 0L)
                speeds.add(speed)
            }

            if (rows.isEmpty()) {
                Log.w(TAG, "recomputeInterpolatedSpeedForEvent: no parsable rows")
                return false
            }

            fun approxEqual(a: Double?, b: Double?, eps: Double = 1e-9): Boolean {
                if (a == null && b == null) return true
                if (a == null || b == null) return false
                return kotlin.math.abs(a - b) <= eps
            }

            val anchorIndices = mutableListOf<Int>()
            var lastAnchorSpeed: Double? = null
            var lastAnchorIndex: Int? = null

            for (i in rows.indices) {
                val s = speeds[i] ?: continue
                if (lastAnchorIndex == null) {
                    lastAnchorIndex = i
                    lastAnchorSpeed = s
                    anchorIndices.add(i)
                } else if (!approxEqual(s, lastAnchorSpeed)) {
                    lastAnchorIndex = i
                    lastAnchorSpeed = s
                    anchorIndices.add(i)
                }
            }

            if (anchorIndices.size < 2) {
                Log.w(TAG, "recomputeInterpolatedSpeedForEvent: only ${anchorIndices.size} speed anchor(s); skipping")
                return false
            }

            val lastIndexWithSpeed = (rows.indices).lastOrNull { speeds[it] != null }
            if (lastIndexWithSpeed != null && !anchorIndices.contains(lastIndexWithSpeed)) {
                anchorIndices.add(lastIndexWithSpeed)
            }

            val newSpeeds = speeds.toMutableList()

            for (a in 0 until anchorIndices.size - 1) {
                val i0 = anchorIndices[a]
                val i1 = anchorIndices[a + 1]
                if (i0 < 0 || i1 <= i0 || i1 >= rows.size) continue

                val v0 = speeds[i0]
                val v1 = speeds[i1]
                val t0 = timestamps[i0].toDouble()
                val t1 = timestamps[i1].toDouble()

                if (v0 == null || v1 == null) continue
                if (t1 <= t0) continue

                val denom = t1 - t0
                for (i in i0..i1) {
                    val ti = timestamps[i].toDouble()
                    val u = ((ti - t0) / denom).coerceIn(0.0, 1.0)
                    newSpeeds[i] = v0 + (v1 - v0) * u
                }
            }

            for (i in rows.indices) {
                rows[i][IDX_SPEED] = newSpeeds[i]?.toString() ?: ""
            }

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

    // ---------------------------------------------------------------------------------------------
    // Misc / placeholders
    // ---------------------------------------------------------------------------------------------

    /**
     * Placeholder for a future convenience loader by eventId.
     * Currently unused; returns empty.
     */
    fun loadEvent(context: Context, eventId: Long): List<EventSample> = emptyList()

    /**
     * Debug log helper (best-effort; never crashes).
     */
    private fun debugLogToFile(context: Context, message: String) {
        try {
            val dir = context.getExternalFilesDir("debug_logs")
            if (dir != null && (dir.exists() || dir.mkdirs())) {
                val file = File(dir, "export_debug.log")
                file.appendText("${System.currentTimeMillis()}, $message\n")
            }
        } catch (_: Exception) {
            // swallow
        }
    }
}
