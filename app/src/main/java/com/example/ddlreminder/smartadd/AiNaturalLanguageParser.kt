package com.example.ddlreminder.smartadd

import com.example.ddlreminder.reminder.ReminderOffset
import com.example.ddlreminder.settings.AiSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.coroutines.coroutineContext

private class ParseFailure(val stage: AiFailureStage, val original: Exception? = null) : Exception()

class AiNaturalLanguageParser(
    private val settings: AiSettings,
    private val client: AiApiClient,
) : NaturalLanguageParser {
    override suspend fun parse(text: String, nowMillis: Long, zoneId: ZoneId): ParseResult {
        val localNow = Instant.ofEpochMilli(nowMillis).atZone(zoneId)
        val context = "Current local datetime: ${localNow.toOffsetDateTime()}\nTimezone: ${zoneId.id}"
        val result = try {
            client.complete(settings, ScheduleExtractionPrompt.text + "\n\n" + context, text)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            AiApiResult.Failure(AiApiErrorType.NETWORK, "AI请求失败", endpoint = safeEndpoint(),
                exceptionClassName = HttpAiApiClient.exceptionName(error))
        }
        coroutineContext.ensureActive()
        val base = when (result) {
            is AiApiResult.Failure -> AiRequestStatus(result.statusCode, result.latencyMs, safeModel(), result.endpoint,
                result.type, result.exceptionClassName, transportSuccess = false, parseSuccess = false,
                failureStage = if (result.type in setOf(AiApiErrorType.NETWORK, AiApiErrorType.TIMEOUT) || result.statusCode == null) AiFailureStage.NETWORK_ERROR else AiFailureStage.HTTP_ERROR,
                attemptCount = result.attemptCount, modelUnavailable = result.modelUnavailable)
            is AiApiResult.Success -> AiRequestStatus(result.statusCode, result.latencyMs, safeModel(), result.endpoint,
                null, transportSuccess = true, attemptCount = result.attemptCount)
        }
        val parseStarted = System.nanoTime()
        val parsed = when (result) {
            is AiApiResult.Failure -> ParseResult(emptyList(), "AI解析失败，请重试", usedAi = true,
                aiErrorType = base.failureStage!!.name)
            is AiApiResult.Success -> withContext(Dispatchers.Default) {
                parseResponse(result.body, text, nowMillis, zoneId)
            }
        }
        coroutineContext.ensureActive()
        val detail = parsed.diagnostic
        val status = base.copy(
            parseSuccess = if (result is AiApiResult.Failure) false else parsed.aiErrorType == null,
            failureStage = detail?.failureStage ?: base.failureStage ?: AiFailureStage.COMPLETE,
            exceptionClassName = detail?.exceptionClassName ?: base.exceptionClassName,
            invalidEventCount = parsed.invalidEventCount,
            parseLatencyMs = if (result is AiApiResult.Success) ((System.nanoTime() - parseStarted) / 1_000_000).coerceAtLeast(0) else null,
        )
        AiDiagnostics.publish(status)
        return parsed.copy(diagnostic = status)
    }

    private fun safeModel() = settings.modelName.replace(settings.apiKey.takeIf { it.isNotEmpty() } ?: "\u0000", "[REDACTED]")
    private fun safeEndpoint(): String = try {
        HttpAiApiClient.validatedEndpoint(settings.baseUrl).toString()
            .replace(settings.apiKey.takeIf { it.isNotEmpty() } ?: "\u0000", "[REDACTED]")
    } catch (_: Exception) { "（无有效 Endpoint）" }

    /** 每个对象单独校验；不将异常 message 或原始响应放入诊断。 */
    fun parseResponse(response: String, source: String, nowMillis: Long, zoneId: ZoneId): ParseResult {
        return try {
            if (response.isBlank()) throw ParseFailure(AiFailureStage.EMPTY_RESPONSE)
            if (response.length > MAX_RESPONSE_CHARS) throw ParseFailure(AiFailureStage.CONTENT_EXTRACTION_FAILED)
            val outer = try { strictObject(response.trim()) } catch (error: Exception) {
                throw ParseFailure(AiFailureStage.CONTENT_EXTRACTION_FAILED, error)
            }
            val root = if (outer.has("events")) outer else {
                val choices = outer.optJSONArray("choices")
                    ?: throw ParseFailure(AiFailureStage.CONTENT_EXTRACTION_FAILED)
                if (choices.length() == 0) throw ParseFailure(AiFailureStage.EMPTY_CHOICES)
                val message = choices.optJSONObject(0)?.optJSONObject("message")
                    ?: throw ParseFailure(AiFailureStage.CONTENT_EXTRACTION_FAILED)
                val content = when (val value = message.opt("content")) {
                    null, JSONObject.NULL -> throw ParseFailure(AiFailureStage.EMPTY_CONTENT)
                    is String -> value
                    is JSONArray -> (0 until value.length()).mapNotNull {
                        value.optJSONObject(it)?.opt("text") as? String
                    }.joinToString("\n")
                    else -> throw ParseFailure(AiFailureStage.CONTENT_EXTRACTION_FAILED)
                }
                if (content.isBlank()) throw ParseFailure(AiFailureStage.EMPTY_CONTENT)
                contentObject(content)
            }
            val events = root.optJSONArray("events") ?: throw ParseFailure(AiFailureStage.SCHEMA_VALIDATION_FAILED)
            val drafts = mutableListOf<ScheduleDraft>()
            var invalidCount = 0
            var firstFailure: ParseFailure? = null
            // 有限批次，避免异常模型输出制造数万个 Compose 节点。
            if (events.length() > 500) throw ParseFailure(AiFailureStage.SCHEMA_VALIDATION_FAILED)
            for (index in 0 until events.length()) {
                try {
                    val item = events.optJSONObject(index) ?: throw ParseFailure(AiFailureStage.SCHEMA_VALIDATION_FAILED)
                    drafts += validateEvent(item, source, nowMillis, zoneId)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    invalidCount++
                    if (firstFailure == null) firstFailure = error as? ParseFailure
                        ?: ParseFailure(AiFailureStage.EVENT_MAPPING_FAILED, error)
                }
            }
            val failure = firstFailure
            val allInvalid = events.length() > 0 && drafts.isEmpty()
            ParseResult(drafts, if (allInvalid) "AI解析失败，请重试" else null, usedAi = true,
                parseWarnings = jsonStrings(root.optJSONArray("parse_warnings")) +
                    if (invalidCount > 0) listOf("${invalidCount} 个结果需要检查，已保留其余日程") else emptyList(),
                discardedFragmentCount = root.optInt("discarded_fragment_count", 0).coerceAtLeast(0),
                aiErrorType = if (allInvalid) failure?.stage?.name else null,
                diagnostic = diagnostic(failure?.stage ?: AiFailureStage.COMPLETE, failure?.original, !allInvalid),
                invalidEventCount = invalidCount)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            val failure = error as? ParseFailure ?: ParseFailure(AiFailureStage.UNKNOWN_PARSE_ERROR, error)
            ParseResult(emptyList(), "AI解析失败，请重试", usedAi = true, aiErrorType = failure.stage.name,
                diagnostic = diagnostic(failure.stage, failure.original, false))
        }
    }

    private fun diagnostic(stage: AiFailureStage, error: Exception?, success: Boolean) =
        AiRequestStatus(null, null, "", "", null, error?.javaClass?.simpleName,
            transportSuccess = true, parseSuccess = success, failureStage = stage)

    private fun validateEvent(item: JSONObject, source: String, nowMillis: Long, zoneId: ZoneId): ScheduleDraft {
        val title = (item.opt("title") as? String)?.trim().orEmpty()
        if (title.isBlank() || title.length > 100) throw ParseFailure(AiFailureStage.SCHEMA_VALIDATION_FAILED)
        val rawDate = item.opt("date") as? String ?: throw ParseFailure(AiFailureStage.SCHEMA_VALIDATION_FAILED)
        val date = try { LocalDate.parse(rawDate.trim()) } catch (error: Exception) {
            throw ParseFailure(AiFailureStage.DATE_PARSE_FAILED, error)
        }
        val rawTime = item.opt("exact_time")?.takeIf { it != JSONObject.NULL }
        val exact = if (rawTime == null || rawTime == "") null else try {
            LocalTime.parse(rawTime as? String ?: throw IllegalArgumentException())
        } catch (error: Exception) { throw ParseFailure(AiFailureStage.TIME_PARSE_FAILED, error) }
        val period = enumValue<TimePeriod>(item.optString("time_period")) ?: TimePeriod.NONE
        val precision = enumValue<TimePrecision>(item.optString("time_precision")) ?: when {
            exact != null -> TimePrecision.EXACT
            period != TimePeriod.NONE -> TimePrecision.PERIOD
            else -> TimePrecision.DATE_ONLY
        }
        val usedDefault = exact == null && precision == TimePrecision.DATE_ONLY
        val effective = exact ?: if (usedDefault) LocalTime.of(23, 59) else null
        val needsConfirmation = item.optBoolean("needs_time_confirmation", precision == TimePrecision.PERIOD) || effective == null
        val deadline = effective?.let { date.atTime(it).atZone(zoneId).toInstant().toEpochMilli() }
        val warnings = jsonStrings(item.optJSONArray("warnings")).toMutableList()
        if (usedDefault) warnings += "未识别具体时间，已默认设置为23:59"
        if (needsConfirmation) warnings += "请确认具体时间"
        if (deadline != null && deadline <= nowMillis) warnings += "识别出的日期时间已经过去，请确认"
        val offsetsJson = item.optJSONArray("reminder_offsets_minutes")
        val rawOffsets = mutableListOf<Long>()
        if (offsetsJson != null) for (i in 0 until offsetsJson.length()) {
            val value = offsetsJson.optLong(i, -1)
            if (value in 0..ReminderOffset.MAX_MINUTES) rawOffsets += value
            else warnings += "部分提醒未识别，请检查提醒设置"
        }
        val canCreate = item.optBoolean("can_create", true) && !needsConfirmation && deadline != null && deadline > nowMillis
        return ScheduleDraft(
            title = title, deadlineMillis = deadline, reminderOffsets = ReminderOffset.normalize(rawOffsets),
            remindersExplicit = rawOffsets.isNotEmpty(), warnings = warnings.distinct(), usedDefaultTime = usedDefault,
            sourceText = source, confidence = ParseConfidence.HIGH, hasBlockingError = !canCreate,
            date = date, exactTime = effective, timePeriod = period, timePrecision = precision,
            needsTimeConfirmation = needsConfirmation, canCreate = canCreate,
            isTentative = item.optBoolean("is_tentative", false),
            notes = (item.opt("notes") as? String)?.trim()?.takeIf { it.isNotEmpty() })
    }

    private inline fun <reified T : Enum<T>> enumValue(raw: String): T? =
        enumValues<T>().firstOrNull { it.name.equals(raw, true) }

    private fun jsonStrings(array: JSONArray?): List<String> = if (array == null) emptyList()
        else (0 until array.length()).mapNotNull { (array.opt(it) as? String)?.takeIf(String::isNotBlank) }

    companion object {
        const val MAX_RESPONSE_CHARS = 2_000_000

        private fun strictObject(value: String): JSONObject {
            // 在递归 JSONTokener 前检查深度，避免异常模型输出造成栈溢出。
            var depth = 0
            var quoted = false
            var escaped = false
            for (ch in value) {
                if (quoted) {
                    if (escaped) escaped = false else if (ch == '\\') escaped = true else if (ch == '"') quoted = false
                } else when (ch) {
                    '"' -> quoted = true
                    '{', '[' -> { depth++; if (depth > 64) throw JSONException("Nesting limit") }
                    '}', ']' -> depth--
                }
            }
            val tokener = JSONTokener(value)
            val result = tokener.nextValue() as? JSONObject ?: throw JSONException("Expected object")
            if (tokener.nextClean() != '\u0000') throw JSONException("Trailing content")
            return result
        }

        private fun contentObject(content: String): JSONObject {
            val trimmed = content.trim()
            try { return strictObject(trimmed) } catch (_: JSONException) { /* 有限的兼容路径 */ }
            if (trimmed.startsWith("```") && trimmed.endsWith("```")) {
                val unfenced = trimmed.substringAfter('\n', "").dropLast(3).trim()
                if (unfenced.isNotEmpty()) try { return strictObject(unfenced) } catch (_: JSONException) { }
            }
            val extracted = try { extractFirstCompleteJsonObject(trimmed) } catch (error: Exception) {
                throw ParseFailure(AiFailureStage.JSON_EXTRACTION_FAILED, error)
            }
            return try { strictObject(extracted) } catch (error: Exception) {
                throw ParseFailure(AiFailureStage.JSON_PARSE_FAILED, error)
            }
        }

        fun extractJsonObject(text: String): String = extractFirstCompleteJsonObject(text)

        /** 字符扫描而不是贪婪正则；字符串内的括号、转义引号和反斜杠均不改变层级。 */
        fun extractFirstCompleteJsonObject(text: String): String {
            require(text.length <= MAX_RESPONSE_CHARS)
            val start = text.indexOf('{')
            check(start >= 0) { "No JSON object" }
            val stack = ArrayDeque<Char>()
            var quoted = false
            var escaped = false
            for (i in start until text.length) {
                val ch = text[i]
                if (quoted) {
                    if (escaped) escaped = false else if (ch == '\\') escaped = true else if (ch == '"') quoted = false
                } else when (ch) {
                    '"' -> quoted = true
                    '{', '[' -> { stack.addLast(ch); check(stack.size <= 64) { "Nesting limit" } }
                    '}', ']' -> {
                        check(stack.isNotEmpty() && stack.removeLast() == if (ch == '}') '{' else '[')
                        if (stack.isEmpty()) return text.substring(start, i + 1)
                    }
                }
            }
            error("Incomplete JSON object")
        }
    }
}

class FallbackNaturalLanguageParser(private val ai: NaturalLanguageParser, private val local: NaturalLanguageParser) : NaturalLanguageParser {
    override suspend fun parse(text: String, nowMillis: Long, zoneId: ZoneId): ParseResult {
        val aiResult = ai.parse(text, nowMillis, zoneId)
        if (aiResult.aiErrorType == null) return aiResult
        val localResult = local.parse(text, nowMillis, zoneId)
        return localResult.copy(
            errorMessage = "AI解析失败，本次使用本地解析结果", usedAi = true, fellBackToLocal = true,
            aiErrorType = aiResult.aiErrorType, diagnostic = aiResult.diagnostic,
            parseWarnings = aiResult.parseWarnings + localResult.parseWarnings)
    }
}
