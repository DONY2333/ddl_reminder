package com.example.ddlreminder.smartadd

import com.example.ddlreminder.settings.AiSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import com.example.ddlreminder.diagnostics.recoverable
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import kotlin.coroutines.coroutineContext

// 粗分类供 fallback 使用；UI 同时展示具体异常类，不展示可能携带敏感数据的异常 message。
enum class AiApiErrorType { UNAUTHORIZED, FORBIDDEN, NOT_FOUND, RATE_LIMITED, SERVER, TIMEOUT, NETWORK, INVALID_CONTENT, UNKNOWN, URL_INVALID, REQUEST_BUILD_FAILED }

sealed interface AiApiResult {
    data class Success(val body: String, val statusCode: Int, val latencyMs: Long, val endpoint: String, val attemptCount: Int = 1) : AiApiResult
    data class Failure(
        val type: AiApiErrorType,
        val message: String,
        val statusCode: Int? = null,
        val latencyMs: Long? = null,
        val endpoint: String,
        val exceptionClassName: String? = null,
        val attemptCount: Int = 1,
        val modelUnavailable: Boolean = false,
    ) : AiApiResult
}

interface AiApiClient {
    suspend fun complete(config: AiSettings, systemPrompt: String, userPrompt: String): AiApiResult
    /** Benchmark 单次尝试契约；真实 HTTP 实现必须绕过 retry。默认供无重试的测试替身使用。 */
    suspend fun completeWithoutRetry(config: AiSettings, systemPrompt: String, userPrompt: String): AiApiResult =
        complete(config, systemPrompt, userPrompt)
}

class HttpAiApiClient(
    // 注入连接与单调时钟，使异常与计时测试不依赖真实外网。
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val nanoTime: () -> Long = System::nanoTime,
    private val retryDelay: suspend () -> Unit = { delay(350) },
    // 未验证中转兼容性时不发送；仅已验证支持 max_tokens 的集成方可显式开启。
    private val maxOutputTokens: Int? = null,
) : AiApiClient {
    override suspend fun completeWithoutRetry(config: AiSettings, systemPrompt: String, userPrompt: String): AiApiResult =
        requestOnce(config, systemPrompt, userPrompt, connectTimeoutMs = 15_000, readTimeoutMs = 15_000)

    override suspend fun complete(config: AiSettings, systemPrompt: String, userPrompt: String): AiApiResult {
        val started = nanoTime()
        var result = requestOnce(config, systemPrompt, userPrompt)
        var attempts = 1
        if (result is AiApiResult.Failure && shouldRetry(result)) {
            retryDelay()
            coroutineContext.ensureActive()
            attempts++
            result = requestOnce(config, systemPrompt, userPrompt)
        }
        coroutineContext.ensureActive()
        val latency = ((nanoTime() - started) / 1_000_000).coerceAtLeast(0)
        return when (result) {
            is AiApiResult.Success -> result.copy(latencyMs = latency.coerceAtLeast(1), attemptCount = attempts)
            is AiApiResult.Failure -> result.copy(latencyMs = maxOf(latency, result.latencyMs ?: 0), attemptCount = attempts)
        }
    }

    private suspend fun requestOnce(config: AiSettings, systemPrompt: String, userPrompt: String,
        connectTimeoutMs: Int = 10_000, readTimeoutMs: Int = 20_000): AiApiResult =
        withContext(Dispatchers.IO) {
            val started = nanoTime()
            fun elapsed() = ((nanoTime() - started) / 1_000_000).coerceAtLeast(0)
            val cleaned = config.normalized()
            val url = try {
                validatedEndpoint(cleaned.baseUrl)
            } catch (error: Exception) {
                return@withContext AiApiResult.Failure(
                    AiApiErrorType.URL_INVALID, "URL_INVALID：请填写完整的 http/https Base URL（包含主机名）",
                    latencyMs = elapsed(), endpoint = "（URL 无效，未发起请求）",
                    exceptionClassName = exceptionName(error),
                )
            }
            // 诊断不显示 Key；无效 URL 不回显原文，避免误粘贴的凭证泄露。
            val diagnosticEndpoint = url.toExternalForm().let {
                if (cleaned.apiKey.isNotEmpty()) it.replace(cleaned.apiKey, "[REDACTED]") else it
            }
            var connection: HttpURLConnection? = null
            var cancellationWatcher: Job? = null
            var requestStarted = false
            var receivedStatus: Int? = null
            try {
                require(cleaned.apiKey.isNotBlank() && cleaned.modelName.isNotBlank())
                val payload = JSONObject().put("model", cleaned.modelName).put("temperature", 0)
                    .put("messages", JSONArray()
                        .put(JSONObject().put("role", "system").put("content", systemPrompt))
                        .put(JSONObject().put("role", "user").put("content", userPrompt)))
                maxOutputTokens?.let { require(it in 1..1200); payload.put("max_tokens", it) }
                val body = payload.toString()
                val active = openConnection(url)
                connection = active
                cancellationWatcher = launch(start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() }
                    finally { recoverable { active.disconnect() } }
                }
                active.requestMethod = "POST"
                active.connectTimeout = connectTimeoutMs
                active.readTimeout = readTimeoutMs
                // 不自动跟随重定向，避免 Authorization 转发给其他主机；Endpoint 即实际请求地址。
                active.instanceFollowRedirects = false
                active.doOutput = true
                active.setRequestProperty("Authorization", "Bearer ${cleaned.apiKey}")
                active.setRequestProperty("Content-Type", "application/json")
                coroutineContext.ensureActive()
                requestStarted = true // 获取 outputStream 才真正尝试 DNS/TCP/TLS 连接。
                active.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val status = active.responseCode
                receivedStatus = status
                val response = (if (status in 200..299) active.inputStream else active.errorStream)
                    ?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                        val output = StringBuilder()
                        val chunk = CharArray(8192)
                        while (true) {
                            coroutineContext.ensureActive()
                            val size = reader.read(chunk)
                            if (size < 0) break
                            if (output.length + size > AiNaturalLanguageParser.MAX_RESPONSE_CHARS) throw java.io.IOException("Response size limit")
                            output.append(chunk, 0, size)
                        }
                        output.toString()
                    }.orEmpty()
                if (status in 200..299) AiApiResult.Success(response, status, elapsed().coerceAtLeast(1), diagnosticEndpoint)
                else AiApiResult.Failure(errorType(status), errorMessage(status), status, elapsed().coerceAtLeast(1), diagnosticEndpoint,
                    modelUnavailable = isModelUnavailable(response))
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                val type = if (requestStarted) errorType(error) else AiApiErrorType.REQUEST_BUILD_FAILED
                val name = exceptionName(error)
                AiApiResult.Failure(type, "${if (requestStarted) "AI 请求失败" else "REQUEST_BUILD_FAILED"}：$name",
                    receivedStatus, elapsed().coerceAtLeast(if (requestStarted) 1 else 0), diagnosticEndpoint, name)
            } finally {
                // 清理异常不能覆盖原始诊断。
                cancellationWatcher?.cancel()
                recoverable { connection?.disconnect() }
            }
        }

    companion object {
        /** 仅返回错误类别布尔，不保存/回显中转的 message（其中可能包含敏感内容）。 */
        fun isModelUnavailable(response: String): Boolean = try {
            val root = JSONObject(AiNaturalLanguageParser.extractFirstCompleteJsonObject(response))
            val error = root.optJSONObject("error") ?: root
            val code = error.optString("code").lowercase()
            val type = error.optString("type").lowercase()
            val message = error.optString("message").take(512).lowercase()
            code in setOf("model_not_found", "model_unavailable", "model_not_available") ||
                type in setOf("model_not_found", "model_unavailable", "model_not_available") ||
                Regex("model.{0,80}(?:not found|unavailable|does not exist)").containsMatchIn(message)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            false
        }

        fun shouldRetry(result: AiApiResult.Failure): Boolean =
            result.statusCode in setOf(429, 500, 502, 503, 504) ||
                (result.type in setOf(AiApiErrorType.NETWORK, AiApiErrorType.TIMEOUT) &&
                    result.exceptionClassName in setOf("SocketTimeoutException", "ConnectException"))
        fun endpoint(baseUrl: String): String = AiSettings.cleanBaseUrl(baseUrl).trimEnd('/') + "/chat/completions"

        fun validatedEndpoint(baseUrl: String): URL {
            val uri = URI(endpoint(baseUrl))
            require(uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank())
            // Base URL 不应携带凭证、查询参数或 fragment；拒绝而非悄悄改写内部内容。
            require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
            require(uri.port in -1..65535)
            return uri.toURL()
        }

        fun exceptionName(error: Throwable): String = error.javaClass.simpleName.ifEmpty { "UnknownException" }

        fun errorType(status: Int): AiApiErrorType = when (status) {
            401 -> AiApiErrorType.UNAUTHORIZED; 403 -> AiApiErrorType.FORBIDDEN
            404 -> AiApiErrorType.NOT_FOUND; 429 -> AiApiErrorType.RATE_LIMITED
            in 500..599 -> AiApiErrorType.SERVER; else -> AiApiErrorType.UNKNOWN
        }
        fun errorMessage(status: Int): String = when (status) {
            401 -> "AI API Key 可能无效"; 403 -> "当前 Key 可能没有访问权限"
            404 -> "请检查 API 地址或模型"; 429 -> "频率限制、余额或中转额度问题"
            in 500..599 -> "中转服务异常"; else -> "AI 服务返回 HTTP $status"
        }
        fun errorType(error: Throwable): AiApiErrorType = when (error) {
            is SocketTimeoutException -> AiApiErrorType.TIMEOUT
            else -> AiApiErrorType.NETWORK
        }
    }
}

