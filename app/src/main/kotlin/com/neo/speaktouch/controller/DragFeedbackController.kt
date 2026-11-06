/*
 * Copyright (C) 2026 Michael G Bledig.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package com.neo.speaktouch.controller

import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.neo.speaktouch.intercepter.event.contract.EventInterceptor
import com.neo.speaktouch.model.Text
import dagger.hilt.android.scopes.ServiceScoped
import javax.inject.Inject

/** Provider selection feedback only during an explicitly started framework long-press drag. */
@ServiceScoped
class DragFeedbackController @Inject constructor(
    private val service: ServiceController,
    private val speech: SpeechController
) : EventInterceptor {
    private data class Drag(val root: AccessibilityNodeInfo, val pkg: String, val window: Int,
        val display: Int, val startedAt: Long, val isDragging: () -> Boolean,
        var engaged: Boolean = false, var lastTime: Long = Long.MIN_VALUE,
        var lastDescription: String? = null, var speechGeneration: Long? = null)
    private var drag: Drag? = null

    /** Called only after Android accepted the long-click/continuous-drag handoff. */
    fun begin(display: Int, requestedAt: Long = SystemClock.uptimeMillis(), isDragging: () -> Boolean): Boolean {
        finish()
        return try {
            val root = service.getRoot() ?: return false
            val pkg = root.packageName?.toString()?.takeIf { it.isNotBlank() } ?: return false
            if (root.window?.type != AccessibilityWindowInfo.TYPE_APPLICATION || !root.refresh() ||
                !root.isVisibleToUser || (Build.VERSION.SDK_INT >= 30 && root.window?.displayId != display)) return false
            drag = Drag(root, pkg, root.windowId, display, requestedAt, isDragging)
            true
        } catch (_: RuntimeException) { false }
    }

    /** Numeric callback states avoid loading API-33 touch-controller classes on older Android. */
    fun onInteractionStateChanged(display: Int, state: Int) {
        val current = drag ?: return
        if (current.display != display) return
        if (state == 3 || state == 4) current.engaged = true // Dragging / delegating.
        else if (state == 0 || state == 2 || current.engaged) finish()
    }

    override fun handle(event: AccessibilityEvent) {
        val current = drag ?: return
        try {
            when (event.eventType) {
                AccessibilityEvent.TYPE_TOUCH_INTERACTION_END,
                AccessibilityEvent.TYPE_TOUCH_INTERACTION_START,
                AccessibilityEvent.TYPE_GESTURE_DETECTION_START -> { finish(); return }
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> if (event.windowId == current.window &&
                    event.packageName?.toString() == current.pkg && event.contentChangeTypes and (256 or 512) != 0) {
                    finish(); return // API-32 drag dropped/cancelled. Never speak these content changes.
                }
            }
            val root = service.getRoot()
            if (root == null || root != current.root || root.windowId != current.window || root.packageName?.toString() != current.pkg ||
                !root.refresh() || !root.isVisibleToUser || root.window?.type != AccessibilityWindowInfo.TYPE_APPLICATION) {
                finish(); return
            }
            if (event.eventType != AccessibilityEvent.TYPE_VIEW_SELECTED) return
            // Selected events are meaningful even when the provider leaves isSelected/focus false.
            if (!current.isDragging()) {
                if (current.engaged) finish()
                return
            }
            current.engaged = true
            if (event.eventTime < current.startedAt || event.eventTime < current.lastTime || event.eventTime > SystemClock.uptimeMillis() ||
                event.windowId != current.window || event.packageName?.toString() != current.pkg || event.isPassword) { return }
            val source = event.source ?: return
            if (source.windowId != current.window || source.packageName?.toString() != current.pkg ||
                !source.refresh() || !source.isVisibleToUser || !source.isEnabled || source.isPassword || source.isEditable ||
                AccessibilityNodeInfoCompat.wrap(source).isAccessibilityDataSensitive) { return }
            if (!belongsTo(source, root)) { return }
            val eventDescription = event.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            val description = source.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            if (eventDescription != null && description != null && eventDescription != description) { return }
            val value = eventDescription ?: description ?: return
            if (service.getRoot() != root || drag !== current || !current.isDragging()) { finish(); return }
            current.lastTime = event.eventTime
            if (current.lastDescription == value) { return }
            current.lastDescription = value
            speech.speak(Text(value)) // Existing flush semantics replace obsolete destinations; no backlog.
            current.speechGeneration = speech.generation
        } catch (error: RuntimeException) { finish() }
    }

    override fun finish() {
        val previous = drag
        drag = null
        if (previous != null) {
            // Do not stop later focused-item, popup, or other ordinary speech.
            if (previous.speechGeneration != null && previous.speechGeneration == speech.generation)
                runCatching { speech.stop() }
        }
    }


    private fun belongsTo(source: AccessibilityNodeInfo, root: AccessibilityNodeInfo): Boolean {
        var node: AccessibilityNodeInfo? = source
        val seen = mutableSetOf<AccessibilityNodeInfo>()
        while (node != null && seen.add(node)) {
            if (node == root) return true
            node = node.parent
        }
        return false
    }
}
