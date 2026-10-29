package com.example.ddlreminder.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Colors = lightColorScheme(
    primary = Color(0xFF315DA8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E3FF),
    secondary = Color(0xFF565E71),
    error = Color(0xFFBA1A1A),
    background = Color(0xFFFFFDF8),
    surface = Color(0xFFFFFDF8),
)

@Composable
fun DdlReminderTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
