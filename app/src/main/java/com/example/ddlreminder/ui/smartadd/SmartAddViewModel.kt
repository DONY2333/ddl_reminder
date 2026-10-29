package com.example.ddlreminder.ui.smartadd

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import com.example.ddlreminder.data.BatchDdlInput
import com.example.ddlreminder.data.DdlRepository
import com.example.ddlreminder.diagnostics.StabilityDiagnostics
import com.example.ddlreminder.diagnostics.recoverable
import com.example.ddlreminder.logging.EventLogger
import com.example.ddlreminder.logging.EventTypes
import com.example.ddlreminder.reminder.ReminderOffset
import com.example.ddlreminder.settings.AiSettingsRepository
import com.example.ddlreminder.settings.ReminderSettingsRepository
import com.example.ddlreminder.smartadd.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

enum class SmartAddPhase { Idle, Parsing, Preview, ParseFailed, Creating, CreateSucceeded }

data class SmartAddUiState(
    val input: String = "",
    val drafts: List<ScheduleDraft> = emptyList(),
    val phase: SmartAddPhase = SmartAddPhase.Idle,
    val message: String? = null,
    val parseWarnings: List<String> = emptyList(),
    val invalidEventCount: Int = 0,
) {
    val parsing get() = phase == SmartAddPhase.Parsing
    val created get() = phase == SmartAddPhase.CreateSucceeded
    val busy get() = parsing || phase == SmartAddPhase.Creating
}

/** 参数只包含计数/布尔信息，不允许把原文、标题或备忘传给埋点。 */
object SmartAddEventMetadata {
    fun parseResult(eventCount: Int, usedAi: Boolean, fallback: Boolean): String =
        "{\"event_count\":$eventCount,\"used_ai\":$usedAi,\"fallback\":$fallback}"
}