data class AiRequestStatus(
    val statusCode: Int?, val latencyMs: Long?, val model: String, val endpoint: String, val errorType: AiApiErrorType?,
    val exceptionClassName: String? = null,
    val transportSuccess: Boolean? = statusCode?.let { it in 200..299 },
    val parseSuccess: Boolean? = null,
    val failureStage: AiFailureStage? = null,
    val attemptCount: Int = 1,
    val invalidEventCount: Int = 0,
    val parseLatencyMs: Long? = null,
    val modelUnavailable: Boolean = false,
) {
    val errorLabel: String get() = when (errorType) {
        AiApiErrorType.URL_INVALID, AiApiErrorType.REQUEST_BUILD_FAILED ->
            errorType.name + (exceptionClassName?.let { " ($it)" } ?: "")
        else -> exceptionClassName ?: errorType?.name ?: failureStage?.takeIf { it != AiFailureStage.COMPLETE }?.name ?: "无"
    }

    fun diagnosticText(): String = "最近一次 AI 请求诊断\n状态码：${statusCode ?: "无"}\n" +
        "耗时：${latencyMs?.let { "${it}ms" } ?: "未知"}\nEndpoint：$endpoint\nModel：$model\n" +
        "AI请求：${when (transportSuccess) { true -> "成功"; false -> "失败"; null -> "未知" }}\n" +
        "日程解析：${when (parseSuccess) { true -> if (invalidEventCount > 0) "部分成功" else "成功"; false -> "失败"; null -> "未执行（仅连接测试）" }}\n" +
        "解析阶段：${failureStage?.name ?: "未执行"}\n错误类型：$errorLabel\n请求次数：$attemptCount"
}
