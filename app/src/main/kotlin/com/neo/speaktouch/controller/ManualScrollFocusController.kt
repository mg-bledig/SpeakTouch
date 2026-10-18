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
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
    private val interpreter: ManualScrollInterpreter
) : EventInterceptor {
    private data class FocusContext(
        val root: AccessibilityNodeInfo, val windowId: Int, val packageName: String?,
        val ancestors: Set<AccessibilityNodeInfo>
    )
    private var context: FocusContext? = null
    private var touched = false
    private var finished = false
    private var touching = false
    private var generation = 0L
    private val handler = Handler(Looper.getMainLooper())
    private data class Pending(val scroll: ManualScrollInterpreter.Scroll, val history: FocusContext, val generation: Long)
    private var pending: Pending? = null
    /** Read-only provenance for speech coordination; it never determines focus selection. */
    internal data class Recovery(val id: Long, val scroll: ManualScrollInterpreter.Scroll,
        val target: AccessibilityNodeInfo? = null, val requestedAt: Long? = null)
    internal var recovery: Recovery? = null
        private set
    internal var recoveryObserver: ((Recovery?) -> Unit)? = null
    private fun reportRecovery(value: Recovery?) {
        recovery = value
        // Speech observation must never prevent an otherwise valid focus action.
        try { recoveryObserver?.invoke(value) } catch (_: RuntimeException) { }
    }
    private val settle = Runnable {
        val request = pending
        pending = null
        if (request != null && !finished && !touching && context === request.history && generation == request.generation) {
            try { recover(request.scroll, request.history, request.generation) }
            catch (_: RuntimeException) { clear() }
            finally {
                if (recovery?.id == request.generation && recovery?.target == null) reportRecovery(null)
            }
        }
    }

    fun start() {
        clear(); finished = false; interpreter.start()
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
                    cancelPending()
                    reportRecovery(null)
                    touching = true
                    touched = true
                    rememberFocus()
                    return
                }
                AccessibilityEvent.TYPE_TOUCH_INTERACTION_END -> {
                    touching = false
                    schedule()
                    return
                }
                AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED -> {
                    cancelPending()
                    if (recovery?.target == null || event.source != recovery?.target) reportRecovery(null)
                    rememberFocus(); return
                }
                AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED -> {
                    return
                }
                AccessibilityEvent.TYPE_VIEW_SCROLLED -> Unit
                else -> return
            }
            cancelPending()
            val scroll = interpreter.confirmedScroll(event) ?: run { reportRecovery(null); return }
            val history = context ?: run { reportRecovery(null); return }
            if (!touched || history.root != scroll.root || history.windowId != scroll.root.windowId ||
                scroll.source !in history.ancestors) { reportRecovery(null); return }
            pending = Pending(scroll, history, generation)
            reportRecovery(Recovery(generation, scroll))
            schedule()
        } catch (_: RuntimeException) {

            clear()
        }
    }

    private fun schedule() {
        handler.removeCallbacks(settle)
        if (pending != null && !touching) {
            handler.postDelayed(settle, SETTLE_MS)
        }
    }

    private fun cancelPending() { generation++; handler.removeCallbacks(settle); pending = null }

    private fun recover(scroll: ManualScrollInterpreter.Scroll, history: FocusContext, requestGeneration: Long) {
        if (!touched || touching || interpreter.navigationOwned(scroll.source) || context !== history ||
            generation != requestGeneration) return
        if (service.getRoot() != scroll.root || service.getRoot()?.windowId != history.windowId) { return }
        if (scroll.root.packageName?.toString() != history.packageName) return
        val current = service.getFocused()
        if (current != null && validFocus(current, scroll.root)) {
            rememberFocus()
            return
        }
        val container = scroll.source
        if (!container.refresh() || !container.isVisibleToUser ||
            container.windowId != history.windowId ||
            container.packageName?.toString() != scroll.root.packageName?.toString() ||
            !belongsTo(container, scroll.root)) { return }
        val order = TraversalOrder(container)
        val nodes = if (scroll.movement.forward) order.nodes() else order.nodes().asReversed()
        val candidate = nodes.firstOrNull {
            it != container && it.refresh() && it.windowId == history.windowId &&
                it.packageName?.toString() == container.packageName?.toString() &&
                belongsTo(it, container) && NodeFilter.Focusable.filter(it)
        } ?: return

        // Refresh/provider calls can race an app/window/focus change. Never steal new focus.
        if (service.getRoot() != scroll.root || service.getRoot()?.windowId != history.windowId ||
            service.getFocused() != current) { return }
        if (current != null && validFocus(current, scroll.root)) { return }
        if (!container.refresh() || !container.isVisibleToUser || container.windowId != history.windowId ||
            container.packageName?.toString() != history.packageName ||
            !candidate.refresh() || !NodeFilter.Focusable.filter(candidate) ||
            candidate.windowId != history.windowId || !belongsTo(candidate, container) ||
            candidate.packageName?.toString() != scroll.root.packageName?.toString() ||
            !belongsTo(container, scroll.root) || service.getRoot() != scroll.root ||
            service.getRoot()?.windowId != history.windowId || service.getFocused() != current ||
            context !== history || generation != requestGeneration || touching || interpreter.navigationOwned(container) ||
            scroll.root.packageName?.toString() != history.packageName) { return }
        // Publish before the action: providers may dispatch the focus event synchronously.
        reportRecovery(Recovery(requestGeneration, scroll, candidate, SystemClock.uptimeMillis()))
        val accepted = candidate.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        if (!accepted && recovery?.id == requestGeneration) reportRecovery(null)

        // Speech, haptics and movement clicks are produced only by the normal focus event.
    }

    private fun rememberFocus() {
        val root = service.getRoot() ?: return
        val focus = service.getFocused() ?: return
        if (!validFocus(focus, root)) return
        val ancestors = linkedSetOf<AccessibilityNodeInfo>()
        var node: AccessibilityNodeInfo? = focus
        while (node != null && ancestors.add(node)) {
            if (node == root) {
                context = FocusContext(root, root.windowId, root.packageName?.toString(), ancestors)
                return
            }
            node = node.parent
        }
    }

    private fun validFocus(node: AccessibilityNodeInfo, root: AccessibilityNodeInfo): Boolean {
        val refreshed = node.refresh()
        val valid = refreshed && node.isAccessibilityFocused && node.windowId == root.windowId &&
            node.packageName?.toString() == root.packageName?.toString() &&
            NodeFilter.Focusable.filter(node) && belongsTo(node, root)
        return valid
    }

    private fun belongsTo(node: AccessibilityNodeInfo, ancestor: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        val seen = mutableSetOf<AccessibilityNodeInfo>()
        while (current != null && seen.add(current)) {
            if (current == ancestor) return true
            current = current.parent
        }
        return false
    }

    private fun clear() { cancelPending(); context = null; touched = false; touching = false; reportRecovery(null) }
    override fun finish() { finished = true; clear(); interpreter.finish() }

    internal companion object { const val SETTLE_MS = 150L }
}
