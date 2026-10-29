package com.example.ddlreminder.smartadd

import com.example.ddlreminder.settings.AiSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TieredParserTest {
    private val config = AiSettings("https://api.example.com/v1", "PRIVATE_KEY", "strong custom/id", true, "fast custom/id", "strong custom/id")
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = LocalDateTime.of(2026, 10, 15, 9, 0).atZone(zone).toInstant().toEpochMilli()
    private val text = "下周一开会，地点教学楼。"
    private val valid = """{"events":[{"title":"会议","date":"2026-10-26","exact_time":"15:00"}]}"""
    private fun success(body: String = valid) = AiApiResult.Success(body, 200, 1234, "https://api.example.com/v1/chat/completions")
    private fun failure(code: Int? = 503, type: AiApiErrorType = AiApiErrorType.SERVER, exception: String? = null) =
        AiApiResult.Failure(type, "failed", code, 100, "https://api.example.com/v1/chat/completions", exception)
    private class Client(val response: suspend (AiSettings) -> AiApiResult) : AiApiClient {
        val models = mutableListOf<String>()
        val prompts = mutableListOf<String>()
        override suspend fun complete(config: AiSettings, systemPrompt: String, userPrompt: String): AiApiResult {
            models += config.modelName; prompts += systemPrompt; return response(config)
        }
    }
    private suspend fun parse(client: AiApiClient, settings: AiSettings = config, source: String = text) =
        // HTTP/JSON 在真实 Default/IO 调度，避免 runTest 虚拟钟在工作线程调度前自动跳到总超时。
        withContext(Dispatchers.Default) { TieredNaturalLanguageParser(settings, client).parse(source, now, zone) }

    @Test fun `local hit uses zero HTTP`() = runTest {
        val client = Client { error("must not call") }
        val result = parse(client, source = "明天下午3点开班会")
        assertEquals(ParseRoute.LOCAL, result.performance!!.routeUsed); assertTrue(client.models.isEmpty())
        assertEquals("班会", result.drafts.single().title)
    }
    @Test fun `fast success never requests strong and keeps exact configured ID`() = runTest {
        val client = Client { success() }; val result = parse(client)
        assertEquals(listOf(config.fastModel), client.models); assertEquals(ParseRoute.AI, result.performance!!.routeUsed)
    }
    @Test fun `complex notice uses default Luna in one AI route without strong`() = runTest {
        val client = Client { success() }
        val result = parse(client, AiSettings(config.baseUrl, config.apiKey, privacyAccepted = true))
        assertEquals(listOf("gpt-6-luna"), client.models)
        assertEquals(ParseRoute.AI, result.performance!!.routeUsed)
        assertTrue(result.drafts.single().isValid)
        assertNull(result.performance!!.strong)
    }
    @Test fun `single model diagnostics hide all dual model concepts`() = runTest {
        val result = parse(Client { success() })
        val display = result.performance!!.diagnosticText()
        assertTrue(display.contains("解析方式：AI")); assertTrue(display.contains("AI模型：${config.fastModel}"))
        listOf("HTTP耗时", "JSON处理耗时", "总耗时", "网络重试", "失败阶段").forEach { assertTrue(display.contains(it)) }
        listOf("Strong", "Fast", "快速模型", "高级模型", "强模型兜底").forEach { assertFalse(display.contains(it)) }
    }
    @Test fun `unrecoverable JSON goes local without requesting strong`() = runTest {
        val client = Client { if (it.modelName == config.fastModel) success("not json") else success() }
        val result = parse(client)
        assertEquals(listOf(config.fastModel), client.models)
        assertEquals(ParseRoute.LOCAL_FALLBACK, result.performance!!.routeUsed)
        assertTrue(client.prompts[0].contains("Timezone: Asia/Shanghai"))
        assertFalse(result.performance!!.modelFallbackOccurred)
        assertEquals("AI解析失败，本次使用本地解析结果", result.errorMessage)
    }
    @Test fun `fence and explanatory prefix do not cause upgrade`() = runTest {
        for (content in listOf("```json\n$valid\n```", "结果如下：$valid", " \n$valid\n ")) {
            val envelope = JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("message", JSONObject().put("content", content)))).toString()
            val client = Client { success(envelope) }; assertEquals(1, parse(client).drafts.size); assertEquals(1, client.models.size)
        }
    }
    @Test fun `missing optional fields never upgrades`() = runTest {
        val client = Client { success() }; assertEquals(1, parse(client).drafts.size); assertEquals(1, client.models.size)
    }
    @Test fun `partial useful events never upgrades`() = runTest {
        val client = Client { success("""{"events":[{"title":"有效","date":"2026-10-26"},{"title":"坏","date":"invalid"}]}""") }
        val result = parse(client); assertEquals(1, result.drafts.size); assertEquals(1, result.invalidEventCount); assertEquals(1, client.models.size)
    }
    @Test fun `period requiring confirmation is useful not a reason to upgrade`() = runTest {
        val client = Client { success("""{"events":[{"title":"会议","date":"2026-10-26","time_period":"morning","time_precision":"period"}]}""") }
        assertTrue(parse(client).drafts.single().needsTimeConfirmation); assertEquals(1, client.models.size)
    }
    @Test fun `empty events with schedule clue uses local fallback`() = runTest {
        val client = Client { if (it.modelName == config.fastModel) success("{\"events\":[]}") else success() }
        assertTrue(parse(client).fellBackToLocal); assertEquals(1, client.models.size)
    }
    @Test fun `empty non schedule text does not upgrade`() = runTest {
        val client = Client { success("{\"events\":[]}") }; parse(client, source = "你好，谢谢"); assertEquals(1, client.models.size)
    }
    @Test fun `missing key date uses local fallback`() = runTest {
        val client = Client { if (it.modelName == config.fastModel) success("""{"events":[{"title":"会议"}]}""") else success() }
        assertTrue(parse(client).fellBackToLocal); assertEquals(1, client.models.size)
    }
    @Test fun `401 and 403 never request strong`() = runTest {
        for (code in listOf(401, 403)) {
            val client = Client { failure(code, HttpAiApiClient.errorType(code)) }
            assertTrue(parse(client).fellBackToLocal); assertEquals(1, client.models.size)
        }
    }
    @Test fun `invalid URL and build errors never request strong`() = runTest {
        for (type in listOf(AiApiErrorType.URL_INVALID, AiApiErrorType.REQUEST_BUILD_FAILED)) {
            val client = Client { failure(null, type) }; parse(client); assertEquals(1, client.models.size)
        }
    }
    @Test fun `DNS connect TLS permission errors never request strong`() = runTest {
        for (exception in listOf("UnknownHostException", "ConnectException", "SSLHandshakeException", "SSLException", "SecurityException")) {
            val client = Client { failure(null, AiApiErrorType.NETWORK, exception) }; parse(client); assertEquals(1, client.models.size)
        }
    }
    @Test fun `model not found never requests different strong ID`() = runTest {
        val client = Client { if (it.modelName == config.fastModel) failure(404, AiApiErrorType.NOT_FOUND).copy(modelUnavailable = true) else success() }
        assertTrue(parse(client).fellBackToLocal); assertEquals(1, client.models.size)
    }
    @Test fun `unqualified 404 may be bad endpoint and must not blindly upgrade`() = runTest {
        val client = Client { failure(404, AiApiErrorType.NOT_FOUND) }; parse(client); assertEquals(1, client.models.size)
    }
    @Test fun `model unavailable goes local without leaking message`() = runTest {
        val response = """{"error":{"code":"model_unavailable","message":"PRIVATE_USER_DATA"}}"""
        assertTrue(HttpAiApiClient.isModelUnavailable(response))
        val client = Client { if (it.modelName == config.fastModel) failure(400, AiApiErrorType.UNKNOWN).copy(modelUnavailable = true) else success() }
        val result = parse(client)
        assertEquals(1, client.models.size); assertTrue(result.performance!!.diagnosticText().contains("不可用"))
        assertFalse(result.performance!!.diagnosticText().contains("PRIVATE_USER_DATA"))
    }
    @Test fun `generic parameter error is not model unavailability`() {
        assertFalse(HttpAiApiClient.isModelUnavailable("""{"error":{"message":"unsupported max_tokens"}}"""))
        assertFalse(HttpAiApiClient.isModelUnavailable("not json"))
    }
    @Test fun `invalid explicit time cannot silently become local date only success`() = runTest {
        val client = Client { success() }; parse(client, source = "明天25点交作业"); assertEquals(1, client.models.size)
    }
    @Test fun `afternoon colon time keeps afternoon not morning`() = runTest {
        val client = Client { error("No HTTP") }; val result = parse(client, source = "明天下午3:30交作业")
        assertEquals(15, result.drafts.single().exactTime!!.hour); assertEquals(ParseRoute.LOCAL, result.performance!!.routeUsed)
    }
    @Test fun `output token compatibility parameter omitted by default and optional when verified`() = runTest {
        for (limit in listOf(null, 1000)) {
            val sent = ByteArrayOutputStream()
            val client = HttpAiApiClient(openConnection = { url -> object : HttpURLConnection(url) {
                override fun getOutputStream() = sent
                override fun getResponseCode() = 200
                override fun getInputStream() = ByteArrayInputStream(valid.toByteArray())
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
            } }, maxOutputTokens = limit)
            client.complete(config, "system", "sample")
            val payload = JSONObject(sent.toString("UTF-8"))
            if (limit == null) assertFalse(payload.has("max_tokens")) else assertEquals(1000, payload.getInt("max_tokens"))
            assertFalse(payload.has("max_completion_tokens")); assertEquals(0, payload.getInt("temperature"))
        }
    }
    @Test fun `debug benchmark uses only fixed sample four serial calls and exact model IDs`() = runTest {
        val client = Client { success() }
        val result = withContext(Dispatchers.Default) { ModelSpeedBenchmark(client).run(config) }
        assertEquals(4, client.models.size)
        assertEquals(List(2) { config.fastModel } + List(2) { config.strongModel }, client.models)
        assertTrue(result.displayText().contains("成功 2/2")); assertTrue(result.displayText().contains("平均"))
        assertFalse(result.displayText().contains(config.apiKey)); assertFalse(result.displayText().contains("军事理论课考试暂定"))
    }
    @Test fun `same model does not repeat identical failed request`() = runTest {
        val client = Client { success("invalid") }
        assertTrue(parse(client, config.copy(strongModel = config.fastModel)).fellBackToLocal); assertEquals(1, client.models.size)
    }
    @Test fun `single model failure goes local once without another model cycle`() = runTest {
        val client = Client { success("invalid") }; val result = parse(client)
        assertTrue(result.fellBackToLocal); assertEquals(ParseRoute.LOCAL_FALLBACK, result.performance!!.routeUsed); assertEquals(1, client.models.size)
    }
    @Test fun `missing fast model uses legacy model name as single model`() = runTest {
        val client = Client { success() }; val result = parse(client, config.copy(fastModel = ""))
        assertEquals(listOf(config.modelName), client.models); assertEquals(ParseRoute.AI, result.performance!!.routeUsed)
    }
    @Test fun `no consent means zero HTTP even complex text`() = runTest {
        val client = Client { error("No consent") }; parse(client, config.copy(privacyAccepted = false)); assertTrue(client.models.isEmpty())
    }
    @Test fun `timeout stops chain and keeps explicit failure`() = runTest {
        val client = Client { delay(100); success() }
        val result = withContext(Dispatchers.Default) { TieredNaturalLanguageParser(config, client, timeoutMillis = 1).parse(text, now, zone) }
        assertEquals("TOTAL_TIMEOUT", result.aiErrorType); assertTrue(result.performance!!.timedOut)
        assertTrue(result.fellBackToLocal); assertEquals(ParseRoute.LOCAL_FALLBACK, result.performance!!.routeUsed)
        assertTrue(client.models.size <= 1)
    }
    @Test(expected = CancellationException::class) fun `external cancellation is never fallback`() = runTest {
        parse(Client { throw CancellationException() })
    }
    @Test fun `military notice stays one event with complete notes using fast`() = runTest {
        val military = """{"events":[{"title":"军事理论课考试","date":"2026-10-17","time_period":"morning","time_precision":"period","is_tentative":true,"notes":"A/B卷，闭卷，1小时，题型：单选、不定项、简答、论述"}]}"""
        val client = Client { success(military) }; val result = parse(client, source = FastPathDetectorTest.MILITARY_NOTICE)
        assertEquals(listOf(config.fastModel), client.models); val draft = result.drafts.single()
        assertEquals("军事理论课考试", draft.title); assertEquals("2026-10-17", draft.date.toString())
        assertEquals(TimePeriod.MORNING, draft.timePeriod); assertTrue(draft.needsTimeConfirmation)
        listOf("A/B卷", "闭卷", "1小时", "题型").forEach { assertTrue(draft.notes!!.contains(it)) }
    }
    @Test fun `diagnostics separate network and local JSON with no user data`() = runTest {
        val result = parse(Client { success() }); val perf = result.performance!!
        assertEquals(1234L, perf.fast!!.latencyMs); assertNotNull(perf.fast!!.parseLatencyMs)
        assertEquals(1, perf.eventCount); assertEquals(0, perf.networkRetryCount)
        val diagnostic = perf.diagnosticText() + perf.eventMetadata()
        listOf("PRIVATE_KEY", text, "会议", "Authorization").forEach { assertFalse(diagnostic.contains(it)) }
    }
    @Test fun `production prompt is shorter with preserved rules and no client implementation details`() {
        assertTrue(ScheduleExtractionPrompt.text.length < 900)
        listOf("notes", "取消", "改期", "暂定", "23:59", "needs_time_confirmation", "parse_warnings").forEach { assertTrue(ScheduleExtractionPrompt.text.contains(it)) }
        listOf("Room", "EventLogger", "Android", "AlarmManager", "Migration", "reasoning", "originalText").forEach { assertFalse(ScheduleExtractionPrompt.text.contains(it)) }
    }
    @Test fun `429 retries same model once then local`() = retriesThenLocal(429)
    @Test fun `503 retries same model once then local`() = retriesThenLocal(503)
    @Test fun `real HTTP 401 neither retries nor requests strong`() = httpRoute(401, listOf(config.fastModel), 0)
    @Test fun `real HTTP 404 goes local without retry or second model`() = httpRoute(404, listOf(config.fastModel), 0)
    @Test fun `timeout retries same model once then local`() = httpRoute(-1, listOf(config.fastModel, config.fastModel), 1)
    private fun retriesThenLocal(code: Int) = httpRoute(code, listOf(config.fastModel, config.fastModel), 1)
    private fun httpRoute(code: Int, expected: List<String>, retries: Int) = runTest {
        val calls = mutableListOf<String>()
        val client = HttpAiApiClient(openConnection = { url -> object : HttpURLConnection(url) {
            private val output = ByteArrayOutputStream()
            override fun getOutputStream() = output
            override fun getResponseCode(): Int {
                val model = JSONObject(output.toString("UTF-8")).getString("model"); calls += model
                if (code == -1 && model == config.fastModel) throw java.net.SocketTimeoutException()
                return if (model == config.fastModel) code else 200
            }
            override fun getInputStream() = ByteArrayInputStream((if (code == 404 && JSONObject(output.toString("UTF-8")).getString("model") == config.fastModel)
                """{"error":{"code":"model_not_found"}}""" else valid).toByteArray())
            override fun getErrorStream() = inputStream
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy() = false
        } }, retryDelay = {})
        val result = parse(client)
        assertEquals(expected, calls)
        assertEquals(retries, result.performance!!.networkRetryCount)
        assertTrue(result.fellBackToLocal)
        assertNull(result.performance!!.strong)
    }
}
