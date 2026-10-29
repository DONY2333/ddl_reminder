package com.example.ddlreminder.logging

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object EventCsvExporter {
    private const val HEADER =
        "event_id,event_type,event_time,ddl_id,deadline_time,reminder_type,metadata"

    fun toCsv(logs: List<EventLogEntity>, zoneId: ZoneId = ZoneId.systemDefault()): String = buildString {
        appendLine(HEADER)
        logs.forEach { log ->
            appendLine(
                listOf(
                    log.id.toString(),
                    log.eventType,
                    formatTime(log.eventTime, zoneId),
                    log.ddlId?.toString().orEmpty(),
                    log.deadlineMillis?.let { formatTime(it, zoneId) }.orEmpty(),
                    log.reminderType.orEmpty(),
                    log.metadataJson.orEmpty(),
                ).joinToString(",", transform = ::escape),
            )
        }
    }

    private fun formatTime(millis: Long, zoneId: ZoneId): String =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochMilli(millis).atZone(zoneId))

    private fun escape(value: String): String =
        "\"${value.replace("\"", "\"\"")}\""
}
