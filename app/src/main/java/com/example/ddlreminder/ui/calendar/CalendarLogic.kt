package com.example.ddlreminder.ui.calendar

import com.example.ddlreminder.data.DdlEntity
import java.time.LocalDate
import java.time.YearMonth

fun selectedDateForMonth(targetMonth: YearMonth, today: LocalDate): LocalDate {
    if (targetMonth == YearMonth.from(today)) return today
    return targetMonth.atDay(today.dayOfMonth.coerceAtMost(targetMonth.lengthOfMonth()))
}

fun upcomingWithin72Hours(
    ddls: List<DdlEntity>,
    nowMillis: Long,
): List<DdlEntity> {
    val endExclusive = nowMillis + 72L * 60 * 60 * 1000
    return ddls.filter {
        !it.isCompleted && it.deadlineMillis > nowMillis && it.deadlineMillis <= endExclusive
    }.sortedBy { it.deadlineMillis }
}
