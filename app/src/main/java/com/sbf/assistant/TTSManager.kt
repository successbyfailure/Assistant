package com.sbf.assistant

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class TTSManager(context: Context) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech = TextToSpeech(context, this)
    private var isInitialized = false
    private val callbacks = ConcurrentHashMap<String, () -> Unit>()
    private val utteranceCounter = AtomicInteger(0)

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.getDefault()
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    utteranceId?.let { callbacks.remove(it)?.invoke() }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    utteranceId?.let { callbacks.remove(it)?.invoke() }
                }
            })
            isInitialized = true
        }
    }

    fun speak(text: String, utteranceId: String = "assistant_speech", onComplete: (() -> Unit)? = null) {
        val id = if (utteranceId == "assistant_speech") {
            "assistant_speech_${utteranceCounter.incrementAndGet()}"
        } else {
            utteranceId
        }
        if (onComplete != null) {
            callbacks[id] = onComplete
        }
        if (isInitialized) {
            tts.speak(text, TextToSpeech.QUEUE_ADD, null, id)
        } else {
            callbacks.remove(id)?.invoke()
        }
    }

    fun stop() {
        tts.stop()
    }

    fun release() {
        tts.shutdown()
    }
}
