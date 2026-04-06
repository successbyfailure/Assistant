package com.sbf.assistant.realtime

import android.util.Base64
import android.util.Log
import com.sbf.assistant.Endpoint
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class RealtimeClient(
    private val endpoint: Endpoint,
    private val model: String,
    private val listener: RealtimeListener
) {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null

    fun connect(sessionConfig: RealtimeSessionConfig) {
        if (webSocket != null) return
        val base = endpoint.baseUrl.trim().trimEnd('/')
        val wsBase = when {
            base.startsWith("https://") -> "wss://${base.removePrefix("https://")}"
            base.startsWith("http://") -> "ws://${base.removePrefix("http://")}"
            else -> "wss://$base"
        }
        val url = if (wsBase.endsWith("/v1")) {
            "$wsBase/realtime?model=$model"
        } else if (wsBase.endsWith("/v1/")) {
            "${wsBase}realtime?model=$model"
        } else {
            "$wsBase/v1/realtime?model=$model"
        }

        val request = Request.Builder()
            .url(url)
            .header("OpenAI-Beta", "realtime=v1")
            .apply {
                if (endpoint.apiKey.isNotBlank()) {
                    header("Authorization", "Bearer ${endpoint.apiKey}")
                }
            }
            .build()
        this.webSocket = client.newWebSocket(request, RealtimeWsListener(sessionConfig))
    }

    fun sendAudioChunk(pcm16Bytes: ByteArray) {
        val b64 = Base64.encodeToString(pcm16Bytes, Base64.NO_WRAP)
        sendEvent(
            mapOf(
                "type" to "input_audio_buffer.append",
                "audio" to b64
            )
        )
    }

    fun commitAudio() = sendEvent(mapOf("type" to "input_audio_buffer.commit"))
    fun clearAudio() = sendEvent(mapOf("type" to "input_audio_buffer.clear"))
    fun requestResponse() = sendEvent(mapOf("type" to "response.create"))
    fun cancelResponse() = sendEvent(mapOf("type" to "response.cancel"))
    fun disconnect() = webSocket?.close(1000, "done")

    fun updateSession(config: RealtimeSessionConfig) {
        sendEvent(
            mapOf(
                "type" to "session.update",
                "session" to config.toMap()
            )
        )
    }

    private fun sendEvent(event: Map<String, Any>) {
        val ws = webSocket ?: return
        ws.send(JSONObject(event).toString())
    }

    private inner class RealtimeWsListener(
        private val initialConfig: RealtimeSessionConfig
    ) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            listener.onConnected()
            updateSession(initialConfig)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val event = JSONObject(text)
                val type = event.optString("type")
                when (type) {
                    "session.created" -> {
                        val sessionId = event.optJSONObject("session")?.optString("id")
                            ?: event.optString("session_id")
                        if (sessionId.isNotBlank()) {
                            listener.onSessionCreated(sessionId)
                        }
                    }
                    "input_audio_buffer.speech_started" -> listener.onSpeechStarted()
                    "input_audio_buffer.speech_stopped" -> listener.onSpeechStopped()
                    "response.audio.delta" -> {
                        val b64 = event.optString("delta")
                        if (b64.isNotBlank()) {
                            listener.onAudioDelta(Base64.decode(b64, Base64.DEFAULT))
                        }
                    }
                    "response.audio.done" -> listener.onAudioDone()
                    "response.audio_transcript.delta",
                    "response.output_text.delta" -> {
                        val delta = event.optString("delta")
                        if (delta.isNotBlank()) {
                            listener.onTextDelta(delta)
                        }
                    }
                    "response.audio_transcript.done",
                    "response.output_text.done" -> {
                        val textDone = event.optString("text")
                        if (textDone.isNotBlank()) {
                            listener.onTextDone(textDone)
                        }
                    }
                    "conversation.item.input_audio_transcription.delta" -> {
                        val delta = event.optString("delta")
                        if (delta.isNotBlank()) {
                            listener.onTranscriptDelta(delta)
                        }
                    }
                    "conversation.item.input_audio_transcription.completed" -> {
                        val transcript = event.optString("transcript")
                        if (transcript.isNotBlank()) {
                            listener.onTranscriptDone(transcript)
                        }
                    }
                    "response.done" -> {
                        val usage = parseUsage(event.optJSONObject("response")?.optJSONObject("usage"))
                        listener.onResponseDone(usage)
                    }
                    "error" -> {
                        val error = event.optJSONObject("error")
                        listener.onError(
                            error?.optString("type").orEmpty().ifBlank { "realtime_error" },
                            error?.optString("message").orEmpty().ifBlank { "Unknown realtime error" }
                        )
                    }
                    else -> parseFallbackEvent(event, type)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed parsing realtime event", e)
                listener.onError("parse_error", e.message ?: "Invalid realtime event")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val body = response?.body?.string()?.take(500).orEmpty()
            val msg = if (body.isBlank()) t.message.orEmpty() else "${t.message}. $body"
            listener.onError("connection_failure", msg.ifBlank { "Realtime websocket failure" })
            this@RealtimeClient.webSocket = null
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            this@RealtimeClient.webSocket = null
            listener.onDisconnected()
        }
    }

    private fun parseUsage(usageJson: JSONObject?): RealtimeUsage? {
        if (usageJson == null) return null
        val input = usageJson.optInt("input_tokens")
        val output = usageJson.optInt("output_tokens")
        val total = usageJson.optInt("total_tokens")
        return RealtimeUsage(input, output, total)
    }

    private fun parseFallbackEvent(event: JSONObject, type: String) {
        if (type.contains("transcription") && event.has("delta")) {
            val delta = event.optString("delta")
            if (delta.isNotBlank()) {
                listener.onTranscriptDelta(delta)
            }
            return
        }
        if (type.contains("transcription") && event.has("transcript")) {
            val transcript = event.optString("transcript")
            if (transcript.isNotBlank()) {
                listener.onTranscriptDone(transcript)
            }
            return
        }
        if (type.contains("audio") && event.has("delta")) {
            val b64 = event.optString("delta")
            if (b64.isNotBlank()) {
                listener.onAudioDelta(Base64.decode(b64, Base64.DEFAULT))
            }
            return
        }
        val output = event.optJSONArray("output") ?: return
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            val content = item.optJSONArray("content") ?: continue
            parseContent(content)
        }
    }

    private fun parseContent(content: JSONArray) {
        for (i in 0 until content.length()) {
            val c = content.optJSONObject(i) ?: continue
            val type = c.optString("type")
            when (type) {
                "audio" -> {
                    val b64 = c.optString("audio")
                    if (b64.isNotBlank()) {
                        listener.onAudioDelta(Base64.decode(b64, Base64.DEFAULT))
                    }
                }
                "output_text" -> {
                    val text = c.optString("text")
                    if (text.isNotBlank()) {
                        listener.onTextDone(text)
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "RealtimeClient"
    }
}
