package com.example.ddlreminder.smartadd

import com.example.ddlreminder.reminder.ReminderOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class LocalNaturalLanguageParserTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = LocalDateTime.of(2026, 10, 14, 10, 0).atZone(zone).toInstant().toEpochMilli()
    private val parser = LocalNaturalLanguageParser()

    @Test fun `one week maps to 10080 minutes`() { assertEquals(setOf(ReminderOffset(10080)), parser.parseReminders("提前一周")) }
    @Test fun `custom two days maps correctly`() { assertEquals(setOf(ReminderOffset(2880)), parser.parseReminders("提前2天")) }
    @Test fun `custom fifteen minutes maps correctly`() { assertEquals(setOf(ReminderOffset(15)), parser.parseReminders("提前15分钟")) }
    @Test fun `event time maps to zero`() { assertEquals(setOf(ReminderOffset(0)), parser.parseReminders("事件发生时")) }
    @Test fun `duplicate reminders are deduplicated`() { assertEquals(1, parser.parseReminders("提前1小时和提前一小时").size) }

    @Test fun `tomorrow afternoon three parses correctly`() = runTest {
        val draft = parser.parse("明天下午3点交高数作业", now, zone).drafts.single()
        val local = java.time.Instant.ofEpochMilli(draft.deadlineMillis!!).atZone(zone)
        assertEquals(15, local.hour); assertEquals(15, local.dayOfMonth)
    }

    @Test fun `next Wednesday evening eight parses correctly`() = runTest {
        val draft = parser.parse("下周三晚上8点交作业", now, zone).drafts.single()
        val local = java.time.Instant.ofEpochMilli(draft.deadlineMillis!!).atZone(zone)
        assertEquals(20, local.hour); assertEquals(21, local.dayOfMonth)
    }

    @Test fun `one paragraph parses multiple tasks`() = runTest {
        val result = parser.parse("明天下午3点交作业，后天下午2点开组会，下周一做英语pre", now, zone)
        assertEquals(3, result.drafts.size)
    }

    @Test fun `missing time uses 2359 and warning`() = runTest {
        val draft = parser.parse("10月25号交作业", now, zone).drafts.single()
        val local = java.time.Instant.ofEpochMilli(draft.deadlineMillis!!).atZone(zone)
        assertEquals(23, local.hour); assertEquals(59, local.minute); assertTrue(draft.usedDefaultTime)
        assertTrue(draft.warnings.any { "23:59" in it })
    }

    @Test fun `missing date remains invalid`() = runTest {
        assertTrue(parser.parse("晚上8点交作业", now, zone).drafts.isEmpty())
    }

    @Test fun `past yearless date rolls to next year`() = runTest {
        val draft = parser.parse("1月5日交作业", now, zone).drafts.single()
        assertEquals(2027, java.time.Instant.ofEpochMilli(draft.deadlineMillis!!).atZone(zone).year)
    }

    @Test fun `afternoon half hour parses`() = runTest {
        val draft = parser.parse("明天下午三点半交作业", now, zone).drafts.single()
        val local = java.time.Instant.ofEpochMilli(draft.deadlineMillis!!).atZone(zone)
        assertEquals(15, local.hour); assertEquals(30, local.minute)
    }
}
