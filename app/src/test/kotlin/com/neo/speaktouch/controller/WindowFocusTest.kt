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

import android.os.Looper
import android.os.Build
import android.app.KeyguardManager
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.neo.speaktouch.intercepter.event.SpeechInterceptor
import com.neo.speaktouch.utils.Reader
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAccessibilityNodeInfo

@Implements(AccessibilityNodeInfo::class)
class WindowEntryNodeShadow : ShadowAccessibilityNodeInfo() {
    @RealObject private lateinit var node: AccessibilityNodeInfo
    private class Focus(var accessibility: AccessibilityNodeInfo? = null, var input: AccessibilityNodeInfo? = null,
                        var refresh: (() -> Boolean)? = null)
    private val focus get() = states.getOrPut(node) { Focus() }
    var accessibility: AccessibilityNodeInfo?
        get() = focus.accessibility
        set(value) { focus.accessibility = value }
    var input: AccessibilityNodeInfo?
        get() = focus.input
        set(value) { focus.input = value }
    var onRefresh: (() -> Boolean)?
        get() = focus.refresh
        set(value) { focus.refresh = value }
    @Implementation fun findFocus(kind: Int): AccessibilityNodeInfo? =
        if (kind == AccessibilityNodeInfo.FOCUS_ACCESSIBILITY) accessibility else input
    @Implementation override fun refresh(): Boolean = onRefresh?.invoke() ?: super.refresh()
    companion object {
        // WindowInfo.getRoot() returns node copies: keep the provider's focus state by identity.
        private val states = mutableMapOf<AccessibilityNodeInfo, Focus>()
        fun reset() { states.clear() }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [22, 28, 33], shadows = [WindowEntryNodeShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class WindowFocusTest {
    private lateinit var service: SliderTestService
    private lateinit var speech: SpeechInterceptor
    private lateinit var tts: TextToSpeech
    private lateinit var controller: WindowFocusController
    private lateinit var window: AccessibilityWindowInfo
    private lateinit var root: AccessibilityNodeInfo
    private lateinit var first: AccessibilityNodeInfo
    private lateinit var second: AccessibilityNodeInfo
    private var completeFocus = true
    private var changes: ScreenChangeController? = null
    private val actions = mutableListOf<Pair<AccessibilityNodeInfo, Int>>()
    private val context get() = RuntimeEnvironment.getApplication()
    private fun ns(node: AccessibilityNodeInfo): WindowEntryNodeShadow = Shadow.extract(node)
    private fun spoken() = shadowOf(tts).spokenTextList.toList()
    private fun drain() = shadowOf(Looper.getMainLooper()).idle()
    private fun node(label: String? = null, owner: AccessibilityWindowInfo = window) = AccessibilityNodeInfo.obtain().apply {
        className = if (label == null) "android.view.ViewGroup" else "android.widget.Button"
        text = label; packageName = "test.menu"; isVisibleToUser = true; isEnabled = true
        isFocusable = label != null; isClickable = label != null
        shadowOf(this).setAccessibilityWindowInfo(owner)
        ns(this).setRefreshReturnValue(true)
        ns(this).setOnPerformActionListener { action, _ ->
            this@WindowFocusTest.actions.add(this to action)
            if (action == AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS && completeFocus) focus(this)
            action == AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS
        }
    }
    private fun focus(node: AccessibilityNodeInfo?) {
        ns(root).accessibility?.isAccessibilityFocused = false
        ns(root).accessibility = node
        node?.isAccessibilityFocused = true
    }
    private fun event(type: Int = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                      source: AccessibilityNodeInfo = root, id: Int = source.windowId) = AccessibilityEvent.obtain(type).apply {
        shadowOf(this).setSourceNode(source); shadowOf(this).setWindowId(id)
        packageName = source.packageName
        if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED && Build.VERSION.SDK_INT >= 28) {
            AccessibilityEvent::class.java.getDeclaredMethod("setWindowChanges", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(this, AccessibilityEvent.WINDOWS_CHANGE_FOCUSED)
        }
    }
    private fun sendFocus(node: AccessibilityNodeInfo) {
        val event = event(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED, node)
        controller.onEvent(event); speech.handle(event)
    }
    @Before fun setup() {
        WindowEntryNodeShadow.reset()
        actions.clear(); completeFocus = true
        window = AccessibilityWindowInfo.obtain().apply {
            shadowOf(this).setId(2); shadowOf(this).setType(AccessibilityWindowInfo.TYPE_APPLICATION)
            shadowOf(this).setActive(true); shadowOf(this).setFocused(true)
        }
        root = node(); first = node("First option"); second = node("Second option")
        ns(root).addChild(first); ns(root).addChild(second); shadowOf(window).setRoot(root)
        service = Robolectric.buildService(SliderTestService::class.java).create().get().apply { this.root = this@WindowFocusTest.root }
        shadowOf(service).setWindows(listOf(window))
        tts = TextToSpeech(context, null)
        speech = SpeechInterceptor(SpeechController(tts, context, Reader(context)))
        controller = WindowFocusController(ServiceController(service), speech)
        controller.start()
    }
    @After fun cleanup() {
        if (::controller.isInitialized) controller.finish()
        changes?.destroy()
        if (::speech.isInitialized) speech.finish()
        drain(); WindowEntryNodeShadow.reset()
    }

    private fun popup(): Pair<AccessibilityNodeInfo, AccessibilityNodeInfo> {
        val pane = node().apply { AccessibilityNodeInfoCompat.wrap(this).paneTitle = "Short cuts" }
        val option = node("App info")
        ns(root).addChild(pane); ns(pane).addChild(option)
        return pane to option
    }
    private fun paneEvent(pane: AccessibilityNodeInfo, flags: Int = AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_APPEARED) =
        event(source = pane).apply { contentChangeTypes = flags }
    private fun dispatch(event: AccessibilityEvent) {
        changes?.observe(event); controller.onEvent(event); speech.handle(event)
    }
    private fun coordinateContext() {
        val permission = "${context.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
        shadowOf(context).grantPermissions(permission)
        shadowOf(context.getSystemService(android.content.Context.KEYGUARD_SERVICE) as KeyguardManager).apply {
            setKeyguardLocked(false); setIsDeviceLocked(false)
        }
        shadowOf(context.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager).setIsInteractive(true)
        val output = SpeechController(tts, context, Reader(context))
        changes = ScreenChangeController(ServiceController(service), output, context).also { it.start() }
        speech = SpeechInterceptor(output, changes)
        controller.finish()
        controller = WindowFocusController(ServiceController(service), speech).also { it.start() }
    }

    @Test fun `new same window pane preserves its actual focused option despite cached icon focus`() {
        focus(first); sendFocus(first)
        val before = spoken().size
        val (pane, option) = popup(); focus(option) // Provider changed focus without a focus event.
        dispatch(paneEvent(pane)); drain()
        assertTrue(actions.isEmpty()); assertEquals(option, ns(root).accessibility)
        assertEquals(before + 1, spoken().size); assertTrue(spoken().last().contains("App info"))
        dispatch(paneEvent(pane, 0)); dispatch(paneEvent(pane)); drain(); sendFocus(option)
        assertEquals(before + 1, spoken().size)
    }
    @Test fun `same window pane ignores outside focus and searches only its eligible controls`() {
        focus(first); sendFocus(first)
        val (pane, option) = popup()
        dispatch(paneEvent(pane)); drain()
        assertEquals(listOf(option to AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS), actions)
        assertEquals(option, ns(root).accessibility)
    }
    @Test fun `same window pane without eligible controls does not focus underlying content`() {
        focus(first); sendFocus(first)
        val (pane, option) = popup(); option.isVisibleToUser = false
        dispatch(paneEvent(pane)); drain()
        assertTrue(actions.isEmpty()); assertEquals(first, ns(root).accessibility)
    }
    @Test fun `pane context survives ordinary window events and composes once with existing focus`() {
        coordinateContext(); focus(first); sendFocus(first)
        val before = spoken().size
        val (pane, option) = popup(); focus(option)
        dispatch(paneEvent(pane)); dispatch(paneEvent(pane, 0)); drain()
        assertEquals(before, spoken().size)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(250))
        assertEquals(before + 1, spoken().size)
        assertTrue(spoken().last().contains("Short cuts")); assertTrue(spoken().last().contains("App info"))
        assertFalse(spoken().last().contains("First option"))
        dispatch(paneEvent(pane)); sendFocus(option)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        assertEquals(before + 1, spoken().size); assertTrue(actions.isEmpty())
    }
    @Test fun `delayed pane focus event composes context without duplicate focus or speech`() {
        coordinateContext(); focus(first); sendFocus(first)
        val before = spoken().size
        val (pane, option) = popup(); completeFocus = false
        dispatch(paneEvent(pane)); drain()
        assertEquals(listOf(option to AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS), actions)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(80))
        focus(option); dispatch(event(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED, option)); drain()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(250))
        assertEquals(before + 1, spoken().size)
        assertTrue(spoken().last().contains("Short cuts")); assertTrue(spoken().last().contains("App info"))
        assertEquals(1, actions.size)
    }
    @Test fun `pane disappearance cancels queued focus and reopening is a new entry`() {
        focus(first); sendFocus(first)
        val (pane, option) = popup()
        dispatch(paneEvent(pane))
        val removed = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
            packageName = root.packageName; contentChangeTypes = AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_DISAPPEARED
            text.add("Short cuts"); shadowOf(this).setWindowId(root.windowId)
        }
        dispatch(removed); drain()
        assertTrue(actions.isEmpty())
        dispatch(paneEvent(pane)); drain()
        assertEquals(listOf(option to AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS), actions)
    }
    @Test fun `touch exploration keeps priority within a newly appeared pane`() {
        focus(first); sendFocus(first)
        val (pane, option) = popup()
        dispatch(paneEvent(pane))
        dispatch(event(AccessibilityEvent.TYPE_VIEW_HOVER_ENTER, option))
        dispatch(paneEvent(pane, 0)); drain()
        assertTrue(actions.isEmpty())
    }
    @Test fun `pane removed before initialization cannot redirect focus to underlying screen`() {
        val (pane, _) = popup()
        dispatch(paneEvent(pane)); pane.isVisibleToUser = false; drain()
        assertTrue(actions.isEmpty()); assertTrue(spoken().isEmpty())
    }
    @Test fun `different panes with the same title each receive one initial composed announcement`() {
        coordinateContext(); focus(first); sendFocus(first)
        val before = spoken().size
        repeat(2) {
            val (pane, option) = popup(); focus(option)
            dispatch(paneEvent(pane)); dispatch(paneEvent(pane, 0)); drain()
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(250))
            assertTrue(spoken().last().contains("Short cuts")); assertTrue(spoken().last().contains("App info"))
        }
        assertEquals(before + 2, spoken().size); assertTrue(actions.isEmpty())
    }

    @Test fun `menu without accessibility focus requests first option and real focus event speaks it`() {
        controller.onEvent(event()); drain()
        assertEquals(listOf(first to AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS), actions)
        assertTrue(spoken().isEmpty())
        sendFocus(first); drain()
        assertEquals(1, spoken().size); assertTrue(spoken().single().contains("First option"))
    }
    @Test fun `input highlighted option takes precedence over first traversal option`() {
        ns(root).input = second; second.isFocused = true
        controller.onEvent(event()); drain()
        assertEquals(listOf(second to AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS), actions)
        assertFalse(actions.any { it.second == AccessibilityNodeInfo.ACTION_CLICK })
    }
    @Test fun `empty passive decor focus does not hide the first usable menu option`() {
        focus(root)
        controller.onEvent(event()); drain()
        assertEquals(listOf(first to AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS), actions)
    }
    @Test fun `existing accessibility focus is preserved and announced once when no event was supplied`() {
        focus(second)
        controller.onEvent(event()); drain()
        assertTrue(actions.isEmpty()); assertEquals(1, spoken().size)
        assertTrue(spoken().single().contains("Second option"))
        controller.onEvent(event()); controller.onEvent(event(AccessibilityEvent.TYPE_WINDOWS_CHANGED)); drain()
        sendFocus(second)
        assertEquals(1, spoken().size); assertTrue(actions.isEmpty())
    }
    @Test fun `normal focus speech already delivered is not repeated at window entry`() {
        focus(second); sendFocus(second)
        controller.onEvent(event()); drain()
        assertEquals(1, spoken().size); assertTrue(actions.isEmpty())
    }
    @Test fun `exploring the same option after initial announcement still reads normally`() {
        focus(second); controller.onEvent(event()); drain()
        assertEquals(1, spoken().size)
        val touch = event(AccessibilityEvent.TYPE_TOUCH_INTERACTION_START)
        controller.onEvent(touch); speech.handle(touch)
        sendFocus(second)
        assertEquals(2, spoken().size); assertTrue(actions.isEmpty())
    }
    @Test fun `same window page change does not compete with manual focus recovery`() {
        focus(second); sendFocus(second)
        focus(null)
        controller.onEvent(event()); drain()
        assertTrue(actions.isEmpty()); assertEquals(1, spoken().size)
    }
    @Test fun `delayed accepted focus request is not repeated by window and content events`() {
        completeFocus = false
        controller.onEvent(event()); drain()
        repeat(3) {
            controller.onEvent(event()); controller.onEvent(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)); drain()
        }
        assertEquals(1, actions.size); assertTrue(spoken().isEmpty())
        focus(first); sendFocus(first); drain()
        assertEquals(1, spoken().size); assertEquals(1, actions.size)
    }
    @Test fun `focus supplied during validation wins over automatic first option`() {
        ns(first).onRefresh = { focus(second); true }
        controller.onEvent(event()); drain()
        assertTrue(actions.isEmpty())
        assertEquals(1, spoken().size); assertTrue(spoken().single().contains("Second option"))
    }
    @Test fun `removed window cancels queued initialization`() {
        controller.onEvent(event())
        val removed = event(AccessibilityEvent.TYPE_WINDOWS_CHANGED)
        if (Build.VERSION.SDK_INT >= 28) {
            AccessibilityEvent::class.java.getDeclaredMethod("setWindowChanges", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(removed, AccessibilityEvent.WINDOWS_CHANGE_REMOVED)
        }
        shadowOf(service).setWindows(emptyList()); service.root = null
        controller.onEvent(removed); drain()
        assertTrue(actions.isEmpty()); assertTrue(spoken().isEmpty())
    }
    @Test fun `window state can precede active root and focused window readiness`() {
        val oldWindow = AccessibilityWindowInfo.obtain().apply {
            shadowOf(this).setId(1); shadowOf(this).setType(AccessibilityWindowInfo.TYPE_APPLICATION)
            shadowOf(this).setActive(true); shadowOf(this).setFocused(true)
        }
        val oldRoot = node(owner = oldWindow); val icon = node("Original icon", oldWindow)
        ns(oldRoot).addChild(icon); ns(oldRoot).accessibility = icon; icon.isAccessibilityFocused = true
        shadowOf(oldWindow).setRoot(oldRoot)
        service.root = oldRoot
        shadowOf(window).setActive(false); shadowOf(window).setFocused(false)
        shadowOf(service).setWindows(listOf(oldWindow, window))
        controller.onEvent(event()); drain()
        assertTrue(actions.isEmpty())
        shadowOf(oldWindow).setFocused(false); shadowOf(window).setFocused(true)
        controller.onEvent(event(AccessibilityEvent.TYPE_WINDOWS_CHANGED)); drain()
        assertEquals(listOf(first to AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS), actions)
        assertFalse(actions.any { it.first == icon })
    }
    @Test fun `content readiness retries only until the menu has an eligible option`() {
        first.isVisibleToUser = false; second.isVisibleToUser = false
        controller.onEvent(event()); drain(); assertTrue(actions.isEmpty())
        first.isVisibleToUser = true
        controller.onEvent(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)); drain()
        assertEquals(listOf(first to AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS), actions)
    }
    @Test fun `touch exploration cancels automatic focus even when more window events follow`() {
        controller.onEvent(event())
        controller.onEvent(event(AccessibilityEvent.TYPE_VIEW_HOVER_ENTER, second))
        controller.onEvent(event(AccessibilityEvent.TYPE_WINDOWS_CHANGED)); drain()
        assertTrue(actions.isEmpty()); assertTrue(spoken().isEmpty())
    }
    @Test fun `user touch and navigation cancel pending initialization`() {
        controller.onEvent(event())
        controller.onEvent(event(AccessibilityEvent.TYPE_TOUCH_INTERACTION_START))
        drain(); assertTrue(actions.isEmpty())
        controller.onEvent(event())
        controller.onEvent(event(AccessibilityEvent.TYPE_GESTURE_DETECTION_START))
        drain(); assertTrue(actions.isEmpty())
    }
    @Test fun `stale root and out of window input focus do not receive actions`() {
        ns(root).setRefreshReturnValue(false)
        controller.onEvent(event()); drain(); assertTrue(actions.isEmpty())
        ns(root).setRefreshReturnValue(true)
        val unrelated = node("Other field")
        ns(root).input = unrelated // Not a member of this window tree.
        controller.onEvent(event()); drain()
        assertEquals(listOf(first to AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS), actions)
    }
    @Test fun `overlay and IME windows do not receive automatic app focus`() {
        for (kind in listOf(AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY, AccessibilityWindowInfo.TYPE_INPUT_METHOD)) {
            shadowOf(window).setType(kind)
            controller.onEvent(event()); drain()
        }
        assertTrue(actions.isEmpty()); assertTrue(spoken().isEmpty())
    }
    @Test fun `controller teardown cancels pending focus and reconnect starts cleanly`() {
        controller.onEvent(event()); controller.finish(); drain()
        assertTrue(actions.isEmpty())
        controller.start(); controller.onEvent(event()); drain()
        assertEquals(1, actions.size)
    }
    @Test fun `SpeakTouch menu and dismissal guards prevent automatic initialization`() {
        controller.onEvent(event(), allowed = false); drain()
        assertTrue(actions.isEmpty()); assertTrue(spoken().isEmpty())
    }
}
