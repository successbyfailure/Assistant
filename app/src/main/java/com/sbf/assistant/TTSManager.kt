package com.sbf.assistant

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

class TTSManager(context: Context) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech = TextToSpeech(context, this)
    private var isInitialized = false
    private var onCompleteCallback: (() -> Unit)? = null

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.getDefault()
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    onCompleteCallback?.invoke()
                    onCompleteCallback = null
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    onCompleteCallback?.invoke()
                    onCompleteCallback = null
                }
            })
            isInitialized = true
        }
    }

    fun speak(text: String, utteranceId: String = "assistant_speech", onComplete: (() -> Unit)? = null) {
        onCompleteCallback = onComplete
        if (isInitialized) {
            tts.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
        } else {
            onComplete?.invoke()
        }
    }

    fun stop() {
        tts.stop()
    }

    fun release() {
        tts.shutdown()
    }
}
