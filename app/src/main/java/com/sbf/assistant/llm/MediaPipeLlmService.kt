package com.sbf.assistant.llm

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.sbf.assistant.ModelDownloadManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Service for on-device LLM inference via MediaPipe.
 *
 * Supports .task model bundles (e.g., Gemma, Phi-2, Falcon, StableLM).
 * The .task format bundles the model with its tokenizer and metadata.
 *
 * Features:
 * - GPU acceleration when available
 * - Streaming token generation
 * - Temperature and top-k/top-p sampling
 */
class MediaPipeLlmService(private val context: Context) {

    private var llmInference: LlmInference? = null
    private var _isAvailable = false
    private var _modelName: String? = null
    private var _modelFilename: String? = null
    private var _isUsingGpu = false

    sealed class Status {
        object Unavailable : Status()
        object Loading : Status()
        object Available : Status()
        data class Error(val message: String, val isOutOfMemory: Boolean = false) : Status()
    }

    /**
     * Initialize the service with a .task model file.
     */
    suspend fun initialize(modelPath: String, preferGpu: Boolean = false): Status = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Initializing MediaPipeLlmService with model: $modelPath")

            val modelFile = File(modelPath)
            if (!modelFile.exists()) {
                Log.e(TAG, "Model file not found: $modelPath")
                return@withContext Status.Error("Modelo no encontrado: $modelPath")
            }

            if (!modelPath.endsWith(".task")) {
                Log.w(TAG, "Model file may not be in .task format: $modelPath")
            }

            release()

            val backend = if (preferGpu) LlmInference.Backend.GPU else LlmInference.Backend.CPU
            _isUsingGpu = backend == LlmInference.Backend.GPU
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(1024)
                .setPreferredBackend(backend)
                .build()

            llmInference = LlmInference.createFromOptions(context, options)
            _isAvailable = true
            _modelName = modelFile.nameWithoutExtension
            _modelFilename = modelFile.name

            Log.d(TAG, "MediaPipeLlmService initialized. GPU=$_isUsingGpu, model=$_modelName")
            Status.Available
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "MediaPipeLlmService out of memory", e)
            _isAvailable = false
            Status.Error("Sin memoria al cargar el modelo", true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaPipeLlmService", e)
            _isAvailable = false
            Status.Error(e.message ?: "Error desconocido")
        }
    }

    /**
     * Initialize with a model from ModelDownloadManager.
     */
    suspend fun initializeWithModel(modelInfo: ModelDownloadManager.ModelInfo): Status {
        val downloadManager = ModelDownloadManager(context)
        val modelFile = downloadManager.getModelFile(modelInfo.filename)
            ?: return Status.Error("Modelo no descargado")

        return initialize(modelFile.absolutePath)
    }

    /**
     * Check if the service is available.
     */
    fun isAvailable(): Boolean = _isAvailable

    /**
     * Check if using GPU acceleration.
     */
    fun isUsingGpu(): Boolean = _isUsingGpu

    /**
     * Get the model name.
     */
    fun getModelName(): String? = _modelName

    fun getModelFilename(): String? = _modelFilename

    /**
     * Generate content (non-streaming).
     */
    suspend fun generateContent(prompt: String): Result<String> = withContext(Dispatchers.IO) {
        val inference = llmInference ?: return@withContext Result.failure(
            IllegalStateException("Servicio no inicializado")
        )

        if (!_isAvailable) {
            return@withContext Result.failure(
                IllegalStateException("Servicio no disponible")
            )
        }

        try {
            val response = inference.generateResponse(prompt)
            Result.success(response)
        } catch (e: Exception) {
            Log.e(TAG, "Generation failed", e)
            Result.failure(e)
        }
    }

    /**
     * Generate content with streaming output.
     */
    fun generateContentStream(prompt: String): Flow<String> = callbackFlow {
        val inference = llmInference
        if (inference == null) {
            close(IllegalStateException("Servicio no inicializado"))
            return@callbackFlow
        }
        if (!_isAvailable) {
            close(IllegalStateException("Servicio no disponible"))
            return@callbackFlow
        }

        try {
            inference.generateResponseAsync(prompt) { partialResult, done ->
                if (partialResult.isNotEmpty()) {
                    trySend(partialResult)
                }
                if (done) {
                    close()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Streaming generation failed", e)
            close(e)
        }

        awaitClose {
            // Cleanup if needed
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Release resources.
     */
    fun release() {
        try {
            llmInference?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing LlmInference", e)
        }
        llmInference = null
        _isAvailable = false
        _modelName = null
        _modelFilename = null
    }

    companion object {
        private const val TAG = "MediaPipeLlmService"

        /**
         * Check if a file is a MediaPipe .task model.
         */
        fun isTaskModel(filename: String): Boolean {
            return filename.endsWith(".task", ignoreCase = true)
        }
    }
}
