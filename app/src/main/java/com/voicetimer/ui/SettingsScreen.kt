package com.voicetimer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.voicetimer.RemindViewModel
import com.voicetimer.remind.ScheduleHours

// Расписание дня: в какое время показывать «неточные» напоминания.
@Composable
fun SettingsScreen(viewModel: RemindViewModel) {
    val schedule by viewModel.schedule.collectAsState()

    Column(
        Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text("Расписание дня", style = MaterialTheme.typography.titleLarge)
        Text(
            "Когда сказано только время суток («вечером») или просто день («завтра»), " +
                "напоминание сработает в указанный час.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        HourRow("Утром", schedule.morning) { viewModel.updateSchedule(schedule.copy(morning = it)) }
        HourRow("Днём", schedule.day) { viewModel.updateSchedule(schedule.copy(day = it)) }
        HourRow("Вечером", schedule.evening) { viewModel.updateSchedule(schedule.copy(evening = it)) }
        HourRow("Ночью", schedule.night) { viewModel.updateSchedule(schedule.copy(night = it)) }
        HourRow("По умолчанию (просто «завтра»)", schedule.defaultHour) {
            viewModel.updateSchedule(schedule.copy(defaultHour = it))
        }

        HorizontalDivider()

        Text("Распознавание речи", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = schedule.cloudWhenOnline,
                onCheckedChange = { viewModel.updateSchedule(schedule.copy(cloudWhenOnline = it)) }
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Облачное распознавание (Google) при интернете")
                Text(
                    "Лучше качество; без интернета — локальная модель Vosk",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        HorizontalDivider()

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = schedule.calendarByDefault,
                onCheckedChange = { viewModel.updateSchedule(schedule.copy(calendarByDefault = it)) }
            )
            Spacer(Modifier.width(12.dp))
            Text("Добавлять напоминания в Google-календарь")
        }
    }
}

@Composable
private fun HourRow(label: String, hour: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        IconButton(onClick = { onChange((hour - 1 + 24) % 24) }) { Text("−", style = MaterialTheme.typography.titleLarge) }
        Text(
            "%02d:00".format(hour),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.widthIn(min = 56.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        IconButton(onClick = { onChange((hour + 1) % 24) }) { Text("+", style = MaterialTheme.typography.titleLarge) }
    }
}
