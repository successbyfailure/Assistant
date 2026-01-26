package com.sbf.assistant

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ChatControllerTest {

    @Test
    fun streamTokensAccumulateAndComplete() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        resetPrefs(context)
        val settings = SettingsManager(context)
        val endpoints = listOf(
            Endpoint("primary", "Primary", "http://example.com", "", "openai")
        )
        settings.saveEndpoints(endpoints)
        settings.saveCategoryConfig(
            Category.AGENT,
            CategoryConfig(primary = ModelConfig("primary", "gpt-test"))
        )
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

        val client = FakeChatStreamClient { _, _, _, callback ->
            callback.onToken("Hola ")
            callback.onToken("mundo")
            callback.onComplete()
        }

        val controller = ChatController(
            settingsManager = settings,
            toolExecutor = ToolExecutor(context, null),
            toolRegistry = ToolRegistry(settings, null),
            geminiNano = null,
            localLlm = null,
            mediaPipeLlm = null,
            scope = scope,
            chatClientFactory = { client }
        )

        val latch = CountDownLatch(1)
        val tokens = mutableListOf<String>()
        val result = CompletableDeferred<String>()

        controller.processQuery("test", object : ChatController.Callbacks {
            override fun onStatusUpdate(status: String) {}
            override fun onResponseToken(token: String) { tokens.add(token) }
            override fun onResponseComplete(fullResponse: String) {
                result.complete(fullResponse)
                latch.countDown()
            }
            override fun onToolExecutionStart() {}
            override fun onToolCalls(toolCalls: List<ToolCall>) {}
            override fun onError(error: String, wasPrimary: Boolean) {}
            override fun handleToolGate(call: ToolCall): ToolResult? = null
        })

        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertEquals(listOf("Hola ", "mundo"), tokens)
        assertEquals("Hola mundo", runBlocking { result.await() })
    }

    @Test
    fun failoverUsesBackupOnPrimaryError() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        resetPrefs(context)
        val settings = SettingsManager(context)
        settings.saveEndpoints(
            listOf(
                Endpoint("primary", "Primary", "http://primary", "", "openai"),
                Endpoint("backup", "Backup", "http://backup", "", "openai")
            )
        )
        settings.saveCategoryConfig(
            Category.AGENT,
            CategoryConfig(
                primary = ModelConfig("primary", "gpt-primary"),
                backup = ModelConfig("backup", "gpt-backup")
            )
        )
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

        val primaryClient = FakeChatStreamClient { _, _, _, callback ->
            callback.onError(IllegalStateException("Primary down"))
        }
        val backupClient = FakeChatStreamClient { _, _, _, callback ->
            callback.onToken("ok")
            callback.onComplete()
        }

        val controller = ChatController(
            settingsManager = settings,
            toolExecutor = ToolExecutor(context, null),
            toolRegistry = ToolRegistry(settings, null),
            geminiNano = null,
            localLlm = null,
            mediaPipeLlm = null,
            scope = scope,
            chatClientFactory = { endpoint ->
                if (endpoint.id == "primary") primaryClient else backupClient
            }
        )

        val latch = CountDownLatch(1)
        val statuses = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val result = CompletableDeferred<String>()

        controller.processQuery("test", object : ChatController.Callbacks {
            override fun onStatusUpdate(status: String) { statuses.add(status) }
            override fun onResponseToken(token: String) {}
            override fun onResponseComplete(fullResponse: String) {
                result.complete(fullResponse)
                latch.countDown()
            }
            override fun onToolExecutionStart() {}
            override fun onToolCalls(toolCalls: List<ToolCall>) {}
            override fun onError(error: String, wasPrimary: Boolean) { errors.add(error) }
            override fun handleToolGate(call: ToolCall): ToolResult? = null
        })

        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertTrue(statuses.contains("Primary failed, trying Backup..."))
        assertEquals("ok", runBlocking { result.await() })
        assertTrue(errors.isEmpty())
    }

    @Test
    fun toolCallsTriggerSecondRequest() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        resetPrefs(context)
        val settings = SettingsManager(context)
        settings.saveEndpoints(listOf(Endpoint("primary", "Primary", "http://primary", "", "openai")))
        settings.saveCategoryConfig(
            Category.AGENT,
            CategoryConfig(primary = ModelConfig("primary", "gpt-primary"))
        )
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

        val sequence = ArrayDeque<(OpenAiClient.StreamCallback) -> Unit>()
        sequence.add { callback ->
            val args = JSONObject().put("query", "test").toString()
            callback.onToolCalls(listOf(ToolCall("call-1", "search_contacts", args)))
        }
        sequence.add { callback ->
            callback.onToken("done")
            callback.onComplete()
        }

        val client = object : ChatStreamClient {
            override fun streamChatCompletion(
                modelName: String,
                messages: List<LlmMessage>,
                tools: List<ToolDefinition>,
                callback: OpenAiClient.StreamCallback
            ) {
                sequence.removeFirstOrNull()?.invoke(callback)
            }
        }

        val controller = ChatController(
            settingsManager = settings,
            toolExecutor = ToolExecutor(context, null),
            toolRegistry = ToolRegistry(settings, null),
            geminiNano = null,
            localLlm = null,
            mediaPipeLlm = null,
            scope = scope,
            chatClientFactory = { client }
        )

        val latch = CountDownLatch(1)
        val toolStarts = mutableListOf<Unit>()
        val result = CompletableDeferred<String>()

        controller.processQuery("test", object : ChatController.Callbacks {
            override fun onStatusUpdate(status: String) {}
            override fun onResponseToken(token: String) {}
            override fun onResponseComplete(fullResponse: String) {
                result.complete(fullResponse)
                latch.countDown()
            }
            override fun onToolExecutionStart() { toolStarts.add(Unit) }
            override fun onToolCalls(toolCalls: List<ToolCall>) {}
            override fun onError(error: String, wasPrimary: Boolean) {}
            override fun handleToolGate(call: ToolCall): ToolResult? {
                return ToolResult(call.id, call.name, "ok")
            }
        })

        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertEquals(1, toolStarts.size)
        assertEquals("done", runBlocking { result.await() })
    }

    private class FakeChatStreamClient(
        private val handler: (
            modelName: String,
            messages: List<LlmMessage>,
            tools: List<ToolDefinition>,
            callback: OpenAiClient.StreamCallback
        ) -> Unit
    ) : ChatStreamClient {
        override fun streamChatCompletion(
            modelName: String,
            messages: List<LlmMessage>,
            tools: List<ToolDefinition>,
            callback: OpenAiClient.StreamCallback
        ): ChatRequestHandle {
            handler(modelName, messages, tools, callback)
            return object : ChatRequestHandle {
                override fun cancel() = Unit
            }
        }
    }

    private fun resetPrefs(context: android.content.Context) {
        context.getSharedPreferences("assistant_settings", android.content.Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }
}
