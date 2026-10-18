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
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.neo.speaktouch.intercepter.event.CallbackInterceptor
import com.neo.speaktouch.intercepter.event.contract.EventInterceptor
import com.neo.speaktouch.model.Type
import dagger.hilt.android.scopes.ServiceScoped
import javax.inject.Inject

/** Movement evidence for focus recovery. Deliberately independent of scroll sound policy. */
@ServiceScoped
class ManualScrollInterpreter @Inject constructor(
    private val service: ServiceController,
    private val callbacks: CallbackInterceptor
) : EventInterceptor {
    internal enum class Axis { HORIZONTAL, VERTICAL }
    internal data class Movement(val axis: Axis, val forward: Boolean)
    internal data class Scroll(val source: AccessibilityNodeInfo, val root: AccessibilityNodeInfo,
        val windowId: Int, val eventTime: Long, val movement: Movement)
    private data class Position(val x: Int, val y: Int, val maxX: Int, val maxY: Int,
        val from: Int, val to: Int, val count: Int, val dx: Int?, val dy: Int?, val time: Long) {
        constructor(event: AccessibilityEvent) : this(event.scrollX, event.scrollY,
            event.maxScrollX, event.maxScrollY, event.fromIndex, event.toIndex, event.itemCount,
            if (Build.VERSION.SDK_INT >= 28) event.scrollDeltaX else null,
            if (Build.VERSION.SDK_INT >= 28) event.scrollDeltaY else null, event.eventTime)
        fun indices() = count > 0 && from >= 0 && to >= from && to < count
        fun validOffsets() = x >= -1 && y >= -1 && (maxX <= 0 || x <= maxX) && (maxY <= 0 || y <= maxY)
        // Default zero offsets in unrelated content events are not a position baseline.
        fun meaningful() = x > 0 || y > 0 || maxX > 0 && x in 0..maxX ||
            maxY > 0 && y in 0..maxY || indices()
    }
    private data class State(val position: Position, val axis: Axis?)
    private val positions = linkedMapOf<AccessibilityNodeInfo, State>()
    private val helpers = mutableSetOf<AccessibilityNodeInfo>()
    private var root: AccessibilityNodeInfo? = null
    private var window: Int? = null
    private var finished = false
    private var confirmed: Scroll? = null

    fun start() { reset(); finished = false }
    internal fun confirmedScroll(event: AccessibilityEvent): Scroll? = confirmed?.takeIf {
        event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED && event.eventTime == it.eventTime &&
            event.source == it.source
    }
    internal fun navigationOwned(node: AccessibilityNodeInfo) = node in helpers || callbacks.hasPendingScroll(node)

    override fun handle(event: AccessibilityEvent) {
        confirmed = null
        if (finished) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> { reset(); return }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                if (Build.VERSION.SDK_INT < 28 || event.windowChanges and AccessibilityEvent.WINDOWS_CHANGE_ACTIVE != 0) reset()
                return
            }
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> { helpers.clear(); return }
        }
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return
        try {
            val active = service.getRoot() ?: run { reset(); return }
            if (root != active || window != active.windowId) {
                reset(); root = active; window = active.windowId
            }
            val source = event.source ?: return
            if (!source.refresh() || !source.isVisibleToUser ||
                (!source.isScrollable && !event.isScrollable) || source.windowId != active.windowId ||
                event.windowId >= 0 && event.windowId != source.windowId ||
                source.packageName == null || source.packageName.toString() != active.packageName?.toString() ||
                event.packageName != null && event.packageName.toString() != active.packageName?.toString() ||
                active.window?.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY ||
                !belongsTo(source, active)) return
            val type = Type.get(AccessibilityNodeInfoCompat.wrap(source))
            if (type == Type.Slider || type == Type.ProgressBar) return
            if (callbacks.hasPendingScroll(source)) helpers.add(source)
            val now = Position(event)
            if (!now.validOffsets()) return
            val before = positions[source]
            if (before != null && now.time > 0 && now.time < before.position.time) return
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                // Seed only a missing baseline; later content events must not mask the next scroll.
                if (before == null && now.meaningful()) {
                    positions[source] = State(now, null)
                    trim()
                }
                return
            }
            val movement = direction(now, before, source)
            positions[source] = State(now, movement?.axis ?: before?.axis)
            trim()

            if (movement != null && !navigationOwned(source)) {
                confirmed = Scroll(source, active, active.windowId, event.eventTime, movement)
            }
        } catch (_: RuntimeException) { confirmed = null }
    }

    private fun direction(now: Position, before: State?, source: AccessibilityNodeInfo): Movement? {
        fun delta(value: Int?) = value?.takeIf { it != -1 && it != 0 }?.compareTo(0)
        fun offset(current: Int, previous: Int?) = previous?.takeIf { it >= 0 && current >= 0 }
            ?.let { current.compareTo(it) }?.takeIf { it != 0 }
        val x = offset(now.x, before?.position?.x)
        val y = offset(now.y, before?.position?.y)
        val dx = delta(now.dx); val dy = delta(now.dy)
        if (x != null && dx != null && x != dx || y != null && dy != null && y != dy) return null
        val horizontal = x ?: dx
        val vertical = y ?: dy
        if (horizontal != null && vertical != null) return null // No diagonal guessing.
        var movement = horizontal?.let { Movement(Axis.HORIZONTAL, it > 0) }
            ?: vertical?.let { Movement(Axis.VERTICAL, it > 0) }
        val previous = before?.position
        if (previous != null && now.indices() && previous.indices() && now.count == previous.count) {
            val first = now.from.compareTo(previous.from)
            val last = now.to.compareTo(previous.to)
            // A changing last visible item alone can mean expansion rather than scrolling.
            if (first != 0 && last != 0 && first != last) return null
            if (movement != null && first != 0 && (first > 0) != movement.forward) return null
            if (movement == null && first != 0 && first == last) {
                val collection = source.collectionInfo ?: return null
                val axis = when {
                    collection.rowCount > 1 && collection.columnCount == 1 -> Axis.VERTICAL
                    collection.columnCount > 1 && collection.rowCount == 1 -> Axis.HORIZONTAL
                    else -> before.axis
                } ?: return null
                movement = Movement(axis, first > 0)
            }
        }
        return movement
    }

    private fun trim() { if (positions.size > 16) positions.remove(positions.keys.first()) }
    private fun belongsTo(node: AccessibilityNodeInfo, ancestor: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        val seen = mutableSetOf<AccessibilityNodeInfo>()
        while (current != null && seen.add(current)) {
            if (current == ancestor) return true
            current = current.parent
        }
        return false
    }
    private fun reset() { positions.clear(); helpers.clear(); root = null; window = null; confirmed = null }
    override fun finish() { finished = true; reset() }
}
