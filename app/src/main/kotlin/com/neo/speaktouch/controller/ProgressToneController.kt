package com.neo.speaktouch.controller

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import dagger.hilt.android.scopes.ServiceScoped
import javax.inject.Inject
import kotlin.math.PI
import kotlin.math.sin

/** A single short PCM tone; a newer tone replaces the previous one instead of queuing. */
@ServiceScoped
class ProgressToneController @Inject constructor() {
    private val handler = Handler(Looper.getMainLooper())
    private var active: AudioTrack? = null

    fun play(progress: Double): Boolean {
        val frequency = frequency(progress) ?: return false
        stop()
        try {
            val samples = pcm(frequency)
            val track = AudioTrack(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
                samples.size, AudioTrack.MODE_STATIC, AudioManager.AUDIO_SESSION_ID_GENERATE)
            active = track
            // A static track starts in STATE_NO_STATIC_DATA until its buffer is written.
            if (track.state == AudioTrack.STATE_UNINITIALIZED ||
                track.write(samples, 0, samples.size) != samples.size || track.state != AudioTrack.STATE_INITIALIZED) {
                stop(); return false
            }
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(completed: AudioTrack) {
                    if (active === completed) stop()
                }
                override fun onPeriodicNotification(track: AudioTrack) = Unit
            }, handler)
            if (track.setNotificationMarkerPosition(FRAMES) != AudioTrack.SUCCESS) {
                stop(); return false
            }
            track.play()
            return true
        } catch (_: RuntimeException) {
            // Audio failure must not prevent progress speech or subsequent event handling.
            stop()
            return false
        }
    }

    fun stop() {
        val track = active ?: return
        active = null
        runCatching { track.setPlaybackPositionUpdateListener(null, handler) }
        runCatching { track.stop() }
        runCatching { track.release() }
    }

    internal companion object {
        const val SAMPLE_RATE = 16000
        const val DURATION_MS = 60
        const val FRAMES = SAMPLE_RATE * DURATION_MS / 1000
        fun frequency(progress: Double): Double? =
            if (progress.isFinite() && progress in 0.0..1.0) 220.0 + 660.0 * progress else null

        private fun pcm(frequency: Double): ByteArray {
            val fadeFrames = SAMPLE_RATE * 5 / 1000
            return ByteArray(FRAMES * 2).apply {
                for (frame in 0 until FRAMES) {
                    val envelope = minOf(frame, FRAMES - 1 - frame, fadeFrames).toDouble() / fadeFrames
                    val sample = (sin(2 * PI * frequency * frame / SAMPLE_RATE) * 32767 * 0.12 * envelope).toInt()
                    this[frame * 2] = sample.toByte()
                    this[frame * 2 + 1] = (sample shr 8).toByte()
                }
            }
        }
    }
}
