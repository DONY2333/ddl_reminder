package com.example.ddlreminder.ui.editor

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ddlreminder.ui.components.ReminderOptions
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DdlEditorScreen(
    viewModel: DdlEditorViewModel,
    contentPadding: PaddingValues,
    onClose: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var presetMenu by remember { mutableStateOf(false) }
    LaunchedEffect(state.saved) { if (state.saved) onClose() }
    val openDatePicker = {
        DatePickerDialog(
            context,
            { _, year, month, day -> viewModel.setDate(LocalDate.of(year, month + 1, day)) },
            state.date.year,
            state.date.monthValue - 1,
            state.date.dayOfMonth,
        ).show()
    }
    val openTimePicker = {
        TimePickerDialog(
            context,
            { _, hour, minute -> viewModel.setTime(LocalTime.of(hour, minute)) },
            state.time.hour,
            state.time.minute,
            true,
        ).show()
    }

    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        TopAppBar(
            title = { Text("DDL") },
            navigationIcon = {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            },
        )
        Column(
            Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            OutlinedTextField(
                value = state.title,
                onValueChange = viewModel::setTitle,
                label = { Text("任务名称") },
                singleLine = true,
                enabled = !state.loading,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.notes,
                onValueChange = viewModel::setNotes,
                label = { Text("备忘（可选）") },
                minLines = 3,
                enabled = !state.loading,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.date.format(DateTimeFormatter.ISO_LOCAL_DATE),
                onValueChange = {},
                readOnly = true,
                label = { Text("截止日期") },
                trailingIcon = {
                    IconButton(onClick = openDatePicker) {
                        Icon(Icons.Default.CalendarToday, "选择日期")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.time.format(DateTimeFormatter.ofPattern("HH:mm")),
                onValueChange = {},
                readOnly = true,
                label = { Text("截止时间") },
                trailingIcon = {
                    IconButton(onClick = openTimePicker) {
                        Icon(Icons.Default.Schedule, "选择时间")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Column {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("提醒时间", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = viewModel::restoreDefaultReminders,
                        enabled = state.reminderSettingsLoaded && !state.loading,
                    ) { Text("恢复默认设置") }
                }
                TextButton(onClick = { presetMenu = true }) { Text("使用提醒方案") }
                DropdownMenu(presetMenu, { presetMenu = false }) {
                    state.presets.forEach { preset ->
                        DropdownMenuItem(text = { Text(preset.name) }, onClick = {
                            viewModel.applyPreset(preset); presetMenu = false
                        })
                    }
                }
                ReminderOptions(
                    selected = state.reminderOffsets,
                    onToggle = viewModel::toggleReminder,
                    onAdd = viewModel::addReminder,
                    enabled = state.reminderSettingsLoaded && !state.loading,
                )
                if (state.reminderOffsets.isEmpty()) {
                    Text(
                        "此任务不会发送提前提醒",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            state.error?.let { error ->
                Text(error, color = MaterialTheme.colorScheme.error)
                if (state.persistedWithWarning) {
                    Button(onClick = viewModel::acknowledgeWarningAndClose) { Text("知道了，返回首页") }
                }
            }
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = viewModel::save,
                enabled = !state.loading && !state.persistedWithWarning && state.reminderSettingsLoaded,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.loading) {
                    CircularProgressIndicator(Modifier.height(20.dp), strokeWidth = 2.dp)
                } else {
                    Text("保存")
                }
            }
        }
    }
}
