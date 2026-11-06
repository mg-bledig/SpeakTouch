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

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.neo.speaktouch.intercepter.event.CallbackInterceptor
import com.neo.speaktouch.utils.Reader
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], shadows = [SliderNodeShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class ActionNavigationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var service: SliderTestService
    private lateinit var root: AccessibilityNodeInfo
    private lateinit var target: AccessibilityNodeInfo
    private lateinit var tts: TextToSpeech
    private lateinit var controller: ActionNavigationController
    private lateinit var granularity: NavigationGranularityController
    private val retry = 0x7f010001
    private val archive = 0x7f010002
    private val calls = mutableListOf<Pair<Int, Bundle?>>()
    private fun ns(node: AccessibilityNodeInfo): SliderNodeShadow = Shadow.extract(node)
    private fun node() = AccessibilityNodeInfo.obtain().apply {
        packageName = "test.actions"; isEnabled = true; isVisibleToUser = true
        className = "android.view.View"; isAccessibilityFocused = true
        val window = AccessibilityWindowInfo.obtain()
        shadowOf(window).setId(1)
        shadowOf(window).setType(AccessibilityWindowInfo.TYPE_APPLICATION)
        shadowOf(this).setAccessibilityWindowInfo(window)
        ns(this).setRefreshReturnValue(true)
    }
    private fun event(type: Int, source: AccessibilityNodeInfo = target) = AccessibilityEvent.obtain(type).apply {
        shadowOf(this).setSourceNode(source)
    }
    private fun spoken() = shadowOf(tts).spokenTextList.map(CharSequence::toString)
    private fun selectActions() {
        repeat(7) { if (granularity.selectedMode != NavigationGranularityController.Mode.ACTIONS) granularity.cycle(true) }
        assertEquals(NavigationGranularityController.Mode.ACTIONS, granularity.selectedMode)
    }
    @Before fun setup() {
        calls.clear()
        root = node(); target = node()
        ns(root).focused = target
        service = SliderTestService().apply { this.root = this@ActionNavigationTest.root }
        tts = TextToSpeech(context, null)
        val speech = SpeechController(tts, context, Reader(context))
        val wrapper = ServiceController(service)
        controller = ActionNavigationController(ActionController(wrapper, context), speech)
        controller.setActivationAvailable(true)
        granularity = NavigationGranularityController(wrapper, speech, CallbackInterceptor(), context, controller)
        granularity.start()
        target.addAction(AccessibilityNodeInfo.AccessibilityAction(retry, "Retry"))
        target.addAction(AccessibilityNodeInfo.AccessibilityAction(archive, "Archive"))
        ns(target).setOnPerformActionListener { action, arguments -> calls.add(action to arguments); true }
    }
    @After fun cleanup() { controller.finish(); granularity.finish(); tts.shutdown() }
    @Test fun `actions is contextual and ordinary activate is initially unchanged`() {
        assertTrue(NavigationGranularityController.Mode.ACTIONS in granularity.availableModes())
        selectActions()
        assertFalse(granularity.activateAction())
        assertTrue(calls.isEmpty())
    }
    @Test fun `vertical selection announces label and double tap executes once without bundle or focus action`() {
        selectActions()
        assertEquals(NavigationGranularityController.Result.ACCEPTED, granularity.move(true))
        assertEquals("Retry", spoken().last())
        assertTrue(calls.isEmpty())
        assertTrue(granularity.activateAction())
        assertEquals(listOf(retry), calls.map { it.first })
        assertNull(calls.single().second)
        assertTrue(target.isAccessibilityFocused)
    }
    @Test fun `reverse selection chooses last action and cycles back to ordinary activate`() {
        assertTrue(controller.move(false)); assertEquals("Archive", spoken().last())
        assertTrue(controller.move(true)); assertEquals("Activate", spoken().last())
        assertFalse(controller.activate())
    }
    @Test fun `focus changes and focus clearing discard selection`() {
        controller.move(true)
        controller.handle(event(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED, node()))
        assertFalse(controller.activate())
        controller.move(true)
        controller.handle(event(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED))
        assertFalse(controller.activate()); assertTrue(calls.isEmpty())
    }
    @Test fun `touch start preserves selection and duplicate focus does not erase it`() {
        controller.move(true)
        controller.handle(event(AccessibilityEvent.TYPE_TOUCH_INTERACTION_START))
        controller.handle(event(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED))
        assertTrue(controller.activate()); assertEquals(retry, calls.single().first)
    }
    @Test fun `removed or relabelled action is consumed with feedback and never clicked instead`() {
        controller.move(true)
        target.removeAction(AccessibilityNodeInfo.AccessibilityAction(retry, "Retry"))
        target.addAction(AccessibilityNodeInfo.AccessibilityAction(retry, "Different action"))
        assertTrue(controller.activate()); assertTrue(calls.isEmpty())
        assertEquals("Action no longer available", spoken().last())
    }
    @Test fun `stale target rejects activation without action or fallback click`() {
        controller.move(true); ns(target).setRefreshReturnValue(false)
        assertTrue(controller.activate()); assertTrue(calls.isEmpty())
    }
    @Test fun `window transition and mode change restore ordinary activation`() {
        selectActions(); granularity.move(true)
        controller.handle(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED))
        assertFalse(granularity.activateAction())
        granularity.move(true); granularity.cycle(true)
        assertFalse(granularity.activateAction()); assertTrue(calls.isEmpty())
    }
    @Test fun `no available actions removes contextual mode without losing saved selection`() {
        selectActions()
        target.removeAction(AccessibilityNodeInfo.AccessibilityAction(retry, "Retry"))
        target.removeAction(AccessibilityNodeInfo.AccessibilityAction(archive, "Archive"))
        assertFalse(controller.available())
        assertEquals(NavigationGranularityController.Mode.DEFAULT, granularity.effectiveMode())
        assertEquals(NavigationGranularityController.Mode.ACTIONS, granularity.selectedMode)
        assertEquals(NavigationGranularityController.Result.DEFAULT, granularity.move(true))
        assertFalse(granularity.activateAction())
    }
    @Test fun `failed provider action reports failure without retry`() {
        controller.move(true)
        ns(target).setOnPerformActionListener { action, arguments -> calls.add(action to arguments); false }
        assertTrue(controller.activate()); assertEquals(1, calls.size)
        assertEquals("Action failed", spoken().last())
    }
    @Test fun `teardown removes actions mode and prevents stale activation`() {
        controller.move(true); controller.finish()
        assertFalse(controller.available()); assertFalse(controller.activate()); assertTrue(calls.isEmpty())
    }
}
