package com.sbf.assistant

import org.json.JSONObject

enum class Category(val key: String) {
    AGENT("agent"),
    STT("stt"),
    TTS("tts"),
    IMAGE_GEN("image_gen"),
    OCR("ocr")
}

data class Endpoint(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val type: String // "openai", "ollama", etc.
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("baseUrl", baseUrl)
        put("apiKey", apiKey)
        put("type", type)
    }

    companion object {
        fun fromJson(json: JSONObject): Endpoint = Endpoint(
            json.getString("id"),
            json.getString("name"),
            json.getString("baseUrl"),
            json.getString("apiKey"),
            json.getString("type")
        )
    }
}

data class ModelConfig(
    val endpointId: String,
    val modelName: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("endpointId", endpointId)
        put("modelName", modelName)
    }

    companion object {
        fun fromJson(json: JSONObject): ModelConfig = ModelConfig(
            json.getString("endpointId"),
            json.getString("modelName")
        )
    }
}

data class CategoryConfig(
    val primary: ModelConfig? = null,
    val backup: ModelConfig? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("primary", primary?.toJson())
        put("backup", backup?.toJson())
    }

    companion object {
        fun fromJson(json: JSONObject): CategoryConfig = CategoryConfig(
            primary = if (json.has("primary") && !json.isNull("primary")) ModelConfig.fromJson(json.getJSONObject("primary")) else null,
            backup = if (json.has("backup") && !json.isNull("backup")) ModelConfig.fromJson(json.getJSONObject("backup")) else null
        )
    }
}

data class McpServerConfig(
    val id: String,
    val name: String,
    val baseUrl: String,
    val type: String,
    val serverName: String,
    val enabled: Boolean = true,
    val ask: Boolean = true,
    val apiKey: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("baseUrl", baseUrl)
        put("type", type)
        put("serverName", serverName)
        put("enabled", enabled)
        put("ask", ask)
        put("apiKey", apiKey)
    }

    companion object {
        fun fromJson(json: JSONObject): McpServerConfig = McpServerConfig(
            id = json.getString("id"),
            name = json.getString("name"),
            baseUrl = json.optString("baseUrl", ""),
            type = json.getString("type"),
            serverName = json.optString("serverName", json.optString("name", "")),
            enabled = json.optBoolean("enabled", true),
            ask = json.optBoolean("ask", true),
            apiKey = json.optString("apiKey", "")
        )
    }
}
