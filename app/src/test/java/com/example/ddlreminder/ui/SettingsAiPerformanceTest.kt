package com.example.ddlreminder.ui

import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.example.ddlreminder.DdlReminderApplication
import com.example.ddlreminder.settings.*
import com.example.ddlreminder.smartadd.*
import com.example.ddlreminder.ui.settings.SettingsViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsAiPerformanceTest {
    private val store = ViewModelStore()
    private var saved = AiSettings()
    private val models = mutableListOf<String>()
    @Before fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun cleanup() { store.clear(); Dispatchers.resetMain() }
    private suspend fun <T> waitReal(block: suspend () -> T) = withContext(Dispatchers.Default) { withTimeout(8_000) { block() } }
    private suspend fun vm(): SettingsViewModel {
        val app = ApplicationProvider.getApplicationContext<DdlReminderApplication>()
        val vm = SettingsViewModel(app.reminderSettings, app.eventLogger, app.csvExportManager, app.presetRepository,
            object : AiSettingsRepository {
                override fun get() = saved
                override fun save(settings: AiSettings) { saved = settings }
            }, object : AiApiClient {
                override suspend fun complete(config: AiSettings, systemPrompt: String, userPrompt: String): AiApiResult {
                    models += config.modelName
                    return AiApiResult.Success("{\"ok\":true}", 200, if (models.size == 1) 780 else 2300, "https://api.example/v1/chat/completions")
                }
            })
        store.put("settings", vm); waitReal { vm.loading.first { !it } }; return vm
    }
    @Test fun `settings saves single custom model with URL key and consent`() = runTest {
        val vm = vm()
        vm.saveAiSettings(" https://api.example/v1 ", " secret ", " custom Luna /model ", true).join()
        assertEquals("custom Luna /model", saved.fastModel)
        assertEquals("custom Luna /model", saved.modelName); assertEquals("secret", saved.apiKey)
        assertEquals("https://api.example/v1", saved.baseUrl); assertTrue(saved.privacyAccepted)
    }
    @Test fun `each connection test uses selected model with measured result`() = runTest {
        val vm = vm()
        vm.testAiConnection("https://api.example/v1", "secret", "fast/id")
        waitReal { vm.testing.first { !it } }
        assertEquals("fast/id", vm.aiRequestStatus.value!!.model); assertEquals(780L, vm.aiRequestStatus.value!!.latencyMs)
        vm.testAiConnection("https://api.example/v1", "secret", "strong/id")
        waitReal { vm.testing.first { !it } }
        assertEquals(listOf("fast/id", "strong/id"), models)
        assertEquals(2300L, vm.aiRequestStatus.value!!.latencyMs); assertNull(vm.aiRequestStatus.value!!.parseSuccess)
    }
    @Test fun `recent parse timing survives opening settings without making request`() = runTest {
        val perf = ParsePerformance(ParseRoute.LOCAL, totalLatencyMs = 8, eventCount = 1)
        AiDiagnostics.publishParse(perf)
        val vm = vm(); assertEquals(perf, vm.parsePerformance.value); assertTrue(models.isEmpty())
    }
    @Test fun `benchmark without privacy confirmation cannot send requests`() = runTest {
        val vm = vm(); vm.runBenchmark("https://api.example/v1", "secret", "fast", "strong", false)
        assertTrue(models.isEmpty()); assertFalse(vm.testing.value); assertNotNull(vm.message.value)
    }
}
