package com.sbf.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ChatHistoryStore(context: Context) {
    private val file = File(context.filesDir, FILE_NAME)

    fun load(): List<ChatMessage> {
        if (!file.exists()) return emptyList()
        val raw = runCatching { file.readText() }.getOrNull().orEmpty()
        if (raw.isBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                val text = obj.optString("text")
                val isUser = obj.optBoolean("isUser", false)
                if (text.isBlank()) null else ChatMessage(text, isUser)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun append(message: ChatMessage) {
        val messages = load().toMutableList()
        messages.add(message)
        saveAll(messages)
    }

    fun saveAll(messages: List<ChatMessage>) {
        val array = JSONArray()
        messages.forEach { message ->
            array.put(
                JSONObject().apply {
                    put("text", message.text)
                    put("isUser", message.isUser)
                }
            )
        }
        file.writeText(array.toString())
    }

    fun clear() {
        if (file.exists()) {
            file.delete()
        }
    }

    companion object {
        private const val FILE_NAME = "chat_history.json"
    }
}
