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
