package com.hotlaps.dynamic.data

import kotlin.math.cos

/**
 * Offline corner-apex and lap detection, run once when a session is finished.
 *
 * Why offline: live detection only sees raw GPS fixes. With the phone's own GPS (~1 Hz) a
 * car is inside a 30 m corner radius for only 2-3 fixes, so at Gingerman (Apr 2026) only 30%
 * of corner passes got an apex, and some passes jumped the radius entirely. And because the
 * Nth visit to a corner was used as "lap N", one missed pass shifted every later lap.
 *
 * Here:
 *  - positions are linearly interpolated between GPS fixes for every row (a no-op at 10 Hz)
 *  - a visit is a run of rows within [radiusM] of a corner; runs of the same corner less than
 *    [MERGE_GAP_MS] apart are one visit (GPS jitter at the radius edge)
 *  - the apex is the row of closest approach
 *  - laps are assigned by corner ORDER in time: a new lap starts whenever a corner index is not
 *    higher than the previous apex's (11 -> 1, or 10 -> 2 when 11 and 1 were missed), so a missed
 *    corner leaves a gap instead of shifting later laps
 *
 * Assumes corners are indexed in driving order, as they are when taught/entered around the lap.
 */
object CornerLapDetector {

    data class CornerSpec(val index: Int, val name: String, val lat: Double, val lon: Double)

    data class Apex(
        val row: Int,
        val utcMs: Long,
        val cornerIndex: Int,
        val cornerName: String,
        val lap: Int,
        val minDistanceM: Double
    )

    const val DEFAULT_RADIUS_M = 30.0

    /** Two passes of the same corner closer together than this are one visit. */
    const val MERGE_GAP_MS = 5_000L

    /** Don't interpolate across GPS outages longer than this; hold the last fix instead. */
    const val MAX_INTERP_GAP_MS = 5_000L

    private const val METERS_PER_DEG_LAT = 111_320.0

