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

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.neo.speaktouch.intercepter.event.CallbackInterceptor
import com.neo.speaktouch.model.NodeFilter
import com.neo.speaktouch.utils.TraversalOrder

/** Evidence from a completed helper scroll, scoped to one root/origin/filter. */
internal class ForwardScrollProgress {
    internal data class Item(
        val node: AccessibilityNodeInfo, val text: String?, val description: String?,
        val row: Int?, val column: Int?
    )
    internal data class Snapshot(
        val items: List<Item>, val bounds: List<Rect>,
        val rows: Int?, val columns: Int?, val position: CallbackInterceptor.Position?
    )
    private var root: AccessibilityNodeInfo? = null
    private var origin: AccessibilityNodeInfo? = null
    private var window: Int? = null
    private var filter: NodeFilter? = null
    private val exhausted = mutableMapOf<AccessibilityNodeInfo, Snapshot>()

    fun begin(root: AccessibilityNodeInfo?, origin: AccessibilityNodeInfo, filter: NodeFilter) {
        if (this.root != root || this.origin != origin || window != root?.windowId || this.filter !== filter) {
            clear()
            this.root = root; this.origin = origin; window = root?.windowId; this.filter = filter
        }
    }

    fun clear() { exhausted.clear(); root = null; origin = null; window = null; filter = null }

    fun hasProof(node: AccessibilityNodeInfo): Boolean = exhausted.containsKey(node)

    fun snapshot(node: AccessibilityNodeInfo, position: CallbackInterceptor.Position?): Snapshot? = try {
        val items = mutableListOf<Item>()
        val bounds = mutableListOf<Rect>()
        val seen = mutableSetOf<AccessibilityNodeInfo>()
        fun collect(current: AccessibilityNodeInfo): Boolean {
            if (!seen.add(current) || !current.refresh() || current.windowId != window) return false
            if (current != node && filter?.filter(current) == true) {
                val info = current.collectionItemInfo
                items.add(Item(current, current.text?.toString(), current.contentDescription?.toString(),
                    info?.rowIndex, info?.columnIndex))
                bounds.add(Rect().also { current.getBoundsInScreen(it) })
            }
            for (index in 0 until current.childCount) {
                if (!collect(current.getChild(index) ?: return false)) return false
            }
            return true
        }
        if (!collect(node)) null else {
            // Use resolved traversal order, not a resource ID (which recycled rows share).
            val order = TraversalOrder(node).nodes()
            val indices = items.indices.sortedBy { order.indexOf(items[it].node) }
            Snapshot(indices.map { items[it] }, indices.map { bounds[it] },
                node.collectionInfo?.rowCount, node.collectionInfo?.columnCount, position)
        }
    } catch (_: RuntimeException) { null }

    fun remember(node: AccessibilityNodeInfo, before: Snapshot, after: Snapshot): Boolean {
        exhausted.remove(node)
        if (advanced(before, after)) return true
        val position = after.position
        val pixelPosition = position?.takeIf { it.maxY > 0 && it.y in 0..it.maxY }
        val indexedPosition = position?.takeIf {
            it.count > 0 && it.from in 0 until it.count && it.to in it.from until it.count
        }
        val lastRow = indexedPosition != null && after.columns?.let { columns ->
            columns > 1 && after.rows == (indexedPosition.count - 1) / columns + 1 &&
                indexedPosition.to / columns == (indexedPosition.count - 1) / columns
        } == true
        val terminal = pixelPosition?.let { it.y == it.maxY } ?: indexedPosition?.let {
            it.to == it.count - 1 || lastRow
        }
        // A partial animation frame in the middle of a list is not a proven edge.
        // Stop this retry safely rather than infer exhaustion or request scrolling forever.
        if (terminal == false || terminal == null && before.bounds != after.bounds) return false
        exhausted[node] = after
        return true
    }

    fun proven(node: AccessibilityNodeInfo, current: Snapshot?): Boolean {
        val proof = exhausted[node] ?: return false
        if (current == proof) return true
        exhausted.remove(node)
        return false
    }

    fun matches(root: AccessibilityNodeInfo, origin: AccessibilityNodeInfo): Boolean =
        this.root == root && this.origin == origin && window == root.windowId

    internal companion object {
        fun advanced(before: Snapshot, after: Snapshot): Boolean {
            val a = before.position; val b = after.position
            if (a != null && b != null) {
                if (a.count > 0 && b.count > 0 && a.count != b.count) return true
                if (a.count > 0 && a.count == b.count && a.to in 0 until a.count &&
                    b.to in 0 until b.count && b.to > a.to) return true
                if (a.maxY > 0 && b.maxY == a.maxY && a.y in 0..a.maxY &&
                    b.y in 0..b.maxY && b.y > a.y) return true
            }
            if (before.rows != after.rows || before.columns != after.columns) return true
            val tail = before.items.lastOrNull() ?: return true // Empty trees cannot prove an edge.
            val index = after.items.indexOf(tail)
            // New trailing content (including a recycled node with a new label/item position)
            // is progress. Merely dropping leading rows or moving their bounds is not.
            return after.items.isEmpty() || index < 0 || index != after.items.lastIndex
        }
    }
}
