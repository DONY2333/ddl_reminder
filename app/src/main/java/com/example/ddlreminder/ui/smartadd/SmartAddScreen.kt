package com.example.ddlreminder.ui.smartadd

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ddlreminder.diagnostics.recoverable
import com.example.ddlreminder.smartadd.CsvSampleManager
import com.example.ddlreminder.smartadd.ScheduleDraft
import com.example.ddlreminder.smartadd.SpeechSupport
import com.example.ddlreminder.smartadd.TimePeriod
import com.example.ddlreminder.ui.components.ReminderOptions
import com.example.ddlreminder.ui.components.NotesSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun SmartAddScreen(viewModel: SmartAddViewModel, onClose: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    DisposableEffect(viewModel) { onDispose { viewModel.onPageLeft() } }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (text == null) viewModel.voiceFailed() else { viewModel.setInput(text); viewModel.voiceSucceeded() }
    }
    val launchVoice = {
        if (!SpeechSupport.isAvailable(context)) viewModel.voiceFailed()
        else recoverable { viewModel.voiceStarted(); voice.launch(SpeechSupport.intent()) }
            .onFailure { viewModel.voiceFailed() }
        Unit
    }
    val audioPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchVoice() else viewModel.voiceFailed()
    }
    val csvPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            viewModel.parseCsvSource {
                context.contentResolver.openInputStream(it)?.use { stream ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val size = stream.read(buffer)
                        if (size < 0) break
                        require(output.size() + size <= 2_000_000)
                        output.write(buffer, 0, size)
                    }
                    output.toByteArray()
                } ?: throw java.io.IOException("Cannot open CSV")
            }
        }
    }
    LaunchedEffect(state.phase) { if (state.created) onClose() }

    SmartAddLayout(state = state, onInput = viewModel::setInput, onParse = viewModel::parseText,
        onLocal = viewModel::useLocalParser, onClose = onClose,
        onVoice = {
            recoverable {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) launchVoice()
                else audioPermission.launch(Manifest.permission.RECORD_AUDIO)
            }.onFailure { viewModel.voiceFailed() }
        },
        onImport = { recoverable { csvPicker.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain")) }.onFailure { viewModel.importFailed() } },
        onSample = { scope.launch {
            val intent = withContext(Dispatchers.IO) { recoverable { CsvSampleManager(context).createShareIntent() }.getOrNull() }
            if (intent == null) viewModel.importFailed()
            else recoverable { context.startActivity(Intent.createChooser(intent, "下载 CSV 示例")) }.onFailure { viewModel.importFailed() }
        } },
        onConfirm = viewModel::confirm,
        draftContent = { index, draft ->
            if (state.busy) Text(draft.title) else DraftEditor(index, draft, viewModel)
        })
}

