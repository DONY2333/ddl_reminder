package com.example.ddlreminder.ui.editor

import com.example.ddlreminder.diagnostics.recoverable

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.ddlreminder.data.DdlRepository
import com.example.ddlreminder.data.OperationResult
import com.example.ddlreminder.data.reminderOffsets
import com.example.ddlreminder.reminder.ReminderOffset
import com.example.ddlreminder.settings.ReminderPreset
import com.example.ddlreminder.settings.ReminderPresetRepository
import com.example.ddlreminder.logging.EventLogger
import com.example.ddlreminder.logging.EventTypes
import com.example.ddlreminder.settings.ReminderSettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.CancellationException
import com.example.ddlreminder.diagnostics.StabilityDiagnostics
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

data class EditorUiState(
    val title: String = "",
    val notes: String = "",
    val date: LocalDate = LocalDate.now(),
    val time: LocalTime = LocalTime.of(23, 59),
    val loading: Boolean = false,
    val error: String? = null,
    val saved: Boolean = false,
    val persistedWithWarning: Boolean = false,
    val reminderOffsets: Set<ReminderOffset> = ReminderOffset.LEGACY_DEFAULT,
    val presets: List<ReminderPreset> = emptyList(),
    val reminderSettingsLoaded: Boolean = false,
)

class DdlEditorViewModel(
    private val repository: DdlRepository,
    private val reminderSettings: ReminderSettingsRepository,
    private val presetRepository: ReminderPresetRepository,
    private val eventLogger: EventLogger,
    private val ddlId: Long?,
    initialDate: LocalDate,
) : ViewModel() {
    private val _uiState = MutableStateFlow(EditorUiState(date = initialDate))
    val uiState = _uiState.asStateFlow()
    private val handler = CoroutineExceptionHandler { _, error ->
        StabilityDiagnostics.record(StabilityDiagnostics.Node.RECOVERABLE_FAILURE, error = error)
        _uiState.value = _uiState.value.copy(loading = false, error = "页面加载或操作失败，请返回首页重试")
    }

    init {
        viewModelScope.launch(handler) {
            presetRepository.presets.catch { error ->
                if (error !is Exception || error is CancellationException) throw error
                StabilityDiagnostics.record(StabilityDiagnostics.Node.RECOVERABLE_FAILURE, error = error)
                _uiState.value = _uiState.value.copy(error = "提醒方案读取失败，请返回后重试")
                emit(emptyList())
            }.collect { presets -> _uiState.value = _uiState.value.copy(presets = presets) }
        }
        viewModelScope.launch(handler) {
            if (ddlId != null) {
                val ddl = repository.get(ddlId)
                if (ddl == null) {
                    _uiState.value = _uiState.value.copy(
                        error = "任务不存在或已被删除",
                        reminderSettingsLoaded = true,
                    )
                } else {
                    val local = Instant.ofEpochMilli(ddl.deadlineMillis).atZone(ZoneId.systemDefault())
                    _uiState.value = _uiState.value.copy(
                        title = ddl.title,
                        notes = ddl.notes.orEmpty(),
                        date = local.toLocalDate(),
                        time = local.toLocalTime(),
                        reminderOffsets = ddl.reminderOffsets(),
                        reminderSettingsLoaded = true,
                    )
                }
            } else {
                _uiState.value = _uiState.value.copy(
                    reminderOffsets = reminderSettings.getDefaults(),
                    reminderSettingsLoaded = true,
                )
            }
        }
    }

    fun setTitle(value: String) { _uiState.value = _uiState.value.copy(title = value, error = null) }
    fun setNotes(value: String) { _uiState.value = _uiState.value.copy(notes = value, error = null) }
    fun setDate(value: LocalDate) { _uiState.value = _uiState.value.copy(date = value, error = null) }
    fun setTime(value: LocalTime) { _uiState.value = _uiState.value.copy(time = value, error = null) }
    fun toggleReminder(offset: ReminderOffset) {
        val current = _uiState.value.reminderOffsets
        _uiState.value = _uiState.value.copy(
            reminderOffsets = if (offset in current) current - offset else current + offset,
            error = null,
        )
    }
    fun addReminder(minutes: Long) {
        if (minutes !in 0..ReminderOffset.MAX_MINUTES) {
            _uiState.value = _uiState.value.copy(error = "提醒时间必须在 0 到 365 天之间")
            return
        }
        _uiState.value = _uiState.value.copy(reminderOffsets = _uiState.value.reminderOffsets + ReminderOffset(minutes), error = null)
        if (ReminderOffset(minutes) !in ReminderOffset.QUICK) viewModelScope.launch(handler) {
            recoverable { eventLogger.log(EventTypes.CUSTOM_REMINDER_USED, metadataJson = "{\"offset_minutes\":$minutes}") }
        }
    }
    fun applyPreset(preset: ReminderPreset) {
        _uiState.value = _uiState.value.copy(reminderOffsets = preset.offsets)
        viewModelScope.launch(handler) { recoverable {
            eventLogger.log(EventTypes.REMINDER_PRESET_USED, metadataJson = "{\"built_in\":${preset.builtIn},\"reminder_count\":${preset.offsets.size}}")
        } }
    }

    fun restoreDefaultReminders() {
        viewModelScope.launch(handler) {
            _uiState.value = _uiState.value.copy(reminderOffsets = reminderSettings.getDefaults())
        }
    }

    fun save() {
        if (_uiState.value.loading) return
        _uiState.value = _uiState.value.copy(loading = true, error = null)
        viewModelScope.launch(handler) {
            try {
            val state = _uiState.value
            val deadline = state.date.atTime(state.time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val now = System.currentTimeMillis()
            val result = withContext(Dispatchers.IO) { if (ddlId == null) {
                repository.create(state.title, deadline, state.reminderOffsets, now, state.notes)
            } else {
                repository.update(ddlId, state.title, deadline, state.reminderOffsets, now, state.notes)
            } }
            _uiState.value = when (result) {
                is OperationResult.Error -> state.copy(loading = false, error = result.message)
                is OperationResult.Success -> state.copy(
                    loading = false,
                    error = result.warning,
                    saved = result.warning == null,
                    persistedWithWarning = result.warning != null,
                )
            }
            } finally {
                _uiState.value = _uiState.value.copy(loading = false)
            }
        }
    }

    fun acknowledgeWarningAndClose() { _uiState.value = _uiState.value.copy(saved = true) }

    class Factory(
        private val repository: DdlRepository,
        private val reminderSettings: ReminderSettingsRepository,
        private val presetRepository: ReminderPresetRepository,
        private val eventLogger: EventLogger,
        private val ddlId: Long?,
        private val initialDate: LocalDate,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            DdlEditorViewModel(repository, reminderSettings, presetRepository, eventLogger, ddlId, initialDate) as T
    }
}
