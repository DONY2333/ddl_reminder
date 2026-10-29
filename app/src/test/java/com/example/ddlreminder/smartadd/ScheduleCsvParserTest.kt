package com.example.ddlreminder.smartadd

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class ScheduleCsvParserTest {
    private val parser = ScheduleCsvParser()
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test fun `csv imports multiple rows`() {
        val result = parser.parse(ScheduleCsvParser.SAMPLE.toByteArray(), zone)
        assertEquals(2, result.drafts.size); assertTrue(result.drafts.all { it.isValid })
    }
    @Test fun `csv identifies invalid date`() {
        val result = parse("title,deadline,reminders\na,bad,1d")
        assertTrue(result.drafts.single().warnings.any { "日期格式错误" in it })
    }
    @Test fun `csv identifies invalid reminder`() {
        val result = parse("title,deadline,reminders\na,2026-10-20 12:00,-1h")
        assertTrue(result.drafts.single().warnings.any { "无效 reminder" in it })
    }
    @Test fun `bad row does not remove valid row`() {
        val result = parse("title,deadline,reminders\nbad,no,xx\ngood,2026-10-20 12:00,15m")
        assertEquals(2, result.drafts.size); assertTrue(result.drafts[1].isValid)
    }
    @Test fun `missing columns is rejected`() { assertTrue(parse("title,deadline\na,b").errorMessage!!.contains("缺少")) }
    @Test fun `quoted reminder list parses`() {
        val result = parse("title,deadline,reminders\na,2026-10-20 12:00,\"7d,1d,0m\"")
        assertEquals(listOf(10080L, 1440L, 0L), result.drafts.single().reminderOffsets.map { it.minutes })
    }
    @Test fun `duplicate reminders are deduplicated`() {
        val result = parse("title,deadline,reminders\na,2026-10-20 12:00,\"1h,1h\"")
        assertEquals(1, result.drafts.single().reminderOffsets.size)
    }
    @Test fun `over 365 day reminder is rejected`() {
        assertTrue(parse("title,deadline,reminders\na,2026-10-20 12:00,366d").drafts.single().warnings.isNotEmpty())
    }
    @Test fun `utf8 bom is accepted`() { assertEquals(1, parse("\uFEFFtitle,deadline,reminders\na,2026-10-20 12:00,1h").drafts.size) }
    private fun parse(value: String) = parser.parse(value.toByteArray(Charsets.UTF_8), zone)
}
