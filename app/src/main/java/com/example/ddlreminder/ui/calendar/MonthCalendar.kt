package com.example.ddlreminder.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ddlreminder.data.DdlEntity
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun MonthCalendar(
    month: YearMonth,
    selectedDate: LocalDate,
    ddls: List<DdlEntity>,
    onDateSelected: (LocalDate) -> Unit,
) {
    val first = month.atDay(1)
    val gridStart = first.minusDays((first.dayOfWeek.value - 1).toLong())
    val ddlDates = ddls.groupBy {
        java.time.Instant.ofEpochMilli(it.deadlineMillis).atZone(ZoneId.systemDefault()).toLocalDate()
    }
    Column {
        Row(Modifier.fillMaxWidth()) {
            java.time.DayOfWeek.entries.forEach { day ->
                Text(
                    day.getDisplayName(TextStyle.SHORT, Locale.CHINA),
                    modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        repeat(6) { week ->
            Row(Modifier.fillMaxWidth()) {
                repeat(7) { weekday ->
                    val date = gridStart.plusDays((week * 7 + weekday).toLong())
                    val tasks = ddlDates[date].orEmpty()
                    DayCell(
                        date = date,
                        inMonth = YearMonth.from(date) == month,
                        selected = date == selectedDate,
                        isToday = date == LocalDate.now(),
                        isPast = date < LocalDate.now(),
                        hasActive = tasks.any { !it.isCompleted },
                        hasOnlyCompleted = tasks.isNotEmpty() && tasks.all { it.isCompleted },
                        onClick = { onDateSelected(date) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    inMonth: Boolean,
    selected: Boolean,
    isToday: Boolean,
    isPast: Boolean,
    hasActive: Boolean,
    hasOnlyCompleted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.aspectRatio(1f).clickable(onClick = onClick).padding(3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(34.dp).clip(CircleShape).background(
                when {
                    isToday -> MaterialTheme.colorScheme.primaryContainer
                    selected && isPast -> MaterialTheme.colorScheme.surfaceVariant
                    selected -> MaterialTheme.colorScheme.primary
                    else -> Color.Transparent
                },
            ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                date.dayOfMonth.toString(),
                color = when {
                    isToday -> MaterialTheme.colorScheme.onPrimaryContainer
                    isPast -> MaterialTheme.colorScheme.onSurface.copy(alpha = .38f)
                    selected -> MaterialTheme.colorScheme.onPrimary
                    !inMonth -> MaterialTheme.colorScheme.onSurface.copy(alpha = .35f)
                    else -> MaterialTheme.colorScheme.onSurface
                },
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
            )
        }
        Box(
            Modifier.padding(top = 2.dp).size(5.dp).clip(CircleShape).background(
                when {
                    hasActive -> MaterialTheme.colorScheme.error
                    hasOnlyCompleted -> Color.Gray
                    else -> Color.Transparent
                },
            ),
        )
    }
}
