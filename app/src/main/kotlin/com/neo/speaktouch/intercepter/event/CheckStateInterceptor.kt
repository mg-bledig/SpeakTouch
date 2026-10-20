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

import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.neo.speaktouch.controller.ServiceController
import com.neo.speaktouch.controller.SpeechController
import com.neo.speaktouch.intercepter.event.contract.EventInterceptor
import com.neo.speaktouch.model.Type
import com.neo.speaktouch.utils.extension.toStateText
import dagger.hilt.android.scopes.ServiceScoped
import javax.inject.Inject

/** Immediate feedback for observed checkbox/radio transitions, independent of click counts. */
@ServiceScoped
class CheckStateInterceptor @Inject constructor(
    private val service: ServiceController,
    private val speech: SpeechController
) : EventInterceptor {
    private data class State(val node: AccessibilityNodeInfo, val root: AccessibilityNodeInfo,
        val window: Int, val checked: Boolean)
    private var observed: State? = null
    private var finished = false

    fun start() { finished = false; rememberFocused() }

    override fun handle(event: AccessibilityEvent) {
        if (finished) return
        try {
            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> { observed = null; return }
                AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                    if (Build.VERSION.SDK_INT < 28 ||
                        event.windowChanges and AccessibilityEvent.WINDOWS_CHANGE_ACTIVE != 0) observed = null
                    return
                }
                AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED -> {
                    if (event.source == null || event.source == observed?.node) observed = null
                    return
                }
                AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> { rememberFocused(); return }
                AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED -> {
                    // SpeechInterceptor just read this event source. Record that same state
                    // without a refresh advancing the baseline past the focus announcement.
                    observed = event.source?.let { capture(it, refresh = false) }
                    return
                }
                AccessibilityEvent.TYPE_VIEW_CLICKED -> Unit
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                    if (event.contentChangeTypes and AccessibilityEvent.CONTENT_CHANGE_TYPE_STATE_DESCRIPTION == 0) return
                }
                else -> return
            }
            val source = event.source ?: return
            // An unfocused radio may emit its deselection before the newly selected one.
            // It must neither speak nor erase the focused control's baseline.
            if (source != service.getFocused()) return
            if (event.windowId >= 0 && event.windowId != source.windowId ||
                event.packageName != null && event.packageName.toString() != source.packageName?.toString()) return
            val current = capture(source, refresh = true) ?: run { observed = null; return }
            val before = observed
            observed = current
            if (before == null || before.node != current.node || before.root != current.root ||
                before.window != current.window || before.checked == current.checked) return
            val node = AccessibilityNodeInfoCompat.wrap(source)
            // Use the same localized state and stateDescription precedence as Reader.
            node.toStateText()?.let(speech::speak)
        } catch (_: RuntimeException) {
            observed = null // Stale providers cannot establish a state transition.
        }
    }

    private fun rememberFocused() {
        observed = try { service.getFocused()?.let { capture(it, refresh = true) } }
            catch (_: RuntimeException) { null }
    }

    private fun capture(node: AccessibilityNodeInfo, refresh: Boolean): State? {
        val root = service.getRoot() ?: return null
        val window = root.windowId
        if (refresh && !node.refresh()) return null
        val compat = AccessibilityNodeInfoCompat.wrap(node)
        val type = Type.get(compat)
        if (type != Type.Checkable.Checkbox && type != Type.Checkable.Radio) return null
        if (!compat.isCheckable || !node.isVisibleToUser || !node.isAccessibilityFocused ||
            node.windowId != window || root.packageName.isNullOrBlank() ||
            node.packageName?.toString() != root.packageName?.toString() ||
            root.window?.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY ||
            node.window?.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY ||
            service.getRoot() != root || service.getRoot()?.windowId != window ||
            service.getFocused() != node) return null
        return State(node, root, window, compat.isChecked)
    }

    override fun finish() { finished = true; observed = null }
}
