package com.example.ddlreminder.smartadd

import com.example.ddlreminder.settings.AiSettings
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZoneId

/** 保留类名兼容现有依赖：本地快路径 → 单一可配置 AI → 保守本地回退，不再换模型。 */
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
        var fast: AiRequestStatus? = null
        var route = ParseRoute.LOCAL
        val result = withTimeoutOrNull(timeoutMillis) {
            val simple = FastPathDetector.detect(text) == FastPath.LOCAL
            if (simple) {
                val candidate = local.parse(text, nowMillis, zoneId)
                // Detector 不代替校验；无效日期/时间等仍交 AI，而不是错误地当作快路径成功。
                val draft = candidate.drafts.singleOrNull()
                if (draft?.isValid == true && (!FastPathDetector.hasExplicitTime(text) || !draft.usedDefaultTime)) return@withTimeoutOrNull candidate
            }
            val config = settings.forSingleModel()
            if (!config.isConfigured || !config.privacyAccepted) return@withTimeoutOrNull local.parse(text, nowMillis, zoneId)

            currentCoroutineContext().ensureActive()
            onAiStarted()
            route = ParseRoute.AI
            fast = pending(config, config.modelName)
            val aiResult = AiNaturalLanguageParser(config, client).parse(text, nowMillis, zoneId)
            fast = aiResult.diagnostic
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
            fellBackToLocal = route != ParseRoute.LOCAL, aiErrorType = "TOTAL_TIMEOUT", diagnostic = fast)
        if (result == null && route != ParseRoute.LOCAL) route = ParseRoute.LOCAL_FALLBACK
        val performance = ParsePerformance(route, fast, null,
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
