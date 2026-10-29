package com.example.ddlreminder.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.ddlreminder.reminder.ReminderOffset

@Composable
fun ReminderOptions(
    selected: Set<ReminderOffset>,
    onToggle: (ReminderOffset) -> Unit,
    onAdd: ((Long) -> Unit)? = null,
    enabled: Boolean = true,
) {
    var customValue by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("分钟") }
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        ReminderOffset.QUICK.forEach { offset ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = offset in selected,
                    onCheckedChange = { onToggle(offset) },
                    enabled = enabled,
                )
                Text(offset.displayName)
            }
        }
        selected.filter { it !in ReminderOffset.QUICK }.forEach { offset ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = true, onCheckedChange = { onToggle(offset) }, enabled = enabled)
                Text(offset.displayName)
            }
        }
        if (onAdd != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(customValue, { customValue = it.filter(Char::isDigit) }, label = { Text("数字") },
                    singleLine = true, modifier = Modifier.weight(1f), enabled = enabled)
                Column {
                    OutlinedButton(onClick = { menu = true }, enabled = enabled) { Text(unit) }
                    DropdownMenu(menu, { menu = false }) {
                        listOf("天", "小时", "分钟").forEach { option ->
                            DropdownMenuItem(text = { Text(option) }, onClick = { unit = option; menu = false })
                        }
                    }
                }
                OutlinedButton(onClick = {
                    val n = customValue.toLongOrNull() ?: return@OutlinedButton
                    onAdd(when (unit) { "天" -> n * 1440; "小时" -> n * 60; else -> n }); customValue = ""
                }, enabled = enabled) { Text("+ 添加") }
            }
            OutlinedButton(onClick = { onAdd(0) }, enabled = enabled && selected.none { it.minutes == 0L }) {
                Text("+ 事件发生时")
            }
        }
    }
}
