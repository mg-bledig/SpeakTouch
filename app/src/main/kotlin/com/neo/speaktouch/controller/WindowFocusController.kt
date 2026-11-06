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
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.neo.speaktouch.intercepter.event.SpeechInterceptor
import com.neo.speaktouch.model.NodeFilter
import com.neo.speaktouch.utils.TraversalOrder
import com.neo.speaktouch.utils.AccessibilityPaneState
import dagger.hilt.android.scopes.ServiceScoped
import javax.inject.Inject

/** Window-entry focus only. Sequential navigation and gesture recognition stay unchanged. */
@ServiceScoped
class WindowFocusController @Inject constructor(
    private val service: ServiceController,
    private val speech: SpeechInterceptor
) {
    private data class Window(val root: AccessibilityNodeInfo, val id: Int, val paneGeneration: Long? = null)
    private data class Attempt(val window: Window, val target: AccessibilityNodeInfo)
    private val handler = Handler(Looper.getMainLooper())
    private var pending: Int? = null
    private var work: Runnable? = null
    private var handled: Window? = null
    private var attempted: Attempt? = null
    private var observedFocus: AccessibilityNodeInfo? = null
    private var finished = false
    private val panes = AccessibilityPaneState()

    fun start() { finish(); finished = false }

    fun onEvent(event: AccessibilityEvent, allowed: Boolean = true) {
        if (finished) return
        if (!allowed) { cancel(); return }
        try {
            observe(event)
        } catch (error: RuntimeException) { cancel() }
    }

    private fun observe(event: AccessibilityEvent) {
        val activeRoot = service.getRoot()
        when (panes.observe(event, activeRoot)) {
            AccessibilityPaneState.Change.APPEARED -> {
                attempted = null
                val pane = activeRoot?.let(panes::current)
                observedFocus = observedFocus?.takeIf { pane?.contains(it) == true }
            }
            AccessibilityPaneState.Change.DISAPPEARED -> {
                cancel(); attempted = null
                if (activeRoot != null) handled = Window(activeRoot, activeRoot.windowId)
                return
            }
            AccessibilityPaneState.Change.NONE -> Unit
        }
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED -> {
                observedFocus = event.source
                val focus = observedFocus
                if (pending != null && focus?.windowId == pending) schedule()
                else if (focus != null && pending == null && handled?.id != focus.windowId &&
                    focus.isVisibleToUser && focus.isAccessibilityFocused) {
                    // A window which already delivered normal focus needs no later reinitialization
                    // for same-window pane/page changes (manual-scroll recovery owns those).
                    root(focus.windowId)?.let { handled = Window(it, it.windowId, panes.current(it)?.generation) }
                }
            }
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED -> {
                if (observedFocus == event.source) observedFocus = null
            }
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START,
            AccessibilityEvent.TYPE_GESTURE_DETECTION_START,
            AccessibilityEvent.TYPE_VIEW_HOVER_ENTER -> {
                // Touch exploration/navigation takes priority over pending automatic entry.
                val id = event.source?.windowId ?: pending ?: preferredWindowId()
                id?.let { root(it) }?.let { handled = Window(it, it.windowId, panes.current(it)?.generation) }
                cancel()
                return
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                pending = event.windowId.takeIf { it >= 0 } ?: preferredWindowId()
                schedule()
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                if (Build.VERSION.SDK_INT >= 28 && event.windowChanges and AccessibilityEvent.WINDOWS_CHANGE_REMOVED != 0 &&
                    event.windowId == pending) {
                    cancel()
                    return
                }
                if (isWindowTransition(event)) {
                    val preferred = preferredWindowId()
                    pending = preferred ?: pending
                    schedule()
                }
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (pending != null && (event.windowId == pending || event.source?.windowId == pending)) schedule()
            }
        }
    }

    private fun preferredWindowId(): Int? = service.getOrThrow().windows.firstOrNull {
        it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused
    }?.id ?: service.getRoot()?.windowId

    private fun root(id: Int): AccessibilityNodeInfo? {
        val windows = service.getOrThrow().windows
        val window = windows.firstOrNull { it.id == id }
        if (window != null) {
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION || (!window.isActive && !window.isFocused)) { return null
            }
            return window.root
        }
        return service.getRoot()?.takeIf {
            it.windowId == id && it.window?.type == AccessibilityWindowInfo.TYPE_APPLICATION
        }
    }

    private fun schedule() {
        work?.let(handler::removeCallbacks)
        val id = pending ?: return
        val paneGeneration = panes.currentGeneration
        // Run after the current window-event turn; readiness retries come only from real events.
        val task = Runnable {
            work = null
            if (!finished && pending == id) initialize(id, paneGeneration)
        }
        work = task
        handler.post(task)
    }

    private fun initialize(id: Int, paneGeneration: Long?) {
        try {
            val root = root(id) ?: return
            if (!root.refresh() || root.windowId != id || root.packageName.isNullOrBlank() || this.root(id) != root) { return }
            val pane = panes.current(root)
            if (pane?.generation != paneGeneration) { cancel(); return }
            val window = Window(root, id, pane?.generation)
            if (window == handled) { cancel(); return }
            // A same-window pane is a distinct entry context; never search the underlying screen.
            val scope = pane?.node ?: root
            val order = TraversalOrder(scope)
            fun usable(node: AccessibilityNodeInfo) = node.windowId == id &&
                node.packageName?.toString() == root.packageName?.toString() &&
                node.refresh() && node.isVisibleToUser && order.contains(scope, node)
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            if (focused != null && usable(focused) && focused.isAccessibilityFocused && NodeFilter.Focusable.filter(focused)) {
                existingFocus(window, focused)
                return
            }
            val input = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf {
                it != root && usable(it) && it.isFocused && NodeFilter.Focusable.filter(it)
            }
            val candidate = input ?: order.nodes().firstOrNull {
                it != root && NodeFilter.Focusable.filter(it) && usable(it)
            } ?: return
            val attempt = Attempt(window, candidate)
            if (attempt == attempted) { return } // An accepted, asynchronous focus request is not a failure.
            if (this.root(id) != root || panes.current(root)?.generation != pane?.generation ||
                !candidate.refresh() || !candidate.isVisibleToUser ||
                candidate.windowId != id || candidate.packageName?.toString() != root.packageName?.toString() ||
                !NodeFilter.Focusable.filter(candidate)) { return }
            // The provider may assign focus during a remote refresh. Preserve it instead
            // of issuing a competing focus action; late matching events are deduplicated.
            val latest = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            if (latest != null && usable(latest) && latest.isAccessibilityFocused && NodeFilter.Focusable.filter(latest)) {
                existingFocus(window, latest)
                return
            }
            attempted = attempt
            val accepted = candidate.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
            if (!accepted) {
                handled = window
                cancel()
            }
            // The real focus event supplies normal speech/click/haptic feedback.
        } catch (error: RuntimeException) { cancel() }
    }

    private fun existingFocus(window: Window, focused: AccessibilityNodeInfo) {
        handled = window
        cancel()
        if (observedFocus != focused) speech.announceInitialWindowFocus(AccessibilityNodeInfoCompat.wrap(focused))
    }

    private fun cancel() {
        work?.let(handler::removeCallbacks)
        work = null
        pending = null
    }

    fun finish() { cancel(); handled = null; attempted = null; observedFocus = null; panes.clear(); finished = true }

    companion object {
        fun isWindowTransition(event: AccessibilityEvent): Boolean =
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED &&
                (Build.VERSION.SDK_INT < 28 || event.windowChanges and
                    (AccessibilityEvent.WINDOWS_CHANGE_ADDED or AccessibilityEvent.WINDOWS_CHANGE_ACTIVE or
                        AccessibilityEvent.WINDOWS_CHANGE_FOCUSED) != 0)
    }
}
