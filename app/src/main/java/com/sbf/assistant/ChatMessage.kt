package com.sbf.assistant

data class ChatStats(
    val toolCount: Int = 0,
    val tokenCount: Int = 0
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
