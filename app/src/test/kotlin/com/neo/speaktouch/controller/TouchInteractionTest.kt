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

import android.accessibilityservice.AccessibilityGestureEvent
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.TouchInteractionController.*
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import com.neo.speaktouch.gestures.TouchGestureController
import com.neo.speaktouch.gestures.TouchInteractionMonitor
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], shadows = [PauseServiceShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class TouchInteractionTest {
    private class Driver : TouchInteractionMonitor.Driver {
        var currentState = STATE_CLEAR
        var clicks = 0
        var holds = 0
        val transitions = mutableListOf<Int>()
        override fun getState() = currentState
        override fun getMaxPointerCount() = 32
        override fun performClick() { clicks++ }
        override fun performLongClickAndStartDrag() { holds++ }
        override fun requestTouchExploration() { transitions.add(STATE_TOUCH_EXPLORING) }
        override fun requestDragging(pointerId: Int) { transitions.add(STATE_DRAGGING) }
        override fun requestDelegating() { transitions.add(STATE_DELEGATING) }
    }
    class RecordingService : AccessibilityService() {
        var failDisplays = false
        override fun getSystemService(name: String): Any? {
            if (failDisplays && name == DISPLAY_SERVICE) throw IllegalStateException("Display unavailable")
            return super.getSystemService(name)
        }
        override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
        override fun onInterrupt() {}
    }
    private lateinit var monitor: TouchInteractionMonitor
    private lateinit var driver: Driver
    private val gestures = mutableListOf<Int>()
    private var contextual = false
    private var customCalls = 0
    private var gestureStarts = 0
    private val looper get() = shadowOf(Looper.getMainLooper())
    @Before fun setup() {
        gestures.clear(); contextual = false; customCalls = 0; gestureStarts = 0
        driver = Driver()
        monitor = TouchInteractionMonitor(RuntimeEnvironment.getApplication(), driver,
            object : TouchInteractionMonitor.Callbacks {
                override fun onDoubleTap(): Boolean { if (contextual) customCalls++; return contextual }
                override fun onGesture(gestureId: Int) { gestures.add(gestureId) }
                override fun onGestureStarted() { gestureStarts++ }
            })
        state(STATE_TOUCH_INTERACTING)
    }
    @After fun cleanup() { monitor.stop(); looper.idleFor(Duration.ofSeconds(2)) }
    private fun state(value: Int) { driver.currentState = value; monitor.onStateChanged(value) }
    private fun recognized(id: Int) = monitor.onGestureCompleted(AccessibilityGestureEvent(id, 0, emptyList()))
    private fun touch(action: Int, x: Float = 100f, y: Float = 100f) {
        val time = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(time, time, action, x, y, 0)
        try { monitor.onMotionEvent(event) } finally { event.recycle() }
    }
    private fun tap() {
        touch(MotionEvent.ACTION_DOWN)
        looper.idleFor(Duration.ofMillis(25))
        touch(MotionEvent.ACTION_UP)
    }
    private fun multi(action: Int, count: Int, y: Float) {
        val properties = Array(count) { index -> MotionEvent.PointerProperties().apply {
            id = index; toolType = MotionEvent.TOOL_TYPE_FINGER
        } }
        val coordinates = Array(count) { index -> MotionEvent.PointerCoords().apply {
            x = 100f + index * 50; this.y = y; pressure = 1f; size = 1f
        } }
        val time = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(time, time, action, count, properties, coordinates,
            0, 0, 1f, 1f, 1, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { monitor.onMotionEvent(event) } finally { event.recycle() }
    }
    @Test fun `normal double tap delegates once to Android click`() {
        recognized(AccessibilityService.GESTURE_DOUBLE_TAP)
        assertEquals(1, driver.clicks); assertEquals(0, driver.holds); assertEquals(0, customCalls)
    }
    @Test fun `selected contextual action replaces click without a second activation`() {
        contextual = true; recognized(AccessibilityService.GESTURE_DOUBLE_TAP)
        assertEquals(1, customCalls); assertEquals(0, driver.clicks); assertEquals(0, driver.holds)
    }
    @Test fun `leaving actions restores normal double tap without changing touch ownership`() {
        contextual = true; recognized(AccessibilityService.GESTURE_DOUBLE_TAP)
        contextual = false; state(STATE_CLEAR); state(STATE_TOUCH_INTERACTING)
        recognized(AccessibilityService.GESTURE_DOUBLE_TAP)
        assertEquals(1, customCalls); assertEquals(1, driver.clicks)
    }
    @Test fun `hold always hands long press and continuous drag back to Android`() {
        contextual = true; recognized(AccessibilityService.GESTURE_DOUBLE_TAP_AND_HOLD)
        assertEquals(1, driver.holds); assertEquals(0, customCalls); assertEquals(0, driver.clicks)
    }
    @Test fun `delegated movement and release do not dispatch additional actions`() {
        recognized(AccessibilityService.GESTURE_DOUBLE_TAP_AND_HOLD)
        state(STATE_DELEGATING)
        recognized(AccessibilityService.GESTURE_DOUBLE_TAP)
        state(STATE_CLEAR)
        recognized(AccessibilityService.GESTURE_DOUBLE_TAP)
        assertEquals(1, driver.holds); assertEquals(0, driver.clicks)
        state(STATE_TOUCH_INTERACTING); recognized(AccessibilityService.GESTURE_DOUBLE_TAP)
        assertEquals(1, driver.clicks)
    }
    @Test fun `real double tap motion is recognized without long press`() {
        tap(); looper.idleFor(Duration.ofMillis(50)); tap()
        looper.idleFor(Duration.ofSeconds(1))
        assertEquals(1, driver.clicks); assertEquals(0, driver.holds)
    }
    @Test fun `real double tap hold motion uses drag handoff`() {
        tap(); looper.idleFor(Duration.ofMillis(50)); touch(MotionEvent.ACTION_DOWN)
        looper.idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout().toLong() + 1))
        assertEquals(1, driver.holds); assertEquals(0, driver.clicks)
    }
    @Test fun `exploration waits for classification then requests framework hover handling`() {
        touch(MotionEvent.ACTION_DOWN)
        looper.idleFor(Duration.ofSeconds(1))
        assertTrue(STATE_TOUCH_EXPLORING in driver.transitions)
        assertEquals(0, driver.clicks); assertEquals(0, driver.holds)
    }
    @Test fun `two finger movement remains framework dragging rather than navigation`() {
        multi(MotionEvent.ACTION_DOWN, 1, 100f)
        multi(MotionEvent.ACTION_POINTER_DOWN or (1 shl 8), 2, 100f)
        multi(MotionEvent.ACTION_MOVE, 2, 500f)
        assertEquals(listOf(STATE_DRAGGING), driver.transitions)
        assertTrue(gestures.isEmpty())
    }
    @Test fun `three finger down remains the existing mode cycle gesture`() {
        multi(MotionEvent.ACTION_DOWN, 1, 100f)
        multi(MotionEvent.ACTION_POINTER_DOWN or (1 shl 8), 2, 100f)
        multi(MotionEvent.ACTION_POINTER_DOWN or (2 shl 8), 3, 100f)
        multi(MotionEvent.ACTION_MOVE, 3, 500f)
        multi(MotionEvent.ACTION_POINTER_UP or (2 shl 8), 3, 500f)
        multi(MotionEvent.ACTION_POINTER_UP or (1 shl 8), 2, 500f)
        multi(MotionEvent.ACTION_UP, 1, 500f)
        looper.idle()
        assertEquals(listOf(AccessibilityService.GESTURE_3_FINGER_SWIPE_DOWN), gestures)
        assertTrue(driver.transitions.isEmpty())
    }
    @Test fun `cancelled touch cannot later enter exploration or activate anything`() {
        touch(MotionEvent.ACTION_DOWN); touch(MotionEvent.ACTION_CANCEL)
        looper.idleFor(Duration.ofSeconds(1))
        assertTrue(driver.transitions.isEmpty()); assertEquals(0, driver.clicks); assertEquals(0, driver.holds)
    }
    @Test fun `recognized swipes use existing gesture dispatch and notify speech lifecycle`() {
        monitor.onGestureStarted(AccessibilityService.GESTURE_SWIPE_DOWN)
        recognized(AccessibilityService.GESTURE_SWIPE_DOWN)
        looper.idle()
        assertEquals(1, gestureStarts)
        assertEquals(listOf(AccessibilityService.GESTURE_SWIPE_DOWN), gestures)
    }
    @Test fun `queued callbacks timers and late gestures are inert after stop`() {
        touch(MotionEvent.ACTION_DOWN)
        recognized(AccessibilityService.GESTURE_SWIPE_RIGHT)
        monitor.stop()
        recognized(AccessibilityService.GESTURE_DOUBLE_TAP)
        recognized(AccessibilityService.GESTURE_DOUBLE_TAP_AND_HOLD)
        looper.idleFor(Duration.ofSeconds(2))
        assertTrue(gestures.isEmpty()); assertTrue(driver.transitions.isEmpty())
        assertEquals(0, driver.clicks); assertEquals(0, driver.holds)
    }
    @Test fun `service registration and cleanup preserve all unrelated flags and are idempotent`() {
        val lifecycle = Robolectric.buildService(RecordingService::class.java).create()
        val service = lifecycle.get()
        service.serviceInfo = AccessibilityServiceInfo().apply {
            flags = AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        val original = service.serviceInfo.flags
        val controller = TouchGestureController(service)
        try {
            assertTrue(controller.start({ true }, { false }, {}))
            assertTrue(controller.available)
            assertEquals(original or AccessibilityServiceInfo.FLAG_SERVICE_HANDLES_DOUBLE_TAP, service.serviceInfo.flags)
            controller.configurationChanged()
            controller.finish(); controller.finish()
            assertFalse(controller.available); assertEquals(original, service.serviceInfo.flags)
        } finally { controller.finish(); lifecycle.destroy() }
    }
    @Test fun `failed registration restores framework ownership and does not expose actions`() {
        val lifecycle = Robolectric.buildService(RecordingService::class.java).create()
        val service = lifecycle.get()
        service.serviceInfo = AccessibilityServiceInfo().apply {
            flags = AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        val original = service.serviceInfo.flags
        service.failDisplays = true
        val controller = TouchGestureController(service)
        try {
            assertFalse(controller.start({ true }, { true }, {}))
            assertFalse(controller.available)
            assertEquals(original, service.serviceInfo.flags)
        } finally { controller.finish(); lifecycle.destroy() }
    }
}
