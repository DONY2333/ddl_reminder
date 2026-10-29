package com.example.ddlreminder.reminder

import org.junit.Assert.assertEquals
import org.junit.Test

class ReminderCalculatorTest {
    private val day = 24L * 60 * 60 * 1000

    @Test
    fun `new ddl creates four correct reminder times`() {
        val now = 1_000_000L
        val deadline = now + 10 * day

        val reminders = ReminderCalculator.futureReminders(deadline, now)

        assertEquals(ReminderOffset.LEGACY_DEFAULT.toList(), reminders.map { it.offset })
        assertEquals(
            ReminderOffset.LEGACY_DEFAULT.map { deadline - it.millis },
            reminders.map { it.triggerAtMillis },
        )
    }

    @Test
    fun `past reminder times are omitted when deadline is less than five days away`() {
        val now = 1_000_000L
        val deadline = now + 2 * day

        val reminders = ReminderCalculator.futureReminders(deadline, now)

        assertEquals(
            listOf(ReminderOffset(1440), ReminderOffset(720)),
            reminders.map { it.offset },
        )
    }

    @Test
    fun `past deadline has no reminders`() {
        assertEquals(emptyList<ReminderTime>(), ReminderCalculator.futureReminders(999, 1_000))
    }

    @Test
    fun `one hour reminder is calculated correctly`() {
        val now = 1_000L
        val deadline = now + 2 * 60 * 60 * 1000
        val result = ReminderCalculator.futureReminders(deadline, now, setOf(ReminderOffset(60)))

        assertEquals(deadline - 60 * 60 * 1000, result.single().triggerAtMillis)
    }

    @Test
    fun `thirty minute reminder is calculated correctly`() {
        val now = 1_000L
        val deadline = now + 2 * 60 * 60 * 1000
        val result = ReminderCalculator.futureReminders(deadline, now, setOf(ReminderOffset(30)))

        assertEquals(deadline - 30 * 60 * 1000, result.single().triggerAtMillis)
    }

    @Test
    fun `disabled reminder is not returned`() {
        val now = 1_000L
        val deadline = now + 10 * day
        val enabled = ReminderOffset.QUICK.toSet() - ReminderOffset(4320)

        val result = ReminderCalculator.futureReminders(deadline, now, enabled)

        assertEquals(false, result.any { it.offset == ReminderOffset(4320) })
    }

    @Test
    fun `enabled reminder whose trigger is past is not returned`() {
        val now = 1_000L
        val deadline = now + 20 * 60 * 1000

        val result = ReminderCalculator.futureReminders(
            deadline,
            now,
            setOf(ReminderOffset(60), ReminderOffset(30)),
        )

        assertEquals(emptyList<ReminderTime>(), result)
    }
}
