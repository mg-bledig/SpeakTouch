/*
 * Intercepts user gestures.
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

package com.neo.speaktouch.intercepter.gesture

import android.accessibilityservice.AccessibilityService
import android.os.Build
import androidx.annotation.RequiresApi
import com.neo.speaktouch.controller.ScreenCurtainController
import com.neo.speaktouch.controller.ContextMenuController
import com.neo.speaktouch.controller.BoundaryFeedbackController
import com.neo.speaktouch.controller.FocusController
import com.neo.speaktouch.controller.RefocusController
import com.neo.speaktouch.controller.ServiceController
import com.neo.speaktouch.controller.SliderController
import com.neo.speaktouch.controller.SpeechController
import dagger.hilt.android.scopes.ServiceScoped
import javax.inject.Inject

@ServiceScoped
class GestureInterceptor @Inject constructor(
    private val focusController: FocusController,
    private val serviceController: ServiceController,
    private val sliderController: SliderController,
    private val refocusController: RefocusController,
    private val speechController: SpeechController,
    private val contextMenuController: ContextMenuController? = null,
    private val screenCurtainController: ScreenCurtainController? = null,
    private val boundaryFeedback: BoundaryFeedbackController? = null
) {

    fun handle(gestureId: Int): Boolean {
        if (gestureId != AccessibilityService.GESTURE_SWIPE_LEFT &&
            gestureId != AccessibilityService.GESTURE_SWIPE_RIGHT) boundaryFeedback?.cancel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            gestureId == AccessibilityService.GESTURE_2_FINGER_SINGLE_TAP) {
            speechController.togglePauseResume()
            return true
        }
        speechController.cancelResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            gestureId == AccessibilityService.GESTURE_3_FINGER_DOUBLE_TAP) {
            screenCurtainController?.toggle()
            return screenCurtainController != null
        }

        when (gestureId) {
            AccessibilityService.GESTURE_SWIPE_DOWN_AND_RIGHT -> {
                contextMenuController?.open()
                return contextMenuController != null
            }
            AccessibilityService.GESTURE_SWIPE_UP_AND_DOWN -> {
                refocusController.reread()
                return true
            }

            AccessibilityService.GESTURE_SWIPE_UP -> return sliderController.adjust(increase = true)

            AccessibilityService.GESTURE_SWIPE_DOWN -> return sliderController.adjust(increase = false)

            AccessibilityService.GESTURE_SWIPE_LEFT -> {
                if (boundaryFeedback != null) boundaryFeedback.navigate(forward = false)
                else focusController.moveFocusToPrevious()

                return true
            }

            AccessibilityService.GESTURE_SWIPE_RIGHT -> {
                if (boundaryFeedback != null) boundaryFeedback.navigate(forward = true)
                else focusController.moveFocusToNext()

                return true
            }

            AccessibilityService.GESTURE_SWIPE_DOWN_AND_LEFT -> {

                serviceController.performGlobalAction(
                    AccessibilityService.GLOBAL_ACTION_BACK
                )

                return true
            }

            else -> Unit
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && handleApi31(gestureId)) return true

        return false
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun handleApi31(gestureId: Int): Boolean {

        when (gestureId) {

            AccessibilityService.GESTURE_2_FINGER_DOUBLE_TAP -> {

                serviceController.performGlobalAction(
                    AccessibilityService.GLOBAL_ACTION_KEYCODE_HEADSETHOOK
                )

                return true
            }

            else -> Unit
        }

        return false
    }
}
