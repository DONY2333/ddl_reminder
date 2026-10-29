package com.example.ddlreminder.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.ddlreminder.DdlReminderApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.example.ddlreminder.diagnostics.recoverable

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // AlarmManager 的注册在关机后会消失，因此开机后从 Room 恢复未来提醒。
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                recoverable {
                    val repository = (context.applicationContext as DdlReminderApplication).repository
                    repository.synchronizeReminders(System.currentTimeMillis())
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
