package com.sbf.assistant

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.util.ArrayDeque

class TtsController(
    context: Context,
    private val settingsManager: SettingsManager
) {
    private val appContext = context.applicationContext
    private val ttsManager = TTSManager(appContext)
    private var ttsMediaPlayer: MediaPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ttsQueue: ArrayDeque<String> = ArrayDeque()
    private var queueEndpoint: Endpoint? = null
    private var queueModelName: String? = null
    private var remoteBusy = false
    private var systemBusy = false
    private val streamingBuffer = StringBuilder()
    private var streamingFinalPending = false
    private val streamHandler = Handler(Looper.getMainLooper())
    private val streamFlushDelayMs = 800L
    private val streamMinChunkChars = 40
    private val streamFlushRunnable = Runnable { emitStreamingChunks(flushAll = false, allowPartial = true) }
    private val drainHandler = Handler(Looper.getMainLooper())
    private val drainDelayMs = 300L
    private val drainRunnable = Runnable { kickDrain() }

    var enabled: Boolean
        get() = settingsManager.ttsAutoReadEnabled
        set(value) {
            settingsManager.ttsAutoReadEnabled = value
            if (!value) {
                stop()
            }
        }

    private var onSpeechComplete: (() -> Unit)? = null

    fun speak(text: String, onComplete: (() -> Unit)? = null) {
        if (settingsManager.ttsChunkOnPunctuation) {
            stop()
        }
        onSpeechComplete = onComplete
        if (!enabled) {
            onComplete?.invoke()
            return
        }
        val ttsPref = settingsManager.getCategoryConfig(Category.TTS).primary
        if (ttsPref == null || ttsPref.endpointId == "system") {
            val chunks = if (settingsManager.ttsChunkOnPunctuation) splitForTts(text) else listOf(text)
            if (chunks.size <= 1) {
                ttsManager.speak(text, onComplete = {
                    mainHandler.post { onSpeechComplete?.invoke(); onSpeechComplete = null }
                })
            } else {
                chunks.forEachIndexed { index, chunk ->
                    val isLast = index == chunks.lastIndex
                    ttsManager.speak(chunk, onComplete = if (isLast) {
                        {
                            mainHandler.post { onSpeechComplete?.invoke(); onSpeechComplete = null }
                        }
                    } else null)
                }
            }
            return
        }
        val endpoint = settingsManager.getEndpoint(ttsPref.endpointId) ?: run {
            onComplete?.invoke()
            return
        }
        if (settingsManager.ttsChunkOnPunctuation) {
            val chunks = splitForTts(text)
            if (chunks.isEmpty()) {
                onComplete?.invoke()
                return
            }
            ttsQueue.clear()
            ttsQueue.addAll(chunks)
            queueEndpoint = endpoint
            queueModelName = ttsPref.modelName
            streamingFinalPending = true
            generateNextChunk()
            return
        }
        OpenAiClient(endpoint).generateSpeech(text, ttsPref.modelName) { file, error ->
            if (file != null) {
                playFile(file) {
                    mainHandler.post { onSpeechComplete?.invoke(); onSpeechComplete = null }
                }
            } else {
                Log.w(TAG, "TTS generation failed: ${error?.message}")
                mainHandler.post { onSpeechComplete?.invoke(); onSpeechComplete = null }
            }
        }
    }

    fun stop() {
        ttsManager.stop()
        ttsMediaPlayer?.stop()
        ttsMediaPlayer?.release()
        ttsMediaPlayer = null
        onSpeechComplete = null
        ttsQueue.clear()
        queueEndpoint = null
        queueModelName = null
        remoteBusy = false
        systemBusy = false
        streamingBuffer.clear()
        streamingFinalPending = false
        streamHandler.removeCallbacks(streamFlushRunnable)
        drainHandler.removeCallbacks(drainRunnable)
    }

    fun release() {
        stop()
        ttsManager.release()
    }

    private fun playFile(file: File, onComplete: () -> Unit) {
        mainHandler.post {
            try {
                ttsMediaPlayer?.release()
                ttsMediaPlayer = MediaPlayer().apply {
                    setOnErrorListener { mp, what, extra ->
                        Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                        mp.release()
                        ttsMediaPlayer = null
                        onComplete()
                        true
                    }
                    setOnCompletionListener { mp ->
                        mp.release()
                        ttsMediaPlayer = null
                        cleanupFile(file)
                        onComplete()
                    }
                    setDataSource(file.absolutePath)
                    prepare()
                    start()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to play TTS audio", e)
                ttsMediaPlayer?.release()
                ttsMediaPlayer = null
                cleanupFile(file)
                onComplete()
            }
        }
    }

    private fun generateNextChunk() {
        val endpoint = queueEndpoint
        val modelName = queueModelName
        val next = ttsQueue.pollFirst()
        if (endpoint == null || modelName == null || next == null) {
            if (streamingFinalPending && ttsQueue.isEmpty()) {
                streamingFinalPending = false
                mainHandler.post { onSpeechComplete?.invoke(); onSpeechComplete = null }
            }
            return
        }
        if (remoteBusy) return
        remoteBusy = true
        OpenAiClient(endpoint).generateSpeech(next, modelName) { file, error ->
            if (file != null) {
                playFile(file) {
                    remoteBusy = false
                    generateNextChunk()
                }
            } else {
                Log.w(TAG, "TTS generation failed: ${error?.message}")
                remoteBusy = false
                generateNextChunk()
            }
        }
    }

    private fun generateNextSystemChunk() {
        val next = ttsQueue.pollFirst() ?: run {
            if (streamingFinalPending && ttsQueue.isEmpty()) {
                streamingFinalPending = false
                mainHandler.post { onSpeechComplete?.invoke(); onSpeechComplete = null }
            }
            return
        }
        if (systemBusy) return
        systemBusy = true
        ttsManager.speak(next, onComplete = {
            systemBusy = false
            generateNextSystemChunk()
        })
    }

    fun startStreaming(onComplete: (() -> Unit)? = null) {
        stop()
        onSpeechComplete = onComplete
        streamingBuffer.clear()
        streamingFinalPending = false
        streamHandler.removeCallbacks(streamFlushRunnable)
        drainHandler.removeCallbacks(drainRunnable)
    }

    fun feedStreaming(text: String, isFinal: Boolean) {
        if (!enabled) return
        if (text.isNotEmpty()) {
            streamingBuffer.append(text)
        }
        if (isFinal) {
            streamingFinalPending = true
        }
        emitStreamingChunks(flushAll = isFinal, allowPartial = false)
        if (!isFinal) {
            streamHandler.removeCallbacks(streamFlushRunnable)
            streamHandler.postDelayed(streamFlushRunnable, streamFlushDelayMs)
        }
        kickDrain()
    }

    private fun emitStreamingChunks(flushAll: Boolean, allowPartial: Boolean) {
        val maxLen = settingsManager.ttsChunkMaxLength.coerceAtLeast(20)
        val separators = settingsManager.ttsChunkSeparators
        val separatorSet = if (separators.isNotEmpty()) separators.toSet() else setOf('.', ',', '/', ';', ':', '!', '?')
        while (true) {
            val chunk = pullNextChunk(separatorSet, maxLen, flushAll, allowPartial) ?: break
            ttsQueue.add(chunk)
        }
        kickDrain()
    }

    private fun pullNextChunk(separatorSet: Set<Char>, maxLen: Int, flushAll: Boolean, allowPartial: Boolean): String? {
        if (streamingBuffer.isEmpty()) return null
        var cutIndex = -1
        for (i in 0 until streamingBuffer.length) {
            if (separatorSet.contains(streamingBuffer[i])) {
                cutIndex = i
                break
            }
        }
        if (cutIndex == -1 && streamingBuffer.length >= maxLen) {
            cutIndex = maxLen - 1
        }
        if (cutIndex == -1 && allowPartial && streamingBuffer.length >= streamMinChunkChars) {
            cutIndex = minOf(streamingBuffer.length, maxLen) - 1
        }
        if (cutIndex == -1) {
            if (!flushAll) return null
            cutIndex = streamingBuffer.length - 1
        }
        val piece = streamingBuffer.substring(0, cutIndex + 1).trim()
        streamingBuffer.delete(0, cutIndex + 1)
        return if (piece.isNotBlank()) piece else pullNextChunk(separatorSet, maxLen, flushAll, allowPartial)
    }

    private fun splitForTts(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val maxLen = settingsManager.ttsChunkMaxLength.coerceAtLeast(20)
        val separators = settingsManager.ttsChunkSeparators
        val separatorSet = if (separators.isNotEmpty()) separators.toSet() else setOf('.', ',', '/', ';', ':', '!', '?')
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        for (ch in text) {
            current.append(ch)
            if (separatorSet.contains(ch)) {
                val piece = current.toString().trim()
                if (piece.isNotEmpty()) {
                    chunks.add(piece)
                    current.clear()
                }
            } else if (current.length >= maxLen) {
                val piece = current.toString().trim()
                if (piece.isNotEmpty()) {
                    chunks.add(piece)
                    current.clear()
                }
            }
        }
        val tail = current.toString().trim()
        if (tail.isNotEmpty()) {
            chunks.add(tail)
        }
        return chunks
    }

    private fun kickDrain() {
        val ttsPref = settingsManager.getCategoryConfig(Category.TTS).primary
        if (ttsPref == null || ttsPref.endpointId == "system") {
            queueEndpoint = null
            queueModelName = null
            if (!systemBusy && ttsQueue.isNotEmpty()) {
                generateNextSystemChunk()
            }
            if (systemBusy && ttsQueue.isNotEmpty()) {
                drainHandler.removeCallbacks(drainRunnable)
                drainHandler.postDelayed(drainRunnable, drainDelayMs)
            }
        } else {
            queueEndpoint = settingsManager.getEndpoint(ttsPref.endpointId)
            queueModelName = ttsPref.modelName
            if (!remoteBusy && ttsQueue.isNotEmpty() && queueEndpoint != null) {
                generateNextChunk()
            }
            if (remoteBusy && ttsQueue.isNotEmpty()) {
                drainHandler.removeCallbacks(drainRunnable)
                drainHandler.postDelayed(drainRunnable, drainDelayMs)
            }
        }
        if (streamingFinalPending && ttsQueue.isEmpty() && !remoteBusy && !systemBusy) {
            streamingFinalPending = false
            mainHandler.post { onSpeechComplete?.invoke(); onSpeechComplete = null }
        }
    }

    private fun cleanupFile(file: File) {
        try {
            file.delete()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete TTS temp file", e)
        }
    }

    companion object {
        private const val TAG = "TtsController"
    }
}
