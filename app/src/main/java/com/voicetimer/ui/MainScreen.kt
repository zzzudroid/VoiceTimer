package com.voicetimer.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicetimer.TimerState
import com.voicetimer.TimerViewModel

@Composable
fun MainScreen(
    viewModel: TimerViewModel,
    onMicClick: () -> Unit
) {
    val timeLeft         by viewModel.timeLeft.collectAsState()
    val totalTime        by viewModel.totalTime.collectAsState()
    val timerState       by viewModel.timerState.collectAsState()
    val recognizedText   by viewModel.recognizedText.collectAsState()
    val partialText      by viewModel.partialText.collectAsState()
    val errorMessage     by viewModel.errorMessage.collectAsState()
    val isListening      by viewModel.isListening.collectAsState()
    val isModelReady     by viewModel.isModelReady.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    val isAlarmRinging   by viewModel.isAlarmRinging.collectAsState()
    val actionLabel      by viewModel.actionLabel.collectAsState()

    val progress by animateFloatAsState(
        targetValue = if (totalTime > 0L) timeLeft.toFloat() / totalTime.toFloat() else 0f,
        animationSpec = tween(100), label = "progress"
    )

    val progressColor by animateColorAsState(
        targetValue = when {
            timerState == TimerState.SNOOZED                  -> MaterialTheme.colorScheme.tertiary
            timerState == TimerState.FINISHED                 -> MaterialTheme.colorScheme.error
            progress < 0.2f && totalTime > 0L                -> MaterialTheme.colorScheme.error
            progress < 0.5f && totalTime > 0L                -> MaterialTheme.colorScheme.tertiary
            else                                              -> MaterialTheme.colorScheme.primary
        },
        label = "progressColor"
    )

    val micScale by animateFloatAsState(
        targetValue = if (isListening) 1.15f else 1f,
        animationSpec = if (isListening) infiniteRepeatable(tween(600), RepeatMode.Reverse) else tween(200),
        label = "micScale"
    )

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly
        ) {

            // Метка действия — над кругом
            Box(modifier = Modifier.height(32.dp), contentAlignment = Alignment.Center) {
                if (actionLabel.isNotBlank()) {
                    Text(
                        text = actionLabel,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center
                    )
                } else {
                    Text(
                        text = "Voice Timer",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Кольцо прогресса + цифры
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { if (timerState == TimerState.SNOOZED) 1f else progress },
                    modifier = Modifier.size(260.dp),
                    strokeWidth = 14.dp,
                    color = progressColor,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = formatTime(timeLeft),
                        fontSize = 56.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    when (timerState) {
                        TimerState.FINISHED -> Text("Время вышло!", color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyLarge)
                        TimerState.SNOOZED  -> Text("Отложено…", color = MaterialTheme.colorScheme.tertiary,
                            style = MaterialTheme.typography.bodyLarge)
                        else -> {}
                    }
                }
            }

            // Статусная строка
            Box(modifier = Modifier.height(48.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                when {
                    errorMessage != null ->
                        Text(errorMessage!!, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                    isListening && partialText.isNotBlank() ->
                        Text(partialText, color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                    isListening ->
                        Text("Слушаю…", color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium)
                    recognizedText.isNotBlank() ->
                        Text("«$recognizedText»", color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                }
            }

            // Кнопки управления таймером
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {

                // Основные кнопки
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    when (timerState) {
                        TimerState.RUNNING -> {
                            OutlinedButton(onClick = { viewModel.pause() }) { Text("Пауза") }
                            OutlinedButton(onClick = { viewModel.reset() }) { Text("Сброс") }
                        }
                        TimerState.PAUSED -> {
                            Button(onClick = { viewModel.start() }) { Text("Продолжить") }
                            OutlinedButton(onClick = { viewModel.reset() }) { Text("Сброс") }
                        }
                        TimerState.IDLE -> {
                            if (totalTime > 0L) Button(onClick = { viewModel.start() }) { Text("Старт") }
                        }
                        TimerState.FINISHED -> {
                            Button(
                                onClick = { viewModel.stopAlarm(); viewModel.reset() },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) { Text("⏹ Стоп") }
                        }
                        TimerState.SNOOZED -> {
                            Button(
                                onClick = { viewModel.stopAlarm(); viewModel.reset() },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                            ) { Text("Отмена") }
                        }
                    }
                }

                // Кнопки «Отложить» — только когда сигнал звенит
                if (isAlarmRinging) {
                    Text("Отложить:", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SnoozeButton("30 сек") { viewModel.snooze(30_000L) }
                        SnoozeButton("1 мин")  { viewModel.snooze(60_000L) }
                        SnoozeButton("5 мин")  { viewModel.snooze(5 * 60_000L) }
                    }
                }
            }

            // Кнопка микрофона / прогресс загрузки
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (downloadProgress in 0..100 && !isModelReady) {
                    Text("Загрузка модели Vosk…", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LinearProgressIndicator(progress = { downloadProgress / 100f }, modifier = Modifier.width(200.dp))
                    Text("$downloadProgress%", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    FloatingActionButton(
                        onClick = onMicClick,
                        shape = CircleShape,
                        modifier = Modifier.size(80.dp).scale(micScale),
                        containerColor = if (isListening) MaterialTheme.colorScheme.errorContainer
                                         else MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Icon(
                            imageVector = if (isListening) Icons.Filled.Stop else Icons.Filled.Mic,
                            contentDescription = if (isListening) "Стоп" else "Голосовой ввод",
                            modifier = Modifier.size(36.dp),
                            tint = if (isListening) MaterialTheme.colorScheme.onErrorContainer
                                   else MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Text(
                        text = when {
                            isListening  -> "Нажми чтобы остановить"
                            isModelReady -> "Нажми и скажи время"
                            else         -> "Инициализация модели…"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SnoozeButton(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.tertiary),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
    ) { Text(label, style = MaterialTheme.typography.labelLarge) }
}

private fun formatTime(ms: Long): String {
    val s = (ms + 999L) / 1000L
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}
