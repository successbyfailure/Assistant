package com.sbf.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class SettingsManager(context: Context) {
    private val prefs = context.getSharedPreferences("assistant_settings", Context.MODE_PRIVATE)

    fun getEndpoints(): List<Endpoint> {
        val json = prefs.getString("endpoints", "[]") ?: "[]"
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { Endpoint.fromJson(array.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveEndpoints(endpoints: List<Endpoint>) {
        val array = JSONArray()
        endpoints.forEach { array.put(it.toJson()) }
        prefs.edit().putString("endpoints", array.toString()).apply()
    }

    fun getCategoryConfig(category: Category): CategoryConfig {
        val json = prefs.getString("config_${category.key}", null) ?: return CategoryConfig()
        return try {
            CategoryConfig.fromJson(JSONObject(json))
        } catch (e: Exception) {
            CategoryConfig()
        }
    }

    fun saveCategoryConfig(category: Category, config: CategoryConfig) {
        prefs.edit().putString("config_${category.key}", config.toJson().toString()).apply()
    }
        
    fun getEndpoint(id: String): Endpoint? {
        return getEndpoints().find { it.id == id }
    }

    var isHealthCheckEnabled: Boolean
        get() = prefs.getBoolean("health_check_enabled", false)
        set(value) = prefs.edit().putBoolean("health_check_enabled", value).apply()

    var toolsEnabled: Boolean
        get() = prefs.getBoolean("tools_enabled", true)
        set(value) = prefs.edit().putBoolean("tools_enabled", value).apply()

    var toolAllowSms: Boolean
        get() = prefs.getBoolean("tool_allow_sms", true)
        set(value) = prefs.edit().putBoolean("tool_allow_sms", value).apply()

    var toolAllowCall: Boolean
        get() = prefs.getBoolean("tool_allow_call", true)
        set(value) = prefs.edit().putBoolean("tool_allow_call", value).apply()

    var toolAllowAlarm: Boolean
        get() = prefs.getBoolean("tool_allow_alarm", true)
        set(value) = prefs.edit().putBoolean("tool_allow_alarm", value).apply()

    var toolAllowOpenApp: Boolean
        get() = prefs.getBoolean("tool_allow_open_app", true)
        set(value) = prefs.edit().putBoolean("tool_allow_open_app", value).apply()

    var toolAllowContacts: Boolean
        get() = prefs.getBoolean("tool_allow_contacts", true)
        set(value) = prefs.edit().putBoolean("tool_allow_contacts", value).apply()

    var toolAllowLocation: Boolean
        get() = prefs.getBoolean("tool_allow_location", true)
        set(value) = prefs.edit().putBoolean("tool_allow_location", value).apply()

    var toolAllowWeather: Boolean
        get() = prefs.getBoolean("tool_allow_weather", true)
        set(value) = prefs.edit().putBoolean("tool_allow_weather", value).apply()

    var toolAllowNotifications: Boolean
        get() = prefs.getBoolean("tool_allow_notifications", true)
        set(value) = prefs.edit().putBoolean("tool_allow_notifications", value).apply()

    var toolAskSms: Boolean
        get() = prefs.getBoolean("tool_ask_sms", true)
        set(value) = prefs.edit().putBoolean("tool_ask_sms", value).apply()

    var toolAskCall: Boolean
        get() = prefs.getBoolean("tool_ask_call", true)
        set(value) = prefs.edit().putBoolean("tool_ask_call", value).apply()

    var toolAskAlarm: Boolean
        get() = prefs.getBoolean("tool_ask_alarm", true)
        set(value) = prefs.edit().putBoolean("tool_ask_alarm", value).apply()

    var toolAskOpenApp: Boolean
        get() = prefs.getBoolean("tool_ask_open_app", true)
        set(value) = prefs.edit().putBoolean("tool_ask_open_app", value).apply()

    var toolAskContacts: Boolean
        get() = prefs.getBoolean("tool_ask_contacts", true)
        set(value) = prefs.edit().putBoolean("tool_ask_contacts", value).apply()

    var toolAskLocation: Boolean
        get() = prefs.getBoolean("tool_ask_location", true)
        set(value) = prefs.edit().putBoolean("tool_ask_location", value).apply()

    var toolAskWeather: Boolean
        get() = prefs.getBoolean("tool_ask_weather", true)
        set(value) = prefs.edit().putBoolean("tool_ask_weather", value).apply()

    var toolAskNotifications: Boolean
        get() = prefs.getBoolean("tool_ask_notifications", true)
        set(value) = prefs.edit().putBoolean("tool_ask_notifications", value).apply()

    var mcpEnabled: Boolean
        get() = prefs.getBoolean("mcp_enabled", true)
        set(value) = prefs.edit().putBoolean("mcp_enabled", value).apply()

    var localSttEnabled: Boolean
        get() = prefs.getBoolean("local_stt_enabled", false)
        set(value) = prefs.edit().putBoolean("local_stt_enabled", value).apply()

    var localAgentEnabled: Boolean
        get() = prefs.getBoolean("local_agent_enabled", false)
        set(value) = prefs.edit().putBoolean("local_agent_enabled", value).apply()

    var hfApiKey: String
        get() = prefs.getString("hf_api_key", "") ?: ""
        set(value) = prefs.edit().putString("hf_api_key", value).apply()

    fun getHfRepoList(): List<String> {
        val json = prefs.getString("hf_repo_list", "[]") ?: "[]"
        return try {
            val array = JSONArray(json)
            (0 until array.length())
                .map { array.optString(it).trim() }
                .filter { it.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun setHfRepoList(repos: List<String>) {
        val array = JSONArray()
        repos.forEach { array.put(it) }
        prefs.edit().putString("hf_repo_list", array.toString()).apply()
    }

    fun getHfScannedModels(): List<ModelDownloadManager.ModelInfo> {
        val json = prefs.getString("hf_scanned_models", "[]") ?: "[]"
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { idx ->
                val obj = array.optJSONObject(idx) ?: return@mapNotNull null
                ModelDownloadManager.modelInfoFromJson(obj)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun setHfScannedModels(models: List<ModelDownloadManager.ModelInfo>) {
        val array = JSONArray()
        models.forEach { model ->
            array.put(ModelDownloadManager.modelInfoToJson(model))
        }
        prefs.edit().putString("hf_scanned_models", array.toString()).apply()
    }

    fun getHfRepoScanDetails(): List<ModelDownloadManager.RepoScanDetails> {
        val json = prefs.getString("hf_repo_scan_details", "[]") ?: "[]"
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { idx ->
                val obj = array.optJSONObject(idx) ?: return@mapNotNull null
                ModelDownloadManager.repoScanDetailsFromJson(obj)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun setHfRepoScanDetails(details: List<ModelDownloadManager.RepoScanDetails>) {
        val array = JSONArray()
        details.forEach { detail ->
            array.put(ModelDownloadManager.repoScanDetailsToJson(detail))
        }
        prefs.edit().putString("hf_repo_scan_details", array.toString()).apply()
    }

    fun getHfAcceptedRepos(): Set<String> {
        val json = prefs.getString("hf_accepted_repos", "[]") ?: "[]"
        return try {
            val array = JSONArray(json)
            (0 until array.length())
                .map { array.optString(it).trim() }
                .filter { it.isNotBlank() }
                .toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    fun setHfAcceptedRepos(repos: Set<String>) {
        val array = JSONArray()
        repos.forEach { array.put(it) }
        prefs.edit().putString("hf_accepted_repos", array.toString()).apply()
    }

    var localWhisperRepo: String
        get() = prefs.getString("local_whisper_repo", "") ?: ""
        set(value) = prefs.edit().putString("local_whisper_repo", value).apply()

    var localWhisperFile: String
        get() = prefs.getString("local_whisper_file", "") ?: ""
        set(value) = prefs.edit().putString("local_whisper_file", value).apply()

    var localGemmaRepo: String
        get() = prefs.getString("local_gemma_repo", "") ?: ""
        set(value) = prefs.edit().putString("local_gemma_repo", value).apply()

    var localGemmaFile: String
        get() = prefs.getString("local_gemma_file", "") ?: ""
        set(value) = prefs.edit().putString("local_gemma_file", value).apply()

    var localSttModel: String
        get() = prefs.getString("local_stt_model", "") ?: ""
        set(value) = prefs.edit().putString("local_stt_model", value).apply()

    var localAgentModel: String
        get() = prefs.getString("local_agent_model", "") ?: ""
        set(value) = prefs.edit().putString("local_agent_model", value).apply()

    var localModelIdleMs: Long
        get() = prefs.getLong("local_model_idle_ms", 5 * 60_000L)
        set(value) = prefs.edit().putLong("local_model_idle_ms", value).apply()

    var agentSystemPrompt: String
        get() = prefs.getString("agent_system_prompt", "") ?: ""
        set(value) = prefs.edit().putString("agent_system_prompt", value).apply()

    var agentUserPromptPrefix: String
        get() = prefs.getString("agent_user_prompt_prefix", "") ?: ""
        set(value) = prefs.edit().putString("agent_user_prompt_prefix", value).apply()

    var ttsAutoReadEnabled: Boolean
        get() = prefs.getBoolean("tts_auto_read_enabled", true)
        set(value) = prefs.edit().putBoolean("tts_auto_read_enabled", value).apply()

    var themeMode: String
        get() = prefs.getString("theme_mode", ThemeManager.MODE_SYSTEM) ?: ThemeManager.MODE_SYSTEM
        set(value) = prefs.edit().putString("theme_mode", value).apply()

    var themeStyle: String
        get() = prefs.getString("theme_style", ThemeManager.STYLE_DEFAULT) ?: ThemeManager.STYLE_DEFAULT
        set(value) = prefs.edit().putString("theme_style", value).apply()

    var voiceShortcutEnabled: Boolean
        get() = prefs.getBoolean("voice_shortcut_enabled", false)
        set(value) = prefs.edit().putBoolean("voice_shortcut_enabled", value).apply()

    var voiceShortcutPhrase: String
        get() = prefs.getString("voice_shortcut_phrase", "") ?: ""
        set(value) = prefs.edit().putString("voice_shortcut_phrase", value).apply()

    var toolTimeoutMs: Long
        get() = prefs.getLong("tool_timeout_ms", 10_000L)
        set(value) = prefs.edit().putLong("tool_timeout_ms", value).apply()

    private fun getDefaultMcpServers(): List<McpServerConfig> {
        return listOf(
            McpServerConfig(
                id = "mcp_local_filesystem",
                name = "Local Filesystem",
                baseUrl = "",
                type = "local_filesystem",
                serverName = "filesystem",
                enabled = true,
                ask = true
            ),
            McpServerConfig(
                id = "mcp_local_calendar",
                name = "Local Calendar",
                baseUrl = "",
                type = "local_calendar",
                serverName = "calendar",
                enabled = false,
                ask = true
            ),
            McpServerConfig(
                id = "mcp_local_notes",
                name = "Local Notes",
                baseUrl = "",
                type = "local_notes",
                serverName = "notes",
                enabled = true,
                ask = true
            )
        )
    }

    fun getMcpServers(): List<McpServerConfig> {
        val json = prefs.getString("mcp_servers", null)
        if (json.isNullOrBlank()) {
            val defaults = getDefaultMcpServers()
            saveMcpServers(defaults)
            return defaults
        }
        val array = try {
            org.json.JSONArray(json)
        } catch (e: Exception) {
            val defaults = getDefaultMcpServers()
            saveMcpServers(defaults)
            return defaults
        }
        val parsed = try {
            (0 until array.length()).map { McpServerConfig.fromJson(array.getJSONObject(it)) }
        } catch (e: Exception) {
            val defaults = getDefaultMcpServers()
            saveMcpServers(defaults)
            return defaults
        }
            val normalized = parsed.map { server ->
                val normalizedName = when (server.type) {
                    "local_filesystem" -> "filesystem"
                    "local_calendar" -> "calendar"
                    "local_notes" -> "notes"
                    else -> server.serverName.ifBlank { server.name.trim().lowercase().replace(" ", "_") }
                }
                if (server.serverName != normalizedName) {
                    server.copy(serverName = normalizedName)
                } else {
                    server
                }
            }
        if (normalized != parsed) {
            saveMcpServers(normalized)
        }
        return normalized
    }

    fun getMcpServerByName(serverName: String): McpServerConfig? {
        return getMcpServers().firstOrNull { it.serverName.equals(serverName, ignoreCase = true) }
    }

    fun saveMcpServers(servers: List<McpServerConfig>) {
        val array = org.json.JSONArray()
        servers.forEach { array.put(it.toJson()) }
        prefs.edit().putString("mcp_servers", array.toString()).apply()
    }
}
