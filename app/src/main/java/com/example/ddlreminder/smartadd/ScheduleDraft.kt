package com.example.ddlreminder.smartadd

import com.example.ddlreminder.reminder.ReminderOffset
import java.time.LocalDate
import java.time.LocalTime

enum class ParseConfidence { HIGH, MEDIUM, LOW }
enum class TimePrecision { EXACT, PERIOD, DATE_ONLY, UNKNOWN }
enum class TimePeriod { MORNING, NOON, AFTERNOON, EVENING, NIGHT, NONE }

data class ScheduleDraft(
    val title: String,
    val deadlineMillis: Long?,
    val reminderOffsets: Set<ReminderOffset> = emptySet(),
    val remindersExplicit: Boolean = false,
    val warnings: List<String> = emptyList(),
    val usedDefaultTime: Boolean = false,
    val ambiguousDate: Boolean = false,
    val sourceText: String = "",
    val confidence: ParseConfidence = ParseConfidence.MEDIUM,
    val sourceLine: Int? = null,
    val possibleDuplicate: Boolean = false,
    val hasBlockingError: Boolean = false,
    val date: LocalDate? = null,
    val exactTime: LocalTime? = null,
    val timePeriod: TimePeriod = TimePeriod.NONE,
    val timePrecision: TimePrecision = TimePrecision.UNKNOWN,
    val needsTimeConfirmation: Boolean = false,
    val canCreate: Boolean = deadlineMillis != null && !hasBlockingError,
    val isTentative: Boolean = false,
    val notes: String? = null,
) {
    val isValid: Boolean get() = title.isNotBlank() && deadlineMillis != null && !ambiguousDate &&
        !hasBlockingError && canCreate && !needsTimeConfirmation
}

data class ParseResult(
    val drafts: List<ScheduleDraft>,
    val errorMessage: String? = null,
    val usedAi: Boolean = false,
    val fellBackToLocal: Boolean = false,
    val parseWarnings: List<String> = emptyList(),
    val discardedFragmentCount: Int = 0,
    val aiErrorType: String? = null,
    val diagnostic: AiRequestStatus? = null,
    val invalidEventCount: Int = 0,
    val performance: ParsePerformance? = null,
)
