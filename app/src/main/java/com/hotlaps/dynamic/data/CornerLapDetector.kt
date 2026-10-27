package com.hotlaps.dynamic.data

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Offline corner-apex and lap detection, run once when a session is finished.
 *
 * Why offline: live detection only sees raw GPS fixes. With the phone's own GPS (~1 Hz) a
 * car is inside a 30 m corner radius for only 2-3 fixes, so at Gingerman (Apr 2026) only 30%
 * of corner passes got an apex, and some passes jumped the radius entirely. And because the
 * Nth visit to a corner was used as "lap N", one missed pass shifted every later lap.
 *
 * Preferred: along-track timing (see [alongTrack]). A clean lap from the session is the
 * reference line; each corner's time is when the car's progress along that line passes the
 * corner, so a pass that stays outside the radius still counts. Works at 1 Hz (Road America,
 * Oct 2026). Needs one clean lap that the radius pass below can find; without it, falls back to:
 *
 * Radius detection:
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

        // ---- Along-track timing (preferred) ---------------------------------------------
        val visitList = visits.map { RadiusVisit(it.corner, it.minRow) }
        alongTrack(timestamps, xs, ys, n, fixRows, corners, visitList, radiusM, ::xOf, ::yOf)?.let { return it }

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

    // =====================================================================================
    // Along-track timing
    //
    // The radius test above needs a fix (or an interpolated chord) to come within radiusM of
    // each corner point. With phone GPS at 1 Hz on a real track the chords cut corners and
    // the fixes wander by 5-10 m, so most passes miss (Road America, Oct 2026: puck dropped at
    // lap 38 and only 0-3 of 13 corners per lap were found after that).
    //
    // Instead:
    //  - pick a clean lap from this session as a reference line (radius visits find it)
    //  - project every fix onto that line -> distance along the lap, unwrapped across laps
    //  - a corner's time is when that distance passes the corner's own distance on the line,
    //    interpolated between the two fixes either side
    // Being off the line sideways doesn't matter, only progress along it. Checked against
    // 10 Hz puck laps thinned to 1 Hz: 468/468 corners, ~0.1 s corner / ~0.05 s lap-time error.
    // =====================================================================================

    private data class RadiusVisit(val corner: CornerSpec, val minRow: Int)

    private class RefLine(val xs: DoubleArray, val ys: DoubleArray, val cum: DoubleArray) {
        val length get() = cum.last()
        private val grid = HashMap<Long, MutableList<Int>>()

        init {
            for (k in 0 until xs.size - 1) {
                val gx0 = floor(minOf(xs[k], xs[k + 1]) / CELL_M).toInt() - 1
                val gx1 = floor(maxOf(xs[k], xs[k + 1]) / CELL_M).toInt() + 1
                val gy0 = floor(minOf(ys[k], ys[k + 1]) / CELL_M).toInt() - 1
                val gy1 = floor(maxOf(ys[k], ys[k + 1]) / CELL_M).toInt() + 1
                for (gx in gx0..gx1) for (gy in gy0..gy1) grid.getOrPut(key(gx, gy)) { ArrayList() }.add(k)
            }
        }

        private fun key(gx: Int, gy: Int) = (gx.toLong() shl 32) or (gy.toLong() and 0xffffffffL)

        /**
         * Distance along the line + sideways distance, or null if no segment is near.
         * Where two parts of the track are close (hairpin legs), the candidate nearest
         * [hintS] wins among those within [AMBIGUITY_M] of the best.
         */
        fun project(x: Double, y: Double, hintS: Double? = null): Pair<Double, Double>? {
            val segs = grid[key(floor(x / CELL_M).toInt(), floor(y / CELL_M).toInt())] ?: return null
            val cands = ArrayList<Pair<Double, Double>>(4)
            for (k in segs) {
                val dx = xs[k + 1] - xs[k]
                val dy = ys[k + 1] - ys[k]
                val seg2 = dx * dx + dy * dy
                val u = if (seg2 == 0.0) 0.0
                else (((x - xs[k]) * dx + (y - ys[k]) * dy) / seg2).coerceIn(0.0, 1.0)
                val d = hypot(x - xs[k] - u * dx, y - ys[k] - u * dy)
                cands.add((cum[k] + u * sqrt(seg2)) to d)
            }
            val best = cands.minByOrNull { it.second } ?: return null
            if (hintS == null) return best
            // Neighbouring segments of the same stretch are always close too; only a
            // candidate on a different part of the lap is a real ambiguity.
            val close = cands.filter { it.second <= best.second + AMBIGUITY_M }
            val anchor = close.minByOrNull { along(it.first, hintS) } ?: return best
            return close.filter { along(it.first, anchor.first) <= SAME_STRETCH_M }.minByOrNull { it.second }
        }

        /** Distance between two positions along the closed lap. */
        private fun along(a: Double, b: Double): Double {
            val diff = abs(a - b) % length
            return minOf(diff, length - diff)
        }
    }

    private fun alongTrack(
        timestamps: LongArray,
        rowXs: DoubleArray,
        rowYs: DoubleArray,
        n: Int,
        fixRows: List<Int>,
        corners: List<CornerSpec>,
        visits: List<RadiusVisit>,
        radiusM: Double,
        xOf: (Double) -> Double,
        yOf: (Double) -> Double
    ): MutableList<Apex>? {
        val m = fixRows.size
        val ft = LongArray(m) { timestamps[fixRows[it]] }
        val fx = DoubleArray(m) { rowXs[fixRows[it]] }
        val fy = DoubleArray(m) { rowYs[fixRows[it]] }

        val ref = referenceLap(ft, fx, fy, corners, visits, timestamps, radiusM, xOf, yOf) ?: return null
        val L = ref.length

        // Each corner's distance along the reference lap
        val cornerS = HashMap<Int, Double>()
        for (c in corners) {
            val p = ref.project(xOf(c.lon), yOf(c.lat)) ?: continue
            if (p.second <= MAX_XTRACK_M) cornerS[c.index] = p.first
        }
        if (cornerS.size < 2) return null
        val byS = corners.filter { it.index in cornerS }.sortedBy { cornerS.getValue(it.index) }

        // ---- Crossings ----
        data class Crossing(val utcMs: Long, val corner: CornerSpec)
        val crossings = ArrayList<Crossing>()
        var prevT = 0L
        var prevS = Double.NaN
        var prevU = Double.NaN
        var maxU = Double.NaN
        var wraps = 0
        for (k in 0 until m) {
            if (fx[k].isNaN()) continue
            val p = ref.project(fx[k], fy[k], if (prevS.isNaN()) null else prevS) ?: continue
            if (p.second > MAX_XTRACK_M) continue   // paddock, or a bad fix
            val s = p.first
            if (!prevS.isNaN()) {
                val ds = s - prevS
                if (ds < -L / 2) wraps++ else if (ds > L / 2) wraps--
            }
            val u = wraps * L + s
            // Far behind the furthest point reached: the car really is elsewhere (pit lane or a
            // paddock next to another part of the track), not jitter. Start counting from here,
            // or corners are skipped until it passes the old point again (Road America, Oct 2026:
            // paddock 35 m from the Carousel, so the out-lap after lunch lost corners 1-3).
            if (!maxU.isNaN() && u < maxU - REWIND_M) maxU = u
            val dtMs = ft[k] - prevT
            // A small step is never a GPS jump: off the reference line on the inside of a bend,
            // a few metres sideways moves the projection fast (Bend 2: 13 m in 0.12 s).
            val plausible = dtMs in 1..MAX_INTERP_GAP_MS &&
                    (u - prevU <= SMALL_STEP_M || (u - prevU) / dtMs * 1000 <= MAX_SPEED_MPS)
            if (!maxU.isNaN() && u > maxU && plausible) {
                // Every corner distance passed between max(prevU, maxU) and u; never twice
                // (maxU), so jitter while stopped can't repeat a corner.
                val lo = maxOf(prevU, maxU)
                var lap = floor(lo / L).toLong()
                while (lap * L <= u) {
                    for (c in byS) {
                        val target = lap * L + cornerS.getValue(c.index)
                        if (target > lo && target <= u) {
                            val t = prevT + ((ft[k] - prevT) * (target - prevU) / (u - prevU)).toLong()
                            crossings.add(Crossing(t, c))
                        }
                    }
                    lap++
                }
            }
            maxU = if (maxU.isNaN()) u else maxOf(maxU, u)
            prevT = ft[k]; prevS = s; prevU = u
        }
        if (crossings.isEmpty()) return null

        // ---- Apex rows, laps by corner order (same rule as the radius path) ----
        var lap = 1
        var prevIndex = 0
        return crossings.mapTo(ArrayList()) { cr ->
            val c = cr.corner
            if (prevIndex != 0 && c.index <= prevIndex) lap++
            prevIndex = c.index
            val row = nearestRow(timestamps, n, cr.utcMs)
            val d = if (rowXs[row].isNaN()) Double.NaN
            else hypot(rowXs[row] - xOf(c.lon), rowYs[row] - yOf(c.lat))
            Apex(row, timestamps[row], c.index, c.name, lap, d)
        }
    }

    /**
     * A clean lap from the session itself: between two consecutive radius visits of the same
     * corner, passing near every other corner in order, no slower than [REF_MAX_SLOWER] x the
     * quickest such lap (not a pit lap). Highest GPS rate wins, then quickest.
     */
    private fun referenceLap(
        ft: LongArray,
        fx: DoubleArray,
        fy: DoubleArray,
        corners: List<CornerSpec>,
        visits: List<RadiusVisit>,
        timestamps: LongArray,
        radiusM: Double,
        xOf: (Double) -> Double,
        yOf: (Double) -> Double
    ): RefLine? {
        if (corners.size < 2) return null
        val near2 = (radiusM * REF_CORNER_RADIUS_FACTOR).let { it * it }
        val cyclic = corners.sortedBy { it.index }

        data class Cand(val a: Int, val b: Int, val durMs: Long)   // fix index range
        val cands = ArrayList<Cand>()
        for ((_, vs) in visits.groupBy { it.corner.index }) {
            val times = vs.map { timestamps[it.minRow] }.sorted()
            for (j in 0 until times.size - 1) {
                val a = ft.lowerBound(times[j])
                val b = ft.lowerBound(times[j + 1])
                if (b - a < MIN_REF_FIXES) continue
                // Gaps in GPS would make a bad line
                if ((a + 1..b).any { ft[it] - ft[it - 1] > MAX_INTERP_GAP_MS }) continue
                // Every other corner passed, in order
                val start = cyclic.indexOfFirst { it.index == vs[0].corner.index }
                var cursor = a
                var ok = true
                for (step in 1 until cyclic.size) {
                    val c = cyclic[(start + step) % cyclic.size]
                    val cx = xOf(c.lon); val cy = yOf(c.lat)
                    var best = -1; var bestD2 = Double.MAX_VALUE
                    for (k in cursor..b) {
                        if (fx[k].isNaN()) continue
                        val d2 = (fx[k] - cx).let { it * it } + (fy[k] - cy).let { it * it }
                        if (d2 < bestD2) { bestD2 = d2; best = k }
                    }
                    if (best < 0 || bestD2 > near2) { ok = false; break }
                    cursor = best
                }
                if (ok) cands.add(Cand(a, b, ft[b] - ft[a]))
            }
        }
        if (cands.isEmpty()) return null
        val quickest = cands.minOf { it.durMs }
        val pick = cands.filter { it.durMs <= quickest * REF_MAX_SLOWER }
            .maxWithOrNull(compareBy<Cand>({ (it.b - it.a).toDouble() / it.durMs }, { -it.durMs }))
            ?: return null

        val px = ArrayList<Double>(); val py = ArrayList<Double>()
        for (k in pick.a..pick.b) {
            if (fx[k].isNaN()) continue
            if (px.isNotEmpty() && fx[k] == px.last() && fy[k] == py.last()) continue
            px.add(fx[k]); py.add(fy[k])
        }
        // The last fix can be just past the first one; a closing chord that points backwards
        // would make the lap too long and corner times drift a little more every lap.
        while (px.size > 2 &&
            (px[0] - px.last()) * (px[1] - px[0]) + (py[0] - py.last()) * (py[1] - py[0]) < 0
        ) {
            px.removeAt(px.size - 1); py.removeAt(py.size - 1)
        }
        px.add(px[0]); py.add(py[0])   // close the loop
        val cum = DoubleArray(px.size)
        for (k in 1 until px.size) cum[k] = cum[k - 1] + hypot(px[k] - px[k - 1], py[k] - py[k - 1])
        if (cum.last() <= 0.0) return null
        return RefLine(px.toDoubleArray(), py.toDoubleArray(), cum)
    }

    /** First index with value >= [t]. */
    private fun LongArray.lowerBound(t: Long): Int {
        var lo = 0; var hi = size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (this[mid] < t) lo = mid + 1 else hi = mid }
        return lo
    }

    private fun nearestRow(timestamps: LongArray, n: Int, t: Long): Int {
        var lo = 0; var hi = n
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (timestamps[mid] < t) lo = mid + 1 else hi = mid }
        if (lo >= n) return n - 1
        if (lo > 0 && t - timestamps[lo - 1] < timestamps[lo] - t) return lo - 1
        return lo
    }

    /** Grid cell size for the reference-line segment index. */
    private const val CELL_M = 50.0

    /** Fixes further than this from the reference line are ignored (paddock, bad fixes). */
    private const val MAX_XTRACK_M = 40.0

    /** Progress faster than this between two fixes is a GPS jump: no corners from it. */
    private const val MAX_SPEED_MPS = 100.0

    /** A step along the line this short is accepted whatever its apparent speed. */
    private const val SMALL_STEP_M = 25.0

    /** Falling this far behind the furthest point reached means the car is elsewhere, not jitter. */
    private const val REWIND_M = 100.0

    /** Near-equal projections onto different parts of the line: prefer continuity. */
    private const val AMBIGUITY_M = 10.0

    /** Candidates this close along the lap are the same stretch of track, not an ambiguity. */
    private const val SAME_STRETCH_M = 100.0

    /** A reference lap must pass within radius x this of every corner. */
    private const val REF_CORNER_RADIUS_FACTOR = 2.5

    /** Reference lap no slower than this x the quickest candidate. */
    private const val REF_MAX_SLOWER = 1.25

    /** Fewest fixes for a usable reference lap. */
    private const val MIN_REF_FIXES = 20

    /** Gap fill only between neighbours this close in time (one corner-to-corner stretch, not a pit stop). */
    private const val GAP_FILL_MAX_WINDOW_MS = 60_000L

    /** Gap fill at most this many consecutive missing corners. */
    private const val GAP_FILL_MAX_CORNERS = 2

    /** A gap-filled corner's closest approach must be within radius x this. */
    private const val GAP_FILL_RADIUS_FACTOR = 2.0
}
