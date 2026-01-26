package com.sbf.assistant

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class WhisperController(
    private val settingsManager: SettingsManager,
    private val audioRecorder: AudioRecorder,
    private val localWhisper: LocalWhisperService,
    private val scope: CoroutineScope
) {
    private var currentFile: File? = null
    private var isRecording = false
    private val mainHandler = Handler(Looper.getMainLooper())

    fun isRecording(): Boolean = isRecording

    fun startRecording(usePcm: Boolean = false): File? {
        currentFile = audioRecorder.startRecording(usePcm = usePcm)
        isRecording = currentFile != null
        return currentFile
    }

    fun stopAndTranscribe(
        config: ModelConfig,
        onResult: (text: String?, error: String?) -> Unit
    ) {
        audioRecorder.stopRecording()
        isRecording = false

        val file = currentFile
        currentFile = null
        if (file == null) {
            onResult(null, "Audio no disponible")
            return
        }

        if (config.endpointId == "local") {
            scope.launch(Dispatchers.IO) {
                val localText = localWhisper.transcribe(file)
                audioRecorder.deleteFile(file)
                withContext(Dispatchers.Main) {
                    val error = if (localText == null) "Local Whisper no disponible" else null
                    onResult(localText, error)
                }
            }
            return
        }

        val endpoint = settingsManager.getEndpoint(config.endpointId)
        if (endpoint == null) {
            audioRecorder.deleteFile(file)
            mainHandler.post { onResult(null, "Endpoint no encontrado") }
            return
        }

        OpenAiClient(endpoint).transcribeAudio(file, config.modelName) { text, error ->
            audioRecorder.deleteFile(file)
            mainHandler.post { onResult(text, error?.message) }
        }
    }

    suspend fun prepareLocalModel(): Boolean {
        val filename = settingsManager.localSttModel
        if (filename.isBlank()) return false
        return withContext(Dispatchers.IO) {
            localWhisper.prepareModel(filename)
        }
    }

    fun cleanup() {
        audioRecorder.cleanup()
        currentFile = null
        isRecording = false
    }
}
