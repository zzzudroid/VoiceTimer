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
    // После остановки движок может независимо прислать и onResults, и onError —
    // оба ведут к finishContinuous(). Флаг гарантирует единственный onFinal за сеанс.
    private var finalized = false

    val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start() {
        if (!isAvailable) { onError("Голосовой сервис Google недоступен"); return }
        accumulated.clear()
        retries = 0
        finalized = false
        listening = true
        startSession()
    }

    fun stop() {
        listening = false
        recognizer?.runCatching { stopListening() }   // итог обычно придёт в onResults
        // Страховка: если recognizer уже «между сессиями»/idle и НЕ пришлёт колбэк на
        // stopListening(), всё равно отдаём накопленное, чтобы черновик установился и
        // «Сохранить» стала активной. finalized не даёт двойного onFinal.
        main.postDelayed({ finishContinuous() }, 700)
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
            // Дефолтная пауза тишины (~1-1.5с) короче времени реакции пользователя между
            // окончанием фразы и нажатием «стоп» — движок успевает решить, что сегмент
            // закончился, и услышимо перезапускает сессию раньше времени. Увеличиваем запас.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
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
        if (finalized) return
        finalized = true
        listening = false
        val full = accumulated.toString().trim()
        accumulated.clear()
        // Освобождаем recognizer вне текущего колбэка (destroy из самого колбэка
        // небезопасен). Любой запоздалый колбэк уже отсечётся флагом finalized.
        main.post { recognizer?.runCatching { destroy() }; recognizer = null }
        // Всегда уведомляем UI об окончании сеанса (даже если распознали пусто) —
        // иначе экран может «залипнуть» в состоянии прослушивания. Пустой текст просто
        // вернёт черновик к пустому: пользователь увидит, что ничего не распозналось.
        onFinal(full)
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
                // Google завершил распознавание фразы — СРАЗУ финализируем и НЕ
                // перезапускаем сессию. Прежний перезапуск на каждом успешном результате
                // давал лишний сигнал «слушаю снова» и гонку, из-за которой распознанный
                // текст оставался в буфере, не попадал в черновик, и «Сохранить» была
                // неактивна. Итог сразу уходит в onFinal → черновик → кнопка активна.
                finishContinuous()
            } else if (t.isNotBlank()) {
                onFinal(t)
            }
        }

        override fun onError(error: Int) {
            // Уже финализировали (успешный результат или ручная остановка) — игнорируем
            // любые запоздалые ошибки завершившейся сессии, чтобы не показать ложную ошибку.
            if (finalized) return
            // В continuous во время пауз приходят NO_MATCH/SPEECH_TIMEOUT — переслушиваем.
            // Это единственный случай перезапуска сессии (Google ничего не распознал),
            // редкий и осознанный, а не на каждом успешном результате.
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
