/*
 * Copyright (C) 2026 Michael G Bledig.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.neo.speaktouch.controller

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper

/** One owned mono PCM track. Replacement/stop invalidate late completion callbacks. */
internal class ShortPcmPlayer {
    private val handler = Handler(Looper.getMainLooper())
    private var active: AudioTrack? = null

    fun play(samples: ByteArray, sampleRate: Int): Boolean {
        if (samples.isEmpty() || samples.size % 2 != 0 || sampleRate <= 0) return false
        stop()
        try {
            val track = AudioTrack(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                AudioFormat.Builder().setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
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
            if (track.setNotificationMarkerPosition(samples.size / 2) != AudioTrack.SUCCESS) {
                stop(); return false
            }
            track.play()
            return true
        } catch (_: RuntimeException) {
            // Audio failure must not prevent speech or subsequent event handling.
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
}