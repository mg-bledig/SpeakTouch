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
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.neo.speaktouch.R
import com.neo.speaktouch.intercepter.event.CallbackInterceptor
import com.neo.speaktouch.intercepter.event.contract.EventInterceptor
import com.neo.speaktouch.model.Text
import com.neo.speaktouch.model.Type
import com.neo.speaktouch.utils.NodeValidator
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ServiceScoped
import javax.inject.Inject

/** Provider-owned text positions. Never segments Reader speech or changes accessibility focus. */
@ServiceScoped
class NavigationGranularityController @Inject constructor(
    private val service: ServiceController,
    private val speech: SpeechController,
    private val callbacks: CallbackInterceptor,
    @param:ApplicationContext private val context: Context
) : EventInterceptor {
    enum class Mode(val mask: Int, val label: Int) {
        DEFAULT(0, R.string.navigation_default),
        CHARACTERS(AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER, R.string.navigation_characters),
        WORDS(AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD, R.string.navigation_words),
        LINES(AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE, R.string.navigation_lines)
    }
    // A provider rejection is not proof that the entire window is exhausted.
    enum class Result { DEFAULT, ACCEPTED, NO_MOVEMENT, UNAVAILABLE }
    private data class Scope(val root: AccessibilityNodeInfo, val anchor: AccessibilityNodeInfo, val window: Int)
    private class Request(val scope: Scope, val target: AccessibilityNodeInfo, val mode: Mode,
        val action: Int, val time: Long, val speechGeneration: Long) {
        var accepted = false
        var earlyEvent: AccessibilityEvent? = null
    }
    private data class EventKey(val source: AccessibilityNodeInfo, val window: Int, val action: Int,
        val granularity: Int, val from: Int, val to: Int, val time: Long)
    var selectedMode = Mode.DEFAULT
        private set
    private var request: Request? = null
    private var lastEvent: EventKey? = null
    private var cursorScope: Scope? = null
    private var cursorTarget: AccessibilityNodeInfo? = null
    private var finished = false

    fun start() { finish(); finished = false; selectedMode = Mode.DEFAULT }

    fun cancelPending() {
        request?.let { pending -> pending.earlyEvent?.recycle(); pending.earlyEvent = null }
        request = null
    }

    private fun scope(): Scope? {
        val root = service.getRoot() ?: return null
        val anchor = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY) ?: return null
        val scope = Scope(root, anchor, root.windowId)
        return scope.takeIf { anchor.refresh() && valid(it) }
    }

    private fun valid(scope: Scope): Boolean {
        val active = service.getRoot() ?: return false
        return scope.window >= 0 && scope.anchor.isVisibleToUser && scope.anchor.isAccessibilityFocused &&
            scope.anchor.windowId == scope.window && active.windowId == scope.window && active == scope.root &&
            !active.packageName.isNullOrBlank() &&
            scope.anchor.packageName?.toString() == active.packageName?.toString() &&
            active.window?.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
            active.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY) == scope.anchor
    }

    private fun supports(node: AccessibilityNodeInfo, mode: Mode, action: Int? = null): Boolean =
        node.movementGranularities and mode.mask != 0 && node.actionList.any {
            if (action != null) it.id == action else
                it.id == AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY ||
                    it.id == AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY
        }

    /** Only the focused description subtree, excluding independent controls and web documents. */
    private fun targets(scope: Scope): List<AccessibilityNodeInfo> {
        if (Type.get(AccessibilityNodeInfoCompat.wrap(scope.anchor)) == Type.Slider) return emptyList()
        val result = mutableListOf<AccessibilityNodeInfo>()
        val visited = mutableSetOf<AccessibilityNodeInfo>()
        fun collect(node: AccessibilityNodeInfo) {
            if (!visited.add(node) || !node.isVisibleToUser || node.windowId != scope.window ||
                node.packageName?.toString() != scope.root.packageName?.toString()) return
            if (Mode.entries.any { it != Mode.DEFAULT && supports(node, it) }) result.add(node)
            val compat = AccessibilityNodeInfoCompat.wrap(node)
            // A provider document is not a grouped native description: do not scan its DOM.
            if (!node.contentDescription.isNullOrBlank() || node.isEditable || node.isCheckable ||
                NodeValidator.isExplorableCollection(compat) || node.actionList.any {
                    it.id == AccessibilityNodeInfo.ACTION_NEXT_HTML_ELEMENT ||
                        it.id == AccessibilityNodeInfo.ACTION_PREVIOUS_HTML_ELEMENT
                }) return
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                if (NodeValidator.isReadableAsChild(AccessibilityNodeInfoCompat.wrap(child))) collect(child)
            }
        }
        collect(scope.anchor)
        return result
    }

    fun availableModes(): List<Mode> = try {
        if (finished) listOf(Mode.DEFAULT) else {
            val scope = scope()
            val targets = scope?.let(::targets).orEmpty()
            Mode.entries.filter { it == Mode.DEFAULT || targets.any { node -> supports(node, it) } }
        }
    } catch (_: RuntimeException) { listOf(Mode.DEFAULT) }

    fun effectiveMode(): Mode = selectedMode.takeIf { it in availableModes() } ?: Mode.DEFAULT

    fun cycle(forward: Boolean) {
        if (finished) return
        cancelPending()
        val modes = availableModes()
        val index = modes.indexOf(selectedMode)
        val next = if (index < 0) { if (forward) 0 else modes.lastIndex }
            else (index + if (forward) 1 else modes.size - 1) % modes.size
        selectedMode = modes[next]
        speech.speak(Text(selectedMode.label))
    }

    private fun belongsTo(node: AccessibilityNodeInfo, ancestor: AccessibilityNodeInfo): Boolean {
        val seen = mutableSetOf<AccessibilityNodeInfo>()
        var current: AccessibilityNodeInfo? = node
        while (current != null && seen.add(current)) {
            if (current == ancestor) return true
            current = current.parent
        }
        return false
    }

    private fun validTarget(scope: Scope, target: AccessibilityNodeInfo): Boolean {
        if (!target.refresh()) return false
        if (target != scope.anchor && !scope.anchor.refresh()) return false
        return target.isVisibleToUser && target.windowId == scope.window &&
            (target != scope.anchor || target.isAccessibilityFocused) &&
            target.packageName?.toString() == scope.root.packageName?.toString() &&
            belongsTo(target, scope.anchor) && valid(scope)
    }

    private fun ownScrolling(scope: Scope) {
        val web = WebNavigationController(service, callbacks).boundary(scope.anchor)
        var owner = web ?: scope.anchor
        if (web == null) {
            val seen = mutableSetOf<AccessibilityNodeInfo>()
            var current: AccessibilityNodeInfo? = scope.anchor
            while (current != null && seen.add(current) && current.windowId == scope.window) {
                if (current.isScrollable) { owner = current; break }
                current = current.parent
            }
        }
        callbacks.markProviderNavigation(scope.root, owner)
    }

    fun move(forward: Boolean): Result {
        cancelPending()
        if (finished) return Result.UNAVAILABLE
        if (selectedMode == Mode.DEFAULT) return Result.DEFAULT
        return try {
            val scope = scope() ?: return Result.UNAVAILABLE
            val candidates = targets(scope).filter { supports(it, selectedMode) }
            if (candidates.isEmpty()) {
                // Losing capabilities in an active text session is not permission to wrap
                // the window. A genuinely different unsupported target can use Default.
                return if (cursorScope == scope) Result.UNAVAILABLE else Result.DEFAULT
            }
            if (cursorScope != scope) { cursorScope = scope; cursorTarget = null }
            val action = if (forward) AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY
                else AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY
            val index = candidates.indexOf(cursorTarget).takeIf { it >= 0 }
                ?: if (forward) 0 else candidates.lastIndex
            val indices = if (forward) index..candidates.lastIndex else index downTo 0
            for (i in indices) {
                val target = candidates[i]
                if (!validTarget(scope, target)) return Result.UNAVAILABLE
                if (!supports(target, selectedMode, action)) continue
                val pending = Request(scope, target, selectedMode, action, SystemClock.uptimeMillis(), speech.generation)
                request = pending
                ownScrolling(scope)
                val accepted = target.performAction(action, Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT, selectedMode.mask)
                    putBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN, false)
                })
                if (accepted) {
                    if (request !== pending) return Result.ACCEPTED
                    cursorTarget = target
                    pending.accepted = true
                    pending.earlyEvent?.let { event ->
                        pending.earlyEvent = null
                        try { handle(event) } finally { event.recycle() }
                    }
                    return Result.ACCEPTED // Provider events, not synchronous focus changes, supply speech.
                }
                cancelPending()
                if (!validTarget(scope, target)) return Result.UNAVAILABLE
            }
            Result.NO_MOVEMENT // Never arm the ordinary navigation boundary/wrap state.
        } catch (_: RuntimeException) { cancelPending(); Result.UNAVAILABLE }
    }

    private fun clearContext() {
        cancelPending(); cursorScope = null; cursorTarget = null; lastEvent = null
    }

    /** Chromium may report only offsets. Use one unambiguous source string, never Reader output. */
    private fun traversalText(event: AccessibilityEvent, source: AccessibilityNodeInfo): String? {
        if (event.text.isNotEmpty()) return event.text.singleOrNull()?.toString()
        // A displayed hint is not the editable value or a reliable offset basis.
        if (AccessibilityNodeInfoCompat.wrap(source).isShowingHintText ||
            source.isEditable && source.text.isNullOrBlank()) return null
        return listOfNotNull(source.text?.toString(), source.contentDescription?.toString())
            .filter { it.isNotBlank() }.distinct().singleOrNull()
    }

    override fun handle(event: AccessibilityEvent) {
        if (finished) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> { cancelPending(); return }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> { clearContext(); return }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                if (Build.VERSION.SDK_INT < 28 || event.windowChanges and AccessibilityEvent.WINDOWS_CHANGE_ACTIVE != 0) clearContext()
                return
            }
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED -> {
                if (event.source != cursorScope?.anchor) clearContext()
                return
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY -> Unit
            else -> return
        }
        val pending = request ?: return
        try {
            val source = event.source ?: return
            if (source != pending.target) return
            if (event.action != pending.action) return
            if (event.movementGranularity != pending.mode.mask) return
            if (event.eventTime < pending.time) return
            if (event.windowId >= 0 && event.windowId != pending.scope.window) return
            if (event.packageName != null && event.packageName.toString() != source.packageName?.toString()) return
            if (!pending.accepted) {
                pending.earlyEvent?.recycle()
                pending.earlyEvent = AccessibilityEvent.obtain(event)
                return
            }
            if (speech.generation != pending.speechGeneration) { cancelPending(); return }
            if (!validTarget(pending.scope, source)) { cancelPending(); return }
            if (!supports(source, pending.mode, pending.action)) { cancelPending(); return }
            val key = EventKey(source, source.windowId, event.action, event.movementGranularity,
                event.fromIndex, event.toIndex, event.eventTime)
            if (key == lastEvent) return
            val text = traversalText(event, source) ?: return
            val start = minOf(event.fromIndex, event.toIndex)
            val end = maxOf(event.fromIndex, event.toIndex)
            if (start < 0 || end > text.length || start >= end) return
            fun splitsSurrogate(offset: Int) = offset > 0 && offset < text.length &&
                Character.isHighSurrogate(text[offset - 1]) && Character.isLowSurrogate(text[offset])
            if (splitsSurrogate(start) || splitsSurrogate(end)) return
            // Reject an older text snapshot when the provider exposes its current text too.
            if (!source.text.isNullOrBlank() && source.text.toString() != text && source.contentDescription?.toString() != text) return
            // For an editing caret, a newer movement may already have superseded this range.
            if (source.isEditable && source.isFocused && source.textSelectionStart >= 0 &&
                source.textSelectionStart == source.textSelectionEnd &&
                source.textSelectionEnd != if (pending.action == AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY) end else start) return
            lastEvent = key
            cancelPending() // Exactly one traversal announcement per accepted movement.
            if (!event.isPassword && !source.isPassword && !pending.scope.anchor.isPassword) {
                val traversed = text.substring(start, end)
                speech.speak(if (pending.mode == Mode.CHARACTERS && traversed == " ")
                    Text(R.string.navigation_space) else Text(traversed))
            }
        } catch (_: RuntimeException) { cancelPending() }
    }

    override fun finish() {
        clearContext(); finished = true
    }
}
