/*
 * Vibration utility wrapper.
 *
 * Copyright (C) 2023 Irineu A. Silva.
 * Copyright (C) 2023 Patryk Miś.
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

import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import com.neo.speaktouch.model.LegacyVibrationEffect
import dagger.hilt.android.scopes.ServiceScoped
import javax.inject.Inject

class VibratorController(
    private val vibrator: Vibrator
) {

    fun vibrateEffectHeavyClick() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(
                VibrationEffect.createPredefined(
                    VibrationEffect.EFFECT_HEAVY_CLICK
                ),
                VibrationAttributes.createForUsage(
                    VibrationAttributes.USAGE_ACCESSIBILITY
                )
            )

            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vibrator.vibrate(
                VibrationEffect.createPredefined(
                    VibrationEffect.EFFECT_HEAVY_CLICK
                )
            )

            return
        }

        vibrateLegacy(
            LegacyVibrationEffect.HeavyClick
        )
    }

    fun vibrateEffectTick() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(
                VibrationEffect.createPredefined(
                    VibrationEffect.EFFECT_TICK
                ),
                VibrationAttributes.createForUsage(
                    VibrationAttributes.USAGE_ACCESSIBILITY
                )
            )

            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vibrator.vibrate(
                VibrationEffect.createPredefined(
                    VibrationEffect.EFFECT_TICK
                )
            )

            return
        }

        vibrateLegacy(
            LegacyVibrationEffect.Tick,
        )
    }

    @Suppress("deprecation")
    fun vibrateLegacy(
        legacyVibrationEffect: LegacyVibrationEffect,
        repeat: Int = DEFAULT_REPEAT,
    ) {
        vibrator.vibrate(
            legacyVibrationEffect.pattern,
            repeat
        )
    }

    @Suppress("deprecation")
    fun vibrateBoundary() {
        val pattern = longArrayOf(0, 18, 30, 18)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = VibrationEffect.createWaveform(pattern, -1)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ACCESSIBILITY))
            } else vibrator.vibrate(effect)
        } else vibrator.vibrate(pattern, -1)
    }
    fun hasVibrator() = vibrator.hasVibrator()

    fun finish() = vibrator.cancel()

    companion object {
        private const val DEFAULT_REPEAT = -1
    }
}
