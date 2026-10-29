package com.example.ddlreminder.smartadd

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class FastPathDetectorTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = LocalDateTime.of(2026, 10, 19, 9, 0).atZone(zone).toInstant().toEpochMilli()
    @Test fun `tomorrow meeting is local`() = local("明天下午3点开班会")
    @Test fun `day after homework is local`() = local("后天晚上8点交高数作业")
    @Test fun `birthday is local`() = local("10月25日小王生日")
    @Test fun `Friday deadline is local`() = local("周五23:59交论文")
    @Test fun `next Monday fitness is local`() = local("下周一上午9点体测")
    private fun local(text: String) { assertEquals(FastPath.LOCAL, FastPathDetector.detect(text)) }
    private fun ai(text: String) { assertEquals(FastPath.AI, FastPathDetector.detect(text)) }
    @Test fun `military notice is AI`() = ai(MILITARY_NOTICE)
    @Test fun `multiple events are AI`() = ai("明天下午3点开班会，后天交高数作业")
    @Test fun `cancelled is AI`() = ai("原定明天下午3点班会取消")
    @Test fun `rescheduled is AI`() = ai("考试改到下周五下午3点")
    @Test fun `group announcement is AI`() = ai("@所有人 明天下午3点开班会，请携带材料")
    @Test fun `chat record is AI`() = ai("老师：明天下午3点开班会\n小李：收到")
    @Test fun `location and relative dependency are AI`() { ai("明天下午3点开班会，地点教学楼"); ai("下周三考试然后报名") }
    @Test fun `period only and unknown text are AI`() { ai("明天上午体测"); ai("你好"); ai("明天这个报告") }
    @Test fun `single long noun is not enough to bypass structure`() { ai("明天下午3点开班会。请准备闭卷考试"); ai("10月25日生日10月26日考试") }
    @Test fun `conjunction and multiple subjects remain conservative`() { ai("明天交高数和英语作业"); ai("明天两场考试") }
    @Test fun `local fast fixtures keep correct titles and deadlines`() = runTest {
        val fixtures = listOf("明天下午3点开班会" to "班会", "后天晚上8点交高数作业" to "高数作业",
            "10月25日小王生日" to "小王生日", "周五23:59交论文" to "论文", "下周一上午9点体测" to "体测")
        for ((text, title) in fixtures) {
            val draft = LocalNaturalLanguageParser().parse(text, now, zone).drafts.single()
            assertEquals(title, draft.title); assertTrue(draft.isValid)
        }
    }
    @Test fun `local benchmark ten requests stays local and reports timing`() = runTest {
        val samples = mutableListOf<Long>()
        val client = object : AiApiClient {
            override suspend fun complete(config: com.example.ddlreminder.settings.AiSettings, systemPrompt: String, userPrompt: String): AiApiResult = error("No HTTP allowed")
        }
        repeat(10) {
            val result = TieredNaturalLanguageParser(com.example.ddlreminder.settings.AiSettings(), client).parse("明天下午3点开班会", now, zone)
            assertEquals(ParseRoute.LOCAL, result.performance!!.routeUsed)
            samples += result.performance!!.totalLatencyMs
        }
        println("LOCAL JVM benchmark: n=10 p50=${samples.sorted()[4]}ms max=${samples.max()}ms (not device/API benchmark)")
    }
    companion object {
        const val MILITARY_NOTICE = "军事理论课考试暂定于10月17日上午举行。本次考试分为A、B卷进行，考试形式为闭卷，时长为1小时。题型包括单项选择题、不定项选择题、简答题和论述题。请同学们做好准备。@所有人"
    }
}
