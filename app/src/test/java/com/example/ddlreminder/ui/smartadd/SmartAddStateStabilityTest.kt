package com.example.ddlreminder.ui.smartadd

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.example.ddlreminder.data.*
import com.example.ddlreminder.logging.NoOpEventLogger
import com.example.ddlreminder.reminder.AlarmScheduler
import com.example.ddlreminder.reminder.ReminderOffset
import com.example.ddlreminder.settings.*
import com.example.ddlreminder.smartadd.*
import com.example.ddlreminder.ui.editor.DdlEditorViewModel
import com.example.ddlreminder.ui.calendar.CalendarViewModel
import com.example.ddlreminder.diagnostics.recoverable
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SmartAddStateStabilityTest {
    private val store = ViewModelStore()
    @Before fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { store.clear(); Dispatchers.resetMain() }
    private val success = AiApiResult.Success("""{"events":[{"title":"event","date":"2099-11-20","exact_time":"18:00"}]}""", 200, 10, "https://api.example.com/v1/chat/completions")

    private fun vm(client: AiApiClient = client { success }, dao: DdlDao = StateDao(), saved: SavedStateHandle = SavedStateHandle(), defaults: ReminderSettingsRepository = StateDefaults(), settings: AiSettingsRepository? = null): SmartAddViewModel {
        val vm = SmartAddViewModel(DdlRepository(dao, StateAlarms), defaults, settings ?: object : AiSettingsRepository {
            override fun get() = AiSettings("https://api.example.com/v1", "secret", "model", true)
            override fun save(settings: AiSettings) = Unit
        }, client, NoOpEventLogger, savedState = saved)
        store.put("smart-${System.nanoTime()}", vm)
        // 此套件测 HTTP 生命周期，显式用复杂输入；简单句在 V1.2.5 应为零 HTTP。
        vm.setInput(saved.get<String>("smart_input") ?: "2099年11月20日18:00开会，地点教学楼")
        return vm
    }
    private fun client(block: suspend () -> AiApiResult) = object : AiApiClient {
        override suspend fun complete(config: AiSettings, systemPrompt: String, userPrompt: String) = block()
    }
    private suspend fun <T> realWait(block: suspend () -> T): T = withContext(Dispatchers.Default) { withTimeout(8_000) { block() } }
    private suspend fun phase(vm: SmartAddViewModel, expected: SmartAddPhase) = realWait { vm.uiState.first { it.phase == expected } }

    @Test fun `rapid clicks start only one request`() = runTest {
        val calls = AtomicInteger()
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<AiApiResult>()
        val vm = vm(client { calls.incrementAndGet(); started.complete(Unit); gate.await() })
        repeat(50) { vm.parseText() }
        realWait { started.await() }
        assertEquals(1, calls.get()); assertEquals(SmartAddPhase.Parsing, vm.uiState.value.phase)
        gate.complete(success)
        assertEquals(1, phase(vm, SmartAddPhase.Preview).drafts.size)
    }
    @Test fun `page left invalidates old request and new preview wins`() = runTest {
        val started = CompletableDeferred<Unit>()
        val oldGate = CompletableDeferred<AiApiResult>()
        val calls = AtomicInteger()
        val vm = vm(client {
            if (calls.incrementAndGet() == 1) withContext(NonCancellable) { started.complete(Unit); oldGate.await() }
            else success
        })
        vm.parseText(); realWait { started.await() }
        vm.onPageLeft()
        assertEquals(SmartAddPhase.Idle, vm.uiState.value.phase)
        vm.parseText(); phase(vm, SmartAddPhase.Preview)
        oldGate.complete(success.copy(body = "not json"))
        realWait { delay(100) }
        assertEquals(SmartAddPhase.Preview, vm.uiState.value.phase)
        assertEquals(1, vm.uiState.value.drafts.size)
    }
    @Test fun `AI failure keeps input and leaves loading with visible error`() = runTest {
        val vm = vm(client { AiApiResult.Failure(AiApiErrorType.NETWORK, "failed", endpoint = "https://api.example.com/v1/chat/completions") })
        vm.setInput("无法解析的文字")
        vm.parseText()
        val state = phase(vm, SmartAddPhase.ParseFailed)
        assertEquals("无法解析的文字", state.input); assertFalse(state.busy); assertNotNull(state.message)
    }
    @Test fun `Luna military notice reaches preview as one event without any strong request`() = runTest {
        val models = mutableListOf<String>()
        val api = object : AiApiClient {
            override suspend fun complete(config: AiSettings, systemPrompt: String, userPrompt: String): AiApiResult {
                models += config.modelName
                return success.copy(body = """{"events":[{"title":"军事理论课考试","date":"2099-10-17","time_period":"morning","notes":"A/B卷，闭卷，1小时，单选、不定项、简答和论述"}]}""")
            }
        }
        val settings = object : AiSettingsRepository {
            override fun get() = AiSettings("https://api.example/v1", "secret", "", true, "", "must-never-call")
            override fun save(settings: AiSettings) = Unit
        }
        val vm = vm(api, settings = settings)
        vm.setInput("军事理论课考试暂定于2099年10月17日上午举行。本次分A/B卷，闭卷，时长1小时，题型包括单选、不定项、简答和论述。")
        vm.parseText()
        val state = phase(vm, SmartAddPhase.Preview)
        assertEquals(listOf("gpt-6-luna"), models); assertEquals(1, state.drafts.size)
        assertEquals("军事理论课考试", state.drafts.single().title)
        assertTrue(state.drafts.single().notes!!.contains("闭卷")); assertFalse(state.busy)
    }
    @Test fun `AI HTTP failure with useful local result shows fallback preview and stops loading`() = runTest {
        val calls = AtomicInteger()
        val vm = vm(client {
            calls.incrementAndGet()
            AiApiResult.Failure(AiApiErrorType.FORBIDDEN, "failed", 403, 20, "https://api.example/v1/chat/completions")
        })
        vm.parseText()
        val state = phase(vm, SmartAddPhase.Preview)
        assertEquals(1, calls.get()); assertFalse(state.busy)
        assertEquals("AI解析失败，本次使用本地解析结果", state.message)
        assertTrue(state.drafts.isNotEmpty())
    }
    @Test fun `unexpected client exception does not leave page busy`() = runTest {
        val vm = vm(client { throw IllegalStateException("private data") })
        vm.setInput("无法解析的文字"); vm.parseText()
        assertFalse(phase(vm, SmartAddPhase.ParseFailed).busy)
    }
    @Test fun `CSV source failure becomes visible error`() = runTest {
        val vm = vm(); vm.parseCsvSource { throw IOException("private file name") }
        val state = phase(vm, SmartAddPhase.ParseFailed)
        assertNotNull(state.message); assertFalse(state.busy); assertTrue(state.input.isNotEmpty())
    }
    @Test fun `invalid CSV becomes error not blank page`() = runTest {
        val vm = vm(); vm.parseCsv("wrong,csv\n1,2".toByteArray())
        assertNotNull(phase(vm, SmartAddPhase.ParseFailed).message)
    }
    @Test fun `Room read failure becomes visible UI error`() = runTest {
        val vm = vm(dao = StateDao(readFails = true)); vm.parseText()
        val state = phase(vm, SmartAddPhase.ParseFailed)
        assertNotNull(state.message); assertFalse(state.busy)
    }
    @Test fun `defaults read failure becomes visible UI error`() = runTest {
        val vm = vm(defaults = StateDefaults(fail = true)); vm.parseText()
        assertNotNull(phase(vm, SmartAddPhase.ParseFailed).message)
    }
    @Test fun `cancellation is not converted to parse failed and loading resets`() = runTest {
        val vm = vm(client { throw CancellationException() }); vm.parseText()
        val state = phase(vm, SmartAddPhase.Idle)
        assertFalse(state.busy); assertTrue(state.input.isNotBlank())
    }
    @Test fun `SavedState restores input without automatically making request`() = runTest {
        val calls = AtomicInteger()
        val vm = vm(client { calls.incrementAndGet(); success }, saved = SavedStateHandle(mapOf("smart_input" to "restored input")))
        assertEquals("restored input", vm.uiState.value.input)
        assertEquals(SmartAddPhase.Idle, vm.uiState.value.phase)
        assertEquals(0, calls.get())
    }
    @Test fun `very large input stays intact in memory without oversized saved bundle`() = runTest {
        val saved = SavedStateHandle()
        val vm = vm(saved = saved)
        val text = "large input ".repeat(10_000)
        vm.setInput(text)
        assertEquals(text, vm.uiState.value.input)
        assertNull(saved.get<String>("smart_input"))
    }
    @Test fun `reenter after leaving parsing allows a new request`() = runTest {
        val gate = CompletableDeferred<AiApiResult>()
        val calls = AtomicInteger()
        val vm = vm(client { if (calls.incrementAndGet() == 1) gate.await() else success })
        vm.parseText()
        realWait { while (calls.get() == 0) delay(1) }
        vm.onPageLeft(); vm.parseText()
        assertEquals(1, phase(vm, SmartAddPhase.Preview).drafts.size)
        assertEquals(2, calls.get())
    }
    @Test fun `Preset flow failure is isolated in editor with visible error`() = runTest {
        val editor = DdlEditorViewModel(DdlRepository(StateDao(), StateAlarms), StateDefaults(), object : ReminderPresetRepository {
            override val presets: Flow<List<ReminderPreset>> = flow { throw IOException("private preset") }
            override suspend fun saveCustom(id: String?, name: String, offsets: Set<ReminderOffset>) = "id"
            override suspend fun deleteCustom(id: String) = Unit
        }, NoOpEventLogger, null, LocalDate.of(2099, 11, 20))
        store.put("editor", editor)
        realWait { editor.uiState.first { it.error != null } }
        assertFalse(editor.uiState.value.loading)
    }
    @Test fun `Calendar Room flow failure has visible message not uncaught exception`() = runTest {
        val calendar = CalendarViewModel(DdlRepository(StateDao(readFails = true), StateAlarms), NoOpEventLogger)
        store.put("calendar", calendar)
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { calendar.ddls.collect() }
        realWait { calendar.message.first { it != null } }
        collector.cancel()
        assertNotNull(calendar.message.value)
    }
    @Test(expected = CancellationException::class) fun `recoverable helper must not swallow cancellation`() {
        recoverable { throw CancellationException() }
    }
    @Test fun `same input and config reuses current edited preview without HTTP`() = runTest {
        val calls = AtomicInteger(); val vm = vm(client { calls.incrementAndGet(); success })
        vm.parseText(); phase(vm, SmartAddPhase.Preview)
        vm.updateTitle(0, "edited locally")
        vm.parseText(); phase(vm, SmartAddPhase.Preview)
        assertEquals(1, calls.get()); assertEquals("edited locally", vm.uiState.value.drafts.single().title)
    }
    @Test fun `changing input invalidates page reuse even if changed back`() = runTest {
        val calls = AtomicInteger(); val vm = vm(client { calls.incrementAndGet(); success })
        vm.parseText(); phase(vm, SmartAddPhase.Preview)
        val original = vm.uiState.value.input
        vm.setInput("different"); vm.setInput(original); vm.parseText(); phase(vm, SmartAddPhase.Preview)
        assertEquals(2, calls.get())
    }
    @Test fun `leaving page invalidates preview reuse`() = runTest {
        val calls = AtomicInteger(); val vm = vm(client { calls.incrementAndGet(); success })
        vm.parseText(); phase(vm, SmartAddPhase.Preview); vm.onPageLeft(); vm.parseText(); phase(vm, SmartAddPhase.Preview)
        assertEquals(2, calls.get())
    }
    @Test fun `configuration change invalidates preview reuse`() = runTest {
        var config = AiSettings("https://api.example.com/v1", "secret", "model", true)
        val settings = object : AiSettingsRepository {
            override fun get() = config
            override fun save(settings: AiSettings) { config = settings }
        }
        val calls = AtomicInteger(); val vm = vm(client { calls.incrementAndGet(); success }, settings = settings)
        vm.parseText(); phase(vm, SmartAddPhase.Preview)
        config = config.copy(fastModel = "new-model")
        vm.parseText(); phase(vm, SmartAddPhase.Preview)
        assertEquals(2, calls.get())
    }
    @Test fun `failed parse is not cached and retry can request again`() = runTest {
        val calls = AtomicInteger(); val vm = vm(client { calls.incrementAndGet(); success.copy(body = "invalid") })
        vm.setInput("无法解析的文字"); vm.parseText(); phase(vm, SmartAddPhase.ParseFailed)
        vm.parseText(); phase(vm, SmartAddPhase.ParseFailed); assertEquals(2, calls.get())
    }
    @Test fun `CSV preview cannot be reused as previous natural language preview`() = runTest {
        val calls = AtomicInteger(); val vm = vm(client { calls.incrementAndGet(); success })
        vm.parseText(); phase(vm, SmartAddPhase.Preview)
        vm.parseCsvSource { throw IOException() }; phase(vm, SmartAddPhase.ParseFailed)
        vm.parseText(); phase(vm, SmartAddPhase.Preview); assertEquals(2, calls.get())
    }
}

