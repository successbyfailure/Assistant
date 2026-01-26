package com.sbf.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.service.voice.VoiceInteractionSession
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import java.util.Locale
import com.sbf.assistant.llm.LocalLlmService
import com.sbf.assistant.llm.MediaPipeLlmService

class AssistantSession(context: Context) : VoiceInteractionSession(context), LifecycleOwner {
    
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    private lateinit var statusText: TextView
    private lateinit var inputField: EditText
    private lateinit var sendButton: FloatingActionButton
    private lateinit var micButton: ImageButton
    private lateinit var btnClose: MaterialButton
    private lateinit var chatRecycler: RecyclerView
    private lateinit var toolProgressContainer: View
    private lateinit var toolProgressText: TextView

    private lateinit var settingsManager: SettingsManager
    private lateinit var ttsController: TtsController
    private lateinit var toolRegistry: ToolRegistry
    private lateinit var toolExecutor: ToolExecutor
    private lateinit var mcpClient: McpClient
    private lateinit var localRuntime: LocalModelRuntime
    private var geminiNano: GeminiNanoService? = null
    private var localLlm: LocalLlmService? = null
    private var mediaPipeLlm: MediaPipeLlmService? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private lateinit var historyStore: ChatHistoryStore

    private val messages = mutableListOf<ChatMessage>()
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var chatController: ChatController
    private var isRequestInFlight = false
    private var currentAssistantMessageIndex = -1
    private var currentTokenCount = 0
    private var currentToolCount = 0
    private var lastRequestTokenCount = 0
    private var lastRequestElapsedMs = 0L
    private var lastRequestContextTokens = 0
    private var statusTickerJob: Job? = null
    
    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        
        settingsManager = SettingsManager(context)
        ttsController = TtsController(context, settingsManager)
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        mcpClient = McpServerFactory.createClient(context, settingsManager)
        toolRegistry = ToolRegistry(settingsManager, mcpClient)
        toolExecutor = ToolExecutor(context, mcpClient)
        localRuntime = LocalModelRuntime(context, settingsManager)
        // Initialize local LLM services (async)
        geminiNano = GeminiNanoService(context)
        localLlm = LocalLlmService(context)
        mediaPipeLlm = MediaPipeLlmService(context)

        scope.launch {
            // Try GeminiNano first (AICore)
            val geminiStatus = geminiNano?.initialize()
            if (geminiStatus == GeminiNanoService.Status.Available) {
                geminiNano?.warmup()
            }

            // Check for local models
            val downloadManager = ModelDownloadManager(context)
            val installedModels = downloadManager.getInstalledModels(
                downloadManager.getAvailableModels(settingsManager)
            )
            val selected = settingsManager.localAgentModel
            val selectedModel = installedModels.firstOrNull { it.filename == selected }
            val effectiveSelected = selectedModel?.takeIf { it.category != "LLM-Multimodal" }

            fun registerLoaded(model: ModelDownloadManager.ModelInfo) {
                val file = downloadManager.getModelFile(model.filename) ?: return
                localRuntime.registerLoaded(model.filename, file.length())
            }

            if (effectiveSelected != null) {
                when (effectiveSelected.type) {
                    "task" -> {
                        var status = mediaPipeLlm?.initializeWithModel(effectiveSelected)
                        if (status is MediaPipeLlmService.Status.Error && status.isOutOfMemory) {
                            val evicted = localRuntime.evictLeastRecentlyUsed()
                            if (evicted == mediaPipeLlm?.getModelFilename()) {
                                mediaPipeLlm?.release()
                            } else if (evicted == localLlm?.getModelFilename()) {
                                localLlm?.release()
                            }
                            status = mediaPipeLlm?.initializeWithModel(effectiveSelected)
                        }
                        if (status is MediaPipeLlmService.Status.Available) {
                            registerLoaded(effectiveSelected)
                        }
                    }
                    "tflite" -> {
                        var status = localLlm?.initializeWithModel(effectiveSelected, settingsManager.hfApiKey)
                        if (status is com.sbf.assistant.llm.LocalLlmService.Status.Error && status.isOutOfMemory) {
                            val evicted = localRuntime.evictLeastRecentlyUsed()
                            if (evicted == localLlm?.getModelFilename()) {
                                localLlm?.release()
                            } else if (evicted == mediaPipeLlm?.getModelFilename()) {
                                mediaPipeLlm?.release()
                            }
                            status = localLlm?.initializeWithModel(effectiveSelected, settingsManager.hfApiKey)
                        }
                        if (status is com.sbf.assistant.llm.LocalLlmService.Status.Available) {
                            registerLoaded(effectiveSelected)
                        }
                    }
                    else -> {}
                }
            } else {
                // Fallback: pick first available by type
                val taskModels = installedModels.filter { MediaPipeLlmService.isTaskModel(it.filename) }
                if (taskModels.isNotEmpty()) {
                    var status = mediaPipeLlm?.initializeWithModel(taskModels.first())
                    if (status is MediaPipeLlmService.Status.Error && status.isOutOfMemory) {
                        val evicted = localRuntime.evictLeastRecentlyUsed()
                        if (evicted == mediaPipeLlm?.getModelFilename()) {
                            mediaPipeLlm?.release()
                        } else if (evicted == localLlm?.getModelFilename()) {
                            localLlm?.release()
                        }
                        status = mediaPipeLlm?.initializeWithModel(taskModels.first())
                    }
                    if (status is MediaPipeLlmService.Status.Available) {
                        registerLoaded(taskModels.first())
                    }
                }

                val tfliteModels = installedModels.filter {
                    it.filename.endsWith(".tflite", ignoreCase = true) && it.category == "LLM-Text"
                }
                if (tfliteModels.isNotEmpty()) {
                    var status = localLlm?.initializeWithModel(tfliteModels.first(), settingsManager.hfApiKey)
                    if (status is com.sbf.assistant.llm.LocalLlmService.Status.Error && status.isOutOfMemory) {
                        val evicted = localRuntime.evictLeastRecentlyUsed()
                        if (evicted == localLlm?.getModelFilename()) {
                            localLlm?.release()
                        } else if (evicted == mediaPipeLlm?.getModelFilename()) {
                            mediaPipeLlm?.release()
                        }
                        status = localLlm?.initializeWithModel(tfliteModels.first(), settingsManager.hfApiKey)
                    }
                    if (status is com.sbf.assistant.llm.LocalLlmService.Status.Available) {
                        registerLoaded(tfliteModels.first())
                    }
                }
            }
        }

