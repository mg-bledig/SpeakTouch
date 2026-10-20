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

import android.content.Context
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.neo.speaktouch.R
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ServiceScoped
import javax.inject.Inject

/** Speech only: input focus confirms editing; clicks and keyboard windows do not. */
@ServiceScoped
class EditingFeedbackController @Inject constructor(
    private val service: ServiceController,
    private val speech: SpeechController,
    @param:ApplicationContext private val context: Context
) {
    private data class Target(val node: AccessibilityNodeInfo, val root: AccessibilityNodeInfo, val window: Int)
    private var input: Target? = null
    private var accessibility: AccessibilityNodeInfo? = null
    private var echo: Target? = null
    private var echoGeneration = -1L
    private var finished = false

    fun start() {
        finished = false
        try { remember() } catch (_: RuntimeException) { input = null; accessibility = null; echo = null }
    }

    private fun remember() {
        echo = null
        input = service.getRoot()?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let(::validate)
        accessibility = service.getFocused()
    }

    /** True only when this event's ordinary focus speech has already been supplied. */
    fun onEvent(event: AccessibilityEvent): Boolean {
        if (finished) return false
        try {
            when (event.eventType) {
                AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> remember()
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> checkWindow()
                AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                    if (Build.VERSION.SDK_INT < 28 || event.windowChanges and AccessibilityEvent.WINDOWS_CHANGE_ACTIVE != 0) {
                        checkWindow()
                    }
                }
                AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED -> {
                    val source = event.source
                    if (source == null || source == accessibility) accessibility = null
                    if (source == null || source == echo?.node) echo = null
                }
                AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                    val source = event.source ?: return false
                    val current = validate(source)
                    val previous = input
                    input = current ?: service.getRoot()?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let(::validate)
                    if (current == null || current == previous ||
                        event.windowId >= 0 && event.windowId != current.window ||
                        event.packageName != null && event.packageName.toString() != source.packageName?.toString()) return false
                    announce(current)
                }
                AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED -> {
                    val source = event.source ?: return false
                    val current = validate(source)?.takeIf {
                        source.isAccessibilityFocused && service.getFocused() == source
                    }
                    val previousAccessibility = accessibility
                    accessibility = source
                    if (current != null && echo == current && echoGeneration == speech.generation) {
                        echo = null
                        return true
                    }
                    echo = null
                    // Some providers send accessibility focus before their input-focus event.
                    // Only a previously observed field can prove this is a new editing transition.
                    if (current != null && previousAccessibility == source && input != current) {
                        input = current
                        announce(current)
                        return true
                    }
                    input = service.getRoot()?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let(::validate)
                }
            }
        } catch (_: RuntimeException) {
            input = null; accessibility = null; echo = null
        }
        return false
    }

    private fun checkWindow() {
        // Showing/hiding the IME is not a change of the original app's focus context.
        val root = service.getRoot()
        if (root == null || input?.let { it.root != root || it.window != root.windowId } == true ||
            echo?.let { it.root != root || it.window != root.windowId } == true ||
            accessibility?.let { it.windowId != root.windowId } == true) {
            input = null; accessibility = null; echo = null
        }
    }

    private fun validate(node: AccessibilityNodeInfo): Target? {
        if (!node.isEditable) return null
        val root = service.getRoot() ?: return null
        val window = root.windowId
        if (window < 0 || !node.refresh() || !node.isEditable || !node.isFocused || !node.isEnabled || !node.isVisibleToUser ||
            node.windowId != window || root.packageName.isNullOrBlank() ||
            root.packageName?.toString() != node.packageName?.toString() ||
            root.window?.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD ||
            node.window?.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD ||
            root.window?.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY ||
            node.window?.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY ||
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) != node ||
            service.getRoot() != root || service.getRoot()?.windowId != window) return null
        return Target(node, root, window)
    }

    private fun announce(target: Target) {
        speech.speak(AccessibilityNodeInfoCompat.wrap(target.node), listOf(context.getString(R.string.text_editing)))
        echo = target
        echoGeneration = speech.generation
    }

    fun finish() { finished = true; input = null; accessibility = null; echo = null }
}
