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
        
        // Check if we are actually the active assistant
        if (isActiveService(this, ComponentName(this, AssistantVoiceInteractionService::class.java))) {
            Log.i(TAG, "Assistant is ACTIVE and READY")
        } else {
            Log.w(TAG, "Assistant is NOT the active service in settings")
        }
    }

    override fun onLaunchVoiceAssistFromKeyguard() {
        Log.d(TAG, "onLaunchVoiceAssistFromKeyguard()")
        showSession(Bundle(), VoiceInteractionSession.SHOW_WITH_ASSIST)
    }

    override fun onShowSessionFailed(args: Bundle) {
        Log.e(TAG, "onShowSessionFailed() args=$args")
        super.onShowSessionFailed(args)
    }
}
