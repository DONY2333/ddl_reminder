package com.example.ddlreminder.reminder

/** V1.2 通用提醒值，单位为分钟；0 表示事件发生时。 */
@JvmInline
value class ReminderOffset(val minutes: Long) {
    init { require(minutes in 0..MAX_MINUTES) { "提醒时间必须在 0 到 365 天之间" } }

    val millis: Long get() = minutes * 60_000L
    val displayName: String get() = format(minutes)

    companion object {
        const val MAX_MINUTES = 365L * 24 * 60
        val QUICK = listOf(7200L, 4320L, 1440L, 720L, 60L, 30L).map(::ReminderOffset)
        val LEGACY_DEFAULT = listOf(7200L, 4320L, 1440L, 720L).map(::ReminderOffset).toSet()

        fun normalize(values: Iterable<Long>): Set<ReminderOffset> = values
            .filter { it in 0..MAX_MINUTES }
            .distinct()
            .sortedDescending()
            .map(::ReminderOffset)
            .toCollection(linkedSetOf())

        fun encode(values: Set<ReminderOffset>): String = values
            .map { it.minutes }.distinct().sortedDescending().joinToString(",")

        fun decode(value: String): Set<ReminderOffset> = normalize(
            value.split(',').mapNotNull { it.trim().toLongOrNull() },
        )

        fun decodeLegacy(value: String): Set<ReminderOffset> = ReminderType.decode(value)
            .map { ReminderOffset(it.offsetMillis / 60_000L) }.toCollection(linkedSetOf())

        fun format(minutes: Long): String = when {
            minutes == 0L -> "事件发生时"
            minutes % 1440L == 0L -> "提前 ${minutes / 1440L} 天"
            minutes % 60L == 0L -> "提前 ${minutes / 60L} 小时"
            else -> "提前 $minutes 分钟"
        }
    }
}

data class ReminderTime(val offset: ReminderOffset, val triggerAtMillis: Long)
