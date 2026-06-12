package com.voicetimer.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.voicetimer.RemindViewModel
import com.voicetimer.remind.Reminder
import com.voicetimer.remind.ReminderType
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

@Composable
fun RemindersScreen(viewModel: RemindViewModel, onMicClick: () -> Unit) {
    var showList by rememberSaveable { mutableStateOf(false) }
    if (showList) {
        AllRemindersView(viewModel, onBack = { showList = false })
    } else {
        NewReminderView(viewModel, onMicClick, onOpenList = { showList = true })
    }
}

// ── Экран «Новое напоминание» ─────────────────────────────────────────────────
@Composable
private fun NewReminderView(viewModel: RemindViewModel, onMicClick: () -> Unit, onOpenList: () -> Unit) {
    val draft by viewModel.draft.collectAsState()
    val partial by viewModel.partialText.collectAsState()
    val error by viewModel.errorMessage.collectAsState()
    val listening by viewModel.isListening.collectAsState()
    val modelReady by viewModel.isModelReady.collectAsState()

    val preview = remember(draft) { if (draft.isBlank()) null else viewModel.previewOf(draft) }
    val micScale by animateFloatAsState(targetValue = if (listening) 1.1f else 1f, label = "mic")

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Новое напоминание", style = MaterialTheme.typography.titleLarge)

        OutlinedTextField(
            value = if (listening && partial.isNotBlank()) partial else draft,
            onValueChange = { viewModel.setDraft(it) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
            placeholder = { Text("Например: «разбуди меня завтра в девять»") },
            label = { Text("Что и когда напомнить") }
        )

        // Предпросмотр распознанного времени
        when {
            error != null ->
                Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            preview != null -> {
                Column {
                    Text("Напоминание произойдёт", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        formatAbsolute(preview.triggerAt),
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        color = if (preview.type == ReminderType.EXACT) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.tertiary
                    )
                    Text(
                        relativeRu(System.currentTimeMillis(), preview.triggerAt) +
                            if (preview.type == ReminderType.INEXACT) " · примерно" else "",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            draft.isNotBlank() ->
                Text("Не понял время. Добавьте: «завтра в 9», «через час», «в среду вечером»",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            else ->
                Text(
                    if (modelReady) "Нажмите 🎤 и скажите по-русски, что и когда напомнить."
                    else "Инициализация модели…",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
                )
        }

        Spacer(Modifier.weight(1f))

        // Большая кнопка микрофона
        FilledTonalButton(
            onClick = onMicClick,
            modifier = Modifier.fillMaxWidth().height(120.dp).scale(micScale),
            shape = MaterialTheme.shapes.large,
            colors = if (listening)
                ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            else ButtonDefaults.filledTonalButtonColors()
        ) {
            Icon(if (listening) Icons.Filled.Stop else Icons.Filled.Mic, contentDescription = null,
                modifier = Modifier.size(48.dp))
        }

        Spacer(Modifier.weight(1f))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onOpenList, modifier = Modifier.weight(1f)) { Text("Все напоминания") }
            Button(
                onClick = { viewModel.saveDraft()?.let { /* ошибка покажется через errorMessage */ } },
                enabled = preview != null && preview.text.isNotBlank(),
                modifier = Modifier.weight(1f)
            ) { Text("Сохранить") }
        }
    }
}

// ── Экран «Все напоминания» ───────────────────────────────────────────────────
@Composable
private fun AllRemindersView(viewModel: RemindViewModel, onBack: () -> Unit) {
    val items by viewModel.items.collectAsState()
    var tabDone by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Reminder?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Назад") }
            Text("Все напоминания", style = MaterialTheme.typography.titleLarge)
        }
        TabRow(selectedTabIndex = if (tabDone) 1 else 0) {
            Tab(selected = !tabDone, onClick = { tabDone = false }, text = { Text("Активные") })
            Tab(selected = tabDone, onClick = { tabDone = true }, text = { Text("Завершённые") })
        }

        val now = System.currentTimeMillis()
        val shown = items.filter { it.done == tabDone }

        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (tabDone) "Нет завершённых" else "Нет активных напоминаний",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (tabDone) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(shown.sortedByDescending { it.triggerAt }, key = { it.id }) { r ->
                    ReminderRow(r, { viewModel.toggleDone(r.id) }, { editing = r }, { viewModel.delete(r.id) })
                }
            }
        } else {
            // Группировка активных по близости срабатывания
            val groups = linkedMapOf<String, MutableList<Reminder>>(
                "Просрочено" to mutableListOf(),
                "Сегодня" to mutableListOf(),
                "Ближайшие 7 дней" to mutableListOf(),
                "Больше чем через неделю" to mutableListOf()
            )
            for (r in shown.sortedBy { it.triggerAt }) {
                val d = daysBetween(now, r.triggerAt)
                val key = when {
                    r.triggerAt < now -> "Просрочено"
                    d == 0 -> "Сегодня"
                    d in 1..7 -> "Ближайшие 7 дней"
                    else -> "Больше чем через неделю"
                }
                groups[key]!!.add(r)
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                groups.forEach { (title, list) ->
                    if (list.isNotEmpty()) {
                        item(key = "h_$title") {
                            Text(title, style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 8.dp, start = 4.dp))
                        }
                        items(list, key = { it.id }) { r ->
                            ReminderRow(r, { viewModel.toggleDone(r.id) }, { editing = r }, { viewModel.delete(r.id) })
                        }
                    }
                }
            }
        }
    }

    editing?.let { r ->
        ReminderEditDialog(
            initial = r, calendarDefault = r.inCalendar,
            onDismiss = { editing = null },
            onSave = { text, time, type, _ ->
                viewModel.updateReminder(r.copy(text = text, triggerAt = time, type = type)); editing = null
            }
        )
    }
}

