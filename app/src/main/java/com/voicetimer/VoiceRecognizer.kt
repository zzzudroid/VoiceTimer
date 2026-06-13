package com.voicetimer

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

// Координатор распознавания: при наличии интернета и доступном сервисе Google
// использует облачный SpeechRecognizer (лучше качество), иначе — локальный Vosk.
// Если облако падает с ошибкой ДО выдачи результата — молча переключаемся на Vosk.
class VoiceRecognizer(
    private val context: Context,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onError: (String) -> Unit,
    onModelReady: () -> Unit,
    onProgress: (Int) -> Unit,
    private val continuous: Boolean,
    private val cloudEnabled: () -> Boolean,
    // уведомление, каким движком идёт текущий сеанс (для индикации в UI)
    private val onEngine: (cloud: Boolean) -> Unit = {}
) {
    private var sessionActive = false
    private var cloudProducedOutput = false

    var lastUsedCloud = false
        private set

    private val vosk = SpeechHelper(context, onPartial, onFinal, onError, onModelReady, onProgress, continuous)

    private val cloud = AndroidSpeechHelper(
        context = context,
        onPartial = { cloudProducedOutput = true; onPartial(it) },
        onFinal = { cloudProducedOutput = true; onFinal(it) },
        onError = { msg -> handleCloudError(msg) },
        continuous = continuous
    )

    val isModelReady: Boolean get() = vosk.isModelReady

    fun init() = vosk.init()

    fun start() {
        sessionActive = true
        cloudProducedOutput = false
        lastUsedCloud = cloudEnabled() && isOnline() && cloud.isAvailable
        onEngine(lastUsedCloud)
        if (lastUsedCloud) cloud.start() else vosk.startListening()
    }

    fun stop() {
        sessionActive = false
        if (lastUsedCloud) cloud.stop() else vosk.stopListening()
    }

    fun destroy() {
        vosk.destroy()
        cloud.destroy()
    }

    // Облако упало: если сеанс ещё идёт и оно ничего не дало — тихо падаем на Vosk
    private fun handleCloudError(msg: String) {
        if (sessionActive && !cloudProducedOutput) {
            lastUsedCloud = false
            onEngine(false)
            vosk.startListening()
        } else {
            onError(msg)
        }
    }

    private fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