        chatController = ChatController(
            settingsManager,
            toolExecutor,
            toolRegistry,
            geminiNano,
            localLlm,
            mediaPipeLlm,
            ModelDownloadManager(context),
            scope
        )
        historyStore = ChatHistoryStore(context)
    }

    override fun onCreateContentView(): View {
        val view = layoutInflater.inflate(R.layout.assistant_overlay, null)
        statusText = view.findViewById(R.id.status_text)
        inputField = view.findViewById(R.id.input_field)
        sendButton = view.findViewById(R.id.send_button)
        micButton = view.findViewById(R.id.mic_button)
        btnClose = view.findViewById(R.id.btn_close)
        chatRecycler = view.findViewById(R.id.chat_recycler)
        toolProgressContainer = view.findViewById(R.id.tool_progress_container)
        toolProgressText = view.findViewById(R.id.tool_progress_text)

        chatAdapter = ChatAdapter(
            messages,
            context,
            onCancelClick = { index -> cancelToolCallsFromUi(index) },
            onStatsClick = { index -> showStatsForMessage(index) },
            onThoughtToggle = { index -> toggleThought(index) }
        )
        chatRecycler.adapter = chatAdapter
        chatRecycler.itemAnimator = null
        chatRecycler.layoutManager = LinearLayoutManager(context).apply {
            stackFromEnd = true
        }

        val storedMessages = historyStore.load()
        if (storedMessages.isNotEmpty()) {
            messages.addAll(storedMessages)
            chatAdapter.notifyDataSetChanged()
        }

        sendButton.setOnClickListener {
            if (isRequestInFlight) {
                cancelRequestFromUi()
                return@setOnClickListener
            }
            val text = inputField.text.toString()
            if (text.isNotBlank()) {
                processUserQuery(text)
                inputField.text.clear()
            }
        }

        micButton.setOnClickListener {
            startListening()
        }

        btnClose.setOnClickListener {
            finish()
        }
        
        return view
    }

    private fun startListening() {
        ttsController.stop()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }
        
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                statusText.text = "Listening..."
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                statusText.text = "Processing..."
            }

            override fun onError(error: Int) {
                statusText.text = "Speech error: $error"
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val query = matches[0]
                    val processed = applyVoiceShortcut(query)
                    if (processed != null) {
                        inputField.setText(processed)
                        processUserQuery(processed)
                    } else {
                        statusText.text = "Assistant Ready"
                    }
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        
        speechRecognizer?.startListening(intent)
    }

    override fun onHandleAssist(assistState: AssistState) {
        super.onHandleAssist(assistState)
        // This is called when the assistant is triggered (e.g. long press home)
        // We can access screen context here if needed.
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onHide() {
        super.onHide()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        speechRecognizer?.stopListening()
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        scope.cancel()
        ttsController.release()
        speechRecognizer?.destroy()
    }

    private fun processUserQuery(query: String) {
        messages.add(ChatMessage(query, true))
        chatAdapter.notifyItemInserted(messages.size - 1)
        chatRecycler.scrollToPosition(messages.size - 1)
        historyStore.append(ChatMessage(query, true))

        currentAssistantMessageIndex = -1
        currentTokenCount = 0
        currentToolCount = 0
        var fullResponse = ""
        var toolInProgress = false
        var autoScrollEnabled = true
        var awaitingFirstResponse = true
        val thinkFilter = ThinkFilter()
        var thoughtBuffer = ""
        isRequestInFlight = true
        updateSendButtonState(true)
        val requestStartMs = android.os.SystemClock.elapsedRealtime()
        var statusPrefix = "Assistant Ready"
        fun updateStatusLabel(prefix: String) {
            statusPrefix = prefix
            val elapsed = lastRequestElapsedMs
            val seconds = elapsed / 1000.0
            statusText.text = String.format(
                Locale.US,
                "%s (%.1fs, %d tok, %d ctx, %d tools)",
                prefix,
                seconds,
                lastRequestTokenCount,
                lastRequestContextTokens,
                currentToolCount
            )
        }
        fun updateProgressLabel(prefix: String) {
            toolProgressText.text = "$prefix (${currentTokenCount} tokens)"
        }
        lastRequestTokenCount = 0
        lastRequestElapsedMs = 0L
        lastRequestContextTokens = 0
        updateStatusLabel("Thinking...")
        updateProgressLabel("Pensando...")
        setToolProgressVisible(true)
        statusTickerJob?.cancel()
        statusTickerJob = scope.launch(Dispatchers.Main) {
            while (isRequestInFlight) {
                lastRequestElapsedMs = android.os.SystemClock.elapsedRealtime() - requestStartMs
                lastRequestContextTokens = estimateContextTokens()
                updateStatusLabel(statusPrefix)
                delay(200)
            }
        }

        chatController.processQuery(query, object : ChatController.Callbacks {
            override fun onStatusUpdate(status: String) {
                updateStatusLabel(status)
            }

            override fun onResponseToken(token: String) {
                val chunk = thinkFilter.consume(token)
                if (chunk.thoughtDelta.isEmpty() && chunk.answerDelta.isEmpty()) {
                    return
                }
                if (chunk.thoughtDelta.isNotEmpty()) {
                    thoughtBuffer += chunk.thoughtDelta
                }
                if (awaitingFirstResponse) {
                    awaitingFirstResponse = false
                    if (!toolInProgress) {
                        setToolProgressVisible(false)
                    }
                }
                if (toolInProgress) {
                    toolInProgress = false
                    setToolProgressVisible(false)
                }
                if (chunk.answerDelta.isNotEmpty()) {
                    currentTokenCount += estimateTokenCount(chunk.answerDelta)
                }
                lastRequestTokenCount = currentTokenCount
                lastRequestElapsedMs = android.os.SystemClock.elapsedRealtime() - requestStartMs
                lastRequestContextTokens = estimateContextTokens()
                updateStatusLabel("Thinking...")
                if (toolProgressContainer.visibility == View.VISIBLE && !toolInProgress) {
                    updateProgressLabel("Pensando...")
                }
                if (fullResponse.isEmpty() && currentAssistantMessageIndex == -1) {
                    fullResponse = chunk.answerDelta
                    val message = ChatMessage(
                        text = fullResponse,
                        isUser = false,
                        thought = thoughtBuffer,
                        isThinking = true,
                        thoughtCollapsed = false,
                        stats = ChatStats(currentToolCount, currentTokenCount)
                    )
                    messages.add(message)
                    currentAssistantMessageIndex = messages.size - 1
                    chatAdapter.notifyItemInserted(currentAssistantMessageIndex)
                } else {
                    fullResponse += chunk.answerDelta
                    val updated = ChatMessage(
                        text = fullResponse,
                        isUser = false,
                        thought = thoughtBuffer,
                        isThinking = true,
                        thoughtCollapsed = false,
                        toolNames = emptyList(),
                        toolCallIds = emptyList(),
                        showCancel = false,
                        stats = ChatStats(currentToolCount, currentTokenCount)
                    )
                    messages[currentAssistantMessageIndex] = updated
                    chatAdapter.notifyItemChanged(currentAssistantMessageIndex)
                }
                if (autoScrollEnabled) {
                    chatRecycler.scrollToPosition(messages.size - 1)
                    val targetIndex = currentAssistantMessageIndex
                    chatRecycler.post {
                        if (!autoScrollEnabled) return@post
                        val layoutManager = chatRecycler.layoutManager as? LinearLayoutManager ?: return@post
                        val firstVisible = layoutManager.findFirstVisibleItemPosition()
                        if (firstVisible != targetIndex) return@post
                        val view = layoutManager.findViewByPosition(targetIndex) ?: return@post
                        if (view.top <= chatRecycler.paddingTop) {
                            autoScrollEnabled = false
                        }
                    }
                }
            }

            override fun onResponseComplete(fullResponse: String) {
                val cleanedResponse = thinkFilter.stripAll(fullResponse).trim()
                val finalThought = thoughtBuffer.trim()
                lastRequestElapsedMs = android.os.SystemClock.elapsedRealtime() - requestStartMs
                lastRequestContextTokens = estimateContextTokens()
                updateStatusLabel("Respuesta lista")
                isRequestInFlight = false
                statusTickerJob?.cancel()
                updateSendButtonState(false)
                awaitingFirstResponse = false
                if (toolInProgress) {
                    toolInProgress = false
                    setToolProgressVisible(false)
                }
                setToolProgressVisible(false)
                if (cleanedResponse.isNotBlank() || finalThought.isNotBlank()) {
                    if (currentAssistantMessageIndex == -1) {
                        val message = ChatMessage(
                            text = cleanedResponse,
                            isUser = false,
                            thought = finalThought,
                            isThinking = false,
                            thoughtCollapsed = true,
                            stats = ChatStats(currentToolCount, currentTokenCount)
                        )
                        messages.add(message)
                        currentAssistantMessageIndex = messages.size - 1
                        chatAdapter.notifyItemInserted(currentAssistantMessageIndex)
                        chatRecycler.scrollToPosition(messages.size - 1)
                    } else {
                        val updated = ChatMessage(
                            text = cleanedResponse,
                            isUser = false,
                            thought = finalThought,
                            isThinking = false,
                            thoughtCollapsed = true,
                            toolNames = emptyList(),
                            toolCallIds = emptyList(),
                            showCancel = false,
                            stats = ChatStats(currentToolCount, currentTokenCount)
                        )
                        messages[currentAssistantMessageIndex] = updated
                        chatAdapter.notifyItemChanged(currentAssistantMessageIndex)
                        chatRecycler.scrollToPosition(messages.size - 1)
                    }
                }
                if (cleanedResponse.isNotBlank()) {
                    historyStore.append(ChatMessage(cleanedResponse, false))
                }
                if (currentTokenCount == 0 && cleanedResponse.isNotBlank()) {
                    currentTokenCount = estimateTokenCount(cleanedResponse)
                    lastRequestTokenCount = currentTokenCount
                    updateStatusLabel("Respuesta lista")
                }
                val trimmed = cleanedResponse.trim()
                if (trimmed.isNotBlank() &&
                    !trimmed.equals("Thinking...", ignoreCase = true) &&
                    !trimmed.equals("Pensando...", ignoreCase = true) &&
                    !trimmed.startsWith("Ejecutando", ignoreCase = true)
                ) {
                    ttsController.speak(cleanedResponse)
                }
                chatRecycler.postDelayed({
                    if (!isRequestInFlight) {
                        updateStatusLabel("Assistant Ready")
                    }
                }, 1200)
            }

            override fun onToolExecutionStart() {
                lastRequestElapsedMs = android.os.SystemClock.elapsedRealtime() - requestStartMs
                lastRequestContextTokens = estimateContextTokens()
                updateStatusLabel("Ejecutando herramientas...")
                awaitingFirstResponse = false
                toolInProgress = true
                updateProgressLabel("Ejecutando tools...")
                setToolProgressVisible(true)
            }

            override fun onToolCalls(toolCalls: List<ToolCall>) {
                currentToolCount = toolCalls.size
                val toolNames = toolCalls.map { it.name }
                val toolCallIds = toolCalls.map { it.id }
                if (currentAssistantMessageIndex == -1) {
                    val message = ChatMessage(
                        text = "Ejecutando tools...",
                        isUser = false,
                        toolNames = toolNames,
                        toolCallIds = toolCallIds,
                        showCancel = true,
                        stats = ChatStats(currentToolCount, currentTokenCount)
                    )
                    messages.add(message)
                    currentAssistantMessageIndex = messages.size - 1
                    chatAdapter.notifyItemInserted(currentAssistantMessageIndex)
                } else {
                    val updated = messages[currentAssistantMessageIndex].copy(
                        toolNames = toolNames,
                        toolCallIds = toolCallIds,
                        showCancel = true,
                        stats = ChatStats(currentToolCount, currentTokenCount)
                    )
                    messages[currentAssistantMessageIndex] = updated
                    chatAdapter.notifyItemChanged(currentAssistantMessageIndex)
                }
            }

            override fun onError(error: String, wasPrimary: Boolean) {
                lastRequestElapsedMs = android.os.SystemClock.elapsedRealtime() - requestStartMs
                lastRequestContextTokens = estimateContextTokens()
                updateStatusLabel("Error: $error")
                isRequestInFlight = false
                statusTickerJob?.cancel()
                updateSendButtonState(false)
                awaitingFirstResponse = false
                if (toolInProgress) {
                    toolInProgress = false
                    setToolProgressVisible(false)
                }
                setToolProgressVisible(false)
                addErrorMessage(error)
            }

            override fun handleToolGate(call: ToolCall): ToolResult? {
                // AssistantSession doesn't have tool gating (no UI for permission dialogs)
                return null
            }
        })
    }

    private fun addErrorMessage(error: String) {
        messages.add(ChatMessage(error, false))
        chatAdapter.notifyItemInserted(messages.size - 1)
        chatRecycler.scrollToPosition(messages.size - 1)
        historyStore.append(ChatMessage(error, false))
    }

    private fun setToolProgressVisible(visible: Boolean) {
        toolProgressContainer.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun applyVoiceShortcut(text: String): String? {
        if (!settingsManager.voiceShortcutEnabled) return text.trim()
        val phrase = settingsManager.voiceShortcutPhrase.trim()
        if (phrase.isBlank()) return text.trim()
        val normalized = text.trim()
        return if (normalized.startsWith(phrase, ignoreCase = true)) {
            normalized.substring(phrase.length).trim().ifBlank { null }
        } else {
            statusText.text = "Di \"$phrase\" para activar"
            null
        }
    }

    private fun cancelRequestFromUi() {
        chatController.cancelCurrentRequest()
        isRequestInFlight = false
        statusTickerJob?.cancel()
        statusText.text = "Assistant Ready"
        setToolProgressVisible(false)
        updateSendButtonState(false)
        if (currentAssistantMessageIndex >= 0) {
            val updated = messages[currentAssistantMessageIndex].copy(
                text = "Solicitud cancelada.",
                showCancel = false,
                toolNames = emptyList(),
                toolCallIds = emptyList(),
                stats = ChatStats(currentToolCount, currentTokenCount)
            )
            messages[currentAssistantMessageIndex] = updated
            chatAdapter.notifyItemChanged(currentAssistantMessageIndex)
        }
    }

    private fun cancelToolCallsFromUi(index: Int) {
        val message = messages.getOrNull(index) ?: return
        if (message.toolCallIds.isNotEmpty()) {
            chatController.cancelToolCalls(message.toolCallIds)
        }
        val updated = message.copy(
            text = "Tools canceladas por el usuario.",
            showCancel = false,
            toolNames = emptyList(),
            toolCallIds = emptyList()
        )
        messages[index] = updated
        chatAdapter.notifyItemChanged(index)
    }

    private fun showStatsForMessage(index: Int) {
        val message = messages.getOrNull(index) ?: return
        val stats = message.stats ?: return
        val text = "Tools ejecutadas: ${stats.toolCount}\nTokens totales: ${stats.tokenCount}"
        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle("Estadisticas")
            .setMessage(text)
            .setPositiveButton("Cerrar", null)
            .show()
    }

    private fun toggleThought(index: Int) {
        val message = messages.getOrNull(index) ?: return
        if (message.thought.isBlank()) return
        val updated = message.copy(thoughtCollapsed = !message.thoughtCollapsed)
        messages[index] = updated
        chatAdapter.notifyItemChanged(index)
    }

    private fun estimateContextTokens(): Int {
        val totalChars = messages.sumOf { it.text.length }
        return (totalChars / 4).coerceAtLeast(0)
    }

    private fun estimateTokenCount(text: String): Int {
        if (text.isBlank()) return 0
        return (text.length / 4).coerceAtLeast(1)
    }

    private fun updateSendButtonState(inFlight: Boolean) {
        if (inFlight) {
            sendButton.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
        } else {
            sendButton.setImageResource(android.R.drawable.ic_menu_send)
        }
    }

    companion object {
        private const val TAG = "AssistantSession"
    }
}
