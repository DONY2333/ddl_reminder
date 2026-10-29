package com.example.ddlreminder.smartadd

import com.example.ddlreminder.settings.AiSettings
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.SocketTimeoutException

@RunWith(RobolectricTestRunner::class)
class AiCompatibilityTest {
    @Test fun `base url without slash builds chat completions endpoint`() { assertEquals("https://example.com/v1/chat/completions", HttpAiApiClient.endpoint("https://example.com/v1")) }
    @Test fun `base url with slash builds same endpoint`() { assertEquals("https://example.com/v1/chat/completions", HttpAiApiClient.endpoint("https://example.com/v1/")) }
    @Test fun `status errors are classified`() {
        assertEquals(AiApiErrorType.UNAUTHORIZED, HttpAiApiClient.errorType(401)); assertEquals(AiApiErrorType.FORBIDDEN, HttpAiApiClient.errorType(403))
        assertEquals(AiApiErrorType.NOT_FOUND, HttpAiApiClient.errorType(404)); assertEquals(AiApiErrorType.RATE_LIMITED, HttpAiApiClient.errorType(429))
        assertEquals(AiApiErrorType.SERVER, HttpAiApiClient.errorType(500))
        assertEquals(AiApiErrorType.TIMEOUT, HttpAiApiClient.errorType(SocketTimeoutException()))
        assertEquals(AiApiErrorType.NETWORK, HttpAiApiClient.errorType(java.io.IOException()))
    }
    @Test fun `markdown json is extracted`() { assertEquals("{\"events\":[]}", AiNaturalLanguageParser.extractJsonObject("```json\n{\"events\":[]}\n```")) }
    @Test fun `prefixed explanation json is extracted`() { assertEquals("{\"events\":[]}", AiNaturalLanguageParser.extractJsonObject("结果如下： {\"events\":[]} 谢谢")) }
    @Test(expected = IllegalStateException::class) fun `html body is rejected`() { AiNaturalLanguageParser.extractJsonObject("<html>bad</html>") }
    @Test fun `valid structured event parses period without inventing time`() {
        val json = """{"events":[{"title":"军事理论课考试","date":"2026-10-17","exact_time":null,"time_period":"morning","time_precision":"period","needs_time_confirmation":true,"can_create":false,"is_tentative":true,"notes":"闭卷，A/B卷，1小时，题型包括单选和简答","reminder_offsets_minutes":[],"warnings":["考试时间暂定"]}],"parse_warnings":[],"discarded_fragment_count":2}"""
        val escaped = json.replace("\\", "\\\\").replace("\"", "\\\"")
        val envelope = """{"choices":[{"message":{"content":"$escaped"}}]}"""
        val parser = AiNaturalLanguageParser(AiSettings("x","y","z"), object : AiApiClient {
            override suspend fun complete(config: AiSettings, systemPrompt: String, userPrompt: String) = AiApiResult.Success(envelope,200,1,"x")
        })
        val result = kotlinx.coroutines.runBlocking {
            parser.parse("private", LocalDateTime.of(2026,10,15,1,0).atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli(), ZoneId.of("Asia/Shanghai"))
        }
        val draft = result.drafts.single(); assertNull(draft.exactTime); assertEquals(TimePeriod.MORNING, draft.timePeriod); assertFalse(draft.canCreate)
        assertEquals(2, result.discardedFragmentCount)
    }
    @Test fun `supplementary exam details stay in notes not extra events`() {
        val notes = "闭卷，考试时长1小时，分A/B卷；题型包括单选、不定项、简答和论述。"
        val result = parseNotesResponse(org.json.JSONObject.quote(notes))
        assertEquals(1, result.drafts.size)
        assertEquals(notes, result.drafts.single().notes)
        assertEquals("军事理论课考试", result.drafts.single().title)
    }

    @Test fun `json null notes remain absent instead of literal null text`() {
        assertNull(parseNotesResponse("null").drafts.single().notes)
    }

    @Test fun `blank ai notes remain absent`() {
        assertNull(parseNotesResponse("\"   \"").drafts.single().notes)
    }

    private fun parseNotesResponse(notesJson: String): ParseResult {
        val content = """{"events":[{"title":"军事理论课考试","date":"2026-10-17","exact_time":null,"time_period":"morning","time_precision":"period","needs_time_confirmation":true,"can_create":false,"notes":$notesJson}]}"""
        val envelope = org.json.JSONObject().put("choices", org.json.JSONArray().put(
            org.json.JSONObject().put("message", org.json.JSONObject().put("content", content)),
        )).toString()
        val parser = AiNaturalLanguageParser(AiSettings("x", "y", "z"), object : AiApiClient {
            override suspend fun complete(config: AiSettings, systemPrompt: String, userPrompt: String) =
                AiApiResult.Success(envelope, 200, 1, "x")
        })
        return kotlinx.coroutines.runBlocking {
            parser.parse("军事理论课考试暂定于10月17日上午举行。闭卷，时长1小时，分A/B卷。", 0, ZoneId.of("Asia/Shanghai"))
        }
    }

    @Test fun `empty choices and null content fail safely`() {
        listOf("{\"choices\":[]}", "{\"choices\":[{\"message\":{\"content\":null}}]}").forEach { body ->
            val parser = AiNaturalLanguageParser(AiSettings("x","y","z"), object : AiApiClient {
                override suspend fun complete(config: AiSettings, systemPrompt: String, userPrompt: String) = AiApiResult.Success(body,200,1,"x")
            })
            val result = kotlinx.coroutines.runBlocking { parser.parse("private",0,ZoneId.of("UTC")) }
            assertTrue(result.drafts.isEmpty())
            assertTrue(result.aiErrorType in setOf(AiFailureStage.EMPTY_CHOICES.name, AiFailureStage.EMPTY_CONTENT.name))
        }
    }
}
