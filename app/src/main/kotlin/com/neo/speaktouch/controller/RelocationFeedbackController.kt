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

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.neo.speaktouch.intercepter.event.contract.EventInterceptor
import com.neo.speaktouch.model.NodeFilter
import com.neo.speaktouch.model.Text
import com.neo.speaktouch.utils.TraversalOrder
import dagger.hilt.android.scopes.ServiceScoped
import javax.inject.Inject

/** Same-window repair after a Home custom action, not a second drag or gesture recognizer.
 * Inspired by BackTalk's subtree-change/ensure-focus policy; independently implemented.
 */
@ServiceScoped
class RelocationFeedbackController @Inject constructor(
    private val service: ServiceController,
    private val speech: SpeechController
) : EventInterceptor {
    internal data class Request(val root: AccessibilityNodeInfo, val pkg: String, val window: Int, val action: Int,
        val started: Long, val previous: Set<AccessibilityNodeInfo>)
    private data class Operation(val request: Request, var scope: AccessibilityNodeInfo? = null,
        var destinations: Set<AccessibilityNodeInfo> = emptySet(), var attempted: Boolean = false,
        var activatedAt: Long? = null, var announced: Boolean = false)
    private val handler = Handler(Looper.getMainLooper())
    private var operation: Operation? = null
    private var work: Runnable? = null

    internal fun prepare(target: AccessibilityNodeInfo, action: Int): Request? {
        finish()
        return runCatching {
            val root = service.getRoot() ?: return null
            val home = service.getOrThrow().packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY
            )?.activityInfo?.packageName ?: return null
            if (action <= 0x01FFFFFF || root.packageName?.toString() != home ||
                target.windowId != root.windowId || target.packageName?.toString() != home ||
                !validRoot(root)) return null
            Request(root, home, root.windowId, action, SystemClock.uptimeMillis(),
                TraversalOrder(root).nodes().filter { it.isVisibleToUser }.toSet())
        }.getOrNull()
    }

    internal fun accepted(request: Request?, accepted: Boolean) {
        if (request != null && accepted && valid(request)) operation = Operation(request)
    }

    override fun handle(event: AccessibilityEvent) {
        val current = operation ?: return
        try {
            val request = current.request
            // Expiry only discards ownership; it never means that an item moved.
            if (SystemClock.uptimeMillis() - request.started > OWNERSHIP_MS) {
                finish(); return
            }
            if (!valid(request)) {
                finish(); return
            }
            if (event.packageName != null && event.packageName.toString() != request.pkg) { return }
            if (event.eventTime < request.started || event.eventTime > SystemClock.uptimeMillis()) { return }
            if (event.windowId >= 0 && event.windowId != request.window) { finish(); return }
            when (event.eventType) {
                AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED -> {
                    val source = event.source ?: return
                    val scope = current.scope
                    if (scope != null && source !in request.previous && eligible(source, request.root) &&
                        belongsTo(source, scope)) current.destinations = current.destinations + source
                    if (current.destinations.isNotEmpty() && source !in current.destinations) {
                        finish()
                    } else if (source.isAccessibilityFocused && usable(source, request.root)) {
                        current.attempted = true; cancelWork()
                    }
                }
                AccessibilityEvent.TYPE_TOUCH_INTERACTION_START,
                AccessibilityEvent.TYPE_GESTURE_DETECTION_START,
                AccessibilityEvent.TYPE_VIEW_HOVER_ENTER -> { current.attempted = true; cancelWork() }
                AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                    if (event.source in current.destinations) noteActivation()
                }
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                    if (event.contentChangeTypes and 512 != 0) { finish(); return }
                    if (current.attempted || event.action != request.action ||
                        event.contentChangeTypes and AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE == 0 ||
                        SystemClock.uptimeMillis() - request.started > ENTRY_MS) return
                    val scope = event.source ?: return
                    if (!usable(scope, request.root) || !belongsTo(scope, request.root)) return
                    // Keep the more specific provider subtree when a subsequent event names the root.
                    if (current.scope == null || scope != request.root) current.scope = scope
                    cancelWork()
                    val task = Runnable { work = null; if (operation === current) recover(current) }
                    work = task
                    handler.postDelayed(task, SETTLE_MS)
                }
                AccessibilityEvent.TYPE_ANNOUNCEMENT -> announce(event, current)
            }
        } catch (error: RuntimeException) { finish() }
    }

    private fun recover(current: Operation) {
        try {
            val request = current.request
            if (!valid(request) || SystemClock.uptimeMillis() - request.started > ENTRY_MS) {
                finish(); return
            }
            val scope = current.scope ?: return
            if (!scope.refresh() || !usable(scope, request.root) || !belongsTo(scope, request.root)) return
            val candidates = TraversalOrder(scope).nodes().filter {
                it !in request.previous && eligible(it, request.root)
            }
            current.destinations = candidates.toSet()
            val focused = request.root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            if (focused != null && focused.refresh() && focused.isAccessibilityFocused &&
                usable(focused, request.root) && belongsTo(focused, request.root)) return
            val target = candidates.firstOrNull() ?: return
            // Recheck after remote queries. A provider-assigned focus always takes precedence.
            val latest = request.root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            if (latest != null && latest.refresh() && latest.isAccessibilityFocused &&
                usable(latest, request.root) && belongsTo(latest, request.root)) return
            if (operation !== current || !valid(request) || !target.refresh() ||
                !eligible(target, request.root) || !belongsTo(target, scope)) return
            current.attempted = true
            target.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
            // Only the real focus event speaks. Accepted asynchronous focus is never retried.
        } catch (_: RuntimeException) { finish() }
    }

    /** Only refresh the identified destination; ordinary Reader and native focus are unchanged. */
    fun focusedDestination(source: AccessibilityNodeInfo): AccessibilityNodeInfo? = runCatching {
        val current = operation ?: return null
        if (source !in current.destinations) { return null }
        if (!valid(current.request)) { return null }
        if (!source.refresh()) { return null }
        if (!source.isAccessibilityFocused) { return null }
        if (!eligible(source, current.request.root)) { return null }
        val scope = current.scope ?: return null
        if (!belongsTo(source, scope)) { return null }
        source
    }.getOrNull()

    /** Observe the ordinary framework click without performing or changing it. */
    fun noteActivation() {
        runCatching {
            val current = operation ?: return
            val focused = service.getFocused() ?: return
            if (focusedDestination(focused) != null) {
                current.activatedAt = SystemClock.uptimeMillis()
            }
        }
    }

    private fun announce(event: AccessibilityEvent, current: Operation) {
        // The evidenced terminal event is source-less, with ACTION_CLICK. Keep ownership after
        // finger-up/UI teardown, but never synthesize success from either of those transitions.
        val activation = current.activatedAt ?: return
        if (current.announced || event.eventTime < activation || event.eventTime - activation > ENTRY_MS ||
            current.destinations.isEmpty() || event.action != AccessibilityNodeInfo.ACTION_CLICK ||
            event.packageName?.toString() != current.request.pkg || event.isPassword) return
        val source = event.source
        if (source != null && (!usable(source, current.request.root) ||
                !belongsTo(source, current.request.root))) { return }
        val value = event.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            ?: event.text.filterNotNull().map(CharSequence::toString).filter(String::isNotBlank)
                .joinToString(", ").takeIf { it.isNotBlank() }
            ?: return
        // One provider terminal announcement per operation, not a general Home announcement reader.
        current.announced = true
        cancelWork()
        speech.speak(Text(value))
    }

    private fun valid(request: Request) = validRoot(request.root, request.window, request.pkg)

    private fun validRoot(root: AccessibilityNodeInfo, window: Int = root.windowId,
                          pkg: String? = root.packageName?.toString()): Boolean = runCatching { service.getRoot()?.let {
        it == root && it.windowId == window && it.packageName?.toString() == pkg &&
            root.refresh() && root.windowId == window && root.packageName?.toString() == pkg &&
            root.isVisibleToUser && root.window?.type == AccessibilityWindowInfo.TYPE_APPLICATION
    } == true }.getOrDefault(false)

    private fun usable(node: AccessibilityNodeInfo, root: AccessibilityNodeInfo): Boolean =
        node.windowId == root.windowId && node.packageName == root.packageName && node.isVisibleToUser &&
            node.isEnabled && !node.isPassword && !node.isEditable &&
            !AccessibilityNodeInfoCompat.wrap(node).isAccessibilityDataSensitive

    private fun eligible(node: AccessibilityNodeInfo, root: AccessibilityNodeInfo): Boolean =
        usable(node, root) && node.isClickable && NodeFilter.Focusable.filter(node) &&
            (!node.contentDescription.isNullOrBlank() || !node.text.isNullOrBlank())

    private fun belongsTo(node: AccessibilityNodeInfo, root: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        val seen = mutableSetOf<AccessibilityNodeInfo>()
        while (current != null && seen.add(current)) {
            if (current == root) return true
            current = current.parent
        }
        return false
    }

    private fun cancelWork() { work?.let(handler::removeCallbacks); work = null }
    override fun finish() {
        cancelWork(); operation = null
    }


    private companion object {
        const val SETTLE_MS = 150L
        const val ENTRY_MS = 10_000L
        const val OWNERSHIP_MS = 120_000L
    }
}
