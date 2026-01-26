package com.sbf.assistant.llm

import android.content.Context
import android.util.Log
import com.sbf.assistant.ModelDownloadManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File

/**
 * Service for on-device LLM inference via TensorFlow Lite.
 *
 * Supports:
 * - Text generation with TFLite models
 * - GPU acceleration when available
 * - Streaming token generation
 * - Temperature and top-k sampling
 *
 * This is an alternative to GeminiNanoService for devices without AICore.
 */
class LocalLlmService(private val context: Context) {

    private var engine: TfLiteLlmEngine? = null
    private var tokenizer: LlmTokenizer? = null
    private var _isAvailable = false
    private var _modelName: String? = null
    private var _modelFilename: String? = null

    sealed class Status {
        object Unavailable : Status()
        object Loading : Status()
        object Available : Status()
        data class Error(val message: String, val isOutOfMemory: Boolean = false) : Status()
    }

    /**
     * Initialize the service with a specific model.
     */
    suspend fun initialize(modelPath: String, vocabPath: String): Status = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Initializing LocalLlmService with model: $modelPath")

            // Verify files exist
            if (!File(modelPath).exists()) {
                Log.e(TAG, "Model file not found: $modelPath")
                return@withContext Status.Error("Modelo no encontrado")
            }

            if (!File(vocabPath).exists()) {
                Log.e(TAG, "Vocab file not found: $vocabPath")
                return@withContext Status.Error("Vocabulario no encontrado")
            }

            release()

            // Initialize tokenizer
            val newTokenizer = LlmTokenizer()
            if (!newTokenizer.loadVocab(vocabPath)) {
                return@withContext Status.Error("Error cargando vocabulario")
            }
            tokenizer = newTokenizer

            // Initialize engine
            val newEngine = TfLiteLlmEngine(context)
            if (!newEngine.initialize(modelPath)) {
                return@withContext Status.Error("Error inicializando modelo")
            }
            engine = newEngine

            _isAvailable = true
            _modelName = File(modelPath).nameWithoutExtension
            _modelFilename = File(modelPath).name
            Log.d(TAG, "LocalLlmService initialized. GPU=${newEngine.isUsingGpu}")

            Status.Available
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "LocalLlmService out of memory", e)
            Status.Error("Sin memoria al cargar el modelo", true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize LocalLlmService", e)
            Status.Error(e.message ?: "Error desconocido")
        }
    }

    /**
     * Initialize with a model from ModelDownloadManager.
     */
    suspend fun initializeWithModel(
        modelInfo: ModelDownloadManager.ModelInfo,
        apiKey: String? = null
    ): Status {
        if (modelInfo.category == "LLM-Multimodal") {
            return Status.Error("Modelo multimodal no compatible con el tokenizer local")
        }
        val downloadManager = ModelDownloadManager(context)
        val modelFile = downloadManager.getModelFile(modelInfo.filename)
            ?: return Status.Error("Modelo no descargado")

        var vocabFile = downloadManager.getTokenizerFile(modelInfo)
        if (vocabFile == null) {
            vocabFile = downloadManager.ensureTokenizerForModel(modelInfo, apiKey)
        }
        if (vocabFile == null) {
            return Status.Error("Archivo de vocabulario no encontrado")
        }
        if (vocabFile.name.endsWith(".model", ignoreCase = true)) {
            return Status.Error("Tokenizer .model no soportado en TFLite LLM local")
        }

        return initialize(modelFile.absolutePath, vocabFile.absolutePath)
    }

    /**
     * Check if the service is available for inference.
     */
    fun isAvailable(): Boolean = _isAvailable

    /**
     * Check if using GPU acceleration.
     */
    fun isUsingGpu(): Boolean = engine?.isUsingGpu ?: false

    /**
     * Get the model name.
     */
    fun getModelName(): String? = _modelName

    fun getModelFilename(): String? = _modelFilename

    /**
     * Generate content (non-streaming).
     */
    suspend fun generateContent(
        prompt: String,
        maxTokens: Int = 256,
        temperature: Float = 0.7f,
        topK: Int = 40
    ): Result<String> = withContext(Dispatchers.IO) {
        val currentEngine = engine ?: return@withContext Result.failure(
            IllegalStateException("Motor no inicializado")
        )
        val currentTokenizer = tokenizer ?: return@withContext Result.failure(
            IllegalStateException("Tokenizer no inicializado")
        )

        if (!_isAvailable) {
            return@withContext Result.failure(
                IllegalStateException("Servicio no disponible")
            )
        }

        try {
            val inputIds = currentTokenizer.encode(prompt, addBos = true)
            val generatedTokens = mutableListOf<Int>()
            var currentInput = inputIds

            for (i in 0 until maxTokens) {
                val logits = currentEngine.runInference(currentInput)
                    ?: return@withContext Result.failure(Exception("Error en inferencia"))

                val nextToken = if (temperature <= 0) {
                    currentEngine.greedyDecode(logits)
                } else {
                    currentEngine.sampleToken(logits, temperature, topK)
                }

                if (nextToken < 0 || currentTokenizer.isEos(nextToken)) {
                    break
                }

                generatedTokens.add(nextToken)

                // Update input for next iteration (autoregressive)
                currentInput = (inputIds.toList() + generatedTokens).toIntArray()

                // Prevent exceeding max sequence length
                if (currentInput.size >= currentEngine.maxSeqLen) {
                    break
                }
            }

            val output = currentTokenizer.decode(generatedTokens.toIntArray())
            Result.success(output)
        } catch (e: Exception) {
            Log.e(TAG, "Generation failed", e)
            Result.failure(e)
        }
    }

    /**
     * Generate content with streaming output.
     */
    fun generateContentStream(
        prompt: String,
        maxTokens: Int = 256,
        temperature: Float = 0.7f,
        topK: Int = 40
    ): Flow<String> = flow {
        val currentEngine = engine ?: throw IllegalStateException("Motor no inicializado")
        val currentTokenizer = tokenizer ?: throw IllegalStateException("Tokenizer no inicializado")

        if (!_isAvailable) {
            throw IllegalStateException("Servicio no disponible")
        }

        val inputIds = currentTokenizer.encode(prompt, addBos = true)
        val generatedTokens = mutableListOf<Int>()
        var currentInput = inputIds

        for (i in 0 until maxTokens) {
            yield() // Allow cancellation

            val logits = currentEngine.runInference(currentInput)
                ?: throw Exception("Error en inferencia")

            val nextToken = if (temperature <= 0) {
                currentEngine.greedyDecode(logits)
            } else {
                currentEngine.sampleToken(logits, temperature, topK)
            }

            if (nextToken < 0 || currentTokenizer.isEos(nextToken)) {
                break
            }

            generatedTokens.add(nextToken)

            // Emit the new token
            val tokenText = currentTokenizer.decodeToken(nextToken)
            if (tokenText.isNotEmpty()) {
                emit(tokenText)
            }

            // Update input for next iteration
            currentInput = (inputIds.toList() + generatedTokens).toIntArray()

            if (currentInput.size >= currentEngine.maxSeqLen) {
                break
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Release resources.
     */
    fun release() {
        engine?.deinitialize()
        engine = null
        tokenizer = null
        _isAvailable = false
        _modelName = null
        _modelFilename = null
    }

    companion object {
        private const val TAG = "LocalLlmService"
    }
}
