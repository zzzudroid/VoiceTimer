package com.voicetimer

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

// Облачное распознавание через системный SpeechRecognizer (Google).
// Тот же контракт колбэков, что у SpeechHelper (Vosk), и тот же continuous-режим:
// слушаем до ручной остановки, накапливая фразы через паузы (перезапуская сессии).
class AndroidSpeechHelper(
    private val context: Context,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val continuous: Boolean = false
) {
    private var recognizer: SpeechRecognizer? = null
    private val accumulated = StringBuilder()
    private var listening = false
    private val main = Handler(Looper.getMainLooper())
    private var retries = 0
    private val maxRetries = 2

    val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start() {
        if (!isAvailable) { onError("Голосовой сервис Google недоступен"); return }
        accumulated.clear()
        retries = 0
        listening = true
        startSession()
    }

    fun stop() {
        listening = false
        recognizer?.stopListening()   // итог придёт в onResults
    }

    fun destroy() {
        listening = false
        recognizer?.destroy(); recognizer = null
    }

    private fun startSession() {
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(listener)
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // язык интерфейса запросов — русский (помогает онлайн-движку)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ru-RU")
        }
        runCatching { recognizer?.startListening(intent) }
            .onFailure { onError("Ошибка запуска распознавания: ${it.message}") }
    }

    private fun appendSegment(seg: String) {
        if (accumulated.isNotEmpty()) accumulated.append(' ')
        accumulated.append(seg)
    }

    private fun firstText(b: Bundle?): String =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

    private fun finishContinuous() {
        val full = accumulated.toString().trim()
        accumulated.clear()
        if (full.isNotBlank()) onFinal(full)
    }

    private val listener = object : RecognitionListener {
        override fun onPartialResults(partialResults: Bundle?) {
            val t = firstText(partialResults)
            if (t.isNotBlank()) retries = 0
            val live = if (continuous) "${accumulated.toString().trim()} $t".trim() else t
            if (live.isNotBlank()) onPartial(live)
        }

        override fun onResults(results: Bundle?) {
            val t = firstText(results)
            if (continuous) {
                if (t.isNotBlank()) appendSegment(t)
                if (listening) {
                    onPartial(accumulated.toString().trim())
                    main.post { if (listening) startSession() } // слушаем следующий сегмент
                } else {
                    finishContinuous()
                }
            } else if (t.isNotBlank()) {
                onFinal(t)
            }
        }

        override fun onError(error: Int) {
            // В continuous во время пауз приходят NO_MATCH/SPEECH_TIMEOUT — просто продолжаем
            if (continuous && listening &&
                (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
            ) {
                main.post { if (listening) startSession() }
                return
            }
            // Пользователь остановил — отдаём накопленное
            if (continuous && !listening) { finishContinuous(); return }
            // Транзиентные сбои сервиса (11 server-disconnected, 5 client, 8 busy) — повторяем
            val transient = error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED ||
                error == SpeechRecognizer.ERROR_CLIENT ||
                error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
            if (listening && transient && retries < maxRetries) {
                retries++
                main.postDelayed({ if (listening) startSession() }, 400)
                return
            }
            onError(mapError(error))
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() { retries = 0 }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun mapError(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Нет сети для распознавания"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Нет разрешения на микрофон"
        SpeechRecognizer.ERROR_NO_MATCH -> "Не расслышал, повторите"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Распознавание занято, повторите"
        else -> "Ошибка распознавания ($code)"
    }
}
