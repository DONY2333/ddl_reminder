package com.example.ddlreminder.reminder

import com.example.ddlreminder.diagnostics.recoverable

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.ddlreminder.DdlReminderApplication
import com.example.ddlreminder.data.reminderOffsets
import com.example.ddlreminder.logging.EventTypes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** BroadcastReceiver 是系统闹钟触发时进入 App 的入口，生命周期很短。 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_DEBUG_TEST) {
            NotificationHelper.showDebugTest(context)
            return
        }
        if (intent.action != ACTION_DDL_REMINDER) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                recoverable {
                val id = intent.getLongExtra(EXTRA_DDL_ID, -1)
                val expectedDeadline = intent.getLongExtra(EXTRA_DEADLINE, -1)
                val offset = intent.getLongExtra(EXTRA_OFFSET_MINUTES, -1).takeIf { it >= 0 }
                    ?.let { recoverable { ReminderOffset(it) }.getOrNull() }
                val repository = (context.applicationContext as DdlReminderApplication).repository
                val eventLogger = (context.applicationContext as DdlReminderApplication).eventLogger
                val ddl = repository.get(id)
                val now = System.currentTimeMillis()
                if (id > 0 && offset != null) {
                    recoverable {
                        eventLogger.log(
                            eventType = EventTypes.REMINDER_FIRED,
                            eventTime = now,
                            ddlId = id,
                            deadlineMillis = expectedDeadline.takeIf { it > 0 },
                            reminderOffset = offset,
                        )
                    }
                }
                // 最终以数据库为准，可拦住完成、删除、编辑后残留的旧闹钟。
                val isStillRelevant = offset != null && (ddl?.deadlineMillis?.let { deadline ->
                    deadline > now || (offset.minutes == 0L && now - deadline in 0..5 * 60_000L)
                } == true)
                if (ddl != null && offset != null && !ddl.isCompleted &&
                    ddl.deadlineMillis == expectedDeadline && isStillRelevant &&
                    offset in ddl.reminderOffsets()
                ) {
                    NotificationHelper.showDdlReminder(context, ddl, offset)
                }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_DDL_REMINDER = "com.example.ddlreminder.DDL_REMINDER"
        const val ACTION_DEBUG_TEST = "com.example.ddlreminder.DEBUG_TEST"
        const val EXTRA_DDL_ID = "ddl_id"
        const val EXTRA_DEADLINE = "deadline"
        const val EXTRA_OFFSET_MINUTES = "reminder_offset_minutes"
    }
}