    /**
     * @param timestamps row times (ms), time-ordered
     * @param lats/lons  recorded row positions (0,0 = no fix); rows repeat the last fix between fixes
     */
    fun detect(
        timestamps: LongArray,
        lats: DoubleArray,
        lons: DoubleArray,
        n: Int,
        corners: List<CornerSpec>,
        radiusM: Double = DEFAULT_RADIUS_M
    ): List<Apex> {
        if (n == 0 || corners.isEmpty()) return emptyList()

        // ---- Interpolated local-metre positions per row ---------------------------------
        val fixRows = ArrayList<Int>()
        var lastLat = Double.NaN
        var lastLon = Double.NaN
        for (i in 0 until n) {
            val la = lats[i]
            val lo = lons[i]
            if (la == 0.0 && lo == 0.0) continue
            if (la != lastLat || lo != lastLon) {
                fixRows.add(i)
                lastLat = la
                lastLon = lo
            }
        }
        if (fixRows.isEmpty()) return emptyList()

        val lat0 = lats[fixRows[0]]
        val lon0 = lons[fixRows[0]]
        val mPerDegLon = METERS_PER_DEG_LAT * cos(Math.toRadians(lat0))
        fun xOf(lon: Double) = (lon - lon0) * mPerDegLon
        fun yOf(lat: Double) = (lat - lat0) * METERS_PER_DEG_LAT

        val xs = DoubleArray(n) { Double.NaN }
        val ys = DoubleArray(n) { Double.NaN }
        for (k in fixRows.indices) {
            val a = fixRows[k]
            val ax = xOf(lons[a])
            val ay = yOf(lats[a])
            val b = if (k + 1 < fixRows.size) fixRows[k + 1] else -1
            val end = if (b >= 0) b else n
            val interpolate = b >= 0 &&
                    timestamps[b] > timestamps[a] &&
                    timestamps[b] - timestamps[a] <= MAX_INTERP_GAP_MS
            val bx = if (interpolate) xOf(lons[b]) else ax
            val by = if (interpolate) yOf(lats[b]) else ay
            for (i in a until end) {
                if (lats[i] == 0.0 && lons[i] == 0.0) continue
                val u = if (interpolate)
                    (timestamps[i] - timestamps[a]).toDouble() / (timestamps[b] - timestamps[a])
                else 0.0
                xs[i] = ax + (bx - ax) * u
                ys[i] = ay + (by - ay) * u
            }
        }

        // ---- Visits per corner ----------------------------------------------------------
        data class Visit(val corner: CornerSpec, var startMs: Long, var endMs: Long, var minRow: Int, var minD2: Double)

        val r2 = radiusM * radiusM
        val visits = ArrayList<Visit>()
        for (c in corners) {
            val cx = xOf(c.lon)
            val cy = yOf(c.lat)
            var current: Visit? = null
            var last: Visit? = null
            for (i in 0 until n) {
                val x = xs[i]
                if (x.isNaN()) continue
                val dx = x - cx
                val dy = ys[i] - cy
                val d2 = dx * dx + dy * dy
                if (d2 <= r2) {
                    val t = timestamps[i]
                    val v = current
                    if (v == null) {
                        val prev = last
                        current = if (prev != null && t - prev.endMs < MERGE_GAP_MS) {
                            prev // jitter at the radius edge: continue the same visit
                        } else {
                            Visit(c, t, t, i, d2).also { visits.add(it); last = it }
                        }
                    }
                    val cur = current!!
                    cur.endMs = t
                    if (d2 < cur.minD2) {
                        cur.minD2 = d2
                        cur.minRow = i
                    }
                } else {
                    current = null
                }
            }
        }

        // ---- Laps by corner order -------------------------------------------------------
        visits.sortBy { timestamps[it.minRow] }
        var lap = 1
        var prevIndex = 0
        val apexes = visits.mapTo(ArrayList()) { v ->
            if (prevIndex != 0 && v.corner.index <= prevIndex) lap++
            prevIndex = v.corner.index
            Apex(
                row = v.minRow,
                utcMs = timestamps[v.minRow],
                cornerIndex = v.corner.index,
                cornerName = v.corner.name,
                lap = lap,
                minDistanceM = kotlin.math.sqrt(v.minD2)
            )
        }

        // ---- Gap fill -------------------------------------------------------------------
        // A corner skipped between two detected neighbours (e.g. C3 -> C5) was still driven:
        // the line just stayed outside the radius (common at 1 Hz, where interpolation cuts
        // the corner). Take its closest approach between the neighbours, if reasonably close.
        val byIndex = corners.associateBy { it.index }
        val sortedIndices = corners.map { it.index }.sorted()
        val filled = ArrayList<Apex>()
        for (k in 0 until apexes.size - 1) {
            val p = apexes[k]
            val q = apexes[k + 1]
            if (q.utcMs - p.utcMs > GAP_FILL_MAX_WINDOW_MS) continue
            val missing: List<Pair<Int, Int>> = when (q.lap) {   // (cornerIndex, lap)
                p.lap -> sortedIndices.filter { it > p.cornerIndex && it < q.cornerIndex }.map { it to p.lap }
                p.lap + 1 -> sortedIndices.filter { it > p.cornerIndex }.map { it to p.lap } +
                        sortedIndices.filter { it < q.cornerIndex }.map { it to q.lap }
                else -> emptyList()
            }
            if (missing.isEmpty() || missing.size > GAP_FILL_MAX_CORNERS) continue

            for ((index, lapOf) in missing) {
                val c = byIndex.getValue(index)
                val cx = xOf(c.lon)
                val cy = yOf(c.lat)
                var bestRow = -1
                var bestD2 = Double.MAX_VALUE
                for (i in p.row + 1 until q.row) {
                    val x = xs[i]
                    if (x.isNaN()) continue
                    val dx = x - cx
                    val dy = ys[i] - cy
                    val d2 = dx * dx + dy * dy
                    if (d2 < bestD2) { bestD2 = d2; bestRow = i }
                }
                val d = kotlin.math.sqrt(bestD2)
                if (bestRow >= 0 && d <= radiusM * GAP_FILL_RADIUS_FACTOR) {
                    filled.add(Apex(bestRow, timestamps[bestRow], index, c.name, lapOf, d))
                }
            }
        }
        apexes.addAll(filled)
        apexes.sortBy { it.utcMs }
        return apexes
    }

    /** Gap fill only between neighbours this close in time (one corner-to-corner stretch, not a pit stop). */
    private const val GAP_FILL_MAX_WINDOW_MS = 60_000L

    /** Gap fill at most this many consecutive missing corners. */
    private const val GAP_FILL_MAX_CORNERS = 2

    /** A gap-filled corner's closest approach must be within radius x this. */
    private const val GAP_FILL_RADIUS_FACTOR = 2.0
}