@Composable
private fun ReminderRow(r: Reminder, onToggleDone: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 8.dp, end = 0.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(
                    r.text, style = MaterialTheme.typography.bodyLarge,
                    textDecoration = if (r.done) TextDecoration.LineThrough else null,
                    color = if (r.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(formatShort(r.triggerAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (r.type == ReminderType.EXACT) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.tertiary)
                    if (r.type == ReminderType.INEXACT) Text("≈", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary)
                    if (r.inCalendar) Icon(Icons.Filled.Event, contentDescription = "В календаре",
                        modifier = Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = "Изменить") }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Удалить", tint = MaterialTheme.colorScheme.error)
            }
            Checkbox(checked = r.done, onCheckedChange = { onToggleDone() })
        }
    }
}

@Composable
private fun ReminderEditDialog(
    initial: Reminder?,
    calendarDefault: Boolean,
    onDismiss: () -> Unit,
    onSave: (text: String, time: Long, type: ReminderType, toCalendar: Boolean) -> Unit
) {
    val context = LocalContext.current
    var text by remember { mutableStateOf(initial?.text ?: "") }
    var timeMs by remember {
        mutableStateOf(initial?.triggerAt ?: (System.currentTimeMillis() + 60 * 60_000L))
    }
    var exact by remember { mutableStateOf((initial?.type ?: ReminderType.EXACT) == ReminderType.EXACT) }
    var toCalendar by remember { mutableStateOf(calendarDefault) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { onSave(text.trim(), timeMs, if (exact) ReminderType.EXACT else ReminderType.INEXACT, toCalendar) },
                enabled = text.isNotBlank()
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
        title = { Text(if (initial == null) "Новое напоминание" else "Изменить") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = text, onValueChange = { text = it },
                    label = { Text("О чём напомнить") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val c = Calendar.getInstance().apply { timeInMillis = timeMs }
                        DatePickerDialog(context, { _, y, m, d -> c.set(y, m, d); timeMs = c.timeInMillis },
                            c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
                    }, modifier = Modifier.weight(1f)) {
                        Text(SimpleDateFormat("d MMM", Locale("ru")).format(Date(timeMs)))
                    }
                    OutlinedButton(onClick = {
                        val c = Calendar.getInstance().apply { timeInMillis = timeMs }
                        TimePickerDialog(context, { _, h, min ->
                            c.set(Calendar.HOUR_OF_DAY, h); c.set(Calendar.MINUTE, min); timeMs = c.timeInMillis
                        }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show()
                    }, modifier = Modifier.weight(1f)) {
                        Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timeMs)))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = exact, onCheckedChange = { exact = it })
                    Spacer(Modifier.width(8.dp))
                    Text(if (exact) "Точное (громкий сигнал)" else "Неточное (мягкий звук)")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = toCalendar, onCheckedChange = { toCalendar = it })
                    Text("Добавить в календарь")
                }
            }
        }
    )
}

// ── Форматирование ────────────────────────────────────────────────────────────

private fun daysBetween(now: Long, target: Long): Int {
    val a = Calendar.getInstance().apply { timeInMillis = now; clearTime() }
    val b = Calendar.getInstance().apply { timeInMillis = target; clearTime() }
    return ((b.timeInMillis - a.timeInMillis) / 86_400_000L).toInt()
}

private fun Calendar.clearTime() {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}

// «07.10.2026 (среда) в 14:00»
private fun formatAbsolute(ms: Long): String {
    val date = SimpleDateFormat("dd.MM.yyyy", Locale("ru")).format(Date(ms))
    val dow = SimpleDateFormat("EEEE", Locale("ru")).format(Date(ms))
    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
    return "$date ($dow) в $time"
}

// «сегодня в 17:13», «завтра в 7:00», «ср, 18 июн в 14:00»
private fun formatShort(ms: Long): String {
    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
    return when (daysBetween(System.currentTimeMillis(), ms)) {
        0 -> "сегодня в $time"
        1 -> "завтра в $time"
        2 -> "послезавтра в $time"
        else -> SimpleDateFormat("EEE, d MMM", Locale("ru")).format(Date(ms)) + " в $time"
    }
}

// «через 2 дня 3 часа», «через 15 минут», «через 9 месяцев 5 дней»
private fun relativeRu(now: Long, target: Long): String {
    val past = target < now
    var diff = abs(target - now)
    val months = diff / (30L * 86_400_000L); diff %= (30L * 86_400_000L)
    val days = diff / 86_400_000L; diff %= 86_400_000L
    val hours = diff / 3_600_000L; diff %= 3_600_000L
    val minutes = diff / 60_000L

    val parts = mutableListOf<String>()
    if (months > 0) parts += plural(months, "месяц", "месяца", "месяцев")
    if (days > 0) parts += plural(days, "день", "дня", "дней")
    if (months == 0L && hours > 0) parts += plural(hours, "час", "часа", "часов")
    if (months == 0L && days == 0L && minutes > 0) parts += plural(minutes, "минуту", "минуты", "минут")
    if (parts.isEmpty()) parts += "меньше минуты"
    val body = parts.take(2).joinToString(" ")
    return if (past) "$body назад" else "через $body"
}

private fun plural(n: Long, one: String, few: String, many: String): String {
    val mod10 = n % 10; val mod100 = n % 100
    val word = when {
        mod10 == 1L && mod100 != 11L -> one
        mod10 in 2..4 && mod100 !in 12..14 -> few
        else -> many
    }
    return "$n $word"
}
