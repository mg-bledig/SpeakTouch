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
import android.os.SystemClock
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.neo.speaktouch.intercepter.event.CallbackInterceptor
import com.neo.speaktouch.intercepter.event.contract.EventInterceptor
import com.neo.speaktouch.model.Type
import com.neo.speaktouch.utils.SpeechPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ServiceScoped
import javax.inject.Inject
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.pow

/** Confirmed vertical scrolling only. Never infers movement or an edge from silence. */
@ServiceScoped
class ScrollFeedbackController @Inject constructor(
    private val service: ServiceController,
    private val callbacks: CallbackInterceptor,
    @ApplicationContext context: Context
) : EventInterceptor {
    private val preferences = SpeechPreferences(context)
    private val player = ShortPcmPlayer()
    private var activeSource: AccessibilityNodeInfo? = null
    private var state: ScrollState? = null
    private var lastSubmissionAt: Long? = null
    private val helperScrolls = mutableSetOf<AccessibilityNodeInfo>()
    private var activeRoot: AccessibilityNodeInfo? = null
    private var activeWindow: Int? = null
    private var finished = false
    private var manualScroll: ConfirmedManualScroll? = null

    /** Read-only classification for the next interceptor; independent of audio settings/throttling. */
    internal data class ConfirmedManualScroll(
        val source: AccessibilityNodeInfo, val root: AccessibilityNodeInfo,
        val windowId: Int, val eventTime: Long, val forward: Boolean
    )

    internal fun confirmedManualScroll(event: AccessibilityEvent): ConfirmedManualScroll? =
        manualScroll?.takeIf {
            event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED && event.eventTime == it.eventTime &&
                event.windowId == it.windowId && event.source == it.source
        }

    fun start() { reset(); lastSubmissionAt = null; finished = false }

    override fun handle(event: AccessibilityEvent) {
        manualScroll = null
        if (finished) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> { reset(); return }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                if (Build.VERSION.SDK_INT < 28 || event.windowChanges and AccessibilityEvent.WINDOWS_CHANGE_ACTIVE != 0) reset()
                return
            }
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> { helperScrolls.clear(); player.stop(); return }
            AccessibilityEvent.TYPE_GESTURE_DETECTION_START -> { player.stop(); return }
        }
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED) return
        try {
            val node = event.source ?: run { return }
            val root = service.getRoot() ?: run { reset(); return }
            if (!valid(event, node, root)) {
                if (activeSource == node) { state = null; activeSource = null }
                player.stop(); return
            }
            if (activeRoot != root || activeWindow != root.windowId) {
                reset(); activeRoot = root; activeWindow = root.windowId
            }
            if (activeSource != node) { player.stop(); state = null; activeSource = node }
            // Mark helpers even when their event duplicates the last manual position.
            if (callbacks.hasPendingScroll(node)) { helperScrolls.add(node); player.stop() }
            val metadata = Position(event)
            val previous = state
            if (previous != null && metadata.eventTime > 0 && previous.metadata.eventTime > metadata.eventTime) { return }
            if (metadata == previous?.metadata) { return }
            val normalized = normalize(metadata, node)
            val movement = direction(metadata, previous?.metadata, node)
            // All valid events update state, including silent/throttled/helper events.
            state = ScrollState(metadata, normalized, movement ?: previous?.lastDirection)
            if (node in helperScrolls) { player.stop(); return }
            if (movement != null) {
                manualScroll = ConfirmedManualScroll(node, root, event.windowId, event.eventTime, movement > 0)
            }
            if (!preferences.audibleNavigationFeedback) { player.stop(); return }
            if (normalized == null) { player.stop(); return }
            if (previous != null && (previous.normalized == null ||
                    normalized.basis != previous.normalized.basis || normalized.total != previous.normalized.total || normalized.columns != previous.normalized.columns)) {
                player.stop(); return // Rebaseline changed normalization; it is not scroll progress.
            }
            if (movement == null) { return }
            val change = previous?.normalized?.let { normalized.fraction.compareTo(it.fraction) }
            if (change != null && change != 0 && change != movement) { return }
            // Pixel positions are exact: an unchanged offset is not additional movement.
            // Indices can stay the same while confirmed deltas move within a tall item.
            if (normalized.basis == Basis.OFFSET && change == 0) { return }
            val now = SystemClock.uptimeMillis()
            if (lastSubmissionAt?.let { now - it < MIN_SEPARATION_MS } == true) { return }
            val samples = pcm(normalized.fraction) ?: run { return }
            lastSubmissionAt = now
            player.play(samples, SAMPLE_RATE)
        } catch (error: RuntimeException) {
            reset() // Optional feedback must never interrupt the accessibility event pipeline.
        }
    }

    private fun valid(event: AccessibilityEvent, node: AccessibilityNodeInfo, root: AccessibilityNodeInfo): Boolean {
        if (!node.refresh()) { return false }
        if (!node.isVisibleToUser) { return false }
        if (!node.isScrollable && !event.isScrollable) { return false }
        if (node.windowId != root.windowId || event.windowId >= 0 && event.windowId != node.windowId) { return false }
        val pkg = root.packageName?.toString()?.takeIf { it.isNotBlank() } ?: run { return false }
        if (node.packageName?.toString() != pkg || event.packageName != null && event.packageName.toString() != pkg) { return false }
        if (root.window?.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) { return false }
        val type = Type.get(AccessibilityNodeInfoCompat.wrap(node))
        if (type == Type.Slider || type == Type.ProgressBar) { return false }
        var ancestor: AccessibilityNodeInfo? = node
        val seen = mutableSetOf<AccessibilityNodeInfo>()
        while (ancestor != null && seen.add(ancestor)) {
            if (ancestor == root) return true
            ancestor = ancestor.parent
        }
        return false
    }

    private enum class Basis { OFFSET, INDICES }
    private data class Normalized(val fraction: Double, val basis: Basis, val total: Int, val columns: Int = 1)
    private data class ScrollState(val metadata: Position, val normalized: Normalized?, val lastDirection: Int?)

    private fun normalize(position: Position, node: AccessibilityNodeInfo): Normalized? {
        if (position.maxY > 0 && position.y in 0..position.maxY) {
            return Normalized(position.y.toDouble() / position.maxY, Basis.OFFSET, position.maxY)
        }
        val collection = node.collectionInfo ?: return null
        if (collection.columnCount < 1 || collection.rowCount <= 1 || position.count <= 1 ||
            position.from !in 0 until position.count ||
            position.to != -1 && (position.to < position.from || position.to >= position.count)) return null
        if (collection.columnCount > 1) {
            // A grid's indices alone do not establish its scroll axis. The Oppo
            // supplies vertical deltas even when both pixel offsets are zero.
            val horizontal = position.deltaX?.let { it != -1 && it != 0 } == true ||
                state?.metadata?.let { position.x >= 0 && it.x >= 0 && position.x != it.x } == true
            val verticalDelta = position.deltaY?.let { it != -1 && it != 0 } == true
            val established = state?.normalized?.let {
                it.basis == Basis.INDICES && it.columns == collection.columnCount && it.total == position.count
            } == true
            if (horizontal || (!verticalDelta && !established)) return null
        }
        return Normalized(position.from.toDouble() / position.count, Basis.INDICES, position.count, collection.columnCount)
    }
    private fun direction(now: Position, before: Position?, node: AccessibilityNodeInfo): Int? {
        val offset = before?.let {
            if (now.y >= 0 && it.y >= 0 && (now.y > 0 || it.y > 0 || now.maxY > 0 || it.maxY > 0))
                now.y.compareTo(it.y) else null
        }
        // Android uses -1 for an unspecified delta, also ambiguous with a one-pixel
        // reverse scroll. Offset/index changes can still establish that movement.
        if (now.deltaY != null && now.deltaY != -1 && now.deltaY != 0) {
            val delta = now.deltaY.compareTo(0)
            if (offset != null && offset != 0 && offset != delta) return null
            return delta
        }
        val horizontal = now.deltaX?.let { it != -1 && it != 0 } == true ||
            before?.let { now.x >= 0 && it.x >= 0 && now.x != it.x } == true
        if (horizontal) return null
        if (offset != null && offset != 0) return offset
        // Index-only direction requires a single-column collection or a grid whose
        // vertical orientation was already established by an explicit delta.
        val collection = node.collectionInfo ?: return null
        if (collection.rowCount <= 1 || before == null) return null
        if (collection.columnCount != 1 && (state?.normalized?.basis != Basis.INDICES ||
                state?.normalized?.columns != collection.columnCount)) return null
        if (!now.validIndices() || !before.validIndices() || now.count != before.count) return null
        val first = now.from.compareTo(before.from)
        val last = now.to.compareTo(before.to)
        return first.takeIf { it != 0 && it == last }
    }

    private data class Position(
        val x: Int, val y: Int, val maxY: Int, val from: Int, val to: Int, val count: Int,
        val deltaX: Int?, val deltaY: Int?, val eventTime: Long
    ) {
        constructor(event: AccessibilityEvent) : this(event.scrollX, event.scrollY, event.maxScrollY,
            event.fromIndex, event.toIndex, event.itemCount,
            if (Build.VERSION.SDK_INT >= 28) event.scrollDeltaX else null,
            if (Build.VERSION.SDK_INT >= 28) event.scrollDeltaY else null, event.eventTime)
        fun validIndices() = count > 0 && from >= 0 && to >= from && to < count
    }

    private fun reset() {
        manualScroll = null
        player.stop(); state = null; helperScrolls.clear()
        activeSource = null; activeRoot = null; activeWindow = null
        // Preserve only the global audio separation clock across source/window changes.
    }
    override fun finish() { finished = true; reset(); lastSubmissionAt = null }

    internal companion object {
        const val SAMPLE_RATE = 16000
        const val DURATION_MS = 40
        const val FRAMES = SAMPLE_RATE * DURATION_MS / 1000
        const val MIN_SEPARATION_MS = 250L
        fun frequency(position: Double): Double? =
            if (position.isFinite() && position in 0.0..1.0) 900.0 * (1500.0 / 900.0).pow(position) else null

        fun pcm(position: Double): ByteArray? {
            val frequency = frequency(position) ?: return null
            return ByteArray(FRAMES * 2).apply {
                val fade = SAMPLE_RATE * 4 / 1000
                for (frame in 0 until FRAMES) {
                    val phase = 2 * PI * frequency * frame / SAMPLE_RATE
                    val envelope = minOf(frame, FRAMES - 1 - frame, fade).toDouble() / fade
                    val sample = (sin(phase) * 32767 * 0.05 * envelope).toInt()
                    this[frame * 2] = sample.toByte(); this[frame * 2 + 1] = (sample shr 8).toByte()
                }
            }
        }
    }
}
