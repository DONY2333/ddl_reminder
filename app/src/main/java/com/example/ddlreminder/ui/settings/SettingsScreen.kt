package com.example.ddlreminder.ui.settings

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.activity.compose.BackHandler
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.ddlreminder.smartadd.BenchmarkState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ddlreminder.ui.components.ReminderOptions
import com.example.ddlreminder.diagnostics.recoverable
import com.example.ddlreminder.smartadd.AiRequestStatus
import com.example.ddlreminder.smartadd.ParsePerformance

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    contentPadding: PaddingValues,
    onClose: () -> Unit,
) {
    val defaults by viewModel.defaults.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val presets by viewModel.presets.collectAsStateWithLifecycle()
    val ai by viewModel.aiSettings.collectAsStateWithLifecycle()
    val aiStatus by viewModel.aiRequestStatus.collectAsStateWithLifecycle()
    val performance by viewModel.parsePerformance.collectAsStateWithLifecycle()
    val benchmark by viewModel.benchmark.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val connectionTesting by viewModel.testing.collectAsStateWithLifecycle()
    val testing = connectionTesting || benchmark.isRunning
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(viewModel, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) viewModel.onSettingsPageLeft()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.onSettingsPageLeft()
        }
    }
    BackHandler(enabled = benchmark.isRunning) { viewModel.onSettingsPageLeft(); onClose() }
    var confirmClear by remember { mutableStateOf(false) }
    var presetName by remember { mutableStateOf("") }
    var presetMinutes by remember { mutableStateOf("") }
    var editingPresetId by remember { mutableStateOf<String?>(null) }
    var baseUrl by remember(ai) { mutableStateOf(ai.baseUrl) }
    var apiKey by remember(ai) { mutableStateOf(ai.apiKey) }
    var model by remember(ai) { mutableStateOf(ai.currentModel) }
    var privacyAccepted by remember(ai) { mutableStateOf(ai.privacyAccepted) }

    LaunchedEffect(Unit) {
        viewModel.shareIntent.collect { intent ->
            recoverable { context.startActivity(Intent.createChooser(intent, "导出使用数据")) }.onFailure {
                android.widget.Toast.makeText(context, "暂时无法分享，请稍后重试", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
    LaunchedEffect(message) {
        message?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show()
            viewModel.clearMessage()
        }
    }
    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        TopAppBar(
            title = { Text("设置") },
            navigationIcon = {
                IconButton(onClick = { viewModel.onSettingsPageLeft(); onClose() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                }
            },
        )
        Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
            Text("默认提醒时间", style = MaterialTheme.typography.titleLarge)
            Text(
                "仅作为之后新建 DDL 的初始值，不会修改已有任务。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
            )
            ReminderOptions(defaults, viewModel::toggle, viewModel::addDefault)
            Text("提醒方案", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 28.dp))
            Text("方案是模板，应用后会复制到 DDL；之后修改方案不会改变历史任务。",
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp))
            presets.forEach { preset ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${preset.name} · ${preset.offsets.joinToString { it.displayName }}", modifier = Modifier.weight(1f))
                    if (!preset.builtIn) {
                        TextButton(onClick = {
                            editingPresetId = preset.id; presetName = preset.name
                            presetMinutes = preset.offsets.joinToString(",") { it.minutes.toString() }
                        }) { Text("编辑") }
                        TextButton(onClick = { viewModel.deletePreset(preset) }) { Text("删除") }
                    }
                }
            }
            OutlinedTextField(presetName, { presetName = it }, label = { Text("自定义方案名称") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(presetMinutes, { presetMinutes = it }, label = { Text("提醒分钟数，例如 10080,1440,0") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = {
                viewModel.saveCustomPreset(editingPresetId, presetName, presetMinutes)
                editingPresetId = null; presetName = ""; presetMinutes = ""
            }) { Text(if (editingPresetId == null) "新建提醒方案" else "保存方案修改") }

            Text("智能解析", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 28.dp))
            Text("未配置时仍可使用本地规则解析。AI API Key 使用 Android Keystore 加密保存在本机。",
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp))
            if (loading) Text("正在读取本机设置…")
            OutlinedTextField(baseUrl, { baseUrl = it }, enabled = !loading && !testing, label = { Text("API 地址") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(apiKey, { apiKey = it }, label = { Text("API Key") },
                enabled = !loading && !testing,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth())
            OutlinedTextField(model, { model = it }, enabled = !loading && !testing, label = { Text("AI 模型") }, placeholder = { Text("gpt-6-luna，以中转模型 ID 为准") }, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(privacyAccepted, { privacyAccepted = it })
                Text("智能解析会将你本次主动输入的文字发送到你配置的 AI API 服务。请确认你信任该服务提供方。DDL Reminder 不会自动上传已有 DDL 或匿名行为日志。")
            }
            Button(onClick = { viewModel.saveAiSettings(baseUrl, apiKey, model, privacyAccepted) }, enabled = !loading && !testing) { Text("保存智能解析设置") }
            TextButton(onClick = { viewModel.testAiConnection(baseUrl, apiKey, model.ifBlank { com.example.ddlreminder.settings.AiSettings.DEFAULT_MODEL }) }, enabled = !loading && !testing && privacyAccepted) { Text(if (testing) "正在请求…" else "测试 AI 连接") }
            AdvancedAiDiagnostics(aiStatus, performance)
            Text(
                "数据与隐私",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = 28.dp),
            )
            Text(
                "DDL Reminder 会在本机保存匿名使用记录，用于了解功能是否好用。" +
                    "不记录任务标题或任何个人身份信息，数据不会自动上传。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
            )
            Button(onClick = viewModel::exportUsageData) { Text("导出使用数据") }
            TextButton(onClick = { confirmClear = true }) { Text("清空使用数据") }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("确定清空本机的匿名使用记录？") },
            text = { Text("此操作不会删除任何 DDL。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    viewModel.clearUsageData()
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            },
        )
    }
}

@Composable
fun AdvancedAiDiagnostics(status: AiRequestStatus?, performance: ParsePerformance?) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column {
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "收起高级诊断" else "高级诊断")
        }
        if (expanded) {
            if (status == null && performance == null) {
                Text("暂无请求记录", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            performance?.let {
                Text(it.diagnosticText(), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            status?.let {
                Text(it.diagnosticText(), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun BenchmarkStatus(state: BenchmarkState, onCancel: () -> Unit) {
    Column {
        if (state.displayText().isNotEmpty()) Text(state.displayText(), style = MaterialTheme.typography.bodySmall)
        if (state.isRunning) TextButton(onClick = onCancel) { Text("取消测试") }
    }
}
