package com.example.ddlreminder.smartadd

import com.example.ddlreminder.settings.AiSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.ConnectException
import java.net.URL
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiPipelineStabilityTest {
    private val settings = AiSettings("https://api.example.com/v1", "SECRET_KEY", "model")
    private fun event(title: String = "event") = JSONObject().put("title", title).put("date", "2026-11-20").put("exact_time", "18:00")
    private fun root(vararg events: JSONObject) = JSONObject().put("events", JSONArray(events.toList())).toString()
    private fun envelope(content: Any?) = JSONObject().put("choices", JSONArray().put(JSONObject().put("message",
        JSONObject().put("content", content ?: JSONObject.NULL)))).toString()
    private fun parser(response: String) = AiNaturalLanguageParser(settings, object : AiApiClient {
        override suspend fun complete(config: AiSettings, systemPrompt: String, userPrompt: String) =
            AiApiResult.Success(response, 200, 1380, "https://api.example.com/v1/chat/completions")
    })
    private suspend fun parseContent(content: String) = parser(envelope(content)).parse("PRIVATE_INPUT", 0, ZoneId.of("UTC"))

    @Test fun `200 perfect json separates transport and parse success`() = runTest {
        val result = parseContent(root(event()))
        assertEquals(1, result.drafts.size)
        assertEquals(true, result.diagnostic!!.transportSuccess)
        assertEquals(true, result.diagnostic!!.parseSuccess)
        assertEquals(200, result.diagnostic!!.statusCode)
        assertEquals(1380L, result.diagnostic!!.latencyMs)
    }
    @Test fun `200 code fenced json works`() = runTest { assertEquals(1, parseContent("```json\n${root(event())}\n```").drafts.size) }
    @Test fun `200 prefixed json works`() = runTest { assertEquals(1, parseContent("结果如下：\n${root(event())}\n结束").drafts.size) }
    @Test fun `200 whitespace works`() = runTest { assertEquals(1, parseContent(" \n ${root(event())}\t ").drafts.size) }
    @Test fun `string braces escaped quotes backslashes and nested arrays do not break extraction`() = runTest {
        val notes = "说明 { 或 }、[嵌套]、\"引号\" 和反斜杠\\"
        val result = parseContent("结果如下：${root(event().put("notes", notes).put("warnings", JSONArray().put("{提示}")))}")
        assertEquals(notes, result.drafts.single().notes)
    }
    @Test fun `empty choices has explicit stage`() = runTest {
        val result = parser("{\"choices\":[]}").parse("private", 0, ZoneId.of("UTC"))
        assertEquals(AiFailureStage.EMPTY_CHOICES, result.diagnostic!!.failureStage)
        assertEquals(true, result.diagnostic!!.transportSuccess)
        assertEquals(false, result.diagnostic!!.parseSuccess)
    }
    @Test fun `null content has explicit stage`() = runTest {
        assertEquals(AiFailureStage.EMPTY_CONTENT, parser(envelope(null)).parse("private", 0, ZoneId.of("UTC")).diagnostic!!.failureStage)
    }
    @Test fun `empty body has explicit stage`() = runTest {
        assertEquals(AiFailureStage.EMPTY_RESPONSE, parser("").parse("private", 0, ZoneId.of("UTC")).diagnostic!!.failureStage)
    }
    @Test fun `200 non json content is extraction failure not network`() = runTest {
        assertEquals(AiFailureStage.JSON_EXTRACTION_FAILED, parseContent("不是 JSON").diagnostic!!.failureStage)
    }
    @Test fun `200 malformed json is parse failure with successful transport`() = runTest {
        val result = parseContent("{\"events\": [], \"broken\": }")
        assertEquals(AiFailureStage.JSON_PARSE_FAILED, result.diagnostic!!.failureStage)
        assertEquals(true, result.diagnostic!!.transportSuccess)
        assertEquals(false, result.diagnostic!!.parseSuccess)
    }
    @Test fun `one bad time does not destroy two good events`() = runTest {
        val result = parseContent(root(event("A"), event("B").put("exact_time", "25:99"), event("C")))
        assertEquals(listOf("A", "C"), result.drafts.map { it.title })
        assertEquals(1, result.invalidEventCount)
        assertEquals(AiFailureStage.TIME_PARSE_FAILED, result.diagnostic!!.failureStage)
        assertNull(result.aiErrorType)
        assertTrue(result.parseWarnings.isNotEmpty())
    }
    @Test fun `optional fields missing use safe defaults`() = runTest {
        val result = parseContent(root(event()))
        val draft = result.drafts.single()
        assertNull(draft.notes); assertFalse(draft.isTentative)
        assertTrue(draft.reminderOffsets.isEmpty()); assertTrue(draft.warnings.isEmpty())
        assertTrue(result.parseWarnings.isEmpty()); assertEquals(0, result.discardedFragmentCount)
    }
    @Test fun `missing title only invalidates its event`() = runTest {
        val result = parseContent(root(event().apply { remove("title") }, event("good")))
        assertEquals("good", result.drafts.single().title)
        assertEquals(1, result.invalidEventCount)
    }
    @Test fun `invalid date is classified and batch stays safe`() = runTest {
        val result = parseContent(root(event().put("date", "2026-02-31")))
        assertEquals(AiFailureStage.DATE_PARSE_FAILED, result.diagnostic!!.failureStage)
        assertTrue(result.drafts.isEmpty())
    }
    @Test fun `missing events schema is classified`() = runTest {
        assertEquals(AiFailureStage.SCHEMA_VALIDATION_FAILED, parseContent("{\"other\":[]}").diagnostic!!.failureStage)
    }
    @Test fun `bad optional offset is ignored without crashing event or batch`() = runTest {
        val result = parseContent(root(event().put("reminder_offsets_minutes", JSONArray().put(-5).put(60))))
        assertEquals(listOf(60L), result.drafts.single().reminderOffsets.map { it.minutes })
        assertTrue(result.drafts.single().warnings.isNotEmpty())
    }
    @Test fun `empty events is a valid parse not falsely marked AI failure`() = runTest {
        val result = parseContent("{\"events\":[]}")
        assertNull(result.aiErrorType)
        assertEquals(true, result.diagnostic!!.parseSuccess)
    }
    @Test fun `diagnostics never contain user content or credentials`() = runTest {
        val result = parseContent(root(event("PRIVATE_TITLE").put("notes", "PRIVATE_NOTES")))
        val diagnostic = result.diagnostic!!.diagnosticText()
        listOf("PRIVATE_INPUT", "PRIVATE_TITLE", "PRIVATE_NOTES", "SECRET_KEY", "Authorization").forEach { assertFalse(diagnostic.contains(it)) }
    }
    @Test fun `twenty consecutive simulated parses accept supported formatting`() = runTest {
        repeat(20) { index ->
            val json = root(event())
            val content = when (index % 4) { 0 -> json; 1 -> "```json\n$json\n```"; 2 -> "结果如下：$json"; else -> " \n$json\n " }
            val result = parseContent(content)
            assertEquals(true, result.diagnostic!!.parseSuccess)
            assertEquals(1, result.drafts.size)
        }
    }
    @Test fun `excessive nesting is rejected without stack overflow`() = runTest {
        val nested = "{\"events\":" + "[".repeat(1000) + "0" + "]".repeat(1000) + "}"
        assertEquals(false, parseContent(nested).diagnostic!!.parseSuccess)
    }
    @Test fun `cancelling blocked HTTP attempts disconnects underlying connection`() = runTest {
        val started = java.util.concurrent.CountDownLatch(1)
        val released = java.util.concurrent.CountDownLatch(1)
        val disconnected = java.util.concurrent.atomic.AtomicBoolean()
        val client = HttpAiApiClient(openConnection = { url -> object : HttpURLConnection(url) {
            override fun getOutputStream(): ByteArrayOutputStream {
                started.countDown()
                check(released.await(5, java.util.concurrent.TimeUnit.SECONDS))
                return ByteArrayOutputStream()
            }
            override fun getResponseCode() = 200
            override fun getInputStream() = ByteArrayInputStream("{}".toByteArray())
            override fun connect() = Unit
            override fun disconnect() { disconnected.set(true); released.countDown() }
            override fun usingProxy() = false
        } }, retryDelay = {})
        val job = launch(Dispatchers.Default) { client.complete(settings, "s", "u") }
        withContext(Dispatchers.IO) { assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
        withContext(Dispatchers.Default) { withTimeout(5_000) { job.cancelAndJoin() } }
        assertTrue(disconnected.get())
    }

    @Test fun `429 retries once`() = retryStatus(429)
    @Test fun `503 retries once`() = retryStatus(503)
    @Test fun `500 502 and 504 retry only once`() = runTest { listOf(500, 502, 504).forEach { verifyStatusRetry(it) } }
    @Test fun `401 never retries`() = noRetryStatus(401)
    @Test fun `403 and 404 never retry`() = runTest { listOf(403, 404).forEach { verifyNoRetry(it) } }
    @Test fun `timeout retries once`() = retryException(SocketTimeoutException())
    @Test fun `connection failure retries once`() = retryException(ConnectException())
    @Test fun `repeated temporary failure stops at two attempts`() = runTest {
        var calls = 0
        val client = HttpAiApiClient(openConnection = { calls++; PipelineConnection(it, 503, "{}") }, retryDelay = {})
        val result = client.complete(settings, "s", "u") as AiApiResult.Failure
        assertEquals(2, calls); assertEquals(2, result.attemptCount)
    }
    @Test fun `JSON or schema errors are never retried`() = runTest {
        listOf("not json", "{\"wrong\":[]}").forEach { content ->
            var calls = 0
            val client = HttpAiApiClient(openConnection = { calls++; PipelineConnection(it, 200, envelope(content)) }, retryDelay = {})
            val result = AiNaturalLanguageParser(settings, client).parse("private", 0, ZoneId.of("UTC"))
            assertFalse(result.diagnostic!!.parseSuccess!!); assertEquals(1, calls)
        }
    }
    @Test(expected = CancellationException::class) fun `parser cancellation is not turned into parse failure`() = runTest {
        val client = object : AiApiClient {
            override suspend fun complete(config: AiSettings, systemPrompt: String, userPrompt: String): AiApiResult = throw CancellationException()
        }
        AiNaturalLanguageParser(settings, client).parse("private", 0, ZoneId.of("UTC"))
    }
    @Test(expected = CancellationException::class) fun `cancellation during retry delay does not retry`() = runTest {
        val client = HttpAiApiClient(openConnection = { PipelineConnection(it, 503, "{}") }, retryDelay = { throw CancellationException() })
        client.complete(settings, "s", "u")
    }

    private fun retryStatus(code: Int) = runTest { verifyStatusRetry(code) }
    private suspend fun verifyStatusRetry(code: Int) {
        var calls = 0
        val client = HttpAiApiClient(openConnection = { calls++; PipelineConnection(it, if (calls == 1) code else 200, "{}") }, retryDelay = {})
        val result = client.complete(settings, "s", "u") as AiApiResult.Success
        assertEquals(2, calls); assertEquals(2, result.attemptCount)
    }
    private fun noRetryStatus(code: Int) = runTest { verifyNoRetry(code) }
    private suspend fun verifyNoRetry(code: Int) {
        var calls = 0
        val client = HttpAiApiClient(openConnection = { calls++; PipelineConnection(it, code, "{}") }, retryDelay = {})
        client.complete(settings, "s", "u"); assertEquals(1, calls)
    }
    private fun retryException(error: Exception) = runTest {
        var calls = 0
        val client = HttpAiApiClient(openConnection = { calls++
            PipelineConnection(it, 200, "{}", if (calls == 1) error else null)
        }, retryDelay = {})
        assertTrue(client.complete(settings, "s", "u") is AiApiResult.Success)
        assertEquals(2, calls)
    }
}

private class PipelineConnection(url: URL, private val status: Int, private val response: String, private val error: Exception? = null) : HttpURLConnection(url) {
    override fun getOutputStream(): ByteArrayOutputStream { error?.let { throw it }; return ByteArrayOutputStream() }
    override fun getResponseCode() = status
    override fun getInputStream() = ByteArrayInputStream(response.toByteArray())
    override fun getErrorStream() = inputStream
    override fun connect() = Unit
    override fun disconnect() = Unit
    override fun usingProxy() = false
}
