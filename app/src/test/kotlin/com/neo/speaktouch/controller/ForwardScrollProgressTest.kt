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

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.neo.speaktouch.intercepter.event.CallbackInterceptor
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [22, 28, 31], shadows = [NavigationNodeShadow::class])
class ForwardScrollProgressTest {
    private lateinit var window: AccessibilityWindowInfo
    private lateinit var root: AccessibilityNodeInfo
    private lateinit var list: AccessibilityNodeInfo
    private lateinit var origin: AccessibilityNodeInfo
    private lateinit var service: SliderTestService
    private lateinit var callbacks: CallbackInterceptor
    private lateinit var focus: FocusController
    private var scrolls = 0
    private val outcomes = mutableListOf<FocusController.NavigationOutcome>()
    private val handler: (FocusController.NavigationOutcome) -> Unit = { outcomes.add(it) }
    private fun nodeShadow(node: AccessibilityNodeInfo): NavigationNodeShadow = Shadow.extract(node)
    private fun node(label: String? = null) = AccessibilityNodeInfo.obtain().apply {
        className = "android.view.View"; packageName = "test.forward"; text = label
        isVisibleToUser = true; isEnabled = true; isFocusable = label != null
        shadowOf(this).setAccessibilityWindowInfo(this@ForwardScrollProgressTest.window)
        nodeShadow(this).setRefreshReturnValue(true)
        nodeShadow(this).setOnPerformActionListener { action, _ ->
            if (action == AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) {
                nodeShadow(root).focused?.isAccessibilityFocused = false
                nodeShadow(root).focused = this; isAccessibilityFocused = true; true
            } else false
        }
    }
    private fun add(parent: AccessibilityNodeInfo, child: AccessibilityNodeInfo) {
        nodeShadow(parent).addChild(child)
        // getParent()/getChild() return copies with their own child-list snapshots.
        // Model a successful provider refresh exposing additions to a retained copy.
        NavigationNodeShadow.refreshBehaviors[parent] = { copy ->
            val existing = (0 until copy.childCount).mapNotNull { copy.getChild(it) }.toSet()
            for (index in 0 until parent.childCount) {
                parent.getChild(index)?.let {
                    if (it !in existing) nodeShadow(copy).addChild(it)
                }
            }
            true
        }
    }
    private fun scrolled(from: Int = -1, to: Int = -1, count: Int = -1, y: Int = 0, maxY: Int = 0) {
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_SCROLLED).apply {
            fromIndex = from; toIndex = to; itemCount = count; scrollY = y; maxScrollY = maxY
            shadowOf(this).setSourceNode(list)
        }
        callbacks.handle(event)
    }
    @Before fun setup() {
        NavigationNodeShadow.refreshBehaviors.clear()
        window = AccessibilityWindowInfo.obtain().apply { shadowOf(this).setId(1) }
        root = node(); list = node(); origin = node("Final visible item")
        list.className = "androidx.recyclerview.widget.RecyclerView"; list.isScrollable = true
        list.collectionInfo = AccessibilityNodeInfo.CollectionInfo.obtain(39, 4, false)
        list.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        list.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        add(root, list); add(list, origin)
        nodeShadow(root).focused = origin; origin.isAccessibilityFocused = true
        nodeShadow(list).setOnPerformActionListener { action, _ ->
            if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) { scrolls++; true } else false
        }
        service = SliderTestService().apply { root = this@ForwardScrollProgressTest.root }
        callbacks = CallbackInterceptor(); focus = FocusController(callbacks, ServiceController(service))
    }
    @After fun cleanup() { callbacks.finish(); NavigationNodeShadow.refreshBehaviors.clear() }
    private fun next() = focus.moveFocusToNext(onResult = handler)

    @Test fun `retained container refresh exposes new children without changing identity`() {
        val retained = origin.parent!!
        assertEquals(list, retained); assertEquals(1, retained.childCount)
        val fresh = node("New successor"); add(list, fresh)
        assertEquals(1, retained.childCount)
        assertTrue(retained.refresh())
        assertEquals(list, retained); assertEquals(2, retained.childCount)
        assertEquals(fresh, retained.getChild(1))
    }

    @Test fun `accepted scroll remains pending until callback without inventing exhaustion`() {
        assertEquals(FocusController.NavigationOutcome.SCROLL_PENDING, next())
        assertEquals(1, scrolls); assertEquals(listOf(FocusController.NavigationOutcome.SCROLL_PENDING), outcomes)
    }
    @Test fun `accepted unchanged callback exhausts without another identical scroll`() {
        next(); scrolled()
        assertEquals(1, scrolls); assertFalse(callbacks.hasPendingScroll(list))
        assertEquals(FocusController.NavigationOutcome.EXHAUSTED, outcomes.last())
        repeat(4) { scrolled() }; assertEquals(1, scrolls)
    }
    @Test fun `stale unchanged grid indices exhaust despite advertised scroll actions`() {
        scrolled(127, 154, 156); next(); scrolled(127, 154, 156)
        assertEquals(FocusController.NavigationOutcome.EXHAUSTED, outcomes.last()); assertEquals(1, scrolls)
        assertTrue(list.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD })
    }
    @Test fun `leading index change without new trailing content is not forward traversal progress`() {
        scrolled(123, 154, 156); next(); scrolled(127, 154, 156)
        assertEquals(FocusController.NavigationOutcome.EXHAUSTED, outcomes.last()); assertEquals(1, scrolls)
    }
    @Test fun `clipping earlier rows while last row stays visible does not create new successors`() {
        val preceding = node("Earlier row")
        add(list, preceding)
        shadowOf(preceding).setTraversalBefore(origin)
        scrolled(123, 154, 156); next()
        preceding.isVisibleToUser = false
        scrolled(127, 154, 156)
        assertEquals(FocusController.NavigationOutcome.EXHAUSTED, outcomes.last()); assertEquals(1, scrolls)
    }
    @Test fun `advancing trailing index keeps genuine virtualized scrolling pending`() {
        scrolled(0, 15, 156); next(); scrolled(4, 19, 156)
        assertEquals(FocusController.NavigationOutcome.SCROLL_PENDING, outcomes.last()); assertEquals(2, scrolls)
        assertTrue(callbacks.hasPendingScroll(list))
    }
    @Test fun `advancing valid pixel offset keeps scrolling pending`() {
        scrolled(y = 100, maxY = 1000); next(); scrolled(y = 200, maxY = 1000)
        assertEquals(FocusController.NavigationOutcome.SCROLL_PENDING, outcomes.last()); assertEquals(2, scrolls)
    }
    @Test fun `new eligible successor is focused before requesting another scroll`() {
        next(); val fresh = node("New item"); add(list, fresh); scrolled()
        assertEquals(fresh, nodeShadow(root).focused); assertEquals(1, scrolls)
        assertEquals(FocusController.NavigationOutcome.FOCUS_REQUESTED, outcomes.last())
    }
    @Test fun `new successor wins even if provider leaves nonterminal indices unchanged`() {
        scrolled(0, 15, 156); next()
        val fresh = node("Newly exposed successor"); add(list, fresh); scrolled(0, 15, 156)
        assertEquals(fresh, nodeShadow(root).focused); assertEquals(1, scrolls)
        assertEquals(FocusController.NavigationOutcome.FOCUS_REQUESTED, outcomes.last())
    }
    @Test fun `multiple advancing pages remain possible until new target materializes`() {
        scrolled(0, 15, 156); next()
        for (page in 1..4) { scrolled(page * 4, 15 + page * 4, 156) }
        assertEquals(5, scrolls); assertTrue(callbacks.hasPendingScroll(list))
        val fresh = node("Later page item"); add(list, fresh); scrolled(20, 35, 156)
        assertEquals(fresh, nodeShadow(root).focused); assertEquals(5, scrolls)
    }
    @Test fun `genuine progression then unchanged callback is bounded`() {
        scrolled(123, 150, 156); next(); scrolled(127, 154, 156); scrolled(127, 154, 156)
        assertEquals(2, scrolls); assertEquals(FocusController.NavigationOutcome.EXHAUSTED, outcomes.last())
        assertFalse(callbacks.hasPendingScroll(list))
    }
    @Test fun `unchanged intermediate animation frame is unavailable not a false boundary`() {
        scrolled(0, 15, 156); next(); scrolled(0, 15, 156)
        assertEquals(FocusController.NavigationOutcome.UNAVAILABLE, outcomes.last())
        assertEquals(1, scrolls); assertFalse(callbacks.hasPendingScroll(list))
    }
    @Test fun `recycled node with changed content does not prove an unchanged frontier`() {
        next(); origin.text = "Recycled later item"; scrolled()
        assertEquals(2, scrolls); assertEquals(FocusController.NavigationOutcome.SCROLL_PENDING, outcomes.last())
    }
    @Test fun `new content invalidates a previously proven no progress container`() {
        next(); scrolled(); assertEquals(1, scrolls)
        val fresh = node("Added afterwards"); add(list, fresh); next()
        assertEquals(fresh, nodeShadow(root).focused)
        assertEquals(FocusController.NavigationOutcome.FOCUS_REQUESTED, outcomes.last())
    }
    @Test fun `new metadata invalidates cached edge proof`() {
        scrolled(100, 130, 156); next(); scrolled(100, 130, 156)
        scrolled(110, 140, 156); next()
        assertEquals(2, scrolls); assertEquals(FocusController.NavigationOutcome.SCROLL_PENDING, outcomes.last())
    }
    @Test fun `failed refresh after accepted scroll is unavailable never exhausted`() {
        next(); nodeShadow(origin).setRefreshReturnValue(false); scrolled()
        assertEquals(FocusController.NavigationOutcome.UNAVAILABLE, outcomes.last()); assertEquals(1, scrolls)
    }
    @Test fun `changed window invalidates callback even when lifecycle event is missing`() {
        next(); shadowOf(window).setId(2); scrolled()
        assertEquals(FocusController.NavigationOutcome.UNAVAILABLE, outcomes.last()); assertEquals(1, scrolls)
    }
    @Test fun `cancelled request cannot retry or establish no progress proof`() {
        next(); focus.cancelPendingNavigation(handler); scrolled()
        assertEquals(listOf(FocusController.NavigationOutcome.SCROLL_PENDING), outcomes)
        next(); assertEquals(2, scrolls)
    }
    @Test fun `ordered traversal uses the same no progress rule`() {
        val preceding = node("Preceding"); add(root, preceding)
        shadowOf(preceding).setTraversalBefore(list)
        next(); scrolled()
        assertEquals(FocusController.NavigationOutcome.EXHAUSTED, outcomes.last()); assertEquals(1, scrolls)
    }
}
