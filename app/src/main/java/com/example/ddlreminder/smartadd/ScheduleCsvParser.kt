package com.example.ddlreminder.smartadd

import com.example.ddlreminder.reminder.ReminderOffset
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class ScheduleCsvParser {
    fun parse(bytes: ByteArray, zoneId: ZoneId = ZoneId.systemDefault()): ParseResult {
        val text = runCatching {
            val decoded = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
            if ('\uFFFD' in decoded) error("CSV 编码错误，请使用 UTF-8")
            decoded
        }.getOrElse { return ParseResult(emptyList(), it.message) }
        val rows = runCatching { parseRows(text) }.getOrElse { return ParseResult(emptyList(), "CSV 行格式损坏") }
        if (rows.isEmpty()) return ParseResult(emptyList(), "CSV 为空")
        val header = rows.first().map { it.trim().lowercase() }
        val titleIndex = header.indexOf("title")
        val deadlineIndex = header.indexOf("deadline")
        val remindersIndex = header.indexOf("reminders")
        if (titleIndex < 0 || deadlineIndex < 0 || remindersIndex < 0) {
            return ParseResult(emptyList(), "CSV 缺少必要列：title,deadline,reminders")
        }
        val drafts = rows.drop(1).mapIndexedNotNull { index, columns ->
            if (columns.all { it.isBlank() }) return@mapIndexedNotNull null
            val line = index + 2
            val title = columns.getOrNull(titleIndex).orEmpty().trim()
            val deadlineRaw = columns.getOrNull(deadlineIndex).orEmpty().trim()
            val reminderRaw = columns.getOrNull(remindersIndex).orEmpty().trim()
            val warnings = mutableListOf<String>()
            if (title.isBlank()) warnings += "第 $line 行：标题为空"
            val deadline = runCatching {
                LocalDateTime.parse(deadlineRaw, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                    .atZone(zoneId).toInstant().toEpochMilli()
            }.getOrElse { warnings += "第 $line 行：日期格式错误"; null }
            val parsedOffsets = parseReminderTokens(reminderRaw)
            if (parsedOffsets.invalid.isNotEmpty()) warnings += "第 $line 行：无效 reminder ${parsedOffsets.invalid.joinToString()}"
            ScheduleDraft(
                title = title,
                deadlineMillis = deadline,
                reminderOffsets = ReminderOffset.normalize(parsedOffsets.values),
                remindersExplicit = reminderRaw.isNotBlank(),
                warnings = warnings,
                ambiguousDate = deadline == null,
                confidence = if (warnings.isEmpty()) ParseConfidence.HIGH else ParseConfidence.LOW,
                sourceLine = line,
                hasBlockingError = title.isBlank() || deadline == null || parsedOffsets.invalid.isNotEmpty(),
            )
        }
        return ParseResult(drafts, if (drafts.isEmpty()) "CSV 没有数据行" else null)
    }

    data class ReminderTokens(val values: List<Long>, val invalid: List<String>)

    fun parseReminderTokens(raw: String): ReminderTokens {
        val values = mutableListOf<Long>()
        val invalid = mutableListOf<String>()
        raw.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }.forEach { token ->
            val match = Regex("^(\\d+)([dhm])$", RegexOption.IGNORE_CASE).matchEntire(token)
            val number = match?.groupValues?.get(1)?.toLongOrNull()
            val minutes = when (match?.groupValues?.get(2)?.lowercase()) {
                "d" -> number?.times(1440); "h" -> number?.times(60); "m" -> number; else -> null
            }
            if (minutes == null || minutes !in 0..ReminderOffset.MAX_MINUTES) invalid += token else values += minutes
        }
        return ReminderTokens(values.distinct().sortedDescending(), invalid)
    }

    private fun parseRows(text: String): List<List<String>> {
        val rows = mutableListOf<MutableList<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' && quoted && i + 1 < text.length && text[i + 1] == '"' -> { field.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { row += field.toString(); field.clear() }
                (c == '\n' || c == '\r') && !quoted -> {
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    row += field.toString(); field.clear(); rows += row; row = mutableListOf()
                }
                else -> field.append(c)
            }
            i++
        }
        if (quoted) error("unclosed quote")
        if (field.isNotEmpty() || row.isNotEmpty()) { row += field.toString(); rows += row }
        return rows
    }

    companion object {
        const val SAMPLE = "title,deadline,reminders\n" +
            "高数作业,2026-10-20 23:59,\"5d,1d,1h\"\n" +
            "小王生日,2026-10-25 09:00,\"7d,1d,0m\"\n"
    }
}
