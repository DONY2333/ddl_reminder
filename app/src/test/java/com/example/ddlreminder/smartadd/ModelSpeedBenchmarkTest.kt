package com.example.ddlreminder.smartadd

import com.example.ddlreminder.settings.AiSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModelSpeedBenchmarkTest {
    private val config = AiSettings("https://api.example/v1", "PRIVATE_KEY", "strong/id", true, "fast/id", "strong/id")
    private val success = AiApiResult.Success("""{"events":[{"title":"test","date":"2099-10-25","exact_time":"15:00"}]}""", 200, 5, "https://api.example/v1/chat/completions")
    private fun client(block: suspend (AiSettings) -> AiApiResult) = object : AiApiClient {
        override suspend fun complete(config: AiSettings, systemPrompt: String, userPrompt: String) = block(config)
    }
    private suspend fun run(runner: ModelSpeedBenchmark, onProgress: suspend (BenchmarkState) -> Unit = {}): BenchmarkState =
        withContext(Dispatchers.Default) { withTimeout(8_000) { runner.run(config, onProgress) } }

    @Test fun `four successes use same complex text exact order and no overlapping calls`() = runTest {
        val models = mutableListOf<String>()
        val texts = mutableListOf<String>()
        var active = 0
        val api = object : AiApiClient {
            override suspend fun complete(config: AiSettings, systemPrompt: String, userPrompt: String): AiApiResult = error("must use one-shot API")
            override suspend fun completeWithoutRetry(config: AiSettings, systemPrompt: String, userPrompt: String): AiApiResult {
                active++; assertEquals(1, active)
                models += config.modelName; texts += userPrompt
                yield(); active--; return success
            }
        }
        val states = mutableListOf<BenchmarkState>()
        val result = run(ModelSpeedBenchmark(api)) { states += it }
        assertEquals(listOf("fast/id", "fast/id", "strong/id", "strong/id"), models)
        assertEquals(List(4) { ModelSpeedBenchmark.SAMPLE }, texts)
        assertTrue(result.attempts.all { it.success }); assertEquals(4, result.attempts.size); assertFalse(result.isRunning)
        assertEquals(listOf("正在测试快速模型 1/4", "正在测试快速模型 2/4", "正在测试高级模型 3/4", "正在测试高级模型 4/4"),
            states.filter { it.isRunning && it.attempts.size == it.currentIndex - 1 }.map { it.progressText })
        for (size in 1..4) assertTrue(states.any { it.attempts.size == size && it.isRunning })
    }
    @Test fun `each completion immediately publishes elapsed time and running average`() = runTest {
        var nanos = 0L
        var calls = 0
        val states = mutableListOf<BenchmarkState>()
        val result = run(ModelSpeedBenchmark(client { calls++; nanos += calls * 10_000_000L; success }, nanoTime = { nanos })) { states += it }
        assertEquals(listOf(10L, 20L, 30L, 40L), result.attempts.map { it.elapsedMs })
        assertTrue(states.first { it.attempts.size == 1 }.displayText().contains("当前平均耗时 10ms"))
        assertTrue(states.first { it.attempts.size == 2 }.displayText().contains("当前平均耗时 15ms"))
        assertTrue(result.displayText().contains("当前平均耗时 35ms"))
    }
    @Test fun `single timeout is one failed row followed by remaining requests`() = runTest {
        val calls = AtomicInteger()
        val result = run(ModelSpeedBenchmark(client { if (calls.incrementAndGet() == 2) delay(5_000); success }, requestTimeoutMs = 500))
        assertEquals(4, calls.get()); assertEquals(4, result.attempts.size)
        assertEquals("REQUEST_TIMEOUT", result.attempts[1].failure); assertFalse(result.attempts[1].success)
        assertEquals(3, result.attempts.count { it.success }); assertFalse(result.isRunning)
    }
    @Test fun `HTTP failure does not abort remaining rows or leave loading`() = runTest {
        var calls = 0
        val result = run(ModelSpeedBenchmark(client {
            calls++; if (calls == 2) AiApiResult.Failure(AiApiErrorType.SERVER, "PRIVATE_ERROR", 503, 10, "https://api.example/v1/chat/completions") else success
        }))
        assertEquals(4, calls); assertEquals(3, result.attempts.count { it.success })
        assertEquals("HTTP_ERROR", result.attempts[1].failure); assertFalse(result.isRunning)
        assertFalse(result.displayText().contains("PRIVATE_ERROR"))
    }
    @Test fun `JSON failures still complete four rows and stop loading`() = runTest {
        val result = run(ModelSpeedBenchmark(client { success.copy(body = "invalid private response") }))
        assertEquals(4, result.attempts.size); assertTrue(result.attempts.none { it.success }); assertFalse(result.isRunning)
        assertFalse(result.displayText().contains("private response"))
    }
    @Test fun `client coroutine exception is isolated to request and never exposes message`() = runTest {
        val result = run(ModelSpeedBenchmark(client { throw IllegalStateException("PRIVATE_KEY user data") }))
        assertEquals(4, result.attempts.size); assertFalse(result.isRunning)
        assertFalse(result.displayText().contains("user data")); assertTrue(result.attempts.none { it.success })
    }
    @Test fun `unexpected observer exception propagates but finally publishes not running`() = runTest {
        var latest = BenchmarkState()
        var thrown = false
        try {
            run(ModelSpeedBenchmark(client { success })) {
                latest = it
                if (it.currentIndex == 2 && !thrown) { thrown = true; throw IllegalStateException() }
            }
            fail("exception should propagate")
        } catch (_: IllegalStateException) { }
        assertTrue(thrown); assertFalse(latest.isRunning)
    }
    @Test fun `external cancellation propagates stops current and prevents remaining requests`() = runTest {
        val started = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val cancelled = CompletableDeferred<Unit>()
        var latest = BenchmarkState()
        val job = launch(Dispatchers.Default) {
            ModelSpeedBenchmark(client {
                calls.incrementAndGet(); started.complete(Unit)
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            }).run(config) { latest = it }
        }
        withContext(Dispatchers.Default) { withTimeout(5_000) { started.await(); job.cancelAndJoin(); cancelled.await() } }
        assertTrue(job.isCancelled); assertEquals(1, calls.get()); assertFalse(latest.isRunning)
    }
    @Test fun `overall timeout cancels in flight stops all subsequent requests and finishes state`() = runTest {
        var calls = 0
        val result = run(ModelSpeedBenchmark(client { calls++; awaitCancellation() }, requestTimeoutMs = 50_000, totalTimeoutMs = 100))
        assertEquals(1, calls); assertTrue(result.message!!.contains("超时")); assertFalse(result.isRunning)
    }
    @Test fun `same model IDs after trim skip all requests with explicit message`() = runTest {
        val result = withContext(Dispatchers.Default) {
            ModelSpeedBenchmark(client { error("No HTTP") }).run(config.copy(fastModel = " strong/id "))
        }
        assertEquals("两个模型配置相同", result.message); assertFalse(result.isRunning); assertTrue(result.attempts.isEmpty())
    }
    @Test fun `invalid configuration still leaves running in finally`() = runTest {
        var latest = BenchmarkState()
        try {
            withContext(Dispatchers.Default) { ModelSpeedBenchmark(client { error("No HTTP") }).run(config.copy(apiKey = "")) { latest = it } }
            fail("should validate config")
        } catch (_: IllegalArgumentException) { }
        assertFalse(latest.isRunning)
    }
    @Test fun `real HTTP benchmark never retries temporary errors and uses fifteen second socket bounds`() = runTest {
        val calls = AtomicInteger()
        val api = HttpAiApiClient(openConnection = { url -> object : HttpURLConnection(url) {
            override fun getOutputStream(): ByteArrayOutputStream {
                calls.incrementAndGet(); assertEquals(15_000, connectTimeout); assertEquals(15_000, readTimeout)
                return ByteArrayOutputStream()
            }
            override fun getResponseCode() = 503
            override fun getErrorStream() = ByteArrayInputStream("{}".toByteArray())
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy() = false
        } }, retryDelay = { error("Benchmark must not retry") })
        val result = run(ModelSpeedBenchmark(api))
        assertEquals(4, calls.get()); assertTrue(result.attempts.none { it.success }); assertFalse(result.isRunning)
    }
    @Test fun `single attempt API does not change normal parsing network retry`() = runTest {
        val calls = AtomicInteger()
        val api = HttpAiApiClient(openConnection = { url -> FailureConnection(url) { calls.incrementAndGet() } }, retryDelay = {})
        api.completeWithoutRetry(config, "system", "sample"); assertEquals(1, calls.get())
        api.complete(config, "system", "sample"); assertEquals(3, calls.get())
    }
    @Test fun `cancel actually disconnects blocked benchmark HTTP connection`() = runTest {
        val started = CountDownLatch(1)
        val released = CountDownLatch(1)
        val calls = AtomicInteger()
        val api = HttpAiApiClient(openConnection = { url -> object : HttpURLConnection(url) {
            override fun getOutputStream(): ByteArrayOutputStream {
                calls.incrementAndGet(); started.countDown(); check(released.await(5, TimeUnit.SECONDS))
                return ByteArrayOutputStream()
            }
            override fun getResponseCode() = 200
            override fun getInputStream() = ByteArrayInputStream(success.body.toByteArray())
            override fun connect() = Unit
            override fun disconnect() { released.countDown() }
            override fun usingProxy() = false
        } })
        val job = launch(Dispatchers.Default) { ModelSpeedBenchmark(api).run(config) }
        withContext(Dispatchers.IO) { assertTrue(started.await(5, TimeUnit.SECONDS)) }
        withContext(Dispatchers.Default) { withTimeout(5_000) { job.cancelAndJoin() } }
        assertEquals(0L, released.count); assertEquals(1, calls.get())
    }
}

private class FailureConnection(url: URL, private val onStart: () -> Unit) : HttpURLConnection(url) {
    override fun getOutputStream(): ByteArrayOutputStream { onStart(); return ByteArrayOutputStream() }
    override fun getResponseCode() = 503
    override fun getErrorStream() = ByteArrayInputStream("{}".toByteArray())
    override fun connect() = Unit
    override fun disconnect() = Unit
    override fun usingProxy() = false
}
