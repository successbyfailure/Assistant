package com.sbf.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Captura audio optimizado para STT usando VOICE_RECOGNITION.
 * Implementa detección de silencios y calibración inicial de ruido para un feedback limpio.
 */
class AudioRecorder(private val context: Context) {
    private var mediaRecorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var audioRecord: AudioRecord? = null
    private var recordThread: Thread? = null
    private var pcmBytesWritten = 0
    private var isPcmRecording = false
    private var autoStopOnSilence = false
    private var silenceDurationMs: Long = 0
    private var minSpeechMs: Long = 0
    private var silenceThreshold = 0.0f
    private var onSilenceDetected: (() -> Unit)? = null
    private var silenceTriggered = false
    private var calibrateNoiseMs: Long = 0
    private var onReadyToSpeak: (() -> Unit)? = null
    private var onAmplitudeUpdate: ((Float) -> Unit)? = null

    fun startRecording(
        usePcm: Boolean = false,
        autoStopOnSilence: Boolean = false,
        silenceDurationMs: Long = 1100, 
        minSpeechMs: Long = 300,      
        silenceThreshold: Float = 0.01f, 
        calibrateNoiseMs: Long = 400,  
        onSilenceDetected: (() -> Unit)? = null,
        onReadyToSpeak: (() -> Unit)? = null,
        onAmplitudeUpdate: ((Float) -> Unit)? = null
    ): File? {
        try {
            this.autoStopOnSilence = autoStopOnSilence
            this.silenceDurationMs = silenceDurationMs
            this.minSpeechMs = minSpeechMs
            this.silenceThreshold = silenceThreshold
            this.calibrateNoiseMs = calibrateNoiseMs
            this.onSilenceDetected = onSilenceDetected
            this.onReadyToSpeak = onReadyToSpeak
            this.onAmplitudeUpdate = onAmplitudeUpdate
            this.silenceTriggered = false
            
            if (usePcm) {
                return startPcmRecording()
            }

            currentFile = File.createTempFile("whisper_input", ".m4a", context.cacheDir)
            if (!setupRecorder(currentFile!!)) return null
            mediaRecorder?.start()
            
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                onReadyToSpeak?.invoke()
            }, 150)
            
