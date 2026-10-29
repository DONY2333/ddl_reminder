package com.example.ddlreminder.smartadd

import com.example.ddlreminder.settings.AiSettings
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZoneId

/** 简单日程在本地解析；复杂文本调用配置的 AI，失败后回退本地规则。 */
class TieredNaturalLanguageParser(
    private val settings: AiSettings,
    private val client: AiApiClient,
    private val timeoutMillis: Long = 30_000,
    private val nanoTime: () -> Long = System::nanoTime,
    private val onAiStarted: () -> Unit = {},
) : NaturalLanguageParser {
    override suspend fun parse(text: String, nowMillis: Long, zoneId: ZoneId): ParseResult {
        val started = nanoTime()
        val local = LocalNaturalLanguageParser()
        var requestStatus: AiRequestStatus? = null
        var route = ParseRoute.LOCAL
        val result = withTimeoutOrNull(timeoutMillis) {
            val simple = FastPathDetector.detect(text) == FastPath.LOCAL
            if (simple) {
                val candidate = local.parse(text, nowMillis, zoneId)
                // 快路径仍需校验日期和时间；明确写出的时间不能被默认时间替代。
                val draft = candidate.drafts.singleOrNull()
                if (draft?.isValid == true && (!FastPathDetector.hasExplicitTime(text) || !draft.usedDefaultTime)) return@withTimeoutOrNull candidate
            }
            val config = settings.forSingleModel()
            if (!config.isConfigured || !config.privacyAccepted) return@withTimeoutOrNull local.parse(text, nowMillis, zoneId)

            currentCoroutineContext().ensureActive()
            onAiStarted()
            route = ParseRoute.AI
            requestStatus = pending(config, config.modelName)
            val aiResult = AiNaturalLanguageParser(config, client).parse(text, nowMillis, zoneId)
            requestStatus = aiResult.diagnostic
            if (aiResult.aiErrorType == null && (aiResult.drafts.isNotEmpty() || !FastPathDetector.hasScheduleClue(text))) return@withTimeoutOrNull aiResult
            route = ParseRoute.LOCAL_FALLBACK
            local.parse(text, nowMillis, zoneId).copy(
                errorMessage = "AI解析失败，本次使用本地解析结果", usedAi = true, fellBackToLocal = true,
                aiErrorType = aiResult.aiErrorType ?: AiFailureStage.SCHEMA_VALIDATION_FAILED.name,
                diagnostic = aiResult.diagnostic, parseWarnings = aiResult.parseWarnings)
        }
        currentCoroutineContext().ensureActive()
        val final = result ?: local.parse(text, nowMillis, zoneId).copy(
            errorMessage = "AI解析失败，本次使用本地解析结果", usedAi = route != ParseRoute.LOCAL,
            fellBackToLocal = route != ParseRoute.LOCAL, aiErrorType = "TOTAL_TIMEOUT", diagnostic = requestStatus)
        if (result == null && route != ParseRoute.LOCAL) route = ParseRoute.LOCAL_FALLBACK
        val performance = ParsePerformance(route, requestStatus, null,
            ((nanoTime() - started) / 1_000_000).coerceAtLeast(0), final.drafts.size, timedOut = result == null)
        AiDiagnostics.publishParse(performance)
        return final.copy(performance = performance)
    }

    companion object {
        private fun pending(config: AiSettings, model: String) = AiRequestStatus(null, null,
            if (config.apiKey.isEmpty()) model else model.replace(config.apiKey, "[REDACTED]"),
            "（请求尚未完成）", null, transportSuccess = null)

    }
}
