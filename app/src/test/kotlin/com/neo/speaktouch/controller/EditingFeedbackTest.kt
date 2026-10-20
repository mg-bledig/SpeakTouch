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

import android.content.Context
import android.speech.tts.TextToSpeech
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.neo.speaktouch.R
import com.neo.speaktouch.intercepter.event.SpeechInterceptor
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAccessibilityNodeInfo

@Implements(AccessibilityNodeInfo::class)
class EditingNodeShadow : ShadowAccessibilityNodeInfo() {
    @RealObject private lateinit var node: AccessibilityNodeInfo
    var input: AccessibilityNodeInfo? = null
    var accessibility: AccessibilityNodeInfo? = null
    @Implementation fun findFocus(kind: Int): AccessibilityNodeInfo? = when (kind) {
        AccessibilityNodeInfo.FOCUS_INPUT -> input
        AccessibilityNodeInfo.FOCUS_ACCESSIBILITY -> accessibility
        else -> null
    }
    @Implementation override fun refresh() = refreshBehaviors[node]?.invoke(node) ?: super.refresh()
    companion object {
        val refreshBehaviors = mutableMapOf<AccessibilityNodeInfo, (AccessibilityNodeInfo) -> Boolean>()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [22, 28, 31], shadows = [EditingNodeShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class EditingFeedbackTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var window: AccessibilityWindowInfo
    private lateinit var root: AccessibilityNodeInfo
    private lateinit var field: AccessibilityNodeInfo
    private lateinit var service: SliderTestService
    private lateinit var speech: SpeechController
    private lateinit var tts: TextToSpeech
    private lateinit var feedback: EditingFeedbackController
    private lateinit var pipeline: SpeechInterceptor
    private fun ns(node: AccessibilityNodeInfo): EditingNodeShadow = Shadow.extract(node)
    private fun node(type: String = "android.view.View") = AccessibilityNodeInfo.obtain().apply {
        className = type; packageName = "test.editing"; isVisibleToUser = true; isEnabled = true
        shadowOf(this).setAccessibilityWindowInfo(this@EditingFeedbackTest.window)
        ns(this).setRefreshReturnValue(true)
    }
    private fun event(type: Int, source: AccessibilityNodeInfo = field) = AccessibilityEvent.obtain(type).apply {
        shadowOf(this).setSourceNode(source); packageName = source.packageName
    }
    private fun send(type: Int, source: AccessibilityNodeInfo = field) = pipeline.handle(event(type, source))
    private fun enter() { field.isFocused = true; ns(root).input = field }
    private fun focus() { ns(root).accessibility = field; field.isAccessibilityFocused = true; send(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED) }
    private fun spoken() = shadowOf(tts).spokenTextList
    private fun full() = Reader(context).read(AccessibilityNodeInfoCompat.wrap(field))
    private fun editing() = context.getString(R.string.text_editing) + ", " + full()
    @Before fun setup() {
        EditingNodeShadow.refreshBehaviors.clear()
        window = AccessibilityWindowInfo.obtain().apply {
            shadowOf(this).setId(1); shadowOf(this).setType(AccessibilityWindowInfo.TYPE_APPLICATION)
        }
        root = node(); field = node("android.widget.EditText").apply { isEditable = true; contentDescription = "Your name" }
        ns(root).addChild(field); ns(root).accessibility = field; field.isAccessibilityFocused = true
        service = SliderTestService().apply { root = this@EditingFeedbackTest.root }
        tts = TextToSpeech(context, null); speech = SpeechController(tts, context, Reader(context))
        feedback = EditingFeedbackController(ServiceController(service), speech, context)
        pipeline = SpeechInterceptor(speech, editing = feedback); pipeline.start()
    }
    @After fun teardown() { pipeline.finish(); EditingNodeShadow.refreshBehaviors.clear() }
    @Test fun `accessibility focus alone reads field normally`() {
        focus(); assertEquals(listOf(full()), spoken())
    }
    @Test fun `confirmed input focus announces editing without actions`() {
        enter(); send(AccessibilityEvent.TYPE_VIEW_FOCUSED)
        assertEquals(listOf(editing()), spoken()); assertTrue(ns(field).performedActions.isEmpty()); assertTrue(ns(root).performedActions.isEmpty())
    }
    @Test fun `click without input focus is not editing`() {
        send(AccessibilityEvent.TYPE_VIEW_CLICKED); assertTrue(spoken().isEmpty())
    }
    @Test fun `repeated input focus events do not repeat editing`() {
        enter(); repeat(3) { send(AccessibilityEvent.TYPE_VIEW_FOCUSED) }; assertEquals(listOf(editing()), spoken())
    }
    @Test fun `input followed by accessibility focus speaks exactly once`() {
        enter(); send(AccessibilityEvent.TYPE_VIEW_FOCUSED); focus(); assertEquals(listOf(editing()), spoken())
    }
    @Test fun `accessibility event before input event detects transition for observed field`() {
        focus(); enter(); focus(); send(AccessibilityEvent.TYPE_VIEW_FOCUSED)
        assertEquals(listOf(full(), editing()), spoken())
    }
    @Test fun `already editing field is silent on repeat activation`() {
        enter(); pipeline.start(); send(AccessibilityEvent.TYPE_VIEW_FOCUSED); send(AccessibilityEvent.TYPE_VIEW_CLICKED)
        assertTrue(spoken().isEmpty())
    }
    @Test fun `navigation onto already editing field retains normal focus speech`() {
        enter(); pipeline.start(); focus(); assertEquals(listOf(full()), spoken())
    }
    @Test fun `keyboard window alone is not confirmation`() {
        send(AccessibilityEvent.TYPE_WINDOWS_CHANGED); assertTrue(spoken().isEmpty())
    }
    @Test fun `claimed input focus without actual lookup match is rejected`() {
        field.isFocused = true; send(AccessibilityEvent.TYPE_VIEW_FOCUSED); assertTrue(spoken().isEmpty())
    }
    @Test fun `actual lookup without focused state is rejected`() {
        ns(root).input = field; send(AccessibilityEvent.TYPE_VIEW_FOCUSED); assertTrue(spoken().isEmpty())
    }
    @Test fun `refresh failure stays silent`() {
        enter(); ns(field).setRefreshReturnValue(false); send(AccessibilityEvent.TYPE_VIEW_FOCUSED); assertTrue(spoken().isEmpty())
    }
    @Test fun `refresh exception stays silent`() {
        enter(); EditingNodeShadow.refreshBehaviors[field] = { throw IllegalStateException() }
        send(AccessibilityEvent.TYPE_VIEW_FOCUSED); assertTrue(spoken().isEmpty())
    }
    @Test fun `focus lost during refresh stays silent`() {
        enter(); EditingNodeShadow.refreshBehaviors[field] = { it.isFocused = false; true }
        send(AccessibilityEvent.TYPE_VIEW_FOCUSED); assertTrue(spoken().isEmpty())
    }
    @Test fun `invisible field stays silent`() {
        enter(); field.isVisibleToUser = false; send(AccessibilityEvent.TYPE_VIEW_FOCUSED); assertTrue(spoken().isEmpty())
    }
    @Test fun `disabled field stays silent`() {
        enter(); field.isEnabled = false; send(AccessibilityEvent.TYPE_VIEW_FOCUSED); assertTrue(spoken().isEmpty())
    }
    @Test fun `noneditable control does not announce editing`() {
        enter(); field.isEditable = false; send(AccessibilityEvent.TYPE_VIEW_FOCUSED); assertTrue(spoken().isEmpty())
    }
    @Test fun `absent active root stays silent`() {
        enter(); service.root = null; send(AccessibilityEvent.TYPE_VIEW_FOCUSED); assertTrue(spoken().isEmpty())
    }
    @Test fun `changed window during refresh stays silent`() {
        enter(); EditingNodeShadow.refreshBehaviors[field] = { service.root = node(); true }
        send(AccessibilityEvent.TYPE_VIEW_FOCUSED); assertTrue(spoken().isEmpty())
    }
    @Test fun `foreign package stays silent`() {
        enter(); field.packageName = "other.app"; send(AccessibilityEvent.TYPE_VIEW_FOCUSED); assertTrue(spoken().isEmpty())
    }
    @Test fun `WebView empty field keeps supplemental label`() {
        field.contentDescription = null
        field.extras.putCharSequence("androidx.view.accessibility.AccessibilityNodeInfoCompat.SUPPLEMENTAL_DESCRIPTION_KEY", "Your name")
        val web = node("android.webkit.WebView"); ns(root).addChild(web); ns(web).addChild(field)
        enter(); send(AccessibilityEvent.TYPE_VIEW_FOCUSED)
        assertEquals(listOf(editing()), spoken()); assertTrue(spoken().single().contains("Your name"))
    }
    @Test fun `WebView populated field keeps label and entered value`() {
        field.contentDescription = null; field.text = "Test"
        field.extras.putCharSequence("androidx.view.accessibility.AccessibilityNodeInfoCompat.SUPPLEMENTAL_DESCRIPTION_KEY", "Your name")
        enter(); send(AccessibilityEvent.TYPE_VIEW_FOCUSED)
        assertEquals(listOf(editing()), spoken()); assertTrue(spoken().single().contains("Your name")); assertTrue(spoken().single().contains("Test"))
    }
    @Test fun `native field content and state use unchanged Reader`() {
        field.text = "Test"; AccessibilityNodeInfoCompat.wrap(field).stateDescription = "Required"
        enter(); send(AccessibilityEvent.TYPE_VIEW_FOCUSED); assertEquals(listOf(editing()), spoken())
    }
    @Test fun `touch observes existing input focus without announcing or resuming`() {
        enter(); send(AccessibilityEvent.TYPE_TOUCH_INTERACTION_START); send(AccessibilityEvent.TYPE_VIEW_FOCUSED)
        assertTrue(spoken().isEmpty())
    }
    @Test fun `new touch permits ordinary focus speech after editing`() {
        enter(); send(AccessibilityEvent.TYPE_VIEW_FOCUSED); send(AccessibilityEvent.TYPE_TOUCH_INTERACTION_START); focus()
        assertEquals(listOf(editing(), full()), spoken())
    }
    @Test fun `unrelated speech invalidates accessibility echo suppression`() {
        enter(); send(AccessibilityEvent.TYPE_VIEW_FOCUSED); speech.speak(com.neo.speaktouch.model.Text("Other")); focus()
        assertEquals(listOf(editing(), "Other", full()), spoken())
    }
    @Test fun `input focus can leave and reenter field`() {
        enter(); send(AccessibilityEvent.TYPE_VIEW_FOCUSED); field.isFocused = false; ns(root).input = null
        send(AccessibilityEvent.TYPE_VIEW_FOCUSED, root); enter(); send(AccessibilityEvent.TYPE_VIEW_FOCUSED)
        assertEquals(listOf(editing(), editing()), spoken())
    }
    @Test fun `finished feedback ignores late events`() {
        feedback.finish(); enter(); send(AccessibilityEvent.TYPE_VIEW_FOCUSED); assertTrue(spoken().isEmpty())
    }
    @Test @Config(qualifiers = "pl") fun `editing prefix is localized in Polish`() {
        enter(); send(AccessibilityEvent.TYPE_VIEW_FOCUSED); assertTrue(spoken().single().startsWith("Edycja, "))
    }
    @Test @Config(qualifiers = "pt") fun `editing prefix is localized in Portuguese`() {
        enter(); send(AccessibilityEvent.TYPE_VIEW_FOCUSED); assertTrue(spoken().single().startsWith("Editando, "))
    }
    @Test fun `keyboard window noise neither repeats nor interrupts editing confirmation`() {
        enter(); send(AccessibilityEvent.TYPE_VIEW_FOCUSED)
        send(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, node("android.inputmethodservice.InputMethodService"))
        send(AccessibilityEvent.TYPE_VIEW_FOCUSED); focus()
        assertEquals(listOf(editing()), spoken())
    }
    @Test fun `old accessibility focus clearing does not erase new field confirmation`() {
        val old = node("android.widget.Button"); ns(root).accessibility = old
        pipeline.start(); enter(); send(AccessibilityEvent.TYPE_VIEW_FOCUSED)
        send(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED, old); focus()
        assertEquals(listOf(editing()), spoken())
    }
    @Test fun `real window change cancels pending focus echo`() {
        enter(); send(AccessibilityEvent.TYPE_VIEW_FOCUSED)
        val otherWindow = AccessibilityWindowInfo.obtain().apply { shadowOf(this).setId(2) }
        val otherRoot = node().apply { shadowOf(this).setAccessibilityWindowInfo(otherWindow) }
        service.root = otherRoot; send(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, otherRoot)
        service.root = root; focus()
        assertEquals(listOf(editing(), full()), spoken())
    }
    @Test fun `password field uses existing Reader output unchanged`() {
        field.isPassword = true; field.text = "hidden"
        enter(); send(AccessibilityEvent.TYPE_VIEW_FOCUSED)
        assertEquals(listOf(editing()), spoken())
    }
    @Test fun `input focus moving to noneditable keyboard node does not reset app field baseline`() {
        enter(); send(AccessibilityEvent.TYPE_VIEW_FOCUSED)
        send(AccessibilityEvent.TYPE_VIEW_FOCUSED, node("android.inputmethodservice.KeyboardView"))
        send(AccessibilityEvent.TYPE_VIEW_FOCUSED)
        assertEquals(listOf(editing()), spoken())
    }

}
