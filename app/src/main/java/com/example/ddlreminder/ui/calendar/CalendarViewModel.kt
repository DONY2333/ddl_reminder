package com.example.ddlreminder.ui.calendar

import com.example.ddlreminder.diagnostics.recoverable

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.ddlreminder.data.DdlEntity
import com.example.ddlreminder.data.DdlRepository
import com.example.ddlreminder.data.OperationResult
import com.example.ddlreminder.logging.EventLogger
import com.example.ddlreminder.logging.EventTypes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import com.example.ddlreminder.diagnostics.StabilityDiagnostics
import java.time.LocalDate
import java.time.YearMonth

data class CalendarUiState(
    val visibleMonth: YearMonth = YearMonth.now(),
    val selectedDate: LocalDate = LocalDate.now(),
)

class CalendarViewModel(
    private val repository: DdlRepository,
    private val eventLogger: EventLogger,
) : ViewModel() {
    private val _message = MutableStateFlow<String?>(null)
    private val handler = CoroutineExceptionHandler { _, error ->
        StabilityDiagnostics.record(StabilityDiagnostics.Node.RECOVERABLE_FAILURE, error = error)
        _message.value = "操作失败，请返回首页重试"
    }
    val ddls: StateFlow<List<DdlEntity>> = repository.allDdls.catch { error ->
        if (error !is Exception || error is CancellationException) throw error
        StabilityDiagnostics.record(StabilityDiagnostics.Node.RECOVERABLE_FAILURE, error = error)
        _message.value = "本机任务读取失败，请重新进入应用重试"
        emit(emptyList())
    }.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList(),
    )
    private val _uiState = MutableStateFlow(CalendarUiState())
    val uiState = _uiState.asStateFlow()
    val message = _message.asStateFlow()

    fun selectDate(date: LocalDate) {
        _uiState.value = _uiState.value.copy(selectedDate = date, visibleMonth = YearMonth.from(date))
    }

    fun changeMonth(delta: Long) {
        val month = _uiState.value.visibleMonth.plusMonths(delta)
        _uiState.value = _uiState.value.copy(
            visibleMonth = month,
            selectedDate = selectedDateForMonth(month, LocalDate.now()),
        )
    }

    fun toggleCompleted(ddl: DdlEntity) = execute {
        repository.setCompleted(ddl.id, !ddl.isCompleted, System.currentTimeMillis())
    }

    fun delete(ddl: DdlEntity) = execute { repository.delete(ddl.id) }

    fun synchronize() = execute { repository.synchronizeReminders(System.currentTimeMillis()) }

    fun scheduleDebugTest() = handle(repository.scheduleDebugTest(System.currentTimeMillis()), "测试通知已安排，将在约 1 分钟后触发")

    fun logThreeDayItemClicked(ddl: DdlEntity) {
        viewModelScope.launch(handler) {
            recoverable {
                eventLogger.log(
                    eventType = EventTypes.THREE_DAY_ITEM_CLICKED,
                    ddlId = ddl.id,
                    deadlineMillis = ddl.deadlineMillis,
                )
            }
        }
    }

    fun checkForNewOverdueEvents() {
        viewModelScope.launch(handler) { repository.recordOverdueEvents(System.currentTimeMillis()) }
    }

    fun clearMessage() { _message.value = null }

    private fun execute(block: suspend () -> OperationResult) {
        viewModelScope.launch(handler) { handle(block()) }
    }

    private fun handle(result: OperationResult, successMessage: String? = null) {
        _message.value = when (result) {
            is OperationResult.Error -> result.message
            is OperationResult.Success -> result.warning ?: successMessage
        }
    }

    class Factory(
        private val repository: DdlRepository,
        private val eventLogger: EventLogger,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            CalendarViewModel(repository, eventLogger) as T
    }
}
