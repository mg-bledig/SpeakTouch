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
class ManualScrollInterpreterTest {
    private lateinit var window: AccessibilityWindowInfo
    private lateinit var root: AccessibilityNodeInfo
    private lateinit var source: AccessibilityNodeInfo
    private lateinit var service: SliderTestService
    private lateinit var callbacks: CallbackInterceptor
    private lateinit var interpreter: ManualScrollInterpreter
    private var time = 1L
    private fun nodeShadow(node: AccessibilityNodeInfo): NavigationNodeShadow = Shadow.extract(node)
    private fun node() = AccessibilityNodeInfo.obtain().apply {
        packageName = "test.scroll"; className = "android.widget.ScrollView"
        isVisibleToUser = true; isScrollable = true
        shadowOf(this).setAccessibilityWindowInfo(this@ManualScrollInterpreterTest.window)
        nodeShadow(this).setRefreshReturnValue(true)
    }
    private fun event(x: Int = 0, y: Int = 0, content: Boolean = false,
        target: AccessibilityNodeInfo = source) = AccessibilityEvent.obtain(
        if (content) AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED else AccessibilityEvent.TYPE_VIEW_SCROLLED
    ).apply {
        eventTime = time++; packageName = "test.scroll"; isScrollable = true
        scrollX = x; scrollY = y
        shadowOf(this).setWindowId(window.id); shadowOf(this).setSourceNode(target)
    }
    private fun read(event: AccessibilityEvent): ManualScrollInterpreter.Scroll? {
        interpreter.handle(event)
        return interpreter.confirmedScroll(event)
    }
    @Before fun setup() {
        NavigationNodeShadow.refreshBehaviors.clear()
        window = AccessibilityWindowInfo.obtain().apply {
            shadowOf(this).setId(4); shadowOf(this).setType(AccessibilityWindowInfo.TYPE_APPLICATION)
        }
        root = node(); source = node(); nodeShadow(root).addChild(source)
        shadowOf(window).setRoot(root)
        // A service event and its source must identify the same actual window.
        assertEquals(window.id, root.windowId)
        assertEquals(window.id, source.windowId)
        assertEquals(root, source.parent)
        service = SliderTestService().apply { this.root = this@ManualScrollInterpreterTest.root }
        callbacks = CallbackInterceptor()
        interpreter = ManualScrollInterpreter(ServiceController(service), callbacks)
        interpreter.start()
    }
    @After fun cleanup() {
        interpreter.finish(); callbacks.finish(); NavigationNodeShadow.refreshBehaviors.clear()
    }
    @Test fun `horizontal offset advances and reverses independently of vertical sound`() {
        assertNull(read(event(100)))
        val forward = read(event(200))!!
        assertEquals(ManualScrollInterpreter.Axis.HORIZONTAL, forward.movement.axis)
        assertTrue(forward.movement.forward)
        assertFalse(read(event(100))!!.movement.forward)
    }
    @Test fun `vertical offset advances and reverses`() {
        read(event(y = 100))
        assertEquals(ManualScrollInterpreter.Axis.VERTICAL, read(event(y = 200))!!.movement.axis)
        assertFalse(read(event(y = 50))!!.movement.forward)
    }
    @Test fun `meaningful content position seeds first horizontal scroll only`() {
        assertNull(read(event(496, content = true)))
        // Final-position content update must not overwrite the first usable baseline.
        assertNull(read(event(1056, content = true)))
        assertTrue(read(event(1056))!!.movement.forward)
    }
    @Test fun `content events never themselves confirm movement`() {
        read(event(100)); assertNull(read(event(200, content = true)))
    }
    @Test fun `default zero content event is not invented first baseline`() {
        read(event(content = true)); assertNull(read(event(1056)))
    }
    @Test fun `zero position with explicit range is meaningful baseline`() {
        read(event(content = true).apply { maxScrollX = 500 })
        assertTrue(read(event(100).apply { maxScrollX = 500 })!!.movement.forward)
    }
    @Test fun `first absolute position without baseline or delta remains unknown`() {
        assertNull(read(event(1056)))
    }
    @Test fun `baseline is tied to source identity not class or labels`() {
        read(event(100, content = true))
        val other = node(); nodeShadow(root).addChild(other)
        assertNull(read(event(200, target = other)))
        assertTrue(read(event(200))!!.movement.forward)
    }
    @Test fun `older content baseline cannot establish direction`() {
        read(event(100, content = true).apply { eventTime = 50 })
        assertNull(read(event(200).apply { eventTime = 40 }))
    }
    @Test fun `diagonal offsets are ambiguous`() {
        read(event(100, 100)); assertNull(read(event(200, 200)))
    }
    @Test fun `unchanged position is not movement`() {
        read(event(100)); assertNull(read(event(100)))
    }
    @Test fun `out of range offset cannot establish direction`() {
        read(event(100))
        assertNull(read(event(600).apply { maxScrollX = 500 }))
    }
    @Test fun `invalid content offset cannot seed a first transition baseline`() {
        read(event(600, content = true).apply { maxScrollX = 500 })
        assertNull(read(event(800).apply { maxScrollX = 1000 }))
    }
    @Test fun `explicit horizontal delta can establish first movement when available`() {
        if (Build.VERSION.SDK_INT < 28) {
            assertNull(read(event()))
        } else {
            val scroll = read(event().apply { scrollDeltaX = 400; scrollDeltaY = 0 })!!
            assertEquals(ManualScrollInterpreter.Axis.HORIZONTAL, scroll.movement.axis)
            assertTrue(scroll.movement.forward)
        }
    }
    @Test fun `contradictory offset and delta are rejected`() {
        read(event(100))
        val next = event(200)
        if (Build.VERSION.SDK_INT >= 28) {
            next.scrollDeltaX = -50
            assertNull(read(next))
        } else assertTrue(read(next)!!.movement.forward)
    }
    @Test fun `diagonal deltas are rejected`() {
        if (Build.VERSION.SDK_INT >= 28) {
            assertNull(read(event().apply { scrollDeltaX = 40; scrollDeltaY = 60 }))
        } else assertNull(read(event()))
    }
    @Test fun `single column index movement remains supported`() {
        source.collectionInfo = AccessibilityNodeInfo.CollectionInfo.obtain(100, 1, false)
        read(event().apply { fromIndex = 0; toIndex = 9; itemCount = 100 })
        val next = read(event().apply { fromIndex = 10; toIndex = 19; itemCount = 100 })!!
        assertEquals(ManualScrollInterpreter.Axis.VERTICAL, next.movement.axis)
        assertTrue(next.movement.forward)
    }
    @Test fun `ambiguous grid indices alone cannot establish axis`() {
        source.collectionInfo = AccessibilityNodeInfo.CollectionInfo.obtain(25, 4, false)
        read(event().apply { fromIndex = 0; toIndex = 19; itemCount = 100 })
        assertNull(read(event().apply { fromIndex = 20; toIndex = 39; itemCount = 100 }))
    }
    @Test fun `opposing index and offset evidence is rejected`() {
        read(event(100).apply { fromIndex = 20; toIndex = 39; itemCount = 100 })
        assertNull(read(event(200).apply { fromIndex = 0; toIndex = 19; itemCount = 100 }))
    }
    @Test fun `helper and callback consumed inertial tail stay excluded until new touch`() {
        read(event(100))
        callbacks.addCallback(object : CallbackInterceptor.Scroll(source) { override fun invoke() = Unit })
        val helper = event(200)
        assertNull(read(helper)); callbacks.handle(helper)
        assertNull(read(event(300)))
        interpreter.handle(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_TOUCH_INTERACTION_START))
        assertTrue(read(event(400))!!.movement.forward)
    }
    @Test fun `window change discards baselines`() {
        read(event(100))
        interpreter.handle(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED))
        assertNull(read(event(200)))
    }
    @Test fun `different active root invalidates old baseline`() {
        read(event(100))
        val newRoot = node(); service.root = newRoot; nodeShadow(newRoot).addChild(source)
        assertNull(read(event(200)))
    }
    @Test fun `stale and detached sources cannot confirm movement`() {
        read(event(100))
        nodeShadow(source).setRefreshReturnValue(false)
        assertNull(read(event(200)))
        val detached = node()
        assertNull(read(event(100, target = detached))); assertNull(read(event(200, target = detached)))
    }
    @Test fun `wrong event window and package are rejected`() {
        read(event(100))
        assertNull(read(event(200).apply { shadowOf(this).setWindowId(99) }))
        assertNull(read(event(200).apply { packageName = "other.app" }))
    }
    @Test fun `finished interpreter rejects late events`() {
        read(event(100)); interpreter.finish(); assertNull(read(event(200)))
    }
}
