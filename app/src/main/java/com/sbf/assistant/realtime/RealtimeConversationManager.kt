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
    // Tracks whether we are currently receiving audio deltas from the server
    private var audioReceiving = false

    fun isSessionActive(): Boolean = isActive

    fun startSession() {
        if (isActive) return

        val realtimeConfig = settingsManager.getCategoryConfig(Category.REALTIME).primary
        if (realtimeConfig == null) {
            onError("Configura un endpoint y modelo en la categoría REALTIME (ModelConfig)")
            return
        }
        if (realtimeConfig.endpointId == "local" || realtimeConfig.endpointId == "system") {
            onError("Realtime requiere un endpoint remoto en la categoría REALTIME")
            return
        }
        val endpoint = settingsManager.getEndpoint(realtimeConfig.endpointId)
        if (endpoint == null) {
            onError("Endpoint REALTIME no encontrado")
            return
        }
        val model = realtimeConfig.modelName
        if (model.isBlank()) {
            onError("Configura el modelo en la categoría REALTIME")
            return
        }

        isActive = true
        audioReceiving = false
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
                    if (!audioReceiving) {
                        audioReceiving = true
                        audioPlayer.start()
                    }
                    setState(RealtimeState.SPEAKING)
                    audioPlayer.write(pcm16Bytes)
                }

                override fun onAudioDone() {
                    audioReceiving = false
                    if (isActive) {
                        // Wait until the AudioTrack actually finishes playing the buffered
                        // audio before transitioning back to LISTENING. Without this, the
                        // mic would pick up the tail of the assistant's own voice.
                        audioPlayer.markEndOfStream {
                            mainHandler.post {
                                if (isActive) setState(RealtimeState.LISTENING)
                            }
                        }
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
        ) { /* amplitude update not needed here */ }
    }

    fun interrupt() {
        if (!isActive) return
        realtimeClient?.cancelResponse()
        realtimeClient?.clearAudio()
        audioReceiving = false
        audioPlayer.stop()
        setState(RealtimeState.LISTENING)
    }

    fun stopSession() {
        if (!isActive) return
        isActive = false
        audioReceiving = false
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
