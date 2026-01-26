package com.sbf.assistant

import android.speech.RecognitionService
import android.content.Intent
import android.os.Bundle
import android.speech.SpeechRecognizer

class AssistantRecognitionService : RecognitionService() {
    override fun onStartListening(intent: Intent?, listener: Callback?) {
        // Implement speech recognition logic here
    }

    override fun onCancel(listener: Callback?) {
        // Stop listening
    }

    override fun onStopListening(listener: Callback?) {
        // Stop listening and process results
    }
}
