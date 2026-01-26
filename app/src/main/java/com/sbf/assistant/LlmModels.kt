package com.sbf.assistant

import org.json.JSONArray
import org.json.JSONObject

data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: JSONObject
) {
    fun toOpenAiJson(): JSONObject = JSONObject().apply {
        put("type", "function")
        put("function", JSONObject().apply {
            put("name", name)
            put("description", description)
            put("parameters", parameters)
        })
    }
}

data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String
)

data class ToolResult(
    val callId: String,
    val name: String,
    val output: String,
    val isError: Boolean = false
)

data class LlmMessage(
    val role: String,
    val content: String? = null,
    val toolCalls: List<ToolCall>? = null,
    val toolCallId: String? = null,
    val name: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("role", role)
        if (content != null) {
            put("content", content)
        }
        if (name != null) {
            put("name", name)
        }
        if (toolCallId != null) {
            put("tool_call_id", toolCallId)
        }
        if (!toolCalls.isNullOrEmpty()) {
            val array = JSONArray()
            toolCalls.forEach { call ->
                array.put(JSONObject().apply {
                    put("id", call.id)
                    put("type", "function")
                    put("function", JSONObject().apply {
                        put("name", call.name)
                        put("arguments", call.arguments)
                    })
                })
            }
            put("tool_calls", array)
        }
    }
}
