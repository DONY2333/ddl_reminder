package com.example.ddlreminder.reminder

enum class ReminderType(val offsetMillis: Long, val displayName: String) {
    FIVE_DAYS(5L * 24 * 60 * 60 * 1000, "5 天"),
    THREE_DAYS(3L * 24 * 60 * 60 * 1000, "3 天"),
    ONE_DAY(24L * 60 * 60 * 1000, "1 天"),
    TWELVE_HOURS(12L * 60 * 60 * 1000, "12 小时"),
    ONE_HOUR(60L * 60 * 1000, "1 小时"),
    THIRTY_MINUTES(30L * 60 * 1000, "30 分钟"),
    ;

    companion object {
        /** V1.0 的固定四个提醒，也是首次安装时的全局默认值。 */
        val LEGACY_DEFAULT: Set<ReminderType> = linkedSetOf(
            FIVE_DAYS, THREE_DAYS, ONE_DAY, TWELVE_HOURS,
        )

        fun encode(types: Set<ReminderType>): String =
            entries.filter { it in types }.joinToString(",") { it.name }

        fun decode(value: String): Set<ReminderType> = value
            .split(',')
            .mapNotNull { name -> entries.find { it.name == name.trim() } }
            .toCollection(linkedSetOf())
    }
}

