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
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

class SpeechHelper(
    private val context: Context,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onModelReady: () -> Unit,
    private val onProgress: (Int) -> Unit   // 0–100, прогресс скачивания модели
) : RecognitionListener {

    companion object {
        private const val MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip"
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
                downloadAndExtract(modelDir)
            }
        }
    }

    private suspend fun downloadAndExtract(targetDir: File) {
        val zipFile = File(context.cacheDir, "vosk-ru.zip")
        try {
            // Скачиваем архив
            val connection = URL(MODEL_URL).openConnection() as HttpURLConnection
            val total = connection.contentLength.toLong()
            var downloaded = 0L

            connection.inputStream.use { input ->
                FileOutputStream(zipFile).use { output ->
                    val buf = ByteArray(8192)
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        output.write(buf, 0, read)
                        downloaded += read
                        if (total > 0) withContext(Dispatchers.Main) {
                            onProgress((downloaded * 100 / total).toInt())
                        }
                    }
                }
            }

            // Распаковываем
            ZipInputStream(zipFile.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    // Убираем первый уровень вложенности (vosk-model-small-ru-0.22/...)
                    val relativePath = entry.name.substringAfter('/')
                    if (relativePath.isNotEmpty()) {
                        val outFile = File(targetDir, relativePath)
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { zip.copyTo(it) }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }

            zipFile.delete()
            loadModel(targetDir.absolutePath)

        } catch (e: Exception) {
            zipFile.delete()
            targetDir.deleteRecursively()
            withContext(Dispatchers.Main) { onError("Ошибка загрузки модели: ${e.message}") }
        }
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
