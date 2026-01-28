package com.sbf.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.service.voice.VoiceInteractionSession
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale
import com.sbf.assistant.llm.LocalLlmService
import com.sbf.assistant.llm.MediaPipeLlmService

/**
 * Sesión de interacción por voz del asistente.
 * Rediseñada para prioridad total en voz, gestos PTT y navegación fluida.
 * Implementa Barge-in (escucha mientras habla) y Standby dinámico.
 */
class AssistantSession(context: Context) : VoiceInteractionSession(context), LifecycleOwner {
    
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    private lateinit var statusText: TextView
    private lateinit var bigMicButton: MaterialButton
    private lateinit var btnStopStt: MaterialButton
    private lateinit var btnCancelStt: MaterialButton
    private lateinit var btnStandbyToggle: MaterialButton
    private lateinit var sttButtonsContainer: View
    private lateinit var btnClose: MaterialButton
    private lateinit var chatRecycler: RecyclerView
    private lateinit var toolProgressContainer: View
    private lateinit var toolProgressText: TextView
    private lateinit var voiceVisualizer: VoiceVisualizerView
    private lateinit var pttHint: TextView
    private lateinit var assistantCard: View

    private lateinit var settingsManager: SettingsManager
    private lateinit var ttsController: TtsController
    private lateinit var whisperController: WhisperController
    private lateinit var toolRegistry: ToolRegistry
    private lateinit var toolExecutor: ToolExecutor
    private lateinit var mcpClient: McpClient
    private lateinit var localRuntime: LocalModelRuntime
    private var geminiNano: GeminiNanoService? = null
    private var localLlm: LocalLlmService? = null
    private var mediaPipeLlm: MediaPipeLlmService? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private lateinit var historyStore: ChatHistoryStore
    private lateinit var warmupManager: WarmupManager

    private val messages = mutableListOf<ChatMessage>()
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var chatController: ChatController
    private var isRequestInFlight = false
    private var currentAssistantMessageIndex = -1
    private var currentTokenCount = 0
    private var currentToolCount = 0
    private var lastRequestTokenCount = 0
    private var statusTickerJob: Job? = null
    private var sttMode: SttMode = SttMode.NONE
    private var activeSttConfig: ModelConfig? = null
    private var isStandbyModeActive = false
    private var standbyTimeoutJob: Job? = null
    private var isTtsSpeaking = false
    private var isSessionActive = false  // Para evitar que coroutines sigan después de onHide
    private val STANDBY_TIMEOUT_MS = 30_000L

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        
        settingsManager = SettingsManager(context)
        warmupManager = WarmupManager(context, settingsManager)
        ttsController = TtsController(context, settingsManager)
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        mcpClient = McpServerFactory.createClient(context, settingsManager)
        toolRegistry = ToolRegistry(settingsManager, mcpClient)
        toolExecutor = ToolExecutor(context, mcpClient)
        localRuntime = LocalModelRuntime(context, settingsManager)
        
        val localWhisper = LocalWhisperService(context, settingsManager, localRuntime, ModelDownloadManager(context))
        whisperController = WhisperController(
            settingsManager,
            AudioRecorder(context),
            localWhisper,
            scope
        )
        geminiNano = GeminiNanoService(context)
        localLlm = LocalLlmService(context)
        mediaPipeLlm = MediaPipeLlmService(context)

        scope.launch {
            geminiNano?.initialize()
            val downloadManager = ModelDownloadManager(context)
            val installedModels = downloadManager.getInstalledModels(downloadManager.getAvailableModels(settingsManager))
            val selected = settingsManager.localAgentModel
            val selectedModel = installedModels.firstOrNull { it.filename == selected }
            val effectiveSelected = selectedModel?.takeIf { it.category != "LLM-Text" || it.filename.endsWith(".task") || it.filename.endsWith(".tflite") }

            if (effectiveSelected != null) {
                when (effectiveSelected.type) {
                    "task" -> mediaPipeLlm?.initializeWithModel(effectiveSelected)
                    "tflite" -> localLlm?.initializeWithModel(effectiveSelected, settingsManager.hfApiKey)
                }
            }
        }

