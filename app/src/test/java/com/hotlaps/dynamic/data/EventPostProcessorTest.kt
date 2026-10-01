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
    fun headerlessAndMalformedRowsSurvive() {
        val f = write("e.csv", row(0, "5.0"), "garbage,row", row(100, "6.0"))
        EventPostProcessor.finish(f, eventName = null, apexSidecar = null)
        val lines = f.readLines()
        assertEquals(3, lines.size)
        assertEquals("garbage,row", lines[1])
    }
}
