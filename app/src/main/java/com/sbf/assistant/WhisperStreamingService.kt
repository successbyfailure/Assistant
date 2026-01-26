package com.sbf.assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.sbf.assistant.whisper.WhisperEngineJava
import com.sbf.assistant.whisper.WhisperUtil
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Real-time streaming transcription service using Whisper TFLite.
 *
 * Continuously captures audio and transcribes it in chunks, providing
 * incremental transcription results.
 *
 * Usage:
 * ```
 * val service = WhisperStreamingService(context, whisperEngine)
 * service.transcriptionFlow.collect { text ->
 *     // Handle incremental transcription
 * }
 * service.startStreaming()
 * // ... later ...
 * service.stopStreaming()
 * ```
 */
class WhisperStreamingService(
    private val context: Context,
    private val whisperEngine: WhisperEngineJava,
    private val settings: SettingsManager
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Audio configuration
    private val sampleRate = WhisperUtil.WHISPER_SAMPLE_RATE // 16000 Hz
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_FLOAT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

    // Streaming configuration
    private val chunkDurationSeconds = 5 // Process audio every 5 seconds
    private val maxBufferSeconds = 30 // Maximum buffer (Whisper limit)
    private val samplesPerChunk = sampleRate * chunkDurationSeconds
    private val maxBufferSamples = sampleRate * maxBufferSeconds

    // State
    private var audioRecord: AudioRecord? = null
    private val isRecording = AtomicBoolean(false)
    private var recordingJob: Job? = null

    // Circular buffer for audio samples
    private val audioBuffer = FloatArray(maxBufferSamples)
    private var bufferWriteIndex = 0
    private var totalSamplesWritten = 0L
    private val bufferLock = Object()

    // Transcription state
    private var lastTranscription = ""
    private var lastProcessedSamples = 0L

    // Flow for emitting transcription results
    private val _transcriptionFlow = MutableSharedFlow<TranscriptionResult>(replay = 1)
    val transcriptionFlow: SharedFlow<TranscriptionResult> = _transcriptionFlow

    data class TranscriptionResult(
        val text: String,
        val isFinal: Boolean,
        val incrementalText: String,
        val durationMs: Long
    )

    interface TranscriptionCallback {
        fun onTranscription(result: TranscriptionResult)
        fun onError(error: String)
        fun onStateChanged(isRecording: Boolean)
    }

    private var callback: TranscriptionCallback? = null

    fun setCallback(callback: TranscriptionCallback?) {
        this.callback = callback
    }

    /**
     * Start streaming transcription.
     * Returns false if permissions are missing or audio cannot be initialized.
     */
    fun startStreaming(): Boolean {
        if (isRecording.get()) {
            Log.w(TAG, "Already recording")
            return true
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO permission not granted")
            callback?.onError("Permiso de micrófono no otorgado")
            return false
        }

        if (!whisperEngine.isInitialized) {
            Log.e(TAG, "Whisper engine not initialized")
            callback?.onError("Motor Whisper no inicializado")
            return false
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize * 2
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "Failed to initialize AudioRecord")
                callback?.onError("Error inicializando grabación de audio")
                return false
            }

            // Reset state
            synchronized(bufferLock) {
                bufferWriteIndex = 0
                totalSamplesWritten = 0L
                lastProcessedSamples = 0L
                lastTranscription = ""
            }

            audioRecord?.startRecording()
            isRecording.set(true)
            callback?.onStateChanged(true)

            // Start recording coroutine
            recordingJob = scope.launch {
                recordingLoop()
            }

            Log.d(TAG, "Streaming started (GPU=${whisperEngine.isUsingGpu})")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start streaming", e)
            callback?.onError("Error iniciando streaming: ${e.message}")
            cleanup()
            return false
        }
    }

    /**
     * Stop streaming and get final transcription.
     */
    fun stopStreaming(): String {
        if (!isRecording.get()) {
            return lastTranscription
        }

        isRecording.set(false)
        recordingJob?.cancel()
        recordingJob = null

        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioRecord", e)
        }

        cleanup()
        callback?.onStateChanged(false)

        // Process any remaining audio
        val finalText = processRemainingAudio()

        Log.d(TAG, "Streaming stopped. Final transcription: $finalText")
        return finalText
    }

    private suspend fun recordingLoop() {
        val readBuffer = FloatArray(bufferSize)
        var lastProcessTime = System.currentTimeMillis()

        while (isRecording.get()) {
            val readCount = audioRecord?.read(readBuffer, 0, readBuffer.size, AudioRecord.READ_BLOCKING) ?: -1

            if (readCount > 0) {
                synchronized(bufferLock) {
                    // Write to circular buffer
                    for (i in 0 until readCount) {
                        audioBuffer[bufferWriteIndex] = readBuffer[i]
                        bufferWriteIndex = (bufferWriteIndex + 1) % maxBufferSamples
                    }
                    totalSamplesWritten += readCount
                }

                // Check if we have enough samples to process
                val samplesAvailable = totalSamplesWritten - lastProcessedSamples
                val now = System.currentTimeMillis()
                val timeSinceLastProcess = now - lastProcessTime

                if (samplesAvailable >= samplesPerChunk || timeSinceLastProcess >= chunkDurationSeconds * 1000) {
                    processCurrentBuffer()
                    lastProcessTime = now
                }
            } else if (readCount < 0) {
                Log.e(TAG, "AudioRecord read error: $readCount")
                withContext(Dispatchers.Main) {
                    callback?.onError("Error leyendo audio")
                }
                break
            }

            // Small delay to prevent busy loop
            delay(10)
        }
    }

    private fun processCurrentBuffer() {
        val startTime = System.currentTimeMillis()

        // Get current buffer contents
        val samples = synchronized(bufferLock) {
            val available = minOf(totalSamplesWritten, maxBufferSamples.toLong()).toInt()
            if (available == 0) return

            val result = FloatArray(available)
            val startIndex = if (totalSamplesWritten > maxBufferSamples) {
                bufferWriteIndex
            } else {
                0
            }

            for (i in 0 until available) {
                result[i] = audioBuffer[(startIndex + i) % maxBufferSamples]
            }

            lastProcessedSamples = totalSamplesWritten
            result
        }

        // Transcribe
        try {
            val transcription = whisperEngine.transcribeBuffer(samples)
            val endTime = System.currentTimeMillis()

            if (transcription != null && transcription.isNotEmpty()) {
                // Calculate incremental text
                val incrementalText = if (transcription.startsWith(lastTranscription)) {
                    transcription.substring(lastTranscription.length)
                } else {
                    transcription
                }

                lastTranscription = transcription

                val result = TranscriptionResult(
                    text = transcription,
                    isFinal = false,
                    incrementalText = incrementalText,
                    durationMs = endTime - startTime
                )

                scope.launch(Dispatchers.Main) {
                    _transcriptionFlow.emit(result)
                    callback?.onTranscription(result)
                }

                Log.d(TAG, "Transcription (${endTime - startTime}ms): $incrementalText")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Transcription failed", e)
        }
    }

    private fun processRemainingAudio(): String {
        // Process any remaining audio in buffer
        processCurrentBuffer()
        return lastTranscription
    }

    private fun cleanup() {
        try {
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing AudioRecord", e)
        }
        audioRecord = null
    }

    fun isRecording(): Boolean = isRecording.get()

    fun destroy() {
        stopStreaming()
        scope.cancel()
    }

    companion object {
        private const val TAG = "WhisperStreaming"
    }
}
