package com.voicetimer

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class TimerViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application

    // Состояние таймера и сигнала — живут в сервисе
    val timeLeft       = TimerService.timeLeft
    val totalTime      = TimerService.totalTime
    val timerState     = TimerService.timerState
    val isAlarmRinging = TimerService.isAlarmRinging
    val actionLabel    = TimerService.actionLabel

    private val _recognizedText  = MutableStateFlow("")
    val recognizedText = _recognizedText.asStateFlow()

    private val _partialText = MutableStateFlow("")
    val partialText = _partialText.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage = _errorMessage.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening = _isListening.asStateFlow()

    private val _isModelReady = MutableStateFlow(false)
    val isModelReady = _isModelReady.asStateFlow()

    private val _downloadProgress = MutableStateFlow(-1)
    val downloadProgress = _downloadProgress.asStateFlow()

    private val speechHelper = SpeechHelper(
        context = application,
        onPartial = { _partialText.value = it },
        onFinal = { text ->
            _isListening.value    = false
            _partialText.value    = ""
            _recognizedText.value = text
            val result = TimeParser.parse(text)
            if (result != null) {
                _errorMessage.value        = null
                TimerService.actionLabel.value = result.label
                startTimerService(result.millis)
            } else {
                _errorMessage.value = "Не понял время. Скажите: «Слей воду 5 минут» или «20 минут»"
            }
        },
        onError = { msg ->
            _isListening.value = false
            _partialText.value = ""
            _errorMessage.value = msg
        },
        onModelReady = { _isModelReady.value = true; _downloadProgress.value = -1 },
        onProgress   = { _downloadProgress.value = it }
    )

    init { _downloadProgress.value = 0; speechHelper.init() }

    // ── Управление таймером ───────────────────────────────────────────────────

    private fun startTimerService(durationMs: Long) {
        app.startForegroundService(
            Intent(app, TimerService::class.java).apply {
                action = TimerService.ACTION_START
                putExtra(TimerService.EXTRA_DURATION, durationMs)
            }
        )
    }

    fun start() {
        when (timerState.value) {
            TimerState.PAUSED -> app.startForegroundService(
                Intent(app, TimerService::class.java).apply { action = TimerService.ACTION_RESUME }
            )
            else -> if (timeLeft.value > 0) startTimerService(timeLeft.value)
        }
    }

    fun pause() = app.startService(
        Intent(app, TimerService::class.java).apply { action = TimerService.ACTION_PAUSE }
    )

    fun reset() {
        app.startService(Intent(app, TimerService::class.java).apply { action = TimerService.ACTION_RESET })
        _recognizedText.value = ""
        _partialText.value    = ""
    }

    fun stopAlarm() = app.startService(
        Intent(app, TimerService::class.java).apply { action = TimerService.ACTION_STOP_ALARM }
    )

    fun snooze(delayMs: Long) = app.startForegroundService(
        Intent(app, TimerService::class.java).apply {
            action = TimerService.ACTION_SNOOZE
            putExtra(TimerService.EXTRA_SNOOZE_MS, delayMs)
        }
    )

    // ── Голос ─────────────────────────────────────────────────────────────────

    fun toggleListening() {
        if (_isListening.value) {
            speechHelper.stopListening(); _isListening.value = false; _partialText.value = ""
        } else {
            _errorMessage.value = null; _isListening.value = true; speechHelper.startListening()
        }
    }

    fun stopListening() {
        if (_isListening.value) {
            speechHelper.stopListening(); _isListening.value = false; _partialText.value = ""
        }
    }

    fun clearError() { _errorMessage.value = null }

    override fun onCleared() { super.onCleared(); speechHelper.destroy() }
}
