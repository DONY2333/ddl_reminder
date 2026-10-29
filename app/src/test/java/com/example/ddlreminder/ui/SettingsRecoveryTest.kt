package com.example.ddlreminder.ui

import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.example.ddlreminder.DdlReminderApplication
import com.example.ddlreminder.settings.*
import com.example.ddlreminder.smartadd.*
import com.example.ddlreminder.reminder.ReminderOffset
import com.example.ddlreminder.ui.settings.SettingsViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsRecoveryTest {
    private val store = ViewModelStore()
    @Before fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun cleanup() { store.clear(); Dispatchers.resetMain() }
    private suspend fun <T> waitReal(block: suspend () -> T) = withContext(Dispatchers.Default) { withTimeout(8_000) { block() } }
    private fun create(failSettings: Boolean = false, failPresets: Boolean = false): SettingsViewModel {
        val app = ApplicationProvider.getApplicationContext<DdlReminderApplication>()
        val vm = SettingsViewModel(app.reminderSettings, app.eventLogger, app.csvExportManager,
            object : ReminderPresetRepository {
                override val presets: Flow<List<ReminderPreset>> = flow { if (failPresets) throw IOException(); emit(emptyList()) }
                override suspend fun saveCustom(id: String?, name: String, offsets: Set<ReminderOffset>) = "id"
                override suspend fun deleteCustom(id: String) = Unit
            }, object : AiSettingsRepository {
                override fun get(): AiSettings { if (failSettings) throw IOException(); return AiSettings() }
                override fun save(settings: AiSettings) = Unit
            }, app.aiApiClient)
        store.put("settings", vm)
        return vm
    }
    @Test fun `settings initialization error has visible message and finally leaves loading`() = runTest {
        val vm = create(failSettings = true)
        waitReal { vm.message.first { it != null } }
        assertFalse(vm.loading.value)
    }
    @Test fun `preset flow error becomes message without killing settings`() = runTest {
        val vm = create(failPresets = true)
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.presets.collect() }
        waitReal { vm.message.first { it != null } }
        job.cancel()
        assertNotNull(vm.message.value)
    }
    @Test fun `opening settings retains shared Smart Add pipeline diagnostic`() = runTest {
        val diagnostic = AiRequestStatus(200, 1380, "model", "https://api.example.com/v1/chat/completions", null,
            "JSONException", transportSuccess = true, parseSuccess = false, failureStage = AiFailureStage.JSON_PARSE_FAILED)
        AiDiagnostics.publish(diagnostic)
        val vm = create()
        waitReal { vm.loading.first { !it } }
        assertEquals(diagnostic, vm.aiRequestStatus.value)
        assertTrue(vm.aiRequestStatus.value!!.diagnosticText().contains("AI请求：成功"))
        assertTrue(vm.aiRequestStatus.value!!.diagnosticText().contains("日程解析：失败"))
    }
}