class SmartAddViewModel(
    private val repository: DdlRepository,
    private val defaults: ReminderSettingsRepository,
    private val aiSettings: AiSettingsRepository,
    private val aiApiClient: AiApiClient,
    private val eventLogger: EventLogger,
    private val csvParser: ScheduleCsvParser = ScheduleCsvParser(),
    private val savedState: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    private val _uiState = MutableStateFlow(SmartAddUiState(input = savedState["smart_input"] ?: ""))
    val uiState = _uiState.asStateFlow()
    private var activeJob: Job? = null
    private var generation = 0L
    // 仅本页内存去重；不写 Room/DataStore/SavedState，不缓存 AI 响应。
    private data class PreviewKey(val text: String, val settings: com.example.ddlreminder.settings.AiSettings, val localOnly: Boolean)
    private var previewKey: PreviewKey? = null

    init {
        log(EventTypes.SMART_ADD_OPENED)
        StabilityDiagnostics.record(StabilityDiagnostics.Node.UI_STATE, state = SmartAddPhase.Idle.name)
    }

    private fun setState(value: SmartAddUiState) {
        if (_uiState.value.phase != value.phase)
            StabilityDiagnostics.record(StabilityDiagnostics.Node.UI_STATE, state = value.phase.name)
        _uiState.value = value
    }

    fun setInput(value: String) {
        if (_uiState.value.busy) return
        if (value != _uiState.value.input) previewKey = null
        // 避免超长粘贴文本导致 Activity SavedState Bundle 超过 Binder 限制；当前页面文本不截断。
        if (value.length <= 64_000) savedState["smart_input"] = value else savedState.remove<String>("smart_input")
        setState(_uiState.value.copy(input = value, message = null))
    }

    fun parseText() = parseText(useLocal = false)
    fun useLocalParser() = parseText(useLocal = true)

    private fun parseText(useLocal: Boolean) {
        val text = _uiState.value.input
        if (text.isBlank() || _uiState.value.busy) return
        log(EventTypes.NATURAL_LANGUAGE_PARSE_REQUESTED)
        launchWork(SmartAddPhase.Parsing) { token ->
            val config = withContext(Dispatchers.IO) { aiSettings.get() }
            val key = PreviewKey(text, config.normalized(), useLocal)
            if (previewKey == key && _uiState.value.drafts.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                if (token == generation) setState(_uiState.value.copy(phase = SmartAddPhase.Preview))
                return@launchWork
            }
            val parser = TieredNaturalLanguageParser(if (useLocal) config.copy(privacyAccepted = false) else config, aiApiClient,
                onAiStarted = { log(EventTypes.AI_PARSE_STARTED) })
            val result = withContext(Dispatchers.Default) {
                parser.parse(text, System.currentTimeMillis(), ZoneId.systemDefault())
            }
            applyResult(result, token)
            if (token == generation && result.drafts.isNotEmpty() && result.aiErrorType == null) previewKey = key
            if (result.usedAi) {
                log(if (result.fellBackToLocal || result.aiErrorType != null) EventTypes.AI_PARSE_FAILED else EventTypes.AI_PARSE_SUCCEEDED,
                    "{\"event_count\":${result.drafts.size},\"failure_stage\":\"${result.aiErrorType ?: "none"}\"}")
                if (result.fellBackToLocal) log(EventTypes.AI_PARSE_FALLBACK_USED)
            }
            log(if (result.drafts.isNotEmpty()) EventTypes.NATURAL_LANGUAGE_PARSE_SUCCEEDED else EventTypes.NATURAL_LANGUAGE_PARSE_FAILED,
                result.performance?.eventMetadata() ?: SmartAddEventMetadata.parseResult(result.drafts.size, result.usedAi, result.fellBackToLocal))
        }
    }

    fun parseCsv(bytes: ByteArray) = parseCsvSource { bytes }

    /** 文件读取和 CSV 解析都不在 Activity Result 回调的主线程执行。 */
    fun parseCsvSource(read: () -> ByteArray) {
        if (_uiState.value.busy) return
        previewKey = null
        log(EventTypes.CSV_IMPORT_STARTED)
        launchWork(SmartAddPhase.Parsing) { token ->
            val bytes = withContext(Dispatchers.IO) { read().also { require(it.size <= 2_000_000) } }
            val result = withContext(Dispatchers.Default) { csvParser.parse(bytes) }
            applyResult(result, token)
            log(EventTypes.CSV_IMPORT_PREVIEWED, "{\"event_count\":${result.drafts.size}}")
        }
    }

    fun importFailed() {
        if (!_uiState.value.busy) setState(_uiState.value.copy(phase = SmartAddPhase.ParseFailed, message = "文件读取失败，请重新选择"))
    }

    private fun launchWork(phase: SmartAddPhase, work: suspend (Long) -> Unit) {
        if (_uiState.value.busy) return
        val token = ++generation
        setState(_uiState.value.copy(phase = phase, message = null))
        activeJob = viewModelScope.launch {
            try {
                work(token)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                StabilityDiagnostics.record(StabilityDiagnostics.Node.RECOVERABLE_FAILURE, error = error)
                if (token == generation) setState(_uiState.value.copy(
                    phase = SmartAddPhase.ParseFailed, message = "处理失败，请重试或使用本地解析"))
            } finally {
                if (token == generation) {
                    // 任何未完成路径（包括取消）都必须退出忙碌状态。
                    if (_uiState.value.busy) setState(_uiState.value.copy(
                        phase = if (_uiState.value.drafts.isEmpty()) SmartAddPhase.Idle else SmartAddPhase.Preview))
                    activeJob = null
                }
            }
        }
    }

    private suspend fun applyResult(result: ParseResult, token: Long) {
        val marked = withContext(Dispatchers.IO) {
            val offsets = withTimeoutOrNull(5_000) { defaults.getDefaults() }
                ?: throw java.io.IOException("Defaults unavailable")
            val existing = withTimeoutOrNull(5_000) { repository.allDdls.first() }
                ?: throw java.io.IOException("Tasks unavailable")
            val withDefaults = result.drafts.map {
                if (it.remindersExplicit) it else it.copy(reminderOffsets = offsets)
            }
            DuplicateDetector.mark(withDefaults, existing)
        }
        currentCoroutineContext().ensureActive()
        if (token != generation) return
        setState(_uiState.value.copy(drafts = marked,
            phase = if (marked.isEmpty()) SmartAddPhase.ParseFailed else SmartAddPhase.Preview,
            message = result.errorMessage ?: if (marked.isEmpty()) "未识别到可创建的日程，请修改输入后重试" else null,
            parseWarnings = result.parseWarnings, invalidEventCount = result.invalidEventCount))
    }

    /** 路由离开或配置变化时取消并使旧 token 失效，旧响应不能覆盖新页面。 */
    fun onPageLeft() {
        generation++
        previewKey = null
        activeJob?.cancel()
        activeJob = null
        if (_uiState.value.busy) setState(_uiState.value.copy(
            phase = if (_uiState.value.drafts.isEmpty()) SmartAddPhase.Idle else SmartAddPhase.Preview))
    }

    fun remove(index: Int) { setState(_uiState.value.copy(drafts = _uiState.value.drafts.filterIndexed { i, _ -> i != index })) }
    fun updateTitle(index: Int, title: String) = update(index) { it.copy(title = title) }
    fun updateDate(index: Int, date: LocalDate) = update(index) { draft ->
        val time = draft.exactTime ?: draft.deadlineMillis?.let { java.time.Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalTime() }
        draft.copy(deadlineMillis = time?.let { date.atTime(it).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }, date = date, ambiguousDate = false,
            warnings = draft.warnings.filterNot { it == "日期需要确认" || it.contains("已经过去") },
            hasBlockingError = time == null, canCreate = time != null && !draft.needsTimeConfirmation)
    }
    fun updateTime(index: Int, time: LocalTime) = update(index) { draft ->
        val date = draft.date ?: draft.deadlineMillis?.let { java.time.Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() } ?: LocalDate.now()
        draft.copy(deadlineMillis = date.atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), exactTime = time,
            timePrecision = com.example.ddlreminder.smartadd.TimePrecision.EXACT, needsTimeConfirmation = false, canCreate = true,
            usedDefaultTime = false, warnings = draft.warnings.filterNot { it.contains("默认设为") || it.contains("具体时间") || it.contains("已经过去") }, hasBlockingError = false)
    }
    fun updateNotes(index: Int, notes: String) = update(index) { it.copy(notes = notes) }
    fun toggleReminder(index: Int, offset: ReminderOffset) = update(index) {
        it.copy(reminderOffsets = if (offset in it.reminderOffsets) it.reminderOffsets - offset else it.reminderOffsets + offset)
    }
    fun addReminder(index: Int, minutes: Long) {
        if (minutes !in 0..ReminderOffset.MAX_MINUTES) { setState(_uiState.value.copy(message = "提醒时间必须在 0 到 365 天之间")); return }
        update(index) { it.copy(reminderOffsets = it.reminderOffsets + ReminderOffset(minutes)) }
    }
    fun ignoreDuplicate(index: Int) = update(index) { it.copy(possibleDuplicate = false) }


    fun confirm() {
        if (_uiState.value.busy) return
        val now = System.currentTimeMillis()
        val valid = _uiState.value.drafts.filter { it.isValid && !it.possibleDuplicate && (it.deadlineMillis ?: 0) > now }
        if (valid.isEmpty()) { setState(_uiState.value.copy(message = "请先修正、移除或确认可能重复的项目")); return }
        launchWork(SmartAddPhase.Creating) { token ->
            val result = withContext(Dispatchers.IO) {
                repository.createBatch(valid.map { BatchDdlInput(it.title, it.deadlineMillis!!, it.reminderOffsets, it.notes) }, now)
            }
            currentCoroutineContext().ensureActive()
            if (token == generation) {
                log(EventTypes.CSV_IMPORT_COMPLETED, "{\"created_count\":${result.createdCount}}")
                setState(_uiState.value.copy(phase = if (result.createdCount > 0) SmartAddPhase.CreateSucceeded else SmartAddPhase.Preview,
                    message = if (result.failedCount > 0) "已创建 ${result.createdCount} 项，${result.failedCount} 项失败" else result.warning))
            }
        }
    }

    fun voiceStarted() = log(EventTypes.VOICE_INPUT_STARTED)
    fun voiceSucceeded() = log(EventTypes.VOICE_INPUT_SUCCEEDED)
    fun voiceFailed() {
        setState(_uiState.value.copy(message = "当前设备暂时无法使用语音识别，请使用文字输入。"))
        log(EventTypes.VOICE_INPUT_FAILED)
    }
    private fun update(index: Int, block: (ScheduleDraft) -> ScheduleDraft) {
        if (_uiState.value.busy) return
        setState(_uiState.value.copy(drafts = _uiState.value.drafts.mapIndexed { i, item -> if (i == index) block(item) else item }))
    }
    private fun log(type: String, metadata: String? = null) = viewModelScope.launch {
        recoverable { eventLogger.log(type, metadataJson = metadata) }
    }

    class Factory(
        private val repository: DdlRepository,
        private val defaults: ReminderSettingsRepository,
        private val aiSettings: AiSettingsRepository,
        private val aiApiClient: AiApiClient,
        private val eventLogger: EventLogger,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: androidx.lifecycle.viewmodel.CreationExtras): T =
            SmartAddViewModel(repository, defaults, aiSettings, aiApiClient, eventLogger,
                savedState = extras.createSavedStateHandle()) as T

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SmartAddViewModel(repository, defaults, aiSettings, aiApiClient, eventLogger) as T
    }
}
