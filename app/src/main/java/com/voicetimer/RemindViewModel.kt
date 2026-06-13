package com.voicetimer

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import com.voicetimer.remind.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

// ViewModel вкладки «Напоминания»: голосовой ввод, список, расписание дня.
class RemindViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application

    val items = ReminderStore.items

    // Сейчас звонящее напоминание (null → тишины нет) — для баннера в приложении
    val ringing = ReminderAlarmService.ringing

    private val _schedule = MutableStateFlow(ScheduleSettings.load(application))
    val schedule = _schedule.asStateFlow()

    private val _recognizedText = MutableStateFlow("")
    val recognizedText = _recognizedText.asStateFlow()

    private val _partialText = MutableStateFlow("")
    val partialText = _partialText.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage = _errorMessage.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening = _isListening.asStateFlow()

    // true → текущий сеанс идёт через облако (Google), false → локальный Vosk
    private val _usingCloud = MutableStateFlow(false)
    val usingCloud = _usingCloud.asStateFlow()

    private val _isModelReady = MutableStateFlow(false)
    val isModelReady = _isModelReady.asStateFlow()

    private val _downloadProgress = MutableStateFlow(-1)
    val downloadProgress = _downloadProgress.asStateFlow()

    // true → последнее добавление просило календарь, но не было разрешения
    private val _needCalendarPermission = MutableStateFlow(false)
    val needCalendarPermission = _needCalendarPermission.asStateFlow()

    // Черновик нового напоминания: распознанный/введённый текст, который
    // показывается в поле и парсится в предпросмотр перед сохранением.
    private val _draft = MutableStateFlow("")
    val draft = _draft.asStateFlow()

    private val recognizer = VoiceRecognizer(
        context = application,
        onPartial = { _partialText.value = it },
        onFinal = { text ->
            _isListening.value = false
            _partialText.value = ""
            _recognizedText.value = text
            // Не сохраняем сразу — кладём в черновик целиком (заменяя прежний), предпросмотр + «Сохранить»
            _draft.value = text
            _errorMessage.value = null
        },
        onError = { msg -> _isListening.value = false; _partialText.value = ""; _errorMessage.value = msg },
        onModelReady = { _isModelReady.value = true; _downloadProgress.value = -1 },
        onProgress = { _downloadProgress.value = it },
        continuous = true,  // слушать до ручной остановки, копить фразы через паузы
        cloudEnabled = { _schedule.value.cloudWhenOnline },
        onEngine = { cloud -> _usingCloud.value = cloud }   // обновляем индикатор (в т.ч. при фолбэке)
    )

    init {
        ReminderStore.load(application)
        _downloadProgress.value = 0
        recognizer.init()
    }

    fun setDraft(s: String) { _draft.value = s; _errorMessage.value = null }

    // Разбирает текущий черновик для предпросмотра (null → не распозналось)
    fun previewOf(text: String): ParsedReminder? =
        ReminderParser.parse(text, System.currentTimeMillis(), _schedule.value)

    // Сохраняет черновик. Возвращает текст ошибки или null при успехе.
    fun saveDraft(): String? {
        val text = _draft.value.trim()
        if (text.isBlank()) return "Введите или надиктуйте напоминание"
        val parsed = previewOf(text)
            ?: return "Не понял время. Например: «позвонить маме завтра в 9»"
        if (parsed.text.isBlank())
            return "Не понял, о чём напомнить. Например: «купить хлеб вечером»"
        addReminder(parsed.text, parsed.triggerAt, parsed.type, _schedule.value.calendarByDefault,
            parsed.recurrence, parsed.recurrenceInterval)
        _draft.value = ""
        _recognizedText.value = ""
        _errorMessage.value = null
        return null
    }

    // ── CRUD ────────────────────────────────────────────────────────────────────

    fun addReminder(
        text: String, triggerAt: Long, type: ReminderType, toCalendar: Boolean,
        recurrence: RecurrenceType = RecurrenceType.NONE,
        recurrenceInterval: Int = 1
    ) {
        var inCal = false
        if (toCalendar) {
            inCal = runCatching { CalendarHelper.addEvent(app, text, triggerAt) }.getOrDefault(false)
            if (!inCal) _needCalendarPermission.value = true
        }
        val r = Reminder(
            id = System.currentTimeMillis(),
            text = text,
            triggerAt = triggerAt,
            type = type,
            inCalendar = inCal,
            recurrence = recurrence,
            recurrenceInterval = recurrenceInterval
        )
        ReminderStore.upsert(app, r)
        ReminderScheduler.schedule(app, r)
    }

    fun updateReminder(r: Reminder) {
        ReminderStore.upsert(app, r)
        ReminderScheduler.cancel(app, r.id)
        if (!r.done) ReminderScheduler.schedule(app, r)
    }

    fun delete(id: Long) {
        ReminderScheduler.cancel(app, id)
        ReminderStore.remove(app, id)
    }

    fun toggleDone(id: Long) {
        val r = ReminderStore.byId(id) ?: return
        val newDone = !r.done
        ReminderStore.setDone(app, id, newDone)
        if (newDone) ReminderScheduler.cancel(app, id)
        else ReminderStore.byId(id)?.let { ReminderScheduler.schedule(app, it) }
    }

    fun updateSchedule(h: ScheduleHours) {
        _schedule.value = h
        ScheduleSettings.save(app, h)
    }

    fun clearCalendarFlag() { _needCalendarPermission.value = false }
    fun clearError() { _errorMessage.value = null }

    // ── Управление звонящим напоминанием из приложения ───────────────────────────

    // «Готово» — остановить сигнал текущего напоминания
    fun stopRinging() = app.startService(
        Intent(app, ReminderAlarmService::class.java).apply { action = ReminderAlarmService.ACTION_STOP }
    )

    // «Отложить» — перенести звонящее напоминание на N минут
    fun snoozeRinging(minutes: Int = 10) = app.startService(
        Intent(app, ReminderAlarmService::class.java).apply {
            action = ReminderAlarmService.ACTION_SNOOZE
            putExtra(ReminderAlarmService.EXTRA_SNOOZE_MIN, minutes)
        }
    )

    // ── Голос ─────────────────────────────────────────────────────────────────

    fun toggleListening() {
        if (_isListening.value) {
            recognizer.stop(); _isListening.value = false; _partialText.value = ""
        } else {
            // Новый сеанс — затираем несохранённый черновик и начинаем распознавание заново
            _errorMessage.value = null; _recognizedText.value = ""; _draft.value = ""; _partialText.value = ""
            _isListening.value = true; recognizer.start()
        }
    }

    fun stopListening() {
        if (_isListening.value) {
            recognizer.stop(); _isListening.value = false; _partialText.value = ""
        }
    }

    override fun onCleared() { super.onCleared(); recognizer.destroy() }
}
