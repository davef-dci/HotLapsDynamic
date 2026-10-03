package com.hotlaps.dynamic.data

import java.io.File
import java.io.RandomAccessFile

/**
 * Live pit-side upload: the event CSV is shipped as small numbered "part" files containing only
 * the rows added since the previous part. Concatenating the parts in order reproduces the event
 * file exactly (part 1 starts with the header). The ApexDynamicsAnalyzer's live mode joins them.
 *
 * Naming (shared with the Analyzer):
 *   event_<id>_part_00001.csv, event_<id>_part_00002.csv, ...
 *   event_<id>_corners.csv     index,name,lat,lon,radiusM   (once, at session start)
 *   event_<id>_backup.csv      complete finished file, uploaded at Stop (parts are then deleted)
 */
object LiveParts {

    fun partName(eventId: Long, number: Int) = "event_${eventId}_part_%05d.csv".format(number)

    fun cornersName(eventId: Long) = "event_${eventId}_corners.csv"

    /**
     * Copies bytes [fromOffset, end of [src]) into [dest], but only up to the last complete line,
     * so a row is never split across parts. Returns the new offset (unchanged if nothing to copy,
     * in which case [dest] is not created).
     */
    fun slice(src: File, fromOffset: Long, dest: File): Long {
        if (!src.exists()) return fromOffset
        RandomAccessFile(src, "r").use { raf ->
            val len = raf.length()
            if (len <= fromOffset) return fromOffset

            // Find the last '\n' in [fromOffset, len)
            var end = len
            val probe = ByteArray(1)
            while (end > fromOffset) {
                raf.seek(end - 1)
                raf.readFully(probe)
                if (probe[0] == '\n'.code.toByte()) break
                end--
            }
            if (end <= fromOffset) return fromOffset

            raf.seek(fromOffset)
            dest.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var remaining = end - fromOffset
                while (remaining > 0) {
                    val n = raf.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    remaining -= n
                }
            }
            return end
        }
    }

    /** Reads a file written by [writeCorners]: (corners, radiusM). Malformed rows are skipped. */
    fun readCorners(src: File, defaultRadiusM: Double = CornerLapDetector.DEFAULT_RADIUS_M):
            Pair<List<CornerLapDetector.CornerSpec>, Double> {
        var radius = defaultRadiusM
        val corners = src.readLines().drop(1).mapNotNull { line ->
            val p = line.split(',')
            if (p.size < 4) return@mapNotNull null
            p.getOrNull(4)?.toDoubleOrNull()?.let { radius = it }
            CornerLapDetector.CornerSpec(
                index = p[0].toIntOrNull() ?: return@mapNotNull null,
                name = p[1],
                lat = p[2].toDoubleOrNull() ?: return@mapNotNull null,
                lon = p[3].toDoubleOrNull() ?: return@mapNotNull null
            )
        }
        return corners to radius
    }

    /** "index,name,lat,lon,radiusM" CSV of the session's track corners. */
    fun writeCorners(dest: File, corners: List<CornerLapDetector.CornerSpec>, radiusM: Double) {
        dest.bufferedWriter().use { w ->
            w.write("index,name,lat,lon,radiusM\n")
            corners.forEach { c ->
                w.write("${c.index},${EventCsvFormat.sanitizeField(c.name)},${c.lat},${c.lon},$radiusM\n")
            }
        }
    }
}
