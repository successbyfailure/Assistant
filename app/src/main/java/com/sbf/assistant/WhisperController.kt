package com.sbf.assistant

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.File
import java.net.SocketTimeoutException

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

    fun startRecording(
        usePcm: Boolean = false,
        autoStopOnSilence: Boolean = false,
        onSilenceDetected: (() -> Unit)? = null,
        onReadyToSpeak: (() -> Unit)? = null,
        onAmplitudeUpdate: ((Float) -> Unit)? = null
    ): File? {
        currentFile = audioRecorder.startRecording(
            usePcm = usePcm,
            autoStopOnSilence = autoStopOnSilence,
            onSilenceDetected = onSilenceDetected,
            onReadyToSpeak = onReadyToSpeak,
            onAmplitudeUpdate = onAmplitudeUpdate
        )
        isRecording = currentFile != null
        return currentFile
    }

    fun cancelRecording() {
        audioRecorder.stopRecording()
        isRecording = false
        val file = currentFile
        currentFile = null
        if (file != null) {
            audioRecorder.deleteFile(file)
        }
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

        OpenAiClient(endpoint).transcribeAudio(file, config.modelName) { text, _, error ->
            if (text != null) {
                audioRecorder.deleteFile(file)
                mainHandler.post { onResult(text, null) }
                return@transcribeAudio
            }
            val remoteError = classifyRemoteError(error)
            val canFallbackToLocal = settingsManager.localSttEnabled && settingsManager.localSttModel.isNotBlank()
            if (!canFallbackToLocal) {
                audioRecorder.deleteFile(file)
                mainHandler.post { onResult(null, remoteError) }
                return@transcribeAudio
            }
            scope.launch(Dispatchers.IO) {
                val prepared = localWhisper.prepareModel(settingsManager.localSttModel)
                val localText = if (prepared) localWhisper.transcribe(file) else null
                audioRecorder.deleteFile(file)
                withContext(Dispatchers.Main) {
                    if (!localText.isNullOrBlank()) {
                        onResult(localText, null)
                    } else {
                        onResult(null, "$remoteError. Fallback local no disponible")
                    }
                }
            }
        }
    }

    private fun classifyRemoteError(error: Throwable?): String {
        return when (error) {
            is OpenAiClient.ApiError -> when (error.code) {
                401, 403 -> "Error de autenticación STT, verifica la API key"
                503 -> "Modelo STT no disponible, intenta de nuevo"
                408 -> "Tiempo de espera agotado en STT"
                else -> error.message ?: "Error STT remoto"
            }
            is SocketTimeoutException -> "Tiempo de espera agotado en STT"
            is IOException -> "Error de red STT, revisa la conexión"
            null -> "Error STT remoto desconocido"
            else -> error.message ?: "Error STT remoto"
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
