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
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.neo.speaktouch.intercepter.event.contract.EventInterceptor
import com.neo.speaktouch.utils.TraversalOrder
import com.neo.speaktouch.utils.SpeechPreferences
import com.neo.speaktouch.utils.Reader
import com.neo.speaktouch.utils.NodeValidator
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ServiceScoped
import android.content.Context
import javax.inject.Inject
import kotlin.math.PI
import kotlin.math.sin

/** Feedback for a navigation request, never for elapsed time or an absent focus event. */
@ServiceScoped
class BoundaryFeedbackController @Inject constructor(
    private val focus: FocusController,
    private val service: ServiceController,
    private val vibration: VibratorController,
    @ApplicationContext context: Context
) : EventInterceptor {
    private val preferences = SpeechPreferences(context)
    private val reader = Reader(context)
    private val player = ShortPcmPlayer()
    private var resultHandler: ((FocusController.NavigationOutcome) -> Unit)? = null
    private var origin: AccessibilityNodeInfo? = null
    private var finished = false
    private data class ArmedEdge(
        val root: AccessibilityNodeInfo, val window: Int,
        val target: AccessibilityNodeInfo, val forward: Boolean
    )
    private var armedEdge: ArmedEdge? = null

    fun start() { cancel(); finished = false }

    fun navigate(forward: Boolean) {
        cancelRequest()
        if (finished) return
        val target = service.getFocused()
        val root = service.getRoot()
        if (target == null || root == null) {
            armedEdge = null
            // Keep existing initial-focus/fallback navigation, without claiming an edge.
            if (forward) focus.moveFocusToNext() else focus.moveFocusToPrevious()
            return
        }
        origin = target
        val window = root.windowId
        val edge = ArmedEdge(root, window, target, forward)
        if (armedEdge != edge) {
            armedEdge = null
        }
        fun current(): Boolean = try {
            !finished && target.refresh() && target.isAccessibilityFocused &&
                focus.navigationOrigin(target) &&
                target.windowId == window && service.getRoot() == root &&
                service.getRoot()?.windowId == window && service.getFocused() == target
        } catch (_: RuntimeException) { false }
        if (!current()) {
            cancel(); return
        }
        lateinit var handler: (FocusController.NavigationOutcome) -> Unit
        val guard = {
            if (resultHandler === handler && current()) true else {
                cancel(); false
            }
        }
        handler = { outcome ->
            if (resultHandler === handler) {
                if (outcome == FocusController.NavigationOutcome.SCROLL_PENDING) {
                    armedEdge = null
                } else {
                    val boundary = outcome == FocusController.NavigationOutcome.EXHAUSTED &&
                        current() && completeTree(root, target) && stillExhausted(root, target, forward) && current()
                    val wrap = boundary && armedEdge == edge
                    cancelRequest()
                    if (!boundary) {
                        armedEdge = null
                    } else if (wrap) {
                        armedEdge = null
                        focus.wrapFocusToWindowEdge(root, target, forward, ::isUsableWrapTarget) {
                            current() && completeTree(root, target) && stillExhausted(root, target, forward) && current()
                        }
                    } else {
                        armedEdge = edge
                        feedback()
                    }
                }
            }
        }
        resultHandler = handler
        try {
            if (forward) focus.moveFocusToNext(onResult = handler, canContinue = guard)
            else focus.moveFocusToPrevious(onResult = handler, canContinue = guard)
        } catch (error: RuntimeException) {
            cancel()
        }
    }

    private fun isUsableWrapTarget(node: AccessibilityNodeInfo): Boolean {
        val compat = AccessibilityNodeInfoCompat.wrap(node)
        if (NodeValidator.isClickable(compat)) return true
        // Preserve argument-free interactions even when the provider omitted click flags.
        if (node.actionList.any {
                it.id == AccessibilityNodeInfo.ACTION_CLICK || it.id == AccessibilityNodeInfo.ACTION_LONG_CLICK ||
                    it.id == AccessibilityNodeInfo.ACTION_DISMISS || it.id == AccessibilityNodeInfo.ACTION_EXPAND ||
                    it.id == AccessibilityNodeInfo.ACTION_COLLAPSE ||
                    it.id > 0x01FFFFFF && !it.label.isNullOrBlank()
            }) return true
        // Use actual existing speech, including roles, states, associated/custom labels
        // and grouped content. Input focusability alone is not a useful wrap destination.
        return reader.read(compat).isNotBlank()
    }

    // Missing/stale children cannot establish a true boundary. Only used after exhaustion.
    private fun completeTree(root: AccessibilityNodeInfo, target: AccessibilityNodeInfo): Boolean = try {
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        val seen = mutableSetOf<AccessibilityNodeInfo>()
        pending.add(root)
        var complete = true
        while (pending.isNotEmpty() && complete) {
            val node = pending.removeLast()
            val firstVisit = seen.add(node)
            if (!firstVisit || node.windowId != root.windowId || !node.refresh()) {
                complete = false
            } else {
                for (index in 0 until node.childCount) {
                    val child = node.getChild(index)
                    if (child == null) {
                        complete = false; break
                    }
                    pending.add(child)
                }
            }
        }
        complete && target in seen
    } catch (error: RuntimeException) {
        false
    }

    // A provider may add content or a scroll action during refresh. Recheck the fresh
    // resolved set before emitting a cue rather than trusting the earlier snapshot.
    private fun stillExhausted(root: AccessibilityNodeInfo, target: AccessibilityNodeInfo, forward: Boolean): Boolean {
        val order = TraversalOrder(root)
        val nodes = order.nodes()
        val web = focus.webNavigation
        val container = web.boundary(target)
        // Native tree exhaustion cannot prove the end of provider-owned web content.
        if (container != null && !web.exhausted(root, target, forward)) return false
        val pivot = container ?: target
        val index = nodes.indexOf(pivot)
        if (index < 0) {
            return false
        }
        val remaining = if (forward) nodes.drop(index + 1) else nodes.take(index)
        val next = remaining.firstOrNull {
            (container == null || !order.contains(container, it)) && focus.navigationCandidate(it)
        }
        if (next != null) {
            return false
        }
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        val blocker = order.ancestorsOf(pivot).firstOrNull { parent ->
            parent.actionList.any { it.id == action } &&
                !(forward && focus.hasExhaustedForwardScroll(parent, root, target))
        }
        return blocker == null
    }
    private fun feedback() {
        if (preferences.audibleNavigationFeedback) player.play(PCM, SAMPLE_RATE)
        if (preferences.hapticNavigationFeedback && vibration.hasVibrator()) vibration.vibrateBoundary()
    }

    /** A selector-search cue only: no traversal, wrap arming or additional haptics. */
    fun playBoundarySound() {
        cancel()
        if (!finished && preferences.audibleNavigationFeedback) player.play(PCM, SAMPLE_RATE)
    }

    fun cancel() {
        focus.clearForwardScrollProofs()
        armedEdge = null
        cancelRequest()
    }

    private fun cancelRequest() {
        resultHandler?.let { focus.cancelPendingNavigation(it) }
        resultHandler = null
        origin = null
        player.stop()
    }

    override fun handle(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED -> cancel()
            // These precede the next swipe too. Cancel its predecessor's pending
            // work/audio, but retain the edge until the gesture/context is known.
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START,
            AccessibilityEvent.TYPE_GESTURE_DETECTION_START -> cancelRequest()
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> if (Build.VERSION.SDK_INT < 28 ||
                event.windowChanges and AccessibilityEvent.WINDOWS_CHANGE_ACTIVE != 0) cancel()
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED ->
                if (event.source != origin && event.source != armedEdge?.target) cancel()
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> armedEdge = null
            AccessibilityEvent.TYPE_VIEW_HOVER_ENTER -> if (event.source != armedEdge?.target) armedEdge = null
        }
    }

    override fun finish() { finished = true; cancel() }

    internal companion object {
        const val SAMPLE_RATE = 16000
        // Two 40 ms, 320 Hz pulses with a 20 ms gap; 100 ms total, quiet and faded.
        val PCM = ByteArray(SAMPLE_RATE / 10 * 2).apply {
            val pulse = SAMPLE_RATE * 40 / 1000
            val gap = SAMPLE_RATE * 20 / 1000
            val fade = SAMPLE_RATE * 4 / 1000
            for (start in listOf(0, pulse + gap)) for (frame in 0 until pulse) {
                val envelope = minOf(frame, pulse - 1 - frame, fade).toDouble() / fade
                val sample = (sin(2 * PI * 320 * frame / SAMPLE_RATE) * 32767 * 0.08 * envelope).toInt()
                val offset = (start + frame) * 2
                this[offset] = sample.toByte()
                this[offset + 1] = (sample shr 8).toByte()
            }
        }
    }
}
