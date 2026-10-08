package com.neo.speaktouch.utils

import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.neo.speaktouch.model.NodeFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27, 28, 31])
class TraversalOrderTest {
    private fun node(label: String? = null, windowId: Int = 1) = AccessibilityNodeInfo.obtain().apply {
        className = "android.view.View"
        text = label
        isVisibleToUser = true
        isFocusable = label != null
        val window = AccessibilityWindowInfo.obtain()
        shadowOf(window).setId(windowId)
        shadowOf(this).setAccessibilityWindowInfo(window)
    }
    private fun add(parent: AccessibilityNodeInfo, vararg children: AccessibilityNodeInfo) {
        children.forEach { shadowOf(parent).addChild(it) }
    }
    private fun before(node: AccessibilityNodeInfo, target: AccessibilityNodeInfo) {
        shadowOf(node).setTraversalBefore(target)
    }
    private fun after(node: AccessibilityNodeInfo, target: AccessibilityNodeInfo) {
        shadowOf(node).setTraversalAfter(target)
    }
    private fun labels(root: AccessibilityNodeInfo) = TraversalOrder(root).nodes()
        .filter { NodeFilter.Focusable.filter(it) }.map { it.text.toString() }
    private fun three(block: (AccessibilityNodeInfo, AccessibilityNodeInfo, AccessibilityNodeInfo) -> Unit = { _, _, _ -> }) {
        val root = node()
        val first = node("First")
        val second = node("Second")
        val third = node("Third")
        add(root, first, second, third)
        block(first, second, third)
        assertEquals(listOf("First", "Third", "Second"), labels(root))
    }

