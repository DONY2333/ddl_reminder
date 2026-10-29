package com.example.ddlreminder.reminder

import com.example.ddlreminder.diagnostics.recoverable

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.example.ddlreminder.data.DdlEntity
import com.example.ddlreminder.data.reminderOffsets
import com.example.ddlreminder.logging.EventLogger

class AndroidAlarmScheduler(
    private val context: Context,
    private val eventLogger: EventLogger,
) : AlarmScheduler {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    override suspend fun schedule(ddl: DdlEntity, nowMillis: Long): String? = recoverable {
        ReminderCalculator.futureReminders(ddl.deadlineMillis, nowMillis, ddl.reminderOffsets()).forEach { reminder ->
            setAlarm(reminder.triggerAtMillis, alarmPendingIntent(ddl, reminder.offset))
            recoverable {
                eventLogger.logReminderScheduledOnce(
                    ddlId = ddl.id,
                    deadlineMillis = ddl.deadlineMillis,
                    reminderOffset = reminder.offset,
                    triggerAtMillis = reminder.triggerAtMillis,
                    eventTime = nowMillis,
                )
            }
        }
    }.exceptionOrNull()?.let { "提醒注册失败：${it.message ?: it.javaClass.simpleName}" }

    override fun cancel(ddl: DdlEntity) {
        ddl.reminderOffsets().forEach { offset ->
            val intent = baseAlarmIntent(ddl.id, ddl.deadlineMillis, offset)
            val pending = PendingIntent.getBroadcast(
                context, 0, intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )
            pending?.let { alarmManager.cancel(it); it.cancel() }
        }
    }

    override fun scheduleDebugTest(triggerAtMillis: Long): String? = recoverable {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_DEBUG_TEST
            data = Uri.parse("ddlreminder://debug/test")
        }
        val pending = PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        setAlarm(triggerAtMillis, pending)
    }.exceptionOrNull()?.let { "测试通知注册失败：${it.message ?: it.javaClass.simpleName}" }

    override fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    private fun setAlarm(triggerAtMillis: Long, pendingIntent: PendingIntent) {
        // Android 12+ 的精确闹钟受特殊权限控制；没有权限时降级为非精确提醒。
        if (canScheduleExact()) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent,
            )
        } else {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent,
            )
        }
    }

    private fun alarmPendingIntent(ddl: DdlEntity, offset: ReminderOffset): PendingIntent {
        val intent = baseAlarmIntent(ddl.id, ddl.deadlineMillis, offset).apply {
            putExtra(AlarmReceiver.EXTRA_DEADLINE, ddl.deadlineMillis)
        }
        return PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun baseAlarmIntent(ddlId: Long, deadlineMillis: Long, offset: ReminderOffset) =
        Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_DDL_REMINDER
            // PendingIntent 用 Intent 的 data 区分身份，确保不同任务/提醒不会互相覆盖。
            data = Uri.parse(alarmIdentity(ddlId, deadlineMillis, offset.minutes))
            putExtra(AlarmReceiver.EXTRA_DDL_ID, ddlId)
            putExtra(AlarmReceiver.EXTRA_OFFSET_MINUTES, offset.minutes)
        }

    companion object {
        fun alarmIdentity(ddlId: Long, deadlineMillis: Long, offsetMinutes: Long): String =
            "ddlreminder://alarm/$ddlId/$deadlineMillis/$offsetMinutes"
    }
}
