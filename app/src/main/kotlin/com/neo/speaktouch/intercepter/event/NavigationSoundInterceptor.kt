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

package com.neo.speaktouch.intercepter.event

import android.content.Context
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.neo.speaktouch.controller.NavigationSoundController
import com.neo.speaktouch.controller.ServiceController
import com.neo.speaktouch.intercepter.event.contract.EventInterceptor
import com.neo.speaktouch.utils.SpeechPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ServiceScoped
import javax.inject.Inject

/** Only confirmed movement produces a click; requests and speech never do. */
@ServiceScoped
class NavigationSoundInterceptor @Inject constructor(
    private val service: ServiceController,
    private val sounds: NavigationSoundController,
    @ApplicationContext context: Context
) : EventInterceptor {
    private val preferences = SpeechPreferences(context)
    private var previous: AccessibilityNodeInfo? = null
    private var previousWindow: Int? = null
    private var finished = false

    fun onServiceConnected() {
        finished = false
        sounds.start()
        remember(service.getFocused()?.takeIf(::valid))
    }

    override fun handle(event: AccessibilityEvent) {
        if (finished) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START,
            AccessibilityEvent.TYPE_GESTURE_DETECTION_START,
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED -> { sounds.stop(); return }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> { reset(); return }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                if (Build.VERSION.SDK_INT < 28 ||
                    event.windowChanges and AccessibilityEvent.WINDOWS_CHANGE_ACTIVE != 0) reset()
                return
            }
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED -> Unit
            else -> return
        }
        val node = event.source ?: return
        if (event.windowId >= 0 && event.windowId != node.windowId) return
        if (event.packageName != null && event.packageName.toString() != node.packageName?.toString()) return
        if (!valid(node)) return
        val moved = previous != null && previousWindow == node.windowId && previous != node
        // Track silent movements too, so enabling the setting never plays a catch-up click.
        remember(node)
        if (!preferences.audibleNavigationFeedback) { sounds.stop(); return }
        if (moved) sounds.play()
    }

    private fun valid(node: AccessibilityNodeInfo): Boolean {
        try {
            if (!node.refresh() || !node.isVisibleToUser || !node.isAccessibilityFocused) return false
            val root = service.getRoot() ?: return false
            if (root.windowId != node.windowId || service.getFocused() != node) return false
            if (root.window?.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY ||
                node.window?.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) return false
            val pkg = root.packageName?.toString()?.takeIf { it.isNotBlank() } ?: return false
            return node.packageName?.toString() == pkg
        } catch (_: RuntimeException) {
            // Optional feedback must not interrupt the event pipeline when a provider vanishes.
            return false
        }
    }

    private fun remember(node: AccessibilityNodeInfo?) { previous = node; previousWindow = node?.windowId }
    private fun reset() { sounds.stop(); remember(null) }
    override fun finish() { finished = true; reset(); sounds.finish() }
}