/** 本页独立拥有系统栏 Insets：固定 topBar + 唯一 LazyColumn，body 只消费 innerPadding 一次。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartAddLayout(
    state: SmartAddUiState,
    onInput: (String) -> Unit = {},
    onParse: () -> Unit = {},
    onLocal: () -> Unit = {},
    onClose: () -> Unit = {},
    onVoice: () -> Unit = {},
    onImport: () -> Unit = {},
    onSample: () -> Unit = {},
    onConfirm: () -> Unit = {},
    modifier: Modifier = Modifier,
    topBarInsets: WindowInsets = TopAppBarDefaults.windowInsets,
    draftContent: @Composable (Int, ScheduleDraft) -> Unit = { _, draft -> Text(draft.title) },
) {
    // 计时只由显式 Parsing 状态驱动，不因重组重新请求；离开状态即取消延迟。
    var slowParsing by remember { mutableStateOf(false) }
    LaunchedEffect(state.phase) {
        slowParsing = false
        if (state.phase == SmartAddPhase.Parsing) { delay(1_500); slowParsing = true }
    }
    Scaffold(
        modifier = modifier.fillMaxSize().imePadding().testTag("smart_page"),
        topBar = {
            TopAppBar(title = { Text("智能添加") }, windowInsets = topBarInsets,
                modifier = Modifier.testTag("smart_topbar"),
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } })
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding).consumeWindowInsets(innerPadding).testTag("smart_list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item("input") {
                OutlinedTextField(state.input, onInput, enabled = !state.busy,
                    label = { Text("例如：下周三晚上8点交高数作业，提前一天和一小时提醒我") },
                    minLines = 4, modifier = Modifier.fillMaxWidth().testTag("smart_input"))
            }
            item("actions") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onVoice, enabled = !state.busy) { Icon(Icons.Default.Mic, null); Text("语音输入") }
                    Button(onClick = onParse, enabled = !state.busy && state.input.isNotBlank()) { Text("解析日程") }
                }
            }
            item("status") {
                when (state.phase) {
                    SmartAddPhase.Idle -> Text("输入日程后点击解析，确认预览后才会保存。")
                    SmartAddPhase.Parsing -> Column {
                        CircularProgressIndicator(Modifier.size(24.dp).testTag("smart_parsing_spinner"))
                        Text(if (slowParsing) "正在理解通知内容…" else "正在识别日程…")
                    }
                    SmartAddPhase.Creating -> Column {
                        CircularProgressIndicator(Modifier.size(24.dp))
                        Text("正在保存日程…")
                    }
                    SmartAddPhase.CreateSucceeded -> Text("已创建日程，正在返回首页…")
                    SmartAddPhase.ParseFailed -> Column {
                        Text(state.message ?: "解析失败，请重试", color = MaterialTheme.colorScheme.error)
                        Row {
                            TextButton(onClick = onParse) { Text("重试") }
                            TextButton(onClick = onLocal) { Text("使用本地解析") }
                        }
                    }
                    SmartAddPhase.Preview -> {
                        val creatable = state.drafts.count { it.isValid && !it.possibleDuplicate }
                        val needsConfirmation = state.drafts.count { !it.isValid } + state.invalidEventCount
                        Column {
                            Text("识别到 $creatable 个有效日程", style = MaterialTheme.typography.titleLarge)
                            Text("$needsConfirmation 个结果需要检查", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (state.message != null && state.phase != SmartAddPhase.ParseFailed) item("message") {
                Column {
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                    if (!state.busy) Row {
                        TextButton(onClick = onParse) { Text("重试") }
                        TextButton(onClick = onLocal) { Text("使用本地解析") }
                    }
                }
            }
            if (state.parseWarnings.isNotEmpty()) item("warnings") {
                state.parseWarnings.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            itemsIndexed(state.drafts) { index, draft -> draftContent(index, draft) }
            if (state.drafts.isNotEmpty()) item("confirm") {
                Button(onClick = onConfirm, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("smart_confirm")) {
                    Text("确认创建 ${state.drafts.count { it.isValid && !it.possibleDuplicate }} 个日程")
                }
            }
            item("csv") {
                HorizontalDivider()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onImport, enabled = !state.busy) { Text("导入 CSV") }
                    TextButton(onClick = onSample, enabled = !state.busy) { Text("下载 CSV 示例") }
                }
            }
        }
    }
}

@Composable
private fun DraftEditor(index: Int, draft: ScheduleDraft, vm: SmartAddViewModel) {
    val context = LocalContext.current
    val local = draft.deadlineMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()) }
    var expanded by remember(draft.sourceText, draft.sourceLine) { mutableStateOf(false) }
    val date = draft.date ?: local?.toLocalDate()
    val timeText = draft.exactTime?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: when (draft.timePeriod) {
        TimePeriod.MORNING -> "上午"; TimePeriod.NOON -> "中午"; TimePeriod.AFTERNOON -> "下午"
        TimePeriod.EVENING -> "晚上"; TimePeriod.NIGHT -> "夜间"; TimePeriod.NONE -> local?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: "时间待确认"
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(draft.title.ifBlank { "未命名日程" }, style = MaterialTheme.typography.titleMedium)
            Text("${date?.format(DateTimeFormatter.ofPattern("M月d日")) ?: "日期待确认"} · $timeText")
            if (draft.needsTimeConfirmation) Text("⚠ 请确认具体时间", color = MaterialTheme.colorScheme.error)
            NotesSummary(draft.notes)
            Text("普通 DDL · ${if (draft.remindersExplicit) "识别到 ${draft.reminderOffsets.size} 个提醒" else "默认提醒"}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            draft.warnings.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
            if (draft.possibleDuplicate) {
                Text("可能已存在", color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { vm.ignoreDuplicate(index) }) { Text("仍然导入") }
            }
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起编辑" else "编辑") }
            if (expanded) {
                OutlinedTextField(draft.title, { vm.updateTitle(index, it) }, label = { Text("标题") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val selected = date ?: LocalDate.now()
                        DatePickerDialog(context, { _, y, m, d -> vm.updateDate(index, LocalDate.of(y, m + 1, d)) },
                            selected.year, selected.monthValue - 1, selected.dayOfMonth).show()
                    }) { Text(date?.toString() ?: "选择日期") }
                    OutlinedButton(onClick = {
                        val selected = draft.exactTime ?: local?.toLocalTime() ?: java.time.LocalTime.of(9, 0)
                        TimePickerDialog(context, { _, h, m -> vm.updateTime(index, java.time.LocalTime.of(h, m)) }, selected.hour, selected.minute, true).show()
                    }) { Text(draft.exactTime?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: "选择具体时间") }
                }
                OutlinedTextField(draft.notes.orEmpty(), { vm.updateNotes(index, it) }, label = { Text("备忘（可选）") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                ReminderOptions(draft.reminderOffsets, { vm.toggleReminder(index, it) }, { vm.addReminder(index, it) })
                TextButton(onClick = { vm.remove(index) }) { Text("移除此项") }
            }
        }
    }
}
