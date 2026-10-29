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
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsBenchmarkTest {
    private val store = ViewModelStore()
    private val success = AiApiResult.Success("""{"events":[{"title":"test","date":"2099-10-25"}]}""", 200, 5, "https://api.example/v1/chat/completions")
    @Before fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun cleanup() { store.clear(); Dispatchers.resetMain() }
    private suspend fun <T> waitReal(block: suspend () -> T): T = withContext(Dispatchers.Default) { withTimeout(8_000) { block() } }
    private suspend fun vm(block: suspend () -> AiApiResult): SettingsViewModel {
        val app = ApplicationProvider.getApplicationContext<DdlReminderApplication>()
        val api = object : AiApiClient {
            override suspend fun complete(config: AiSettings, systemPrompt: String, userPrompt: String) = block()
        }
        val vm = SettingsViewModel(app.reminderSettings, app.eventLogger, app.csvExportManager, app.presetRepository,
            object : AiSettingsRepository {
                override fun get() = AiSettings()
                override fun save(settings: AiSettings) = Unit
            }, api)
        store.put("settings", vm); waitReal { vm.loading.first { !it } }; return vm
    }
    private fun start(vm: SettingsViewModel, fast: String = "fast") = vm.runBenchmark("https://api.example/v1", "secret", fast, "strong", true)
    private suspend fun finished(vm: SettingsViewModel) = waitReal { vm.benchmark.first { !it.isRunning && it.message != null } }

    @Test fun `four completed requests release benchmark loading and keep all results`() = runTest {
        val calls = AtomicInteger(); val vm = vm { calls.incrementAndGet(); success }
        start(vm); val state = finished(vm)
        assertEquals(4, calls.get()); assertEquals(4, state.attempts.size); assertFalse(state.isRunning); assertFalse(vm.testing.value)
    }
    @Test fun `cancel button action cancels current coroutine and all remaining requests`() = runTest {
        val started = CompletableDeferred<Unit>(); val cancelled = CompletableDeferred<Unit>(); val calls = AtomicInteger()
        val vm = vm { calls.incrementAndGet(); started.complete(Unit); try { awaitCancellation() } finally { cancelled.complete(Unit) } }
        start(vm); waitReal { started.await() }; vm.cancelBenchmark(); waitReal { cancelled.await() }
        assertEquals(1, calls.get()); assertFalse(vm.benchmark.value.isRunning); assertEquals("测试已取消", vm.benchmark.value.message)
    }
    @Test fun `page leaving cancels in flight job and resets loading immediately`() = runTest {
        val started = CompletableDeferred<Unit>(); val cancelled = CompletableDeferred<Unit>(); val calls = AtomicInteger()
        val vm = vm { calls.incrementAndGet(); started.complete(Unit); try { awaitCancellation() } finally { cancelled.complete(Unit) } }
        start(vm); waitReal { started.await() }; vm.onSettingsPageLeft(); waitReal { cancelled.await() }
        assertEquals(1, calls.get()); assertFalse(vm.benchmark.value.isRunning); assertTrue(vm.benchmark.value.message!!.contains("离开"))
    }
    @Test fun `validation exception at coroutine start always resets benchmark flag`() = runTest {
        val calls = AtomicInteger(); val vm = vm { calls.incrementAndGet(); success }
        start(vm, fast = ""); val state = finished(vm)
        assertFalse(state.isRunning); assertTrue(state.message!!.contains("失败")); assertEquals(0, calls.get())
    }
    @Test fun `same model configuration is visible and never starts job`() = runTest {
        val calls = AtomicInteger(); val vm = vm { calls.incrementAndGet(); success }
        start(vm, fast = " strong ")
        assertEquals("两个模型配置相同", vm.benchmark.value.message); assertFalse(vm.benchmark.value.isRunning); assertEquals(0, calls.get())
    }
    @Test fun `rapid benchmark clicks cannot start concurrent runs`() = runTest {
        val started = CompletableDeferred<Unit>(); val calls = AtomicInteger()
        val vm = vm { calls.incrementAndGet(); started.complete(Unit); awaitCancellation() }
        repeat(20) { start(vm) }; waitReal { started.await() }
        assertEquals(1, calls.get()); assertTrue(vm.benchmark.value.isRunning); vm.cancelBenchmark()
    }
    @Test fun `internal CancellationException is rethrown and never leaves busy UI`() = runTest {
        val vm = vm { throw CancellationException() }
        start(vm); val state = finished(vm)
        assertFalse(state.isRunning); assertEquals("测试已取消", state.message)
    }
    @Test fun `late cancelled response cannot overwrite cancellation or start next sample`() = runTest {
        val started = CompletableDeferred<Unit>(); val gate = CompletableDeferred<Unit>(); val returned = CompletableDeferred<Unit>(); val calls = AtomicInteger()
        val vm = vm {
            calls.incrementAndGet()
            withContext(NonCancellable) { started.complete(Unit); gate.await() }
            returned.complete(Unit); success
        }
        start(vm); waitReal { started.await() }; vm.cancelBenchmark(); gate.complete(Unit)
        waitReal { returned.await(); delay(100) }
        assertEquals(1, calls.get()); assertEquals("测试已取消", vm.benchmark.value.message); assertFalse(vm.benchmark.value.isRunning)
    }
    @Test fun `ViewModel clear cancels benchmark and does not retain loading`() = runTest {
        val started = CompletableDeferred<Unit>(); val cancelled = CompletableDeferred<Unit>()
        val vm = vm { started.complete(Unit); try { awaitCancellation() } finally { cancelled.complete(Unit) } }
        start(vm); waitReal { started.await() }; store.clear()
        waitReal { cancelled.await(); vm.benchmark.first { !it.isRunning } }
        assertFalse(vm.benchmark.value.isRunning)
    }
}
