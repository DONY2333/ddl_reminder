package com.example.ddlreminder.smartadd

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class V121ParserRegressionTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = LocalDateTime.of(2026, 10, 15, 13, 19).atZone(zone).toInstant().toEpochMilli()
    private val parser = LocalNaturalLanguageParser()

    @Test fun `military theory notice becomes one tentative event with notes`() = runTest {
        val text = "军事理论课考试暂定于10月17日上午举行。本次考试分为A、B卷进行，考试形式为闭卷，时长为1小时。题型包括单项选择题、不定项选择题、简答题和论述题。请同学们做好准备。@所有人"
        val result = parser.parse(text, now, zone)
        assertEquals(1, result.drafts.size)
        val draft = result.drafts.single()
        assertEquals("军事理论课考试", draft.title)
        assertEquals("2026-10-17", draft.date.toString())
        assertEquals(TimePeriod.MORNING, draft.timePeriod)
        assertNull(draft.exactTime)
        assertTrue(draft.needsTimeConfirmation); assertFalse(draft.canCreate); assertTrue(draft.isTentative)
        val notes = draft.notes.orEmpty()
        listOf("A/B卷", "闭卷", "1小时", "单项选择题", "不定项选择题", "简答题", "论述题").forEach { assertTrue("missing $it", notes.contains(it)) }
        assertFalse(result.drafts.any { "请同学们" in it.title || "@所有人" in it.title })
    }

    @Test fun `meeting location remains one event`() = runTest {
        val result = parser.parse("明天下午3点开班会，地点逸夫楼，请提前10分钟到。", now, zone)
        assertEquals(1, result.drafts.size); assertEquals(15, result.drafts.single().exactTime?.hour)
    }
    @Test fun `two weekday events remain two`() = runTest { assertEquals(2, parser.parse("周三交高数作业，周五晚上8点英语pre。", now, zone).drafts.size) }
    @Test fun `mention only creates zero events`() = runTest { assertEquals(0, parser.parse("@所有人 请大家注意查收。", now, zone).drafts.size) }
    @Test fun `unknown exam time creates zero creatable events`() = runTest { assertEquals(0, parser.parse("考试时间另行通知。", now, zone).drafts.count { it.canCreate }) }
    @Test fun `same date morning fitness and afternoon meeting creates two`() = runTest { assertEquals(2, parser.parse("10月20日上午体测，下午3点班会。", now, zone).drafts.size) }
    @Test fun `cancelled exam creates zero events`() = runTest { assertEquals(0, parser.parse("原定周三考试取消。", now, zone).drafts.size) }
    @Test fun `rescheduled exam uses friday three and warning`() = runTest {
        val draft = parser.parse("考试改到周五下午3点。", now, zone).drafts.single()
        assertEquals(15, draft.exactTime?.hour); assertTrue(draft.warnings.any { "改期" in it })
    }

    @Test fun `twenty four realistic fixtures keep conservative event counts`() = runTest {
        val fixtures = listOf(
            "明天下午3点开会" to 1, "后天晚上8点交作业" to 1, "10月20日23:59交报告" to 1,
            "周三交作业，周五下午3点开班会" to 2, "请大家注意" to 0, "收到请回复" to 0,
            "10月25日小王生日" to 1, "下周一上午9点参加竞赛" to 1, "报名时间另行通知" to 0,
            "本周五下午2点参加志愿活动，地点体育馆" to 1, "原定明天班会取消" to 0,
            "答辩暂定于10月28日下午" to 1, "月底交课程论文" to 1, "@所有人" to 0,
            "明天上午体测" to 1, "10月30日晚上7点做项目汇报" to 1,
            "下周三下午3点考试，闭卷，携带学生证" to 1, "请同学们做好准备" to 0,
            "周四上午10点组会。会议地点实验楼。" to 1, "考试采用闭卷形式，时长两小时" to 0,
            "10月22日报名竞赛" to 1, "明天下午4点班会，后天上午9点考试" to 2,
            "活动延期到周六下午2点" to 1, "课程安排请查看教务系统" to 0,
        )
        fixtures.forEach { (text, expected) -> assertEquals(text, expected, parser.parse(text, now, zone).drafts.size) }
    }
}
