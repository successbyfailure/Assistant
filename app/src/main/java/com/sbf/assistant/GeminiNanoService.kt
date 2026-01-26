package com.sbf.assistant

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * Service for on-device Gemini Nano inference via AICore (ML Kit GenAI).
 *
 * Supports:
 * - Text-only prompts
 * - Multimodal (image + text) prompts
 * - Streaming responses
 * - Automatic availability checking and model download
 */
class GeminiNanoService(private val context: Context) {

    private var generativeModel: GenerativeModel? = null
    private var _isAvailable = false
    private var _isDownloading = false
    private var lastStatusSummary: String = "Sin inicializar"

    sealed class Status {
        object Unavailable : Status()
        object Downloading : Status()
        data class DownloadProgress(val bytesDownloaded: Long) : Status()
        object Available : Status()
        data class Error(val message: String) : Status()
    }

    /**
     * Initialize the service and check availability.
     * Call this once at app startup.
     */
    suspend fun initialize(): Status = withContext(Dispatchers.IO) {
        try {
            generativeModel = Generation.getClient()
            val status = checkAndPrepare()
            _isAvailable = status == Status.Available
            lastStatusSummary = statusToSummary(status)
            status
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize GeminiNanoService", e)
            lastStatusSummary = "Error: ${e.message ?: "desconocido"}"
            Status.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Check status and download model if needed.
     */
    private suspend fun checkAndPrepare(): Status {
        val model = generativeModel ?: return Status.Unavailable

        return try {
            val featureStatus = model.checkStatus()

            when (featureStatus) {
                FeatureStatus.UNAVAILABLE -> {
                    Log.d(TAG, "Gemini Nano not available on this device")
                    Status.Unavailable
                }
                FeatureStatus.DOWNLOADABLE -> {
                    Log.d(TAG, "Gemini Nano downloadable, starting download...")
                    _isDownloading = true
                    try {
                        model.download().collect { downloadStatus ->
                            when (downloadStatus) {
                                is DownloadStatus.DownloadStarted -> {
                                    Log.d(TAG, "Download started")
                                }
                                is DownloadStatus.DownloadProgress -> {
                                    Log.d(TAG, "Download progress: ${downloadStatus.totalBytesDownloaded}")
                                }
                                DownloadStatus.DownloadCompleted -> {
                                    Log.d(TAG, "Download completed")
                                }
                                is DownloadStatus.DownloadFailed -> {
                                    Log.e(TAG, "Download failed: ${downloadStatus.e.message}")
                                }
                            }
                        }
                        _isDownloading = false
                        _isAvailable = true
                        lastStatusSummary = "Disponible"
                        Status.Available
                    } catch (e: Exception) {
                        Log.e(TAG, "Download failed", e)
                        _isDownloading = false
                        lastStatusSummary = "Error descarga: ${e.message ?: "desconocido"}"
                        Status.Error(e.message ?: "Download failed")
                    }
                }
                FeatureStatus.DOWNLOADING -> {
                    Log.d(TAG, "Gemini Nano currently downloading")
                    _isDownloading = true
                    lastStatusSummary = "Descargando"
                    Status.Downloading
                }
                FeatureStatus.AVAILABLE -> {
                    Log.d(TAG, "Gemini Nano available")
                    _isAvailable = true
                    lastStatusSummary = "Disponible"
                    Status.Available
                }
                else -> Status.Unavailable
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check status", e)
            lastStatusSummary = "Error: ${e.message ?: "desconocido"}"
            Status.Error(e.message ?: "Status check failed")
        }
    }

    /**
     * Check if Gemini Nano is available for inference.
     */
    fun isAvailable(): Boolean = _isAvailable && !_isDownloading

    fun getStatusSummary(): String = lastStatusSummary

    private fun statusToSummary(status: Status): String {
        return when (status) {
            Status.Unavailable -> "No disponible"
            Status.Downloading -> "Descargando"
            is Status.DownloadProgress -> "Descargando ${status.bytesDownloaded} bytes"
            Status.Available -> "Disponible"
            is Status.Error -> "Error: ${status.message}"
        }
    }

    /**
     * Warm up the model for faster first inference.
     * Call this after initialization in background.
     */
    suspend fun warmup() = withContext(Dispatchers.IO) {
        try {
            generativeModel?.warmup()
            Log.d(TAG, "Model warmed up")
        } catch (e: Exception) {
            Log.w(TAG, "Warmup failed", e)
        }
    }

    /**
     * Generate content from a text prompt (non-streaming).
     */
    suspend fun generateContent(prompt: String): Result<String> = withContext(Dispatchers.IO) {
        val model = generativeModel ?: return@withContext Result.failure(
            IllegalStateException("Model not initialized")
        )

        if (!_isAvailable) {
            return@withContext Result.failure(
                IllegalStateException("Gemini Nano not available")
            )
        }

        try {
            val response = model.generateContent(prompt)
            val text = response.candidates.firstOrNull()?.text ?: ""
            Result.success(text)
        } catch (e: Exception) {
            Log.e(TAG, "Generation failed", e)
            Result.failure(e)
        }
    }

    /**
     * Generate content from an image + text prompt (non-streaming).
     */
    suspend fun generateContent(image: Bitmap, prompt: String): Result<String> = withContext(Dispatchers.IO) {
        val model = generativeModel ?: return@withContext Result.failure(
            IllegalStateException("Model not initialized")
        )

        if (!_isAvailable) {
            return@withContext Result.failure(
                IllegalStateException("Gemini Nano not available")
            )
        }

        try {
            val request = generateContentRequest(
                ImagePart(image),
                TextPart(prompt)
            ) {
                temperature = 0.7f
            }

            val response = model.generateContent(request)
            val text = response.candidates.firstOrNull()?.text ?: ""
            Result.success(text)
        } catch (e: Exception) {
            Log.e(TAG, "Multimodal generation failed", e)
            Result.failure(e)
        }
    }

    /**
     * Generate content with streaming response.
     */
    fun generateContentStream(prompt: String): Flow<String> = flow {
        val model = generativeModel ?: throw IllegalStateException("Model not initialized")

        if (!_isAvailable) {
            throw IllegalStateException("Gemini Nano not available")
        }

        try {
            model.generateContentStream(prompt).collect { response ->
                val text = response.candidates.firstOrNull()?.text
                if (!text.isNullOrEmpty()) {
                    emit(text)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Streaming generation failed", e)
            throw e
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Generate content with streaming and custom parameters.
     */
    fun generateContentStream(
        prompt: String,
        temperature: Float = 0.7f,
        maxOutputTokens: Int = 256
    ): Flow<String> = flow {
        val model = generativeModel ?: throw IllegalStateException("Model not initialized")

        if (!_isAvailable) {
            throw IllegalStateException("Gemini Nano not available")
        }

        try {
            val request = generateContentRequest(TextPart(prompt)) {
                this.temperature = temperature
                this.maxOutputTokens = maxOutputTokens
            }

            model.generateContentStream(request).collect { response ->
                val text = response.candidates.firstOrNull()?.text
                if (!text.isNullOrEmpty()) {
                    emit(text)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Streaming generation failed", e)
            throw e
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Get the base model name/version.
     */
    suspend fun getModelName(): String? = withContext(Dispatchers.IO) {
        try {
            generativeModel?.getBaseModelName()
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "GeminiNanoService"
    }
}
