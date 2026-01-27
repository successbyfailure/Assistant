package com.sbf.assistant

import android.util.Log
import java.io.File

enum class WhisperMode {
    TRANSCRIBE,
    TRANSLATE
}

class LocalWhisperService(
    private val context: android.content.Context,
    private val settings: SettingsManager,
    private val modelRuntime: LocalModelRuntime,
    private val modelDownloadManager: ModelDownloadManager
) {
    private var engine: com.sbf.assistant.whisper.WhisperEngineJava? = null
    private var engineModel: String? = null
    private var engineVocab: String? = null
    private val engineLock = Any()

    fun transcribe(audioFile: File): String? {
        val filename = settings.localSttModel
        if (filename.isBlank()) {
            Log.d(TAG, "Local Whisper model not configured.")
            return null
        }
        return transcribeWithModel(filename, audioFile)
    }

    fun translate(audioFile: File): String? {
        val filename = settings.localSttModel
        if (filename.isBlank()) {
            Log.d(TAG, "Local Whisper model not configured.")
            return null
        }
        return transcribeWithModel(filename, audioFile, WhisperMode.TRANSLATE)
    }

    fun transcribeWithModel(
        filename: String,
        audioFile: File,
        mode: WhisperMode = WhisperMode.TRANSCRIBE
    ): String? {
        if (filename.isBlank()) {
            Log.d(TAG, "Local Whisper model not configured.")
            return null
        }
        val file = modelDownloadManager.getModelFile(filename) ?: run {
            Log.d(TAG, "Local Whisper model not installed: $filename")
            return null
        }
        val vocabFile = resolveWhisperVocabFile(filename)
        if (vocabFile == null) {
            Log.d(TAG, "Whisper vocab not installed for $filename")
            return null
        }
        modelRuntime.ensureLoaded(filename, file.length()) { releaseModel(it) }
        synchronized(engineLock) {
            return try {
                val engine = getEngine(file.absolutePath, vocabFile.absolutePath, isMultilingual(filename))
                if (mode == WhisperMode.TRANSLATE && !filename.contains("translate")) {
                    Log.w(TAG, "Translate requested but model is not translate-capable: $filename")
                }
                Log.d(TAG, "Running Whisper inference on ${audioFile.name}")
                engine.transcribeFile(audioFile.absolutePath)
            } catch (e: Exception) {
                Log.e(TAG, "Whisper inference failed", e)
                null
            }
        }
    }

    fun prepareModel(filename: String): Boolean {
        if (filename.isBlank()) return false
        val file = modelDownloadManager.getModelFile(filename) ?: return false
        val vocabFile = resolveWhisperVocabFile(filename) ?: return false
        modelRuntime.ensureLoaded(filename, file.length()) { releaseModel(it) }
        synchronized(engineLock) {
            return try {
                getEngine(file.absolutePath, vocabFile.absolutePath, isMultilingual(filename))
                true
            } catch (e: Exception) {
                Log.e(TAG, "Whisper prepare failed", e)
                false
            }
        }
    }

    private fun resolveWhisperVocabFile(filename: String): File? {
        val vocabName = if (filename.contains(".en.")) {
            "filters_vocab_en.bin"
        } else {
            "filters_vocab_multilingual.bin"
        }
        return modelDownloadManager.getModelFile(vocabName)
    }

    private fun isMultilingual(filename: String): Boolean {
        return !filename.contains(".en.")
    }

    @Synchronized
    private fun getEngine(modelPath: String, vocabPath: String, multilingual: Boolean): com.sbf.assistant.whisper.WhisperEngineJava {
        val current = engine
        if (current != null && engineModel == modelPath && engineVocab == vocabPath) {
            return current
        }
        current?.deinitialize()

        fun initEngine(): com.sbf.assistant.whisper.WhisperEngineJava {
            val newEngine = com.sbf.assistant.whisper.WhisperEngineJava(context)
            val initialized = newEngine.initialize(modelPath, vocabPath, multilingual)
            if (!initialized) {
                throw IllegalStateException("Whisper engine initialization failed")
            }
            engine = newEngine
            engineModel = modelPath
            engineVocab = vocabPath
            return newEngine
        }

        return try {
            initEngine()
        } catch (e: OutOfMemoryError) {
            val evicted = modelRuntime.evictLeastRecentlyUsed()
            val currentName = File(modelPath).name
            if (evicted != null && evicted != currentName) {
                initEngine()
            } else {
                throw e
            }
        }
    }

    companion object {
        private const val TAG = "LocalWhisper"
    }

    fun releaseModel(filename: String? = null) {
        synchronized(engineLock) {
            if (engine == null) return
            if (filename == null || engineModel?.endsWith(filename) == true || engineModel == filename) {
                engine?.deinitialize()
                engine = null
                engineModel = null
                engineVocab = null
            }
        }
    }
}

data class LocalModelInfo(
    val id: String,
    val name: String,
    val status: String,
    val memoryMb: Int,
    val enabled: Boolean,
    val repoId: String,
    val filename: String,
    val installed: Boolean
)

object LocalModelRegistry {
    /**
     * Get list of installed models from ModelDownloadManager.
     * For LLM, use GeminiNanoService (AICore / ML Kit GenAI) instead of local models.
     */
    fun getInstalledModels(context: android.content.Context): List<LocalModelInfo> {
        val downloadManager = ModelDownloadManager(context)
        val installed = downloadManager.getInstalledModels()

        return installed.map { model ->
            val file = downloadManager.getModelFile(model.filename)
            val sizeMb = (file?.length() ?: 0L) / (1024 * 1024)

            LocalModelInfo(
                id = model.filename,
                name = model.name,
                status = "Instalado",
                memoryMb = sizeMb.toInt(),
                enabled = true,
                repoId = "",
                filename = model.filename,
                installed = true
            )
        }
    }

    fun getAvailableModels(context: android.content.Context, settings: SettingsManager): List<LocalModelInfo> {
        return getInstalledModels(context)
    }
}
