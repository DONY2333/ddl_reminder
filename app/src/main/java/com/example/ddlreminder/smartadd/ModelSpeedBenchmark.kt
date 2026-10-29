package com.example.ddlreminder.smartadd

import com.example.ddlreminder.settings.AiSettings
import com.example.ddlreminder.diagnostics.StabilityDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZoneId

enum class BenchmarkModel(val label: String) { FAST("快速模型"), STRONG("高级模型") }

data class BenchmarkAttempt(
    val index: Int,
    val model: BenchmarkModel,
    val success: Boolean,
    val elapsedMs: Long,
    val failure: String? = null,
)

/** 不含输入/响应/Key/模型 ID；结果与运行状态作为一个不可变快照发给 UI。 */
data class BenchmarkState(
    val isRunning: Boolean = false,
    val currentIndex: Int = 0,
    val attempts: List<BenchmarkAttempt> = emptyList(),
    val message: String? = null,
) {
    val progressText: String? get() = if (isRunning && currentIndex in 1..4)
        "正在测试${if (currentIndex <= 2) BenchmarkModel.FAST.label else BenchmarkModel.STRONG.label} $currentIndex/4" else null

    fun displayText(): String = buildString {
        progressText?.let { appendLine(it) }
        message?.let { appendLine(it) }
        attempts.forEach { appendLine("${it.model.label} ${it.index}/4：${if (it.success) "成功" else "失败（${it.failure ?: "UNKNOWN"}）"} · ${it.elapsedMs}ms") }
        for (model in BenchmarkModel.entries) {
            val rows = attempts.filter { it.model == model }
            if (rows.isNotEmpty()) appendLine("${model.label}：成功 ${rows.count { it.success }}/${rows.size} · 当前平均耗时 ${rows.map { it.elapsedMs }.average().toLong()}ms")
        }
        if (attempts.isNotEmpty()) append("平均耗时包含失败请求；成功仅表示结构化输出可用。")
    }.trim()
}

/** 开发测速：同一样例按两个模型各执行两次，串行请求且不重试。 */
class ModelSpeedBenchmark(
    private val client: AiApiClient,
    private val requestTimeoutMs: Long = 15_000,
    private val totalTimeoutMs: Long = 90_000,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    suspend fun run(settings: AiSettings, onProgress: suspend (BenchmarkState) -> Unit = {}): BenchmarkState {
        var state = BenchmarkState(isRunning = true)
        try {
            val config = settings.normalized()
            require(config.privacyAccepted && config.baseUrl.isNotBlank() && config.apiKey.isNotBlank() &&
                config.fastModel.isNotBlank() && config.strongModel.isNotBlank())
            if (config.fastModel == config.strongModel) {
                state = state.copy(message = "两个模型配置相同")
            } else {
                // 测速使用单次请求适配器，与正常解析的重试策略隔离。
                val oneShot = object : AiApiClient {
                    override suspend fun complete(config: AiSettings, systemPrompt: String, userPrompt: String) =
                        client.completeWithoutRetry(config, systemPrompt, userPrompt)
                }
                val completed = withTimeoutOrNull(totalTimeoutMs) {
                    for (index in 1..4) {
                        currentCoroutineContext().ensureActive()
                        val role = if (index <= 2) BenchmarkModel.FAST else BenchmarkModel.STRONG
                        val model = if (role == BenchmarkModel.FAST) config.fastModel else config.strongModel
                        state = state.copy(currentIndex = index)
                        onProgress(state)
                        val started = nanoTime()
                        var failure: String? = null
                        val result = try {
                            // 包含网络、读取和 JSON 处理；owned timeout 不吞外部取消。
                            withTimeoutOrNull(requestTimeoutMs) {
                                AiNaturalLanguageParser(config.copy(modelName = model), oneShot)
                                    .parse(SAMPLE, System.currentTimeMillis(), ZoneId.systemDefault())
                            }.also { if (it == null) failure = "REQUEST_TIMEOUT" }
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            StabilityDiagnostics.record(StabilityDiagnostics.Node.RECOVERABLE_FAILURE, error = error)
                            failure = error.javaClass.simpleName
                            null
                        }
                        currentCoroutineContext().ensureActive()
                        val success = result != null && result.aiErrorType == null && result.drafts.isNotEmpty()
                        if (!success && failure == null) failure = result?.aiErrorType ?: "EMPTY_EVENTS"
                        val row = BenchmarkAttempt(index, role, success,
                            ((nanoTime() - started) / 1_000_000).coerceAtLeast(0), failure)
                        state = state.copy(attempts = state.attempts + row)
                        onProgress(state) // 一次完成立即展示，不等整组结束。
                    }
                    true
                } ?: false
                state = state.copy(message = if (completed) "测试完成" else "测试已超时（90秒上限），已停止后续请求")
            }
        } catch (error: CancellationException) {
            state = state.copy(message = "测试已取消")
            throw error
        } finally {
            state = state.copy(isRunning = false)
            onProgress(state)
        }
        return state
    }

    companion object {
        const val SAMPLE = "军事理论课考试暂定于下周五上午举行。本次考试分为A、B卷进行，考试形式为闭卷，时长为1小时。题型包括单项选择题、不定项选择题、简答题和论述题。请同学们做好准备。@所有人"
    }
}
