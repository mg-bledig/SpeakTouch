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

import dagger.hilt.android.scopes.ServiceScoped
import javax.inject.Inject
import kotlin.math.PI
import kotlin.math.sin

/** Subtle fixed click, independent of progress pitch/cadence and TTS. */
@ServiceScoped
class NavigationSoundController @Inject constructor() {
    private val player = ShortPcmPlayer()
    private var finished = false

    fun start() { player.stop(); finished = false }
    fun play(): Boolean = !finished && player.play(CLICK, SAMPLE_RATE)
    fun stop() = player.stop()
    fun finish() { finished = true; player.stop() }

    internal companion object {
        const val SAMPLE_RATE = 16000
        const val DURATION_MS = 16
        const val FRAMES = SAMPLE_RATE * DURATION_MS / 1000
        private val CLICK = ByteArray(FRAMES * 2).apply {
            val fadeFrames = SAMPLE_RATE * 2 / 1000
            for (frame in 0 until FRAMES) {
                val envelope = minOf(frame, FRAMES - 1 - frame, fadeFrames).toDouble() / fadeFrames
                val sample = (sin(2 * PI * 1800.0 * frame / SAMPLE_RATE) * 32767 * 0.06 * envelope).toInt()
                this[frame * 2] = sample.toByte()
                this[frame * 2 + 1] = (sample shr 8).toByte()
            }
        }
    }
}