package com.hotlaps.dynamic.data

import java.io.File

/**
 * One-time finishing pass over an event CSV after recording stops:
 *  - linear speed interpolation between speed-change anchors (same algorithm as before)
 *  - optional event name for every row
 *  - apex tags from the writer's sidecar (apexes that missed the in-memory hold)
 *
 * Streams the file twice and holds only one long + one double per row (~8 MB for 500k rows),
 * where the old readLines()/joinToString approach needed several copies of the whole file.
 * The result is written to a temp file and swapped in, so a failure leaves the original intact.
 */
object EventPostProcessor {

    data class Result(
        val rows: Int,
        val speedAnchors: Int,
        val apexTagsApplied: Int
    )

    private data class ApexTag(
        val utcMs: Long,
        val cornerIndex: Int,
        val visitNumber: Int,
        val cornerName: String
    )

    fun finish(file: File, eventName: String?, apexSidecar: File?): Result {
        require(file.exists()) { "Event CSV not found: ${file.absolutePath}" }

        // ---- Pass 1: timestamps + speeds -------------------------------------------------
        var timestamps = LongArray(4096)
        var speeds = DoubleArray(4096)   // NaN = no speed
        var n = 0

        file.bufferedReader().useLines { lines ->
            lines.forEachIndexed { lineNo, line ->
                if (lineNo == 0 && line.startsWith("timestampMs")) return@forEachIndexed
                if (line.isBlank()) return@forEachIndexed
                if (n == timestamps.size) {
                    timestamps = timestamps.copyOf(n * 2)
                    speeds = speeds.copyOf(n * 2)
                }
                val parts = line.split(',')
                timestamps[n] = parts.getOrNull(EventCsvFormat.IDX_TIMESTAMP_MS)?.toLongOrNull() ?: 0L
                speeds[n] = if (parts.size > EventCsvFormat.IDX_SPEED)
                    parts[EventCsvFormat.IDX_SPEED].toDoubleOrNull() ?: Double.NaN
                else Double.NaN
                n++
            }
        }

        val speedAnchors = interpolateSpeeds(timestamps, speeds, n)

        // ---- Apex sidecar -> row index ---------------------------------------------------
        val apexByRow = HashMap<Int, ApexTag>()
        readSidecar(apexSidecar).forEach { tag ->
            nearestIndex(timestamps, n, tag.utcMs)?.let { apexByRow[it] = tag }
        }

        // ---- Pass 2: rewrite to temp, then swap ------------------------------------------
        val tmp = File(file.parentFile, file.name + ".tmp")
        val cleanName = eventName?.let { EventCsvFormat.sanitizeField(it) }
        var row = 0

        tmp.bufferedWriter().use { out ->
            file.bufferedReader().useLines { lines ->
                lines.forEachIndexed { lineNo, line ->
                    if (lineNo == 0 && line.startsWith("timestampMs")) {
                        out.write(line); out.write("\n"); return@forEachIndexed
                    }
                    if (line.isBlank()) return@forEachIndexed

                    val parts = line.split(',').toMutableList()
                    if (parts.size >= EventCsvFormat.EXPECTED_COLS) {
                        val s = speeds[row]
                        parts[EventCsvFormat.IDX_SPEED] = if (s.isNaN()) "" else s.toString()
                        if (cleanName != null) parts[EventCsvFormat.IDX_EVENT_NAME] = cleanName
                        apexByRow[row]?.let { tag ->
                            parts[EventCsvFormat.IDX_CORNER_INDEX] = tag.cornerIndex.toString()
                            parts[EventCsvFormat.IDX_CORNER_NAME] = tag.cornerName
                            parts[EventCsvFormat.IDX_VISIT_NUMBER] = tag.visitNumber.toString()
                            parts[EventCsvFormat.IDX_APEX] = "1"
                        }
                        out.write(parts.joinToString(","))
                    } else {
                        out.write(line) // keep malformed rows untouched
                    }
                    out.write("\n")
                    row++
                }
            }
        }

        // File.renameTo won't replace an existing file on Android, so swap via a .bak
        val bak = File(file.parentFile, file.name + ".bak")
        bak.delete()
        check(file.renameTo(bak)) { "Could not move ${file.name} aside" }
        if (!tmp.renameTo(file)) {
            bak.renameTo(file)
            error("Could not replace ${file.name}; original restored")
        }
        bak.delete()
        apexSidecar?.delete()

        return Result(rows = n, speedAnchors = speedAnchors, apexTagsApplied = apexByRow.size)
    }

    /**
     * Linear speed interpolation between "anchor" rows where the GPS speed changed.
     * Speeds are held constant between GPS fixes when recorded, so this smooths the steps.
     * Returns the number of anchors (0 or 1 = nothing interpolated).
     */
    internal fun interpolateSpeeds(timestamps: LongArray, speeds: DoubleArray, n: Int): Int {
        val anchors = ArrayList<Int>()
        var lastSpeed = Double.NaN
        for (i in 0 until n) {
            val s = speeds[i]
            if (s.isNaN()) continue
            if (anchors.isEmpty() || kotlin.math.abs(s - lastSpeed) > 1e-9) {
                anchors.add(i)
                lastSpeed = s
            }
        }
        if (anchors.size < 2) return anchors.size

        val lastWithSpeed = (n - 1 downTo 0).firstOrNull { !speeds[it].isNaN() }
        if (lastWithSpeed != null && anchors.last() != lastWithSpeed) anchors.add(lastWithSpeed)

        val original = speeds.copyOf(n)
        for (a in 0 until anchors.size - 1) {
            val i0 = anchors[a]
            val i1 = anchors[a + 1]
            val t0 = timestamps[i0].toDouble()
            val t1 = timestamps[i1].toDouble()
            if (t1 <= t0) continue
            val v0 = original[i0]
            val v1 = original[i1]
            for (i in i0..i1) {
                val u = ((timestamps[i] - t0) / (t1 - t0)).coerceIn(0.0, 1.0)
                speeds[i] = v0 + (v1 - v0) * u
            }
        }
        return anchors.size
    }

    private fun readSidecar(sidecar: File?): List<ApexTag> {
        if (sidecar == null || !sidecar.exists()) return emptyList()
        return sidecar.readLines().mapNotNull { line ->
            val p = line.split(',')
            if (p.size < 4) return@mapNotNull null
            ApexTag(
                utcMs = p[0].toLongOrNull() ?: return@mapNotNull null,
                cornerIndex = p[1].toIntOrNull() ?: return@mapNotNull null,
                visitNumber = p[2].toIntOrNull() ?: return@mapNotNull null,
                cornerName = p[3]
            )
        }
    }

    /** Timestamps are recorded in time order, so binary search. */
    private fun nearestIndex(timestamps: LongArray, n: Int, target: Long): Int? {
        if (n == 0) return null
        var lo = 0
        var hi = n - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (timestamps[mid] < target) lo = mid + 1 else hi = mid
        }
        return if (lo > 0 && target - timestamps[lo - 1] <= timestamps[lo] - target) lo - 1 else lo
    }
}
