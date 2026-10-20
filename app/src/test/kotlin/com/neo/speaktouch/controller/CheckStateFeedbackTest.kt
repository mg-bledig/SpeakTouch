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
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.neo.speaktouch.R
import com.neo.speaktouch.intercepter.event.CheckStateInterceptor
import com.neo.speaktouch.intercepter.event.FocusInterceptor
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
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [22, 28, 31], shadows = [NavigationNodeShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class CheckStateFeedbackTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var window: AccessibilityWindowInfo
    private lateinit var root: AccessibilityNodeInfo
    private lateinit var control: AccessibilityNodeInfo
    private lateinit var service: SliderTestService
    private lateinit var tts: TextToSpeech
    private lateinit var speech: SpeechController
    private lateinit var feedback: CheckStateInterceptor
    private val normalFocus = FocusInterceptor()
    private lateinit var focusSpeech: SpeechInterceptor
    private fun ns(node: AccessibilityNodeInfo): NavigationNodeShadow = Shadow.extract(node)
    private fun node(type: String = "android.view.View") = AccessibilityNodeInfo.obtain().apply {
        className = type; packageName = "test.check"; isVisibleToUser = true; isEnabled = true
        shadowOf(this).setAccessibilityWindowInfo(this@CheckStateFeedbackTest.window)
        ns(this).setRefreshReturnValue(true)
    }
    private fun setFocus(node: AccessibilityNodeInfo) {
        ns(root).focused?.isAccessibilityFocused = false
        ns(root).focused = node; node.isAccessibilityFocused = true
    }
    private fun event(type: Int, source: AccessibilityNodeInfo = control) = AccessibilityEvent.obtain(type).apply {
        shadowOf(this).setSourceNode(source); packageName = "test.check"; className = source.className
    }
    private fun focused(node: AccessibilityNodeInfo = control) {
        setFocus(node); feedback.handle(event(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED, node))
    }
    private fun clicked(source: AccessibilityNodeInfo = control) = feedback.handle(event(AccessibilityEvent.TYPE_VIEW_CLICKED, source))
    private fun changed(source: AccessibilityNodeInfo = control, mask: Int = 64) =
        feedback.handle(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, source).apply { contentChangeTypes = mask })
    private fun spoken() = shadowOf(tts).spokenTextList
    private fun text(id: Int) = context.getString(id)
    @Before fun setup() {
        NavigationNodeShadow.refreshBehaviors.clear()
        window = AccessibilityWindowInfo.obtain().apply {
            shadowOf(this).setId(1); shadowOf(this).setType(AccessibilityWindowInfo.TYPE_APPLICATION)
        }
        root = node(); control = node("android.widget.CheckBox").apply {
            text = "Ham"; isCheckable = true; isClickable = true
        }
        ns(root).addChild(control); setFocus(control)
        service = SliderTestService().apply { this.root = this@CheckStateFeedbackTest.root }
        tts = TextToSpeech(context, null)
        speech = SpeechController(tts, context, Reader(context))
        feedback = CheckStateInterceptor(ServiceController(service), speech)
        focusSpeech = SpeechInterceptor(speech)
        focused()
    }
    @After fun cleanup() {
        feedback.finish(); speech.shutdown(); NavigationNodeShadow.refreshBehaviors.clear()
        shadowOf(Looper.getMainLooper()).idle()
    }
    @Test fun `unchecked checkbox activation announces Checked immediately without focus movement`() {
        control.isChecked = true; clicked()
        assertEquals(listOf(text(R.string.text_checked)), spoken())
        assertEquals(control, ns(root).focused)
        assertTrue(ns(control).performedActions.isEmpty()); assertTrue(ns(root).performedActions.isEmpty())
    }
    @Test fun `checked checkbox activation announces Not checked`() {
        control.isChecked = true; focused(); control.isChecked = false; clicked()
        assertEquals(listOf(text(R.string.text_not_checked)), spoken())
    }
    @Test fun `genuine rapid alternating activations each announce their resulting state`() {
        control.isChecked = true; clicked(); control.isChecked = false; clicked(); control.isChecked = true; clicked()
        assertEquals(listOf(text(R.string.text_checked), text(R.string.text_not_checked), text(R.string.text_checked)), spoken())
    }
    @Test fun `radio selection announces localized Selected`() {
        control.className = "android.widget.RadioButton"; focused(); control.isChecked = true; clicked()
        assertEquals(listOf(text(R.string.text_selected)), spoken())
    }
    @Test fun `already selected radio activation does not claim a state change`() {
        control.className = "android.widget.RadioButton"; control.isChecked = true; focused(); clicked(); clicked()
        assertTrue(spoken().isEmpty())
    }
    @Test fun `unselected radio failed activation stays silent`() {
        control.className = "android.widget.RadioButton"; focused(); clicked()
        assertTrue(spoken().isEmpty())
    }
    @Test fun `selected property alone is not evidence that a radio became checked`() {
        control.className = "android.widget.RadioButton"; focused(); control.isSelected = true; clicked()
        assertTrue(spoken().isEmpty())
    }
    @Test fun `failed click action cannot generate success feedback`() {
        ns(control).setOnPerformActionListener { _, _ -> false }
        assertFalse(control.performAction(AccessibilityNodeInfo.ACTION_CLICK)); clicked()
        assertTrue(spoken().isEmpty())
        assertEquals(listOf(AccessibilityNodeInfo.ACTION_CLICK), ns(control).performedActions)
    }
    @Test fun `unchanged repeated events have no additional state transition to announce`() {
        control.isChecked = true; clicked(); clicked(); changed(); changed()
        assertEquals(listOf(text(R.string.text_checked)), spoken())
    }
    @Test fun `state description event before click announces one confirmed transition`() {
        control.isChecked = true; changed(); clicked()
        assertEquals(listOf(text(R.string.text_checked)), spoken())
    }
    @Test fun `click before state description event announces one confirmed transition`() {
        control.isChecked = true; clicked(); changed()
        assertEquals(listOf(text(R.string.text_checked)), spoken())
    }
    @Test fun `provider state description takes precedence on checkbox transition`() {
        AccessibilityNodeInfoCompat.wrap(control).stateDescription = "Ham included"
        control.isChecked = true; changed(); clicked()
        assertEquals(listOf("Ham included"), spoken())
    }
    @Test fun `provider state description takes precedence on radio transition`() {
        control.className = "android.widget.RadioButton"; focused()
        AccessibilityNodeInfoCompat.wrap(control).stateDescription = "Delivery by post selected"
        control.isChecked = true; clicked(); changed()
        assertEquals(listOf("Delivery by post selected"), spoken())
    }
    @Test fun `description update without checked transition is not reported as activation`() {
        AccessibilityNodeInfoCompat.wrap(control).stateDescription = "Other information"; changed(); clicked()
        assertTrue(spoken().isEmpty())
    }
    @Test fun `WebView checkbox uses supplemental name on focus and state only on activation`() {
        val web = node("android.webkit.WebView"); ns(root).addChild(web); ns(web).addChild(control)
        control.text = ""; control.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_NEXT_HTML_ELEMENT)
        control.extras.putCharSequence("androidx.view.accessibility.AccessibilityNodeInfoCompat.SUPPLEMENTAL_DESCRIPTION_KEY", "Ham")
        val focus = event(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
        focusSpeech.handle(focus); feedback.handle(focus)
        assertTrue(spoken().single().contains("Ham"))
        control.isChecked = true; clicked()
        assertEquals(2, spoken().size); assertEquals(text(R.string.text_checked), spoken().last())
        assertTrue(ns(control).performedActions.isEmpty())
    }
    @Test fun `WebView radio selection needs no native input focus or additional action`() {
        control.className = "android.widget.RadioButton"; control.text = ""
        control.extras.putCharSequence("androidx.view.accessibility.AccessibilityNodeInfoCompat.SUPPLEMENTAL_DESCRIPTION_KEY", "Collect in person")
        control.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_NEXT_HTML_ELEMENT)
        focused(); control.isChecked = true; clicked()
        assertFalse(control.isFocused); assertEquals(listOf(text(R.string.text_selected)), spoken())
        assertTrue(ns(control).performedActions.isEmpty())
    }
    @Test fun `normal focus retains full name role and current state speech`() {
        control.isChecked = true
        val focus = event(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
        focusSpeech.handle(focus); feedback.handle(focus); clicked()
        assertEquals(listOf(Reader(context).read(AccessibilityNodeInfoCompat.wrap(control))), spoken())
        assertTrue(spoken().single().contains("Ham"))
        assertTrue(spoken().single().contains(text(R.string.text_checked)))
    }
    @Test fun `late deselection event from other radio does not erase current baseline`() {
        control.className = "android.widget.RadioButton"; focused()
        val other = node("android.widget.RadioButton").apply { isCheckable = true; isChecked = false }
        clicked(other); changed(other)
        control.isChecked = true; clicked()
        assertEquals(listOf(text(R.string.text_selected)), spoken())
    }
    @Test fun `unfocused control state changes are ignored`() {
        val other = node(); setFocus(other); control.isChecked = true; clicked(); changed()
        assertTrue(spoken().isEmpty())
    }
    @Test fun `focus cleared drops the old transition context`() {
        feedback.handle(event(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED))
        control.isChecked = true; clicked()
        assertTrue(spoken().isEmpty())
    }
    @Test fun `window change cancels old baseline`() {
        feedback.handle(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)); control.isChecked = true; clicked()
        assertTrue(spoken().isEmpty())
    }
    @Test fun `same node with changed window ID has no previous state proof`() {
        assertEquals(1, control.windowId); shadowOf(window).setId(2)
        assertEquals(2, control.windowId); control.isChecked = true; clicked()
        assertTrue(spoken().isEmpty())
    }
    @Test fun `refresh failure prevents state feedback`() {
        NavigationNodeShadow.refreshBehaviors[control] = { false }; control.isChecked = true; clicked()
        assertTrue(spoken().isEmpty())
    }
    @Test fun `refresh exception is contained without feedback`() {
        NavigationNodeShadow.refreshBehaviors[control] = { throw IllegalStateException() }; clicked()
        assertTrue(spoken().isEmpty())
    }
    @Test fun `refresh lost focus prevents state feedback`() {
        control.isChecked = true
        NavigationNodeShadow.refreshBehaviors[control] = { it.isAccessibilityFocused = false; true }; clicked()
        assertTrue(spoken().isEmpty())
    }
    @Test fun `invisible control is not announced`() {
        control.isVisibleToUser = false; control.isChecked = true; clicked()
        assertTrue(spoken().isEmpty())
    }
    @Test fun `event checked payload cannot replace current node state`() {
        feedback.handle(event(AccessibilityEvent.TYPE_VIEW_CLICKED).apply { isChecked = true })
        assertTrue(spoken().isEmpty())
    }
    @Test fun `provider refresh supplies changed state even when event source is older`() {
        val click = event(AccessibilityEvent.TYPE_VIEW_CLICKED)
        NavigationNodeShadow.refreshBehaviors[control] = { it.isChecked = true; true }
        feedback.handle(click)
        assertEquals(listOf(text(R.string.text_checked)), spoken())
    }
    @Test fun `generic subtree and text changes do not drive check feedback`() {
        control.isChecked = true; changed(mask = AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE)
        changed(mask = AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT)
        assertTrue(spoken().isEmpty()); clicked(); assertEquals(1, spoken().size)
    }
    @Test fun `state bit is accepted with other content change bits`() {
        control.isChecked = true; changed(mask = 64 or AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE)
        assertEquals(listOf(text(R.string.text_checked)), spoken())
    }
    @Test fun `buttons links edit fields switches and sliders remain outside this path`() {
        for (type in listOf("android.widget.Button", "android.widget.EditText", "android.widget.Switch",
            "android.widget.SeekBar", "android.view.View")) {
            control.className = type; control.isCheckable = type == "android.widget.Switch"
            focused(); control.isChecked = !control.isChecked; clicked(); changed()
        }
        assertTrue(spoken().isEmpty())
    }
    @Test fun `ordinary touch stops speech and captures state without making an announcement`() {
        focusSpeech.handle(event(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED))
        val count = spoken().size
        val touch = event(AccessibilityEvent.TYPE_TOUCH_INTERACTION_START)
        focusSpeech.handle(touch); feedback.handle(touch)
        assertTrue(shadowOf(tts).isStopped); assertEquals(count, spoken().size)
        control.isChecked = true; clicked()
        assertEquals(count + 1, spoken().size); assertEquals(text(R.string.text_checked), spoken().last())
    }
    @Test fun `service start observes current focus without announcing or moving it`() {
        feedback.start(); assertTrue(spoken().isEmpty())
        control.isChecked = true; clicked(); assertEquals(1, spoken().size)
        assertTrue(ns(control).performedActions.isEmpty())
    }
    @Test fun `teardown ignores late events and restart establishes fresh baseline`() {
        feedback.finish(); control.isChecked = true; clicked(); changed()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2)); assertTrue(spoken().isEmpty())
        feedback.start(); control.isChecked = false; clicked()
        assertEquals(listOf(text(R.string.text_not_checked)), spoken())
    }
    @Test fun `already focused checkbox and radio click handlers do not request accessibility focus again`() {
        for (type in listOf("android.widget.CheckBox", "android.widget.RadioButton")) {
            control.className = type; normalFocus.handle(event(AccessibilityEvent.TYPE_VIEW_CLICKED))
        }
        assertTrue(ns(control).performedActions.isEmpty())
    }
    @Test fun `ordinary button click retains its existing focus action`() {
        control.className = "android.widget.Button"; control.isCheckable = false
        normalFocus.handle(event(AccessibilityEvent.TYPE_VIEW_CLICKED))
        assertEquals(listOf(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS), ns(control).performedActions)
    }
    @Test fun `unfocused checkbox click retains existing focus handling`() {
        control.isAccessibilityFocused = false
        normalFocus.handle(event(AccessibilityEvent.TYPE_VIEW_CLICKED))
        assertEquals(listOf(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS), ns(control).performedActions)
    }
    @Test @Config(qualifiers = "pl") fun `Polish feedback uses existing localized state resources`() {
        control.isChecked = true; clicked(); control.isChecked = false; clicked()
        assertEquals(listOf(text(R.string.text_checked), text(R.string.text_not_checked)), spoken())
    }
    @Test @Config(qualifiers = "pt") fun `Portuguese radio feedback uses existing localized state resource`() {
        control.className = "android.widget.RadioButton"; focused(); control.isChecked = true; clicked()
        assertEquals(listOf(text(R.string.text_selected)), spoken())
    }
}
