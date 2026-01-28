package com.sbf.assistant

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.Locale
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
    private lateinit var toolProgressContainer: View
    private lateinit var toolProgressText: TextView
    private lateinit var voiceVisualizer: VoiceVisualizerView
    private lateinit var sttButtonsContainer: View
    private lateinit var normalInputContainer: View
    private lateinit var btnStopStt: MaterialButton
    private lateinit var btnCancelStt: MaterialButton
    private lateinit var pttHint: TextView

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
    private lateinit var updateChecker: UpdateChecker

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
        voiceVisualizer = findViewById(R.id.voice_visualizer)
        sttButtonsContainer = findViewById(R.id.stt_buttons_container)
        normalInputContainer = findViewById(R.id.normal_input_container)
        btnStopStt = findViewById(R.id.btn_stop_stt)
        btnCancelStt = findViewById(R.id.btn_cancel_stt)
        pttHint = findViewById(R.id.tv_ptt_hint)

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

        geminiNano = GeminiNanoService(this.applicationContext)
        localLlm = LocalLlmService(this.applicationContext)
        mediaPipeLlm = MediaPipeLlmService(this.applicationContext)

        scope.launch {
            geminiNano?.initialize()
            val installedModels = downloadManager.getInstalledModels(downloadManager.getAvailableModels(settingsManager))
            val selected = settingsManager.localAgentModel
            val selectedModel = installedModels.firstOrNull { it.filename == selected }
            val effectiveSelected = selectedModel?.takeIf { it.category != "LLM-Multimodal" }

            if (effectiveSelected != null) {
                when (effectiveSelected.type) {
                    "task" -> mediaPipeLlm?.initializeWithModel(effectiveSelected)
                    "tflite" -> localLlm?.initializeWithModel(effectiveSelected, settingsManager.hfApiKey)
                }
            }
        }

        chatController = ChatController(settingsManager, toolExecutor, toolRegistry, geminiNano, localLlm, mediaPipeLlm, downloadManager, scope)

        chatAdapter = ChatAdapter(messages, this, 
            onCancelClick = { index -> cancelToolCallsFromUi(index) },
            onStatsClick = { index -> showStatsForMessage(index) },
            onThoughtToggle = { index -> toggleThought(index) })
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
            if (isRequestInFlight) cancelRequestFromUi()
            else {
                val text = inputField.text.toString()
                if (text.isNotBlank()) {
                    processUserQuery(text)
                    inputField.text.clear()
                }
            }
        }

        micButton.setOnClickListener { handleMicClick() }
        btnStopStt.setOnClickListener {
            val config = activeSttConfig ?: settingsManager.getCategoryConfig(Category.STT).primary
            if (config != null) stopWhisperAndTranscribe(config)
        }
        btnCancelStt.setOnClickListener { cancelWhisper() }

        // Check for updates
        updateChecker = UpdateChecker(this)
        checkForUpdates()
    }

    private fun checkForUpdates() {
        scope.launch {
            val release = updateChecker.checkForUpdate()
            if (release != null) {
                updateChecker.showUpdateDialog(this@ChatActivity, release)
            }
        }
    }

    private fun handleMicClick() {
        if (!permissionController.hasPermission(Manifest.permission.RECORD_AUDIO)) {
            permissionController.requestPermission(Manifest.permission.RECORD_AUDIO) { granted ->
                if (granted) handleMicClick()
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
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { updateMicButtonSize() }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        var startX = 0f
        var isCancelled = false
        micButton.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    isCancelled = false
                    v.postDelayed({
                        if (v.isPressed && !isCancelled && sttMode == SttMode.NONE) {
                            val sttConfig = settingsManager.getCategoryConfig(Category.STT).primary
                            if (sttConfig != null && sttConfig.endpointId != "system") {
                                startWhisperPtt(sttConfig)
                            }
                        }
                    }, 300)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (sttMode == SttMode.PTT && !isCancelled) {
                        val diffX = event.rawX - startX
                        if (diffX > 150) {
                            isCancelled = true
                            cancelWhisper()
                            pttHint.text = "Cancelled!"
                            v.postDelayed({ if (sttMode == SttMode.NONE) pttHint.visibility = View.GONE }, 1000)
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (sttMode == SttMode.PTT) {
                        if (!isCancelled) {
                            val config = activeSttConfig ?: settingsManager.getCategoryConfig(Category.STT).primary
                            if (config != null) stopWhisperAndTranscribe(config)
                        }
                    }
                }
            }
            false
        }
    }

    private fun updateMicButtonSize() {
        val empty = inputField.text.isNullOrBlank()
        val sizeDp = if (empty) 72 else 48
        val sizePx = (sizeDp * resources.displayMetrics.density).toInt()
        val params = micButton.layoutParams
        params.width = sizePx
        params.height = sizePx
        micButton.layoutParams = params
    }

    private fun toggleWhisperRecording(config: ModelConfig) {
        if (!whisperController.isRecording()) {
            startWhisperAuto(config)
        } else {
            if (sttMode == SttMode.AUTO) cancelWhisper()
            else stopWhisperAndTranscribe(config)
        }
    }

    private fun startWhisperAuto(config: ModelConfig) {
        ttsController.stop() // Detener reproducción al empezar a grabar
        statusText.text = "Cargando Whisper..."
        scope.launch {
            if (config.endpointId == "local") {
                if (!whisperController.prepareLocalModel()) {
                    statusText.text = "Error local"
                    return@launch
                }
            }
            showSttUi(true, ptt = false)
            val file = whisperController.startRecording(
                usePcm = true,
                autoStopOnSilence = true,
                onSilenceDetected = { if (sttMode == SttMode.AUTO) stopWhisperAndTranscribe(config) },
                onReadyToSpeak = { cueReadyToSpeak() },
                onAmplitudeUpdate = { amp -> voiceVisualizer.addAmplitude(amp) }
            )
            if (file == null) {
                statusText.text = "Error"
                showSttUi(false)
            } else {
                sttMode = SttMode.AUTO
                activeSttConfig = config
                statusText.text = "Escuchando..."
            }
        }
    }

    private fun startWhisperPtt(config: ModelConfig) {
        ttsController.stop() // Detener reproducción en PTT también
        statusText.text = "Cargando Whisper..."
        scope.launch {
            if (config.endpointId == "local") {
                if (!whisperController.prepareLocalModel()) {
                    statusText.text = "Error local"
                    return@launch
                }
            }
            showSttUi(true, ptt = true)
            val file = whisperController.startRecording(usePcm = true, autoStopOnSilence = false, 
                onReadyToSpeak = { cueReadyToSpeak() },
                onAmplitudeUpdate = { amp -> voiceVisualizer.addAmplitude(amp) })
            if (file == null) {
                statusText.text = "Error"
                showSttUi(false)
            } else {
                sttMode = SttMode.PTT
                activeSttConfig = config
                statusText.text = "Escuchando (PTT)..."
            }
        }
    }

    private fun cancelWhisper() {
        whisperController.cancelRecording()
        sttMode = SttMode.NONE
        activeSttConfig = null
        statusText.text = "Ready"
        showSttUi(false)
    }

    private fun stopWhisperAndTranscribe(config: ModelConfig) {
        statusText.text = "Transcribing..."
        sttMode = SttMode.NONE
        activeSttConfig = null
        showSttUi(false)
        whisperController.stopAndTranscribe(config) { text, error ->
            if (text != null) {
                val processed = text.trim()
                if (processed.isNotBlank()) {
                    inputField.setText(processed)
                    processUserQuery(processed)
                    inputField.text.clear()
                }
            } else {
                statusText.text = "Ready"
                Toast.makeText(this, error ?: "Whisper Error", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showSttUi(recording: Boolean, ptt: Boolean = false) {
        if (recording) {
            voiceVisualizer.visibility = View.VISIBLE
            voiceVisualizer.clear()
            if (ptt) {
                sttButtonsContainer.visibility = View.GONE
                normalInputContainer.visibility = View.VISIBLE
                pttHint.visibility = View.VISIBLE
                pttHint.text = "Desliza a la derecha para cancelar"
            } else {
                sttButtonsContainer.visibility = View.VISIBLE
                normalInputContainer.visibility = View.GONE
                pttHint.visibility = View.GONE
            }
        } else {
            sttButtonsContainer.visibility = View.GONE
            normalInputContainer.visibility = View.VISIBLE
            voiceVisualizer.visibility = View.GONE
            pttHint.visibility = View.GONE
        }
    }

    private fun cueReadyToSpeak() {
        try {
            val vibrator = getSystemService(android.os.Vibrator::class.java)
            vibrator?.vibrate(android.os.VibrationEffect.createOneShot(40, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            val tone = android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 70)
            tone.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 100)
        } catch (_: Exception) {}
    }

    private fun startSystemListening() {
        ttsController.stop()
        if (speechRecognizer == null) speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { 
                statusText.text = "Listening..."; showSttUi(true, ptt = false)
                cueReadyToSpeak()
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { voiceVisualizer.addAmplitude((rmsdB + 2.0f) / 10.0f) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { statusText.text = "Processing..."; showSttUi(false) }
            override fun onError(error: Int) { statusText.text = "Ready"; showSttUi(false) }
            override fun onResults(results: Bundle?) {
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { text ->
                    val processed = text.trim()
                    if (processed.isNotBlank()) {
                        inputField.setText(processed)
                        processUserQuery(processed)
                        inputField.text.clear()
                    }
                }
                statusText.text = "Ready"
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
            statusText.text = String.format(Locale.US, "%s (%.1fs, %d tok, %d ctx)", prefix, seconds, lastRequestTokenCount, lastRequestContextTokens)
        }

        statusTickerJob = scope.launch(Dispatchers.Main) {
            while (isRequestInFlight) {
                lastRequestElapsedMs = android.os.SystemClock.elapsedRealtime() - requestStartMs
                updateStatusLabel(statusPrefix)
                delay(200)
            }
        }

        chatController.processQuery(query, object : ChatController.Callbacks {
            override fun onStatusUpdate(status: String) { updateStatusLabel(status) }
            override fun onResponseToken(token: String) {
                val chunk = thinkFilter.consume(token)
                if (chunk.thoughtDelta.isEmpty() && chunk.answerDelta.isEmpty()) return
                if (chunk.thoughtDelta.isNotEmpty()) thoughtBuffer += chunk.thoughtDelta
                if (awaitingFirstResponse) { awaitingFirstResponse = false; setToolProgressVisible(false) }
                if (toolInProgress) { toolInProgress = false; setToolProgressVisible(false) }
                if (chunk.answerDelta.isNotEmpty()) currentTokenCount += (chunk.answerDelta.length / 4).coerceAtLeast(1)
                
                lastRequestTokenCount = currentTokenCount
                if (fullResponse.isEmpty() && currentAssistantMessageIndex == -1) {
                    fullResponse = chunk.answerDelta
                    val message = ChatMessage(text = fullResponse, isUser = false, thought = thoughtBuffer, isThinking = true, stats = ChatStats(currentToolCount, currentTokenCount))
                    messages.add(message)
                    currentAssistantMessageIndex = messages.size - 1
                    chatAdapter.notifyItemInserted(currentAssistantMessageIndex)
                } else {
                    fullResponse += chunk.answerDelta
                    val updated = ChatMessage(text = fullResponse, isUser = false, thought = thoughtBuffer, isThinking = true, stats = ChatStats(currentToolCount, currentTokenCount))
                    messages[currentAssistantMessageIndex] = updated
                    chatAdapter.notifyItemChanged(currentAssistantMessageIndex)
                }
                if (autoScrollEnabled) chatRecycler.scrollToPosition(messages.size - 1)
            }

            override fun onResponseComplete(fullResponse: String) {
                val cleaned = thinkFilter.stripAll(fullResponse).trim()
                isRequestInFlight = false
                statusTickerJob?.cancel()
                setToolProgressVisible(false)
                if (cleaned.isNotBlank()) {
                    val updated = ChatMessage(text = cleaned, isUser = false, thought = thoughtBuffer.trim(), isThinking = false, thoughtCollapsed = true, stats = ChatStats(currentToolCount, currentTokenCount))
                    if (currentAssistantMessageIndex != -1) {
                        messages[currentAssistantMessageIndex] = updated
                        chatAdapter.notifyItemChanged(currentAssistantMessageIndex)
                    } else {
                        messages.add(updated)
                        chatAdapter.notifyItemInserted(messages.size - 1)
                    }
                    historyStore.append(ChatMessage(cleaned, false))
                    ttsController.speak(cleaned)
                }
                chatRecycler.postDelayed({ if (!isRequestInFlight) statusText.text = "Ready" }, 1200)
                updateSendButtonState(false)
            }

            override fun onToolExecutionStart() { toolInProgress = true; setToolProgressVisible(true) }
            override fun onToolCalls(toolCalls: List<ToolCall>) { currentToolCount = toolCalls.size }
            override fun onError(error: String, wasPrimary: Boolean) { isRequestInFlight = false; statusText.text = "Error: $error"; setToolProgressVisible(false); updateSendButtonState(false) }
            override fun handleToolGate(call: ToolCall): ToolResult? {
                return runBlocking { this@ChatActivity.handleToolGate(call) }
            }
        })
        updateSendButtonState(true)
    }

    private suspend fun handleToolGate(call: ToolCall): ToolResult? {
        if (!settingsManager.toolsEnabled) return ToolResult(call.id, call.name, "Tools desactivadas.", true)
        val permissionList = when (call.name) {
            "search_contacts" -> listOf(Manifest.permission.READ_CONTACTS)
            "get_location" -> listOf(Manifest.permission.ACCESS_FINE_LOCATION)
            else -> emptyList()
        }
        if (permissionList.isNotEmpty() && !permissionController.ensurePermissions(permissionList) { showPermissionSettingsDialog() }) {
            return ToolResult(call.id, call.name, "Permiso denegado.", true)
        }
        return null
    }

    private fun showPermissionSettingsDialog() {
        AlertDialog.Builder(this).setTitle("Permiso requerido").setMessage("Habilita el permiso en Ajustes.")
            .setPositiveButton("Ajustes") { _, _ ->
                startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
            }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun updateTtsMenuIcon(item: MenuItem) {
        if (ttsController.enabled) { item.setIcon(android.R.drawable.ic_lock_silent_mode_off); item.title = "TTS On" }
        else { item.setIcon(android.R.drawable.ic_lock_silent_mode); item.title = "TTS Off" }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        ttsController.release()
        speechRecognizer?.destroy()
        whisperController.cleanup()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.chat_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> { startActivity(Intent(this, MainActivity::class.java)); true }
            R.id.action_tts_toggle -> { ttsController.enabled = !ttsController.enabled; updateTtsMenuIcon(item); true }
            R.id.action_clear_chat -> { messages.clear(); chatController.clearHistory(); chatAdapter.notifyDataSetChanged(); historyStore.clear(); true }
            R.id.action_check_updates -> { checkForUpdatesManually(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun checkForUpdatesManually() {
        scope.launch {
            statusText.text = "Checking for updates..."
            updateChecker.clearSkippedVersion()
            val release = updateChecker.checkForUpdate(forceCheck = true)
            if (release != null) {
                updateChecker.showUpdateDialog(this@ChatActivity, release)
            } else {
                statusText.text = "You have the latest version"
            }
        }
    }

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        menu?.findItem(R.id.action_tts_toggle)?.let { updateTtsMenuIcon(it) }
        return super.onPrepareOptionsMenu(menu)
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { _, windowInsets ->
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            toolbar.updatePadding(top = systemBars.top)
            findViewById<View>(R.id.input_container)?.updatePadding(bottom = maxOf(systemBars.bottom, ime.bottom))
            windowInsets
        }
    }

    private fun setToolProgressVisible(visible: Boolean) {
        toolProgressContainer.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun cancelRequestFromUi() {
        chatController.cancelCurrentRequest()
        isRequestInFlight = false
        statusTickerJob?.cancel()
        statusText.text = "Ready"
        setToolProgressVisible(false)
        updateSendButtonState(false)
        if (currentAssistantMessageIndex >= 0) {
            messages[currentAssistantMessageIndex] = messages[currentAssistantMessageIndex].copy(text = "Solicitud cancelada.", showCancel = false)
            chatAdapter.notifyItemChanged(currentAssistantMessageIndex)
        }
    }

    private fun cancelToolCallsFromUi(index: Int) {
        messages.getOrNull(index)?.let { msg ->
            if (msg.toolCallIds.isNotEmpty()) chatController.cancelToolCalls(msg.toolCallIds)
            messages[index] = msg.copy(text = "Tools canceladas.", showCancel = false)
            chatAdapter.notifyItemChanged(index)
        }
    }

    private fun showStatsForMessage(index: Int) {
        messages.getOrNull(index)?.stats?.let { stats ->
            AlertDialog.Builder(this).setTitle("Estadisticas").setMessage("Tools: ${stats.toolCount}\nTokens: ${stats.tokenCount}").setPositiveButton("OK", null).show()
        }
    }

    private fun toggleThought(index: Int) {
        messages.getOrNull(index)?.let { msg ->
            messages[index] = msg.copy(thoughtCollapsed = !msg.thoughtCollapsed)
            chatAdapter.notifyItemChanged(index)
        }
    }

    private fun updateSendButtonState(inFlight: Boolean) {
        sendButton.setImageResource(if (inFlight) android.R.drawable.ic_menu_close_clear_cancel else android.R.drawable.ic_menu_send)
    }

    private enum class SttMode { NONE, AUTO, PTT }
}
