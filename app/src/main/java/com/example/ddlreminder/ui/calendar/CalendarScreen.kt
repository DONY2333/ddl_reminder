package com.example.ddlreminder.ui.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ddlreminder.BuildConfig
import com.example.ddlreminder.data.DdlEntity
import com.example.ddlreminder.data.isOverdue
import com.example.ddlreminder.ui.components.NotesSummary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
fun CalendarScreen(
    viewModel: CalendarViewModel,
    contentPadding: PaddingValues,
    notificationsEnabled: Boolean,
    exactAlarmsEnabled: Boolean,
    onRequestNotifications: () -> Unit,
    onRequestExactAlarm: () -> Unit,
    onAdd: (LocalDate) -> Unit,
    onEdit: (DdlEntity) -> Unit,
    onSettings: () -> Unit,
    onSmartAdd: () -> Unit,
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val ddls by viewModel.ddls.collectAsStateWithLifecycle()
    val selectedTasks = ddls.filter { it.localDate() == ui.selectedDate }
    val now = System.currentTimeMillis()
    val upcoming = upcomingWithin72Hours(ddls, now)

    LaunchedEffect(Unit) {
        while (true) {
            viewModel.checkForNewOverdueEvents()
            delay(60_000)
        }
    }

    Scaffold(
        modifier = Modifier.padding(contentPadding),
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onAdd(ui.selectedDate) },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("新增 DDL") },
            )
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("DDL Reminder", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "设置") }
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = onSmartAdd, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.AutoAwesome, null)
                    Spacer(Modifier.width(8.dp))
                    Text("智能添加")
                }
                Spacer(Modifier.height(8.dp))
                if (!notificationsEnabled) {
                    StatusBanner("通知未开启：任务会保存，但系统无法显示提醒。", "开启通知", onRequestNotifications)
                }
                if (!exactAlarmsEnabled) {
                    StatusBanner("精确定时未开启：提醒已降级，触发时间可能延迟。", "去设置", onRequestExactAlarm)
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    IconButton(onClick = { viewModel.changeMonth(-1) }) {
                        Icon(Icons.Default.ChevronLeft, "上个月")
                    }
                    Text(
                        "${ui.visibleMonth.year} 年 ${ui.visibleMonth.monthValue} 月",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    IconButton(onClick = { viewModel.changeMonth(1) }) {
                        Icon(Icons.Default.ChevronRight, "下个月")
                    }
                }
                MonthCalendar(ui.visibleMonth, ui.selectedDate, ddls, viewModel::selectDate)
                Spacer(Modifier.height(18.dp))
                Text("未来三天", style = MaterialTheme.typography.titleMedium)
                if (upcoming.isEmpty()) {
                    Text(
                        "未来三天暂无 DDL",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 14.dp),
                    )
                } else {
                    upcoming.forEach { ddl ->
                        UpcomingDdlRow(ddl, now) {
                            viewModel.logThreeDayItemClicked(ddl)
                            onEdit(ddl)
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(bottom = 14.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        ui.selectedDate.format(DateTimeFormatter.ofPattern("M 月 d 日 EEEE", Locale.CHINA)),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onAdd(ui.selectedDate) }) { Text("新增") }
                }
                HorizontalDivider()
            }

            if (selectedTasks.isEmpty()) {
                item {
                    Text(
                        "这一天还没有 DDL",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 28.dp),
                    )
                }
            } else {
                items(selectedTasks, key = { it.id }) { ddl ->
                    DdlRow(
                        ddl = ddl,
                        onToggle = { viewModel.toggleCompleted(ddl) },
                        onEdit = { onEdit(ddl) },
                        onDelete = { viewModel.delete(ddl) },
                    )
                    HorizontalDivider()
                }
            }

            if (BuildConfig.DEBUG) {
                item {
                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = viewModel::scheduleDebugTest,
                        enabled = notificationsEnabled,
                    ) {
                        Text("1 分钟后测试通知（Debug）")
                    }
                    Text(
                        "用于验证 AlarmManager 与系统通知；需先开启通知权限。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun UpcomingDdlRow(ddl: DdlEntity, nowMillis: Long, onClick: () -> Unit) {
    val local = Instant.ofEpochMilli(ddl.deadlineMillis).atZone(ZoneId.systemDefault())
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        TextButton(onClick = onClick, contentPadding = PaddingValues(0.dp)) {
            Text(ddl.title, color = MaterialTheme.colorScheme.onSurface)
        }
        Text(
            "${local.format(DateTimeFormatter.ofPattern("M 月 d 日 HH:mm"))} · ${remainingText(ddl.deadlineMillis, nowMillis)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        NotesSummary(ddl.notes)
    }
}

private fun remainingText(deadlineMillis: Long, nowMillis: Long): String {
    val minutes = ((deadlineMillis - nowMillis) / 60_000).coerceAtLeast(0)
    val deadline = Instant.ofEpochMilli(deadlineMillis).atZone(ZoneId.systemDefault())
    val today = Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault()).toLocalDate()
    return when {
        deadline.toLocalDate() == today.plusDays(1) -> "明天 ${deadline.format(DateTimeFormatter.ofPattern("HH:mm"))}"
        minutes < 60 -> "还有 ${minutes.coerceAtLeast(1)} 分钟"
        else -> "还有 ${minutes / 60} 小时"
    }
}

@Composable
private fun StatusBanner(message: String, action: String, onAction: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    ) {
        Row(
            Modifier.padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun DdlRow(ddl: DdlEntity, onToggle: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val overdue = ddl.isOverdue(System.currentTimeMillis())
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = ddl.isCompleted, onCheckedChange = { onToggle() })
        Column(
            Modifier.weight(1f).padding(horizontal = 6.dp),
        ) {
            TextButton(onClick = onEdit, contentPadding = PaddingValues(0.dp)) {
                Text(
                    ddl.title,
                    color = if (ddl.isCompleted) Color.Gray else MaterialTheme.colorScheme.onSurface,
                    textDecoration = if (ddl.isCompleted) TextDecoration.LineThrough else null,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ddl.localTimeText(), style = MaterialTheme.typography.bodySmall)
                if (overdue) {
                    Spacer(Modifier.width(10.dp))
                    Text("已逾期", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                }
            }
            NotesSummary(ddl.notes)
        }
        Column {
            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "更多操作") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("删除") },
                    leadingIcon = { Icon(Icons.Default.Delete, null) },
                    onClick = { menuOpen = false; confirmDelete = true },
                )
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除 DDL？") },
            text = { Text("“${ddl.title}”将从本机删除，尚未触发的提醒也会取消。") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }
}

private fun DdlEntity.localDate(): LocalDate =
    Instant.ofEpochMilli(deadlineMillis).atZone(ZoneId.systemDefault()).toLocalDate()

private fun DdlEntity.localTimeText(): String =
    Instant.ofEpochMilli(deadlineMillis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
