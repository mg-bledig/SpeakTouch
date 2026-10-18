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
import android.media.AudioTrack
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.neo.speaktouch.intercepter.event.CallbackInterceptor
import com.neo.speaktouch.intercepter.event.NavigationSoundInterceptor
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
import org.robolectric.shadows.ShadowAudioTrack
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [22, 28, 31], shadows = [NavigationAudioShadow::class, NavigationNodeShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class ManualScrollFocusTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var window: AccessibilityWindowInfo
    private lateinit var root: AccessibilityNodeInfo
    private lateinit var container: AccessibilityNodeInfo
    private lateinit var old: AccessibilityNodeInfo
    private lateinit var first: AccessibilityNodeInfo
    private lateinit var last: AccessibilityNodeInfo
    private lateinit var service: SliderTestService
    private lateinit var wrapper: ServiceController
    private lateinit var callbacks: CallbackInterceptor
    private lateinit var scrollFeedback: ScrollFeedbackController
    private lateinit var interpreter: ManualScrollInterpreter
    private lateinit var recovery: ManualScrollFocusController
    private val actions = mutableListOf<Pair<AccessibilityNodeInfo, Int>>()
    private var time = 1L
    private fun nodeShadow(node: AccessibilityNodeInfo): NavigationNodeShadow = Shadow.extract(node)
    private fun node(label: String? = null) = AccessibilityNodeInfo.obtain().apply {
        packageName = "test.manualscroll"
        className = if (label == null) "android.view.View" else "android.widget.Button"
        text = label
        isVisibleToUser = true
        isEnabled = true
        isFocusable = label != null
        shadowOf(this).setAccessibilityWindowInfo(this@ManualScrollFocusTest.window)
        nodeShadow(this).setRefreshReturnValue(true)
    }
    private fun focus(target: AccessibilityNodeInfo?) {
        nodeShadow(root).focused?.isAccessibilityFocused = false
        target?.isAccessibilityFocused = true
        nodeShadow(root).focused = target
    }
    private fun record(target: AccessibilityNodeInfo) {
        nodeShadow(target).setOnPerformActionListener { action, _ ->
            actions.add(target to action)
            if (action == AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) { focus(target); true } else false
        }
    }
    private fun event(type: Int, source: AccessibilityNodeInfo? = null) =
        AccessibilityEvent.obtain(type).apply {
            eventTime = time++
            packageName = "test.manualscroll"
            shadowOf(this).setWindowId(window.id)
            source?.let { shadowOf(this).setSourceNode(it) }
        }
    private fun scroll(y: Int, source: AccessibilityNodeInfo = container) =
        event(AccessibilityEvent.TYPE_VIEW_SCROLLED, source).apply {
            scrollY = y; maxScrollY = 500; scrollX = 0; isScrollable = true
        }
    private fun horizontal(x: Int, content: Boolean = false) =
        event(if (content) AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED else AccessibilityEvent.TYPE_VIEW_SCROLLED, container).apply {
            scrollX = x; scrollY = 0; maxScrollX = 2000; maxScrollY = 0; isScrollable = true
        }
    private fun advance(milliseconds: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(milliseconds))
    private fun prepareHorizontal() {
        interpreter.start() // No prior scroll position: retain the controller's real focus history.
        deliver(horizontal(496, content = true))
        old.isVisibleToUser = false
        focus(null)
        deliver(event(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED, old))
    }
    private fun deliver(event: AccessibilityEvent, settle: Boolean = true) {
        // Match production order, including settled recovery rather than synchronous focus actions.
        interpreter.handle(event)
        recovery.handle(event)
        scrollFeedback.handle(event)
        callbacks.handle(event)
        if (settle) shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ManualScrollFocusController.SETTLE_MS))
    }
    @Before fun setup() {
        context.getSharedPreferences(SpeechPreferences.FILE_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        NavigationNodeShadow.refreshBehaviors.clear()
        window = AccessibilityWindowInfo.obtain().apply {
            shadowOf(this).setId(1); shadowOf(this).setType(AccessibilityWindowInfo.TYPE_APPLICATION)
        }
        root = node()
        container = node().apply {
            className = "androidx.recyclerview.widget.RecyclerView"; isScrollable = true
            collectionInfo = AccessibilityNodeInfo.CollectionInfo.obtain(100, 1, false)
        }
        nodeShadow(root).addChild(container)
        old = node("Old"); first = node("First"); last = node("Last")
        listOf(old, first, last).forEach { nodeShadow(container).addChild(it); record(it) }
        shadowOf(window).setRoot(root)
        service = SliderTestService().apply { this.root = this@ManualScrollFocusTest.root }
        wrapper = ServiceController(service)
        callbacks = CallbackInterceptor()
        scrollFeedback = ScrollFeedbackController(wrapper, callbacks, context)
        interpreter = ManualScrollInterpreter(wrapper, callbacks)
        recovery = ManualScrollFocusController(wrapper, interpreter)
        focus(old); recovery.start()
        deliver(event(AccessibilityEvent.TYPE_TOUCH_INTERACTION_START))
        deliver(scroll(100))
        deliver(event(AccessibilityEvent.TYPE_TOUCH_INTERACTION_END))
        assertTrue(actions.isEmpty())
    }
    @After fun cleanup() {
        if (::recovery.isInitialized) recovery.finish()
        if (::scrollFeedback.isInitialized) scrollFeedback.finish()
        if (::callbacks.isInitialized) callbacks.finish()
        NavigationNodeShadow.refreshBehaviors.clear()
        NavigationAudioShadow.api22WriteRecorder = null
    }

    @Test fun `recovery honors existing explicit traversal order`() {
        old.isVisibleToUser = false
        nodeShadow(last).setTraversalBefore(first)
        deliver(scroll(200))
        assertEquals(listOf(last to AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS), actions)
    }
    @Test fun `grouped children remain part of their existing focusable parent`() {
        old.isVisibleToUser = false; first.isVisibleToUser = false; last.isVisibleToUser = false
        val group = node("Group")
        val passive = node().apply { text = "Child" }
        nodeShadow(container).addChild(group); nodeShadow(group).addChild(passive); record(group)
        deliver(scroll(200))
        assertEquals(listOf(group to AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS), actions)
    }
    @Test fun `focus change during candidate refresh is not overwritten`() {
        old.isVisibleToUser = false
        val outside = node("Outside")
        nodeShadow(root).addChild(outside)
        NavigationNodeShadow.refreshBehaviors[first] = { focus(outside); true }
        deliver(scroll(200))
        assertTrue(actions.isEmpty()); assertEquals(outside, wrapper.getFocused())
    }
    @Test fun `provider failure during startup is contained`() {
        NavigationNodeShadow.refreshBehaviors[old] = { throw IllegalStateException("Gone") }
        recovery.start()
        deliver(scroll(200))
        assertTrue(actions.isEmpty())
    }
    @Test fun `valid visible current focus is preserved`() {
        deliver(scroll(200))
        assertEquals(old, service.root?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY))
        assertTrue(actions.isEmpty())
    }
    @Test fun `invisible focus recovers to first eligible visible node for forward movement`() {
        old.isVisibleToUser = false
        deliver(scroll(200))
        assertEquals(listOf(first to AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS), actions)
        assertEquals(first, wrapper.getFocused())
    }
    @Test fun `stale focus recovers without clearing or activating anything`() {
        nodeShadow(old).setRefreshReturnValue(false)
        deliver(scroll(200))
        assertEquals(listOf(first to AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS), actions)
    }
    @Test fun `missing focus uses remembered container association`() {
        old.isVisibleToUser = false
        focus(null)
        deliver(scroll(200))
        assertEquals(listOf(first to AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS), actions)
    }
    @Test fun `backward movement chooses opposite visible edge`() {
        old.isVisibleToUser = false
        deliver(scroll(0))
        assertEquals(listOf(last to AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS), actions)
    }
    @Test fun `ineligible current focus is recovered and passive blank nodes are skipped`() {
        old.text = null; old.isFocusable = false; old.className = "android.view.View"
        first.text = null; first.isFocusable = false; first.className = "android.view.View"
        deliver(scroll(200))
        assertEquals(listOf(last to AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS), actions)
    }
    @Test fun `navigation owned scroll and its inertial tail never recover manual focus`() {
        old.isVisibleToUser = false
        var retried = false
        callbacks.addCallback(object : CallbackInterceptor.Scroll(container) {
            override fun invoke() { retried = true }
        })
        deliver(scroll(200)); deliver(scroll(250))
        assertTrue(retried); assertTrue(actions.isEmpty())
        deliver(event(AccessibilityEvent.TYPE_TOUCH_INTERACTION_START))
        deliver(event(AccessibilityEvent.TYPE_TOUCH_INTERACTION_END))
        deliver(scroll(300))
        assertEquals(listOf(first to AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS), actions)
    }
    @Test fun `no eligible visible target leaves focus unchanged`() {
        listOf(old, first, last).forEach { it.isVisibleToUser = false }
        deliver(scroll(200))
        assertTrue(actions.isEmpty()); assertEquals(old, wrapper.getFocused())
    }
    @Test fun `valid focus outside scrolled container is not stolen`() {
        val outside = node("Outside")
        nodeShadow(root).addChild(outside); focus(outside)
        deliver(scroll(200))
        assertTrue(actions.isEmpty()); assertEquals(outside, wrapper.getFocused())
    }
    @Test fun `unrelated scrolling container cannot steal focus after original focus disappears`() {
        old.isVisibleToUser = false
        val other = node().apply { isScrollable = true }
        nodeShadow(root).addChild(other); nodeShadow(other).addChild(node("Other"))
        deliver(scroll(100, other)); deliver(scroll(200, other))
        assertTrue(actions.isEmpty())
    }
    @Test fun `window change cancels remembered manual recovery`() {
        old.isVisibleToUser = false
        deliver(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED))
        deliver(scroll(100)); deliver(scroll(200))
        assertTrue(actions.isEmpty())
    }
    @Test fun `active root change during target refresh cancels recovery`() {
        old.isVisibleToUser = false
        NavigationNodeShadow.refreshBehaviors[first] = { service.root = null; true }
        deliver(scroll(200))
        assertTrue(actions.isEmpty())
    }
    @Test fun `stale container does not recover focus`() {
        old.isVisibleToUser = false; nodeShadow(container).setRefreshReturnValue(false)
        deliver(scroll(200)); assertTrue(actions.isEmpty())
    }
    @Test fun `unchanged position or unknown direction cannot recover focus`() {
        old.isVisibleToUser = false
        deliver(scroll(100)); assertTrue(actions.isEmpty())
    }
    @Test fun `recovery works with audible feedback disabled`() {
        SpeechPreferences(context).audibleNavigationFeedback = false
        old.isVisibleToUser = false
        deliver(scroll(200))
        assertEquals(listOf(first to AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS), actions)
    }
    @Test fun `teardown blocks recovery and reconnect requires a new manual interaction`() {
        recovery.finish(); old.isVisibleToUser = false
        deliver(scroll(200)); assertTrue(actions.isEmpty())
        focus(first); recovery.start(); first.isVisibleToUser = false
        deliver(scroll(300)); assertTrue(actions.isEmpty())
        deliver(event(AccessibilityEvent.TYPE_TOUCH_INTERACTION_START))
        deliver(event(AccessibilityEvent.TYPE_TOUCH_INTERACTION_END)); deliver(scroll(400))
        assertEquals(listOf(last to AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS), actions)
    }
    @Test fun `recovery requests no click sound until normal focus event and duplicate event stays silent`() {
        val tracks = mutableListOf<AudioTrack>()
        val recorder = ShadowAudioTrack.OnAudioDataWrittenListener { track, _, _ -> tracks.add(track) }
        ShadowAudioTrack.addAudioDataListener(recorder)
        NavigationAudioShadow.api22WriteRecorder = { track, _ -> tracks.add(track) }
        val movement = NavigationSoundInterceptor(wrapper, NavigationSoundController(), context)
        movement.onServiceConnected()
        try {
            old.isVisibleToUser = false
            deliver(scroll(200))
            val scrollToneCount = tracks.size
            assertEquals(1, scrollToneCount)
            assertEquals(listOf(first to AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS), actions)
            val focused = event(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED, first)
            movement.handle(focused); recovery.handle(focused); movement.handle(focused)
            assertEquals(scrollToneCount + 1, tracks.size)
            deliver(scroll(300))
            assertEquals(1, actions.size)
        } finally {
            movement.finish(); ShadowAudioTrack.removeAudioDataListener(recorder)
        }
    }

    @Test fun `first horizontal page uses content baseline and retained cleared focus ancestry`() {
        prepareHorizontal()
        deliver(horizontal(1056))
        assertEquals(listOf(first to AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS), actions)
    }
    @Test fun `reverse horizontal page chooses last eligible target`() {
        prepareHorizontal()
        deliver(horizontal(0))
        assertEquals(listOf(last to AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS), actions)
    }
    @Test fun `horizontal scroll preserves persistent valid focus`() {
        interpreter.start(); deliver(horizontal(496, content = true)); deliver(horizontal(1056))
        assertTrue(actions.isEmpty()); assertEquals(old, wrapper.getFocused())
    }
    @Test fun `first horizontal position alone does not guess direction`() {
        interpreter.start(); old.isVisibleToUser = false; focus(null)
        deliver(horizontal(1056))
        assertTrue(actions.isEmpty())
    }
    @Test fun `recovery waits for touch end and quiet settling`() {
        deliver(event(AccessibilityEvent.TYPE_TOUCH_INTERACTION_START))
        prepareHorizontal()
        deliver(horizontal(1056), settle = false)
        advance(500)
        assertTrue(actions.isEmpty())
        deliver(event(AccessibilityEvent.TYPE_TOUCH_INTERACTION_END), settle = false)
        advance(100); assertTrue(actions.isEmpty())
        advance(100)
        assertEquals(listOf(first to AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS), actions)
    }
    @Test fun `latest scroll replaces pending direction and extends settling without backlog`() {
        prepareHorizontal()
        deliver(horizontal(1056), settle = false)
        advance(100); assertTrue(actions.isEmpty())
        deliver(horizontal(496), settle = false)
        advance(100); assertTrue(actions.isEmpty()) // Original deadline has passed.
        advance(100)
        assertEquals(listOf(last to AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS), actions)
        advance(1000); assertEquals(1, actions.size)
    }
    @Test fun `new user focus during settling cancels pending recovery`() {
        prepareHorizontal(); deliver(horizontal(1056), settle = false)
        val outside = node("Outside"); nodeShadow(root).addChild(outside); focus(outside)
        deliver(event(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED, outside))
        advance(500); assertTrue(actions.isEmpty()); assertEquals(outside, wrapper.getFocused())
    }
    @Test fun `new touch cancels pending recovery without resuming stale request at touch end`() {
        prepareHorizontal(); deliver(horizontal(1056), settle = false)
        deliver(event(AccessibilityEvent.TYPE_TOUCH_INTERACTION_START))
        deliver(event(AccessibilityEvent.TYPE_TOUCH_INTERACTION_END))
        advance(500); assertTrue(actions.isEmpty())
    }
    @Test fun `new touch during provider validation invalidates in flight recovery`() {
        prepareHorizontal()
        NavigationNodeShadow.refreshBehaviors[first] = {
            deliver(event(AccessibilityEvent.TYPE_TOUCH_INTERACTION_START), settle = false)
            true
        }
        deliver(horizontal(1056))
        assertTrue(actions.isEmpty())
    }
    @Test fun `window change during settling cancels request`() {
        prepareHorizontal(); deliver(horizontal(1056), settle = false)
        deliver(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)); advance(500)
        assertTrue(actions.isEmpty())
    }
    @Test fun `root replacement without a window event still rejects pending recovery`() {
        prepareHorizontal(); deliver(horizontal(1056), settle = false)
        service.root = node(); advance(500)
        assertTrue(actions.isEmpty())
    }
    @Test fun `container becoming stale during settling prevents focus action`() {
        prepareHorizontal(); deliver(horizontal(1056), settle = false)
        // The pending request retains event.getSource(), a copy. Model the provider
        // disappearing for that node identity, not only the original shadow instance.
        NavigationNodeShadow.refreshBehaviors[container] = { false }
        assertFalse(AccessibilityNodeInfo.obtain(container).refresh())
        advance(500)
        assertTrue(actions.isEmpty())
    }
    @Test fun `candidate becoming stale during final validation is not focused`() {
        prepareHorizontal()
        var refreshes = 0
        NavigationNodeShadow.refreshBehaviors[first] = { ++refreshes == 1 }
        deliver(horizontal(1056))
        assertTrue(actions.isEmpty())
    }
    @Test fun `helper registered during settling cancels manual recovery`() {
        prepareHorizontal(); deliver(horizontal(1056), settle = false)
        callbacks.addCallback(object : CallbackInterceptor.Scroll(container) { override fun invoke() = Unit })
        advance(500); assertTrue(actions.isEmpty())
    }
    @Test fun `horizontal navigation helper and its tail do not recover focus`() {
        prepareHorizontal()
        callbacks.addCallback(object : CallbackInterceptor.Scroll(container) { override fun invoke() = Unit })
        deliver(horizontal(1056)); deliver(horizontal(1600))
        assertTrue(actions.isEmpty())
    }
    @Test fun `finish cancels queued recovery`() {
        prepareHorizontal(); deliver(horizontal(1056), settle = false)
        recovery.finish(); advance(500); assertTrue(actions.isEmpty())
    }
    @Test fun `page announcement alone cannot trigger focus recovery`() {
        prepareHorizontal()
        deliver(event(AccessibilityEvent.TYPE_ANNOUNCEMENT, container).apply { text.add("Page 2 of 6") })
        advance(500); assertTrue(actions.isEmpty())
    }
}
