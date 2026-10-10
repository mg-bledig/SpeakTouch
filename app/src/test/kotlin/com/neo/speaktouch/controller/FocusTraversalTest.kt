package com.neo.speaktouch.controller

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.neo.speaktouch.intercepter.event.CallbackInterceptor
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAccessibilityNodeInfo

@Implements(AccessibilityNodeInfo::class)
class TraversalFocusShadow : ShadowAccessibilityNodeInfo() {
    var focused: AccessibilityNodeInfo? = null
    @Implementation fun findFocus(kind: Int): AccessibilityNodeInfo? =
        if (kind == AccessibilityNodeInfo.FOCUS_ACCESSIBILITY) focused else null
}

class TraversalTestService : AccessibilityService() {
    var root: AccessibilityNodeInfo? = null
    override fun getRootInActiveWindow(): AccessibilityNodeInfo? = root
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 31], shadows = [TraversalFocusShadow::class])
class FocusTraversalTest {
    private lateinit var root: AccessibilityNodeInfo
    private lateinit var service: TraversalTestService
    private lateinit var controller: FocusController
    private lateinit var callbacks: CallbackInterceptor
    private val focused get() = shadow(root).focused
    private fun shadow(node: AccessibilityNodeInfo): TraversalFocusShadow = Shadow.extract(node)
    private fun node(label: String? = null) = AccessibilityNodeInfo.obtain().apply {
        className = "android.view.View"; text = label; isVisibleToUser = true; isFocusable = label != null
        shadow(this).setOnPerformActionListener { action, _ ->
            if (action == AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) {
                focus(this)
                true
            } else false
        }
    }
    private fun add(parent: AccessibilityNodeInfo, vararg children: AccessibilityNodeInfo) {
        children.forEach { shadow(parent).addChild(it) }
    }
    private fun before(a: AccessibilityNodeInfo, b: AccessibilityNodeInfo) { shadow(a).setTraversalBefore(b) }
    private fun after(a: AccessibilityNodeInfo, b: AccessibilityNodeInfo) { shadow(a).setTraversalAfter(b) }
    private fun focus(node: AccessibilityNodeInfo?) {
        shadow(root).focused = node
        node?.let { shadow(it).focused = it }
    }
    private fun assertFocus(label: String) { assertEquals(label, focused?.text?.toString()) }
    @Before fun setup() {
        root = AccessibilityNodeInfo.obtain().apply { className = "android.view.View"; isVisibleToUser = true }
        service = TraversalTestService().apply { root = this@FocusTraversalTest.root }
        callbacks = CallbackInterceptor()
        controller = FocusController(callbacks, ServiceController(service))
    }
    private fun fixture(afterOnly: Boolean = false): List<AccessibilityNodeInfo> {
        val a = node("First"); val b = node("Second"); val c = node("Third")
        add(root, a, b, c)
        if (afterOnly) { after(c, a); after(b, c) } else { before(a, c); before(c, b) }
        return listOf(a, c, b)
    }
    private fun sweep(expected: List<AccessibilityNodeInfo>) {
        focus(null)
        controller.moveFocusToFirst()
        assertEquals(expected.first(), focused)
        expected.drop(1).forEach { controller.moveFocusToNext(); assertEquals(it, focused) }
        expected.dropLast(1).reversed().forEach { controller.moveFocusToPrevious(); assertEquals(it, focused) }
    }
    @Test fun `before ordering is shared by forward backward and initial focus`() = sweep(fixture())
    @Test fun `after ordering is shared by forward backward and initial focus`() = sweep(fixture(true))
    @Test fun `structural comparison stays unchanged in both directions`() {
        val a = node("First"); val b = node("Second"); val c = node("Third")
        add(root, a, b, c); sweep(listOf(a, b, c))
    }
    @Test fun `root and target are null when active root is unavailable`() {
        service.root = null
        assertNull(ServiceController(service).getRoot())
        assertNull(controller.getTarget())
    }
    @Test fun `default focus movement does nothing when active root is unavailable`() {
        val a = node("A"); val b = node("B"); add(root, a, b); focus(a)
        service.root = null
        controller.moveFocusToFirst()
        controller.moveFocusToNext()
        controller.moveFocusToPrevious()
        assertEquals(a, focused)
        listOf(root, a, b).forEach { assertTrue(shadow(it).performedActions.isEmpty()) }
    }
    @Test fun `navigation resumes after active root becomes available again`() {
        val a = node("A"); val b = node("B"); add(root, a, b)
        service.root = null
        controller.moveFocusToFirst()
        controller.moveFocusToNext()
        controller.moveFocusToPrevious()
        assertNull(focused)
        service.root = root
        assertEquals(root, ServiceController(service).getRoot())
        assertEquals(root, controller.getTarget())
        controller.moveFocusToFirst(); assertFocus("A")
        assertEquals(a, controller.getTarget())
        controller.moveFocusToNext(); assertFocus("B")
        controller.moveFocusToPrevious(); assertFocus("A")
    }
    @Test fun `explicit target keeps structural navigation when active root is temporarily unavailable`() {
        val a = node("A"); val b = node("B"); add(root, a, b); focus(a)
        service.root = null
        controller.moveFocusToNext(target = a); assertFocus("B")
        controller.moveFocusToPrevious(target = b); assertFocus("A")
    }
    @Test fun `initial focus can move ahead of structural first child`() {
        val a = node("First"); val b = node("Second"); add(root, a, b); before(b, a)
        controller.moveFocusToFirst(); assertFocus("Second")
    }
    @Test fun `initial focus with existing focus preserves movement granularity action`() {
        val a = node("First"); add(root, a); focus(a)
        controller.moveFocusToFirst()
        assertEquals(listOf(AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY), shadow(a).performedActions)
    }
    @Test fun `last ordered item does not jump back to a structural successor`() {
        val ordered = fixture(); focus(ordered.last()); controller.moveFocusToNext()
        assertEquals(ordered.last(), focused)
    }
    @Test fun `first ordered item does not jump back to a structural predecessor`() {
        val a = node("First"); val b = node("Second"); add(root, a, b); before(b, a); focus(b)
        controller.moveFocusToPrevious(); assertEquals(b, focused)
    }
    @Test fun `cross subtree anchor ordering has inverse navigation`() {
        val left = node(); val right = node(); val a = node("A"); val b = node("B")
        add(root, left, right); add(left, a); add(right, b); before(right, left)
        sweep(listOf(b, a))
    }
    @Test fun `child before parent supports inverse navigation without changing eligibility`() {
        val parent = node("Parent"); val child = node("Child")
        add(root, parent); add(parent, child); before(child, parent)
        sweep(listOf(child, parent))
    }
    @Test fun `group and actionable child stay separate focus targets`() {
        val group = node("Group").apply { isFocusable = false; isScreenReaderFocusable = true }
        val passive = node("Passive").apply { isFocusable = false }
        val button = node("Action").apply { isClickable = true }
        val other = node("Other")
        add(root, group, other); add(group, passive, button); before(other, group)
        sweep(listOf(other, group, button))
    }
    @Test fun `actionable descendant can be ordered independently of its group`() {
        val group = node("Group").apply { isFocusable = false; isScreenReaderFocusable = true }
        val passive = node("Passive").apply { isFocusable = false }
        val action = node("Action").apply { isClickable = true }; val other = node("Other")
        add(root, group, other); add(group, passive, action); after(action, other)
        sweep(listOf(group, other, action))
    }
    @Test fun `passive member constraint moves group while leaving passive member suppressed`() {
        val group = node("Group").apply { isFocusable = false; isScreenReaderFocusable = true }
        val passive = node("Passive").apply { isFocusable = false }; val other = node("Other")
        add(root, group, other); add(group, passive); after(passive, other)
        sweep(listOf(other, group))
    }
    @Test fun `cycle fallback remains navigable in both directions`() {
        val a = node("A"); val b = node("B"); add(root, a, b); before(a, b); before(b, a)
        sweep(listOf(a, b))
    }
    @Test fun `unlabeled interactive leaf retains focus eligibility after reordering`() {
        val blank = node().apply { isClickable = true }
        val b = node("B"); val c = node("C")
        add(root, blank, b, c); before(c, blank)
        sweep(listOf(b, c, blank))
    }
    private fun scrolled(container: AccessibilityNodeInfo) {
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_SCROLLED)
        shadowOf(event).setSourceNode(container)
        callbacks.handle(event)
    }
    private fun collection() = node().apply {
        AccessibilityNodeInfoCompat.wrap(this).setCollectionInfo(
            AccessibilityNodeInfoCompat.CollectionInfoCompat.obtain(3, 1, false))
    }
    @Test fun `forward scroll boundary precedes leaving collection and retries with fresh order`() {
        val list = collection(); val a = node("A"); val b = node("B"); val outside = node("Outside")
        add(root, list, outside); add(list, a, b); before(b, a); focus(a)
        var scrolls = 0
        shadow(list).setOnPerformActionListener { action, _ ->
            if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) { scrolls++; scrolls == 1 } else false
        }
        controller.moveFocusToNext(); assertFocus("A"); assertEquals(1, scrolls)
        val fresh = node("Fresh"); add(list, fresh); after(fresh, a)
        scrolled(list); assertFocus("Fresh")
        assertTrue(shadow(outside).performedActions.isEmpty())
    }
    @Test fun `backward scroll boundary precedes leaving collection and retries with fresh order`() {
        val outside = node("Outside"); val list = collection(); val a = node("A"); val b = node("B")
        add(root, outside, list); add(list, a, b); before(b, a); focus(b)
        var scrolls = 0
        shadow(list).setOnPerformActionListener { action, _ ->
            if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) { scrolls++; scrolls == 1 } else false
        }
        controller.moveFocusToPrevious(); assertFocus("B"); assertEquals(1, scrolls)
        val fresh = node("Fresh"); add(list, fresh); before(fresh, b)
        scrolled(list); assertFocus("Fresh")
        assertTrue(shadow(outside).performedActions.isEmpty())
    }
    @Test fun `failed scroll continues to next eligible node outside collection`() {
        val list = collection(); val a = node("A"); val b = node("B"); val outside = node("Outside")
        add(root, list, outside); add(list, a, b); before(b, a); focus(a)
        controller.moveFocusToNext(); assertFocus("Outside")
        assertEquals(listOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD), shadow(list).performedActions)
    }
    @Test fun `failed backward scroll continues outside collection`() {
        val outside = node("Outside"); val list = collection(); val a = node("A"); val b = node("B")
        add(root, outside, list); add(list, a, b); before(b, a); focus(b)
        controller.moveFocusToPrevious(); assertFocus("Outside")
        assertEquals(listOf(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD), shadow(list).performedActions)
    }
    @Test fun `interleaved leaf order delays scrolling until final visit to a collection`() {
        val left = collection(); val right = collection()
        val a = node("A"); val leftTail = node("Left tail"); val b = node("B"); val rightTail = node("Right tail")
        add(root, left, right); add(left, a, leftTail); add(right, b, rightTail); before(b, a)
        var scrolls = 0
        shadow(left).setOnPerformActionListener { action, _ ->
            if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) { scrolls++; scrolls == 1 } else false
        }
        focus(leftTail)
        controller.moveFocusToNext(); assertFocus("B"); assertEquals(0, scrolls)
        controller.moveFocusToNext(); assertFocus("A")
        controller.moveFocusToNext(); assertFocus("A"); assertEquals(1, scrolls)
        val fresh = node("Fresh"); add(left, fresh); after(fresh, a)
        scrolled(left); assertFocus("Fresh")
    }
}
