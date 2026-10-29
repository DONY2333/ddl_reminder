package com.example.ddlreminder

import com.example.ddlreminder.diagnostics.recoverable
import com.example.ddlreminder.diagnostics.StabilityDiagnostics

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.example.ddlreminder.ui.DdlApp
import com.example.ddlreminder.ui.theme.DdlReminderTheme
import com.example.ddlreminder.logging.EventTypes
import com.example.ddlreminder.reminder.ReminderOffset
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {
    private var requestedDdlId by mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StabilityDiagnostics.record(StabilityDiagnostics.Node.CREATED)
        requestedDdlId = intent.requestedDdlId()
        logColdOpenOnce()
        logNotificationClick(intent)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            DdlReminderTheme {
                DdlApp(openDdlId = requestedDdlId)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedDdlId = intent.requestedDdlId()
        logNotificationClick(intent)
    }

    private fun Intent.requestedDdlId(): Long? =
        getLongExtra(EXTRA_OPEN_DDL_ID, -1).takeIf { it > 0 }

    private fun logColdOpenOnce() {
        if ((application as DdlReminderApplication).initializationFailed) return
        if (!appOpenLogged.compareAndSet(false, true)) return
        lifecycleScope.launch {
            recoverable {
                (application as DdlReminderApplication).eventLogger.log(EventTypes.APP_OPENED)
            }
        }
    }

    private fun logNotificationClick(launchIntent: Intent) {
        if ((application as DdlReminderApplication).initializationFailed) return
        val ddlId = launchIntent.getLongExtra(EXTRA_OPEN_DDL_ID, -1).takeIf { it > 0 } ?: return
        val reminderOffset = launchIntent.getLongExtra(EXTRA_REMINDER_OFFSET, -1)
            .takeIf { it >= 0 }?.let { ReminderOffset(it) } ?: return
        val firedAt = launchIntent.getLongExtra(EXTRA_REMINDER_FIRED_AT, -1)
        val clickedAt = System.currentTimeMillis()
        lifecycleScope.launch {
            recoverable {
                (application as DdlReminderApplication).eventLogger.log(
                    eventType = EventTypes.REMINDER_CLICKED,
                    eventTime = clickedAt,
                    ddlId = ddlId,
                    reminderOffset = reminderOffset,
                    metadataJson = firedAt.takeIf { it > 0 }?.let {
                        "{\"click_delay_ms\":${(clickedAt - it).coerceAtLeast(0)}}"
                    },
                )
            }
        }
        // Activity 因旋转等原因重建时，不应重复记录同一次点击。
        launchIntent.removeExtra(EXTRA_REMINDER_OFFSET)
        launchIntent.removeExtra(EXTRA_REMINDER_FIRED_AT)
    }

    override fun onStart() { super.onStart(); StabilityDiagnostics.record(StabilityDiagnostics.Node.STARTED) }
    override fun onResume() { super.onResume(); StabilityDiagnostics.record(StabilityDiagnostics.Node.RESUMED) }
    override fun onPause() { StabilityDiagnostics.record(StabilityDiagnostics.Node.PAUSED); super.onPause() }
    override fun onStop() { StabilityDiagnostics.record(StabilityDiagnostics.Node.STOPPED); super.onStop() }
    override fun onDestroy() { StabilityDiagnostics.record(StabilityDiagnostics.Node.DESTROYED); super.onDestroy() }

    companion object {
        const val EXTRA_OPEN_DDL_ID = "open_ddl_id"
        const val EXTRA_REMINDER_OFFSET = "open_reminder_offset"
        const val EXTRA_REMINDER_FIRED_AT = "reminder_fired_at"
        private val appOpenLogged = AtomicBoolean(false)
    }
}
