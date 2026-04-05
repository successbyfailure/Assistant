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
        get() = prefs.getString("agent_user_prompt_prefix", "\$now - source: \$source") ?: "\$now - source: \$source"
        set(value) = prefs.edit().putString("agent_user_prompt_prefix", value).apply()

    var agentUserPromptPrefixVarsEnabled: Boolean
        get() = prefs.getBoolean("agent_user_prompt_prefix_vars", true)
        set(value) = prefs.edit().putBoolean("agent_user_prompt_prefix_vars", value).apply()

    var ttsAutoReadEnabled: Boolean
        get() = prefs.getBoolean("tts_auto_read_enabled", true)
        set(value) = prefs.edit().putBoolean("tts_auto_read_enabled", value).apply()

    var ttsChunkOnPunctuation: Boolean
        get() = prefs.getBoolean("tts_chunk_on_punctuation", false)
        set(value) = prefs.edit().putBoolean("tts_chunk_on_punctuation", value).apply()

    var ttsChunkSeparators: String
        get() = prefs.getString("tts_chunk_separators", ".,/;:!?") ?: ".,/;:!?"
        set(value) = prefs.edit().putString("tts_chunk_separators", value).apply()

    var ttsChunkMaxLength: Int
        get() = prefs.getInt("tts_chunk_max_length", 280)
        set(value) = prefs.edit().putInt("tts_chunk_max_length", value).apply()

    var ttsStreamOnTokens: Boolean
        get() = prefs.getBoolean("tts_stream_on_tokens", false)
        set(value) = prefs.edit().putBoolean("tts_stream_on_tokens", value).apply()

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

    var autoConversationEnabled: Boolean
        get() = prefs.getBoolean("auto_conversation_enabled", true)
        set(value) = prefs.edit().putBoolean("auto_conversation_enabled", value).apply()

    var autoConversationTimeoutMs: Long
        get() = prefs.getLong("auto_conversation_timeout_ms", 8_000L)
        set(value) = prefs.edit().putLong("auto_conversation_timeout_ms", value).apply()

    var toolTimeoutMs: Long
        get() = prefs.getLong("tool_timeout_ms", 10_000L)
        set(value) = prefs.edit().putLong("tool_timeout_ms", value).apply()

    var statsTotalSttTokens: Int
        get() = prefs.getInt("stats_total_stt_tokens", 0)
        set(value) = prefs.edit().putInt("stats_total_stt_tokens", value).apply()

    var statsTotalTtsTokens: Int
        get() = prefs.getInt("stats_total_tts_tokens", 0)
        set(value) = prefs.edit().putInt("stats_total_tts_tokens", value).apply()

    var statsTotalLlmPromptTokens: Int
        get() = prefs.getInt("stats_total_llm_prompt_tokens", 0)
        set(value) = prefs.edit().putInt("stats_total_llm_prompt_tokens", value).apply()

    var statsTotalLlmCompTokens: Int
        get() = prefs.getInt("stats_total_llm_comp_tokens", 0)
        set(value) = prefs.edit().putInt("stats_total_llm_comp_tokens", value).apply()

    var statsTotalToolCalls: Int
        get() = prefs.getInt("stats_total_tool_calls", 0)
        set(value) = prefs.edit().putInt("stats_total_tool_calls", value).apply()

    var statsTotalToolTokens: Int
        get() = prefs.getInt("stats_total_tool_tokens", 0)
        set(value) = prefs.edit().putInt("stats_total_tool_tokens", value).apply()

    var statsCountLlm: Int
        get() = prefs.getInt("stats_count_llm", 0)
        set(value) = prefs.edit().putInt("stats_count_llm", value).apply()

    var statsCountStt: Int
        get() = prefs.getInt("stats_count_stt", 0)
        set(value) = prefs.edit().putInt("stats_count_stt", value).apply()

    var statsCountTts: Int
        get() = prefs.getInt("stats_count_tts", 0)
        set(value) = prefs.edit().putInt("stats_count_tts", value).apply()

    var statsCountTools: Int
        get() = prefs.getInt("stats_count_tools", 0)
        set(value) = prefs.edit().putInt("stats_count_tools", value).apply()

    fun clearStats() {
        prefs.edit().apply {
            putInt("stats_total_stt_tokens", 0)
            putInt("stats_total_tts_tokens", 0)
            putInt("stats_total_llm_prompt_tokens", 0)
            putInt("stats_total_llm_comp_tokens", 0)
            putInt("stats_total_tool_calls", 0)
            putInt("stats_total_tool_tokens", 0)
            putInt("stats_count_llm", 0)
            putInt("stats_count_stt", 0)
            putInt("stats_count_tts", 0)
            putInt("stats_count_tools", 0)
            putString("stats_tokens_by_service", null)
            putString("stats_tokens_by_model", null)
            putString("stats_tokens_by_day", null)
        }.apply()
    }

    fun recordTokenUsage(service: String, model: String?, tokens: Int) {
        if (tokens <= 0) return
        val serviceKey = service.trim().lowercase()
        val now = java.time.LocalDate.now()
        val dateKey = now.toString()

        val byService = readIntMap("stats_tokens_by_service")
        byService[serviceKey] = (byService[serviceKey] ?: 0) + tokens
        writeIntMap("stats_tokens_by_service", byService)

        if (!model.isNullOrBlank()) {
            val byModel = readIntMap("stats_tokens_by_model")
            val modelKey = "${serviceKey}:${model.trim()}"
            byModel[modelKey] = (byModel[modelKey] ?: 0) + tokens
            writeIntMap("stats_tokens_by_model", byModel)
        }

        val byDay = readIntMap("stats_tokens_by_day")
        byDay[dateKey] = (byDay[dateKey] ?: 0) + tokens
        pruneDays(byDay)
        writeIntMap("stats_tokens_by_day", byDay)
    }

    fun getTokenUsageByService(): Map<String, Int> {
        return readIntMap("stats_tokens_by_service")
    }

    fun getTokenUsageByModel(): Map<String, Int> {
        return readIntMap("stats_tokens_by_model")
    }

    fun getTokenUsageByDay(): Map<String, Int> {
        return readIntMap("stats_tokens_by_day")
    }

    private fun pruneDays(map: MutableMap<String, Int>) {
        val today = java.time.LocalDate.now()
        val keep = (0..6).map { today.minusDays(it.toLong()).toString() }.toSet()
        val iterator = map.keys.iterator()
        while (iterator.hasNext()) {
            val key = iterator.next()
            if (key !in keep) {
                iterator.remove()
            }
        }
    }

    private fun readIntMap(key: String): MutableMap<String, Int> {
        val json = prefs.getString(key, null) ?: return mutableMapOf()
        val obj = try {
            org.json.JSONObject(json)
        } catch (e: Exception) {
            return mutableMapOf()
        }
        val map = mutableMapOf<String, Int>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            map[k] = obj.optInt(k, 0)
        }
        return map
    }

    private fun writeIntMap(key: String, map: Map<String, Int>) {
        val obj = org.json.JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        prefs.edit().putString(key, obj.toString()).apply()
    }

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
