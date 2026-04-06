package com.sbf.assistant.realtime

import com.sbf.assistant.ToolDefinition

data class RealtimeSessionConfig(
    val voice: String = "alloy",
    val instructions: String = "",
    val inputAudioTranscriptionModel: String? = null,
    val vadType: String = "server_vad",
    val vadThreshold: Float = 0.5f,
    val silenceDurationMs: Int = 800,
    val tools: List<ToolDefinition> = emptyList()
) {
    fun toMap(): Map<String, Any> {
        val map = mutableMapOf<String, Any>(
            "voice" to voice,
            "turn_detection" to mapOf(
                "type" to vadType,
                "threshold" to vadThreshold,
                "silence_duration_ms" to silenceDurationMs
            )
        )
        if (instructions.isNotBlank()) {
            map["instructions"] = instructions
        }
        if (!inputAudioTranscriptionModel.isNullOrBlank()) {
            map["input_audio_transcription"] = mapOf(
                "model" to inputAudioTranscriptionModel
            )
        }
        return map
    }
}

data class RealtimeUsage(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val totalTokens: Int = 0
)

enum class RealtimeState {
    IDLE,
    CONNECTING,
    LISTENING,
    TRANSCRIBING,
    RESPONDING,
    SPEAKING,
    ERROR
}

interface RealtimeListener {
    fun onConnected() {}
    fun onSessionCreated(sessionId: String)
    fun onSpeechStarted()
    fun onSpeechStopped()
    fun onTranscriptDelta(text: String)
    fun onTranscriptDone(text: String)
    fun onTextDelta(text: String) {}
    fun onTextDone(text: String) {}
    fun onAudioDelta(pcm16Bytes: ByteArray)
    fun onAudioDone()
    fun onResponseDone(usage: RealtimeUsage?)
    fun onError(type: String, message: String)
    fun onDisconnected() {}
}
