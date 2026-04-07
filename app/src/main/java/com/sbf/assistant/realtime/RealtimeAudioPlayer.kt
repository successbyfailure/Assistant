package com.sbf.assistant.realtime

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

class RealtimeAudioPlayer {
    private var audioTrack: AudioTrack? = null
    private var totalFramesWritten = 0L
    private var pendingCompleteCallback: (() -> Unit)? = null

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
        totalFramesWritten = 0L
        pendingCompleteCallback = null
    }

    fun write(pcm16Bytes: ByteArray) {
        val track = audioTrack ?: return
        track.write(pcm16Bytes, 0, pcm16Bytes.size, AudioTrack.WRITE_NON_BLOCKING)
        // PCM16 = 2 bytes per frame (sample)
        totalFramesWritten += pcm16Bytes.size / 2L
    }

    /**
     * Called when the server signals all audio has been sent.
     * Sets a playback marker so [onComplete] fires only when the AudioTrack
     * has actually finished rendering all buffered data.
     */
    fun markEndOfStream(onComplete: () -> Unit) {
        val track = audioTrack
        if (track == null || totalFramesWritten <= 0L) {
            onComplete()
            return
        }
        pendingCompleteCallback = onComplete
        val markerFrame = totalFramesWritten.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        track.setNotificationMarkerPosition(markerFrame)
        track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(track: AudioTrack) {
                val cb = pendingCompleteCallback
                pendingCompleteCallback = null
                track.setPlaybackPositionUpdateListener(null)
                cb?.invoke()
            }
            override fun onPeriodicNotification(track: AudioTrack) = Unit
        })
    }

    fun stop() {
        pendingCompleteCallback = null
        val track = audioTrack ?: return
        try { track.setPlaybackPositionUpdateListener(null) } catch (_: Exception) {}
        try { track.stop() } catch (_: Exception) {}
        track.flush()
        track.release()
        audioTrack = null
        totalFramesWritten = 0L
    }

    fun clear() {
        pendingCompleteCallback = null
        val track = audioTrack ?: return
        try { track.setPlaybackPositionUpdateListener(null) } catch (_: Exception) {}
        try {
            track.pause()
            track.flush()
            totalFramesWritten = 0L
            track.play()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val SAMPLE_RATE = 16000
    }
}
