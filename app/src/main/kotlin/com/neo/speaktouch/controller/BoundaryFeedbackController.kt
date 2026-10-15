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
import com.neo.speaktouch.utils.SpeechPreferences
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
    private val player = ShortPcmPlayer()
    private var resultHandler: ((FocusController.NavigationOutcome) -> Unit)? = null
    private var origin: AccessibilityNodeInfo? = null
    private var finished = false

    fun start() { cancel(); finished = false }

    fun navigate(forward: Boolean) {
        cancel()
        if (finished) return
        val target = service.getFocused()
        val root = service.getRoot()
        if (target == null || root == null) {
            // Keep existing initial-focus/fallback navigation, without claiming an edge.
            if (forward) focus.moveFocusToNext() else focus.moveFocusToPrevious()
            return
        }
        origin = target
        val window = root.windowId
        fun current(): Boolean = try {
            !finished && target.refresh() && target.isVisibleToUser && target.isAccessibilityFocused &&
                target.windowId == window && service.getRoot() == root &&
                service.getRoot()?.windowId == window && service.getFocused() == target
        } catch (_: RuntimeException) { false }
        if (!current()) { cancel(); return }
        lateinit var handler: (FocusController.NavigationOutcome) -> Unit
        val guard = {
            if (resultHandler === handler && current()) true else { cancel(); false }
        }
        handler = { outcome ->
            if (resultHandler === handler) {
                if (outcome != FocusController.NavigationOutcome.SCROLL_PENDING) {
                    val boundary = outcome == FocusController.NavigationOutcome.EXHAUSTED &&
                        current() && completeTree(root, target) && stillExhausted(root, target, forward) && current()
                    cancel()
                    if (boundary) feedback()
                }
            }
        }
        resultHandler = handler
        try {
            if (forward) focus.moveFocusToNext(onResult = handler, canContinue = guard)
            else focus.moveFocusToPrevious(onResult = handler, canContinue = guard)
        } catch (_: RuntimeException) { cancel() }
    }

    // Missing/stale children cannot establish a true boundary. Only used after exhaustion.
    private fun completeTree(root: AccessibilityNodeInfo, target: AccessibilityNodeInfo): Boolean = try {
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        val seen = mutableSetOf<AccessibilityNodeInfo>()
        pending.add(root)
        var complete = true
        while (pending.isNotEmpty() && complete) {
            val node = pending.removeLast()
            if (!seen.add(node) || node.windowId != root.windowId || !node.refresh()) {
                complete = false
            } else {
                for (index in 0 until node.childCount) {
                    val child = node.getChild(index)
                    if (child == null) { complete = false; break }
                    pending.add(child)
                }
            }
        }
        complete && target in seen
    } catch (_: RuntimeException) { false }

    // A provider may add content or a scroll action during refresh. Recheck the fresh
    // resolved set before emitting a cue rather than trusting the earlier snapshot.
    private fun stillExhausted(root: AccessibilityNodeInfo, target: AccessibilityNodeInfo, forward: Boolean): Boolean {
        val order = TraversalOrder(root)
        val nodes = order.nodes()
        val index = nodes.indexOf(target)
        if (index < 0) return false
        val remaining = if (forward) nodes.drop(index + 1) else nodes.take(index)
        if (remaining.any { NodeFilter.Focusable.filter(it) }) return false
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return order.ancestorsOf(target).none { parent -> parent.actionList.any { it.id == action } }
    }
    private fun feedback() {
        if (preferences.audibleNavigationFeedback) player.play(PCM, SAMPLE_RATE)
        if (preferences.hapticNavigationFeedback && vibration.hasVibrator()) vibration.vibrateBoundary()
    }

    fun cancel() {
        resultHandler?.let { focus.cancelPendingNavigation(it) }
        resultHandler = null
        origin = null
        player.stop()
    }

    override fun handle(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START,
            AccessibilityEvent.TYPE_GESTURE_DETECTION_START,
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED -> cancel()
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> if (Build.VERSION.SDK_INT < 28 ||
                event.windowChanges and AccessibilityEvent.WINDOWS_CHANGE_ACTIVE != 0) cancel()
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED -> if (event.source != origin) cancel()
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