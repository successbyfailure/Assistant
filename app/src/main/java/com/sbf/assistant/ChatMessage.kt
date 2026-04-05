package com.sbf.assistant

data class ChatStats(
    val toolCount: Int = 0,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
    val durationMs: Long = 0,
    val model: String = ""
)

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val thought: String = "",
    val isThinking: Boolean = false,
    val thoughtCollapsed: Boolean = true,
    val toolNames: List<String> = emptyList(),
    val toolCallIds: List<String> = emptyList(),
    val showCancel: Boolean = false,
    val stats: ChatStats? = null
)
