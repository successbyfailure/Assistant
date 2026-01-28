package com.sbf.assistant

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Manages model warmup requests to preload models on the server
 * while the user is preparing their input.
 */
class WarmupManager(
    private val context: Context,
    private val settingsManager: SettingsManager
) {
    private val client = HttpClientProvider.streaming
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var lastLlmWarmup = 0L
    private var lastSttWarmup = 0L
    private var llmWarmupJob: Job? = null
    private var sttWarmupJob: Job? = null

    companion object {
        private const val TAG = "WarmupManager"
        private const val WARMUP_COOLDOWN_MS = 240_000L // 4 min cooldown (server keeps models 5 min)
        private const val WARMUP_DEBOUNCE_MS = 300L // Debounce rapid triggers
    }

    /**
     * Trigger LLM warmup. Call when user starts typing or when voice input is sent to STT.
     */
    fun warmupLlm() {
        val now = System.currentTimeMillis()
        if (now - lastLlmWarmup < WARMUP_COOLDOWN_MS) {
            Log.d(TAG, "LLM warmup skipped (cooldown)")
            return
        }

        llmWarmupJob?.cancel()
        llmWarmupJob = scope.launch {
            delay(WARMUP_DEBOUNCE_MS)
            doLlmWarmup()
        }
    }

    /**
     * Trigger STT warmup. Call when user initiates voice input.
     */
    fun warmupStt() {
        val now = System.currentTimeMillis()
        if (now - lastSttWarmup < WARMUP_COOLDOWN_MS) {
            Log.d(TAG, "STT warmup skipped (cooldown)")
            return
        }

        sttWarmupJob?.cancel()
        sttWarmupJob = scope.launch {
            delay(WARMUP_DEBOUNCE_MS)
            doSttWarmup()
        }
    }

    /**
     * Trigger both STT and LLM warmup for voice interactions.
     * STT warmup runs first, LLM warmup runs after a short delay.
     */
    fun warmupForVoice() {
        warmupStt()
        // LLM warmup will be triggered when audio is sent to STT
    }

    private suspend fun doLlmWarmup() {
        val config = settingsManager.getCategoryConfig(Category.AGENT).primary ?: return
        val endpoint = settingsManager.getEndpoint(config.endpointId) ?: return

        // Skip local endpoints
        if (config.endpointId == "local") return

        Log.d(TAG, "Starting LLM warmup for ${config.modelName}")
        lastLlmWarmup = System.currentTimeMillis()

        try {
            val baseUrl = normalizedBaseUrl(endpoint)
            val url = if (baseUrl.endsWith("/")) "${baseUrl}chat/completions"
                      else "${baseUrl}/chat/completions"

            val json = JSONObject().apply {
                put("model", config.modelName)
                put("max_tokens", 1)
                put("stream", false)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", "hi")
                    })
                })
            }

            val requestBuilder = Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .post(json.toString().toRequestBody("application/json".toMediaType()))

            if (endpoint.apiKey.isNotBlank()) {
                requestBuilder.header("Authorization", "Bearer ${endpoint.apiKey}")
            }

            val response = client.newCall(requestBuilder.build()).execute()
            response.use {
                if (it.isSuccessful) {
                    Log.d(TAG, "LLM warmup successful")
                } else {
                    Log.w(TAG, "LLM warmup failed: ${it.code}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "LLM warmup error", e)
        }
    }

    private suspend fun doSttWarmup() {
        val config = settingsManager.getCategoryConfig(Category.STT).primary ?: return
        val endpoint = settingsManager.getEndpoint(config.endpointId) ?: return

        // Skip local/system endpoints
        if (config.endpointId == "local" || config.endpointId == "system") return

        Log.d(TAG, "Starting STT warmup for ${config.modelName}")
        lastSttWarmup = System.currentTimeMillis()

        try {
            val baseUrl = normalizedBaseUrl(endpoint)
            val url = if (baseUrl.endsWith("/")) "${baseUrl}audio/transcriptions"
                      else "${baseUrl}/audio/transcriptions"

            // Create a minimal silent WAV file (44 bytes header + minimal samples)
            val silentWav = createMinimalSilentWav()

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", "warmup.wav", silentWav.toRequestBody("audio/wav".toMediaType()))
                .addFormDataPart("model", config.modelName)
                .build()

            val requestBuilder = Request.Builder()
                .url(url)
                .post(requestBody)

            if (endpoint.apiKey.isNotBlank()) {
                requestBuilder.header("Authorization", "Bearer ${endpoint.apiKey}")
            }

            val response = client.newCall(requestBuilder.build()).execute()
            response.use {
                if (it.isSuccessful) {
                    Log.d(TAG, "STT warmup successful")
                } else {
                    Log.w(TAG, "STT warmup failed: ${it.code}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "STT warmup error", e)
        }
    }

    /**
     * Creates a minimal valid WAV file with silence (about 0.1 seconds)
     */
    private fun createMinimalSilentWav(): ByteArray {
        val sampleRate = 16000
        val numChannels = 1
        val bitsPerSample = 16
        val numSamples = 1600 // 0.1 seconds at 16kHz
        val dataSize = numSamples * numChannels * (bitsPerSample / 8)
        val fileSize = 36 + dataSize

        return java.io.ByteArrayOutputStream().apply {
            // RIFF header
            write("RIFF".toByteArray())
            writeLittleEndianInt(fileSize)
            write("WAVE".toByteArray())

            // fmt chunk
            write("fmt ".toByteArray())
            writeLittleEndianInt(16) // chunk size
            writeLittleEndianShort(1) // PCM format
            writeLittleEndianShort(numChannels)
            writeLittleEndianInt(sampleRate)
            writeLittleEndianInt(sampleRate * numChannels * bitsPerSample / 8) // byte rate
            writeLittleEndianShort(numChannels * bitsPerSample / 8) // block align
            writeLittleEndianShort(bitsPerSample)

            // data chunk
            write("data".toByteArray())
            writeLittleEndianInt(dataSize)
            // Write silence (zeros)
            write(ByteArray(dataSize))
        }.toByteArray()
    }

    private fun java.io.ByteArrayOutputStream.writeLittleEndianInt(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
        write((value shr 16) and 0xFF)
        write((value shr 24) and 0xFF)
    }

    private fun java.io.ByteArrayOutputStream.writeLittleEndianShort(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
    }

    private fun normalizedBaseUrl(endpoint: Endpoint): String {
        var base = endpoint.baseUrl.trim()
        if (endpoint.type == "openai" && !base.endsWith("/v1") && !base.endsWith("/v1/")) {
            base = base.trimEnd('/')
            base = "$base/v1"
        }
        return base
    }

    /**
     * Reset warmup cooldowns. Call if endpoint configuration changes.
     */
    fun resetCooldowns() {
        lastLlmWarmup = 0L
        lastSttWarmup = 0L
    }

    fun release() {
        llmWarmupJob?.cancel()
        sttWarmupJob?.cancel()
        scope.cancel()
    }
}
