package com.sbf.assistant

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File

class TtsController(
    context: Context,
    private val settingsManager: SettingsManager
) {
    private val appContext = context.applicationContext
    private val ttsManager = TTSManager(appContext)
    private var ttsMediaPlayer: MediaPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    var enabled: Boolean
        get() = settingsManager.ttsAutoReadEnabled
        set(value) {
            settingsManager.ttsAutoReadEnabled = value
            if (!value) {
                stop()
            }
        }

    fun speak(text: String) {
        if (!enabled) return
        val ttsPref = settingsManager.getCategoryConfig(Category.TTS).primary
        if (ttsPref == null || ttsPref.endpointId == "system") {
            ttsManager.speak(text)
            return
        }
        val endpoint = settingsManager.getEndpoint(ttsPref.endpointId) ?: return
        OpenAiClient(endpoint).generateSpeech(text, ttsPref.modelName) { file, error ->
            if (file != null) {
                playFile(file)
            } else if (error != null) {
                Log.w(TAG, "TTS generation failed: ${error.message}")
            }
        }
    }

    fun stop() {
        ttsManager.stop()
        ttsMediaPlayer?.stop()
        ttsMediaPlayer?.release()
        ttsMediaPlayer = null
    }

    fun release() {
        stop()
        ttsManager.release()
    }

    private fun playFile(file: File) {
        mainHandler.post {
            try {
                ttsMediaPlayer?.release()
                ttsMediaPlayer = MediaPlayer().apply {
                    setOnErrorListener { mp, what, extra ->
                        Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                        mp.release()
                        ttsMediaPlayer = null
                        true
                    }
                    setOnCompletionListener { mp ->
                        mp.release()
                        ttsMediaPlayer = null
                        cleanupFile(file)
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
            }
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
