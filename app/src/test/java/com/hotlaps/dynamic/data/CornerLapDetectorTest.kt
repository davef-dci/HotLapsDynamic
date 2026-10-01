package com.hotlaps.dynamic.data

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

class CornerLapDetectorTest {

    /** Start 60 m past corner 1 (outside its 30 m radius). */
    private val START_ANGLE = 0.3

    // A circular "track" of radius 200 m with 4 corners at 0/90/180/270 degrees, driven
    // clockwise starting just after corner 1.
    private val lat0 = 43.0
    private val lon0 = -89.0
    private val mPerDegLon = 111_320.0 * cos(Math.toRadians(lat0))

    private fun pointAt(angleRad: Double, radiusM: Double = 200.0): Pair<Double, Double> {
        val x = radiusM * sin(angleRad)
        val y = radiusM * cos(angleRad)
        return (lat0 + y / 111_320.0) to (lon0 + x / mPerDegLon)
    }

    private val corners = (0 until 4).map { k ->
        val (la, lo) = pointAt(k * Math.PI / 2)
        CornerLapDetector.CornerSpec(index = k + 1, name = "C${k + 1}", lat = la, lon = lo)
    }

    /**
     * Simulate [laps] laps at 30 m/s, rows at [rowHz], GPS fixes at [gpsHz] (rows repeat
     * the last fix in between, as the recorder does).
     */
    private fun simulate(laps: Double, rowHz: Int, gpsHz: Double): Triple<LongArray, DoubleArray, DoubleArray> {
        val circumference = 2 * Math.PI * 200.0
        val totalS = laps * circumference / 30.0
        val n = (totalS * rowHz).toInt()
        val ts = LongArray(n)
        val la = DoubleArray(n)
        val lo = DoubleArray(n)
        var lastFixT = -1e9
        var fix = 0.0 to 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / rowHz
            if (t - lastFixT >= 1.0 / gpsHz - 1e-9) {
                fix = pointAt(START_ANGLE + 30.0 * t / 200.0)
                lastFixT = t
            }
            ts[i] = 1_000_000L + (t * 1000).toLong()
            la[i] = fix.first
            lo[i] = fix.second
        }
        return Triple(ts, la, lo)
    }

    @Test
    fun puckRate_everyCornerEveryLap_lapsByOrder() {
        val (ts, la, lo) = simulate(laps = 5.0, rowHz = 10, gpsHz = 10.0)
        val apexes = CornerLapDetector.detect(ts, la, lo, ts.size, corners)
        // starts just after C1, so lap 1 = C2..C4, then 4 full laps of C1..C4, then C1 of lap 6
        assertEquals(listOf(2, 3, 4), apexes.filter { it.lap == 1 }.map { it.cornerIndex })
        assertEquals(listOf(1, 2, 3, 4), apexes.filter { it.lap == 2 }.map { it.cornerIndex })
        assertTrue(apexes.all { it.minDistanceM < 2.0 })
    }

    @Test
    fun phoneGpsAt1Hz_interpolationStillFindsEveryCorner() {
        // 30 m/s with 1 Hz fixes: only ~2 raw fixes per 60 m-wide radius
        val (ts, la, lo) = simulate(laps = 5.0, rowHz = 20, gpsHz = 1.0)
        val apexes = CornerLapDetector.detect(ts, la, lo, ts.size, corners)
        val perLap = apexes.groupBy { it.lap }.mapValues { (_, v) -> v.map { it.cornerIndex } }
        assertEquals(listOf(1, 2, 3, 4), perLap[2])
        assertEquals(listOf(1, 2, 3, 4), perLap[5])
        // apex placed within a few metres despite 30 m between fixes (chord of the circle)
        assertTrue(apexes.all { it.minDistanceM < 5.0 })
    }

    @Test
    fun missedCorner_leavesGap_doesNotShiftLaterLaps() {
        val (ts, la, lo) = simulate(laps = 4.0, rowHz = 10, gpsHz = 10.0)
        // Corner 3 moved 60 m off the line for... all laps would be "missed": instead drop GPS
        // for a few seconds around one pass of corner 3 in lap 2.
        val c3PassRow = ts.indices.first { i ->
            val t = (ts[i] - 1_000_000L) / 1000.0
            t > (2 * Math.PI * 200 / 30) && kotlin.math.abs(((START_ANGLE + 30.0 * t / 200.0) % (2 * Math.PI)) - Math.PI) < 0.01
        }
        for (i in c3PassRow - 40..c3PassRow + 40) { la[i] = 0.0; lo[i] = 0.0 }
        val apexes = CornerLapDetector.detect(ts, la, lo, ts.size, corners)
        val perLap = apexes.groupBy { it.lap }.mapValues { (_, v) -> v.map { it.cornerIndex } }
        assertEquals(listOf(1, 2, 4), perLap[2])      // gap where C3 was missed
        assertEquals(listOf(1, 2, 3, 4), perLap[3])   // later laps unaffected
    }

    /**
     * Real recorded session (optional):
     *   gradlew testDebugUnitTest --tests "*CornerLapDetectorTest*" -DreplayCsv=<event.csv> -DtrackJson=<track.json>
     */
    @Test
    fun realSession() {
        val csvPath = System.getProperty("replayCsv")
        val trackPath = System.getProperty("trackJson")
        assumeTrue(!csvPath.isNullOrBlank() && !trackPath.isNullOrBlank())

        val track = JsonParser.parseString(File(trackPath!!).readText()).asJsonObject
        val specs = track.getAsJsonArray("corners").map { e ->
            val o = e.asJsonObject
            CornerLapDetector.CornerSpec(
                index = o["index"].asInt,
                name = o["name"]?.takeIf { !it.isJsonNull }?.asString ?: "Corner ${o["index"].asInt}",
                lat = o["lat"].asDouble,
                lon = o["lon"].asDouble
            )
        }

        val ts = ArrayList<Long>(); val la = ArrayList<Double>(); val lo = ArrayList<Double>()
        var liveApexes = 0
        File(csvPath!!).bufferedReader().useLines { lines ->
            lines.drop(1).forEach { line ->
                val p = line.split(',')
                if (p.size < EventCsvFormat.EXPECTED_COLS) return@forEach
                ts.add(p[0].toLong()); la.add(p[5].toDouble()); lo.add(p[6].toDouble())
                if (p[EventCsvFormat.IDX_APEX].trim() in setOf("1", "True", "true")) liveApexes++
            }
        }
        val apexes = CornerLapDetector.detect(ts.toLongArray(), la.toDoubleArray(), lo.toDoubleArray(), ts.size, specs)

        val laps = apexes.groupBy { it.lap }
        println("realSession ${File(csvPath).name}: liveApexes=$liveApexes offlineApexes=${apexes.size} laps=${laps.size}")
        laps.forEach { (lap, list) ->
            println("  lap %2d: %s".format(lap, list.joinToString(" ") { "C${it.cornerIndex}" }))
        }
        val perCorner = apexes.groupingBy { it.cornerIndex }.eachCount().toSortedMap()
        println("  per corner: $perCorner")
        val dist = apexes.map { it.minDistanceM }.sorted()
        println("  closest-approach m: median=%.1f p90=%.1f max=%.1f".format(
            dist[dist.size / 2], dist[(dist.size * 9) / 10], dist.last()))

        // Full finishing pass on a copy; optionally keep the output (-DfinishOut=<file>)
        val copy = File.createTempFile("finish", ".csv").apply { deleteOnExit() }
        File(csvPath).copyTo(copy, overwrite = true)
        val result = EventPostProcessor.finish(copy, null, null, specs)
        println("  finish pass: $result")
        assertTrue(result.redetected)
        assertEquals(apexes.size, result.apexTagsApplied)
        System.getProperty("finishOut")?.takeIf { it.isNotBlank() }?.let { copy.copyTo(File(it), overwrite = true) }
    }
}
