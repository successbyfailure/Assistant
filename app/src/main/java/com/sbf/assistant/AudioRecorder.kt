package com.sbf.assistant

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

class AudioRecorder(private val context: Context) {
    private var mediaRecorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var isChunking = false
    private var chunkHandler = Handler(Looper.getMainLooper())
    private var onChunkReady: ((File) -> Unit)? = null
    private val oldFiles = mutableListOf<File>()
    private var audioRecord: AudioRecord? = null
    private var recordThread: Thread? = null
    private var pcmBytesWritten = 0
    private var isPcmRecording = false

    fun startRecording(
        chunkDurationMs: Long = 0,
        onChunk: ((File) -> Unit)? = null,
        usePcm: Boolean = false
    ): File? {
        try {
            onChunkReady = onChunk
            if (usePcm) {
                isChunking = false
                return startPcmRecording()
            }

            isChunking = chunkDurationMs > 0
            currentFile = File.createTempFile("whisper_input", ".m4a", context.cacheDir)

            if (!setupRecorder(currentFile!!)) {
                return null
            }
            mediaRecorder?.start()

            if (isChunking) {
                startChunkTimer(chunkDurationMs)
            }

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
                setAudioSource(MediaRecorder.AudioSource.MIC)
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

    private fun startChunkTimer(duration: Long) {
        chunkHandler.postDelayed(object : Runnable {
            override fun run() {
                if (mediaRecorder != null) {
                    emitCurrentChunk()
                    startChunkTimer(duration)
                }
            }
        }, duration)
    }

    private fun emitCurrentChunk() {
        synchronized(this) {
            try {
                // En MP4/AAC es complejo hacer chunks reales sin cerrar el archivo.
                // Por ahora, para el prototipo de chunking remoto, cerraremos y reabriremos
                // rápidamente o usaremos un formato más amigable como WAV en el futuro.
                // Nota: El chunking en MP4 requiere reconstruir cabeceras.
                mediaRecorder?.stop()
                mediaRecorder?.release()
                mediaRecorder = null

                currentFile?.let {
                    onChunkReady?.invoke(it)
                    oldFiles.add(it)
                }

                currentFile = File.createTempFile("whisper_chunk", ".m4a", context.cacheDir)
                if (setupRecorder(currentFile!!)) {
                    mediaRecorder?.start()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error emitting chunk", e)
                cleanup()
            }
        }
    }

    fun stopRecording() {
        synchronized(this) {
            chunkHandler.removeCallbacksAndMessages(null)
            if (isPcmRecording) {
                stopPcmRecording()
            } else {
                try {
                    mediaRecorder?.stop()
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping MediaRecorder", e)
                }
                try {
                    mediaRecorder?.release()
                } catch (e: Exception) {
                    Log.e(TAG, "Error releasing MediaRecorder", e)
                }
                mediaRecorder = null
            }
        }
    }

    fun cleanup() {
        synchronized(this) {
            stopRecording()
            currentFile?.let {
                try {
                    if (it.exists()) it.delete()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to delete current file", e)
                }
            }
            currentFile = null

            oldFiles.forEach {
                try {
                    if (it.exists()) it.delete()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to delete old file", e)
                }
            }
            oldFiles.clear()
            onChunkReady = null
        }
    }

    fun deleteFile(file: File) {
        try {
            if (file.exists()) {
                file.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete file: ${file.name}", e)
        }
    }

    private fun startPcmRecording(): File? {
        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (minBuffer == AudioRecord.ERROR || minBuffer == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Invalid AudioRecord buffer size")
            return null
        }
        val bufferSize = minBuffer * 2
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed")
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
            FileOutputStream(currentFile!!, true).use { output ->
                while (isPcmRecording) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        output.write(buffer, 0, read)
                        pcmBytesWritten += read
                    }
                }
            }
        }.also { it.start() }
        return currentFile
    }

    private fun stopPcmRecording() {
        isPcmRecording = false
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord", e)
        }
        try {
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AudioRecord", e)
        }
        audioRecord = null
        try {
            recordThread?.join(500)
        } catch (e: Exception) {
            Log.w(TAG, "Error joining record thread", e)
        }
        recordThread = null
        currentFile?.let { updateWavHeader(it, pcmBytesWritten) }
    }

    private fun writeWavHeader(file: File, sampleRate: Int, channels: Int, bitsPerSample: Int, dataLength: Int) {
        FileOutputStream(file).use { output ->
            val byteRate = sampleRate * channels * bitsPerSample / 8
            val blockAlign = channels * bitsPerSample / 8
            val header = ByteArray(44)
            fun putString(offset: Int, value: String) {
                val bytes = value.toByteArray()
                for (i in bytes.indices) {
                    header[offset + i] = bytes[i]
                }
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
            putString(0, "RIFF")
            putInt(4, 36 + dataLength)
            putString(8, "WAVE")
            putString(12, "fmt ")
            putInt(16, 16)
            putShort(20, 1)
            putShort(22, channels)
            putInt(24, sampleRate)
            putInt(28, byteRate)
            putShort(32, blockAlign)
            putShort(34, bitsPerSample)
            putString(36, "data")
            putInt(40, dataLength)
            output.write(header, 0, 44)
        }
    }

    private fun updateWavHeader(file: File, dataLength: Int) {
        try {
            RandomAccessFile(file, "rw").use { raf ->
                raf.seek(4)
                raf.write(intToByteArray(36 + dataLength))
                raf.seek(40)
                raf.write(intToByteArray(dataLength))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update WAV header", e)
        }
    }

    private fun intToByteArray(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte()
        )
    }
}
