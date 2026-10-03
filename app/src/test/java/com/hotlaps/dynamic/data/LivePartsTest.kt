package com.hotlaps.dynamic.data

import com.hotlaps.dynamic.model.EventSample
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.TimeZone

class LivePartsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun sample(t: Long) = EventSample(
        eventId = 7L, intervalMs = t, utcMs = t,
        longG = 0.1f, latG = 0.2f, zG = 0f, gSum = 0.3f,
        trackName = "T", eventName = "E", gpsLat = 43.0, gpsLon = -89.0, speedMps = 20.0
    )

    @Test
    fun partsConcatenateToTheEventFile_andNeverSplitARow() {
        val csv = File(tmp.root, "event_7.csv")
        val w = EventCsvWriter(csv, File(tmp.root, "event_7_apex.txt"), flushEveryRows = 7,
            timeZone = TimeZone.getTimeZone("UTC"))
        val partsDir = tmp.newFolder("parts")
        var offset = 0L
        var number = 0

        for (i in 0 until 1_000) {
            w.append(sample(i * 100L), holdFromUtcMs = null)
            if (i % 37 == 0) { // irregular snapshot times, like the 60 s live cycle
                w.flushReady()
                val dest = File(partsDir, LiveParts.partName(7L, number + 1))
                val newOffset = LiveParts.slice(csv, offset, dest)
                if (newOffset > offset) { number++; offset = newOffset } else assertFalse(dest.exists())
            }
        }
        w.close()
        LiveParts.slice(csv, offset, File(partsDir, LiveParts.partName(7L, number + 1))).also {
            if (it > offset) number++
        }

        val parts = partsDir.listFiles()!!.sortedBy { it.name }
        assertEquals(number, parts.size)
        assertTrue(parts.first().readText().startsWith("timestampMs"))
        assertTrue(parts.all { it.readText().endsWith("\n") })
        assertTrue(parts.drop(1).all { p -> p.readLines().all { it.split(',').size == EventCsvFormat.EXPECTED_COLS } })
        val joined = parts.map { it.readBytes() }.reduce { a, b -> a + b }
        assertArrayEquals(csv.readBytes(), joined)
    }

    @Test
    fun partialLastLineIsLeftForTheNextPart() {
        val src = File(tmp.root, "s.csv").apply { writeText("a,1\nb,2\nc,") }
        val p1 = File(tmp.root, "p1")
        val off = LiveParts.slice(src, 0, p1)
        assertEquals("a,1\nb,2\n", p1.readText())
        src.appendText("3\n")
        val p2 = File(tmp.root, "p2")
        assertEquals(src.length(), LiveParts.slice(src, off, p2))
        assertEquals("c,3\n", p2.readText())
    }

    @Test
    fun cornersFile() {
        val f = File(tmp.root, "c.csv")
        LiveParts.writeCorners(f, listOf(CornerLapDetector.CornerSpec(1, "Turn 1, fast", 42.4, -86.1)), 30.0)
        assertEquals(listOf("index,name,lat,lon,radiusM", "1,Turn 1  fast,42.4,-86.1,30.0"), f.readLines())
        val (back, radius) = LiveParts.readCorners(f)
        assertEquals(listOf(CornerLapDetector.CornerSpec(1, "Turn 1  fast", 42.4, -86.1)), back)
        assertEquals(30.0, radius, 0.0)
        // A session without a track has a header-only file
        LiveParts.writeCorners(f, emptyList(), 25.0)
        assertTrue(LiveParts.readCorners(f).first.isEmpty())
    }
}
