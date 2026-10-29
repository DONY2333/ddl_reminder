package com.example.ddlreminder.reminder

/** 纯 Kotlin 计算器，不依赖 Android，因此容易做单元测试。 */
object ReminderCalculator {
    fun futureReminders(
        deadlineMillis: Long,
        nowMillis: Long,
        enabledOffsets: Set<ReminderOffset> = ReminderOffset.LEGACY_DEFAULT,
    ): List<ReminderTime> {
        if (deadlineMillis <= nowMillis) return emptyList()
        return enabledOffsets.sortedByDescending { it.minutes }.mapNotNull { offset ->
            val trigger = deadlineMillis - offset.millis
            if (trigger > nowMillis || (offset.minutes == 0L && trigger >= nowMillis)) {
                ReminderTime(offset, trigger)
            } else null
        }
    }
}
