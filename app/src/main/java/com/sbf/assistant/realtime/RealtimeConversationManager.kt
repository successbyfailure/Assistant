package com.sbf.assistant.realtime

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.sbf.assistant.AudioRecorder
import com.sbf.assistant.Category
import com.sbf.assistant.SettingsManager

class RealtimeConversationManager(
    context: Context,
    private val settingsManager: SettingsManager,
    private val onTranscript: (String) -> Unit,
    private val onResponse: (String) -> Unit,
    private val onStateChange: (RealtimeState) -> Unit,
    private val onError: (String) -> Unit
) {
    enum class RealtimeState {
        IDLE,
        CONNECTING,
        LISTENING,
        TRANSCRIBING,
        RESPONDING,
        SPEAKING
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioRecorder = AudioRecorder(appContext)
    private val audioPlayer = RealtimeAudioPlayer()

    private var realtimeClient: RealtimeClient? = null
    private var currentState = RealtimeState.IDLE
    private var isActive = false
    private val responseBuffer = StringBuilder()

    fun isSessionActive(): Boolean = isActive

    fun startSession() {
        if (isActive) return
        val agentConfig = settingsManager.getCategoryConfig(Category.AGENT).primary
        if (agentConfig == null || agentConfig.endpointId == "local" || agentConfig.endpointId == "system") {
            onError("Realtime requiere endpoint remoto en categoría AGENT")
            return
        }
        val endpoint = settingsManager.getEndpoint(agentConfig.endpointId)
        if (endpoint == null) {
            onError("Endpoint AGENT no encontrado")
            return
        }

        val model = settingsManager.realtimeModel.ifBlank { agentConfig.modelName }
        if (model.isBlank()) {
            onError("Configura realtimeModel o un modelo AGENT")
            return
        }

        isActive = true
        responseBuffer.clear()
        setState(RealtimeState.CONNECTING)
        val sessionConfig = RealtimeSessionConfig(
            voice = settingsManager.realtimeVoice,
            instructions = settingsManager.agentSystemPrompt.trim(),
            vadType = "server_vad",
            silenceDurationMs = 800
        )

        realtimeClient = RealtimeClient(
            endpoint = endpoint,
            model = model,
            listener = object : RealtimeListener {
                override fun onConnected() {
                    setState(RealtimeState.LISTENING)
                    startStreamingMic()
                }

                override fun onSessionCreated(sessionId: String) = Unit

                override fun onSpeechStarted() {
                    setState(RealtimeState.TRANSCRIBING)
                }

                override fun onSpeechStopped() {
                    setState(RealtimeState.RESPONDING)
                    realtimeClient?.commitAudio()
                    realtimeClient?.requestResponse()
                }

                override fun onTranscriptDelta(text: String) = Unit

                override fun onTranscriptDone(text: String) {
                    mainHandler.post { onTranscript(text) }
                }

                override fun onTextDelta(text: String) {
                    responseBuffer.append(text)
                }

                override fun onTextDone(text: String) {
                    mainHandler.post {
                        onResponse(text.ifBlank { responseBuffer.toString() })
                    }
                    responseBuffer.clear()
                }

                override fun onAudioDelta(pcm16Bytes: ByteArray) {
                    setState(RealtimeState.SPEAKING)
                    audioPlayer.start()
                    audioPlayer.write(pcm16Bytes)
                }

                override fun onAudioDone() {
                    if (isActive) {
                        setState(RealtimeState.LISTENING)
                    }
                }

                override fun onResponseDone(usage: RealtimeUsage?) = Unit

                override fun onError(type: String, message: String) {
                    mainHandler.post {
                        onError("$type: $message")
                    }
                    stopSession()
                }

                override fun onDisconnected() {
                    stopSession()
                }
            }
        )
        realtimeClient?.connect(sessionConfig)
    }

    private fun startStreamingMic() {
        audioRecorder.startStreamingRecording(
            onChunk = { chunk -> realtimeClient?.sendAudioChunk(chunk) },
            chunkSizeMs = 100
        ) { amplitude ->
            // State callback is enough for now; UI amplitudes can be added when needed.
        }
    }

    fun interrupt() {
        if (!isActive) return
        realtimeClient?.cancelResponse()
        realtimeClient?.clearAudio()
        audioPlayer.stop()
        setState(RealtimeState.LISTENING)
    }

    fun stopSession() {
        if (!isActive) return
        isActive = false
        audioRecorder.stopRecording()
        audioRecorder.cleanup()
        audioPlayer.stop()
        realtimeClient?.disconnect()
        realtimeClient = null
        responseBuffer.clear()
        setState(RealtimeState.IDLE)
    }

    private fun setState(state: RealtimeState) {
        if (state == currentState) return
        currentState = state
        mainHandler.post { onStateChange(state) }
    }
}
