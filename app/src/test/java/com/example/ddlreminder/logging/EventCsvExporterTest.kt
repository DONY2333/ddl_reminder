package com.example.ddlreminder.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class EventCsvExporterTest {
    @Test
    fun `csv contains exact header`() {
        val csv = EventCsvExporter.toCsv(emptyList(), ZoneOffset.UTC)

        assertEquals(
            "event_id,event_type,event_time,ddl_id,deadline_time,reminder_type,metadata",
            csv.lineSequence().first(),
        )
    }

    @Test
    fun `csv exports multiple events`() {
        val csv = EventCsvExporter.toCsv(
            listOf(
                EventLogEntity(1, EventTypes.APP_OPENED, 0),
                EventLogEntity(2, EventTypes.DDL_DELETED, 1_000, ddlId = 9, deadlineMillis = 2_000),
            ),
            ZoneOffset.UTC,
        )

        assertEquals(3, csv.lineSequence().filter { it.isNotEmpty() }.count())
        assertTrue(csv.contains("\"app_opened\""))
        assertTrue(csv.contains("\"ddl_deleted\""))
    }

    @Test
    fun `csv escapes metadata commas quotes and newlines`() {
        val metadata = "{\"note\":\"comma, quote \\\" and\nnewline\"}"

        val csv = EventCsvExporter.toCsv(
            listOf(EventLogEntity(1, EventTypes.APP_OPENED, 0, metadataJson = metadata)),
            ZoneOffset.UTC,
        )

        assertTrue(csv.contains("\"{\"\"note\"\":\"\"comma, quote \\\"\" and\nnewline\"\"}\""))
        assertEquals(metadata, parseLastCsvField(csv.substringAfter('\n').trimEnd()))
    }

    private fun parseLastCsvField(row: String): String {
        val start = row.lastIndexOf(",\"") + 2
        return row.substring(start, row.length - 1).replace("\"\"", "\"")
    }
}
