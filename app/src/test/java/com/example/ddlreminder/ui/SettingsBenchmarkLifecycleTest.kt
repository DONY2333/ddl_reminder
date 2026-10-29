package com.example.ddlreminder.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.example.ddlreminder.DdlReminderApplication
import com.example.ddlreminder.settings.*
import com.example.ddlreminder.smartadd.*
import com.example.ddlreminder.ui.settings.SettingsScreen
import com.example.ddlreminder.ui.settings.SettingsViewModel
import kotlinx.coroutines.awaitCancellation
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import android.os.Looper
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsBenchmarkLifecycleTest {
    @get:Rule val compose = createComposeRule()
    private val store = ViewModelStore()
    @After fun cleanup() { store.clear() }
    @Test fun `disposing actual SettingsScreen cancels benchmark without waiting for VM clear`() {
        val app = ApplicationProvider.getApplicationContext<DdlReminderApplication>()
        val calls = AtomicInteger()
        val cancelled = AtomicInteger()
        val vm = SettingsViewModel(app.reminderSettings, app.eventLogger, app.csvExportManager, app.presetRepository,
            object : AiSettingsRepository {
                override fun get() = AiSettings()
                override fun save(settings: AiSettings) = Unit
            }, object : AiApiClient {
                override suspend fun complete(config: AiSettings, systemPrompt: String, userPrompt: String): AiApiResult {
                    calls.incrementAndGet()
                    try { awaitCancellation() } finally { cancelled.incrementAndGet() }
                }
            })
        store.put("settings", vm)
        var visible by mutableStateOf(true)
        compose.setContent { MaterialTheme {
            if (visible) SettingsScreen(vm, PaddingValues()) {} else Text("other page")
        } }
        compose.waitUntil(10_000) { !vm.loading.value }
        compose.runOnIdle { vm.runBenchmark("https://api.example/v1", "secret", "fast", "strong", true) }
        compose.waitUntil(10_000) {
            // IO/Default 回到 Android 主线程的进度回调需要推进 Robolectric 主 Looper。
            shadowOf(Looper.getMainLooper()).idle()
            calls.get() == 1
        }
        compose.runOnIdle { visible = false }
        compose.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idle()
            cancelled.get() == 1 && !vm.benchmark.value.isRunning
        }
        assertEquals(1, calls.get()); assertTrue(vm.benchmark.value.message!!.contains("离开"))
    }
}
