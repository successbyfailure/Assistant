package com.sbf.assistant

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import java.util.Locale
import org.json.JSONObject
import com.sbf.assistant.llm.LocalLlmService
import com.sbf.assistant.llm.MediaPipeLlmService

class ChatActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var toolbar: MaterialToolbar
    private lateinit var statusText: TextView
    private lateinit var inputField: EditText
    private lateinit var sendButton: FloatingActionButton
    private lateinit var micButton: ImageButton
    private lateinit var chatRecycler: RecyclerView
    private lateinit var toolProgressContainer: android.view.View
    private lateinit var toolProgressText: TextView

    private lateinit var settingsManager: SettingsManager
    private lateinit var ttsController: TtsController
    private lateinit var permissionController: PermissionController
    private lateinit var whisperController: WhisperController
    private lateinit var toolRegistry: ToolRegistry
    private lateinit var toolExecutor: ToolExecutor
    private lateinit var mcpClient: McpClient
    private var geminiNano: GeminiNanoService? = null
    private var localLlm: LocalLlmService? = null
    private var mediaPipeLlm: MediaPipeLlmService? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private lateinit var historyStore: ChatHistoryStore

    private val messages = mutableListOf<ChatMessage>()
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var chatController: ChatController
    private var isRequestInFlight = false
    private var sttMode: SttMode = SttMode.NONE
    private var activeSttConfig: ModelConfig? = null
    private var currentAssistantMessageIndex = -1
    private var currentTokenCount = 0
    private var currentToolCount = 0
    private var lastRequestTokenCount = 0
    private var lastRequestElapsedMs = 0L
    private var lastRequestContextTokens = 0
    private var statusTickerJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.apply(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_chat)

        toolbar = findViewById(R.id.toolbar)
        statusText = findViewById(R.id.status_text)
        inputField = findViewById(R.id.input_field)
        sendButton = findViewById(R.id.send_button)
        micButton = findViewById(R.id.mic_button)
        chatRecycler = findViewById(R.id.chat_recycler)
        toolProgressContainer = findViewById(R.id.tool_progress_container)
        toolProgressText = findViewById(R.id.tool_progress_text)

        setSupportActionBar(toolbar)
        setupWindowInsets()
        setupMicButtonBehavior()

        settingsManager = SettingsManager(this)
        ttsController = TtsController(this.applicationContext, settingsManager)
        permissionController = PermissionController(this)
        val localRuntime = LocalModelRuntime(this, settingsManager)
        val downloadManager = ModelDownloadManager(this)
        whisperController = WhisperController(
            settingsManager,
            AudioRecorder(this),
            LocalWhisperService(this, settingsManager, localRuntime, downloadManager),
            scope
        )
        historyStore = ChatHistoryStore(this)
        mcpClient = McpServerFactory.createClient(this.applicationContext, settingsManager)
        toolRegistry = ToolRegistry(settingsManager, mcpClient)
        toolExecutor = ToolExecutor(this.applicationContext, mcpClient)

        // Initialize local LLM services (async, may not be available)
        geminiNano = GeminiNanoService(this.applicationContext)
        localLlm = LocalLlmService(this.applicationContext)
        mediaPipeLlm = MediaPipeLlmService(this.applicationContext)

        scope.launch {
            // Try to initialize GeminiNano first (AICore)
            val geminiStatus = geminiNano?.initialize()
            if (geminiStatus == GeminiNanoService.Status.Available) {
                geminiNano?.warmup()
            }

            // Check for local models
            val downloadManager = ModelDownloadManager(this@ChatActivity)
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
                val taskModels = installedModels.filter {
                    MediaPipeLlmService.isTaskModel(it.filename)
                }
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
            ModelDownloadManager(this),
            scope
        )

        chatAdapter = ChatAdapter(
            messages,
            this,
            onCancelClick = { index -> cancelToolCallsFromUi(index) },
            onStatsClick = { index -> showStatsForMessage(index) },
            onThoughtToggle = { index -> toggleThought(index) }
        )
        chatRecycler.adapter = chatAdapter
        chatRecycler.itemAnimator = null
        chatRecycler.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }

        val storedMessages = historyStore.load()
        if (storedMessages.isNotEmpty()) {
            messages.addAll(storedMessages)
        } else {
            messages.add(ChatMessage("Welcome! How can I assist you today?", false))
        }
        chatAdapter.notifyDataSetChanged()

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

        micButton.setOnClickListener { handleMicClick() }

    }

    private fun handleMicClick() {
        stopSpeechOutput()
        if (!permissionController.hasPermission(Manifest.permission.RECORD_AUDIO)) {
            permissionController.requestPermission(Manifest.permission.RECORD_AUDIO) { granted ->
                if (granted) {
                    handleMicClick()
                }
            }
            return
        }

        val sttConfig = settingsManager.getCategoryConfig(Category.STT).primary
        if (sttConfig?.endpointId == "system" || sttConfig == null) {
            startSystemListening()
        } else {
            toggleWhisperRecording(sttConfig)
        }
    }

    private fun setupMicButtonBehavior() {
        updateMicButtonSize()
        inputField.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateMicButtonSize()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        micButton.setOnLongClickListener {
            val sttConfig = settingsManager.getCategoryConfig(Category.STT).primary
            if (sttConfig?.endpointId == "system" || sttConfig == null) return@setOnLongClickListener false
            if (!permissionController.hasPermission(Manifest.permission.RECORD_AUDIO)) {
                permissionController.requestPermission(Manifest.permission.RECORD_AUDIO) { }
                return@setOnLongClickListener true
            }
            if (sttMode == SttMode.NONE) {
                startWhisperPtt(sttConfig)
            }
            true
        }

        micButton.setOnTouchListener { _, event ->
            if (sttMode == SttMode.PTT) {
                if (event.action == android.view.MotionEvent.ACTION_UP || event.action == android.view.MotionEvent.ACTION_CANCEL) {
                    val config = activeSttConfig
                    if (config != null) {
                        stopWhisperAndTranscribe(config)
                    } else {
                        cancelWhisper()
                    }
                    return@setOnTouchListener true
                }
            }
            false
        }
    }

    private fun updateMicButtonSize() {
        val empty = inputField.text.isNullOrBlank()
        val sizeDp = if (empty) 64 else 48
        val sizePx = (sizeDp * resources.displayMetrics.density).toInt()
        val params = micButton.layoutParams
        params.width = sizePx
        params.height = sizePx
        micButton.layoutParams = params
    }

    private enum class SttMode {
        NONE,
        AUTO,
        PTT
    }

    private fun toggleWhisperRecording(config: ModelConfig) {
        if (!whisperController.isRecording()) {
            stopSpeechOutput()
            startWhisperAuto(config)
            return
        }

        if (sttMode == SttMode.AUTO) {
            cancelWhisper()
        } else {
            stopWhisperAndTranscribe(config)
        }
    }

    private fun startWhisperAuto(config: ModelConfig) {
        val onSilenceDetected = {
            if (whisperController.isRecording() && sttMode == SttMode.AUTO) {
                stopWhisperAndTranscribe(config)
            }
        }
        statusText.text = "Cargando Whisper..."
        scope.launch {
            if (config.endpointId == "local") {
                val ready = whisperController.prepareLocalModel()
                if (!ready) {
                    statusText.text = "Ready"
                    Toast.makeText(this@ChatActivity, "No se pudo cargar el modelo Whisper", Toast.LENGTH_SHORT).show()
                    return@launch
                }
            }
            val file = whisperController.startRecording(
                usePcm = true,
                autoStopOnSilence = true,
                onSilenceDetected = onSilenceDetected,
                onReadyToSpeak = { cueReadyToSpeak() }
            )
            if (file == null) {
                statusText.text = "Ready"
                Toast.makeText(this@ChatActivity, "No se pudo iniciar la grabacion", Toast.LENGTH_SHORT).show()
            } else {
                sttMode = SttMode.AUTO
                activeSttConfig = config
                statusText.text = "Escuchando (Whisper)..."
                micButton.setColorFilter(ContextCompat.getColor(this@ChatActivity, android.R.color.holo_red_light))
            }
        }
    }

    private fun startWhisperPtt(config: ModelConfig) {
        statusText.text = "Cargando Whisper..."
        scope.launch {
            if (config.endpointId == "local") {
                val ready = whisperController.prepareLocalModel()
                if (!ready) {
                    statusText.text = "Ready"
                    Toast.makeText(this@ChatActivity, "No se pudo cargar el modelo Whisper", Toast.LENGTH_SHORT).show()
                    return@launch
                }
            }
            val file = whisperController.startRecording(
                usePcm = true,
                autoStopOnSilence = false
            )
            if (file == null) {
                statusText.text = "Ready"
                Toast.makeText(this@ChatActivity, "No se pudo iniciar la grabacion", Toast.LENGTH_SHORT).show()
            } else {
                sttMode = SttMode.PTT
                activeSttConfig = config
                statusText.text = "Escuchando (PTT)..."
                micButton.setColorFilter(ContextCompat.getColor(this@ChatActivity, android.R.color.holo_red_light))
            }
        }
    }

    private fun cancelWhisper() {
        whisperController.cancelRecording()
        sttMode = SttMode.NONE
        activeSttConfig = null
        micButton.setColorFilter(null)
        statusText.text = "Ready"
    }

    private fun stopWhisperAndTranscribe(config: ModelConfig) {
        statusText.text = "Transcribing..."
        micButton.setColorFilter(null)
        sttMode = SttMode.NONE
        activeSttConfig = null
        whisperController.stopAndTranscribe(config) { text, error ->
            if (text != null) {
                val processed = applyVoiceShortcut(text)
                if (processed != null) {
                    inputField.setText(processed)
                    processUserQuery(processed)
                    inputField.text.clear()
                } else {
                    statusText.text = "Ready"
                }
            } else {
                statusText.text = "Ready"
                val message = error ?: "Whisper Error"
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun cueReadyToSpeak() {
        try {
            val vibrator = getSystemService(android.os.Vibrator::class.java)
            if (vibrator != null && vibrator.hasVibrator()) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    vibrator.vibrate(android.os.VibrationEffect.createOneShot(30, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(30)
                }
            }
        } catch (_: Exception) {
        }
        try {
            val tone = android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 70)
            tone.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 80)
        } catch (_: Exception) {
        }
    }

    private fun startSystemListening() {
        stopSpeechOutput()

        // Lazy initialization to avoid memory leaks
        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { statusText.text = "Listening..." }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { statusText.text = "Processing..." }
            override fun onError(error: Int) { statusText.text = "Ready" }
            override fun onResults(results: Bundle?) {
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { text ->
                    val processed = applyVoiceShortcut(text)
                    if (processed != null) {
                        inputField.setText(processed)
                        processUserQuery(processed)
                        inputField.text.clear()
                    } else {
                        statusText.text = "Ready"
                    }
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        speechRecognizer?.startListening(intent)
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
        val requestStartMs = android.os.SystemClock.elapsedRealtime()
        var statusPrefix = "Ready"
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
                if (toolProgressContainer.visibility == android.view.View.VISIBLE && !toolInProgress) {
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
                handleSpeechOutput(cleanedResponse)
                chatRecycler.postDelayed({
                    if (!isRequestInFlight) {
                        updateStatusLabel("Ready")
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
                return runBlocking {
                    this@ChatActivity.handleToolGate(call)
                }
            }
        })
        updateSendButtonState(true)
    }

    private fun handleSpeechOutput(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        if (trimmed.equals("Thinking...", ignoreCase = true) ||
            trimmed.equals("Pensando...", ignoreCase = true) ||
            trimmed.startsWith("Ejecutando", ignoreCase = true)
        ) {
            return
        }
        ttsController.speak(text)
    }

    private fun addErrorMessage(error: String) {
        messages.add(ChatMessage(error, false))
        chatAdapter.notifyItemInserted(messages.size - 1)
        chatRecycler.scrollToPosition(messages.size - 1)
        historyStore.append(ChatMessage(error, false))
    }

    private fun updateTtsMenuIcon(item: MenuItem) {
        if (ttsController.enabled) {
            item.setIcon(android.R.drawable.ic_lock_silent_mode_off)
            item.title = "TTS On"
        } else {
            item.setIcon(android.R.drawable.ic_lock_silent_mode)
            item.title = "TTS Off"
        }
    }

    private fun stopSpeechOutput() {
        ttsController.stop()
    }

    private suspend fun handleToolGate(call: ToolCall): ToolResult? {
        val args = safeArgs(call)
        val enabledResult = enforceToolEnabled(call)
        if (enabledResult != null) {
            return enabledResult
        }
        val permissionList = requiredPermissions(call.name)
        if (permissionList.isNotEmpty()) {
            val granted = ensurePermissions(permissionList)
            if (!granted) {
                return ToolResult(call.id, call.name, "Permiso denegado para ${call.name}.", true)
            }
        }

        if (requiresConfirmation(call.name)) {
            val confirmed = confirmTool(call, args)
            if (!confirmed) {
                return ToolResult(call.id, call.name, "Accion cancelada por el usuario.", true)
            }
        }
        return null
    }

    private fun safeArgs(call: ToolCall): JSONObject {
        return try {
            if (call.arguments.isBlank()) JSONObject() else JSONObject(call.arguments)
        } catch (e: Exception) {
            JSONObject()
        }
    }

    private fun requiredPermissions(toolName: String): List<String> {
        if (toolName.startsWith("mcp.")) {
            val parsed = McpToolAdapter.parseToolName(toolName)
            if (parsed?.serverName == "calendar") {
                return listOf(
                    Manifest.permission.READ_CALENDAR,
                    Manifest.permission.WRITE_CALENDAR
                )
            }
            return emptyList()
        }
        return when (toolName) {
            "search_contacts" -> listOf(Manifest.permission.READ_CONTACTS)
            "get_location" -> listOf(Manifest.permission.ACCESS_FINE_LOCATION)
            else -> emptyList()
        }
    }

    private fun requiresConfirmation(toolName: String): Boolean {
        return when (toolName) {
            "send_sms" -> settingsManager.toolAskSms
            "make_call" -> settingsManager.toolAskCall
            "set_alarm" -> settingsManager.toolAskAlarm
            "open_app" -> settingsManager.toolAskOpenApp
            "search_contacts" -> settingsManager.toolAskContacts
            "get_location" -> settingsManager.toolAskLocation
            "get_weather" -> settingsManager.toolAskWeather
            "read_notifications" -> settingsManager.toolAskNotifications
            else -> {
                if (!toolName.startsWith("mcp.")) {
                    false
                } else {
                    val parsed = McpToolAdapter.parseToolName(toolName)
                    val config = parsed?.let { settingsManager.getMcpServerByName(it.serverName) }
                    config?.ask == true
                }
            }
        }
    }

    private suspend fun ensurePermissions(permissions: List<String>): Boolean {
        return permissionController.ensurePermissions(permissions) {
            showPermissionSettingsDialog()
        }
    }

    private suspend fun confirmTool(call: ToolCall, args: JSONObject): Boolean {
        return withContext(Dispatchers.Main) {
            val deferred = CompletableDeferred<Boolean>()
            val message = buildConfirmationMessage(call, args)
            AlertDialog.Builder(this@ChatActivity)
                .setTitle("Confirmar accion")
                .setMessage(message)
                .setPositiveButton("Habilitar") { _, _ -> deferred.complete(true) }
                .setNegativeButton("Cancelar") { _, _ -> deferred.complete(false) }
                .setOnCancelListener { deferred.complete(false) }
                .show()
            deferred.await()
        }
    }

    private fun enforceToolEnabled(call: ToolCall): ToolResult? {
        if (!settingsManager.toolsEnabled) {
            return ToolResult(call.id, call.name, "Tools desactivadas en ajustes.", true)
        }
        val allowed = when (call.name) {
            "send_sms" -> settingsManager.toolAllowSms
            "make_call" -> settingsManager.toolAllowCall
            "set_alarm" -> settingsManager.toolAllowAlarm
            "open_app" -> settingsManager.toolAllowOpenApp
            "search_contacts" -> settingsManager.toolAllowContacts
            "get_location" -> settingsManager.toolAllowLocation
            "get_weather" -> settingsManager.toolAllowWeather
            "read_notifications" -> settingsManager.toolAllowNotifications
            else -> {
                if (!call.name.startsWith("mcp.")) {
                    true
                } else if (!settingsManager.mcpEnabled) {
                    false
                } else {
                    val parsed = McpToolAdapter.parseToolName(call.name)
                    val config = parsed?.let { settingsManager.getMcpServerByName(it.serverName) }
                    config?.enabled == true
                }
            }
        }
        return if (!allowed) {
            ToolResult(call.id, call.name, "Tool desactivada en ajustes.", true)
        } else {
            null
        }
    }

    private fun buildConfirmationMessage(call: ToolCall, args: JSONObject): String {
        return when (call.name) {
            "send_sms" -> {
                val number = args.optString("number")
                val message = args.optString("message")
                "Enviar SMS a $number?\nMensaje: $message"
            }
            "make_call" -> {
                val number = args.optString("number")
                "Llamar a $number?"
            }
            "open_app" -> {
                val pkg = args.optString("package")
                val query = args.optString("query")
                val target = if (pkg.isNotBlank()) pkg else query
                "Abrir la app $target?"
            }
            "set_alarm" -> {
                val hour = args.optInt("hour", -1)
                val minute = args.optInt("minute", -1)
                "Crear alarma a las %02d:%02d?".format(hour, minute)
            }
            "search_contacts" -> "Habilitar acceso a contactos para buscar?"
            "get_location" -> "Habilitar acceso a ubicacion?"
            else -> "Habilitar ejecutar ${call.name}?"
        }
    }

    private fun showPermissionSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permiso requerido")
            .setMessage("Necesitas habilitar el permiso en Ajustes para continuar.")
            .setPositiveButton("Abrir ajustes") { _, _ ->
                val intent = Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        ttsController.release()
        speechRecognizer?.destroy()
        speechRecognizer = null
        whisperController.cleanup()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.chat_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, MainActivity::class.java))
                true
            }
            R.id.action_tts_toggle -> {
                ttsController.enabled = !ttsController.enabled
                updateTtsMenuIcon(item)
                true
            }
            R.id.action_clear_chat -> {
                messages.clear()
                chatController.clearHistory()
                chatAdapter.notifyDataSetChanged()
                historyStore.clear()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        val item = menu?.findItem(R.id.action_tts_toggle)
        if (item != null) {
            updateTtsMenuIcon(item)
        }
        return super.onPrepareOptionsMenu(menu)
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { _, windowInsets ->
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            toolbar.updatePadding(top = systemBars.top)
            findViewById<android.view.View>(R.id.input_container)?.updatePadding(bottom = maxOf(systemBars.bottom, ime.bottom))
            windowInsets
        }
    }

    private fun setToolProgressVisible(visible: Boolean) {
        toolProgressContainer.visibility = if (visible) android.view.View.VISIBLE else android.view.View.GONE
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
        statusText.text = "Ready"
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
        androidx.appcompat.app.AlertDialog.Builder(this)
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

    private fun updateSendButtonState(inFlight: Boolean) {
        if (inFlight) {
            sendButton.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
        } else {
            sendButton.setImageResource(android.R.drawable.ic_menu_send)
        }
    }

    private fun estimateContextTokens(): Int {
        val totalChars = messages.sumOf { it.text.length }
        return (totalChars / 4).coerceAtLeast(0)
    }

    private fun estimateTokenCount(text: String): Int {
        if (text.isBlank()) return 0
        return (text.length / 4).coerceAtLeast(1)
    }

    companion object {
        private const val TAG = "ChatActivity"
    }
}
