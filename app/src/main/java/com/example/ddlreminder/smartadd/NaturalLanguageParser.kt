package com.example.ddlreminder.smartadd

import java.time.ZoneId

interface NaturalLanguageParser {
    suspend fun parse(text: String, nowMillis: Long, zoneId: ZoneId = ZoneId.systemDefault()): ParseResult
}