private class StateDefaults(private val fail: Boolean = false) : ReminderSettingsRepository {
    override val defaults = flowOf(emptySet<ReminderOffset>())
    override suspend fun getDefaults(): Set<ReminderOffset> { if (fail) throw IOException(); return emptySet() }
    override suspend fun setDefaults(offsets: Set<ReminderOffset>) = Unit
}
private object StateAlarms : AlarmScheduler {
    override suspend fun schedule(ddl: DdlEntity, nowMillis: Long): String? = null
    override fun cancel(ddl: DdlEntity) = Unit
    override fun scheduleDebugTest(triggerAtMillis: Long): String? = null
    override fun canScheduleExact() = true
}
private class StateDao(private val readFails: Boolean = false) : DdlDao {
    override fun observeAll(): Flow<List<DdlEntity>> = flow { if (readFails) throw IOException(); emit(emptyList()) }
    override suspend fun getById(id: Long): DdlEntity? = null
    override suspend fun getActiveFuture(nowMillis: Long) = emptyList<DdlEntity>()
    override suspend fun getOverdue(nowMillis: Long) = emptyList<DdlEntity>()
    override suspend fun insert(ddl: DdlEntity) = 1L
    override suspend fun update(ddl: DdlEntity) = Unit
    override suspend fun delete(ddl: DdlEntity) = Unit
}
