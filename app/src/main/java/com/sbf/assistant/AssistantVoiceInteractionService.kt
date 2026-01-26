package com.sbf.assistant

import android.service.voice.VoiceInteractionService

class AssistantVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        // The service is ready. 
        // Note: To trigger the assistant from a hardware button or long-press home,
        // the system usually handles the session lifecycle automatically once
        // this app is set as the default Digital Assistant.
    }

    override fun onShutdown() {
        super.onShutdown()
    }
}
