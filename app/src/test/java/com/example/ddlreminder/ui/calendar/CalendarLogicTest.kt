package com.example.ddlreminder.ui.calendar

import com.example.ddlreminder.data.DdlEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class CalendarLogicTest {
    private val hour = 60L * 60 * 1000

    @Test
    fun `changing month selects same day number as today`() {
        val result = selectedDateForMonth(YearMonth.of(2026, 11), LocalDate.of(2026, 10, 12))

        assertEquals(LocalDate.of(2026, 11, 12), result)
    }

    @Test
    fun `month without same day selects its last day`() {
        val result = selectedDateForMonth(YearMonth.of(2026, 2), LocalDate.of(2026, 1, 31))

        assertEquals(LocalDate.of(2026, 2, 28), result)
    }

    @Test
    fun `upcoming list contains only next 72 hours sorted by deadline`() {
        val now = 1_000L
        val result = upcomingWithin72Hours(
            listOf(
                entity(1, now + 70 * hour),
                entity(2, now + 2 * hour),
                entity(3, now + 73 * hour),
            ),
            now,
        )

        assertEquals(listOf(2L, 1L), result.map { it.id })
    }

    @Test
    fun `completed and overdue tasks are excluded from upcoming list`() {
        val now = 1_000L
        val result = upcomingWithin72Hours(
            listOf(
                entity(1, now + hour).copy(isCompleted = true),
                entity(2, now - hour),
                entity(3, now + hour),
            ),
            now,
        )

        assertEquals(listOf(3L), result.map { it.id })
    }

    private fun entity(id: Long, deadline: Long) = DdlEntity(
        id = id,
        title = "task $id",
        deadlineMillis = deadline,
        createdAtMillis = 0,
        updatedAtMillis = 0,
    )
}
