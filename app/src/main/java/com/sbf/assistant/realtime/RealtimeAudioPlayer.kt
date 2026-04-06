package com.sbf.assistant.realtime

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

class RealtimeAudioPlayer {
    private var audioTrack: AudioTrack? = null

    fun start() {
        if (audioTrack != null) return
        val bufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(SAMPLE_RATE)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build().apply {
                setVolume(AudioTrack.getMaxVolume())
                play()
            }
    }

    fun write(pcm16Bytes: ByteArray) {
        val track = audioTrack ?: return
        track.write(pcm16Bytes, 0, pcm16Bytes.size, AudioTrack.WRITE_NON_BLOCKING)
    }

    fun stop() {
        val track = audioTrack ?: return
        try {
            track.stop()
        } catch (_: Exception) {
        }
        track.flush()
        track.release()
        audioTrack = null
    }

    fun clear() {
        val track = audioTrack ?: return
        try {
            track.pause()
            track.flush()
            track.play()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val SAMPLE_RATE = 16000
    }
}
