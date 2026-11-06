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
package com.neo.speaktouch.utils

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat

/** Tracks an explicitly announced pane without confusing missing event metadata with dismissal. */
internal class AccessibilityPaneState {
    data class Pane(val root: AccessibilityNodeInfo, val node: AccessibilityNodeInfo,
                    val title: String, val generation: Long, val appearedAt: Long) {
        fun contains(target: AccessibilityNodeInfo?): Boolean = belongsTo(target, node)
    }
    enum class Change { NONE, APPEARED, DISAPPEARED }
    private var pane: Pane? = null
    private var generation = 0L
    val currentGeneration: Long? get() = pane?.generation

    fun clear() { pane = null }

    fun observe(event: AccessibilityEvent, root: AccessibilityNodeInfo?): Change {
        return try { observePane(event, root) } catch (_: RuntimeException) { clear(); Change.NONE }
    }

    private fun observePane(event: AccessibilityEvent, root: AccessibilityNodeInfo?): Change {
        val old = pane
        if (root == null || old != null && old.root != root) clear()
        if (root == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.windowId >= 0 && event.windowId != root.windowId ||
            event.packageName != null && event.packageName.toString() != root.packageName?.toString()) return Change.NONE
        val source = event.source
        val flags = event.contentChangeTypes
        val current = pane
        if (current != null && event.eventTime < current.appearedAt) return Change.NONE
        if (flags and AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_DISAPPEARED != 0) {
            // A removed pane often has no event source; Android still supplies its window/title.
            val title = event.text.singleOrNull()?.toString()
            if (current != null && (source == current.node || source == null &&
                    (title.isNullOrBlank() || title == current.title))) {
                clear()
                return Change.DISAPPEARED
            }
            return Change.NONE
        }
        if (flags and (AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_APPEARED or
                AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_TITLE) == 0 || source == null) return Change.NONE
        val liveSource = resolve(source, root) ?: return Change.NONE
        if (liveSource.windowId != root.windowId || liveSource.packageName?.toString() != root.packageName?.toString()) return Change.NONE
        val title = AccessibilityNodeInfoCompat.wrap(liveSource).paneTitle?.toString()?.trim()
            ?.takeIf { it.isNotEmpty() } ?: return Change.NONE
        if (current?.node == liveSource && current.title == title) return Change.NONE
        pane = Pane(root, liveSource, title, ++generation, event.eventTime)
        return Change.APPEARED
    }

    fun current(root: AccessibilityNodeInfo): Pane? {
        return try { validPane(root) } catch (_: RuntimeException) { clear(); null }
    }

    private fun validPane(root: AccessibilityNodeInfo): Pane? {
        val current = pane ?: return null
        val source = if (current.root == root) resolve(current.node, root) else null
        if (source == null || source.windowId != root.windowId ||
            source.packageName?.toString() != root.packageName?.toString() ||
            AccessibilityNodeInfoCompat.wrap(source).paneTitle?.toString()?.trim() != current.title) {
            clear()
            return null
        }
        return current.copy(root = root, node = source).also { pane = it }
    }

    /** Event sources are snapshots. Follow only their known identity path from the current root;
     * a cached parent link alone does not establish that a pane still belongs to the live tree. */
    private fun resolve(target: AccessibilityNodeInfo, root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val path = mutableListOf<AccessibilityNodeInfo>()
        var ancestor: AccessibilityNodeInfo? = target
        val seen = mutableSetOf<AccessibilityNodeInfo>()
        while (ancestor != root) {
            if (ancestor == null || !seen.add(ancestor)) return null
            path.add(ancestor)
            ancestor = ancestor.parent
        }
        if (!root.refresh() || !root.isVisibleToUser) return null
        var current = root
        for (identity in path.asReversed()) {
            val parent = current
            current = (0 until parent.childCount).asSequence().mapNotNull { parent.getChild(it) }
                .firstOrNull { it == identity } ?: return null
            if (!current.refresh() || !current.isVisibleToUser) return null
        }
        return current
    }

    private companion object {
        fun belongsTo(target: AccessibilityNodeInfo?, ancestor: AccessibilityNodeInfo): Boolean {
            var node = target
            val seen = mutableSetOf<AccessibilityNodeInfo>()
            while (node != null && seen.add(node)) {
                if (node == ancestor) return true
                node = node.parent
            }
            return false
        }
    }
}
