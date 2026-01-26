package com.sbf.assistant

import android.util.Log
import com.sbf.assistant.llm.LocalLlmService
import com.sbf.assistant.llm.MediaPipeLlmService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.CancellationException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Shared logic for chat processing between ChatActivity and AssistantSession.
 * Handles LLM message management, tool execution, and streaming responses.
 */
interface ChatRequestHandle {
    fun cancel()
}

interface ChatStreamClient {
    fun streamChatCompletion(
        modelName: String,
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        callback: OpenAiClient.StreamCallback
    ): ChatRequestHandle
}

class OpenAiChatStreamClient(private val endpoint: Endpoint) : ChatStreamClient {
    private val client = OpenAiClient(endpoint)

    override fun streamChatCompletion(
        modelName: String,
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        callback: OpenAiClient.StreamCallback
    ): ChatRequestHandle {
        val eventSource = client.streamChatCompletion(modelName, messages, tools, callback)
        return object : ChatRequestHandle {
            override fun cancel() {
                eventSource.cancel()
            }
        }
    }
}

class ChatController(
    private val settingsManager: SettingsManager,
    private val toolExecutor: ToolExecutor,
    private val toolRegistry: ToolRegistry,
    private val geminiNano: GeminiNanoService?,
    private val localLlm: LocalLlmService?,
    private val mediaPipeLlm: MediaPipeLlmService?,
    private val modelDownloadManager: ModelDownloadManager,
    private val scope: CoroutineScope,
    private val chatClientFactory: (Endpoint) -> ChatStreamClient = { OpenAiChatStreamClient(it) }
) {
    private val llmMessages = mutableListOf<LlmMessage>()
    private var currentRequest: ChatRequestHandle? = null
    private var cancelRequested = false
    private val canceledToolCalls = mutableSetOf<String>()
    private var toolExecutionJob: Job? = null
    private var pendingToolCalls: List<ToolCall>? = null
    private var pendingConfig: ModelConfig? = null
    private var pendingIsPrimary = true
    private var pendingCallbacks: Callbacks? = null
    private var toolRunId = 0
    private val toolExecutorPool = Executors.newCachedThreadPool()
    private val toolFutures = mutableMapOf<String, Future<ToolResult>>()

    interface Callbacks {
        fun onStatusUpdate(status: String)
        fun onResponseToken(token: String)
        fun onResponseComplete(fullResponse: String)
        fun onToolExecutionStart()
        fun onToolCalls(toolCalls: List<ToolCall>)
        fun onError(error: String, wasPrimary: Boolean)
        fun handleToolGate(call: ToolCall): ToolResult?
    }

    fun processQuery(query: String, callbacks: Callbacks) {
        cancelRequested = false
        canceledToolCalls.clear()
        pendingToolCalls = null
        pendingConfig = null
        pendingCallbacks = null
        val config = settingsManager.getCategoryConfig(Category.AGENT)
        val primary = config.primary
        if (primary == null) {
            callbacks.onStatusUpdate("Configure a primary model first")
            return
        }

        callbacks.onStatusUpdate("Thinking...")

        maybeAddSystemPrompt()
        val userPrefix = settingsManager.agentUserPromptPrefix.trim()
        val llmQuery = if (userPrefix.isBlank()) query else "$userPrefix\n$query"
        llmMessages.add(LlmMessage(role = "user", content = llmQuery))

        // Check if it's a local model
        if (primary.endpointId == "local") {
            runLocalInference(primary.modelName, callbacks)
            return
        }
        executeChatRequest(primary, isPrimary = true, callbacks)
    }

    fun cancelCurrentRequest() {
        cancelRequested = true
        currentRequest?.cancel()
        currentRequest = null
    }

    fun cancelToolCalls(callIds: List<String>) {
        toolRunId += 1
        canceledToolCalls.addAll(callIds)
        callIds.forEach { id ->
            toolFutures.remove(id)?.cancel(true)
        }
        val callbacks = pendingCallbacks
        val config = pendingConfig
        val toolCalls = pendingToolCalls
        if (callbacks != null && config != null && toolCalls != null) {
            toolExecutionJob?.cancel()
            val cancelled = toolCalls.filter { callIds.contains(it.id) }
            if (cancelled.isEmpty()) return
            scope.launch(Dispatchers.IO) {
                cancelled.forEach { call ->
                    llmMessages.add(
                        LlmMessage(
                            role = "tool",
                            content = "ERROR: Tool cancelada por el usuario.",
                            toolCallId = call.id,
                            name = call.name
                        )
                    )
                }
                scope.launch(Dispatchers.Main) {
                    executeChatRequest(config, pendingIsPrimary, callbacks)
                }
            }
        }
    }

    private fun maybeAddSystemPrompt() {
        if (llmMessages.isNotEmpty()) return
        val prompt = settingsManager.agentSystemPrompt.trim()
        if (prompt.isNotBlank()) {
            llmMessages.add(LlmMessage(role = "system", content = prompt))
        }
    }

    private fun executeChatRequest(config: ModelConfig, isPrimary: Boolean, callbacks: Callbacks) {
        val endpoint = settingsManager.getEndpoint(config.endpointId)
        if (endpoint == null) {
            handleRequestError(Exception("Endpoint not found"), isPrimary, callbacks)
            return
        }

        val client = chatClientFactory(endpoint)
        var fullResponse = ""
        var toolCallsHandled = false

        cancelRequested = false
        currentRequest = client.streamChatCompletion(config.modelName, llmMessages, toolRegistry.getTools(), object : OpenAiClient.StreamCallback {
            override fun onToken(token: String) {
                scope.launch {
                    if (cancelRequested) return@launch
                    fullResponse += token
                    callbacks.onResponseToken(token)
                }
            }

            override fun onToolCalls(toolCalls: List<ToolCall>) {
                if (cancelRequested) return
                scope.launch(Dispatchers.Main) {
                    if (cancelRequested) return@launch
                    toolCallsHandled = true
                    callbacks.onToolCalls(toolCalls)
                    callbacks.onToolExecutionStart()
                    Log.d(TAG, "Tool calls received: ${toolCalls.size}")
                    llmMessages.add(LlmMessage(role = "assistant", toolCalls = toolCalls))
                    currentRequest = null
                    pendingToolCalls = toolCalls
                    pendingConfig = config
                    pendingIsPrimary = isPrimary
                    pendingCallbacks = callbacks
                    val runId = toolRunId + 1
                    toolRunId = runId
                    toolExecutionJob = scope.launch(Dispatchers.IO) {
                        val results = mutableListOf<ToolResult>()
                        for (call in toolCalls) {
                            if (cancelRequested || runId != toolRunId) return@launch
                            if (canceledToolCalls.contains(call.id)) {
                                results.add(ToolResult(call.id, call.name, "Tool cancelada por el usuario.", true))
                                continue
                            }
                            val timeoutMs = settingsManager.toolTimeoutMs
                            val future = toolExecutorPool.submit<ToolResult> {
                                if (canceledToolCalls.contains(call.id)) {
                                    return@submit ToolResult(call.id, call.name, "Tool cancelada por el usuario.", true)
                                }
                                // Include gating in the timeout window.
                                val gateResult = callbacks.handleToolGate(call)
                                gateResult ?: toolExecutor.execute(call)
                            }
                            toolFutures[call.id] = future
                            val executed = try {
                                future.get(timeoutMs, TimeUnit.MILLISECONDS)
                            } catch (e: TimeoutException) {
                                future.cancel(true)
                                ToolResult(call.id, call.name, "Timeout ejecutando tool.", true)
                            } catch (e: CancellationException) {
                                ToolResult(call.id, call.name, "Tool cancelada por el usuario.", true)
                            } catch (e: Exception) {
                                future.cancel(true)
                                ToolResult(call.id, call.name, "Error ejecutando tool: ${e.message}", true)
                            } finally {
                                toolFutures.remove(call.id)
                            }
                            if (canceledToolCalls.contains(call.id)) {
                                results.add(ToolResult(call.id, call.name, "Tool cancelada por el usuario.", true))
                            } else {
                                results.add(executed)
                            }
                        }
                        results.forEach { result ->
                            val content = if (result.isError) "ERROR: ${result.output}" else result.output
                            llmMessages.add(
                                LlmMessage(
                                    role = "tool",
                                    content = content,
                                    toolCallId = result.callId,
                                    name = result.name
                                )
                            )
                        }
                        launch(Dispatchers.Main) {
                            if (cancelRequested || runId != toolRunId) return@launch
                            callbacks.onStatusUpdate("Thinking...")
                            executeChatRequest(config, isPrimary, callbacks)
                        }
                    }
                }
            }

            override fun onComplete() {
                scope.launch {
                    if (cancelRequested) return@launch
                    currentRequest = null
                    if (toolCallsHandled) return@launch
                    if (fullResponse.isNotBlank()) {
                        llmMessages.add(LlmMessage(role = "assistant", content = fullResponse))
                    }
                    callbacks.onResponseComplete(fullResponse)
                }
            }

            override fun onError(e: Throwable) {
                scope.launch {
                    if (cancelRequested) return@launch
                    currentRequest = null
                    handleRequestError(e, isPrimary, callbacks)
                }
            }
        })
    }

    private fun handleRequestError(e: Throwable, wasPrimary: Boolean, callbacks: Callbacks) {
        if (wasPrimary) {
            val config = settingsManager.getCategoryConfig(Category.AGENT)
            val backup = config.backup
            if (backup != null) {
                callbacks.onStatusUpdate("Primary failed, trying Backup...")
                executeChatRequest(backup, isPrimary = false, callbacks)
            } else {
                callbacks.onError("Primary Error: ${e.message}", wasPrimary = true)
            }
        } else {
            callbacks.onError("Backup Error: ${e.message}", wasPrimary = false)
        }
    }

    fun clearHistory() {
        llmMessages.clear()
    }

    private fun runLocalInference(modelName: String, callbacks: Callbacks) {
        val resolved = when {
            modelName == MODEL_GEMINI_NANO || modelName.endsWith(".litertlm", ignoreCase = true) -> MODEL_GEMINI_NANO
            modelName == MODEL_MEDIAPIPE || modelName.endsWith(".task", ignoreCase = true) -> MODEL_MEDIAPIPE
            modelName == MODEL_TFLITE || modelName.endsWith(".tflite", ignoreCase = true) -> MODEL_TFLITE
            else -> ""
        }

        val toolsEnabled = settingsManager.toolsEnabled && toolRegistry.getTools().isNotEmpty()
        if (toolsEnabled) {
            runLocalInferenceWithTools(modelName, resolved, callbacks)
            return
        }
        scope.launch(Dispatchers.IO) {
            val ensureError = ensureLocalModelReady(resolved, modelName, callbacks)
            if (ensureError != null) {
                withContext(Dispatchers.Main) { callbacks.onError(ensureError, true) }
                return@launch
            }
            withContext(Dispatchers.Main) { callbacks.onStatusUpdate("Thinking...") }
            val flow = when (resolved) {
                MODEL_GEMINI_NANO -> geminiNano!!.generateContentStream(
                    buildLocalPrompt(includeTools = false, forceFinal = false)
                )
                MODEL_MEDIAPIPE -> mediaPipeLlm!!.generateContentStream(
                    buildLocalPrompt(includeTools = false, forceFinal = false)
                )
                MODEL_TFLITE -> localLlm!!.generateContentStream(
                    buildLocalPrompt(includeTools = false, forceFinal = false)
                )
                else -> {
                    withContext(Dispatchers.Main) {
                        callbacks.onError("Modelo local desconocido: $modelName", true)
                    }
                    return@launch
                }
            }
            try {
                var fullResponse = ""
                kotlinx.coroutines.withTimeout(LOCAL_INFERENCE_TIMEOUT_MS) {
                    flow.collect { chunk ->
                        fullResponse += chunk
                        launch(Dispatchers.Main) {
                            callbacks.onResponseToken(chunk)
                        }
                    }
                }
                launch(Dispatchers.Main) {
                    if (fullResponse.isNotBlank()) {
                        llmMessages.add(LlmMessage(role = "assistant", content = fullResponse))
                    }
                    callbacks.onResponseComplete(fullResponse)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Local inference error", e)
                launch(Dispatchers.Main) {
                    callbacks.onError("Error: ${e.message}", true)
                }
            }
        }
    }

    private fun findLocalModelInfo(filename: String): ModelDownloadManager.ModelInfo? {
        val models = modelDownloadManager.getAvailableModels(settingsManager)
        return models.firstOrNull { it.filename == filename }
    }

    private suspend fun ensureLocalModelReady(
        resolved: String,
        modelName: String,
        callbacks: Callbacks
    ): String? {
        return when (resolved) {
            MODEL_GEMINI_NANO -> {
                if (geminiNano == null || !geminiNano.isAvailable()) {
                    "Gemini Nano (AICore) no disponible"
                } else {
                    null
                }
            }
            MODEL_MEDIAPIPE -> {
                if (mediaPipeLlm == null) {
                    return "MediaPipe LLM no disponible"
                }
                if (mediaPipeLlm.isAvailable() &&
                    (modelName == MODEL_MEDIAPIPE || mediaPipeLlm.getModelFilename() == modelName)
                ) {
                    return null
                }
                withContext(Dispatchers.Main) { callbacks.onStatusUpdate("Cargando modelo...") }
                val modelInfo = findLocalModelInfo(modelName)
                    ?: return "Modelo MediaPipe no encontrado: $modelName"
                val status = mediaPipeLlm.initializeWithModel(modelInfo)
                if (status is MediaPipeLlmService.Status.Available) null
                else (status as? MediaPipeLlmService.Status.Error)?.message
                    ?: "No se pudo inicializar MediaPipe"
            }
            MODEL_TFLITE -> {
                if (localLlm == null) {
                    return "LLM TFLite no disponible"
                }
                if (localLlm.isAvailable() &&
                    (modelName == MODEL_TFLITE || localLlm.getModelFilename() == modelName)
                ) {
                    return null
                }
                withContext(Dispatchers.Main) { callbacks.onStatusUpdate("Cargando modelo...") }
                val modelInfo = findLocalModelInfo(modelName)
                    ?: return "Modelo TFLite no encontrado: $modelName"
                val status = localLlm.initializeWithModel(modelInfo, settingsManager.hfApiKey)
                if (status is LocalLlmService.Status.Available) null
                else (status as? LocalLlmService.Status.Error)?.message
                    ?: "No se pudo inicializar el modelo TFLite"
            }
            else -> "Modelo local desconocido: $modelName"
        }
    }

    private fun runLocalInferenceWithTools(modelName: String, resolved: String, callbacks: Callbacks) {
        scope.launch(Dispatchers.IO) {
            val prompt = buildLocalPrompt(includeTools = true, forceFinal = false)
            val (firstResponse, toolCalls) = streamLocalFirstResponseWithTools(
                resolved,
                modelName,
                prompt,
                callbacks
            ) ?: return@launch
            if (toolCalls.isNullOrEmpty()) {
                if (firstResponse.isNotBlank()) {
                    llmMessages.add(LlmMessage(role = "assistant", content = firstResponse))
                }
                launch(Dispatchers.Main) { callbacks.onResponseComplete(firstResponse) }
                return@launch
            }

            llmMessages.add(LlmMessage(role = "assistant", toolCalls = toolCalls))
            val results = executeToolCalls(toolCalls, callbacks) ?: run {
                launch(Dispatchers.Main) { callbacks.onError("Ejecucion cancelada", true) }
                return@launch
            }
            results.forEach { result ->
                val content = if (result.isError) "ERROR: ${result.output}" else result.output
                llmMessages.add(
                    LlmMessage(
                        role = "tool",
                        content = content,
                        toolCallId = result.callId,
                        name = result.name
                    )
                )
            }

            val finalPrompt = buildLocalPrompt(includeTools = true, forceFinal = true)
            val finalResponse = streamLocalFinalResponse(
                resolved,
                modelName,
                finalPrompt,
                callbacks
            )
                ?: return@launch
            if (finalResponse.isNotBlank()) {
                llmMessages.add(LlmMessage(role = "assistant", content = finalResponse))
            }
            launch(Dispatchers.Main) { callbacks.onResponseComplete(finalResponse) }
        }
    }

    private suspend fun streamLocalFirstResponseWithTools(
        resolved: String,
        modelName: String,
        prompt: String,
        callbacks: Callbacks
    ): Pair<String, List<ToolCall>?>? {
        val ensureError = ensureLocalModelReady(resolved, modelName, callbacks)
        if (ensureError != null) {
            withContext(Dispatchers.Main) { callbacks.onError(ensureError, true) }
            return null
        }
        withContext(Dispatchers.Main) { callbacks.onStatusUpdate("Thinking...") }
        val flow = when (resolved) {
            MODEL_GEMINI_NANO -> {
                geminiNano!!.generateContentStream(prompt)
            }
            MODEL_MEDIAPIPE -> {
                mediaPipeLlm!!.generateContentStream(prompt)
            }
            MODEL_TFLITE -> {
                localLlm!!.generateContentStream(prompt)
            }
            else -> {
                withContext(Dispatchers.Main) { callbacks.onError("Modelo local desconocido: $modelName", true) }
                return null
            }
        }

        val buffer = StringBuilder()
        var streamToUi: Boolean? = null
        var detectedToolCalls: List<ToolCall>? = null

        try {
            kotlinx.coroutines.withTimeout(LOCAL_INFERENCE_TIMEOUT_MS) {
                flow.collect { chunk ->
                    buffer.append(chunk)
                    if (streamToUi == null) {
                        val trimmed = buffer.toString().trimStart()
                        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                            if (trimmed.contains("\"tool_calls\"")) {
                                streamToUi = false
                            }
                        } else if (trimmed.isNotEmpty()) {
                            streamToUi = true
                            withContext(Dispatchers.Main) { callbacks.onResponseToken(buffer.toString()) }
                        }
                    } else if (streamToUi == true) {
                        withContext(Dispatchers.Main) { callbacks.onResponseToken(chunk) }
                    }

                    if (streamToUi == false) {
                        val calls = parseToolCalls(buffer.toString())
                        if (!calls.isNullOrEmpty()) {
                            detectedToolCalls = calls
                            currentCoroutineContext().cancel()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.TimeoutCancellationException) {
                withContext(Dispatchers.Main) { callbacks.onError("Timeout esperando respuesta local.", true) }
                return null
            }
            if (detectedToolCalls == null && e !is kotlin.coroutines.cancellation.CancellationException) {
                withContext(Dispatchers.Main) { callbacks.onError("Error: ${e.message}", true) }
                return null
            }
        }

        val responseText = buffer.toString()
        if (streamToUi == false && detectedToolCalls == null) {
            withContext(Dispatchers.Main) { callbacks.onResponseToken(responseText) }
        }
        return responseText to detectedToolCalls
    }

    private suspend fun streamLocalFinalResponse(
        resolved: String,
        modelName: String,
        prompt: String,
        callbacks: Callbacks
    ): String? {
        val ensureError = ensureLocalModelReady(resolved, modelName, callbacks)
        if (ensureError != null) {
            withContext(Dispatchers.Main) { callbacks.onError(ensureError, true) }
            return null
        }
        withContext(Dispatchers.Main) { callbacks.onStatusUpdate("Thinking...") }
        val flow = when (resolved) {
            MODEL_GEMINI_NANO -> {
                geminiNano!!.generateContentStream(prompt)
            }
            MODEL_MEDIAPIPE -> {
                mediaPipeLlm!!.generateContentStream(prompt)
            }
            MODEL_TFLITE -> {
                localLlm!!.generateContentStream(prompt)
            }
            else -> {
                withContext(Dispatchers.Main) { callbacks.onError("Modelo local desconocido: $modelName", true) }
                return null
            }
        }

        val buffer = StringBuilder()
        try {
            kotlinx.coroutines.withTimeout(LOCAL_INFERENCE_TIMEOUT_MS) {
                flow.collect { chunk ->
                    buffer.append(chunk)
                    withContext(Dispatchers.Main) { callbacks.onResponseToken(chunk) }
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.TimeoutCancellationException) {
                withContext(Dispatchers.Main) { callbacks.onError("Timeout esperando respuesta local.", true) }
            } else {
                withContext(Dispatchers.Main) { callbacks.onError("Error: ${e.message}", true) }
            }
            return null
        }
        return buffer.toString()
    }

    private suspend fun generateLocalContent(
        resolved: String,
        modelName: String,
        prompt: String,
        callbacks: Callbacks
    ): String? {
        return when (resolved) {
            MODEL_GEMINI_NANO -> {
                if (geminiNano == null || !geminiNano.isAvailable()) {
                    withContext(Dispatchers.Main) { callbacks.onError("Gemini Nano (AICore) no disponible", true) }
                    null
                } else {
                    geminiNano.generateContent(prompt).getOrElse {
                        withContext(Dispatchers.Main) { callbacks.onError("Error: ${it.message}", true) }
                        null
                    }
                }
            }
            MODEL_MEDIAPIPE -> {
                if (mediaPipeLlm == null || !mediaPipeLlm.isAvailable()) {
                    withContext(Dispatchers.Main) { callbacks.onError("MediaPipe LLM no disponible", true) }
                    null
                } else if (modelName != MODEL_MEDIAPIPE && mediaPipeLlm.getModelFilename() != modelName) {
                    withContext(Dispatchers.Main) { callbacks.onError("Modelo MediaPipe no cargado: $modelName", true) }
                    null
                } else {
                    mediaPipeLlm.generateContent(prompt).getOrElse {
                        withContext(Dispatchers.Main) { callbacks.onError("Error: ${it.message}", true) }
                        null
                    }
                }
            }
            MODEL_TFLITE -> {
                if (localLlm == null || !localLlm.isAvailable()) {
                    withContext(Dispatchers.Main) { callbacks.onError("LLM TFLite no disponible", true) }
                    null
                } else if (modelName != MODEL_TFLITE && localLlm.getModelFilename() != modelName) {
                    withContext(Dispatchers.Main) { callbacks.onError("Modelo TFLite no cargado: $modelName", true) }
                    null
                } else {
                    localLlm.generateContent(prompt).getOrElse {
                        withContext(Dispatchers.Main) { callbacks.onError("Error: ${it.message}", true) }
                        null
                    }
                }
            }
            else -> {
                withContext(Dispatchers.Main) { callbacks.onError("Modelo local desconocido: $modelName", true) }
                null
            }
        }
    }

    private fun buildLocalPrompt(includeTools: Boolean, forceFinal: Boolean): String {
        val builder = StringBuilder()
        val tools = if (includeTools) toolRegistry.getTools() else emptyList()
        if (tools.isNotEmpty()) {
            builder.append("Herramientas disponibles (JSON schema):\n")
            tools.forEach { tool ->
                builder.append("- ").append(tool.name).append(": ").append(tool.description).append("\n")
                builder.append(tool.parameters.toString()).append("\n")
            }
            if (forceFinal) {
                builder.append("Responde solo con el texto final, sin JSON.\n")
            } else {
                builder.append(
                    "Si necesitas usar una herramienta, responde SOLO con JSON en este formato:\n" +
                        "{\"tool_calls\":[{\"name\":\"tool_name\",\"arguments\":{}}]}\n" +
                        "Si no necesitas herramientas, responde con texto normal.\n"
                )
            }
        }

        llmMessages.forEach { msg ->
            when (msg.role) {
                "system" -> builder.append("System: ").append(msg.content.orEmpty()).append("\n")
                "user" -> builder.append("User: ").append(msg.content.orEmpty()).append("\n")
                "assistant" -> {
                    if (!msg.content.isNullOrBlank()) {
                        builder.append("Assistant: ").append(msg.content).append("\n")
                    }
                }
                "tool" -> {
                    val name = msg.name ?: "tool"
                    builder.append("Tool(").append(name).append("): ")
                        .append(msg.content.orEmpty()).append("\n")
                }
            }
        }
        builder.append("Assistant:")
        return builder.toString()
    }

    private fun parseToolCalls(text: String): List<ToolCall>? {
        val raw = text.trim()
        val jsonText = extractJsonObject(raw) ?: return null
        return try {
            val obj = org.json.JSONObject(jsonText)
            val calls = obj.optJSONArray("tool_calls") ?: return null
            val results = mutableListOf<ToolCall>()
            for (i in 0 until calls.length()) {
                val callObj = calls.optJSONObject(i) ?: continue
                val name = callObj.optString("name")
                val args = callObj.opt("arguments")
                val argText = when (args) {
                    is org.json.JSONObject -> args.toString()
                    is String -> args
                    else -> ""
                }
                if (name.isNotBlank()) {
                    results.add(ToolCall(id = "local_call_$i", name = name, arguments = argText))
                }
            }
            results
        } catch (e: Exception) {
            null
        }
    }

    private fun extractJsonObject(text: String): String? {
        if (text.startsWith("{") && text.endsWith("}")) return text
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1)
        }
        return null
    }

    private suspend fun executeToolCalls(
        toolCalls: List<ToolCall>,
        callbacks: Callbacks
    ): List<ToolResult>? {
        if (cancelRequested) return null
        val runId = toolRunId + 1
        toolRunId = runId
        scope.launch(Dispatchers.Main) {
            callbacks.onToolCalls(toolCalls)
            callbacks.onToolExecutionStart()
        }
        val results = mutableListOf<ToolResult>()
        for (call in toolCalls) {
            if (cancelRequested || runId != toolRunId) return null
            if (canceledToolCalls.contains(call.id)) {
                results.add(ToolResult(call.id, call.name, "Tool cancelada por el usuario.", true))
                continue
            }
            val timeoutMs = settingsManager.toolTimeoutMs
            val future = toolExecutorPool.submit<ToolResult> {
                if (canceledToolCalls.contains(call.id)) {
                    return@submit ToolResult(call.id, call.name, "Tool cancelada por el usuario.", true)
                }
                val gateResult = callbacks.handleToolGate(call)
                gateResult ?: toolExecutor.execute(call)
            }
            toolFutures[call.id] = future
            val executed = try {
                future.get(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                future.cancel(true)
                ToolResult(call.id, call.name, "Timeout ejecutando tool.", true)
            } catch (e: CancellationException) {
                ToolResult(call.id, call.name, "Tool cancelada por el usuario.", true)
            } catch (e: Exception) {
                future.cancel(true)
                ToolResult(call.id, call.name, "Error ejecutando tool: ${e.message}", true)
            } finally {
                toolFutures.remove(call.id)
            }
            if (canceledToolCalls.contains(call.id)) {
                results.add(ToolResult(call.id, call.name, "Tool cancelada por el usuario.", true))
            } else {
                results.add(executed)
            }
        }
        return results
    }

    /**
     * Returns list of available local models for the "local" endpoint.
     */
    fun getAvailableLocalModels(): List<String> {
        val models = mutableListOf<String>()
        if (geminiNano != null && geminiNano.isAvailable()) {
            models.add(MODEL_GEMINI_NANO)
        }
        if (mediaPipeLlm != null && mediaPipeLlm.isAvailable()) {
            models.add(MODEL_MEDIAPIPE)
        }
        if (localLlm != null && localLlm.isAvailable()) {
            models.add(MODEL_TFLITE)
        }
        return models
    }

    companion object {
        private const val TAG = "ChatController"

        const val ENDPOINT_LOCAL = "local"
        const val MODEL_GEMINI_NANO = "gemini-nano"
        const val MODEL_MEDIAPIPE = "mediapipe"
        const val MODEL_TFLITE = "tflite"
        private const val LOCAL_INFERENCE_TIMEOUT_MS = 120_000L
    }
}