            return currentFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            cleanup()
            return null
        }
    }

    private fun setupRecorder(file: File): Boolean {
        return try {
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(file.absolutePath)
                prepare()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup MediaRecorder", e)
            mediaRecorder?.release()
            mediaRecorder = null
            false
        }
    }

    companion object {
        private const val TAG = "AudioRecorder"
    }

    fun stopRecording() {
        synchronized(this) {
            if (isPcmRecording) {
                stopPcmRecording()
            } else {
                try { mediaRecorder?.stop() } catch (_: Exception) {}
                try { mediaRecorder?.release() } catch (_: Exception) {}
                mediaRecorder = null
            }
        }
    }

    fun cleanup() {
        synchronized(this) {
            stopRecording()
            currentFile?.let {
                try { if (it.exists()) it.delete() } catch (_: Exception) {}
            }
            currentFile = null
            onSilenceDetected = null
            onReadyToSpeak = null
            onAmplitudeUpdate = null
        }
    }

    fun deleteFile(file: File) {
        try { if (file.exists()) file.delete() } catch (_: Exception) {}
    }

    @SuppressLint("MissingPermission") // Permission is checked before calling this method
    private fun startPcmRecording(): File? {
        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (minBuffer == AudioRecord.ERROR || minBuffer == AudioRecord.ERROR_BAD_VALUE) return null
        val bufferSize = minBuffer * 2
        val recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate, channelConfig, audioFormat, bufferSize)
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return null
        }
        currentFile = File.createTempFile("whisper_input", ".wav", context.cacheDir)
        pcmBytesWritten = 0
        isPcmRecording = true
        audioRecord = recorder
        writeWavHeader(currentFile!!, sampleRate, 1, 16, 0)
        recorder.startRecording()
        
        recordThread = Thread {
            val buffer = ByteArray(bufferSize)
            var speechDetected = false
            var speechStartMs = 0L
            var lastVoiceMs = 0L
            var calibrating = true 
            var calibrateStartMs = 0L
            var calibrateRmsSum = 0.0
            var calibrateCount = 0
            
            FileOutputStream(currentFile!!, true).use { output ->
                while (isPcmRecording) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        output.write(buffer, 0, read)
                        pcmBytesWritten += read
                        val rms = computeRms(buffer, read)
                        android.os.Handler(android.os.Looper.getMainLooper()).post { onAmplitudeUpdate?.invoke(rms) }

                        val now = System.currentTimeMillis()
                        if (calibrating) {
                            if (calibrateStartMs == 0L) calibrateStartMs = now
                            calibrateRmsSum += rms.toDouble()
                            calibrateCount++
                            if (now - calibrateStartMs >= calibrateNoiseMs) {
                                val avg = (calibrateRmsSum / calibrateCount.coerceAtLeast(1)).toFloat()
                                // Umbral más alto (3.5x) para detectar mejor el fin de habla en ambientes reales
                                silenceThreshold = (avg * 3.5f).coerceIn(0.012f, 0.06f)
                                calibrating = false
                                android.os.Handler(android.os.Looper.getMainLooper()).post { onReadyToSpeak?.invoke() }
                            }
                            continue
                        }

                        if (autoStopOnSilence && !silenceTriggered) {
                            if (rms >= silenceThreshold) {
                                if (!speechDetected) {
                                    speechDetected = true
                                    speechStartMs = now
                                }
                                lastVoiceMs = now
                            } else if (speechDetected && lastVoiceMs > 0L) {
                                val silenceMs = now - lastVoiceMs
                                val speechMs = now - speechStartMs
                                if (speechMs >= minSpeechMs && silenceMs >= silenceDurationMs) {
                                    silenceTriggered = true
                                    android.os.Handler(android.os.Looper.getMainLooper()).post { onSilenceDetected?.invoke() }
                                }
                            }
                        }
                    }
                }
            }
        }.also { it.start() }
        return currentFile
    }

    private fun stopPcmRecording() {
        isPcmRecording = false
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        try { recordThread?.join(500) } catch (_: Exception) {}
        recordThread = null
        currentFile?.let { updateWavHeader(it, pcmBytesWritten) }
    }

    private fun writeWavHeader(file: File, sampleRate: Int, channels: Int, bitsPerSample: Int, dataLength: Int) {
        FileOutputStream(file).use { output ->
            val byteRate = sampleRate * channels * bitsPerSample / 8
            val blockAlign = channels * bitsPerSample / 8
            val header = ByteArray(44)
            fun putString(offset: Int, value: String) {
                value.toByteArray().forEachIndexed { i, b -> header[offset + i] = b }
            }
            fun putInt(offset: Int, value: Int) {
                header[offset] = (value and 0xFF).toByte()
                header[offset + 1] = ((value shr 8) and 0xFF).toByte()
                header[offset + 2] = ((value shr 16) and 0xFF).toByte()
                header[offset + 3] = ((value shr 24) and 0xFF).toByte()
            }
            fun putShort(offset: Int, value: Int) {
                header[offset] = (value and 0xFF).toByte()
                header[offset + 1] = ((value shr 8) and 0xFF).toByte()
            }
            putString(0, "RIFF"); putInt(4, 36 + dataLength); putString(8, "WAVE")
            putString(12, "fmt "); putInt(16, 16); putShort(20, 1); putShort(22, channels)
            putInt(24, sampleRate); putInt(28, byteRate); putShort(32, blockAlign); putShort(34, bitsPerSample)
            putString(36, "data"); putInt(40, dataLength)
            output.write(header, 0, 44)
        }
    }

    private fun updateWavHeader(file: File, dataLength: Int) {
        try {
            RandomAccessFile(file, "rw").use { raf ->
                raf.seek(4); raf.write(intToByteArray(36 + dataLength))
                raf.seek(40); raf.write(intToByteArray(dataLength))
            }
        } catch (_: Exception) {}
    }

    private fun intToByteArray(value: Int): ByteArray {
        return byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte(), ((value shr 16) and 0xFF).toByte(), ((value shr 24) and 0xFF).toByte())
    }

    private fun computeRms(buffer: ByteArray, length: Int): Float {
        var sumSquares = 0.0
        var i = 0
        while (i + 1 < length) {
            val sample = (buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)
            val s = sample.toShort().toInt()
            sumSquares += (s * s).toDouble()
            i += 2
        }
        val sampleCount = (length / 2).coerceAtLeast(1)
        return (kotlin.math.sqrt(sumSquares / sampleCount) / 32768.0).toFloat()
    }
}
