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

/** A single short PCM tone; a newer tone replaces the previous one instead of queuing. */
@ServiceScoped
class ProgressToneController @Inject constructor() {
    private val player = ShortPcmPlayer()

    fun play(progress: Double): Boolean {
        val frequency = frequency(progress) ?: return false
        return player.play(pcm(frequency), SAMPLE_RATE)
    }

    fun stop() = player.stop()

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