        chatController = ChatController(settingsManager, toolExecutor, toolRegistry, geminiNano, localLlm, mediaPipeLlm, ModelDownloadManager(context), scope)
        historyStore = ChatHistoryStore(context)
    }

    override fun onCreateContentView(): View {
        val themedContext = ContextThemeWrapper(context, R.style.Theme_Assistant)
        val view = LayoutInflater.from(themedContext).inflate(R.layout.assistant_overlay, null)
        assistantCard = view.findViewById(R.id.assistant_card)
        
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            val bottomInset = maxOf(systemBars.bottom, ime.bottom)
            assistantCard.updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin = bottomInset }
            windowInsets
        }

        statusText = view.findViewById(R.id.status_text)
        bigMicButton = view.findViewById(R.id.big_mic_button)
        btnStopStt = view.findViewById(R.id.btn_stop_stt)
        btnCancelStt = view.findViewById(R.id.btn_cancel_stt)
        btnStandbyToggle = view.findViewById(R.id.btn_standby_toggle)
        sttButtonsContainer = view.findViewById(R.id.stt_buttons_container)
        btnClose = view.findViewById(R.id.btn_close)
        chatRecycler = view.findViewById(R.id.chat_recycler)
        toolProgressContainer = view.findViewById(R.id.tool_progress_container)
        toolProgressText = view.findViewById(R.id.tool_progress_text)
        voiceVisualizer = view.findViewById(R.id.voice_visualizer)
        pttHint = view.findViewById(R.id.tv_ptt_hint)

        chatAdapter = ChatAdapter(messages, themedContext, 
            onCancelClick = { index -> cancelToolCallsFromUi(index) },
            onStatsClick = { index -> showStatsForMessage(index) },
            onThoughtToggle = { index -> toggleThought(index) })
        chatRecycler.adapter = chatAdapter
        chatRecycler.itemAnimator = null 
        chatRecycler.layoutManager = LinearLayoutManager(context).apply { stackFromEnd = true }

        val storedMessages = historyStore.load()
        if (storedMessages.isNotEmpty()) {
            messages.addAll(storedMessages)
            chatAdapter.notifyDataSetChanged()
            chatRecycler.post { chatRecycler.scrollToPosition(messages.size - 1) }
        }

        setupVoiceControls()
        setupStandbyToggle()
        btnClose.setOnClickListener { finish() }

        val openFullApp = {
            val intent = Intent(context, ChatActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(intent)
            finish()
        }
        
        assistantCard.setOnClickListener { /* Consume clicks */ }
        assistantCard.setOnLongClickListener { openFullApp(); true }
        chatRecycler.setOnLongClickListener { openFullApp(); true }
        view.findViewById<View>(R.id.background_scrim).setOnClickListener { finish() }

        return view
    }

    private fun setupStandbyToggle() {
        // Leer el setting actual - si está deshabilitado, no activar standby
        isStandbyModeActive = settingsManager.voiceShortcutEnabled
        updateStandbyIcon()

        btnStandbyToggle.setOnClickListener {
            // Solo permitir toggle si el setting está habilitado
            if (!settingsManager.voiceShortcutEnabled) {
                statusText.text = "Activa 'Palabra clave' en ajustes"
                return@setOnClickListener
            }
            isStandbyModeActive = !isStandbyModeActive
            updateStandbyIcon()
            if (isStandbyModeActive) startStandbyMonitor()
            else {
                stopStandbyMonitor()
                statusText.text = "Escucha de palabra clave desactivada"
            }
        }
    }

    private fun updateStandbyIcon() {
        btnStandbyToggle.setIconResource(android.R.drawable.ic_popup_sync)
        // Si el setting global está deshabilitado, mostrar más transparente
        val settingEnabled = settingsManager.voiceShortcutEnabled
        btnStandbyToggle.alpha = when {
            !settingEnabled -> 0.2f
            isStandbyModeActive -> 1.0f
            else -> 0.4f
        }
    }

    private fun setupVoiceControls() {
        var startX = 0f
        var isCancelled = false
        var isLongPress = false
        var longPressRunnable: Runnable? = null

        bigMicButton.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    isCancelled = false
                    isLongPress = false
                    longPressRunnable = Runnable {
                        if (v.isPressed && !isCancelled && (sttMode == SttMode.NONE || sttMode == SttMode.STANDBY)) {
                            isLongPress = true
                            startListeningPTT()
                        }
                    }
                    v.postDelayed(longPressRunnable, 400)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (sttMode == SttMode.PTT && !isCancelled) {
                        val diffX = event.rawX - startX
                        if (diffX > 150) {
                            isCancelled = true
                            cancelWhisper()
                            pttHint.text = "¡Cancelado!"
                            v.postDelayed({ pttHint.animate().alpha(0f).setDuration(300).start() }, 500)
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    longPressRunnable?.let { v.removeCallbacks(it) }
                    longPressRunnable = null

                    if (sttMode == SttMode.PTT && !isCancelled) {
                        // PTT: soltar envía a transcribir
                        if (activeSttConfig != null) {
                            stopWhisperAndTranscribe(activeSttConfig!!)
                        }
                    } else if (!isLongPress && !isCancelled && (sttMode == SttMode.NONE || sttMode == SttMode.STANDBY)) {
                        // Tap corto: modo auto
                        startListeningAuto()
                    }
                    isLongPress = false
                }
            }
            true // Consumir el evento para evitar conflictos
        }

        btnStopStt.setOnClickListener {
            if (activeSttConfig != null) stopWhisperAndTranscribe(activeSttConfig!!)
            else {
                whisperController.cancelRecording()
                sttMode = SttMode.NONE
                showSttUi(false)
            }
        }
        btnCancelStt.setOnClickListener { cancelWhisper() }
    }

    private enum class SttMode { NONE, AUTO, PTT, STANDBY }

    private fun startListeningAuto() {
        // Cancelar cualquier grabación o escucha en curso
        if (whisperController.isRecording()) {
            whisperController.cancelRecording()
        }
        stopStandbyMonitor()
        ttsController.stop()
        isTtsSpeaking = false

        sttMode = SttMode.NONE
        activeSttConfig = null

        val sttConfig = settingsManager.getCategoryConfig(Category.STT).primary
        if (sttConfig?.endpointId == "system" || sttConfig == null) {
            sttMode = SttMode.AUTO
            startSystemListening()
        } else {
            startWhisperMode(sttConfig, ptt = false)
        }
    }

    private fun startListeningPTT() {
        // Cancelar cualquier grabación o escucha en curso
        if (whisperController.isRecording()) {
            whisperController.cancelRecording()
        }
        stopStandbyMonitor()
        ttsController.stop()
        isTtsSpeaking = false

        val sttConfig = settingsManager.getCategoryConfig(Category.STT).primary
        if (sttConfig != null && sttConfig.endpointId != "system") {
            startWhisperMode(sttConfig, ptt = true)
        } else {
            statusText.text = "PTT requiere Whisper (no sistema)"
        }
    }

    private fun startSystemListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            // Intentar reducir pitidos si el sistema lo permite
            putExtra("android.speech.extra.DICTATION_MODE", true)
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (sttMode == SttMode.STANDBY) {
                    val phrase = settingsManager.voiceShortcutPhrase.trim()
                    statusText.text = "Vigilando: '$phrase' o 'Stop'"
                } else {
                    statusText.text = "Escuchando..."
                    showSttUi(true, ptt = false)
                    cueReadyToSpeak()
                }
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {
                if (sttMode != SttMode.STANDBY) voiceVisualizer.addAmplitude((rmsdB + 2.0f) / 10.0f)
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                if (sttMode == SttMode.STANDBY) {
                    // Reintentar listener sin reiniciar timeout, solo si la sesión sigue activa
                    scope.launch {
                        delay(1500)
                        if (isSessionActive && isStandbyModeActive && sttMode == SttMode.STANDBY) {
                            startSystemListening()
                        }
                    }
                } else {
                    statusText.text = "Error: $error"
                    showSttUi(false)
                    sttMode = SttMode.NONE
                }
            }
            override fun onResults(results: Bundle?) {
                val query = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                handleSystemVoiceResult(query)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val query = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                handleSystemVoiceResult(query, isPartial = true)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        speechRecognizer?.startListening(intent)
    }

    private fun handleSystemVoiceResult(query: String, isPartial: Boolean = false) {
        val phrase = settingsManager.voiceShortcutPhrase.trim()
        val isStop = query.contains("stop", ignoreCase = true) || query.contains("parar", ignoreCase = true)
        val isWake = phrase.isNotBlank() && query.contains(phrase, ignoreCase = true)

        if (isStop) {
            speechRecognizer?.stopListening()
            ttsController.stop()
            isTtsSpeaking = false
            chatController.cancelCurrentRequest()
            isStandbyModeActive = false
            goToSleep()
            return
        }

        if (isWake) {
            stopStandbyMonitor()
            ttsController.stop()
            isTtsSpeaking = false
            startListeningAuto()
            return
        }

        if (!isPartial && sttMode == SttMode.STANDBY) {
            // Reiniciar vigilancia si no hubo match, solo si sesión activa
            scope.launch {
                delay(500)
                if (isSessionActive && isStandbyModeActive && sttMode == SttMode.STANDBY) {
                    startSystemListening()
                }
            }
        } else if (!isPartial && sttMode != SttMode.STANDBY && !isRequestInFlight) {
            val processed = query.trim()
            if (processed.isNotBlank()) processUserQuery(processed)
            statusText.text = "Assistant Ready"
        }
    }

    private fun startWhisperMode(config: ModelConfig, ptt: Boolean) {
        // Guardar config y modo ANTES del coroutine para evitar race conditions
        activeSttConfig = config
        sttMode = if (ptt) SttMode.PTT else SttMode.AUTO
        showSttUi(true, ptt)

        scope.launch(Dispatchers.IO) {
            if (config.endpointId == "local") {
                if (!whisperController.prepareLocalModel()) {
                    withContext(Dispatchers.Main) {
                        showSttUi(false)
                        sttMode = SttMode.NONE
                        activeSttConfig = null
                        statusText.text = "Error preparando modelo"
                    }
                    return@launch
                }
            }
            val file = whisperController.startRecording(
                usePcm = true,
                autoStopOnSilence = !ptt,
                onSilenceDetected = {
                    scope.launch(Dispatchers.Main) {
                        if (sttMode == SttMode.AUTO && activeSttConfig != null) {
                            stopWhisperAndTranscribe(activeSttConfig!!)
                        }
                    }
                },
                onReadyToSpeak = { scope.launch(Dispatchers.Main) { cueReadyToSpeak() } },
                onAmplitudeUpdate = { amp -> scope.launch(Dispatchers.Main) { voiceVisualizer.addAmplitude(amp) } }
            )
            if (file == null) withContext(Dispatchers.Main) {
                showSttUi(false)
                sttMode = SttMode.NONE
                activeSttConfig = null
                statusText.text = "Error grabación"
            }
        }
    }

    private fun stopWhisperAndTranscribe(config: ModelConfig) {
        sttMode = SttMode.NONE
        activeSttConfig = null
        showSttUi(false)
        statusText.text = "Transcribiendo..."
        // Warmup LLM while STT is processing
        warmupManager.warmupLlm()

        whisperController.stopAndTranscribe(config) { text, error ->
            scope.launch(Dispatchers.Main) {
                if (text != null) {
                    val processed = text.trim()
                    if (processed.isNotBlank()) {
                        processUserQuery(processed)
                    } else {
                        statusText.text = "Audio vacío"
                    }
                } else {
                    statusText.text = "Error STT: ${error ?: "desconocido"}"
                }
            }
        }
    }

    private fun cancelWhisper() {
        whisperController.cancelRecording()
        sttMode = SttMode.NONE
        activeSttConfig = null
        showSttUi(false)
        statusText.text = "Cancelado"
    }

    private fun startStandbyMonitor() {
        if (!isStandbyModeActive || !isSessionActive) return
        standbyTimeoutJob?.cancel()
        sttMode = SttMode.STANDBY
        startSystemListening()

        // Solo iniciar timeout si el TTS no está hablando
        if (!isTtsSpeaking) {
            startStandbyTimeout()
        }
    }

    private fun startStandbyTimeout() {
        standbyTimeoutJob?.cancel()
        standbyTimeoutJob = scope.launch {
            delay(STANDBY_TIMEOUT_MS)
            if (isSessionActive && sttMode == SttMode.STANDBY) {
                withContext(Dispatchers.Main) {
                    goToSleep()
                }
            }
        }
    }

    private fun stopStandbyMonitor() {
        standbyTimeoutJob?.cancel()
        standbyTimeoutJob = null
        if (sttMode == SttMode.STANDBY) {
            speechRecognizer?.stopListening()
            sttMode = SttMode.NONE
        }
    }

    private fun goToSleep() {
        stopStandbyMonitor()
        sttMode = SttMode.NONE
        statusText.text = "En reposo - Toca el micrófono"
        updateStandbyIcon()
    }

    private fun showSttUi(recording: Boolean, ptt: Boolean = false) {
        voiceVisualizer.visibility = if (recording && sttMode != SttMode.STANDBY) View.VISIBLE else View.GONE
        sttButtonsContainer.visibility = if (recording && !ptt && sttMode != SttMode.STANDBY) View.VISIBLE else View.GONE
        bigMicButton.visibility = if (!recording || ptt || sttMode == SttMode.STANDBY) View.VISIBLE else View.GONE
        pttHint.alpha = if (recording && ptt) 1f else 0f
    }

    private fun cueReadyToSpeak() {
        try {
            val vibrator = context.getSystemService(android.os.Vibrator::class.java)
            vibrator?.vibrate(android.os.VibrationEffect.createOneShot(40, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            val tone = android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 70)
            tone.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 100)
        } catch (_: Exception) {}
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        isSessionActive = true
        // Warmup both STT and LLM for voice interaction
        warmupManager.warmupStt()
        warmupManager.warmupLlm()
        // Force start recording on first open
        startListeningAuto()
    }

    override fun onHide() {
        super.onHide()
        isSessionActive = false  // IMPORTANTE: marcar como inactiva ANTES de limpiar
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)

        // Parar TODO cuando la ventana se oculta
        standbyTimeoutJob?.cancel()
        standbyTimeoutJob = null
        speechRecognizer?.stopListening()
        whisperController.cancelRecording()
        ttsController.stop()

        sttMode = SttMode.NONE
        activeSttConfig = null
        isTtsSpeaking = false
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)

        // Limpiar completamente
        standbyTimeoutJob?.cancel()
        scope.cancel()
        ttsController.release()
        speechRecognizer?.destroy()
        speechRecognizer = null
        warmupManager.release()
    }

    private fun processUserQuery(query: String) {
        stopStandbyMonitor()
        messages.add(ChatMessage(query, true))
        chatAdapter.notifyItemInserted(messages.size - 1)
        chatRecycler.scrollToPosition(messages.size - 1)
        historyStore.append(ChatMessage(query, true))

        currentAssistantMessageIndex = -1
        currentTokenCount = 0
        currentToolCount = 0
        isRequestInFlight = true
        val requestStartMs = android.os.SystemClock.elapsedRealtime()
        
        fun updateStatusLabel(prefix: String) {
            val elapsed = android.os.SystemClock.elapsedRealtime() - requestStartMs
            val seconds = elapsed / 1000.0
            statusText.text = String.format(Locale.US, "%s (%.1fs, %d tok)", prefix, seconds, lastRequestTokenCount)
        }

        statusTickerJob = scope.launch(Dispatchers.Main) {
            while (isRequestInFlight) {
                updateStatusLabel("Pensando...")
                delay(200)
            }
        }

        chatController.processQuery(query, object : ChatController.Callbacks {
            override fun onStatusUpdate(status: String) { updateStatusLabel(status) }
            override fun onResponseToken(token: String) {
                // ... logic for tokens ...
                if (currentAssistantMessageIndex == -1) {
                    messages.add(ChatMessage(token, false, stats = ChatStats(currentToolCount, currentTokenCount)))
                    currentAssistantMessageIndex = messages.size - 1
                    chatAdapter.notifyItemInserted(currentAssistantMessageIndex)
                } else {
                    val msg = messages[currentAssistantMessageIndex]
                    messages[currentAssistantMessageIndex] = msg.copy(text = msg.text + token)
                    chatAdapter.notifyItemChanged(currentAssistantMessageIndex)
                }
                chatRecycler.scrollToPosition(messages.size - 1)
            }

            override fun onResponseComplete(fullResponse: String) {
                isRequestInFlight = false
                statusTickerJob?.cancel()
                setToolProgressVisible(false)

                historyStore.append(ChatMessage(fullResponse, false))

                // Barge-in: empezar a escuchar DURANTE el TTS para permitir interrupciones
                // Solo si el setting está habilitado Y el usuario tiene el modo activo
                isTtsSpeaking = true
                if (isStandbyModeActive && settingsManager.voiceShortcutEnabled) {
                    startStandbyMonitor()
                }

                ttsController.speak(fullResponse) {
                    isTtsSpeaking = false
                    if (!isStandbyModeActive) {
                        statusText.text = "Assistant Ready"
                    } else if (sttMode == SttMode.STANDBY) {
                        // TTS terminó, ahora sí iniciar el timeout de 30 segundos
                        startStandbyTimeout()
                    }
                }

                chatRecycler.postDelayed({
                    if (!isRequestInFlight && sttMode != SttMode.STANDBY) statusText.text = "Assistant Ready"
                }, 500)
            }

            override fun onToolExecutionStart() { setToolProgressVisible(true) }
            override fun onToolCalls(toolCalls: List<ToolCall>) { currentToolCount = toolCalls.size }
            override fun onError(error: String, wasPrimary: Boolean) {
                isRequestInFlight = false
                statusText.text = "Error: $error"
                setToolProgressVisible(false)
            }
            override fun handleToolGate(call: ToolCall): ToolResult? = null
        })
    }

    private fun setToolProgressVisible(visible: Boolean) {
        toolProgressContainer.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun cancelToolCallsFromUi(index: Int) {
        val message = messages.getOrNull(index) ?: return
        if (message.toolCallIds.isNotEmpty()) chatController.cancelToolCalls(message.toolCallIds)
        messages[index] = message.copy(text = "Tools canceladas.", showCancel = false)
        chatAdapter.notifyItemChanged(index)
    }

    private fun showStatsForMessage(index: Int) {
        val message = messages.getOrNull(index) ?: return
        val stats = message.stats ?: return
        AlertDialog.Builder(context).setTitle("Estadisticas").setMessage("Tools: ${stats.toolCount}\nTokens: ${stats.tokenCount}").setPositiveButton("OK", null).show()
    }

    private fun toggleThought(index: Int) {
        val message = messages.getOrNull(index) ?: return
        messages[index] = message.copy(thoughtCollapsed = !message.thoughtCollapsed)
        chatAdapter.notifyItemChanged(index)
    }

}
