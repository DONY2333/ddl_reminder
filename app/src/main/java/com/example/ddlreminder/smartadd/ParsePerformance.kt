package com.example.ddlreminder.smartadd

enum class ParseRoute { LOCAL, AI, FAST_AI, STRONG_AI, FAST_THEN_STRONG, LOCAL_FALLBACK }

/** 仅进程内的结构化诊断，不允许存用户文本或响应。HTTP 时间含中转/模型等待，不能拆成真实推理时间。 */
data class ParsePerformance(
    val routeUsed: ParseRoute,
    val fast: AiRequestStatus? = null,
    val strong: AiRequestStatus? = null,
    val totalLatencyMs: Long = 0,
    val eventCount: Int = 0,
    val timedOut: Boolean = false,
) {
    val networkRetryCount get() = (fast?.attemptCount?.minus(1) ?: 0) + (strong?.attemptCount?.minus(1) ?: 0)
    val modelFallbackOccurred get() = fast != null && strong != null
    val latencyBucket get() = when {
        totalLatencyMs < 500 -> "<500ms"
        totalLatencyMs < 1500 -> "500-1500ms"
        totalLatencyMs < 3000 -> "1500-3000ms"
        totalLatencyMs <= 5000 -> "3000-5000ms"
        else -> ">5000ms"
    }
    // 只用固定枚举/计数/布尔；模型 ID 仅诊断显示，不进入匿名行为日志。
    fun eventMetadata(): String = "{\"event_count\":$eventCount,\"parse_route\":\"${routeUsed.name}\",\"latency_bucket\":\"$latencyBucket\",\"fast_model_success\":${fast?.parseSuccess == true},\"strong_fallback_used\":$modelFallbackOccurred}"

    fun diagnosticText(): String = buildString {
        val visibleRoute = when (routeUsed) {
            ParseRoute.LOCAL -> "LOCAL"
            ParseRoute.LOCAL_FALLBACK -> "LOCAL_FALLBACK"
            else -> "AI"
        }
        append("最近一次日程解析\n解析方式：$visibleRoute\n")
        val ai = fast ?: strong // 兼容旧诊断字段，不暴露双模型概念。
        append("AI模型：${ai?.model ?: "未调用"}\n")
        append("HTTP耗时：${ai?.latencyMs?.let { "${it}ms" } ?: "未执行/未完成"}\n")
        append("JSON处理耗时：${ai?.parseLatencyMs?.let { "${it}ms" } ?: "未执行"}\n")
        append("总耗时：${totalLatencyMs}ms\n网络重试：$networkRetryCount\n失败阶段：${if (timedOut) "TOTAL_TIMEOUT" else ai?.failureStage?.takeUnless { it == AiFailureStage.COMPLETE } ?: "无"}\n日程数：$eventCount")
        if (ai?.modelUnavailable == true) append("\nAI模型不可用或未找到")
        else if (ai?.statusCode == 404) append("\nHTTP 404：请检查模型或Endpoint")
        if (timedOut) append("\n总超时：已停止，请重试")
    }
}
