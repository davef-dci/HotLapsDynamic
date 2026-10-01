package com.hotlaps.dynamic.data

import com.hotlaps.dynamic.model.EventSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Endurance-length recording through the real writer + finishing pass.
 *
 * The old recorder rewrote the whole CSV at every corner exit: at Gingerman (Apr 2026) that
 * stalled sampling ~1 s per corner at 6 MB and ~9 s at 30 MB, then crashed. These tests assert
 * the per-sample cost stays flat no matter how big the file gets.
 */
class RecordingSoakTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun sample(t: Long) = EventSample(
        eventId = 1L, intervalMs = t, utcMs = t,
        longG = 0.1f, latG = 0.2f, zG = 0f, gSum = 0.3f,
        trackName = "Gingerman Raceway", eventName = "Soak",
        gpsLat = 42.4, gpsLon = -86.1, speedMps = 30.0
    )

    @Test
    fun threeHourSession_at20Hz_staysFastAndTagsEveryApex() {
        val csv = File(tmp.root, "event_1.csv")
        val sidecar = File(tmp.root, "event_1_apex.txt")
        val w = EventCsvWriter(csv, sidecar)

        val rows = 3 * 60 * 60 * 20   // 216,000 rows at 20 Hz
        val periodMs = 50L
        // A 3 s corner visit every 8 s, apex 1.5 s into it
        var worstCallNs = 0L
        var worstLateCallNs = 0L
        var apexes = 0
        for (i in 0 until rows) {
            val t = i * periodMs
            val phase = t % 8_000
            val visitStart = t - phase
            val inVisit = phase < 3_000

            val start = System.nanoTime()
            // Same order as DriveViewModel: the visit is finalized (apex tagged) on the first
            // sample outside the radius, before that sample is appended.
            if (phase == 3_000L) {
                w.tagApex(visitStart + 1_500, 1 + (apexes % 12), 1 + apexes / 12, "C")
                apexes++
            }
            w.append(sample(t), if (inVisit) visitStart else null)
            val ns = System.nanoTime() - start
            if (ns > worstCallNs) worstCallNs = ns
            if (i > rows * 9 / 10 && ns > worstLateCallNs) worstLateCallNs = ns
        }
        w.close()

        val mb = csv.length() / 1e6
        println("soak: rows=$rows file=${"%.1f".format(mb)} MB apexes=$apexes " +
                "worstCall=${worstCallNs / 1_000_000} ms worstLast10%=${worstLateCallNs / 1_000_000} ms " +
                "sidecarTags=${w.sidecarTags}")

        assertEquals(0, w.sidecarTags)
        // Late in the session (file at ~40 MB) a call must still be quick. The old code took
        // seconds here. Generous bound for slow CI machines.
        assertTrue("worst call late in session ${worstLateCallNs / 1_000_000} ms",
            worstLateCallNs < 250_000_000L)

        val fin = EventPostProcessor.finish(csv, "Soak, final", sidecar)
        assertEquals(rows, fin.rows)
        val apexRows = csv.bufferedReader().useLines { lines ->
            lines.drop(1).count { it.endsWith(",1") }
        }
        assertEquals(apexes, apexRows)
    }

    /**
     * Optional: replay a real recorded event CSV through the writer using its own recorded
     * closestCornerIndex / distance columns to open and close corner visits.
     *
     *   gradlew :app:testDebugUnitTest --tests "*RecordingSoakTest*" -DreplayCsv="C:/path/event.csv"
     */
    @Test
    fun replayRealSession_everyVisitGetsItsApexInMemory() {
        val path = System.getProperty("replayCsv")
        assumeTrue("set -DreplayCsv=<file> to run", !path.isNullOrBlank())
        val source = File(path!!)
        val radiusM = System.getProperty("replayRadiusM")?.toDoubleOrNull() ?: 30.0

        val csv = File(tmp.root, "event_1.csv")
        val w = EventCsvWriter(csv, File(tmp.root, "event_1_apex.txt"))

        data class Visit(val entry: Long, var bestT: Long, var bestD: Double, val count: Int)
        val open = HashMap<Int, Visit>()
        val counts = HashMap<Int, Int>()
        var visits = 0
        var worstNs = 0L

        source.bufferedReader().useLines { lines ->
            lines.drop(1).forEach { line ->
                val p = line.split(',')
                if (p.size < EventCsvFormat.EXPECTED_COLS) return@forEach
                val t = p[0].toLong()
                val corner = p[EventCsvFormat.IDX_CLOSEST_CORNER_INDEX].toInt()
                val d = p[EventCsvFormat.IDX_DIST_TO_CLOSEST_CORNER_M].toDouble()
                val inside = corner > 0 && d > 0 && d <= radiusM

                val start = System.nanoTime()
                if (corner > 0) {
                    val v = open[corner]
                    if (inside) {
                        if (v == null) {
                            val n = (counts[corner] ?: 0) + 1
                            counts[corner] = n
                            open[corner] = Visit(t, t, d, n)
                        } else if (d < v.bestD) { v.bestD = d; v.bestT = t }
                    } else if (v != null) {
                        // finalize before appending this sample, as DriveViewModel does
                        w.tagApex(v.bestT, corner, v.count, "C$corner")
                        open.remove(corner)
                        visits++
                    }
                }
                w.append(sample(t), open.values.minOfOrNull { it.entry })
                worstNs = maxOf(worstNs, System.nanoTime() - start)
            }
        }
        w.close()
        val sidecarTags = w.sidecarTags
        val fin = EventPostProcessor.finish(csv, null, File(tmp.root, "event_1_apex.txt"))
        val apexRows = csv.bufferedReader().useLines { l -> l.drop(1).count { it.endsWith(",1") } }
        println("replay ${source.name}: visits=$visits apexRows=$apexRows " +
                "viaSidecar=$sidecarTags (visits longer than the 2 min hold) " +
                "worstCall=${worstNs / 1_000_000} ms rows=${fin.rows}")
        assertEquals(visits, apexRows)
        assertTrue(sidecarTags <= visits / 100) // only rare, very long visits
        assertTrue(worstNs < 250_000_000L)
    }
}
