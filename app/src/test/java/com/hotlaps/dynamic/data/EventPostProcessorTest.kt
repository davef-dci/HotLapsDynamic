package com.hotlaps.dynamic.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EventPostProcessorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** A 19-column row with the given timestamp and speed ("" = none). */
    private fun row(t: Long, speed: String, eventName: String = "Untitled session") =
        "$t,$t,2026-04-17 10:00:00,Gingerman Raceway,$eventName,42.4,-86.1,1,50.0," +
                "0.1,0.2,0.1,0.2,0.3,$speed,0,,0,0"

    private fun write(name: String, vararg lines: String) =
        File(tmp.root, name).apply { writeText(lines.joinToString("\n") + "\n") }

    private fun cols(file: File) = file.readLines().drop(1).map { it.split(',') }

    @Test
    fun speedsAreInterpolatedBetweenGpsSpeedChanges() {
        // GPS speed updates at t=0 (10 m/s) and t=400 (20 m/s); rows in between repeat 10
        val f = write(
            "e.csv", EventCsvFormat.HEADER.trim(),
            row(0, "10.0"), row(100, "10.0"), row(200, "10.0"), row(300, "10.0"), row(400, "20.0")
        )
        val result = EventPostProcessor.finish(f, eventName = null, apexSidecar = null)

        assertEquals(5, result.rows)
        val speeds = cols(f).map { it[EventCsvFormat.IDX_SPEED].toDouble() }
        assertEquals(listOf(10.0, 12.5, 15.0, 17.5, 20.0), speeds)
    }

    @Test
    fun eventNameAndSidecarApexesAreApplied_andSidecarRemoved() {
        val f = write(
            "e.csv", EventCsvFormat.HEADER.trim(),
            row(0, "10.0"), row(100, "10.0"), row(200, "10.0"), row(300, "10.0")
        )
        val sidecar = write("e_apex.txt", "190,4,7,Carousel")

        val result = EventPostProcessor.finish(f, eventName = "Sat, race 2", apexSidecar = sidecar)

        assertEquals(1, result.apexTagsApplied)
        val rows = cols(f)
        assertTrue(rows.all { it.size == EventCsvFormat.EXPECTED_COLS })
        assertTrue(rows.all { it[EventCsvFormat.IDX_EVENT_NAME] == "Sat  race 2" })
        val apex = rows.single { it[EventCsvFormat.IDX_APEX] == "1" }
        assertEquals("200", apex[EventCsvFormat.IDX_TIMESTAMP_MS])
        assertEquals("4", apex[EventCsvFormat.IDX_CORNER_INDEX])
        assertEquals("Carousel", apex[EventCsvFormat.IDX_CORNER_NAME])
        assertEquals("7", apex[EventCsvFormat.IDX_VISIT_NUMBER])
        assertFalse(sidecar.exists())
        assertTrue(f.readLines().first().startsWith("timestampMs"))
        assertFalse(File(tmp.root, "e.csv.tmp").exists())
        assertFalse(File(tmp.root, "e.csv.bak").exists())
    }

    @Test
    fun withTrackCorners_offlineApexesReplaceLiveTags() {
        // Car drives north along lon=-89.0 at 20 m/s, passing a corner at (43.001, -89.0001)
        val rows = (0 until 200).map { i ->
            val t = i * 100L
            val lat = 43.0 + (20.0 * i * 0.1) / 111_320.0
            // A wrong live tag (corner 9, lap 5) on row 10
            val tag = if (i == 10) "9,Old,5,1" else "0,,0,0"
            "$t,$t,2026-04-17 10:00:00,T,E,$lat,-89.0,1,50.0,0.1,0.2,0.1,0.2,0.3,20.0,$tag"
        }
        val f = write("e.csv", EventCsvFormat.HEADER.trim(), *rows.toTypedArray())
        val corner = CornerLapDetector.CornerSpec(1, "Turn 1, fast", 43.001, -89.0001)

        val result = EventPostProcessor.finish(f, null, null, listOf(corner))

        assertTrue(result.redetected)
        assertEquals(1, result.liveApexRows)
        val apexRows = cols(f).filter { it[EventCsvFormat.IDX_APEX] == "1" }
        assertEquals(1, apexRows.size)
        val a = apexRows.single()
        assertEquals("1", a[EventCsvFormat.IDX_CORNER_INDEX])
        assertEquals("Turn 1  fast", a[EventCsvFormat.IDX_CORNER_NAME])
        assertEquals("1", a[EventCsvFormat.IDX_VISIT_NUMBER])
        // closest approach: 111.32 m north of start -> row ~56 (5.6 s at 20 m/s)
        val t = a[EventCsvFormat.IDX_TIMESTAMP_MS].toLong()
        assertTrue("apex at $t", t in 5_400L..5_800L)
        assertTrue(cols(f).all { it.size == EventCsvFormat.EXPECTED_COLS })
    }

    @Test
    fun headerlessAndMalformedRowsSurvive() {
        val f = write("e.csv", row(0, "5.0"), "garbage,row", row(100, "6.0"))
        EventPostProcessor.finish(f, eventName = null, apexSidecar = null)
        val lines = f.readLines()
        assertEquals(3, lines.size)
        assertEquals("garbage,row", lines[1])
    }
}
