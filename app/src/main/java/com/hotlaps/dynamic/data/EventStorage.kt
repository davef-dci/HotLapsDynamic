package com.hotlaps.dynamic.data

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import com.hotlaps.dynamic.model.Event
import com.hotlaps.dynamic.model.EventSample
import com.hotlaps.dynamic.util.GForceSmoother
import com.hotlaps.dynamic.util.RecordingHealth
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
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

    // ---------------------------------------------------------------------------------------------
    // Recording writer thread
    // ---------------------------------------------------------------------------------------------

    /**
     * All recording-time disk work (appends, apex tagging, backups, finishing) runs on this one
     * thread, in submission order. The sampling loop only enqueues, so it never waits on storage.
     */
    private val recordingExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "EventRecorder").apply { isDaemon = true }
    }

    /** Open writers keyed by eventId. Only touched on [recordingExecutor]. */
    private val writers = HashMap<Long, EventCsvWriter>()

    // ---------------------------------------------------------------------------------------------
    // CSV schema (see EventCsvFormat)
    // ---------------------------------------------------------------------------------------------

    private const val CSV_HEADER = EventCsvFormat.HEADER
    private const val IDX_TIMESTAMP_MS = EventCsvFormat.IDX_TIMESTAMP_MS
    private const val IDX_DELTA_MS = EventCsvFormat.IDX_DELTA_MS
    private const val IDX_TRACK_NAME = EventCsvFormat.IDX_TRACK_NAME
    private const val IDX_EVENT_NAME = EventCsvFormat.IDX_EVENT_NAME
    private const val IDX_GPS_LAT = EventCsvFormat.IDX_GPS_LAT
    private const val IDX_GPS_LON = EventCsvFormat.IDX_GPS_LON
    private const val IDX_CLOSEST_CORNER_INDEX = EventCsvFormat.IDX_CLOSEST_CORNER_INDEX
    private const val IDX_DIST_TO_CLOSEST_CORNER_M = EventCsvFormat.IDX_DIST_TO_CLOSEST_CORNER_M
    private const val IDX_RAW_LAT_G = EventCsvFormat.IDX_RAW_LAT_G
    private const val IDX_RAW_LONG_G = EventCsvFormat.IDX_RAW_LONG_G
    private const val IDX_LAT_G = EventCsvFormat.IDX_LAT_G
    private const val IDX_LONG_G = EventCsvFormat.IDX_LONG_G
    private const val IDX_GSUM = EventCsvFormat.IDX_GSUM
    private const val IDX_SPEED = EventCsvFormat.IDX_SPEED
    private const val IDX_CORNER_INDEX = EventCsvFormat.IDX_CORNER_INDEX
    private const val IDX_CORNER_NAME = EventCsvFormat.IDX_CORNER_NAME
    private const val IDX_VISIT_NUMBER = EventCsvFormat.IDX_VISIT_NUMBER
    private const val IDX_APEX = EventCsvFormat.IDX_APEX

    private const val EXPECTED_COLS = EventCsvFormat.EXPECTED_COLS

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
    // Recording path
    // ---------------------------------------------------------------------------------------------

    private fun apexSidecarFile(dir: File, eventId: Long) = File(dir, "event_${eventId}_apex.txt")

    private fun backupFile(dir: File, eventId: Long) = File(dir, "event_${eventId}_backup.csv")

    /** Call only on [recordingExecutor]. */
    private fun writerFor(context: Context, eventId: Long): EventCsvWriter? {
        writers[eventId]?.let { return it }
        val dir = eventsDir(context) ?: return null
        return EventCsvWriter(
            file = File(dir, "event_${eventId}.csv"),
            apexSidecar = apexSidecarFile(dir, eventId)
        ).also { writers[eventId] = it }
    }

    /**
     * Queue one telemetry sample for the event CSV. Returns immediately.
     *
     * @param holdFromUtcMs start of the earliest open corner visit (null if none), so the
     *        apex row is still in memory when [tagApex] runs. See [EventCsvWriter].
     */
    fun appendSample(context: Context, sample: EventSample, holdFromUtcMs: Long?) {
        val appContext = context.applicationContext
        recordingExecutor.execute {
            try {
                writerFor(appContext, sample.eventId)?.append(sample, holdFromUtcMs)
            } catch (e: Exception) {
                Log.e(TAG, "appendSample: error writing sample for event ${sample.eventId}", e)
            }
        }
    }

    /** Queue an apex tag for one corner visit. Returns immediately; never rewrites the file. */
    fun tagApex(
        context: Context,
        eventId: Long,
        apexUtcMs: Long,
        cornerIndex: Int,
        visitNumber: Int,
        cornerName: String
    ) {
        val appContext = context.applicationContext
        recordingExecutor.execute {
            try {
                val inMemory = writerFor(appContext, eventId)
                    ?.tagApex(apexUtcMs, cornerIndex, visitNumber, cornerName)
                if (inMemory == false) {
                    Log.w(TAG, "tagApex: corner=$cornerIndex visit=$visitNumber missed held rows; sidecar")
                }
            } catch (e: Exception) {
                Log.e(TAG, "tagApex: failed for event $eventId", e)
            }
        }
    }

    /**
     * Writes rows that are no longer taggable, then copies the event CSV to
     * event_<id>_backup.csv. Blocks until done, so call from a background thread.
     */
    fun flushAndBackup(context: Context, eventId: Long) {
        val appContext = context.applicationContext
        runOnRecorder {
            writers[eventId]?.flushReady()
            val dir = eventsDir(appContext) ?: return@runOnRecorder
            val src = File(dir, "event_${eventId}.csv")
            if (!src.exists()) return@runOnRecorder
            try {
                src.copyTo(backupFile(dir, eventId), overwrite = true)
                Log.d(TAG, "flushAndBackup: backup written for event $eventId (${src.length() / 1024} KB)")
            } catch (e: Exception) {
                Log.e(TAG, "flushAndBackup: failed for event $eventId", e)
            }
        }
    }

    /**
     * Ends recording for [eventId]: writes all remaining rows, runs the finishing pass
     * (speed interpolation, event name, corner apexes + laps from [corners]), renames the file to [finalName]
     * if given, and refreshes the backup copy. Blocks, so call from a background thread.
     *
     * Everything happens in this order on purpose: renaming before the last rows were written
     * used to split the tail of the session into a separate headerless file.
     *
     * @return the final event file, or null if nothing was recorded.
     */
    fun finishEvent(
        context: Context,
        eventId: Long,
        finalName: String?,
        corners: List<CornerLapDetector.CornerSpec>? = null,
        cornerRadiusM: Double = CornerLapDetector.DEFAULT_RADIUS_M
    ): File? {
        val appContext = context.applicationContext
        runOnRecorder { writers.remove(eventId)?.close() }

        val dir = eventsDir(appContext) ?: return null
        var file = File(dir, "event_${eventId}.csv")
        if (!file.exists()) return null

        try {
            val result = EventPostProcessor.finish(
                file, finalName, apexSidecarFile(dir, eventId), corners, cornerRadiusM
            )
            Log.d(TAG, "finishEvent: event $eventId finished: $result")
            RecordingHealth.log(
                "FINISH PASS event=$eventId rows=${result.rows} liveApexes=${result.liveApexRows} " +
                        "apexes=${result.apexTagsApplied} laps=${result.laps} redetected=${result.redetected}"
            )
        } catch (e: Exception) {
            Log.e(TAG, "finishEvent: finishing pass failed for event $eventId; raw file kept", e)
        }

        if (!finalName.isNullOrBlank()) {
            renameEventFile(file, finalName)?.let { file = it }
        }

        try {
            file.copyTo(backupFile(dir, eventId), overwrite = true)
        } catch (e: Exception) {
            Log.e(TAG, "finishEvent: backup copy failed for event $eventId", e)
        }
        return file
    }

    // ---------------------------------------------------------------------------------------------
    // Live pit-side upload (see LiveParts)
    // ---------------------------------------------------------------------------------------------

    /** Bytes of each event file already shipped as live parts, and parts made so far. Recorder thread only. */
    private val liveOffsets = HashMap<Long, Long>()
    private val livePartCounts = HashMap<Long, Int>()

    /** Local queue of live files waiting to upload, in order: cache/live_upload/<eventId>/ */
    fun liveQueueDir(context: Context, eventId: Long): File =
        File(context.cacheDir, "live_upload/$eventId").apply { mkdirs() }

    /** Queues the session's corner list for upload (once, at session start). */
    fun queueLiveCorners(
        context: Context,
        eventId: Long,
        corners: List<CornerLapDetector.CornerSpec>,
        radiusM: Double
    ) {
        if (corners.isEmpty()) return
        // "00000_" prefix sorts it ahead of every part in the upload queue
        val dest = File(liveQueueDir(context, eventId), "00000_" + LiveParts.cornersName(eventId))
        try {
            LiveParts.writeCorners(dest, corners, radiusM)
        } catch (e: Exception) {
            Log.e(TAG, "queueLiveCorners failed for event $eventId", e)
        }
    }

    /**
     * Queues the rows written since the previous call as the next live part. Runs on the recorder
     * thread (blocking the caller), so rows are never split and the writer can't race the copy.
     */
    fun snapshotLivePart(context: Context, eventId: Long) {
        val appContext = context.applicationContext
        runOnRecorder {
            writers[eventId]?.flushReady()
            val dir = eventsDir(appContext) ?: return@runOnRecorder
            val src = File(dir, "event_${eventId}.csv")
            val number = (livePartCounts[eventId] ?: 0) + 1
            val queue = liveQueueDir(appContext, eventId)
            val dest = File(queue, LiveParts.partName(eventId, number))
            val from = liveOffsets[eventId] ?: 0L
            val to = LiveParts.slice(src, from, dest)
            if (to > from) {
                liveOffsets[eventId] = to
                livePartCounts[eventId] = number
            }
        }
    }

    /** Forget live-upload state for a finished event (local queue is removed by the uploader). */
    fun clearLiveState(eventId: Long) {
        runOnRecorder {
            liveOffsets.remove(eventId)
            livePartCounts.remove(eventId)
        }
    }

    private fun runOnRecorder(block: () -> Unit) {
        try {
            recordingExecutor.submit(block).get()
        } catch (e: Exception) {
            Log.e(TAG, "runOnRecorder: task failed", e)
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

        // Events are already recorded into the public folder. Copying a file onto itself with
        // copyTo(overwrite = true) deletes the target first, i.e. deletes the event.
        if (srcDir.canonicalPath == dstDir.canonicalPath) return 0

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
    // Renaming
    // ---------------------------------------------------------------------------------------------

    /**
     * Renames [oldFile] to "<sanitized name>.csv" (adding " (2)", " (3)"... if taken).
     * Returns the new file, or null if the rename failed.
     */
    private fun renameEventFile(oldFile: File, newName: String): File? {
        val dir = oldFile.parentFile ?: return null

        // Sanitize the name for filesystem safety
        val safeBase = newName
            .replace("[^A-Za-z0-9 _-]".toRegex(), "_")
            .replace(" +".toRegex(), " ")
            .trim()
            .ifBlank { "Event" }

        var newFile = File(dir, "$safeBase.csv")
        var suffix = 2
        while (newFile.exists()) {
            newFile = File(dir, "$safeBase ($suffix).csv")
            suffix++
        }

        return if (oldFile.renameTo(newFile)) {
            Log.d(TAG, "renameEventFile: renamed to ${newFile.name}")
            newFile
        } else {
            Log.e(TAG, "renameEventFile: renameTo() failed for ${oldFile.absolutePath}")
            null
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