    @Test fun `no relationships preserve structural preorder`() {
        val root = node()
        val container = node()
        add(root, node("First"), container, node("Third"))
        add(container, node("Second"))
        assertEquals(listOf("First", "Second", "Third"), labels(root))
    }
    @Test fun `before only declarations reorder siblings`() = three { first, second, third ->
        before(first, third)
        before(third, second)
    }
    @Test fun `after only declarations reorder siblings`() = three { first, second, third ->
        after(third, first)
        after(second, third)
    }
    @Test fun `one sided before declaration is sufficient`() = three { _, second, third -> before(third, second) }
    @Test fun `one sided after declaration is sufficient`() = three { _, second, third -> after(second, third) }
    @Test fun `sparse constraints do not require consecutive traversal links`() {
        val root = node()
        val a = node("A"); val b = node("B"); val c = node("C"); val d = node("D")
        add(root, a, b, c, d)
        before(d, b)
        assertEquals(listOf("A", "C", "D", "B"), labels(root))
    }
    @Test fun `unconstrained available siblings retain stable baseline order`() {
        val root = node()
        val a = node("A"); val b = node("B"); val c = node("C"); val d = node("D")
        add(root, a, b, c, d)
        before(c, a)
        assertEquals(listOf("B", "C", "A", "D"), labels(root))
    }
    @Test fun `duplicate constraints do not inflate dependency counts`() = three { _, second, third ->
        before(third, second)
        after(second, third)
    }
    @Test fun `self references are ignored`() {
        val root = node(); val a = node("A"); val b = node("B")
        add(root, a, b); before(a, a); after(b, b)
        assertEquals(listOf("A", "B"), labels(root))
    }
    @Test fun `missing target is ignored without following its links`() {
        val root = node(); val a = node("A"); val b = node("B"); val missing = node("Missing")
        add(root, a, b); before(b, missing); before(missing, a)
        assertEquals(listOf("A", "B"), labels(root))
    }
    @Test fun `cross window target is ignored`() {
        val root = node(); val a = node("A"); val b = node("B"); val foreign = node("Foreign", 2)
        add(root, a, b, foreign); before(b, foreign); before(foreign, a)
        assertEquals(listOf("A", "B", "Foreign"), labels(root))
    }
    @Test fun `cycle falls back to structural order in affected scope`() {
        val root = node(); val a = node("A"); val b = node("B"); val c = node("C")
        add(root, a, b, c); before(a, b); before(b, c); before(c, a)
        repeat(3) { assertEquals(listOf("A", "B", "C"), labels(root)) }
    }
    @Test fun `contradictory before and after fall back deterministically`() {
        val root = node(); val a = node("A"); val b = node("B")
        add(root, a, b); before(b, a); after(b, a)
        assertEquals(listOf("A", "B"), labels(root))
    }
    @Test fun `cycle fallback restores complete window baseline`() {
        val root = node(); val container = node(); val a = node("A"); val b = node("B"); val end = node("End")
        add(root, container, end); add(container, a, b)
        before(a, b); before(b, a); before(end, container)
        assertEquals(listOf("A", "B", "End"), labels(root))
    }
    @Test fun `filtered container anchors order its descendants without acquiring focus`() {
        val root = node(); val container = node(); val first = node("First")
        add(root, first, container); add(container, node("Child")); before(container, first)
        assertFalse(NodeFilter.Focusable.filter(container))
        assertEquals(listOf("Child", "First"), labels(root))
    }
    @Test fun `invisible anchor orders visible endpoints but remains excluded`() {
        val root = node(); val a = node("A"); val hidden = node("Hidden").apply { isVisibleToUser = false }; val b = node("B")
        add(root, a, hidden, b); after(a, hidden); after(hidden, b)
        assertEquals(listOf("B", "A"), labels(root))
    }
    @Test fun `cross subtree leaf constraints retain unrelated leaves stably`() {
        val root = node(); val left = node(); val right = node(); val a = node("A"); val b = node("B")
        add(root, left, right); add(left, a, node("Left tail")); add(right, b, node("Right tail")); before(b, a)
        assertEquals(listOf("Left tail", "B", "A", "Right tail"), labels(root))
    }
    @Test fun `screen reader group keeps passive children suppressed and action child separate`() {
        val root = node(); val group = node("Group").apply {
            isFocusable = false
            AccessibilityNodeInfoCompat.wrap(this).isScreenReaderFocusable = true
        }
        val passive = node("Passive").apply { isFocusable = false }
        val action = node("Action").apply { isClickable = true }
        val other = node("Other")
        add(root, group, other); add(group, passive, action); before(other, group)
        assertEquals(listOf("Other", "Group", "Action"), labels(root))
    }
    @Test fun `collections remain excluded while their items stay independent`() {
        val root = node(); val collection = node().apply {
            AccessibilityNodeInfoCompat.wrap(this).setCollectionInfo(
                AccessibilityNodeInfoCompat.CollectionInfoCompat.obtain(2, 1, false))
        }
        val a = node("A"); val b = node("B"); val other = node("Other")
        add(root, collection, other); add(collection, a, b); before(other, collection); before(b, a)
        assertFalse(NodeFilter.Focusable.filter(collection))
        assertEquals(listOf("Other", "B", "A"), labels(root))
    }
    @Test fun `leaf constraints support stable interleaving across subtrees`() {
        val root = node(); val left = node(); val right = node()
        val a = node("A"); val b = node("B"); val c = node("C"); val d = node("D")
        add(root, left, right); add(left, a, b); add(right, c, d)
        before(a, c); before(d, b)
        assertEquals(listOf("A", "C", "D", "B"), labels(root))
    }
    @Test fun `descendant before ancestor is honored without inventing a cycle`() {
        val root = node(); val parent = node("Parent"); val child = node("Child")
        add(root, parent); add(parent, child); before(child, parent)
        assertEquals(listOf("Child", "Parent"), labels(root))
    }
    @Test fun `container constraints keep contents ordered without disturbing later unconstrained nodes`() {
        val root = node(); val left = node(); val right = node(); val tail = node("Tail")
        add(root, left, right, tail)
        add(left, node("A"), node("B")); add(right, node("C"), node("D"))
        before(right, left)
        assertEquals(listOf("C", "D", "A", "B", "Tail"), labels(root))
    }
    @Test fun `passive group member anchor moves its existing group without acquiring focus`() {
        val root = node(); val group = node("Group").apply {
            isFocusable = false
            AccessibilityNodeInfoCompat.wrap(this).isScreenReaderFocusable = true
        }
        val passive = node("Passive").apply { isFocusable = false }; val other = node("Other")
        add(root, group, other); add(group, passive); after(passive, other)
        assertFalse(NodeFilter.Focusable.filter(passive))
        assertEquals(listOf("Other", "Group"), labels(root))
    }
}
