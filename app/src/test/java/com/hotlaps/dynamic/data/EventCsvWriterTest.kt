package com.hotlaps.dynamic.data

import com.hotlaps.dynamic.model.EventSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.TimeZone

class EventCsvWriterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var csv: File
    private lateinit var sidecar: File

    @Before
    fun setUp() {
        csv = File(tmp.root, "event_1.csv")
        sidecar = File(tmp.root, "event_1_apex.txt")
    }

    private fun writer(maxHoldMs: Long = 120_000L, flushEveryRows: Int = 50) =
        EventCsvWriter(csv, sidecar, maxHoldMs, flushEveryRows, TimeZone.getTimeZone("UTC"))

    private fun sample(t: Long, eventName: String = "Test") = EventSample(
        eventId = 1L, intervalMs = t, utcMs = t,
        longG = 0.1f, latG = 0.2f, zG = 0f, gSum = 0.3f,
        trackName = "Track", eventName = eventName,
        gpsLat = 43.0, gpsLon = -89.0, speedMps = 20.0
    )

    private fun dataRows(): List<List<String>> =
        csv.readLines().drop(1).map { it.split(',') }

    @Test
    fun apexInsideOpenVisit_isTaggedInMemory_andWrittenOnCorrectRow() {
        val w = writer()
        // 0..99 outside, visit open 100..130 (entry at t=10_000), then outside again
        for (i in 0 until 100) w.append(sample(i * 100L), holdFromUtcMs = null)
        for (i in 100..130) w.append(sample(i * 100L), holdFromUtcMs = 10_000L)

        assertTrue(w.tagApex(apexUtcMs = 11_530L, cornerIndex = 3, visitNumber = 2, cornerName = "Canada"))
        for (i in 131 until 200) w.append(sample(i * 100L), holdFromUtcMs = null)
        w.close()

        val rows = dataRows()
        assertEquals(200, rows.size)
        val apexRows = rows.filter { it[EventCsvFormat.IDX_APEX] == "1" }
        assertEquals(1, apexRows.size)
        val apex = apexRows.single()
        assertEquals("11500", apex[EventCsvFormat.IDX_TIMESTAMP_MS]) // nearest row to 11_530
        assertEquals("3", apex[EventCsvFormat.IDX_CORNER_INDEX])
        assertEquals("Canada", apex[EventCsvFormat.IDX_CORNER_NAME])
        assertEquals("2", apex[EventCsvFormat.IDX_VISIT_NUMBER])
        assertFalse(sidecar.exists())
        assertEquals(1, csv.readLines().count { it.startsWith("timestampMs") })
    }

    @Test
    fun fileIsOnlyEverAppendedTo_neverRewritten() {
        val w = writer(flushEveryRows = 10)
        var snapshot = ""
        for (i in 0 until 2_000) {
            val visitOpen = (i / 60) % 2 == 1 // alternate 6 s outside / 6 s inside
            val entry = if (visitOpen) (i - i % 60) * 100L else null
            w.append(sample(i * 100L), entry)
            if (visitOpen && i % 60 == 59) w.tagApex(i * 100L - 3_000, 1, i / 120, "C1")

            val now = if (csv.exists()) csv.readText() else ""
            assertTrue("file content was rewritten at row $i", now.startsWith(snapshot))
            snapshot = now
        }
        w.close()
        assertEquals(2_000, dataRows().size)
    }

    @Test
    fun apexOlderThanHeldRows_goesToSidecar() {
        val w = writer()
        for (i in 0 until 100) w.append(sample(i * 100L), holdFromUtcMs = null)
        w.flushReady()
        assertFalse(w.tagApex(apexUtcMs = 2_000L, cornerIndex = 1, visitNumber = 1, cornerName = "C1"))
        w.close()

        assertEquals(listOf("2000,1,1,C1"), sidecar.readLines())
        assertTrue(dataRows().none { it[EventCsvFormat.IDX_APEX] == "1" })
    }

    @Test
    fun stuckVisit_isCappedByMaxHold() {
        val w = writer(maxHoldMs = 5_000L, flushEveryRows = 1)
        // Visit never closes (car parked in the radius)
        for (i in 0 until 300) w.append(sample(i * 100L), holdFromUtcMs = 0L)
        // Rows older than 5 s behind the newest must already be on disk
        val written = dataRows().size
        assertTrue("expected ~250 rows written, got $written", written in 240..260)
    }

    @Test
    fun midVisitFlush_keepsHeldRowsTaggable() {
        val w = writer()
        for (i in 0 until 60) w.append(sample(i * 100L), holdFromUtcMs = null)
        for (i in 60 until 90) w.append(sample(i * 100L), holdFromUtcMs = 6_000L)
        w.flushReady() // periodic backup while inside a corner
        assertEquals(60, dataRows().size)

        assertTrue(w.tagApex(7_000L, 2, 1, "C2"))
        w.close()
        assertEquals("7000", dataRows().single { it[EventCsvFormat.IDX_APEX] == "1" }[0])
    }

    @Test
    fun commasInNames_doNotShiftColumns() {
        val w = writer()
        w.append(sample(0L, eventName = "Road America, Sat AM"), holdFromUtcMs = 0L)
        w.tagApex(0L, 1, 1, "Turn 5, Moraine")
        w.close()

        val row = dataRows().single()
        assertEquals(EventCsvFormat.EXPECTED_COLS, row.size)
        assertEquals("Road America  Sat AM", row[EventCsvFormat.IDX_EVENT_NAME])
        assertEquals("Turn 5  Moraine", row[EventCsvFormat.IDX_CORNER_NAME])
    }
}
