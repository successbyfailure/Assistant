package com.sbf.assistant

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONObject
import org.json.JSONArray
import java.io.IOException
import java.io.File
import android.util.Log

class OpenAiClient(private val endpoint: Endpoint) {
    private val client = HttpClientProvider.streaming

    interface StreamCallback {
        fun onToken(token: String)
        fun onToolCalls(toolCalls: List<ToolCall>)
        fun onComplete()
        fun onError(e: Throwable)
    }

    interface ModelsCallback {
        fun onSuccess(models: List<String>)
        fun onError(e: Throwable)
    }

    class ApiError(val code: Int, message: String) : Exception(message)

    fun fetchModels(callback: ModelsCallback) {
        val baseUrl = normalizedBaseUrl()
        val url = if (baseUrl.endsWith("/")) "${baseUrl}models"
                  else "${baseUrl}/models"

        val requestBuilder = Request.Builder()
            .url(url)
            .get()

        if (endpoint.apiKey.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer ${endpoint.apiKey}")
        }

        client.newCall(requestBuilder.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback.onError(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string()?.take(2000).orEmpty()
                    Log.e(TAG, "Models error ${response.code}: $errorBody")
                    val errorMsg = when (response.code) {
                        401 -> "API Key inválida o expirada"
                        402 -> "Plan gratuito agotado o requiere pago"
                        429 -> "Límite de solicitudes alcanzado. Espera un momento."
                        404 -> "Endpoint no encontrado (404). Verifica la URL."
                        400 -> "Solicitud inválida (400)"
                        else -> "Error del servidor: ${response.code}"
                    }
                    callback.onError(ApiError(response.code, errorMsg))
                    return
                }
                try {
                    val body = response.body?.string() ?: ""
                    val jsonResponse = JSONObject(body)
                    val data = jsonResponse.getJSONArray("data")
                    val models = mutableListOf<String>()
                    for (i in 0 until data.length()) {
                        models.add(data.getJSONObject(i).getString("id"))
                    }
                    callback.onSuccess(models)
                } catch (e: Exception) {
                    callback.onError(e)
                }
            }
        })
    }

    fun transcribeAudio(file: File, modelName: String, callback: (String?, Throwable?) -> Unit) {
        val baseUrl = normalizedBaseUrl()
        val url = if (baseUrl.endsWith("/")) "${baseUrl}audio/transcriptions"
                  else "${baseUrl}/audio/transcriptions"

        val mimeType = guessAudioMimeType(file)
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, file.asRequestBody(mimeType.toMediaType()))
            .addFormDataPart("model", modelName)
            .build()
        Log.d(TAG, "STT request model=$modelName url=$url mime=$mimeType size=${file.length()}")

        val requestBuilder = Request.Builder()
            .url(url)
            .post(requestBody)

        if (endpoint.apiKey.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer ${endpoint.apiKey}")
        }

        client.newCall(requestBuilder.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(null, e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string()?.take(2000).orEmpty()
                    Log.e(TAG, "STT error ${response.code}: $errorBody")
                    callback(null, ApiError(response.code, "STT Error: ${response.code} $errorBody"))
                    return
                }
                try {
                    val json = JSONObject(response.body?.string() ?: "{}")
                    callback(json.optString("text"), null)
                } catch (e: Exception) {
                    callback(null, e)
                }
            }
        })
    }

    fun generateSpeech(text: String, modelName: String, callback: (File?, Throwable?) -> Unit) {
        val baseUrl = normalizedBaseUrl()
        val url = if (baseUrl.endsWith("/")) "${baseUrl}audio/speech"
                  else "${baseUrl}/audio/speech"

        val json = JSONObject().apply {
            put("model", modelName)
            put("input", text)
            put("voice", "alloy")
        }

        val requestBuilder = Request.Builder()
            .url(url)
            .post(json.toString().toRequestBody("application/json".toMediaType()))

        if (endpoint.apiKey.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer ${endpoint.apiKey}")
        }

        client.newCall(requestBuilder.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(null, e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string()?.take(2000).orEmpty()
                    Log.e(TAG, "TTS error ${response.code}: $errorBody")
                    callback(null, ApiError(response.code, "TTS Error: ${response.code}"))
                    return
                }
                try {
                    val tempFile = File.createTempFile("tts_", ".mp3")
                    response.body?.source()?.let { source ->
                        tempFile.outputStream().use { output ->
                            output.write(source.readByteArray())
                        }
                        callback(tempFile, null)
                    } ?: callback(null, Exception("Empty body"))
                } catch (e: Exception) {
                    callback(null, e)
                }
            }
        })
    }

    fun streamChatCompletion(modelName: String, prompt: String, callback: StreamCallback): EventSource {
        val messages = listOf(LlmMessage(role = "user", content = prompt))
        return streamChatCompletion(modelName, messages, emptyList(), callback)
    }

    fun streamChatCompletion(
        modelName: String,
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        callback: StreamCallback
    ): EventSource {
        val baseUrl = normalizedBaseUrl()
        val url = if (baseUrl.endsWith("/")) "${baseUrl}chat/completions"
                  else "${baseUrl}/chat/completions"
        
        val toolsToSend = if (supportsTools()) tools.map { normalizeTool(it) } else emptyList()
        val json = JSONObject().apply {
            put("model", modelName)
            put("stream", true)
            val jsonMessages = JSONArray().apply {
                messages.forEach { put(it.toJson()) }
            }
            put("messages", jsonMessages)
            if (toolsToSend.isNotEmpty()) {
                val toolArray = JSONArray()
                toolsToSend.forEach { toolArray.put(it.toOpenAiJson()) }
                put("tools", toolArray)
            }
        }
        Log.d(TAG, "Chat request model=$modelName messages=${messages.size} tools=${toolsToSend.size} endpointType=${endpoint.type}")
        if (toolsToSend.isNotEmpty()) {
            Log.d(TAG, "Tool schema sample: ${toolsToSend.first().toOpenAiJson()}")
            toolsToSend.forEach { tool ->
                val required = tool.parameters.opt("required")
                if (required != null && required !is JSONArray) {
                    Log.w(TAG, "Invalid required type for tool ${tool.name}: ${required.javaClass}")
                }
            }
        }

        val requestBuilder = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .post(json.toString().toRequestBody("application/json".toMediaType()))

        if (endpoint.apiKey.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer ${endpoint.apiKey}")
        }

        val toolBuilders = mutableMapOf<Int, ToolCallBuilder>()
        val eventSourceListener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") {
                    toolBuilders.clear()
                    callback.onComplete()
                    return
                }
                try {
                    val jsonResponse = JSONObject(data)
                    val choices = jsonResponse.getJSONArray("choices")
                    if (choices.length() > 0) {
                        val choice = choices.getJSONObject(0)
                        val delta = choice.getJSONObject("delta")
                        if (delta.has("content")) {
                            callback.onToken(delta.getString("content"))
                        }
                        if (delta.has("tool_calls")) {
                            val toolCalls = delta.getJSONArray("tool_calls")
                            for (i in 0 until toolCalls.length()) {
                                val toolCall = toolCalls.getJSONObject(i)
                                val index = toolCall.optInt("index", i)
                                val builder = toolBuilders.getOrPut(index) { ToolCallBuilder() }
                                val idValue = toolCall.optString("id")
                                if (idValue.isNotBlank()) {
                                    builder.id = idValue
                                }
                                val function = toolCall.optJSONObject("function")
                                if (function != null) {
                                    val nameValue = function.optString("name")
                                    if (nameValue.isNotBlank()) {
                                        builder.name = nameValue
                                    }
                                    val argumentsValue = function.optString("arguments")
                                    if (argumentsValue.isNotBlank()) {
                                        builder.arguments.append(argumentsValue)
                                    }
                                }
                            }
                        }
                        val finishReason = choice.optString("finish_reason")
                        if (finishReason == "tool_calls") {
                            val built = toolBuilders.toSortedMap().values.mapNotNull { it.build() }
                            Log.d(TAG, "Tool calls detected: ${built.size}")
                            if (built.isNotEmpty()) {
                                callback.onToolCalls(built)
                            }
                            toolBuilders.clear()
                        }
                    }
                } catch (e: Exception) {
                    // Ignore
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                toolBuilders.clear()
                val error = if (response != null && !response.isSuccessful) {
                    val errorBody = response.body?.string()?.take(2000).orEmpty()
                    Log.e(TAG, "Chat error ${response.code}: $errorBody")
                    val msg = when (response.code) {
                        401 -> "Error 401: API Key no autorizada"
                        429 -> "Error 429: Demasiadas peticiones"
                        400 -> "Error 400: Solicitud invalida"
                        else -> "Error ${response.code}: ${response.message}"
                    }
                    ApiError(response.code, msg)
                } else {
                    t ?: Exception("Fallo en la conexión")
                }
                callback.onError(error)
            }
        }

        return EventSources.createFactory(client).newEventSource(requestBuilder.build(), eventSourceListener)
    }

    private class ToolCallBuilder {
        var id: String = ""
        var name: String = ""
        val arguments: StringBuilder = StringBuilder()

        fun build(): ToolCall? {
            if (name.isBlank()) return null
            return ToolCall(id = id.ifBlank { "tool_${hashCode()}" }, name = name, arguments = arguments.toString())
        }
    }

    companion object {
        private const val TAG = "OpenAiClient"
    }

    private fun supportsTools(): Boolean {
        return endpoint.type in setOf(
            "openai",
            "generic",
            "ollama_cloud",
            "ollama_self-hosted",
            "ollama_self_hosted",
            "localai"
        )
    }

    private fun normalizedBaseUrl(): String {
        var base = endpoint.baseUrl.trim()
        if (endpoint.type == "openai" && !base.endsWith("/v1") && !base.endsWith("/v1/")) {
            base = base.trimEnd('/')
            base = "$base/v1"
        }
        return base
    }

    private fun guessAudioMimeType(file: File): String {
        val name = file.name.lowercase()
        return when {
            name.endsWith(".m4a") || name.endsWith(".mp4") -> "audio/mp4"
            name.endsWith(".wav") -> "audio/wav"
            name.endsWith(".ogg") -> "audio/ogg"
            name.endsWith(".mp3") -> "audio/mpeg"
            else -> "application/octet-stream"
        }
    }

    private fun normalizeTool(tool: ToolDefinition): ToolDefinition {
        val normalizedParams = normalizeParameters(tool.parameters)
        return tool.copy(parameters = normalizedParams)
    }

    private fun normalizeParameters(parameters: JSONObject): JSONObject {
        val normalized = JSONObject(parameters.toString())
        if (normalized.has("required")) {
            val required = normalized.opt("required")
            when (required) {
                is JSONArray -> Unit
                is String -> {
                    normalized.put("required", JSONArray().put(required))
                    Log.w(TAG, "Normalized required string to array for schema.")
                }
                is List<*> -> {
                    normalized.put("required", JSONArray(required))
                    Log.w(TAG, "Normalized required list to array for schema.")
                }
                else -> {
                    normalized.remove("required")
                    Log.w(TAG, "Removed invalid required type: ${required?.javaClass}")
                }
            }
        }
        return normalized
    }
}
