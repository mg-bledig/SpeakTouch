package com.neo.speaktouch.controller

import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import dagger.hilt.android.scopes.ServiceScoped
import javax.inject.Inject

@ServiceScoped
class RefocusController @Inject constructor(
    private val serviceController: ServiceController,
    private val speechController: SpeechController
) {
    fun reread() {
        val focused = serviceController.getFocused() ?: return
        val node = AccessibilityNodeInfoCompat.wrap(focused)
        if (!node.refresh() || !node.isVisibleToUser || !node.isAccessibilityFocused) return

        // Refresh can race with a focus or window change. Never read an old target.
        val current = serviceController.getFocused() ?: return
        val root = serviceController.getOrThrow().rootInActiveWindow ?: return
        if (current != focused || root.windowId != node.windowId) return

        speechController.speak(node)
    }
}
