package com.example.ddlreminder.smartadd

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AiFailureStage {
    NETWORK_ERROR, HTTP_ERROR, EMPTY_RESPONSE, EMPTY_CHOICES, EMPTY_CONTENT,
    CONTENT_EXTRACTION_FAILED, JSON_EXTRACTION_FAILED, JSON_PARSE_FAILED,
    SCHEMA_VALIDATION_FAILED, EVENT_MAPPING_FAILED, TIME_PARSE_FAILED, DATE_PARSE_FAILED,
    UNKNOWN_PARSE_ERROR, COMPLETE,
}

/** 仅保存最近一次安全诊断；不持有输入、响应、标题、备忘或凭证，不写 event_logs。 */
object AiDiagnostics {
    private val current = MutableStateFlow<AiRequestStatus?>(null)
    val latest = current.asStateFlow()
    private val parse = MutableStateFlow<ParsePerformance?>(null)
    val latestParse = parse.asStateFlow()
    fun publishParse(value: ParsePerformance) { parse.value = value }
    fun publish(status: AiRequestStatus) { current.value = status }
}
