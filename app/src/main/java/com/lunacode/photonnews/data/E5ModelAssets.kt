package com.lunacode.photonnews.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class E5ModelFiles(val model: File, val tokenizer: File)

object E5ModelAssets {
    const val MODEL_SIZE_LABEL = "118 MB"
    private const val REVISION = "6a0d452"
    private const val MODEL_SHA256 = "dd476dd0c2514e9b9be83aeb3853fac0763e0bdf4a71645407587d77c48a2d88"
    private const val TOKENIZER_SHA256 = "0b44a9d7b51c3c62626640cda0e2c2f70fdacdc25bbbd68038369d14ebdf4c39"
    private const val MODEL_URL =
        "https://huggingface.co/intfloat/multilingual-e5-small/resolve/$REVISION/onnx/model_qint8_avx512_vnni.onnx"
    private const val TOKENIZER_URL =
        "https://huggingface.co/intfloat/multilingual-e5-small/resolve/$REVISION/tokenizer.json"
    private const val MODEL_MAX_BYTES = 150L * 1024 * 1024
    private const val TOKENIZER_MAX_BYTES = 25L * 1024 * 1024

    fun existing(context: Context): E5ModelFiles? {
        val directory = File(context.filesDir, "models/multilingual-e5-small")
        val model = File(directory, "model.onnx")
        val tokenizer = File(directory, "tokenizer.json")
        return if (
            ModelFileIntegrity.matches(model, MODEL_SHA256) &&
            ModelFileIntegrity.matches(tokenizer, TOKENIZER_SHA256)
        ) {
            E5ModelFiles(model, tokenizer)
        } else null
    }

    suspend fun install(context: Context): E5ModelFiles = withContext(Dispatchers.IO) {
        existing(context)?.let { return@withContext it }
        val directory = File(context.filesDir, "models/multilingual-e5-small")
        if (!directory.exists() && !directory.mkdirs()) {
            error("Не удалось создать каталог модели")
        }
        require(directory.usableSpace >= 170L * 1024 * 1024) {
            "Для установки модели требуется не менее 170 МБ свободного места"
        }
        val model = File(directory, "model.onnx")
        val tokenizer = File(directory, "tokenizer.json")
        ensureFile(model, MODEL_URL, MODEL_SHA256, MODEL_MAX_BYTES)
        ensureFile(tokenizer, TOKENIZER_URL, TOKENIZER_SHA256, TOKENIZER_MAX_BYTES)
        E5ModelFiles(model, tokenizer)
    }

    private fun ensureFile(file: File, url: String, expectedSha256: String, maxBytes: Long) {
        if (ModelFileIntegrity.matches(file, expectedSha256)) return
        if (file.exists() && !file.delete()) error("Не удалось удалить повреждённый файл модели")
        val temporary = File(file.parentFile, file.name + ".part")
        if (temporary.exists()) temporary.delete()

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 90_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "PHOTON-AI-NEWS/3.1 Android model installer")
        }
        try {
            val code = connection.responseCode
            require(code in 200..299) { "Сервер модели вернул HTTP $code" }
            val length = connection.contentLengthLong
            require(length <= 0L || length <= maxBytes) { "Файл модели превышает допустимый размер" }
            var total = 0L
            BufferedInputStream(connection.inputStream).use { input ->
                BufferedOutputStream(temporary.outputStream()).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= maxBytes) { "Файл модели превышает допустимый размер" }
                        output.write(buffer, 0, count)
                    }
                }
            }
            require(ModelFileIntegrity.matches(temporary, expectedSha256)) {
                "SHA-256 модели не совпадает"
            }
            require(temporary.renameTo(file)) { "Не удалось сохранить проверенную модель" }
        } catch (error: Exception) {
            temporary.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

}
