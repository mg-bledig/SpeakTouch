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
import android.os.Bundle
import android.os.Looper
import android.os.Vibrator
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.neo.speaktouch.intercepter.event.CallbackInterceptor
import com.neo.speaktouch.utils.SpeechPreferences
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
class WebNavigationTest {
    private lateinit var window: AccessibilityWindowInfo
    private lateinit var root: AccessibilityNodeInfo
    private lateinit var web: AccessibilityNodeInfo
    private lateinit var button: AccessibilityNodeInfo
    private lateinit var before: AccessibilityNodeInfo
    private lateinit var after: AccessibilityNodeInfo
    private lateinit var service: SliderTestService
    private lateinit var wrapper: ServiceController
    private lateinit var callbacks: CallbackInterceptor
    private lateinit var focus: FocusController
    private val html = mutableListOf<Pair<Int, Bundle?>>()
    private val focusActions = mutableListOf<AccessibilityNodeInfo>()
    private var accepted = true
    private var duringAction: (() -> Unit)? = null
    private fun ns(node: AccessibilityNodeInfo): NavigationNodeShadow = Shadow.extract(node)
    private fun node(text: String? = null, webNode: Boolean = false) = AccessibilityNodeInfo.obtain().apply {
        className = "android.view.View"; packageName = "test.web"; this.text = text
        isVisibleToUser = true; isEnabled = true
        shadowOf(this).setAccessibilityWindowInfo(this@WebNavigationTest.window)
        ns(this).setRefreshReturnValue(true)
        if (webNode) {
            addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_NEXT_HTML_ELEMENT)
            addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_PREVIOUS_HTML_ELEMENT)
        }
        ns(this).setOnPerformActionListener { action, args ->
            when (action) {
                AccessibilityNodeInfo.ACTION_NEXT_HTML_ELEMENT,
                AccessibilityNodeInfo.ACTION_PREVIOUS_HTML_ELEMENT -> {
                    html.add(action to args?.let { Bundle(it) }); duringAction?.invoke(); accepted
                }
                AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS -> {
                    focusActions.add(this); setFocus(this); true
                }
                else -> false
            }
        }
    }
    private fun setFocus(node: AccessibilityNodeInfo) {
        ns(root).focused?.isAccessibilityFocused = false
        ns(root).focused = node; node.isAccessibilityFocused = true
    }
    private fun add(parent: AccessibilityNodeInfo, child: AccessibilityNodeInfo) = ns(parent).addChild(child)
    private fun event(type: Int, source: AccessibilityNodeInfo = web) = AccessibilityEvent.obtain(type).apply {
        packageName = "test.web"; shadowOf(this).setSourceNode(source)
    }
    @Before fun setup() {
        NavigationNodeShadow.refreshBehaviors.clear()
        RuntimeEnvironment.getApplication().getSharedPreferences(SpeechPreferences.FILE_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        window = AccessibilityWindowInfo.obtain().apply {
            shadowOf(this).setId(1); shadowOf(this).setType(AccessibilityWindowInfo.TYPE_APPLICATION)
        }
        root = node(); before = node("Native before"); after = node("Native after")
        web = node("Document", true).apply { className = "android.webkit.WebView"; isScrollable = true }
        button = node("Test button", true).apply { className = "android.widget.Button"; isClickable = true }
        add(root, before); add(root, web); add(root, after); add(web, button)
        shadowOf(window).setRoot(root); setFocus(button)
        service = SliderTestService().apply { this.root = this@WebNavigationTest.root }
        wrapper = ServiceController(service); callbacks = CallbackInterceptor(); focus = FocusController(callbacks, wrapper)
    }
    @After fun cleanup() {
        callbacks.finish(); NavigationNodeShadow.refreshBehaviors.clear()
        RuntimeEnvironment.getApplication().getSharedPreferences(SpeechPreferences.FILE_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test fun `forward submits default HTML selector without focusing or native traversal`() {
        assertEquals(FocusController.NavigationOutcome.PROVIDER_HANDLED, focus.moveFocusToNext())
        assertEquals(AccessibilityNodeInfo.ACTION_NEXT_HTML_ELEMENT, html.single().first)
        assertEquals("", html.single().second!!.getString(AccessibilityNodeInfo.ACTION_ARGUMENT_HTML_ELEMENT_STRING))
        assertTrue(focusActions.isEmpty()); assertEquals(button, wrapper.getFocused())
    }
    @Test fun `backward submits previous HTML action and empty selector`() {
        assertEquals(FocusController.NavigationOutcome.PROVIDER_HANDLED, focus.moveFocusToPrevious())
        assertEquals(AccessibilityNodeInfo.ACTION_PREVIOUS_HTML_ELEMENT, html.single().first)
        assertEquals("", html.single().second!!.getString(AccessibilityNodeInfo.ACTION_ARGUMENT_HTML_ELEMENT_STRING))
        assertTrue(focusActions.isEmpty())
    }
    @Test fun `accepted delayed focus is handled exactly once and never reports exhaustion`() {
        val results = mutableListOf<FocusController.NavigationOutcome>()
        focus.moveFocusToNext(onResult = { results.add(it) })
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertEquals(listOf(FocusController.NavigationOutcome.PROVIDER_HANDLED), results)
        assertTrue(focusActions.isEmpty()); assertEquals(1, html.size)
        val apples = node("Apples", true); setFocus(apples)
        assertEquals(apples, wrapper.getFocused()); assertTrue(focusActions.isEmpty())
    }
    @Test fun `provider selects passive text regardless of native input focusability`() {
        val apples = node("Apples", true)
        duringAction = { setFocus(apples) }
        assertFalse(apples.isFocusable); assertFalse(apples.isClickable)
        assertEquals(FocusController.NavigationOutcome.PROVIDER_HANDLED, focus.moveFocusToNext())
        assertEquals(apples, wrapper.getFocused()); assertTrue(focusActions.isEmpty())
    }
    @Test fun `forward exhaustion leaves whole web subtree for native successor`() {
        val tail = node("Provider-owned tail", true); add(web, tail); accepted = false
        assertEquals(FocusController.NavigationOutcome.FOCUS_REQUESTED, focus.moveFocusToNext())
        assertEquals(listOf(after), focusActions); assertEquals(1, html.size)
    }
    @Test fun `backward exhaustion exits to native predecessor without entering web children`() {
        accepted = false
        assertEquals(FocusController.NavigationOutcome.FOCUS_REQUESTED, focus.moveFocusToPrevious())
        assertEquals(listOf(before), focusActions)
    }
    @Test fun `forward native entry focuses container before provider reading`() {
        setFocus(before)
        assertEquals(FocusController.NavigationOutcome.FOCUS_REQUESTED, focus.moveFocusToNext())
        assertEquals(listOf(web), focusActions); assertTrue(html.isEmpty())
        assertEquals(FocusController.NavigationOutcome.PROVIDER_HANDLED, focus.moveFocusToNext())
    }
    @Test fun `backward native entry asks provider for last web item`() {
        setFocus(after)
        assertEquals(FocusController.NavigationOutcome.PROVIDER_HANDLED, focus.moveFocusToPrevious())
        assertEquals(AccessibilityNodeInfo.ACTION_PREVIOUS_HTML_ELEMENT, html.single().first)
        assertTrue(focusActions.isEmpty())
    }
    @Test fun `previous from container exits rather than wrapping inside document`() {
        setFocus(web)
        assertEquals(FocusController.NavigationOutcome.FOCUS_REQUESTED, focus.moveFocusToPrevious())
        assertEquals(listOf(before), focusActions); assertTrue(html.isEmpty())
    }
    @Test fun `ordinary native navigation remains unchanged`() {
        setFocus(after)
        val native = node("Last native"); add(root, native)
        assertEquals(FocusController.NavigationOutcome.FOCUS_REQUESTED, focus.moveFocusToNext())
        assertEquals(listOf(native), focusActions); assertTrue(html.isEmpty())
    }
    @Test fun `clickability alone never enables provider navigation`() {
        button.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_NEXT_HTML_ELEMENT)
        button.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_PREVIOUS_HTML_ELEMENT)
        assertEquals(WebNavigationController.Result.UNSUPPORTED, focus.webNavigation.navigate(button, true))
        assertTrue(html.isEmpty())
    }
    @Test fun `missing directional action is unsupported not exhaustion`() {
        button.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_NEXT_HTML_ELEMENT)
        assertEquals(WebNavigationController.Result.UNSUPPORTED, focus.webNavigation.navigate(button, true))
        assertFalse(focus.webNavigation.exhausted(root, button, true)); assertTrue(html.isEmpty())
    }
    @Test fun `advertised actions outside a web container are invalid not an edge`() {
        after.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_NEXT_HTML_ELEMENT); setFocus(after)
        assertEquals(FocusController.NavigationOutcome.UNAVAILABLE, focus.moveFocusToNext())
        assertTrue(html.isEmpty()); assertTrue(focusActions.isEmpty())
    }
    @Test fun `stale origin never invokes provider or signals an edge`() {
        NavigationNodeShadow.refreshBehaviors[button] = { false }
        assertEquals(FocusController.NavigationOutcome.UNAVAILABLE, focus.moveFocusToNext())
        assertTrue(html.isEmpty()); assertFalse(focus.webNavigation.exhausted(root, button, true))
    }
    @Test fun `refresh exception is unavailable`() {
        NavigationNodeShadow.refreshBehaviors[button] = { throw IllegalStateException() }
        assertEquals(FocusController.NavigationOutcome.UNAVAILABLE, focus.moveFocusToNext())
        assertTrue(html.isEmpty())
    }
    @Test fun `hidden enclosing web container is rejected`() {
        NavigationNodeShadow.refreshBehaviors[web] = { it.isVisibleToUser = false; true }
        assertEquals(FocusController.NavigationOutcome.UNAVAILABLE, focus.moveFocusToNext())
        assertTrue(html.isEmpty())
    }
    @Test fun `window change during rejected action does not establish exhaustion`() {
        accepted = false; duringAction = { service.root = node("Other window") }
        assertEquals(FocusController.NavigationOutcome.UNAVAILABLE, focus.moveFocusToNext())
        assertTrue(focusActions.isEmpty()); assertFalse(focus.webNavigation.exhausted(root, button, true))
    }
    @Test fun `focus change during rejected action prevents native fallback`() {
        accepted = false; duringAction = { setFocus(after) }
        assertEquals(FocusController.NavigationOutcome.UNAVAILABLE, focus.moveFocusToNext())
        assertTrue(focusActions.isEmpty())
    }
    @Test fun `provider scroll ownership survives delayed focus and is cleared by touch`() {
        focus.moveFocusToNext()
        assertTrue(callbacks.hasPendingScroll(web)); assertTrue(callbacks.hasPendingScroll(button))
        callbacks.handle(event(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED, button))
        assertTrue(callbacks.hasPendingScroll(web))
        callbacks.handle(event(AccessibilityEvent.TYPE_TOUCH_INTERACTION_START))
        assertFalse(callbacks.hasPendingScroll(web))
    }
    @Test fun `window change and teardown clear provider scroll ownership`() {
        focus.moveFocusToNext(); callbacks.handle(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED))
        assertFalse(callbacks.hasPendingScroll(web))
        focus.moveFocusToNext(); callbacks.finish(); assertFalse(callbacks.hasPendingScroll(web))
    }
    @Test fun `provider scrolling cannot trigger manual focus recovery`() {
        val interpreter = ManualScrollInterpreter(wrapper, callbacks)
        val recovery = ManualScrollFocusController(wrapper, interpreter)
        interpreter.start(); recovery.start()
        fun send(event: AccessibilityEvent) { interpreter.handle(event); recovery.handle(event); callbacks.handle(event) }
        send(event(AccessibilityEvent.TYPE_TOUCH_INTERACTION_START))
        send(event(AccessibilityEvent.TYPE_TOUCH_INTERACTION_END))
        focus.moveFocusToNext()
        ns(root).focused = null; button.isAccessibilityFocused = false
        val scroll = event(AccessibilityEvent.TYPE_VIEW_SCROLLED).apply {
            eventTime = 100; scrollX = 0; scrollY = 100; maxScrollY = 1000; isScrollable = true
        }
        send(scroll)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertNull(interpreter.confirmedScroll(scroll)); assertTrue(focusActions.isEmpty())
        recovery.finish(); interpreter.finish()
    }
    @Test fun `accepted web action does not arm boundary or wrap on repeated swipes`() {
        val context = RuntimeEnvironment.getApplication()
        SpeechPreferences(context).apply { audibleNavigationFeedback = false; hapticNavigationFeedback = false }
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        val boundary = BoundaryFeedbackController(focus, wrapper, VibratorController(vibrator), context)
        boundary.start(); repeat(3) { boundary.navigate(true) }
        assertEquals(3, html.size); assertTrue(focusActions.isEmpty()); boundary.finish()
    }
    @Test fun `web edge wraps only after second proven exhaustion`() {
        // Native final item stays in the window: provider exhaustion reaches it first.
        accepted = false; focus.moveFocusToNext(); assertEquals(after, wrapper.getFocused())
        val context = RuntimeEnvironment.getApplication()
        SpeechPreferences(context).apply { audibleNavigationFeedback = false; hapticNavigationFeedback = false }
        val boundary = BoundaryFeedbackController(focus, wrapper,
            VibratorController(context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator), context)
        boundary.start(); focusActions.clear()
        boundary.navigate(true); assertTrue(focusActions.isEmpty())
        boundary.navigate(true); assertEquals(listOf(before), focusActions)
        boundary.finish()
    }
    @Test fun `no active root is unavailable without provider action`() {
        service.root = null
        assertEquals(WebNavigationController.Result.UNAVAILABLE, focus.webNavigation.navigate(button, true))
        assertTrue(html.isEmpty())
    }
    @Test fun `lost focus during validation is unavailable`() {
        NavigationNodeShadow.refreshBehaviors[button] = { it.isAccessibilityFocused = false; true }
        assertEquals(FocusController.NavigationOutcome.UNAVAILABLE, focus.moveFocusToNext())
        assertTrue(html.isEmpty()); assertTrue(focusActions.isEmpty())
    }
    @Test fun `changed window ID during validation is unavailable`() {
        assertEquals(1, root.windowId); assertEquals(1, button.windowId)
        NavigationNodeShadow.refreshBehaviors[button] = {
            shadowOf(window).setId(2)
            assertEquals(2, root.windowId); assertEquals(2, it.windowId)
            true
        }
        assertEquals(FocusController.NavigationOutcome.UNAVAILABLE, focus.moveFocusToNext())
        assertTrue(html.isEmpty())
    }
    @Test fun `provider exception is unavailable never an exhaustion proof`() {
        duringAction = { throw IllegalStateException("provider detached") }
        assertEquals(FocusController.NavigationOutcome.UNAVAILABLE, focus.moveFocusToNext())
        assertFalse(focus.webNavigation.exhausted(root, button, true)); assertTrue(focusActions.isEmpty())
    }
    @Test fun `pure web edge cues first then delegates wrap to provider in both directions`() {
        root = node(); add(root, web); service.root = root; setFocus(button)
        accepted = false
        // Actions from the container select the opposite document edge; actions from
        // the current final/first element report exhaustion. This is provider behavior.
        var wrapped = 0
        ns(web).setOnPerformActionListener { action, args ->
            if (action == AccessibilityNodeInfo.ACTION_NEXT_HTML_ELEMENT ||
                action == AccessibilityNodeInfo.ACTION_PREVIOUS_HTML_ELEMENT) {
                assertEquals("", args!!.getString(AccessibilityNodeInfo.ACTION_ARGUMENT_HTML_ELEMENT_STRING))
                wrapped++; true
            } else false
        }
        val context = RuntimeEnvironment.getApplication()
        SpeechPreferences(context).apply { audibleNavigationFeedback = false; hapticNavigationFeedback = false }
        val boundary = BoundaryFeedbackController(focus, wrapper,
            VibratorController(context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator), context)
        boundary.start()
        for (forward in listOf(true, false)) {
            boundary.navigate(forward); assertEquals(0, wrapped)
            boundary.navigate(forward); assertEquals(1, wrapped)
            assertTrue(focusActions.isEmpty()); wrapped = 0
        }
        boundary.finish()
    }
    @Test fun `delayed genuine focus event speaks selected web text once`() {
        val context = RuntimeEnvironment.getApplication()
        val tts = android.speech.tts.TextToSpeech(context) { }
        val speech = SpeechController(tts, context, com.neo.speaktouch.utils.Reader(context))
        val interceptor = com.neo.speaktouch.intercepter.event.SpeechInterceptor(speech)
        focus.moveFocusToNext()
        assertNull(shadowOf(tts).lastSpokenText)
        val apples = node("Apples", true); setFocus(apples)
        interceptor.handle(event(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED, apples))
        assertEquals("Apples", shadowOf(tts).lastSpokenText)
        assertEquals(1, shadowOf(tts).spokenTextList.size)
        speech.shutdown()
    }

    @Test fun `native WebView host does not replace action capable document root`() {
        val host = node().apply { className = "android.webkit.WebView" }
        root = node(); add(root, before); add(root, host); add(root, after); add(host, web)
        service.root = root; setFocus(button)
        assertEquals(web, focus.webNavigation.container(button))
        assertEquals(FocusController.NavigationOutcome.PROVIDER_HANDLED, focus.moveFocusToNext())
        assertTrue(callbacks.hasPendingScroll(host)); assertTrue(callbacks.hasPendingScroll(web))
        setFocus(after); html.clear()
        assertEquals(FocusController.NavigationOutcome.PROVIDER_HANDLED, focus.moveFocusToPrevious())
        assertEquals(AccessibilityNodeInfo.ACTION_PREVIOUS_HTML_ELEMENT, html.single().first)
        assertTrue(focusActions.isEmpty())
    }
    @Test fun `virtual document visibility uses visible enclosing WebView host`() {
        val host = node().apply { className = "android.webkit.WebView" }
        root = node(); add(root, host); add(host, web); service.root = root; setFocus(button)
        NavigationNodeShadow.refreshBehaviors[web] = { it.isVisibleToUser = false; true }
        assertEquals(FocusController.NavigationOutcome.PROVIDER_HANDLED, focus.moveFocusToNext())
        NavigationNodeShadow.refreshBehaviors[host] = { it.isVisibleToUser = false; true }
        assertEquals(FocusController.NavigationOutcome.UNAVAILABLE, focus.moveFocusToNext())
    }

    @Test fun `exhaustion exits nested host without retrying its generic scroll actions`() {
        val host = node().apply {
            className = "android.webkit.WebView"; isFocusable = true; isScrollable = true
            addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
            addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        }
        var scrolls = 0
        ns(host).setOnPerformActionListener { _, _ -> scrolls++; true }
        root = node(); add(root, before); add(root, host); add(root, after); add(host, web)
        service.root = root; setFocus(button); accepted = false
        assertEquals(FocusController.NavigationOutcome.FOCUS_REQUESTED, focus.moveFocusToNext())
        assertEquals(listOf(after), focusActions); assertEquals(0, scrolls)
        focusActions.clear(); setFocus(button)
        assertEquals(FocusController.NavigationOutcome.FOCUS_REQUESTED, focus.moveFocusToPrevious())
        assertEquals(listOf(before), focusActions); assertEquals(0, scrolls)
    }

    @Test fun `native ancestor scrolling after web exit retains actual focused origin for retry`() {
        accepted = false; after.isVisibleToUser = false
        NavigationNodeShadow.refreshBehaviors[after] = { it.isVisibleToUser = after.isVisibleToUser; true }
        root.isScrollable = true
        root.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        var scrolls = 0
        ns(root).setOnPerformActionListener { action, _ ->
            if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) { scrolls++; true } else false
        }
        val results = mutableListOf<FocusController.NavigationOutcome>()
        assertEquals(FocusController.NavigationOutcome.SCROLL_PENDING,
            focus.moveFocusToNext(onResult = { results.add(it) }))
        after.isVisibleToUser = true
        callbacks.handle(event(AccessibilityEvent.TYPE_VIEW_SCROLLED, root))
        assertEquals(listOf(after), focusActions); assertEquals(1, scrolls)
        assertEquals(listOf(FocusController.NavigationOutcome.SCROLL_PENDING,
            FocusController.NavigationOutcome.FOCUS_REQUESTED), results)
    }

    @Test fun `nested native host cannot block a proven web edge or become its wrap target`() {
        val host = node().apply { className = "android.webkit.WebView"; isFocusable = true }
        root = node(); add(root, host); add(host, web); service.root = root; setFocus(button)
        accepted = false
        var wraps = 0
        ns(web).setOnPerformActionListener { action, _ ->
            if (action == AccessibilityNodeInfo.ACTION_NEXT_HTML_ELEMENT) { wraps++; true } else false
        }
        val context = RuntimeEnvironment.getApplication()
        SpeechPreferences(context).apply { audibleNavigationFeedback = false; hapticNavigationFeedback = false }
        val boundary = BoundaryFeedbackController(focus, wrapper,
            VibratorController(context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator), context)
        boundary.start()
        boundary.navigate(true); assertEquals(0, wraps); assertTrue(focusActions.isEmpty())
        boundary.navigate(true); assertEquals(1, wraps); assertTrue(focusActions.isEmpty())
        boundary.finish()
    }

}
