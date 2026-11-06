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
package com.neo.speaktouch.controller

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.neo.speaktouch.intercepter.event.SpeechInterceptor
import com.neo.speaktouch.utils.Reader
import java.time.Duration
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
import org.robolectric.shadow.api.Shadow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [22, 28, 33], shadows = [WindowEntryNodeShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class RelocationFeedbackTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var window: AccessibilityWindowInfo
    private lateinit var root: AccessibilityNodeInfo
    private lateinit var origin: AccessibilityNodeInfo
    private lateinit var scope: AccessibilityNodeInfo
    private lateinit var service: SliderTestService
    private lateinit var controller: RelocationFeedbackController
    private lateinit var speech: SpeechController
    private lateinit var interceptor: SpeechInterceptor
    private lateinit var tts: TextToSpeech
    private val focusActions = mutableListOf<AccessibilityNodeInfo>()
    private fun ns(node: AccessibilityNodeInfo): WindowEntryNodeShadow = Shadow.extract(node)
    private fun advance(ms: Long = 151) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun output() = shadowOf(tts).spokenTextList.toList()
    private fun node(label: String? = null) = AccessibilityNodeInfo.obtain().apply {
        packageName = "test.home"; className = "android.view.View"
        contentDescription = label; isVisibleToUser = true; isEnabled = true
        isClickable = label != null; isFocusable = label != null
        shadowOf(this).setAccessibilityWindowInfo(this@RelocationFeedbackTest.window)
        ns(this).setRefreshReturnValue(true)
        ns(this).setOnPerformActionListener { action, _ ->
            if (action == AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) {
                focusActions.add(this); focus(this); true
            } else false
        }
    }
    private fun focus(node: AccessibilityNodeInfo?) {
        ns(root).accessibility?.isAccessibilityFocused = false
        ns(root).accessibility = node
        node?.isAccessibilityFocused = true
    }
    private fun event(type: Int, source: AccessibilityNodeInfo? = scope) = AccessibilityEvent.obtain(type).apply {
        eventTime = SystemClock.uptimeMillis(); packageName = "test.home"
        shadowOf(this).setWindowId(root.windowId)
        if (source != null) shadowOf(this).setSourceNode(source)
    }
    private fun start(accepted: Boolean = true) {
        val request = controller.prepare(origin, CUSTOM)
        assertNotNull(request)
        controller.accepted(request, accepted)
        focus(null)
    }
    private fun subtree(actionId: Int = CUSTOM, source: AccessibilityNodeInfo = scope) =
        event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, source).apply {
            action = actionId; contentChangeTypes = AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE
        }.also(controller::handle)
    private fun expose(label: String = "Provider destination") = node(label).also { ns(scope).addChild(it) }
    private fun announcement(value: String = "Provider confirms movement") =
        event(AccessibilityEvent.TYPE_ANNOUNCEMENT, null).apply {
            action = AccessibilityNodeInfo.ACTION_CLICK; text.add(value)
            shadowOf(this).setWindowId(-1)
        }.also(controller::handle)
    private fun focused(node: AccessibilityNodeInfo) =
        event(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED, node).also {
            // Raw event wording must not replace the refreshed destination's own description.
            it.contentDescription = "Move item here"
            controller.handle(it); interceptor.handle(it)
        }

    @Before fun setup() {
        WindowEntryNodeShadow.reset(); focusActions.clear()
        shadowOf(context.packageManager).addResolveInfoForIntent(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), ResolveInfo().apply {
                activityInfo = ActivityInfo().apply { packageName = "test.home"; name = "Home" }
            })
        window = AccessibilityWindowInfo.obtain().apply {
            shadowOf(this).setId(7); shadowOf(this).setType(AccessibilityWindowInfo.TYPE_APPLICATION)
            shadowOf(this).setActive(true)
        }
        root = node(); origin = node("Original icon"); scope = node()
        ns(root).addChild(origin); ns(root).addChild(scope); shadowOf(window).setRoot(root)
        service = Robolectric.buildService(SliderTestService::class.java).create().get().apply {
            this.root = this@RelocationFeedbackTest.root
        }
        tts = TextToSpeech(context, null); speech = SpeechController(tts, context, Reader(context))
        controller = RelocationFeedbackController(ServiceController(service), speech)
        interceptor = SpeechInterceptor(speech, relocation = controller)
        focus(origin)
    }
    @After fun cleanup() {
        if (::controller.isInitialized) controller.finish()
        if (::speech.isInitialized) speech.shutdown()
        shadowOf(Looper.getMainLooper()).idle(); WindowEntryNodeShadow.reset()
    }

    @Test fun `correlated subtree recovers first new destination and only focus event speaks`() {
        start(); val first = expose(); expose("Second destination")
        subtree(); advance(149); assertTrue(focusActions.isEmpty())
        advance(2); assertEquals(listOf(first), focusActions); assertTrue(output().isEmpty())
        focused(first); assertTrue(output().single().contains("Provider destination"))
        subtree(); advance(); assertEquals(1, focusActions.size)
    }
    @Test fun `existing valid focus is preserved`() {
        start(); expose(); focus(origin); subtree(); advance()
        assertTrue(focusActions.isEmpty()); assertTrue(output().isEmpty())
    }
    @Test fun `old underlying controls cannot become relocation destinations`() {
        start(); subtree(CUSTOM, root); advance(); assertTrue(focusActions.isEmpty())
        announcement(); assertTrue(output().isEmpty())
    }
    @Test fun `unaccepted action and uncorrelated events do not recover`() {
        start(false); expose(); subtree(); advance(); assertTrue(focusActions.isEmpty())
        start(); expose(); subtree(CUSTOM + 1); advance(); assertTrue(focusActions.isEmpty())
    }
    @Test fun `user exploration cancels pending initialization`() {
        start(); expose(); subtree()
        controller.handle(event(AccessibilityEvent.TYPE_VIEW_HOVER_ENTER))
        subtree(); advance(); assertTrue(focusActions.isEmpty())
    }
    @Test fun `stale destination cannot receive focus`() {
        start(); val target = expose(); subtree()
        ns(target).setRefreshReturnValue(false); advance(); assertTrue(focusActions.isEmpty())
    }
    @Test fun `changed root cancels delayed work and provider feedback`() {
        start(); expose(); subtree(); service.root = node(); advance(); announcement()
        assertTrue(focusActions.isEmpty()); assertTrue(output().isEmpty())
    }
    @Test fun `provider completion survives focus clear and finger release and is spoken once`() {
        start(); val target = expose(); subtree(); advance(); focused(target)
        val before = output().size
        controller.noteActivation()
        focus(null)
        controller.handle(event(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED, target))
        controller.handle(event(AccessibilityEvent.TYPE_TOUCH_INTERACTION_END, null))
        controller.handle(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, root).apply {
            action = AccessibilityNodeInfo.ACTION_CLICK
        })
        subtree(AccessibilityNodeInfo.ACTION_CLICK, root)
        announcement(); announcement()
        assertEquals(listOf("Provider confirms movement"), output().drop(before))
    }
    @Test fun `release successful focus and closing interface never synthesize success`() {
        start(); expose(); subtree(); advance(); focus(null)
        controller.handle(event(AccessibilityEvent.TYPE_TOUCH_INTERACTION_END, null))
        subtree(AccessibilityNodeInfo.ACTION_CLICK, root); advance()
        assertTrue(output().isEmpty())
    }
    @Test fun `cancellation blocks later announcements`() {
        start(); expose(); subtree(); advance()
        controller.noteActivation()
        controller.handle(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply { contentChangeTypes = 512 })
        announcement(); assertTrue(output().isEmpty())
    }
    @Test fun `unrelated focus package action and stale announcements are rejected`() {
        start(); expose(); subtree(); advance()
        controller.noteActivation()
        val bad = event(AccessibilityEvent.TYPE_ANNOUNCEMENT, null).apply {
            action = CUSTOM; text.add("Not completion")
        }
        controller.handle(bad)
        bad.action = AccessibilityNodeInfo.ACTION_CLICK; bad.packageName = "other.app"; controller.handle(bad)
        bad.packageName = "test.home"; bad.eventTime = 0; controller.handle(bad)
        assertTrue(output().isEmpty())
        focus(origin); focused(origin); val before = output().size
        announcement(); assertEquals(before, output().size)
    }
    @Test fun `teardown cancels callbacks and expires operation`() {
        start(); expose(); subtree(); controller.finish(); advance(); announcement()
        assertTrue(focusActions.isEmpty()); assertTrue(output().isEmpty())
        start(); expose(); subtree(); advance(); controller.noteActivation(); advance(120_001); announcement()
        assertTrue(output().isEmpty())
    }
    @Test fun `provider announcement requires destination activation and never speaks unrelated Home updates`() {
        start(); expose(); subtree(); advance(); announcement()
        assertTrue(output().isEmpty())
    }
    @Test fun `delayed accepted focus is not requested twice`() {
        start(); val target = expose()
        ns(target).setOnPerformActionListener { action, _ ->
            if (action == AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) { focusActions.add(target); true } else false
        }
        subtree(); advance(); subtree(); advance()
        assertEquals(listOf(target), focusActions); assertTrue(output().isEmpty())
        focus(target); focused(target); assertEquals(1, output().size)
    }
    @Test fun `provider focus before settling is preserved and supplies completion ownership`() {
        start(); val target = expose(); subtree(); focus(target); focused(target); advance()
        assertTrue(focusActions.isEmpty()); controller.noteActivation(); announcement()
        assertEquals("Provider confirms movement", output().last())
    }
    @Test fun `changed window and sensitive destination cannot redirect focus`() {
        start(); val target = expose(); target.isPassword = true; subtree(); advance()
        assertTrue(focusActions.isEmpty())
        target.isPassword = false; subtree()
        shadowOf(window).setId(8); advance(); announcement()
        assertTrue(focusActions.isEmpty()); assertTrue(output().isEmpty())
    }
    private companion object { const val CUSTOM = 0x7f010001 }
}
