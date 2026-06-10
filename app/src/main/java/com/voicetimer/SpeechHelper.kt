package com.voicetimer

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.FileOutputStream

class SpeechHelper(
    private val context: Context,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onModelReady: () -> Unit,
    private val onProgress: (Int) -> Unit   // 0–100, прогресс скачивания модели
) : RecognitionListener {

    companion object {
        // Модель вшита в APK (app/src/main/assets/vosk-model-ru) — загрузка из сети не нужна
        private const val ASSET_MODEL_DIR = "vosk-model-ru"
        private const val MODEL_DIR_NAME = "vosk-model-ru"
        private const val SAMPLE_RATE = 16000.0f
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var model: Model? = null
    private var speechService: SpeechService? = null

    val isModelReady: Boolean get() = model != null

    // Вызывается при старте приложения
    fun init() {
        scope.launch {
            val modelDir = File(context.filesDir, MODEL_DIR_NAME)
            // Проверяем наличие ключевого файла модели
            if (File(modelDir, "am/final.mdl").exists()) {
                loadModel(modelDir.absolutePath)
            } else {
                unpackFromAssets(modelDir)
            }
        }
    }

    // Распаковывает модель из assets в приватную папку приложения (один раз после установки)
    private suspend fun unpackFromAssets(targetDir: File) {
        try {
            // Считаем общее число файлов для прогресса
            val allFiles = listAssetFiles(ASSET_MODEL_DIR)
            val total = allFiles.size.coerceAtLeast(1)
            var done = 0

            for (assetPath in allFiles) {
                val relativePath = assetPath.removePrefix("$ASSET_MODEL_DIR/")
                val outFile = File(targetDir, relativePath)
                outFile.parentFile?.mkdirs()
                context.assets.open(assetPath).use { input ->
                    FileOutputStream(outFile).use { input.copyTo(it) }
                }
                done++
                withContext(Dispatchers.Main) { onProgress(done * 100 / total) }
            }

            loadModel(targetDir.absolutePath)

        } catch (e: Exception) {
            targetDir.deleteRecursively()
            withContext(Dispatchers.Main) { onError("Ошибка распаковки модели: ${e.message}") }
        }
    }

    // Рекурсивно собирает пути всех файлов внутри каталога assets
    private fun listAssetFiles(dir: String): List<String> {
        val children = context.assets.list(dir) ?: return emptyList()
        if (children.isEmpty()) return listOf(dir) // это файл, а не папка
        return children.flatMap { listAssetFiles("$dir/$it") }
    }

    private suspend fun loadModel(path: String) {
        try {
            model = Model(path)
            withContext(Dispatchers.Main) { onModelReady() }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { onError("Ошибка загрузки модели: ${e.message}") }
        }
    }

    fun startListening() {
        val m = model ?: return
        stopListening()
        try {
            val recognizer = Recognizer(m, SAMPLE_RATE)
            speechService = SpeechService(recognizer, SAMPLE_RATE).also {
                it.startListening(this)
            }
        } catch (e: Exception) {
            onError("Ошибка микрофона: ${e.message}")
        }
    }

    fun stopListening() {
        speechService?.stop()
        speechService = null
    }

    fun destroy() {
        scope.cancel()
        speechService?.shutdown()
        model = null
    }

    // --- RecognitionListener ---

    override fun onPartialResult(hypothesis: String?) {
        val text = parseJson(hypothesis) ?: return
        if (text.isNotBlank()) onPartial(text)
    }

    override fun onResult(hypothesis: String?) {
        // Vosk вызывает onResult после паузы в речи
        val text = parseJson(hypothesis) ?: return
        if (text.isNotBlank()) {
            stopListening()
            onFinal(text)
        }
    }

    override fun onFinalResult(hypothesis: String?) {
        val text = parseJson(hypothesis) ?: return
        if (text.isNotBlank()) onFinal(text)
    }

    override fun onError(exception: Exception?) {
        onError(exception?.message ?: "Ошибка распознавания")
    }

    override fun onTimeout() {
        stopListening()
    }

    private fun parseJson(json: String?): String? = runCatching {
        JSONObject(json ?: return null).optString("text").trim()
    }.getOrNull()
}
