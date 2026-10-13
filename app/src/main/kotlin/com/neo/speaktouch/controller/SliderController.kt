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

import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.neo.speaktouch.model.Type
import com.neo.speaktouch.utils.extension.toStateText
import dagger.hilt.android.scopes.ServiceScoped
import javax.inject.Inject

@ServiceScoped
class SliderController @Inject constructor(
    private val serviceController: ServiceController,
    private val speechController: SpeechController
) {
    fun adjust(increase: Boolean): Boolean {
        val focused = serviceController.getFocused() ?: return false
        val node = AccessibilityNodeInfoCompat.wrap(focused)
        if (Type.get(node) != Type.Slider) return false

        // A stale or unavailable slider must not redirect the gesture elsewhere.
        if (!node.refresh()) return true
        if (Type.get(node) != Type.Slider) return false
        if (!node.isVisibleToUser || !node.isEnabled || !node.isAccessibilityFocused) return true

        val action = if (increase) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        if (node.actionList.none { it.id == action }) return true

        if (node.performAction(action) && node.refresh() &&
            node.isAccessibilityFocused && node.isVisibleToUser &&
            Type.get(node) == Type.Slider && serviceController.getFocused() == focused) {
            node.toStateText(Type.Slider)?.let(speechController::speak)
        }
        return true
    }
}
