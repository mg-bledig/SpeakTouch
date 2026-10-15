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

import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.neo.speaktouch.intercepter.event.contract.EventInterceptor
import com.neo.speaktouch.model.NodeFilter
import com.neo.speaktouch.utils.TraversalOrder
import dagger.hilt.android.scopes.ServiceScoped
import javax.inject.Inject

/** Restores invalid focus after confirmed manual movement, never after a navigation-owned scroll. */
@ServiceScoped
class ManualScrollFocusController @Inject constructor(
    private val service: ServiceController,
    private val scrollFeedback: ScrollFeedbackController
) : EventInterceptor {
    private data class FocusContext(
        val root: AccessibilityNodeInfo, val windowId: Int,
        val ancestors: Set<AccessibilityNodeInfo>
    )
    private var context: FocusContext? = null
    private var touched = false
    private var finished = false

    fun start() {
        clear(); finished = false
        try { rememberFocus() } catch (_: RuntimeException) { clear() }
    }

    override fun handle(event: AccessibilityEvent) {
        if (finished) return
        try {
            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> { clear(); return }
                AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                    if (Build.VERSION.SDK_INT < 28 ||
                        event.windowChanges and AccessibilityEvent.WINDOWS_CHANGE_ACTIVE != 0) clear()
                    return
                }
                AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> {
                    touched = true
                    rememberFocus()
                    return
                }
                AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED -> { rememberFocus(); return }
                AccessibilityEvent.TYPE_VIEW_SCROLLED -> Unit
                else -> return
            }
            val scroll = scrollFeedback.confirmedManualScroll(event) ?: return
            val history = context ?: return
            if (!touched || history.root != scroll.root || history.windowId != scroll.root.windowId ||
                scroll.source !in history.ancestors) return
            if (service.getRoot() != scroll.root || service.getRoot()?.windowId != history.windowId) return
            val current = service.getFocused()
            if (current != null && validFocus(current, scroll.root)) {
                rememberFocus()
                return
            }
            val container = scroll.source
            if (!container.refresh() || !container.isVisibleToUser ||
                container.windowId != history.windowId ||
                container.packageName?.toString() != scroll.root.packageName?.toString() ||
                !belongsTo(container, scroll.root)) return
            val order = TraversalOrder(container)
            val nodes = if (scroll.forward) order.nodes() else order.nodes().asReversed()
            val candidate = nodes.firstOrNull {
                it != container && it.refresh() && it.windowId == history.windowId &&
                    it.packageName?.toString() == container.packageName?.toString() &&
                    belongsTo(it, container) && NodeFilter.Focusable.filter(it)
            } ?: return
            // Refresh/provider calls can race an app/window/focus change. Never steal new focus.
            if (service.getRoot() != scroll.root || service.getRoot()?.windowId != history.windowId ||
                service.getFocused() != current) return
            if (current != null && validFocus(current, scroll.root)) return
            if (!candidate.refresh() || !NodeFilter.Focusable.filter(candidate) ||
                candidate.windowId != history.windowId || !belongsTo(candidate, container) ||
                candidate.packageName?.toString() != scroll.root.packageName?.toString() ||
                !belongsTo(container, scroll.root) || service.getRoot() != scroll.root ||
                service.getRoot()?.windowId != history.windowId || service.getFocused() != current) return
            candidate.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
            // Speech, haptics and movement clicks are produced only by the normal focus event.
        } catch (_: RuntimeException) {
            clear() // A disappearing provider must not interrupt the event pipeline.
        }
    }

    private fun rememberFocus() {
        val root = service.getRoot() ?: return
        val focus = service.getFocused() ?: return
        if (!validFocus(focus, root)) return
        val ancestors = linkedSetOf<AccessibilityNodeInfo>()
        var node: AccessibilityNodeInfo? = focus
        while (node != null && ancestors.add(node)) {
            if (node == root) {
                context = FocusContext(root, root.windowId, ancestors)
                return
            }
            node = node.parent
        }
    }

    private fun validFocus(node: AccessibilityNodeInfo, root: AccessibilityNodeInfo): Boolean =
        node.refresh() && node.isAccessibilityFocused && node.windowId == root.windowId &&
            node.packageName?.toString() == root.packageName?.toString() &&
            NodeFilter.Focusable.filter(node) && belongsTo(node, root)

    private fun belongsTo(node: AccessibilityNodeInfo, ancestor: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        val seen = mutableSetOf<AccessibilityNodeInfo>()
        while (current != null && seen.add(current)) {
            if (current == ancestor) return true
            current = current.parent
        }
        return false
    }

    private fun clear() { context = null; touched = false }
    override fun finish() { finished = true; clear() }
}
