package com.sbf.assistant

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

class TTSManager(context: Context) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech = TextToSpeech(context, this)
    private var isInitialized = false

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.getDefault()
            isInitialized = true
        }
    }

    fun speak(text: String, utteranceId: String = "assistant_speech") {
        if (isInitialized) {
            tts.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
        }
    }

    fun stop() {
        tts.stop()
    }

    fun release() {
        tts.shutdown()
    }
}
