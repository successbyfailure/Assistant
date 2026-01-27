package com.sbf.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.Manifest
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputLayout
import com.sbf.assistant.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settingsManager: SettingsManager
    private lateinit var modelCacheManager: ModelCacheManager
    private lateinit var endpointAdapter: EndpointAdapter
    private lateinit var categoryAdapter: CategoryAdapter
    private lateinit var networkScanner: NetworkScanner
    private lateinit var modelDownloadManager: ModelDownloadManager
    private lateinit var permissionController: PermissionController
    private lateinit var localRuntime: LocalModelRuntime
    private lateinit var localWhisperService: LocalWhisperService
    private lateinit var localLlmService: com.sbf.assistant.llm.LocalLlmService
    private lateinit var mediaPipeLlmService: com.sbf.assistant.llm.MediaPipeLlmService
    private var geminiNanoService: GeminiNanoService? = null
    private lateinit var testRecorder: AudioRecorder
    private val aicoreDownloadStatus = mutableMapOf<String, String>()
    private val aicoreDownloadInProgress = mutableSetOf<String>()
    private var lastAicoreRenderMs = 0L
    private var testRecordingFile: java.io.File? = null
    private var testInProgress = false
    private val testStatus = mutableMapOf<String, String>()
    private val hfRepoExpanded = mutableMapOf<String, Boolean>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.apply(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settingsManager = SettingsManager(this)
        permissionController = PermissionController(this)
        modelCacheManager = ModelCacheManager(this)
        networkScanner = NetworkScanner(this)
        modelDownloadManager = ModelDownloadManager(this)
        localRuntime = LocalModelRuntime(this, settingsManager)
        localWhisperService = LocalWhisperService(this, settingsManager, localRuntime, modelDownloadManager)
        localLlmService = com.sbf.assistant.llm.LocalLlmService(this)
        mediaPipeLlmService = com.sbf.assistant.llm.MediaPipeLlmService(this)

        // Initialize Gemini Nano (via AICore)
        geminiNanoService = GeminiNanoService(this)
        lifecycleScope.launch {
            geminiNanoService?.initialize()
        }

        testRecorder = AudioRecorder(this)

        setupUI()
    }

    private fun setupUI() {
        setupTabs()
        endpointAdapter = EndpointAdapter(settingsManager.getEndpoints()) { endpoint ->
            val endpoints = settingsManager.getEndpoints().toMutableList()
            endpoints.remove(endpoint)
            settingsManager.saveEndpoints(endpoints)
            modelCacheManager.clearCache(endpoint.id)
            endpointAdapter.updateData(endpoints)
            categoryAdapter.notifyDataSetChanged()
        }
        binding.rvEndpoints.layoutManager = LinearLayoutManager(this)
        binding.rvEndpoints.adapter = endpointAdapter

        categoryAdapter = CategoryAdapter(Category.entries, settingsManager) { category ->
            showEditCategoryDialog(category)
        }
        binding.rvCategories.layoutManager = LinearLayoutManager(this)
        binding.rvCategories.adapter = categoryAdapter

        binding.btnAddEndpoint.setOnClickListener { showAddEndpointDialog() }
        binding.btnAddTemplate.setOnClickListener { showTemplatesDialog() }
        binding.btnSetDefault.setOnClickListener {
            val intent = Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS)
            startActivity(intent)
        }

        setupToolSettings()
        setupPromptSettings()
        setupThemeSettings()
        setupMcpSettings()
        setupLocalSettings()
        setupModelManager()

        // Configuration for Health Check
        val dialogSettings = LayoutInflater.from(this).inflate(R.layout.view_health_settings, null)
        val swHealth = dialogSettings.findViewById<MaterialSwitch>(R.id.sw_health_check)
        swHealth.isChecked = settingsManager.isHealthCheckEnabled
        
        swHealth.setOnCheckedChangeListener { _, isChecked ->
            settingsManager.isHealthCheckEnabled = isChecked
            if (isChecked) {
                startHealthCheck()
                Toast.makeText(this, "Health Check Enabled (every 15 min)", Toast.LENGTH_SHORT).show()
            } else {
                stopHealthCheck()
                Toast.makeText(this, "Health Check Disabled", Toast.LENGTH_SHORT).show()
            }
        }
        
        // Add the switch to the layout
        binding.sectionEndpoints.addView(dialogSettings, 3)
    }

    private fun setupTabs() {
        val tabLayout = binding.tabLayout
        tabLayout.addTab(tabLayout.newTab().setText("Endpoints"))
        tabLayout.addTab(tabLayout.newTab().setText("Categorias"))
        tabLayout.addTab(tabLayout.newTab().setText("Tools"))
        tabLayout.addTab(tabLayout.newTab().setText("Prompts"))
        tabLayout.addTab(tabLayout.newTab().setText("Apariencia"))
        tabLayout.addTab(tabLayout.newTab().setText("MCP"))
        tabLayout.addTab(tabLayout.newTab().setText("Local"))
        tabLayout.addTab(tabLayout.newTab().setText("Gestor de Modelos"))
        showSection(0)

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                showSection(tab.position)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
    }

    private fun showSection(index: Int) {
        binding.sectionEndpoints.visibility = if (index == 0) View.VISIBLE else View.GONE
        binding.sectionCategories.visibility = if (index == 1) View.VISIBLE else View.GONE
        binding.sectionTools.visibility = if (index == 2) View.VISIBLE else View.GONE
        binding.sectionPrompts.visibility = if (index == 3) View.VISIBLE else View.GONE
        binding.sectionAppearance.visibility = if (index == 4) View.VISIBLE else View.GONE
        binding.sectionMcp.visibility = if (index == 5) View.VISIBLE else View.GONE
        binding.sectionLocal.visibility = if (index == 6) View.VISIBLE else View.GONE
        binding.sectionModelManager.visibility = if (index == 7) View.VISIBLE else View.GONE
    }

    private fun setupThemeSettings() {
        val modeLabels = listOf("Sistema", "Claro", "Oscuro")
        val modeValues = listOf(
            ThemeManager.MODE_SYSTEM,
            ThemeManager.MODE_LIGHT,
            ThemeManager.MODE_DARK
        )
        val modeAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, modeLabels)
        binding.spThemeMode.setAdapter(modeAdapter)
        val currentModeIndex = modeValues.indexOf(settingsManager.themeMode).coerceAtLeast(0)
        binding.spThemeMode.setText(modeLabels[currentModeIndex], false)
        binding.spThemeMode.onItemClickListener =
            AdapterView.OnItemClickListener { _, _, position, _ ->
                settingsManager.themeMode = modeValues[position]
                applyThemeChange()
            }

        val styleLabels = listOf("Default", "Custom")
        val styleValues = listOf(ThemeManager.STYLE_DEFAULT, ThemeManager.STYLE_CUSTOM)
        val styleAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, styleLabels)
        binding.spThemeStyle.setAdapter(styleAdapter)
        val currentStyleIndex = styleValues.indexOf(settingsManager.themeStyle).coerceAtLeast(0)
        binding.spThemeStyle.setText(styleLabels[currentStyleIndex], false)
        binding.spThemeStyle.onItemClickListener =
            AdapterView.OnItemClickListener { _, _, position, _ ->
                settingsManager.themeStyle = styleValues[position]
                applyThemeChange()
            }
    }

    private fun applyThemeChange() {
        ThemeManager.apply(this)
        recreate()
    }

    private fun setupToolSettings() {
        val swToolsEnabled = binding.swToolsEnabled
        val swSms = binding.swToolSms
        val swCall = binding.swToolCall
        val swAlarm = binding.swToolAlarm
        val swOpenApp = binding.swToolOpenApp
        val swContacts = binding.swToolContacts
        val swLocation = binding.swToolLocation
        val swWeather = binding.swToolWeather
        val swNotifications = binding.swToolNotifications
        val swAskSms = binding.swToolAskSms
        val swAskCall = binding.swToolAskCall
        val swAskAlarm = binding.swToolAskAlarm
        val swAskOpenApp = binding.swToolAskOpenApp
        val swAskContacts = binding.swToolAskContacts
        val swAskLocation = binding.swToolAskLocation
        val swAskWeather = binding.swToolAskWeather
        val swAskNotifications = binding.swToolAskNotifications

        fun applyEnabledState(enabled: Boolean) {
            swSms.isEnabled = enabled
            swCall.isEnabled = enabled
            swAlarm.isEnabled = enabled
            swOpenApp.isEnabled = enabled
            swContacts.isEnabled = enabled
            swLocation.isEnabled = enabled
            swWeather.isEnabled = enabled
            swNotifications.isEnabled = enabled
            swAskSms.isEnabled = enabled
            swAskCall.isEnabled = enabled
            swAskAlarm.isEnabled = enabled
            swAskOpenApp.isEnabled = enabled
            swAskContacts.isEnabled = enabled
            swAskLocation.isEnabled = enabled
            swAskWeather.isEnabled = enabled
            swAskNotifications.isEnabled = enabled
        }

        swToolsEnabled.isChecked = settingsManager.toolsEnabled
        swSms.isChecked = settingsManager.toolAllowSms
        swCall.isChecked = settingsManager.toolAllowCall
        swAlarm.isChecked = settingsManager.toolAllowAlarm
        swOpenApp.isChecked = settingsManager.toolAllowOpenApp
        swContacts.isChecked = settingsManager.toolAllowContacts
        swLocation.isChecked = settingsManager.toolAllowLocation
        swWeather.isChecked = settingsManager.toolAllowWeather
        swNotifications.isChecked = settingsManager.toolAllowNotifications
        swAskSms.isChecked = settingsManager.toolAskSms
        swAskCall.isChecked = settingsManager.toolAskCall
        swAskAlarm.isChecked = settingsManager.toolAskAlarm
        swAskOpenApp.isChecked = settingsManager.toolAskOpenApp
        swAskContacts.isChecked = settingsManager.toolAskContacts
        swAskLocation.isChecked = settingsManager.toolAskLocation
        swAskWeather.isChecked = settingsManager.toolAskWeather
        swAskNotifications.isChecked = settingsManager.toolAskNotifications
        applyEnabledState(swToolsEnabled.isChecked)

        swToolsEnabled.setOnCheckedChangeListener { _, isChecked ->
            settingsManager.toolsEnabled = isChecked
            applyEnabledState(isChecked)
        }
        swSms.setOnCheckedChangeListener { _, isChecked -> settingsManager.toolAllowSms = isChecked }
        swCall.setOnCheckedChangeListener { _, isChecked -> settingsManager.toolAllowCall = isChecked }
        swAlarm.setOnCheckedChangeListener { _, isChecked -> settingsManager.toolAllowAlarm = isChecked }
        swOpenApp.setOnCheckedChangeListener { _, isChecked -> settingsManager.toolAllowOpenApp = isChecked }
        swContacts.setOnCheckedChangeListener { _, isChecked -> settingsManager.toolAllowContacts = isChecked }
        swLocation.setOnCheckedChangeListener { _, isChecked -> settingsManager.toolAllowLocation = isChecked }
        swWeather.setOnCheckedChangeListener { _, isChecked -> settingsManager.toolAllowWeather = isChecked }
        swNotifications.setOnCheckedChangeListener { _, isChecked -> settingsManager.toolAllowNotifications = isChecked }
        swAskSms.setOnCheckedChangeListener { _, isChecked -> settingsManager.toolAskSms = isChecked }
        swAskCall.setOnCheckedChangeListener { _, isChecked -> settingsManager.toolAskCall = isChecked }
        swAskAlarm.setOnCheckedChangeListener { _, isChecked -> settingsManager.toolAskAlarm = isChecked }
        swAskOpenApp.setOnCheckedChangeListener { _, isChecked -> settingsManager.toolAskOpenApp = isChecked }
        swAskContacts.setOnCheckedChangeListener { _, isChecked -> settingsManager.toolAskContacts = isChecked }
        swAskLocation.setOnCheckedChangeListener { _, isChecked -> settingsManager.toolAskLocation = isChecked }
        swAskWeather.setOnCheckedChangeListener { _, isChecked -> settingsManager.toolAskWeather = isChecked }
        swAskNotifications.setOnCheckedChangeListener { _, isChecked -> settingsManager.toolAskNotifications = isChecked }

        binding.etToolTimeout.setText(settingsManager.toolTimeoutMs.toString())
        binding.etToolTimeout.doAfterTextChanged { text ->
            val value = text?.toString()?.trim().orEmpty().toLongOrNull()
            if (value != null && value >= 1000L) {
                settingsManager.toolTimeoutMs = value
            }
        }

        binding.btnPermissions.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    private fun setupPromptSettings() {
        binding.etSystemPrompt.setText(settingsManager.agentSystemPrompt)
        binding.etUserPromptPrefix.setText(settingsManager.agentUserPromptPrefix)
        binding.swVoiceShortcut.isChecked = settingsManager.voiceShortcutEnabled
        binding.etVoiceShortcut.setText(settingsManager.voiceShortcutPhrase)
        binding.etVoiceShortcut.isEnabled = binding.swVoiceShortcut.isChecked

        binding.btnSavePrompts.setOnClickListener {
            settingsManager.agentSystemPrompt = binding.etSystemPrompt.text?.toString().orEmpty()
            settingsManager.agentUserPromptPrefix = binding.etUserPromptPrefix.text?.toString().orEmpty()
            Toast.makeText(this, "Prompts guardados", Toast.LENGTH_SHORT).show()
        }

        binding.swVoiceShortcut.setOnCheckedChangeListener { _, isChecked ->
            settingsManager.voiceShortcutEnabled = isChecked
            binding.etVoiceShortcut.isEnabled = isChecked
        }
        binding.etVoiceShortcut.doAfterTextChanged { text ->
            settingsManager.voiceShortcutPhrase = text?.toString().orEmpty().trim()
        }
    }

    private fun setupMcpSettings() {
        binding.btnAddMcp.setOnClickListener { showAddMcpDialog() }
        binding.swMcpEnabled.isChecked = settingsManager.mcpEnabled
        binding.swMcpEnabled.setOnCheckedChangeListener { _, isChecked ->
            settingsManager.mcpEnabled = isChecked
        }
        renderMcpList()
    }

    private fun setupLocalSettings() {
        binding.btnOpenModelManager.setOnClickListener {
            openModelManagerTab()
        }
        binding.btnTestGeminiNano.setOnClickListener {
            runGeminiNanoTest()
        }
        binding.btnClearModelStorage.setOnClickListener {
            confirmClearModelStorage()
        }
        updateMemoryInfo()
        updateStorageInfo()
        renderAicoreStatus()
        renderAicoreModels()
    }

    private fun setupModelManager() {
        binding.etHfApiKey.setText(settingsManager.hfApiKey)
        binding.btnSaveHfKey.setOnClickListener {
            settingsManager.hfApiKey = binding.etHfApiKey.text?.toString().orEmpty().trim()
            Toast.makeText(this, "API key guardada", Toast.LENGTH_SHORT).show()
        }
        binding.btnAddHfRepo.setOnClickListener {
            val repo = binding.etHfRepo.text?.toString().orEmpty().trim()
            if (repo.isNotBlank()) {
                addHfRepo(repo)
                binding.etHfRepo.setText("")
            }
        }
        binding.btnScanHfRepos.setOnClickListener {
            scanHfRepos()
        }
        ensureDefaultRepos()
        renderHfRepoList()
    }

    private fun openModelManagerTab() {
        binding.tabLayout.getTabAt(7)?.select()
    }

    private fun renderAicoreStatus() {
        val (available, message) = getAicoreStatus()
        val status = if (available) "Disponible" else "No disponible"
        binding.tvAicoreStatus.text = "Gemini Nano (AICore): $status - $message"
    }

    private fun getAicoreStatus(): Pair<Boolean, String> {
        return try {
            val info = packageManager.getPackageInfo("com.google.android.aicore", 0)
            val appInfo = packageManager.getApplicationInfo("com.google.android.aicore", 0)
            val enabled = appInfo.enabled
            if (enabled) {
                true to "v${info.versionName ?: "desconocida"}"
            } else {
                false to "Instalado pero deshabilitado"
            }
        } catch (e: Exception) {
            false to "No instalado"
        }
    }

    private fun renderAicoreModels() {
        val container = binding.aicoreModelsContainer
        container.removeAllViews()
        val (aicoreAvailable, _) = getAicoreStatus()
        val sttAssigned = settingsManager.getCategoryConfig(Category.STT).primary?.endpointId == "local"
        val agentAssigned = settingsManager.getCategoryConfig(Category.AGENT).primary?.endpointId == "local"
        val assignedSttModel = settingsManager.localSttModel
        val assignedAgentModel = settingsManager.localAgentModel
        fun addSection(
            title: String,
            models: List<ModelDownloadManager.ModelInfo>,
            requiresAicore: Boolean,
            statusMessage: String? = null,
            expanded: Boolean = true
        ) {
            val sectionTitle = TextView(this).apply {
                text = title
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge)
                setPadding(0, 16, 0, 8)
            }
            container.addView(sectionTitle)

            if (!statusMessage.isNullOrBlank()) {
                val statusLabel = TextView(this).apply {
                    text = statusMessage
                    alpha = 0.75f
                    setPadding(0, 0, 0, 8)
                }
                container.addView(statusLabel)
            }

            if (!expanded) {
                val spacer = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        12
                    )
                }
                container.addView(spacer)
                return
            }

            if (models.isEmpty()) {
                val emptyLabel = TextView(this).apply {
                    text = "Sin modelos disponibles"
                    alpha = 0.7f
                }
                container.addView(emptyLabel)
                val spacer = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        12
                    )
                }
                container.addView(spacer)
                return
            }

            models.forEach { model ->
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(12)
                }

                val titleRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                }

                val titleView = TextView(this).apply {
                    text = model.name
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
                }

                val gatedDot = TextView(this).apply {
                    visibility = if (model.gated) View.VISIBLE else View.GONE
                    text = "●"
                    setTextColor(
                        androidx.core.content.ContextCompat.getColor(
                            this@MainActivity,
                            R.color.gated_indicator
                        )
                    )
                    setPadding(8, 0, 0, 0)
                }

                titleRow.addView(titleView)
                titleRow.addView(gatedDot)

                val installedFile = modelDownloadManager.getModelFile(model.filename)
                val sizeBytes = installedFile?.length()?.takeIf { it > 0 } ?: model.sizeBytes
                val sizeLabel = if (sizeBytes > 0) formatBytes(sizeBytes) else "?"
                val detail = TextView(this).apply {
                    text = "${model.type.uppercase()} - $sizeLabel\n${model.description}"
                    alpha = 0.75f
                }

                val gatedLabel = TextView(this).apply {
                    visibility = if (model.gated) View.VISIBLE else View.GONE
                    text = "Requiere aceptar terminos"
                    alpha = 0.8f
                }

                val installed = modelDownloadManager.isModelInstalled(model)
                val statusText = aicoreDownloadStatus[model.filename]
                val statusLabel = TextView(this).apply {
                    val base = if (installed) "Instalado" else "No instalado"
                    val aicoreHint = if (requiresAicore && !aicoreAvailable) {
                        " (requiere AICore)"
                    } else {
                        ""
                    }
                    val memoryInfo = localRuntime.getLoadedModelInfo(model.filename)
                    val memoryLabel = if (memoryInfo != null) {
                        val mb = memoryInfo.sizeBytes / (1024 * 1024)
                        "En memoria: ${mb}MB"
                    } else {
                        "No cargado en memoria"
                    }
                    val testLabel = testStatus[model.filename]
                    val lines = mutableListOf<String>()
                    lines.add(if (!statusText.isNullOrBlank()) "$base$aicoreHint - $statusText" else "$base$aicoreHint")
                    lines.add(memoryLabel)
                    if (!testLabel.isNullOrBlank()) {
                        lines.add(testLabel)
                    }
                    text = lines.joinToString("\n")
                    alpha = 0.8f
                }

                val actionRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                }

                val loadButton = MaterialButton(this).apply {
                    text = if (localRuntime.isLoaded(model.filename)) "Unload" else "Load"
                    isEnabled = installed
                    if (!installed) {
                        alpha = 0.6f
                    }
                    setOnClickListener {
                        if (localRuntime.isLoaded(model.filename)) {
                            unloadLocalModel(model)
                        } else {
                            loadLocalModel(model)
                        }
                    }
                }
                actionRow.addView(loadButton)
                val canAssignStt = model.category == "STT"
                val canAssignLlm = model.category == "LLM-Text" || model.category == "Function-Calling"
                if (canAssignStt || canAssignLlm) {
                    val testButton = MaterialButton(this).apply {
                        text = "Test"
                        isEnabled = installed
                        if (canAssignStt) {
                            setOnClickListener { startWhisperTest(model) }
                        } else if (canAssignLlm) {
                            setOnClickListener { runLlmTest(model) }
                        }
                        if (!installed) {
                            alpha = 0.6f
                        }
                    }
                    actionRow.addView(testButton)
                }

                val assignmentLabel = TextView(this).apply {
                    val assigned = when {
                        canAssignStt && sttAssigned && assignedSttModel == model.filename -> "Asignado STT"
                        canAssignLlm && agentAssigned && assignedAgentModel == model.filename -> "Asignado LLM"
                        else -> "No asignado"
                    }
                    text = assigned
                    alpha = 0.7f
                }

                card.addView(titleRow)
                card.addView(detail)
                card.addView(gatedLabel)
                card.addView(statusLabel)
                card.addView(actionRow)
                card.addView(assignmentLabel)
                container.addView(card)
            }

            val spacer = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    12
                )
            }
            container.addView(spacer)
        }

        val allModels = modelDownloadManager.getAvailableModels(settingsManager)
        val installedOnly = modelDownloadManager.getInstalledModels(allModels)
        if (installedOnly.isEmpty()) {
            val emptyLabel = TextView(this).apply {
                text = "No hay modelos descargados. Ve al Gestor de Modelos."
                alpha = 0.75f
            }
            container.addView(emptyLabel)
            return
        }
        val tfliteModels = installedOnly.filter { it.type == "tflite" }
        val litertModels = installedOnly.filter { it.type == "litertlm" }
        val taskModels = installedOnly.filter { it.type == "task" }

        val geminiStatus = geminiNanoService?.getStatusSummary().orEmpty()
        val aicoreStatusLine = if (geminiStatus.isNotBlank()) {
            "Gemini Nano: $geminiStatus"
        } else {
            "Gemini Nano: estado desconocido"
        }
        val aicoreExpanded = true
        addSection(
            title = "AICore / Gemini Nano (LiteRT LM)",
            models = litertModels,
            requiresAicore = true,
            statusMessage = aicoreStatusLine,
            expanded = aicoreExpanded
        )
        addSection("TFLite (.tflite)", tfliteModels, requiresAicore = false)
        addSection("MediaPipe (.task)", taskModels, requiresAicore = false)
    }

    private fun renderHfRepoList() {
        val container = binding.hfRepoContainer
        container.removeAllViews()
        val repos = settingsManager.getHfRepoList()
        if (repos.isEmpty()) {
            val emptyLabel = TextView(this).apply {
                text = "Sin repos configurados"
                alpha = 0.7f
            }
            container.addView(emptyLabel)
            updateHfScanStatus()
            return
        }

        val details = settingsManager.getHfRepoScanDetails()
        val accepted = settingsManager.getHfAcceptedRepos()
        val (aicoreAvailable, _) = getAicoreStatus()
        repos.forEach { repo ->
            val repoDetails = details.firstOrNull { it.repoId == repo }
            val expanded = hfRepoExpanded[repo] ?: false
            val card = com.google.android.material.card.MaterialCardView(this).apply {
                radius = 20f
                setContentPadding(16, 16, 16, 16)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, 16)
                }
            }
            val cardContent = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            val headerRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            val titleColumn = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val title = TextView(this).apply {
                text = repo
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge)
            }
            val models = repoDetails?.models.orEmpty()
            val gated = repoDetails?.gated == true
            val compatibleCount = models.count { it.compatible }
            val downloadedCount = models.count { modelDownloadManager.getModelFile(it.filename) != null }
            val isRepoAccepted = accepted.contains(repo)
            val installableCount = models.count { model ->
                val requiresAicore = model.type == "litertlm"
                model.compatible &&
                    (!gated || isRepoAccepted) &&
                    (!requiresAicore || aicoreAvailable)
            }
            val summary = TextView(this).apply {
                text = "Descargados: $downloadedCount/$compatibleCount · Instalables: $installableCount"
                alpha = 0.7f
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            }
            titleColumn.addView(title)
            titleColumn.addView(summary)
            val toggle = TextView(this).apply {
                text = if (expanded) "v" else ">"
                alpha = 0.6f
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge)
                setPadding(12, 0, 12, 0)
            }
            val remove = MaterialButton(
                this,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = "Quitar"
                setOnClickListener { removeHfRepo(repo) }
            }
            headerRow.addView(titleColumn)
            headerRow.addView(toggle)
            headerRow.addView(remove)
            headerRow.setOnClickListener {
                hfRepoExpanded[repo] = !(hfRepoExpanded[repo] ?: true)
                renderHfRepoList()
            }
            toggle.setOnClickListener {
                hfRepoExpanded[repo] = !(hfRepoExpanded[repo] ?: true)
                renderHfRepoList()
            }

            val gatedLabel = TextView(this).apply {
                val status = if (gated) {
                    if (accepted.contains(repo)) "Gated: aceptado" else "Gated: pendiente"
                } else {
                    "Gated: no"
                }
                text = status
                alpha = 0.85f
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            }

            val actionRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            if (gated) {
                val termsButton = MaterialButton(this).apply {
                    text = "Aceptar términos"
                    setOnClickListener {
                        settingsManager.setHfAcceptedRepos(accepted + repo)
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://huggingface.co/$repo"))
                        startActivity(intent)
                        renderHfRepoList()
                    }
                }
                actionRow.addView(termsButton)
            }

            cardContent.addView(headerRow)
            cardContent.addView(gatedLabel)
            cardContent.addView(actionRow)

            val modelList = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = if (expanded) View.VISIBLE else View.GONE
            }
            if (models.isEmpty()) {
                val empty = TextView(this).apply {
                    text = "Sin modelos compatibles detectados"
                    alpha = 0.7f
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                }
                modelList.addView(empty)
            } else {
                models.forEachIndexed { index, model ->
                    val modelRow = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(12)
                    }
                    val name = TextView(this).apply {
                        text = model.name
                        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
                    }
                    val installedFile = modelDownloadManager.getModelFile(model.filename)
                    val sizeBytes = installedFile?.length()?.takeIf { it > 0 } ?: model.sizeBytes
                    val sizeLabel = if (sizeBytes > 0) formatBytes(sizeBytes) else "?"
                    val detail = TextView(this).apply {
                        val compat = if (model.compatible) "Compatible" else "Incompleto"
                        text = "${model.type.uppercase()} · $sizeLabel · $compat"
                        alpha = 0.75f
                        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                    }
                    if (!model.compatible && model.missingReason != null) {
                        val missing = TextView(this).apply {
                            text = model.missingReason
                            alpha = 0.7f
                            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                        }
                        modelRow.addView(missing)
                    }
                    val requiresAicore = model.type == "litertlm"
                    if (requiresAicore && !aicoreAvailable) {
                        val aicoreNote = TextView(this).apply {
                            text = "Requiere AICore"
                            alpha = 0.7f
                            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                        }
                        modelRow.addView(aicoreNote)
                    }

                    val modelActions = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                    }
                    val installed = modelDownloadManager.getModelFile(model.filename) != null
                    val downloadStatus = aicoreDownloadStatus[model.filename]
                    if (!downloadStatus.isNullOrBlank()) {
                        val statusLabel = TextView(this).apply {
                            text = downloadStatus
                            alpha = 0.7f
                            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                        }
                        modelRow.addView(statusLabel)
                    }
                    val button = MaterialButton(this).apply {
                        text = if (installed) "Quitar" else "Descargar"
                        val gatedAllowed = !gated || accepted.contains(repo)
                        val aicoreAllowed = !requiresAicore || aicoreAvailable
                        isEnabled = model.compatible &&
                            gatedAllowed &&
                            aicoreAllowed &&
                            !aicoreDownloadInProgress.contains(model.filename)
                        if (!model.compatible || !gatedAllowed || !aicoreAllowed) {
                            alpha = 0.6f
                        }
                        setOnClickListener {
                            if (installed) {
                                deleteLocalModel(
                                    ModelDownloadManager.ModelInfo(
                                        name = model.name,
                                        url = model.url,
                                        filename = model.filename,
                                        sizeBytes = model.sizeBytes,
                                        type = model.type,
                                        category = model.category,
                                        description = model.description,
                                        gated = gated,
                                        termsUrl = "https://huggingface.co/$repo"
                                    )
                                )
                            } else {
                                startAicoreModelDownload(
                                    ModelDownloadManager.ModelInfo(
                                        name = model.name,
                                        url = model.url,
                                        filename = model.filename,
                                        sizeBytes = model.sizeBytes,
                                        type = model.type,
                                        category = model.category,
                                        description = model.description,
                                        gated = gated,
                                        termsUrl = "https://huggingface.co/$repo"
                                    )
                                )
                            }
                        }
                    }
                    modelActions.addView(button)

                    modelRow.addView(name)
                    modelRow.addView(detail)
                    modelRow.addView(modelActions)
                    if (index > 0) {
                        val divider = View(this).apply {
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                12
                            )
                        }
                        modelList.addView(divider)
                    }
                    modelList.addView(modelRow)
                }
            }

            cardContent.addView(modelList)
            card.addView(cardContent)
            container.addView(card)
        }
        updateHfScanStatus()
    }

    private fun addHfRepo(repo: String) {
        val normalized = repo.trim()
        if (normalized.isBlank()) return
        val repos = settingsManager.getHfRepoList().toMutableList()
        if (!repos.contains(normalized)) {
            repos.add(normalized)
            settingsManager.setHfRepoList(repos)
            renderHfRepoList()
        }
    }

    private fun removeHfRepo(repo: String) {
        val repos = settingsManager.getHfRepoList().toMutableList()
        repos.remove(repo)
        settingsManager.setHfRepoList(repos)
        renderHfRepoList()
    }

    private fun updateHfScanStatus() {
        val count = settingsManager.getHfScannedModels().size
        binding.tvHfScanStatus.text = "Modelos compatibles: $count"
    }

    private fun scanHfRepos(autoSelect: Boolean = true) {
        val repos = settingsManager.getHfRepoList()
        if (repos.isEmpty()) {
            Toast.makeText(this, "Agrega al menos un repo", Toast.LENGTH_SHORT).show()
            return
        }
        binding.tvHfScanStatus.text = "Escaneando..."
        lifecycleScope.launch {
            val (details, errors) = modelDownloadManager.scanHfReposDetailed(repos, settingsManager.hfApiKey)
            settingsManager.setHfRepoScanDetails(details)
            val compatibleModels = details.flatMap { repo ->
                repo.models.filter { it.compatible }.map { model ->
                    ModelDownloadManager.ModelInfo(
                        name = model.name,
                        url = model.url,
                        filename = model.filename,
                        sizeBytes = model.sizeBytes,
                        type = model.type,
                        category = model.category,
                        description = model.description,
                        gated = repo.gated,
                        termsUrl = "https://huggingface.co/${repo.repoId}"
                    )
                }
            }
            settingsManager.setHfScannedModels(compatibleModels)
            val errorCount = errors.size
            val message = if (errorCount > 0) {
                "Listo con $errorCount errores"
            } else {
                "Escaneo completado"
            }
            binding.tvHfScanStatus.text = "$message. Modelos: ${compatibleModels.size}"
            renderAicoreModels()
            renderHfRepoList()
        }
    }

    private fun ensureDefaultRepos() {
        val repos = settingsManager.getHfRepoList()
        if (repos.isNotEmpty()) return
        val defaults = listOf(
            "DocWolle/whisper_tflite_models",
            "litert-community/gemma-3-270m-it",
            "litert-community/Qwen2.5-1.5B-Instruct",
            "litert-community/Phi-4-mini-instruct",
            "litert-community/Gemma2-2B-IT",
            "litert-community/DeepSeek-R1-Distill-Qwen-1.5B",
            "litert-community/SmolVLM-256M-Instruct",
            "google/gemma-3n-E2B-it-litert-lm",
            "google/gemma-3n-E4B-it-litert-lm"
        )
        settingsManager.setHfRepoList(defaults)
        scanHfRepos(autoSelect = true)
    }


    private fun startWhisperTest(model: ModelDownloadManager.ModelInfo) {
        if (testInProgress) return
        if (!permissionController.hasPermission(Manifest.permission.RECORD_AUDIO)) {
            permissionController.requestPermission(Manifest.permission.RECORD_AUDIO) { granted ->
                if (granted) {
                    startWhisperTest(model)
                } else {
                    Toast.makeText(this, "Permiso de microfono requerido", Toast.LENGTH_SHORT).show()
                }
            }
            return
        }
        testInProgress = true
        testStatus[model.filename] = "Cargando modelo..."
        renderAicoreModels()
        Toast.makeText(this, "Cargando modelo Whisper...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            var file: java.io.File? = null
            try {
                val ready = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    localWhisperService.prepareModel(model.filename)
                }
                if (!ready) {
                    Toast.makeText(this@MainActivity, "No se pudo cargar el modelo Whisper", Toast.LENGTH_SHORT).show()
                    testStatus[model.filename] = "Test fallo al cargar"
                    renderAicoreModels()
                    return@launch
                }
                testStatus[model.filename] = "Grabando audio..."
                renderAicoreModels()
                file = testRecorder.startRecording(usePcm = true)
                if (file == null) {
                    Toast.makeText(this@MainActivity, "No se pudo iniciar grabacion", Toast.LENGTH_SHORT).show()
                    testStatus[model.filename] = "Test fallo al grabar"
                    renderAicoreModels()
                    return@launch
                }
                testRecordingFile = file
                Toast.makeText(this@MainActivity, "Escuchando (5s)...", Toast.LENGTH_SHORT).show()
                delay(5000)
                testRecorder.stopRecording()
                testRecordingFile = null
                testStatus[model.filename] = "Transcribiendo..."
                renderAicoreModels()
                val recordingFile = file ?: return@launch
                val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    localWhisperService.transcribeWithModel(model.filename, recordingFile)
                }
                testRecorder.deleteFile(recordingFile)
                file = null
                val text = result ?: "Sin respuesta. Motor Whisper local no implementado o sin memoria."
                testStatus[model.filename] = "Test completo"
                renderAicoreModels()
                showTestResult("Test STT - ${model.name}", text)
            } finally {
                if (file != null) {
                    testRecorder.stopRecording()
                    testRecorder.deleteFile(file)
                    testRecordingFile = null
                }
                testInProgress = false
            }
        }
    }

    private fun runLlmTest(model: ModelDownloadManager.ModelInfo) {
        lifecycleScope.launch {
            testStatus[model.filename] = "Test en progreso..."
            renderAicoreModels()
            val file = modelDownloadManager.getModelFile(model.filename)
            if (file == null) {
                testStatus[model.filename] = "Test fallo: no instalado"
                renderAicoreModels()
                showTestResult("Test LLM - ${model.name}", "Modelo no instalado.")
                return@launch
            }

            val ensureError = ensureModelLoadedForTest(model, file)
            if (ensureError != null) {
                testStatus[model.filename] = "Test fallo"
                renderAicoreModels()
                showTestResult("Test LLM - ${model.name}", ensureError)
                return@launch
            }

            when (model.type) {
                "tflite" -> {
                    if (model.category == "LLM-Multimodal") {
                        testStatus[model.filename] = "Test no disponible"
                        renderAicoreModels()
                        showTestResult(
                            "Test LLM - ${model.name}",
                            "Modelo multimodal: el test local solo soporta texto."
                        )
                        return@launch
                    }
                    val result = localLlmService.generateContent("ping")
                    testStatus[model.filename] = if (result.isSuccess) "Test completo" else "Test fallo"
                    renderAicoreModels()
                    showTestResult(
                        "Test LLM - ${model.name}",
                        result.getOrNull() ?: "Sin respuesta."
                    )
                }
                "task" -> {
                    val result = mediaPipeLlmService.generateContent("ping")
                    testStatus[model.filename] = if (result.isSuccess) "Test completo" else "Test fallo"
                    renderAicoreModels()
                    showTestResult(
                        "Test MediaPipe - ${model.name}",
                        result.getOrNull() ?: "Sin respuesta."
                    )
                }
                "litertlm" -> {
                    val gemini = geminiNanoService
                    if (gemini == null) {
                        testStatus[model.filename] = "Test no disponible"
                        renderAicoreModels()
                        showTestResult("Test LiteRT LM", "AICore no inicializado.")
                        return@launch
                    }
                    val status = gemini.initialize()
                    if (status == GeminiNanoService.Status.Available) {
                        val result = gemini.generateContent("ping")
                        testStatus[model.filename] = if (result.isSuccess) "Test completo" else "Test fallo"
                        renderAicoreModels()
                        showTestResult(
                            "Test AICore",
                            result.getOrNull() ?: "Sin respuesta."
                        )
                    } else {
                        testStatus[model.filename] = "Test no disponible"
                        renderAicoreModels()
                        showTestResult("Test AICore", "AICore no disponible para este dispositivo.")
                    }
                }
                else -> {
                    testStatus[model.filename] = "Test no disponible"
                    renderAicoreModels()
                    showTestResult("Test LLM - ${model.name}", "Modelo local no soportado.")
                }
            }
        }
    }

    private suspend fun ensureModelLoadedForTest(
        model: ModelDownloadManager.ModelInfo,
        file: java.io.File
    ): String? {
        return when (model.type) {
            "tflite" -> {
                if (model.category == "LLM-Multimodal") {
                    "Modelo multimodal: el test local solo soporta texto."
                } else if (localLlmService.isAvailable() && localLlmService.getModelFilename() == model.filename) {
                    null
                } else {
                    var status = localLlmService.initializeWithModel(model, settingsManager.hfApiKey)
                    if (status is com.sbf.assistant.llm.LocalLlmService.Status.Error && status.isOutOfMemory) {
                        val evicted = localRuntime.evictLeastRecentlyUsed()
                        if (evicted == localLlmService.getModelFilename()) {
                            localLlmService.release()
                        } else if (evicted == mediaPipeLlmService.getModelFilename()) {
                            mediaPipeLlmService.release()
                        }
                        status = localLlmService.initializeWithModel(model, settingsManager.hfApiKey)
                    }
                    if (status is com.sbf.assistant.llm.LocalLlmService.Status.Available) {
                        localRuntime.registerLoaded(model.filename, file.length())
                        null
                    } else {
                        (status as? com.sbf.assistant.llm.LocalLlmService.Status.Error)?.message
                            ?: "No se pudo inicializar el modelo."
                    }
                }
            }
            "task" -> {
                if (mediaPipeLlmService.isAvailable() && mediaPipeLlmService.getModelFilename() == model.filename) {
                    null
                } else {
                    var status = mediaPipeLlmService.initializeWithModel(model)
                    if (status is com.sbf.assistant.llm.MediaPipeLlmService.Status.Error && status.isOutOfMemory) {
                        val evicted = localRuntime.evictLeastRecentlyUsed()
                        if (evicted == mediaPipeLlmService.getModelFilename()) {
                            mediaPipeLlmService.release()
                        } else if (evicted == localLlmService.getModelFilename()) {
                            localLlmService.release()
                        }
                        status = mediaPipeLlmService.initializeWithModel(model)
                    }
                    if (status is com.sbf.assistant.llm.MediaPipeLlmService.Status.Available) {
                        localRuntime.registerLoaded(model.filename, file.length())
                        null
                    } else {
                        (status as? com.sbf.assistant.llm.MediaPipeLlmService.Status.Error)?.message
                            ?: "No se pudo inicializar el modelo."
                    }
                }
            }
            "litertlm" -> {
                val gemini = geminiNanoService
                if (gemini == null) {
                    "AICore no inicializado."
                } else {
                    val status = gemini.initialize()
                    if (status == GeminiNanoService.Status.Available) {
                        localRuntime.registerLoaded(model.filename, file.length())
                        null
                    } else {
                        "AICore no disponible para este dispositivo."
                    }
                }
            }
            else -> "Modelo local no soportado."
        }
    }

    private fun runGeminiNanoTest() {
        lifecycleScope.launch {
            val gemini = geminiNanoService ?: GeminiNanoService(this@MainActivity).also {
                geminiNanoService = it
            }
            val status = gemini.initialize()
            if (status != GeminiNanoService.Status.Available) {
                val message = when (status) {
                    is GeminiNanoService.Status.Unavailable -> "Gemini Nano no esta disponible en este dispositivo."
                    is GeminiNanoService.Status.Downloading -> "Gemini Nano se esta descargando."
                    is GeminiNanoService.Status.DownloadProgress ->
                        "Descargando Gemini Nano: ${status.bytesDownloaded} bytes"
                    is GeminiNanoService.Status.Error -> "Error: ${status.message}"
                    else -> "Gemini Nano no disponible."
                }
                showTestResult("Test Gemini Nano", message)
                return@launch
            }
            val result = gemini.generateContent("Di 'Hola, estoy funcionando correctamente' en español.")
            result.fold(
                onSuccess = { text ->
                    showTestResult("Test Gemini Nano", "✓ Respuesta:\n$text")
                },
                onFailure = { e ->
                    showTestResult("Test Gemini Nano", "✗ Error: ${e.message}")
                }
            )
        }
    }

    private fun loadLocalModel(model: ModelDownloadManager.ModelInfo) {
        val file = modelDownloadManager.getModelFile(model.filename)
        if (file == null) {
            Toast.makeText(this, "Modelo no instalado.", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val statusMessage = when (model.type) {
                "tflite" -> {
                    if (model.category == "STT") {
                        val ready = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            localWhisperService.prepareModel(model.filename)
                        }
                        if (ready) {
                            localRuntime.registerLoaded(model.filename, file.length())
                            "Cargado"
                        } else {
                            "No se pudo cargar"
                        }
                    } else {
                    var llmStatus = localLlmService.initializeWithModel(model, settingsManager.hfApiKey)
                        if (llmStatus is com.sbf.assistant.llm.LocalLlmService.Status.Error && llmStatus.isOutOfMemory) {
                            val evicted = localRuntime.evictLeastRecentlyUsed()
                            if (evicted == localLlmService.getModelFilename()) {
                                localLlmService.release()
                            } else if (evicted == mediaPipeLlmService.getModelFilename()) {
                                mediaPipeLlmService.release()
                            }
                            llmStatus = localLlmService.initializeWithModel(model, settingsManager.hfApiKey)
                        }
                        if (llmStatus is com.sbf.assistant.llm.LocalLlmService.Status.Available) {
                            localRuntime.registerLoaded(model.filename, file.length())
                            "Cargado"
                        } else {
                            (llmStatus as? com.sbf.assistant.llm.LocalLlmService.Status.Error)?.message
                                ?: "Error al cargar"
                        }
                    }
                }
                "task" -> {
                        var mpStatus = mediaPipeLlmService.initializeWithModel(model)
                    if (mpStatus is com.sbf.assistant.llm.MediaPipeLlmService.Status.Error && mpStatus.isOutOfMemory) {
                        val evicted = localRuntime.evictLeastRecentlyUsed()
                        if (evicted == mediaPipeLlmService.getModelFilename()) {
                            mediaPipeLlmService.release()
                        } else if (evicted == localLlmService.getModelFilename()) {
                            localLlmService.release()
                        }
                        mpStatus = mediaPipeLlmService.initializeWithModel(model)
                    }
                    if (mpStatus is com.sbf.assistant.llm.MediaPipeLlmService.Status.Available) {
                        localRuntime.registerLoaded(model.filename, file.length())
                        "Cargado"
                    } else {
                        (mpStatus as? com.sbf.assistant.llm.MediaPipeLlmService.Status.Error)?.message
                            ?: "Error al cargar"
                    }
                }
                "litertlm" -> {
                    val gemini = geminiNanoService ?: GeminiNanoService(this@MainActivity).also {
                        geminiNanoService = it
                    }
                    val status = gemini.initialize()
                    if (status == GeminiNanoService.Status.Available) {
                        localRuntime.registerLoaded(model.filename, file.length())
                        "AICore listo"
                    } else {
                        "AICore no disponible"
                    }
                }
                else -> "Tipo no soportado"
            }
            renderAicoreModels()
            updateMemoryInfo()
            Toast.makeText(this@MainActivity, statusMessage, Toast.LENGTH_SHORT).show()
        }
    }

    private fun unloadLocalModel(model: ModelDownloadManager.ModelInfo) {
        if (model.type == "tflite" && model.category == "STT") {
            localWhisperService.releaseModel(model.filename)
        }
        if (model.type == "tflite" && localLlmService.getModelFilename() == model.filename) {
            localLlmService.release()
        }
        if (model.type == "task" && mediaPipeLlmService.getModelFilename() == model.filename) {
            mediaPipeLlmService.release()
        }
        localRuntime.removeLoaded(model.filename)
        renderAicoreModels()
        updateMemoryInfo()
    }

    private fun confirmDeleteModel(model: ModelDownloadManager.ModelInfo) {
        AlertDialog.Builder(this)
            .setTitle("Borrar modelo")
            .setMessage("Eliminar ${model.name}?")
            .setPositiveButton("Borrar") { _, _ ->
                deleteLocalModel(model)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun deleteLocalModel(model: ModelDownloadManager.ModelInfo) {
        unloadLocalModel(model)
        val deleted = modelDownloadManager.deleteModel(model)
        if (deleted) {
            aicoreDownloadStatus.remove(model.filename)
            testStatus.remove(model.filename)
            Toast.makeText(this, "Modelo borrado", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "No se pudo borrar", Toast.LENGTH_SHORT).show()
        }
        updateStorageInfo()
        renderAicoreModels()
        renderHfRepoList()
    }

    private fun confirmClearModelStorage() {
        AlertDialog.Builder(this)
            .setTitle("Limpiar almacenamiento")
            .setMessage("Se eliminaran todos los modelos descargados. Continuar?")
            .setPositiveButton("Limpiar") { _, _ ->
                clearModelStorage()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun clearModelStorage() {
        val allModels = modelDownloadManager.getAvailableModels(settingsManager)
        var deletedAny = false
        allModels.forEach { model ->
            if (modelDownloadManager.deleteModel(model)) {
                deletedAny = true
            }
        }
        if (deletedAny) {
            aicoreDownloadStatus.clear()
            testStatus.clear()
            Toast.makeText(this, "Modelos eliminados", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "No habia modelos para borrar", Toast.LENGTH_SHORT).show()
        }
        updateStorageInfo()
        renderAicoreModels()
    }

    private fun showTestResult(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Cerrar", null)
            .show()
    }

    private fun getLocalModelOptions(category: Category): List<Pair<String, String>> {
        val installed = modelDownloadManager.getInstalledModels(
            modelDownloadManager.getAvailableModels(settingsManager)
        )
        val filtered = when (category) {
            Category.STT -> installed.filter { it.category == "STT" }
            Category.AGENT -> installed.filter {
                it.category == "LLM-Text" || it.category == "Function-Calling"
            }
            else -> emptyList()
        }
        return filtered.map { it.name to it.filename }
    }

    private fun startAicoreModelDownload(model: ModelDownloadManager.ModelInfo) {
        if (aicoreDownloadInProgress.contains(model.filename)) return
        aicoreDownloadInProgress.add(model.filename)
        aicoreDownloadStatus[model.filename] = "Iniciando..."
        renderAicoreModels()

        lifecycleScope.launch {
            val result = modelDownloadManager.downloadModel(model, settingsManager.hfApiKey) { percent ->
                runOnUiThread {
                    aicoreDownloadStatus[model.filename] =
                        if (percent >= 0) "Descargando ${percent}%" else "Descargando..."
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastAicoreRenderMs > 500L) {
                        lastAicoreRenderMs = now
                        renderAicoreModels()
                        renderHfRepoList()
                    }
                }
            }
            runOnUiThread {
                aicoreDownloadInProgress.remove(model.filename)
                if (result.isSuccess) {
                    aicoreDownloadStatus.remove(model.filename)
                    Toast.makeText(this@MainActivity, "Descarga completa", Toast.LENGTH_SHORT).show()
                } else {
                    aicoreDownloadStatus[model.filename] =
                        "Error: ${result.exceptionOrNull()?.message ?: "desconocido"}"
                    Toast.makeText(this@MainActivity, "Fallo la descarga", Toast.LENGTH_SHORT).show()
                }
                updateStorageInfo()
                renderAicoreModels()
                renderHfRepoList()
            }
        }
    }

    private fun updateMemoryInfo() {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val info = android.app.ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        val totalMb = info.totalMem / (1024 * 1024)
        val availMb = info.availMem / (1024 * 1024)
        binding.tvMemoryInfo.text = "Memoria: ${availMb}MB libres de ${totalMb}MB"
    }

    private fun updateStorageInfo() {
        val free = modelDownloadManager.getAvailableSpace()
        val used = modelDownloadManager.getTotalUsedSpace()
        binding.tvStorageInfo.text = "Almacenamiento: ${formatBytes(free)} libres, ${formatBytes(used)} en modelos"
    }

    private fun formatBytes(bytes: Long): String {
        val kb = 1024.0
        val mb = kb * 1024.0
        val gb = mb * 1024.0
        return when {
            bytes >= gb -> String.format(Locale.US, "%.1fGB", bytes / gb)
            bytes >= mb -> String.format(Locale.US, "%.1fMB", bytes / mb)
            bytes >= kb -> String.format(Locale.US, "%.1fKB", bytes / kb)
            else -> "${bytes}B"
        }
    }

    private fun renderMcpList() {
        val container = binding.mcpContainer
        container.removeAllViews()

        val servers = settingsManager.getMcpServers()
        servers.forEach { server ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(8)
            }
            val label = TextView(this).apply {
                val suffix = when (server.type) {
                    "local_filesystem" -> "local"
                    "local_calendar" -> "local (stub)"
                    "local_notes" -> "local"
                    else -> "remote"
                }
                text = if (server.baseUrl.isBlank()) "${server.name} ($suffix)"
                       else "${server.name} (${server.baseUrl})"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val enableToggle = MaterialSwitch(this).apply {
                isChecked = server.enabled
                text = "Habilitar"
                setOnCheckedChangeListener { _, isChecked ->
                    val updated = servers.map {
                        if (it.id == server.id) it.copy(enabled = isChecked) else it
                    }
                    settingsManager.saveMcpServers(updated)
                }
                thumbTintList = androidx.core.content.ContextCompat.getColorStateList(
                    context,
                    R.color.switch_enabled_thumb
                )
                trackTintList = androidx.core.content.ContextCompat.getColorStateList(
                    context,
                    R.color.switch_enabled_track
                )
            }
            val askToggle = MaterialSwitch(this).apply {
                isChecked = server.ask
                text = "Preguntar"
                setOnCheckedChangeListener { _, isChecked ->
                    val updated = servers.map {
                        if (it.id == server.id) it.copy(ask = isChecked) else it
                    }
                    settingsManager.saveMcpServers(updated)
                }
                thumbTintList = androidx.core.content.ContextCompat.getColorStateList(
                    context,
                    R.color.switch_ask_thumb
                )
                trackTintList = androidx.core.content.ContextCompat.getColorStateList(
                    context,
                    R.color.switch_ask_track
                )
            }
            row.addView(label)
            row.addView(enableToggle)
            row.addView(askToggle)

            if (!server.type.startsWith("local_")) {
                val remove = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = "Eliminar"
                    setOnClickListener {
                        val updated = servers.filterNot { it.id == server.id }
                        settingsManager.saveMcpServers(updated)
                        renderMcpList()
                    }
                }
                row.addView(remove)
            }
            container.addView(row)
        }
    }

    private fun showAddMcpDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_endpoint, null)
        val etName = dialogView.findViewById<EditText>(R.id.et_name)
        val etUrl = dialogView.findViewById<EditText>(R.id.et_url)
        val etKey = dialogView.findViewById<EditText>(R.id.et_key)
        val tilKey = dialogView.findViewById<TextInputLayout>(R.id.til_key)
        val spinnerType = dialogView.findViewById<AutoCompleteTextView>(R.id.spinner_type)
        val tilUrl = dialogView.findViewById<TextInputLayout>(R.id.til_url)
        val btnScan = dialogView.findViewById<MaterialButton>(R.id.btn_scan)
        val tvHelpLink = dialogView.findViewById<TextView>(R.id.tv_help_link)

        tilKey.visibility = View.VISIBLE
        btnScan.visibility = View.GONE
        tvHelpLink.visibility = View.GONE
        tilUrl.visibility = View.VISIBLE

        val types = listOf("Remote MCP")
        spinnerType.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, types))
        spinnerType.setText("Remote MCP", false)
        spinnerType.isEnabled = false

        AlertDialog.Builder(this)
            .setTitle("Agregar MCP")
            .setView(dialogView)
            .setPositiveButton("Agregar") { _, _ ->
                val name = etName.text.toString().trim()
                val url = etUrl.text.toString().trim()
                val key = etKey.text.toString().trim()
                if (name.isBlank() || url.isBlank()) {
                    Toast.makeText(this, "Nombre y URL son obligatorios", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val servers = settingsManager.getMcpServers().toMutableList()
                servers.add(
                    McpServerConfig(
                        id = "mcp_${UUID.randomUUID()}",
                        name = name,
                        baseUrl = url,
                        type = "remote_http",
                        serverName = name.trim().lowercase().replace(" ", "_"),
                        enabled = true,
                        apiKey = key
                    )
                )
                settingsManager.saveMcpServers(servers)
                renderMcpList()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun startHealthCheck() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<HealthCheckWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "endpoint_health_check",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    private fun stopHealthCheck() {
        WorkManager.getInstance(this).cancelUniqueWork("endpoint_health_check")
    }

    private fun showTemplatesDialog() {
        val templates = EndpointTemplates.list
        val names = templates.map { it.name }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Select a Template")
            .setItems(names) { _, which ->
                showAddFromTemplateDialog(templates[which])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAddFromTemplateDialog(template: EndpointTemplate) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_endpoint, null)
        val etName = dialogView.findViewById<EditText>(R.id.et_name)
        val etUrl = dialogView.findViewById<EditText>(R.id.et_url)
        val etKey = dialogView.findViewById<EditText>(R.id.et_key)
        val spinnerType = dialogView.findViewById<AutoCompleteTextView>(R.id.spinner_type)
        val tilUrl = dialogView.findViewById<TextInputLayout>(R.id.til_url)
        val tilKey = dialogView.findViewById<TextInputLayout>(R.id.til_key)
        val tvHelpLink = dialogView.findViewById<TextView>(R.id.tv_help_link)
        val btnScan = dialogView.findViewById<MaterialButton>(R.id.btn_scan)

        etName.setText(template.name)
        etUrl.setText(template.baseUrl)
        spinnerType.setText(template.type, false)
        spinnerType.isEnabled = false
        
        if (!template.requiresApiKey) {
            tilKey.visibility = View.GONE
        }
        
        template.helpText?.let {
            tvHelpLink.visibility = View.VISIBLE
            tvHelpLink.text = it
            template.helpUrl?.let { url ->
                tvHelpLink.setOnClickListener {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    startActivity(intent)
                }
            }
        }

        btnScan.visibility = View.GONE

        AlertDialog.Builder(this)
            .setTitle("Configure ${template.name}")
            .setView(dialogView)
            .setPositiveButton("Add") { _, _ ->
                val name = etName.text.toString()
                val url = etUrl.text.toString()
                val key = etKey.text.toString()
                
                if (name.isNotBlank() && url.isNotBlank()) {
                    if (template.requiresApiKey && key.isBlank()) {
                        Toast.makeText(this, "API Key is required for ${template.name}", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    
                    val endpoints = settingsManager.getEndpoints().toMutableList()
                    endpoints.add(Endpoint(UUID.randomUUID().toString(), name, url, key, template.type))
                    settingsManager.saveEndpoints(endpoints)
                    endpointAdapter.updateData(endpoints)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAddEndpointDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_endpoint, null)
        val etName = dialogView.findViewById<EditText>(R.id.et_name)
        val etUrl = dialogView.findViewById<EditText>(R.id.et_url)
        val etKey = dialogView.findViewById<EditText>(R.id.et_key)
        val spinnerType = dialogView.findViewById<AutoCompleteTextView>(R.id.spinner_type)
        val tilUrl = dialogView.findViewById<TextInputLayout>(R.id.til_url)
        val tilKey = dialogView.findViewById<TextInputLayout>(R.id.til_key)
        val tvHelpLink = dialogView.findViewById<TextView>(R.id.tv_help_link)
        val btnScan = dialogView.findViewById<MaterialButton>(R.id.btn_scan)
        val pbScanning = dialogView.findViewById<ProgressBar>(R.id.pb_scanning)

        val types = listOf("OpenAI", "Ollama Cloud", "Ollama Self-Hosted", "LocalAI", "Generic")
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, types)
        spinnerType.setAdapter(adapter)

        spinnerType.setOnItemClickListener { _, _, position, _ ->
            val selectedType = types[position]
            when (selectedType) {
                "OpenAI" -> {
                    etUrl.setText("https://api.openai.com/v1")
                    tilUrl.visibility = View.VISIBLE
                    btnScan.visibility = View.GONE
                    tvHelpLink.visibility = View.GONE
                }
                "Ollama Cloud" -> {
                    etUrl.setText("https://api.ollama.com/v1")
                    tilUrl.visibility = View.GONE
                    btnScan.visibility = View.GONE
                    tvHelpLink.visibility = View.VISIBLE
                    tvHelpLink.setOnClickListener {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://ollama.com/signup"))
                        startActivity(intent)
                    }
                }
                "Ollama Self-Hosted" -> {
                    etUrl.setText("http://")
                    tilUrl.visibility = View.VISIBLE
                    btnScan.visibility = View.VISIBLE
                    tvHelpLink.visibility = View.GONE
                }
                else -> {
                    tilUrl.visibility = View.VISIBLE
                    btnScan.visibility = View.GONE
                    tvHelpLink.visibility = View.GONE
                }
            }
        }

        btnScan.setOnClickListener {
            pbScanning.visibility = View.VISIBLE
            btnScan.isEnabled = false
            val foundIps = mutableListOf<String>()
            
            lifecycleScope.launch {
                networkScanner.scanForOllama(object : NetworkScanner.ScanCallback {
                    override fun onDeviceFound(ip: String) {
                        foundIps.add(ip)
                        etUrl.setText("http://$ip:11434/v1")
                        Toast.makeText(this@MainActivity, "Found Ollama at $ip", Toast.LENGTH_SHORT).show()
                    }

                    override fun onScanFinished() {
                        pbScanning.visibility = View.GONE
                        btnScan.isEnabled = true
                        if (foundIps.isEmpty()) {
                            Toast.makeText(this@MainActivity, "No Ollama servers found in local network", Toast.LENGTH_LONG).show()
                        }
                    }
                })
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Add Endpoint")
            .setView(dialogView)
            .setPositiveButton("Add") { _, _ ->
                val name = etName.text.toString()
                val url = etUrl.text.toString()
                val key = etKey.text.toString()
                val type = spinnerType.text.toString().lowercase().replace(" ", "_")
                
                if (name.isNotBlank() && url.isNotBlank()) {
                    if (type == "ollama_cloud" && key.isBlank()) {
                        Toast.makeText(this, "API Key is required for Ollama Cloud", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    
                    val endpoints = settingsManager.getEndpoints().toMutableList()
                    endpoints.add(Endpoint(UUID.randomUUID().toString(), name, url, key, type))
                    settingsManager.saveEndpoints(endpoints)
                    endpointAdapter.updateData(endpoints)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEditCategoryDialog(category: Category) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_category, null)
        val spinnerPrimaryEndpoint = dialogView.findViewById<AutoCompleteTextView>(R.id.spinner_primary_endpoint)
        val spinnerPrimaryModel = dialogView.findViewById<AutoCompleteTextView>(R.id.spinner_primary_model)
        val spinnerBackupEndpoint = dialogView.findViewById<AutoCompleteTextView>(R.id.spinner_backup_endpoint)
        val spinnerBackupModel = dialogView.findViewById<AutoCompleteTextView>(R.id.spinner_backup_model)
        val tvInfo = dialogView.findViewById<TextView>(R.id.tv_category_info)

        // Show info about the category
        tvInfo.text = when(category) {
            Category.AGENT -> "Agent: The main brain that processes your queries. (Remote or Local)"
            Category.STT -> {
                "STT: Speech-to-Text conversion. 'Android Default' is local/system. Whisper models are remote."
            }
            Category.TTS -> "TTS: Text-to-Speech playback. 'Android Default' is system voices."
            else -> "Configure endpoints for ${category.name}"
        }

        val endpoints = settingsManager.getEndpoints().toMutableList()
        val endpointNames = endpoints.map { it.name }.toMutableList()
        val currentConfig = settingsManager.getCategoryConfig(category)
        val currentPrimaryId = currentConfig.primary?.endpointId
        val localOptions = getLocalModelOptions(category)
        val shouldIncludeLocal = localOptions.isNotEmpty() || currentPrimaryId == "local"
        if (category == Category.STT || category == Category.TTS) {
            endpointNames.add(0, "Android Default (System)")
        }
        if ((category == Category.STT || category == Category.AGENT) && shouldIncludeLocal) {
            endpointNames.add(0, "Local (on-device)")
        }

        val endpointAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, endpointNames)
        spinnerPrimaryEndpoint.setAdapter(endpointAdapter)
        spinnerBackupEndpoint.setAdapter(endpointAdapter)

        fun updateModelList(endpointName: String, modelSpinner: AutoCompleteTextView, currentModel: String?) {
            if (endpointName == "Android Default (System)") {
                modelSpinner.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, listOf("default")))
                modelSpinner.setText("default", false)
                return
            }
            if (endpointName == "Local (on-device)") {
                val localModels = getLocalModelOptions(category)
                val displayNames = localModels.map { it.first }
                modelSpinner.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, displayNames))
                val currentDisplay = localModels.firstOrNull { it.second == currentModel }?.first
                if (currentDisplay != null) {
                    modelSpinner.setText(currentDisplay, false)
                } else if (displayNames.isNotEmpty()) {
                    modelSpinner.setText(displayNames.first(), false)
                }
                return
            }

            val endpoint = endpoints.find { it.name == endpointName }
            if (endpoint != null) {
                val cachedModels = modelCacheManager.getModels(endpoint.id)
                if (cachedModels != null) {
                    val modelAdapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_dropdown_item_1line, cachedModels)
                    modelSpinner.setAdapter(modelAdapter)
                    if (cachedModels.contains(currentModel)) {
                        modelSpinner.setText(currentModel, false)
                    }
                    return
                }

                modelSpinner.setText("Loading models...", false)
                OpenAiClient(endpoint).fetchModels(object : OpenAiClient.ModelsCallback {
                    override fun onSuccess(models: List<String>) {
                        modelCacheManager.saveModels(endpoint.id, models)
                        runOnUiThread {
                            val modelAdapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_dropdown_item_1line, models)
                            modelSpinner.setAdapter(modelAdapter)
                            if (models.contains(currentModel)) {
                                modelSpinner.setText(currentModel, false)
                            } else {
                                modelSpinner.setText("", false)
                            }
                        }
                    }

                    override fun onError(e: Throwable) {
                        runOnUiThread {
                            val errorMsg = if (e is OpenAiClient.ApiError) e.message else "Error: ${e.message}"
                            Toast.makeText(this@MainActivity, errorMsg, Toast.LENGTH_LONG).show()
                            modelSpinner.setText("", false)
                        }
                    }
                })
            }
        }

        spinnerPrimaryEndpoint.setOnItemClickListener { _, _, _, _ ->
            updateModelList(spinnerPrimaryEndpoint.text.toString(), spinnerPrimaryModel, null)
        }

        spinnerBackupEndpoint.setOnItemClickListener { _, _, _, _ ->
            updateModelList(spinnerBackupEndpoint.text.toString(), spinnerBackupModel, null)
        }

        // Initial setup
        currentConfig.primary?.let { config ->
            if (config.endpointId == "system") {
                spinnerPrimaryEndpoint.setText("Android Default (System)", false)
                updateModelList("Android Default (System)", spinnerPrimaryModel, "default")
            } else if (config.endpointId == "local") {
                spinnerPrimaryEndpoint.setText("Local (on-device)", false)
                updateModelList("Local (on-device)", spinnerPrimaryModel, config.modelName)
            } else {
                val endpoint = endpoints.find { it.id == config.endpointId }
                if (endpoint != null) {
                    spinnerPrimaryEndpoint.setText(endpoint.name, false)
                    updateModelList(endpoint.name, spinnerPrimaryModel, config.modelName)
                }
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Edit ${category.name}")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val primaryName = spinnerPrimaryEndpoint.text.toString()
                val primaryEndpoint = if (primaryName == "Android Default (System)" || primaryName.startsWith("Local ")) {
                    null
                } else {
                    endpoints.find { it.name == primaryName }
                }
                val primaryModelName = spinnerPrimaryModel.text.toString()

                if (primaryName == "Android Default (System)") {
                    val newConfig = CategoryConfig(primary = ModelConfig("system", "default"))
                    settingsManager.saveCategoryConfig(category, newConfig)
                    categoryAdapter.notifyDataSetChanged()
                } else if (primaryName.startsWith("Local ")) {
                    val localModels = getLocalModelOptions(category)
                    val selected = localModels.firstOrNull { it.first == primaryModelName }
                    if (selected != null) {
                        val localModelName = selected.second
                        val newConfig = CategoryConfig(primary = ModelConfig("local", localModelName))
                        settingsManager.saveCategoryConfig(category, newConfig)
                        if (category == Category.STT) {
                            settingsManager.localSttModel = localModelName
                            settingsManager.localSttEnabled = true
                        } else if (category == Category.AGENT) {
                            settingsManager.localAgentModel = localModelName
                            settingsManager.localAgentEnabled = true
                        }
                        categoryAdapter.notifyDataSetChanged()
                    }
                } else if (primaryEndpoint != null && primaryModelName.isNotBlank() && primaryModelName != "Loading models...") {
                    val newConfig = CategoryConfig(
                        primary = ModelConfig(primaryEndpoint.id, primaryModelName)
                    )
                    settingsManager.saveCategoryConfig(category, newConfig)
                    categoryAdapter.notifyDataSetChanged()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
