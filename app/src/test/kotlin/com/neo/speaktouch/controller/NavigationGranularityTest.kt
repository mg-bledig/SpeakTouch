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

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.neo.speaktouch.R
import com.neo.speaktouch.controller.NavigationGranularityController.Mode
import com.neo.speaktouch.controller.NavigationGranularityController.Result
import com.neo.speaktouch.intercepter.event.CallbackInterceptor
import com.neo.speaktouch.intercepter.event.SpeechInterceptor
import com.neo.speaktouch.intercepter.gesture.GestureInterceptor
import com.neo.speaktouch.model.Text
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
@Config(sdk = [22, 28, 31], shadows = [EditingNodeShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class NavigationGranularityTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var window: AccessibilityWindowInfo
    private lateinit var root: AccessibilityNodeInfo
    private lateinit var target: AccessibilityNodeInfo
    private lateinit var service: SliderTestService
    private lateinit var speech: SpeechController
    private lateinit var tts: TextToSpeech
    private lateinit var controller: NavigationGranularityController
    private lateinit var gestures: GestureInterceptor
    private lateinit var callbacks: CallbackInterceptor
    private val bundles = mutableListOf<Pair<Int, Bundle?>>()
    private var announcementCount = 0
    private fun ns(node: AccessibilityNodeInfo): EditingNodeShadow = Shadow.extract(node)
    private fun node(type: String = "android.view.View") = AccessibilityNodeInfo.obtain().apply {
        className = type; packageName = "test.granularity"; isVisibleToUser = true; isEnabled = true
        shadowOf(this).setAccessibilityWindowInfo(this@NavigationGranularityTest.window)
        ns(this).setRefreshReturnValue(true)
        ns(this).setOnPerformActionListener { _, _ -> false }
    }
    private fun advertise(node: AccessibilityNodeInfo = target, mask: Int = 7, next: Boolean = true, previous: Boolean = true) {
        node.movementGranularities = mask
        node.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_NEXT_AT_MOVEMENT_GRANULARITY)
        node.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY)
        if (next) node.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_NEXT_AT_MOVEMENT_GRANULARITY)
        if (previous) node.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY)
    }
    private fun focus(node: AccessibilityNodeInfo) {
        ns(root).accessibility?.isAccessibilityFocused = false
        ns(root).accessibility = node; node.isAccessibilityFocused = true
    }
    private fun event(type: Int, source: AccessibilityNodeInfo = target) = AccessibilityEvent.obtain(type).apply {
        shadowOf(this).setSourceNode(source); packageName = source.packageName; eventTime = SystemClock.uptimeMillis()
    }
    private fun traversal(from: Int = 0, to: Int = 1, value: String = target.text?.toString() ?: "abcdef",
        forward: Boolean = true, mode: Mode = controller.selectedMode, source: AccessibilityNodeInfo = target) =
        event(AccessibilityEvent.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY, source).apply {
            action = if (forward) AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY
                else AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY
            movementGranularity = mode.mask; fromIndex = from; toIndex = to; text.add(value)
        }
    private fun selected(mode: Mode = Mode.CHARACTERS) {
        repeat(4) { if (controller.selectedMode != mode) controller.cycle(true) }
        assertEquals(mode, controller.selectedMode); announcementCount = shadowOf(tts).spokenTextList.size
    }
    private fun spoken() = shadowOf(tts).spokenTextList.drop(announcementCount)
    private fun perform(forward: Boolean = true): Result = controller.move(forward)
    private fun handle(event: AccessibilityEvent) = controller.handle(event)
    @Before fun setup() {
        EditingNodeShadow.refreshBehaviors.clear()
        window = AccessibilityWindowInfo.obtain().apply {
            shadowOf(this).setId(1); shadowOf(this).setType(AccessibilityWindowInfo.TYPE_APPLICATION)
        }
        root = node(); target = node("android.widget.TextView").apply { text = "abcdef"; isFocusable = true }
        ns(root).addChild(target); shadowOf(window).setRoot(root); focus(target); advertise()
        ns(target).setOnPerformActionListener { action, args -> bundles.add(action to args?.let(::Bundle)); true }
        service = SliderTestService().apply { root = this@NavigationGranularityTest.root }
        tts = TextToSpeech(context, null); speech = SpeechController(tts, context, Reader(context))
        callbacks = CallbackInterceptor(); val wrapper = ServiceController(service)
        controller = NavigationGranularityController(wrapper, speech, callbacks, context); controller.start()
        gestures = GestureInterceptor(FocusController(callbacks, wrapper), wrapper, SliderController(wrapper, speech),
            RefocusController(wrapper, speech), speech, granularity = controller)
    }
    @After fun cleanup() { controller.finish(); callbacks.finish(); speech.shutdown(); EditingNodeShadow.refreshBehaviors.clear() }
    @Test fun `default mode starts selected and effective`() {
        assertEquals(Mode.DEFAULT, controller.selectedMode); assertEquals(Mode.DEFAULT, controller.effectiveMode())
    }
    @Test fun `cycling uses advertised modes and wraps in both directions`() {
        repeat(4) { controller.cycle(true) }; assertEquals(Mode.DEFAULT, controller.selectedMode)
        controller.cycle(false); assertEquals(Mode.LINES, controller.selectedMode)
        controller.cycle(false); assertEquals(Mode.WORDS, controller.selectedMode)
    }
    @Test fun `mode change speaks localized name only`() {
        controller.cycle(true); assertEquals(listOf(context.getString(R.string.navigation_characters)), spoken())
    }
    @Test fun `mask without movement actions is not a supported backend`() {
        advertise(next = false, previous = false); assertEquals(listOf(Mode.DEFAULT), controller.availableModes())
    }
    @Test fun `actions without granularity mask do not advertise modes`() {
        advertise(mask = 0); assertEquals(listOf(Mode.DEFAULT), controller.availableModes())
    }
    @Test fun `only supported granularities appear in cycle`() {
        advertise(mask = Mode.WORDS.mask); assertEquals(listOf(Mode.DEFAULT, Mode.WORDS), controller.availableModes())
        controller.cycle(true); assertEquals(Mode.WORDS, controller.selectedMode)
    }
    @Test fun `unsupported temporary target keeps selected mode and uses Default`() {
        selected(); val other = node("android.widget.Button"); ns(root).addChild(other); focus(other)
        assertEquals(Mode.DEFAULT, controller.effectiveMode()); assertEquals(Result.DEFAULT, perform())
        assertEquals(Mode.CHARACTERS, controller.selectedMode)
        focus(target); assertEquals(Mode.CHARACTERS, controller.effectiveMode())
    }
    @Test fun `characters submit exact action and nonextending Bundle`() {
        selected(); assertEquals(Result.ACCEPTED, perform())
        assertEquals(AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY, bundles.single().first)
        assertEquals(1, bundles.single().second!!.getInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT))
        assertFalse(bundles.single().second!!.getBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN, true))
        assertTrue(spoken().isEmpty()); assertEquals(target, ns(root).accessibility)
    }
    @Test fun `backward word movement submits previous and correct mask`() {
        selected(Mode.WORDS); assertEquals(Result.ACCEPTED, perform(false))
        assertEquals(AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY, bundles.single().first)
        assertEquals(2, bundles.single().second!!.getInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT))
        handle(traversal(0, 3, forward = false)); assertEquals(listOf("abc"), spoken())
    }
    @Test fun `line uses platform mask and exact reported range`() {
        selected(Mode.LINES); perform(); handle(traversal(0, 6)); assertEquals(listOf("abcdef"), spoken())
        assertEquals(4, bundles.single().second!!.getInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT))
    }
    @Test fun `movement does not speak full Reader role name or state`() {
        target.contentDescription = "Label"; AccessibilityNodeInfoCompat.wrap(target).stateDescription = "Required"
        selected(); perform(); handle(traversal()); assertEquals(listOf("a"), spoken())
    }
    @Test fun `delayed event supplies speech with accessibility focus unchanged`() {
        selected(); perform(); assertTrue(spoken().isEmpty())
        handle(traversal()); assertEquals(listOf("a"), spoken())
        assertTrue(target.isAccessibilityFocused); assertEquals(target, ns(root).accessibility)
    }
    @Test fun `provider rejection stays a text failure and does not navigate or wrap`() {
        selected(); ns(target).setOnPerformActionListener { _, _ -> false }
        repeat(3) { assertEquals(Result.NO_MOVEMENT, perform()) }
        assertEquals(target, ns(root).accessibility); assertTrue(spoken().isEmpty())
        assertTrue(ns(root).performedActions.isEmpty())
    }
    @Test fun `missing requested directional action does not fall into sequential navigation`() {
        advertise(previous = false); selected(); assertEquals(Result.NO_MOVEMENT, perform(false))
        assertTrue(bundles.isEmpty()); assertEquals(target, ns(root).accessibility)
    }
    @Test fun `false action followed by traversal cannot announce success`() {
        selected(); ns(target).setOnPerformActionListener { _, _ -> false }; perform(); handle(traversal())
        assertTrue(spoken().isEmpty())
    }
    @Test fun `duplicate event speaks at most once per accepted move`() {
        selected(); perform(); val e = traversal(); handle(e); handle(e); assertEquals(listOf("a"), spoken())
    }
    @Test fun `duplicate from prior request cannot consume a newer move`() {
        selected(); perform(); val old = traversal(); handle(old); perform(); handle(old); handle(traversal(1, 2))
        assertEquals(listOf("a", "b"), spoken())
    }
    @Test fun `wrong direction ignored while valid response still works`() {
        selected(); perform(); handle(traversal(forward = false)); assertTrue(spoken().isEmpty())
        handle(traversal()); assertEquals(listOf("a"), spoken())
    }
    @Test fun `wrong granularity ignored`() {
        selected(); perform(); handle(traversal(mode = Mode.WORDS)); assertTrue(spoken().isEmpty())
    }
    @Test fun `other source ignored`() {
        val other = node(); ns(root).addChild(other); selected(); perform(); handle(traversal(source = other))
        assertTrue(spoken().isEmpty())
    }
    @Test fun `wrong event package ignored`() {
        selected(); perform(); handle(traversal().apply { packageName = "other.app" }); assertTrue(spoken().isEmpty())
    }
    @Test fun `event predating request ignored`() {
        selected(); perform(); handle(traversal().apply { eventTime = SystemClock.uptimeMillis() - 1 })
        assertTrue(spoken().isEmpty())
    }
    @Test fun `focus change invalidates delayed response`() {
        selected(); perform(); val other = node(); ns(root).addChild(other); focus(other)
        handle(event(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED, other)); handle(traversal()); assertTrue(spoken().isEmpty())
    }
    @Test fun `window change invalidates response even without a window event`() {
        selected(); perform(); val other = node(); service.root = other; handle(traversal()); assertTrue(spoken().isEmpty())
    }
    @Test fun `window event cancels old movement`() {
        selected(); perform(); handle(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)); handle(traversal())
        assertTrue(spoken().isEmpty())
    }
    @Test fun `touch start cancels stale text feedback`() {
        selected(); perform(); handle(event(AccessibilityEvent.TYPE_TOUCH_INTERACTION_START)); handle(traversal()); assertTrue(spoken().isEmpty())
    }
    @Test fun `text change invalidates saved movement context`() {
        selected(); perform(); handle(event(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED)); handle(traversal()); assertTrue(spoken().isEmpty())
    }
    @Test fun `unrelated speech cannot be replaced by stale traversal feedback`() {
        selected(); perform(); speech.speak(Text("Other")); handle(traversal()); assertEquals(listOf("Other"), spoken())
    }
    @Test fun `refresh false prevents action`() {
        selected(); ns(target).setRefreshReturnValue(false); assertEquals(Result.UNAVAILABLE, perform()); assertTrue(bundles.isEmpty())
    }
    @Test fun `refresh exception contained`() {
        selected(); EditingNodeShadow.refreshBehaviors[target] = { throw IllegalStateException() }
        assertEquals(Result.UNAVAILABLE, perform()); assertTrue(bundles.isEmpty())
    }
    @Test fun `invisible pending target does not speak`() {
        selected(); perform(); target.isVisibleToUser = false; handle(traversal()); assertTrue(spoken().isEmpty())
    }
    @Test fun `malformed offsets never speak`() {
        selected(); perform(); listOf(-1 to 1, 0 to 99, 2 to 2).forEach { (a,b) -> handle(traversal(a,b)) }
        assertTrue(spoken().isEmpty()); handle(traversal()); assertEquals(listOf("a"), spoken())
    }
    @Test fun `reverse offsets are normalized without custom segmentation`() {
        selected(); perform(false); handle(traversal(3, 0, forward = false)); assertEquals(listOf("abc"), spoken())
    }
    @Test fun `Unicode provider range preserves emoji and combining characters`() {
        // Unicode escapes in the Kotlin literal define one surrogate pair and a combining mark.
        target.text = "A\uD83D\uDC4Be\u0301"
        selected(); perform(); handle(traversal(1, 3)); assertEquals(listOf("\uD83D\uDC4B"), spoken())
        perform(); handle(traversal(3, 5)); assertEquals(listOf("\uD83D\uDC4B", "e\u0301"), spoken())
    }
    @Test fun `invalid surrogate boundary rejected without slicing half an emoji`() {
        target.text = "\uD83D\uDC4B"; selected(); perform(); handle(traversal(0, 1)); assertTrue(spoken().isEmpty())
        handle(traversal(0, 2)); assertEquals(listOf("\uD83D\uDC4B"), spoken())
    }
    @Test fun `ambiguous aggregate event text does not invent content`() {
        selected(); perform(); handle(traversal().apply { text.add("Other") })
        assertTrue(spoken().isEmpty())
    }
    @Test fun `updated node text rejects older event snapshot`() {
        selected(); perform(); target.text = "new text"; handle(traversal(value = "abcdef")); assertTrue(spoken().isEmpty())
    }
    @Test fun `password movement never speaks exposed raw text`() {
        target.isPassword = true; selected(); perform(); handle(traversal()); assertTrue(spoken().isEmpty())
    }
    @Test fun `selection change events do not duplicate traversal speech`() {
        selected(); perform(); handle(event(AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED)); handle(traversal())
        assertEquals(listOf("a"), spoken())
    }
    @Test fun `selecting a mode does not clear selection or move input focus`() {
        target.isEditable = true; target.isFocused = true; ns(root).input = target; target.setTextSelection(1, 4)
        selected(Mode.WORDS); assertEquals(1, target.textSelectionStart); assertEquals(4, target.textSelectionEnd)
        assertTrue(ns(target).performedActions.isEmpty()); assertEquals(target, ns(root).input)
    }
    @Test fun `provider owns caret update and editing is not reannounced`() {
        target.isEditable = true; target.isFocused = true; ns(root).input = target
        val editing = EditingFeedbackController(ServiceController(service), speech, context)
        val normal = SpeechInterceptor(speech, editing = editing); normal.start(); selected()
        ns(target).setOnPerformActionListener { _, args ->
            assertFalse(args!!.getBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN)); target.setTextSelection(1,1); true
        }
        perform(); val e = traversal(); normal.handle(e); handle(e)
        assertEquals(listOf("a"), spoken()); assertEquals(1, target.textSelectionEnd); assertEquals(target, ns(root).input)
    }
    @Test fun `nonfocusable readable descendants provide their own advertised movement`() {
        val group = node().apply { isFocusable = true }
        val child = node("android.widget.TextView").apply { text = "Child" }
        advertise(child, Mode.WORDS.mask); ns(root).addChild(group); ns(group).addChild(child); focus(group)
        ns(child).setOnPerformActionListener { _, _ -> true }
        assertEquals(listOf(Mode.DEFAULT, Mode.WORDS), controller.availableModes()); selected(Mode.WORDS)
        assertEquals(Result.ACCEPTED, perform()); handle(traversal(0, 5, "Child", source = child))
        assertEquals(listOf("Child"), spoken()); assertEquals(group, ns(root).accessibility)
    }
    @Test fun `independent focusable children are not hijacked for parent text movement`() {
        val group = node().apply { isFocusable = true }; ns(root).addChild(group); ns(group).addChild(target); focus(group)
        assertEquals(listOf(Mode.DEFAULT), controller.availableModes())
    }
    @Test fun `parent description prevents unrelated child name traversal`() {
        target.isFocusable = false; val group = node().apply { isFocusable = true; contentDescription = "Group" }
        ns(root).addChild(group); ns(group).addChild(target); focus(group)
        assertEquals(listOf(Mode.DEFAULT), controller.availableModes())
    }
    @Test fun `web document is not scanned for native descendant capabilities`() {
        val web = node("android.webkit.WebView").apply { addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_NEXT_HTML_ELEMENT) }
        target.isFocusable = false; ns(root).addChild(web); ns(web).addChild(target); focus(web)
        assertEquals(listOf(Mode.DEFAULT), controller.availableModes())
    }
    @Test fun `web text uses movement actions and owns provider scrolling`() {
        val web = node("android.webkit.WebView").apply { addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_NEXT_HTML_ELEMENT) }
        ns(root).addChild(web); ns(web).addChild(target); target.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_NEXT_HTML_ELEMENT)
        selected(); perform(); assertTrue(callbacks.hasPendingScroll(web))
        assertEquals(listOf(AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY), ns(target).performedActions)
    }
    @Test fun `native text action scroll is not mistaken for manual recovery`() {
        root.isScrollable = true; selected(); perform(); assertTrue(callbacks.hasPendingScroll(root))
    }
    @Test fun `slider keeps adjustment precedence and selected mode`() {
        selected(Mode.WORDS); val slider = node("android.widget.SeekBar").apply {
            isFocusable = true; addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
            addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        }; ns(root).addChild(slider); focus(slider)
        ns(slider).setOnPerformActionListener { _, _ -> true }
        assertTrue(gestures.handle(AccessibilityService.GESTURE_SWIPE_UP)); assertTrue(gestures.handle(AccessibilityService.GESTURE_SWIPE_DOWN))
        assertEquals(listOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD), ns(slider).performedActions)
        assertEquals(Mode.WORDS, controller.selectedMode); focus(target); assertEquals(Mode.WORDS, controller.effectiveMode())
    }
    @Test fun `nondefault vertical gestures invoke movement action in requested direction`() {
        selected(); gestures.handle(AccessibilityService.GESTURE_SWIPE_DOWN); gestures.handle(AccessibilityService.GESTURE_SWIPE_UP)
        assertEquals(listOf(AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY, AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY), bundles.map { it.first })
    }
    private fun sequentialTarget(): AccessibilityNodeInfo = node("android.widget.Button").apply {
        isFocusable = true; text = "Next"; ns(root).addChild(this)
        ns(this).setOnPerformActionListener { action, _ -> if (action == AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) { focus(this); true } else false }
        ns(target).setOnPerformActionListener { action, _ -> if (action == AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) { focus(target); true } else false }
    }
    @Test fun `Default vertical gestures remain ordinary item navigation`() {
        val next = sequentialTarget(); gestures.handle(AccessibilityService.GESTURE_SWIPE_DOWN); assertEquals(next, ns(root).accessibility)
        gestures.handle(AccessibilityService.GESTURE_SWIPE_UP); assertEquals(target, ns(root).accessibility)
    }
    @Test fun `left right stay sequential after granularity movement without forgetting mode`() {
        val next = sequentialTarget(); selected(); perform(); handle(traversal())
        gestures.handle(AccessibilityService.GESTURE_SWIPE_RIGHT); assertEquals(next, ns(root).accessibility)
        gestures.handle(AccessibilityService.GESTURE_SWIPE_LEFT); assertEquals(target, ns(root).accessibility)
        assertEquals(Mode.CHARACTERS, controller.selectedMode)
    }
    @Test fun `unrelated gesture cancels a pending text announcement`() {
        selected(); perform(); gestures.handle(AccessibilityService.GESTURE_SWIPE_DOWN_AND_RIGHT); handle(traversal()); assertTrue(spoken().isEmpty())
    }
    @Test fun `native three finger selection uses API guard`() {
        val supported = android.os.Build.VERSION.SDK_INT >= 30
        assertEquals(supported, gestures.handle(AccessibilityService.GESTURE_3_FINGER_SWIPE_DOWN))
        assertEquals(if (supported) Mode.CHARACTERS else Mode.DEFAULT, controller.selectedMode)
        if (supported) { gestures.handle(AccessibilityService.GESTURE_3_FINGER_SWIPE_UP); assertEquals(Mode.DEFAULT, controller.selectedMode) }
    }
    @Test fun `synchronous provider event is held until acceptance`() {
        selected(); ns(target).setOnPerformActionListener { _, _ -> handle(traversal()); assertTrue(spoken().isEmpty()); true }
        perform(); assertEquals(listOf("a"), spoken())
    }
    @Test fun `synchronous event from rejected action stays silent`() {
        selected(); ns(target).setOnPerformActionListener { _, _ -> handle(traversal()); false }
        perform(); assertTrue(spoken().isEmpty())
    }
    @Test fun `service cleanup cancels pending feedback and ignores late events`() {
        selected(); perform(); controller.finish(); handle(traversal()); assertTrue(spoken().isEmpty()); assertEquals(Result.UNAVAILABLE, perform())
    }
    @Test fun `service reconnect resets to Default and releases old context`() {
        selected(); perform(); controller.start(); handle(traversal()); assertEquals(Mode.DEFAULT, controller.selectedMode); assertTrue(spoken().isEmpty())
    }
    @Test @Config(qualifiers = "pl") fun `mode names are localized in Polish`() {
        controller.cycle(true); assertEquals(listOf(context.getString(R.string.navigation_characters)), spoken()); assertEquals("Znaki", spoken().single())
    }
    @Test @Config(qualifiers = "pt") fun `mode names are localized in Portuguese`() {
        controller.cycle(true); assertEquals("Caracteres", spoken().single())
    }
    @Test fun `capability removed before response does not speak stale traversal`() {
        selected(); perform(); advertise(mask = 0); handle(traversal()); assertTrue(spoken().isEmpty())
    }
    @Test fun `newer editing caret rejects old range and accepts current reported range`() {
        target.isEditable = true; target.isFocused = true; ns(root).input = target
        selected(); perform(); target.setTextSelection(2, 2)
        handle(traversal(0, 1)); assertTrue(spoken().isEmpty())
        handle(traversal(1, 2)); assertEquals(listOf("b"), spoken())
    }
    @Test fun `group movement advances only across eligible description children`() {
        val group = node().apply { isFocusable = true }
        val first = node("android.widget.TextView").apply { text = "One" }
        val second = node("android.widget.TextView").apply { text = "Two" }
        ns(root).addChild(group); ns(group).addChild(first); ns(group).addChild(second); focus(group)
        advertise(first); advertise(second); ns(first).setOnPerformActionListener { _, _ -> false }
        ns(second).setOnPerformActionListener { _, _ -> true }
        selected(); assertEquals(Result.ACCEPTED, perform()); handle(traversal(0, 1, "Two", source = second))
        assertEquals(listOf("T"), spoken()); assertEquals(group, ns(root).accessibility)
    }
    private fun withBoundary(block: (GestureInterceptor) -> Unit) {
        val prefs = com.neo.speaktouch.utils.SpeechPreferences(context)
        val sound = prefs.audibleNavigationFeedback; val haptic = prefs.hapticNavigationFeedback
        prefs.audibleNavigationFeedback = false; prefs.hapticNavigationFeedback = false
        val wrapper = ServiceController(service); val focus = FocusController(callbacks, wrapper)
        val boundary = BoundaryFeedbackController(focus, wrapper,
            VibratorController(context.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator), context)
        boundary.start()
        try {
            block(GestureInterceptor(focus, wrapper, SliderController(wrapper, speech),
                RefocusController(wrapper, speech), speech, boundaryFeedback = boundary, granularity = controller))
        } finally { boundary.finish(); prefs.audibleNavigationFeedback = sound; prefs.hapticNavigationFeedback = haptic }
    }
    @Test fun `Default down preserves first edge then second request wraps`() {
        val last = sequentialTarget(); focus(last)
        withBoundary { input ->
            input.handle(AccessibilityService.GESTURE_SWIPE_DOWN); assertEquals(last, ns(root).accessibility)
            input.handle(AccessibilityService.GESTURE_SWIPE_DOWN); assertEquals(target, ns(root).accessibility)
        }
    }
    @Test fun `Default up preserves first edge then second request wraps`() {
        val last = sequentialTarget()
        withBoundary { input ->
            input.handle(AccessibilityService.GESTURE_SWIPE_UP); assertEquals(target, ns(root).accessibility)
            input.handle(AccessibilityService.GESTURE_SWIPE_UP); assertEquals(last, ns(root).accessibility)
        }
    }
    @Test fun `unsupported effective Default retains two stage boundary without discarding Words`() {
        val last = sequentialTarget(); selected(Mode.WORDS); focus(last)
        withBoundary { input ->
            input.handle(AccessibilityService.GESTURE_SWIPE_DOWN); assertEquals(last, ns(root).accessibility)
            input.handle(AccessibilityService.GESTURE_SWIPE_DOWN); assertEquals(target, ns(root).accessibility)
        }
        assertEquals(Mode.WORDS, controller.selectedMode)
    }
    @Test fun `text failure does not arm whole window boundary state`() {
        val last = sequentialTarget(); advertise(last); focus(last); selected()
        ns(last).setOnPerformActionListener { action, _ ->
            if (action == AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) { focus(last); true } else false
        }
        withBoundary { input ->
            repeat(2) { input.handle(AccessibilityService.GESTURE_SWIPE_DOWN); assertEquals(last, ns(root).accessibility) }
            selected(Mode.DEFAULT)
            input.handle(AccessibilityService.GESTURE_SWIPE_DOWN); assertEquals(last, ns(root).accessibility)
            input.handle(AccessibilityService.GESTURE_SWIPE_DOWN); assertEquals(target, ns(root).accessibility)
        }
    }

    @Test fun `flat text movement avoids repeated redundant provider refreshes`() {
        selected(); var refreshes = 0
        EditingNodeShadow.refreshBehaviors[target] = { refreshes++; true }
        perform(); handle(traversal()); assertEquals(listOf("a"), spoken())
        assertTrue("Refresh cost should stay bounded per movement, not multiply for each guard", refreshes <= 3)
    }
    @Test fun `reentrant cancellation cannot revive a completed service context`() {
        selected(); ns(target).setOnPerformActionListener { _, _ ->
            handle(traversal()); controller.finish(); true
        }
        assertEquals(Result.ACCEPTED, perform()); handle(traversal()); assertTrue(spoken().isEmpty())
        assertEquals(Result.UNAVAILABLE, perform())
    }

    @Test fun `lost capabilities in active text context do not fall into Default wrapping`() {
        selected(Mode.WORDS); perform(); handle(traversal(0, 3))
        advertise(mask = 0, next = false, previous = false)
        assertEquals(Result.UNAVAILABLE, perform()); assertEquals(target, ns(root).accessibility)
        assertEquals(Mode.WORDS, controller.selectedMode)
    }

    @Test fun `disabled readable content can still use advertised text movement without activation`() {
        target.isEnabled = false; selected(); assertEquals(Result.ACCEPTED, perform())
        handle(traversal()); assertEquals(listOf("a"), spoken())
        assertEquals(listOf(AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY), ns(target).performedActions)
    }


    private fun emptyTraversal(from: Int = 0, to: Int = 1, forward: Boolean = true,
        source: AccessibilityNodeInfo = target, mode: Mode = controller.selectedMode) =
        traversal(from, to, forward = forward, source = source, mode = mode).apply { text.clear() }

    private fun webTarget() {
        val web = node("android.webkit.WebView").apply {
            addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_NEXT_HTML_ELEMENT)
            addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_PREVIOUS_HTML_ELEMENT)
        }
        ns(root).addChild(web); ns(web).addChild(target)
        target.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_NEXT_HTML_ELEMENT)
        target.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_PREVIOUS_HTML_ELEMENT)
    }

    private fun verifyEmptyTextMovements(description: Boolean) {
        val content = "Example link"
        if (description) { target.text = null; target.contentDescription = content }
        else { target.text = content; target.contentDescription = null }
        for ((mode, range) in listOf(Mode.CHARACTERS to (1 to 2), Mode.WORDS to (8 to 12), Mode.LINES to (0 to 12))) {
            for (forward in listOf(true, false)) {
                selected(mode); assertEquals(Result.ACCEPTED, perform(forward))
                val (from, to) = if (forward) range else range.second to range.first
                val response = emptyTraversal(from, to, forward)
                handle(response); handle(response)
                assertEquals(listOf(content.substring(range.first, range.second)), spoken())
                assertEquals(target, ns(root).accessibility)
            }
        }
    }

    @Test fun `web paragraph empty event uses source text in both directions at all modes`() {
        webTarget(); verifyEmptyTextMovements(description = false)
    }
    @Test fun `web link empty event uses source description in both directions at all modes`() {
        webTarget(); verifyEmptyTextMovements(description = true)
    }
    @Test fun `native empty event can use its unique source text`() {
        verifyEmptyTextMovements(description = false)
    }
    @Test fun `populated event retains its explicit range basis when source strings differ`() {
        target.text = "abcdef"; target.contentDescription = "Example link"
        selected(Mode.WORDS); perform(); handle(traversal(8, 12, "Example link"))
        assertEquals(listOf("link"), spoken())
    }
    @Test fun `empty event rejects different text and description even if only one fits bounds`() {
        target.text = "abc"; target.contentDescription = "Example link"
        selected(Mode.WORDS); perform(); handle(emptyTraversal(8, 12)); assertTrue(spoken().isEmpty())
    }
    @Test fun `identical source text and description have one unambiguous range basis`() {
        target.contentDescription = target.text; selected(); perform(); handle(emptyTraversal())
        assertEquals(listOf("a"), spoken())
    }
    @Test fun `empty event does not use hints states supplemental names or Reader speech`() {
        target.text = null; target.contentDescription = null
        AccessibilityNodeInfoCompat.wrap(target).apply { hintText = "Hint"; stateDescription = "State" }
        target.extras.putCharSequence("androidx.view.accessibility.AccessibilityNodeInfoCompat.SUPPLEMENTAL_DESCRIPTION_KEY", "Name")
        selected(); perform(); handle(emptyTraversal()); assertTrue(spoken().isEmpty())
    }
    @Test fun `empty event does not use text exposed as a displayed hint`() {
        target.isEditable = true; AccessibilityNodeInfoCompat.wrap(target).isShowingHintText = true
        selected(); perform(); handle(emptyTraversal()); assertTrue(spoken().isEmpty())
    }
    @Test fun `empty event rejects malformed ranges before accepting valid provider offsets`() {
        webTarget(); selected(); perform()
        for ((from, to) in listOf(-1 to 1, 0 to 99, 2 to 2, Int.MIN_VALUE to Int.MAX_VALUE)) handle(emptyTraversal(from, to))
        assertTrue(spoken().isEmpty()); handle(emptyTraversal()); assertEquals(listOf("a"), spoken())
    }
    @Test fun `empty event uses UTF16 offsets without splitting surrogate pairs`() {
        webTarget(); target.text = "A\uD83D\uDC4Be\u0301"; selected(); perform()
        handle(emptyTraversal(1, 2)); handle(emptyTraversal(2, 3)); assertTrue(spoken().isEmpty())
        handle(emptyTraversal(1, 3)); assertEquals(listOf("\uD83D\uDC4B"), spoken())
        perform(false); handle(emptyTraversal(5, 3, false))
        assertEquals(listOf("\uD83D\uDC4B", "e\u0301"), spoken())
    }
    @Test fun `empty event from another source cannot supply fallback text`() {
        val other = node().apply { text = "Other" }; ns(root).addChild(other)
        selected(); perform(); handle(emptyTraversal(source = other)); assertTrue(spoken().isEmpty())
        handle(emptyTraversal()); assertEquals(listOf("a"), spoken())
    }
    @Test fun `empty event from a stale refreshed source is rejected`() {
        selected(); perform(); ns(target).setRefreshReturnValue(false); handle(emptyTraversal())
        assertTrue(spoken().isEmpty())
    }
    @Test fun `empty event after active window replacement is rejected`() {
        selected(); perform(); service.root = node(); handle(emptyTraversal()); assertTrue(spoken().isEmpty())
    }
    @Test fun `empty event rejects wrong direction and mode without consuming valid response`() {
        selected(); perform(); handle(emptyTraversal(forward = false)); handle(emptyTraversal(mode = Mode.WORDS))
        assertTrue(spoken().isEmpty()); handle(emptyTraversal()); assertEquals(listOf("a"), spoken())
    }
    @Test fun `touch start still cancels delayed empty text traversal`() {
        selected(); perform(); handle(event(AccessibilityEvent.TYPE_TOUCH_INTERACTION_START))
        handle(emptyTraversal()); assertTrue(spoken().isEmpty())
    }
    @Test fun `empty event cannot announce a rejected action or removed capabilities`() {
        selected(); ns(target).setOnPerformActionListener { _, _ -> false }; perform(); handle(emptyTraversal())
        assertTrue(spoken().isEmpty())
        ns(target).setOnPerformActionListener { _, _ -> true }; perform(); advertise(mask = 0)
        handle(emptyTraversal()); assertTrue(spoken().isEmpty())
    }
    @Test fun `empty native editing event follows current caret without changing selection`() {
        target.isEditable = true; target.isFocused = true; ns(root).input = target
        selected(); perform(); target.setTextSelection(1, 1); handle(emptyTraversal())
        assertEquals(listOf("a"), spoken()); assertEquals(1, target.textSelectionEnd)
        assertEquals(target, ns(root).input)
    }
    @Test fun `empty event password text stays silent`() {
        target.isPassword = true; selected(); perform(); handle(emptyTraversal()); assertTrue(spoken().isEmpty())
    }
    @Test fun `empty event buffers until action acceptance without extra focus actions`() {
        selected(); ns(target).setOnPerformActionListener { _, _ -> handle(emptyTraversal()); true }
        assertEquals(Result.ACCEPTED, perform()); assertEquals(listOf("a"), spoken())
        assertEquals(listOf(AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY), ns(target).performedActions)
    }

    @Test fun `empty editable value does not traverse its description label`() {
        target.isEditable = true; target.text = null; target.contentDescription = "Field label"
        selected(); perform(); handle(emptyTraversal()); assertTrue(spoken().isEmpty())
    }
    @Test fun `empty line events use exact provider line offsets in multiline text`() {
        webTarget(); target.text = "First line\nSecond line"; selected(Mode.LINES)
        perform(); handle(emptyTraversal(11, 22)); assertEquals(listOf("Second line"), spoken())
        perform(false); handle(emptyTraversal(10, 0, false))
        assertEquals(listOf("Second line", "First line"), spoken())
    }

    private fun verifySpaceCharacters(emptyEvent: Boolean, description: Boolean = false) {
        if (description) { target.text = null; target.contentDescription = "A B" }
        else target.text = "A B"
        selected(Mode.CHARACTERS)
        for (forward in listOf(true, false)) {
            assertEquals(Result.ACCEPTED, perform(forward))
            val response = traversal(if (forward) 1 else 2, if (forward) 2 else 1,
                value = "A B", forward = forward)
            if (emptyEvent) response.text.clear()
            handle(response); handle(response)
        }
        assertEquals(List(2) { context.getString(R.string.navigation_space) }, spoken())
    }
    @Test fun `native character spaces are named in both directions once per movement`() {
        verifySpaceCharacters(emptyEvent = false)
    }
    @Test fun `web source text character spaces are named in both directions`() {
        webTarget(); verifySpaceCharacters(emptyEvent = true)
    }
    @Test fun `web source description character spaces are named in both directions`() {
        webTarget(); verifySpaceCharacters(emptyEvent = true, description = true)
    }
    @Test fun `adjacent normal characters and punctuation keep their exact speech`() {
        target.text = "A B!"; selected()
        for ((from, to) in listOf(0 to 1, 1 to 2, 2 to 3, 3 to 4)) {
            perform(); handle(emptyTraversal(from, to))
        }
        assertEquals(listOf("A", context.getString(R.string.navigation_space), "B", "!"), spoken())
    }
    @Test fun `word and line space ranges are not renamed in either direction`() {
        target.text = "A B"
        for (mode in listOf(Mode.WORDS, Mode.LINES)) {
            selected(mode)
            for (forward in listOf(true, false)) {
                perform(forward); handle(emptyTraversal(1, 2, forward))
            }
            assertEquals(listOf(" ", " "), spoken())
        }
    }
    @Test fun `other whitespace and multi character ranges are not renamed`() {
        target.text = "A\t\n\u00a0  B"; selected()
        for ((from, to) in listOf(1 to 2, 2 to 3, 3 to 4, 4 to 6)) {
            perform(); handle(emptyTraversal(from, to))
        }
        assertEquals(listOf("\t", "\n", "\u00a0", "  "), spoken())
    }
    @Test fun `space label remains suppressed for password sources and events`() {
        target.text = "A B"; target.isPassword = true; selected(); perform(); handle(emptyTraversal(1, 2))
        assertTrue(spoken().isEmpty())
        target.isPassword = false; perform(); handle(emptyTraversal(1, 2).apply { isPassword = true })
        assertTrue(spoken().isEmpty())
    }
    @Test fun `space feedback never changes editing selection or focus`() {
        target.text = "A B"; target.isEditable = true; target.isFocused = true; ns(root).input = target
        selected(); perform(); target.setTextSelection(2, 2); handle(emptyTraversal(1, 2))
        assertEquals(listOf(context.getString(R.string.navigation_space)), spoken())
        assertEquals(2, target.textSelectionStart); assertEquals(2, target.textSelectionEnd)
        assertEquals(target, ns(root).input); assertEquals(target, ns(root).accessibility)
    }
    @Test @Config(qualifiers = "en") fun `space is localized in English`() {
        verifySpaceCharacters(emptyEvent = true); assertEquals(listOf("space", "space"), spoken())
    }
    @Test @Config(qualifiers = "pl") fun `space is localized in Polish`() {
        verifySpaceCharacters(emptyEvent = true); assertEquals(listOf("spacja", "spacja"), spoken())
    }
    @Test @Config(qualifiers = "pt") fun `space is localized in Portuguese`() {
        verifySpaceCharacters(emptyEvent = true); assertEquals(listOf("espaço", "espaço"), spoken())
    }

}
