package com.hotlaps.dynamic.data

import com.hotlaps.dynamic.model.EventSample
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Append-only writer for one event CSV.
 *
 * The event file is NEVER rewritten while recording. Rewriting it to tag each apex froze
 * sampling for up to ~9 s per corner on long sessions (Gingerman, Apr 2026) and ended in an
 * ANR and a crash.
 *
 * Instead, the most recent rows are held in memory while a corner visit is open
 * ([append]'s holdFromUtcMs), so the apex row can be tagged ([tagApex]) before it is written.
 * If an apex falls outside the held rows, it is recorded in [apexSidecar] and applied by
 * [EventPostProcessor] when the event is finished.
 *
 * Not thread-safe: use from a single thread.
 */
class EventCsvWriter(
    val file: File,
    val apexSidecar: File,
    private val maxHoldMs: Long = DEFAULT_MAX_HOLD_MS,
    private val flushEveryRows: Int = DEFAULT_FLUSH_EVERY_ROWS,
    timeZone: TimeZone = TimeZone.getDefault()
) {
    companion object {
        /** Corner visits are ~3 s (max seen 142 s with the car stopped in the radius). */
        const val DEFAULT_MAX_HOLD_MS = 120_000L

        /** ~5 s at 10 Hz. */
        const val DEFAULT_FLUSH_EVERY_ROWS = 50
    }

    private val localTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        .apply { this.timeZone = timeZone }

    /** Rows that may still be tagged as an apex. Time-ordered. */
    private val held = ArrayDeque<EventSample>()

    /** Formatted rows released from [held], waiting for the next batched append. */
    private val ready = StringBuilder()
    private var readyRows = 0

    /** Rows written to disk so far (for diagnostics/tests). */
    var rowsWritten: Long = 0
        private set

    /** Apex tags that missed the held rows and went to the sidecar. */
    var sidecarTags: Int = 0
        private set

    /**
     * Adds one sample.
     *
     * @param holdFromUtcMs start time of the earliest open corner visit, or null when no
     *        visit is open. Rows at or after this time stay taggable (up to [maxHoldMs]).
     *        Passing null releases the held rows, so when a visit closes, call [tagApex]
     *        before appending the sample that closed it.
     */
    fun append(sample: EventSample, holdFromUtcMs: Long?) {
        held.addLast(sample)
        release(holdFromUtcMs, releaseAll = false)
        if (readyRows >= flushEveryRows) writeReady()
    }

    /**
     * Marks the held row closest to [apexUtcMs] as the apex for this corner visit.
     * Returns false if the apex was written to the sidecar instead.
     */
    fun tagApex(apexUtcMs: Long, cornerIndex: Int, visitNumber: Int, cornerName: String): Boolean {
        val first = held.firstOrNull()
        if (first == null || apexUtcMs < first.utcMs) {
            apexSidecar.appendText(
                "$apexUtcMs,$cornerIndex,$visitNumber,${EventCsvFormat.sanitizeField(cornerName)}\n"
            )
            sidecarTags++
            return false
        }

        var bestIndex = 0
        var bestError = Long.MAX_VALUE
        for (i in held.indices) {
            val err = kotlin.math.abs(held[i].utcMs - apexUtcMs)
            if (err < bestError) {
                bestError = err
                bestIndex = i
            }
        }
        held[bestIndex] = held[bestIndex].copy(
            cornerIndex = cornerIndex,
            visitNumber = visitNumber,
            cornerName = cornerName,
            isApexSample = true
        )
        return true
    }

    /**
     * Writes rows that can no longer be tagged. Rows inside an open corner visit stay held,
     * so a mid-visit backup doesn't cost that visit its apex.
     */
    fun flushReady() = writeReady()

    /** Writes everything, including held rows. Call once when recording ends. */
    fun close() {
        release(holdFromUtcMs = null, releaseAll = true)
        writeReady()
    }

    private fun release(holdFromUtcMs: Long?, releaseAll: Boolean) {
        val newest = held.lastOrNull()?.utcMs ?: return
        while (held.isNotEmpty()) {
            val t = held.first().utcMs
            val releasable = releaseAll ||
                    holdFromUtcMs == null ||
                    t < holdFromUtcMs ||
                    newest - t > maxHoldMs
            if (!releasable) break
            val s = held.removeFirst()
            ready.append(EventCsvFormat.formatRow(s, localTimeFormat.format(Date(s.utcMs))))
            readyRows++
        }
    }

    private fun writeReady() {
        if (readyRows == 0) return
        if (!file.exists() || file.length() == 0L) {
            file.appendText(EventCsvFormat.HEADER)
        }
        file.appendText(ready.toString())
        rowsWritten += readyRows
        ready.setLength(0)
        readyRows = 0
    }
}
