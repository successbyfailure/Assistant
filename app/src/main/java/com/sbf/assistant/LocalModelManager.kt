package com.sbf.assistant

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

data class LocalModelConfig(
    val id: String,
    val name: String,
    val repoId: String,
    val filename: String
)

object LocalModelManager {
    private const val HF_BASE_URL = "https://huggingface.co"
    private val client = OkHttpClient()

    fun getConfig(settings: SettingsManager, id: String): LocalModelConfig {
        return when (id) {
            "local_whisper" -> LocalModelConfig(
                id = id,
                name = "Whisper (on-device)",
                repoId = settings.localWhisperRepo,
                filename = settings.localWhisperFile
            )
            "local_gemma" -> LocalModelConfig(
                id = id,
                name = "Gemma 2B",
                repoId = settings.localGemmaRepo,
                filename = settings.localGemmaFile
            )
            else -> LocalModelConfig(id, id, "", "")
        }
    }

    fun getModelFile(context: Context, config: LocalModelConfig): File {
        val baseDir = File(context.filesDir, "local_models/${config.id}")
        return File(baseDir, config.filename)
    }

    fun isInstalled(context: Context, config: LocalModelConfig): Boolean {
        if (config.filename.isBlank()) return false
        val file = getModelFile(context, config)
        return file.exists() && file.length() > 0L
    }

    fun getSizeMb(file: File): Int {
        return (file.length() / (1024 * 1024)).toInt()
    }

    fun buildDownloadUrl(config: LocalModelConfig): String {
        val repo = config.repoId.trim()
        val file = config.filename.trim()
        return "$HF_BASE_URL/$repo/resolve/main/$file"
    }

    fun downloadModel(
        context: Context,
        config: LocalModelConfig,
        apiKey: String,
        onProgress: (percent: Int) -> Unit,
        onComplete: (success: Boolean, error: String?) -> Unit
    ) {
        val url = buildDownloadUrl(config)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .build()

        Thread {
            val baseDir = File(context.filesDir, "local_models/${config.id}")
            if (!baseDir.exists()) {
                baseDir.mkdirs()
            }
            val tempFile = File(baseDir, "${config.filename}.part")
            val finalFile = File(baseDir, config.filename)
            var success = false
            var error: String? = null

            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        error = "HTTP ${response.code}"
                        return@use
                    }
                    val body = response.body ?: run {
                        error = "Empty body"
                        return@use
                    }
                    val total = body.contentLength()
                    var lastPercent = -1
                    body.byteStream().use { input ->
                        FileOutputStream(tempFile).use { output ->
                            val buffer = ByteArray(8192)
                            var read = input.read(buffer)
                            var downloaded = 0L
                            while (read > 0) {
                                output.write(buffer, 0, read)
                                downloaded += read
                                if (total > 0) {
                                    val percent = ((downloaded * 100) / total).toInt()
                                    if (percent != lastPercent) {
                                        lastPercent = percent
                                        onProgress(percent)
                                    }
                                } else if (downloaded == read.toLong()) {
                                    onProgress(-1)
                                }
                                read = input.read(buffer)
                            }
                            output.flush()
                        }
                    }
                    if (tempFile.exists()) {
                        if (finalFile.exists()) {
                            finalFile.delete()
                        }
                        tempFile.renameTo(finalFile)
                    }
                    success = finalFile.exists()
                    if (!success) {
                        error = "Write failed"
                    }
                }
            } catch (e: Exception) {
                error = e.message
            } finally {
                if (!success) {
                    tempFile.delete()
                }
            }

            onComplete(success, error)
        }.start()
    }
}
