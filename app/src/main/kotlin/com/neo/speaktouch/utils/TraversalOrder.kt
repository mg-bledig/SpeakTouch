package com.neo.speaktouch.utils

import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.neo.speaktouch.model.NodeFilter
import java.util.PriorityQueue

/** Per-navigation window snapshot; ordering and focus eligibility remain separate. */
class TraversalOrder(root: AccessibilityNodeInfo) {
    private val children = linkedMapOf<AccessibilityNodeInfo, List<AccessibilityNodeInfo>>()
    private val parents = mutableMapOf<AccessibilityNodeInfo, AccessibilityNodeInfo?>()
    private val baseline = mutableListOf<AccessibilityNodeInfo>()
    private val resolved: List<AccessibilityNodeInfo>
    val isReordered: Boolean get() = resolved != baseline

    init {
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        parents[root] = null
        pending.add(root)
        while (pending.isNotEmpty()) {
            val node = pending.removeLast()
            baseline.add(node)
            val discovered = buildList {
                for (index in 0 until node.childCount) {
                    val child = node.getChild(index) ?: continue
                    if (parents.containsKey(child)) continue
                    parents[child] = node
                    add(child)
                }
            }
            children[node] = discovered
            discovered.asReversed().forEach { pending.add(it) }
        }

        val positions = baseline.withIndex().associate { it.value to it.index }
        val outgoing = MutableList(baseline.size) { mutableSetOf<Int>() }
        val incoming = MutableList(baseline.size) { 0 }
        val declarations = mutableSetOf<Pair<AccessibilityNodeInfo, AccessibilityNodeInfo>>()
        fun addEdge(from: Int, to: Int) {
            if (from != to && outgoing[from].add(to)) incoming[to]++
        }
        fun subtree(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = buildList {
            val stack = ArrayDeque<AccessibilityNodeInfo>()
            stack.add(node)
            while (stack.isNotEmpty()) {
                val current = stack.removeLast()
                add(current)
                children.getValue(current).asReversed().forEach { stack.add(it) }
            }
        }
        fun constrain(before: AccessibilityNodeInfo?, after: AccessibilityNodeInfo?) {
            if (before == null || after == null || before == after) return
            if (before.windowId != root.windowId || after.windowId != root.windowId) return
            if (before !in children || after !in children) return
            fun owner(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
                if (!node.isVisibleToUser || NodeFilter.Focusable.filter(node)) return node
                return ancestorsOf(node).firstOrNull {
                    NodeValidator.mustFocus(AccessibilityNodeInfoCompat.wrap(it))
                } ?: node
            }
            // A passive member's content belongs to its existing focus group. Moving
            // that content must not create a second target or split the group's speech.
            val first = owner(before)
            val second = owner(after)
            if (first == second || first.windowId != root.windowId || second.windowId != root.windowId) return
            if (!declarations.add(first to second)) return
            if (contains(first, second) || contains(second, first)) {
                // Overlapping subtrees: honor the nodes' direct relationship without
                // manufacturing contradictory edges between their descendants.
                addEdge(positions.getValue(first), positions.getValue(second))
            } else {
                // A container anchor orders its content even if the container is filtered.
                // Leaf relationships affect the leaves, not their unrelated siblings.
                val from = subtree(first).map { positions.getValue(it) }
                val to = subtree(second).map { positions.getValue(it) }
                if (from.size > 1 && to.size > 1) {
                    // A zero-content fence avoids a quadratic number of edges for containers.
                    val fence = outgoing.size
                    outgoing.add(mutableSetOf())
                    incoming.add(0)
                    from.forEach { addEdge(it, fence) }
                    to.forEach { addEdge(fence, it) }
                } else {
                    for (source in from) for (target in to) addEdge(source, target)
                }
            }
        }
        for (node in baseline) {
            constrain(node, node.traversalBefore)
            constrain(node.traversalAfter, node)
        }

        // Process ready fences immediately so native-node ties still follow the baseline.
        val ready = PriorityQueue(compareBy<Int> { if (it < baseline.size) 1 else 0 }.thenBy { it })
        incoming.indices.filter { incoming[it] == 0 }.forEach { ready.add(it) }
        val result = mutableListOf<AccessibilityNodeInfo>()
        while (ready.isNotEmpty()) {
            val index = ready.remove()
            if (index < baseline.size) result.add(baseline[index])
            for (next in outgoing[index]) {
                if (--incoming[next] == 0) ready.add(next)
            }
        }
        // Cycles/conflicts never produce a partial order or a navigation loop.
        resolved = result.takeIf { it.size == baseline.size } ?: baseline.toList()
    }

    /** Resolved sequence based on structural preorder, including filtered anchors. */
    fun nodes(): List<AccessibilityNodeInfo> = resolved

    /** Physical ancestry, retained independently of the resolved navigation sequence. */
    fun ancestorsOf(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
        generateSequence(parents[node]) { parents[it] }.toList()

    fun contains(ancestor: AccessibilityNodeInfo, node: AccessibilityNodeInfo): Boolean {
        if (node !in parents) return false
        return generateSequence(node) { parents[it] }.any { it == ancestor }
    }
}
