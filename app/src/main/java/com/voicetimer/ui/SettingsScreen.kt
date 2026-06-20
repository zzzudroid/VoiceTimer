package com.voicetimer.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.voicetimer.BuildConfig
import com.voicetimer.RemindViewModel
import com.voicetimer.remind.ReliabilityStatus
import com.voicetimer.remind.ScheduleHours
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Расписание дня: в какое время показывать «неточные» напоминания.
@Composable
fun SettingsScreen(viewModel: RemindViewModel) {
    val schedule by viewModel.schedule.collectAsState()
    val context = LocalContext.current

    // Статус операций бэкапа → тост
    val backupMessage by viewModel.backupMessage.collectAsState()
    LaunchedEffect(backupMessage) {
        backupMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.clearBackupMessage()
        }
    }

    // Системные диалоги выбора файла (SAF): создание копии и открытие копии
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let { viewModel.exportTo(it) } }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.importFrom(it) } }

    var showRestoreConfirm by remember { mutableStateOf(false) }
    val defaultBackupName = remember {
        "voicetimer-backup-${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}.json"
    }

    if (showRestoreConfirm) {
        AlertDialog(
            onDismissRequest = { showRestoreConfirm = false },
            title = { Text("Восстановить из копии?") },
            text = { Text("Текущие напоминания и настройки будут заменены данными из выбранного файла.") },
            confirmButton = {
                TextButton(onClick = {
                    showRestoreConfirm = false
                    // фильтр «*/*»: файл копии после пересылки через Drive/мессенджер
                    // может прийти с другим MIME, иначе он был бы недоступен для выбора
                    importLauncher.launch(arrayOf("*/*"))
                }) { Text("Выбрать файл") }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreConfirm = false }) { Text("Отмена") }
            }
        )
    }

    // Статус надёжности перечитываем при каждом возврате на экран
    // (пользователь мог только что выдать разрешение в системных настройках).
    val lifecycleOwner = LocalLifecycleOwner.current
    var statusTick by remember { mutableStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) statusTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val exactOk = remember(statusTick) { ReliabilityStatus.canScheduleExact(context) }
    val batteryOk = remember(statusTick) { ReliabilityStatus.isIgnoringBatteryOptimizations(context) }

    Column(
        Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text("Надёжность напоминаний", style = MaterialTheme.typography.titleLarge)
        if (exactOk && batteryOk) {
            Text(
                "✓ Всё настроено — напоминания зазвонят даже в спящем режиме.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!exactOk) {
            ReliabilityWarning(
                text = "Точные будильники запрещены — сигнал может задержаться или не прозвучать. " +
                    "Разрешите «Будильники и напоминания».",
                button = "Разрешить точные будильники",
                onClick = { ReliabilityStatus.requestExactAlarm(context) }
            )
        }
        if (!batteryOk) {
            ReliabilityWarning(
                text = "Включена оптимизация батареи — система может «усыпить» приложение и " +
                    "пропустить напоминание. Отключите оптимизацию для VoiceTimer.",
                button = "Отключить оптимизацию батареи",
                onClick = { ReliabilityStatus.requestIgnoreBatteryOptimizations(context) }
            )
        }

        HorizontalDivider()

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

        HorizontalDivider()

        Text("Резервная копия", style = MaterialTheme.typography.titleMedium)
        Text(
            "Сохраните напоминания и настройки в файл, чтобы вернуть их после " +
                "переустановки. Будильники выставятся заново при восстановлении.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { exportLauncher.launch(defaultBackupName) }) {
                Text("Сохранить копию")
            }
            OutlinedButton(onClick = { showRestoreConfirm = true }) {
                Text("Восстановить")
            }
        }

        HorizontalDivider()

        Text("О программе", style = MaterialTheme.typography.titleMedium)
        Text(
            "Версия ${BuildConfig.VERSION_NAME} (сборка ${BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.bodyLarge
        )
        Text(
            "Коммит ${BuildConfig.GIT_HASH}" +
                if (BuildConfig.GIT_DIRTY) " · есть несохранённые изменения" else "",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "Собрано ${BuildConfig.BUILD_TIME}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "github.com/zzzudroid/VoiceTimer",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// Карточка-предупреждение о незаданном условии надёжности с кнопкой исправления.
@Composable
private fun ReliabilityWarning(text: String, button: String, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        )
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("⚠ $text", style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onClick) { Text(button) }
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
