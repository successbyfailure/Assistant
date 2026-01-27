package com.sbf.assistant

import android.content.ComponentName
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.util.Log

class AssistantVoiceInteractionService : VoiceInteractionService() {

    companion object {
        private const val TAG = "AssistantVIS"
    }

    override fun onReady() {
        super.onReady()
        Log.d(TAG, "VoiceInteractionService onReady()")

        // Check if we're the active assistant
        val isActive = isActiveService(this, ComponentName(this, AssistantVoiceInteractionService::class.java))
        Log.d(TAG, "Is active service: $isActive")
    }

    override fun onShutdown() {
        Log.d(TAG, "VoiceInteractionService onShutdown()")
        super.onShutdown()
    }

    override fun onLaunchVoiceAssistFromKeyguard() {
        Log.d(TAG, "onLaunchVoiceAssistFromKeyguard() - launching session")
        showSession(Bundle(), VoiceInteractionSession.SHOW_WITH_ASSIST)
    }

    override fun onShowSessionFailed(args: Bundle) {
        Log.e(TAG, "onShowSessionFailed() args=$args")
        super.onShowSessionFailed(args)
    }
}
