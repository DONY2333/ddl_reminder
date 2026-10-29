package com.example.ddlreminder.ui.settings

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.ddlreminder.reminder.ReminderOffset
import com.example.ddlreminder.settings.*
import com.example.ddlreminder.logging.CsvExportManager
import com.example.ddlreminder.logging.EventLogger
import com.example.ddlreminder.smartadd.*
import com.example.ddlreminder.diagnostics.StabilityDiagnostics
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class SettingsViewModel(
    private val repository: ReminderSettingsRepository,
    private val eventLogger: EventLogger,
    private val csvExportManager: CsvExportManager,
    private val presetRepository: ReminderPresetRepository,
    private val aiSettingsRepository: AiSettingsRepository,
    private val aiApiClient: AiApiClient,
    private val benchmarkRunner: ModelSpeedBenchmark = ModelSpeedBenchmark(aiApiClient),
) : ViewModel() {
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val handler = CoroutineExceptionHandler { _, error ->
        StabilityDiagnostics.record(StabilityDiagnostics.Node.RECOVERABLE_FAILURE, error = error)
        _message.value = "设置读取或操作失败，请返回后重试"
    }
    private fun loadFailure(error: Throwable) {
        if (error !is Exception || error is CancellationException) throw error
        StabilityDiagnostics.record(StabilityDiagnostics.Node.RECOVERABLE_FAILURE, error = error)
        _message.value = "设置读取失败，请返回后重试"
    }
    val defaults: StateFlow<Set<ReminderOffset>> = repository.defaults.catch {
        loadFailure(it); emit(ReminderOffset.LEGACY_DEFAULT)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReminderOffset.LEGACY_DEFAULT)
    val presets = presetRepository.presets.catch {
        loadFailure(it); emit(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _aiSettings = MutableStateFlow(AiSettings().forSingleModel())
    val aiSettings = _aiSettings.asStateFlow()
    // App 内共享最近一次诊断，Smart Add 的解析失败不会在进入设置页时消失。
    val aiRequestStatus = AiDiagnostics.latest
    val parsePerformance = AiDiagnostics.latestParse
    private val _benchmark = MutableStateFlow(BenchmarkState())
    val benchmark = _benchmark.asStateFlow()
    private var benchmarkJob: Job? = null
    @Volatile private var benchmarkGeneration = 0L
    private val _loading = MutableStateFlow(true)
    val loading = _loading.asStateFlow()
    private val _testing = MutableStateFlow(false)
    val testing = _testing.asStateFlow()
    private val _shareIntent = MutableSharedFlow<Intent>(extraBufferCapacity = 1)
    val shareIntent = _shareIntent.asSharedFlow()

    init {
        viewModelScope.launch(handler) {
            try { _aiSettings.value = withContext(Dispatchers.IO) { aiSettingsRepository.get().forSingleModel() } }
            finally { _loading.value = false }
        }
    }

    fun toggle(offset: ReminderOffset) = viewModelScope.launch(handler) {
        val current = repository.getDefaults()
        repository.setDefaults(if (offset in current) current - offset else current + offset)
    }

    fun addDefault(minutes: Long) = viewModelScope.launch(handler) {
        if (minutes !in 0..ReminderOffset.MAX_MINUTES) { _message.value = "提醒时间必须在 0 到 365 天之间"; return@launch }
        repository.setDefaults(repository.getDefaults() + ReminderOffset(minutes))
    }

    fun saveCustomPreset(id: String?, name: String, minutesCsv: String) = viewModelScope.launch(handler) {
        val offsets = ReminderOffset.normalize(minutesCsv.split(',').mapNotNull { it.trim().toLongOrNull() })
        if (name.isBlank() || offsets.isEmpty()) { _message.value = "请输入方案名称和分钟数"; return@launch }
        presetRepository.saveCustom(id, name, offsets)
        _message.value = "提醒方案已保存"
    }

    fun deletePreset(preset: ReminderPreset) = viewModelScope.launch(handler) {
        if (!preset.builtIn) presetRepository.deleteCustom(preset.id)
    }

    fun saveAiSettings(baseUrl: String, apiKey: String, model: String, accepted: Boolean) = viewModelScope.launch(handler) {
        val settings = AiSettings(baseUrl, apiKey, model, accepted, model, _aiSettings.value.strongModel).forSingleModel()
        withContext(Dispatchers.IO) { aiSettingsRepository.save(settings) }
        _aiSettings.value = settings
        _message.value = "智能解析设置已保存"
    }

    fun runBenchmark(baseUrl: String, apiKey: String, fastModel: String, strongModel: String, accepted: Boolean) {
        if (!com.example.ddlreminder.BuildConfig.DEBUG || _testing.value || _loading.value ||
            _benchmark.value.isRunning || benchmarkJob?.isCompleted == false) return
        if (!accepted) { _message.value = "请先确认 AI 隐私说明"; return }
        val config = AiSettings(baseUrl, apiKey, strongModel, accepted, fastModel, strongModel).normalized()
        if (config.fastModel.isNotBlank() && config.fastModel == config.strongModel) {
            _benchmark.value = BenchmarkState(message = "两个模型配置相同")
            return
        }
        val token = ++benchmarkGeneration
        _benchmark.value = BenchmarkState(isRunning = true, message = "正在准备测速…")
        // LAZY 先保存 Job 再启动，避免同步完成/异常发生在 Job 赋值之前。
        benchmarkJob = viewModelScope.launch(handler, start = CoroutineStart.LAZY) {
            try {
                withContext(Dispatchers.Default) {
                    benchmarkRunner.run(config) { progress ->
                        // 与取消操作在主线程串行，代次检查和赋值之间不能被旧回调穿插覆盖。
                        withContext(Dispatchers.Main.immediate) {
                            if (token == benchmarkGeneration) _benchmark.value = progress
                        }
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) {
                    if (token == benchmarkGeneration) _benchmark.value = _benchmark.value.copy(message = "测试已取消")
                    throw error
                }
                StabilityDiagnostics.record(StabilityDiagnostics.Node.RECOVERABLE_FAILURE, error = error)
                if (token == benchmarkGeneration) _benchmark.value = _benchmark.value.copy(message = "测速失败，请检查配置后重试")
            } finally {
                if (token == benchmarkGeneration) _benchmark.value = _benchmark.value.copy(isRunning = false)
                if (benchmarkJob === currentCoroutineContext()[Job]) benchmarkJob = null
            }
        }
        benchmarkJob?.start()
    }

    fun cancelBenchmark() = stopBenchmark("测试已取消")
    fun onSettingsPageLeft() = stopBenchmark("已离开页面，测试已取消")

    private fun stopBenchmark(message: String) {
        if (!_benchmark.value.isRunning && benchmarkJob?.isActive != true) return
        ++benchmarkGeneration // 先失效，防止取消清理/迟到响应覆盖取消状态或下一次测速。
        benchmarkJob?.cancel()
        _benchmark.value = _benchmark.value.copy(isRunning = false, message = message)
    }

    fun testAiConnection(baseUrl: String, apiKey: String, model: String) {
        if (_testing.value || _loading.value || _benchmark.value.isRunning || benchmarkJob?.isCompleted == false) return
        _testing.value = true
        viewModelScope.launch(handler) {
            val config = AiSettings(baseUrl, apiKey, model, privacyAccepted = true).normalized()
            try {
                when (val result = aiApiClient.complete(config, "只输出JSON。", "只输出 {\"ok\":true}")) {
                    is AiApiResult.Success -> {
                        var exceptionClass: String? = null
                        val valid = withContext(Dispatchers.Default) {
                            try {
                                val outer = org.json.JSONObject(result.body)
                                val content = outer.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.opt("content") as? String
                                    ?: result.body
                                org.json.JSONObject(AiNaturalLanguageParser.extractJsonObject(content)).optBoolean("ok")
                            } catch (error: Exception) {
                                if (error is CancellationException) throw error
                                exceptionClass = error.javaClass.simpleName
                                false
                            }
                        }
                        AiDiagnostics.publish(AiRequestStatus(result.statusCode, result.latencyMs, safeModel(config),
                            result.endpoint, if (valid) null else AiApiErrorType.INVALID_CONTENT, exceptionClass,
                            transportSuccess = true, failureStage = if (valid) null else AiFailureStage.CONTENT_EXTRACTION_FAILED,
                            attemptCount = result.attemptCount))
                        _message.value = if (valid) "AI连接正常（仅连接测试，不代表日程解析成功）" else "连接成功，但返回格式异常"
                    }
                    is AiApiResult.Failure -> {
                        AiDiagnostics.publish(AiRequestStatus(result.statusCode, result.latencyMs, safeModel(config),
                            result.endpoint, result.type, result.exceptionClassName, transportSuccess = false,
                            failureStage = if (result.statusCode == null) AiFailureStage.NETWORK_ERROR else AiFailureStage.HTTP_ERROR,
                            attemptCount = result.attemptCount))
                        _message.value = "AI请求失败，请查看最近一次诊断"
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                StabilityDiagnostics.record(StabilityDiagnostics.Node.RECOVERABLE_FAILURE, error = error)
                AiDiagnostics.publish(AiRequestStatus(null, null, safeModel(config), "（请求未完成）",
                    AiApiErrorType.UNKNOWN, error.javaClass.simpleName, transportSuccess = false,
                    failureStage = AiFailureStage.NETWORK_ERROR))
                _message.value = "AI请求失败，请查看最近一次诊断"
            } finally { _testing.value = false }
        }
    }

    private fun safeModel(config: AiSettings): String =
        if (config.apiKey.isEmpty()) config.modelName else config.modelName.replace(config.apiKey, "[REDACTED]")

    fun exportUsageData() = viewModelScope.launch(handler) {
        val intent = withContext(Dispatchers.IO) { csvExportManager.createShareIntent() }
        if (intent == null) _message.value = "导出失败，请稍后重试" else _shareIntent.emit(intent)
    }

    fun clearUsageData() = viewModelScope.launch(handler) {
        withContext(Dispatchers.IO) { eventLogger.deleteAllLogs() }
        _message.value = "匿名使用记录已清空"
    }

    fun clearMessage() { _message.value = null }

    class Factory(
        private val repository: ReminderSettingsRepository,
        private val eventLogger: EventLogger,
        private val csvExportManager: CsvExportManager,
        private val presetRepository: ReminderPresetRepository,
        private val aiSettingsRepository: AiSettingsRepository,
        private val aiApiClient: AiApiClient,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(repository, eventLogger, csvExportManager, presetRepository, aiSettingsRepository, aiApiClient) as T
    }
}
