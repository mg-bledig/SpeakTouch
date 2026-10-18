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

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.neo.speaktouch.intercepter.event.CallbackInterceptor
import com.neo.speaktouch.intercepter.event.SpeechInterceptor
import com.neo.speaktouch.model.Text
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
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [22, 28, 31], shadows = [NavigationNodeShadow::class, PauseServiceShadow::class, PauseSpeechShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class PageFocusSpeechTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var service: SliderTestService
    private lateinit var window: AccessibilityWindowInfo
    private lateinit var root: AccessibilityNodeInfo
    private lateinit var container: AccessibilityNodeInfo
    private lateinit var old: AccessibilityNodeInfo
    private lateinit var camera: AccessibilityNodeInfo
    private lateinit var ezviz: AccessibilityNodeInfo
    private lateinit var tts: TextToSpeech
    private lateinit var speech: SpeechController
    private lateinit var changes: ScreenChangeController
    private lateinit var interpreter: ManualScrollInterpreter
    private lateinit var recovery: ManualScrollFocusController
    private lateinit var callbacks: CallbackInterceptor
    private lateinit var interceptor: SpeechInterceptor
    private val requests = mutableListOf<AccessibilityNodeInfo>()
    private var actionAccepted = true
    private var time = 1L
    private fun nodeShadow(node: AccessibilityNodeInfo): NavigationNodeShadow = Shadow.extract(node)
    private fun spoken() = shadowOf(tts).spokenTextList.toList()
    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun node(label: String? = null) = AccessibilityNodeInfo.obtain().apply {
        packageName = "test.home"; className = if (label == null) "android.view.View" else "android.widget.TextView"
        text = label; isVisibleToUser = true; isEnabled = true; isFocusable = label != null
        shadowOf(this).setAccessibilityWindowInfo(this@PageFocusSpeechTest.window)
        nodeShadow(this).setRefreshReturnValue(true)
        nodeShadow(this).setOnPerformActionListener { action, _ ->
            assertEquals(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, action)
            requests.add(this); actionAccepted
        }
    }
    private fun setFocus(node: AccessibilityNodeInfo?) {
        nodeShadow(root).focused?.isAccessibilityFocused = false
        node?.isAccessibilityFocused = true
        nodeShadow(root).focused = node
    }
    private fun event(type: Int, source: AccessibilityNodeInfo? = null) = AccessibilityEvent.obtain(type).apply {
        eventTime = time++; packageName = "test.home"
        shadowOf(this).setWindowId(window.id)
        source?.let { shadowOf(this).setSourceNode(it) }
    }
    private fun dispatch(event: AccessibilityEvent) {
        // Same order as service observation and the relevant production interceptors.
        changes.observe(event); interpreter.handle(event); recovery.handle(event)
        interceptor.handle(event); callbacks.handle(event)
    }
    private fun page(label: String = "Page 2 of 6") = dispatch(
        event(AccessibilityEvent.TYPE_ANNOUNCEMENT).apply { className = "android.widget.ScrollView"; text.add(label) })
    private fun scroll(x: Int) = dispatch(event(AccessibilityEvent.TYPE_VIEW_SCROLLED, container).apply {
        scrollX = x; scrollY = 0; maxScrollX = 6000; isScrollable = true
    })
    private fun gainFocus(node: AccessibilityNodeInfo) {
        setFocus(node); dispatch(event(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED, node))
    }
    private fun loseFocus() {
        old.isVisibleToUser = false; setFocus(null)
        dispatch(event(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED, old))
    }
    private fun transition(label: String = "Page 2 of 6", x: Int = 1056) {
        loseFocus(); page(label); advance(80); scroll(x)
    }
    @Before fun setup() {
        NavigationNodeShadow.refreshBehaviors.clear()
        shadowOf(context).grantPermissions("${context.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        shadowOf(context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).apply {
            setKeyguardLocked(false); setIsDeviceLocked(false)
        }
        shadowOf(context.getSystemService(Context.POWER_SERVICE) as PowerManager).setIsInteractive(true)
        shadowOf(context.packageManager).addResolveInfoForIntent(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), ResolveInfo().apply {
                isDefault = true
                activityInfo = ActivityInfo().apply { packageName = "test.home"; name = "test.home.Home"; enabled = true; exported = true }
            })
        service = Robolectric.buildService(SliderTestService::class.java).create().get()
        window = AccessibilityWindowInfo.obtain().apply {
            shadowOf(this).setId(7); shadowOf(this).setType(AccessibilityWindowInfo.TYPE_APPLICATION); shadowOf(this).setActive(true)
        }
        root = node(); container = node().apply { className = "android.widget.ScrollView"; isScrollable = true }
        old = node("Old"); camera = node("Camera"); ezviz = node("EZVIZ")
        nodeShadow(root).addChild(container)
        listOf(old, camera, ezviz).forEach { nodeShadow(container).addChild(it) }
        shadowOf(window).setRoot(root); service.root = root; shadowOf(service).setWindows(listOf(window))
        // Fail at fixture setup if nodes are not attached to the active service window.
        listOf(root, container, old, camera, ezviz).forEach { assertEquals(window.id, it.windowId) }
        assertEquals(AccessibilityWindowInfo.TYPE_APPLICATION, root.window?.type)
        callbacks = CallbackInterceptor()
        interpreter = ManualScrollInterpreter(ServiceController(service), callbacks)
        recovery = ManualScrollFocusController(ServiceController(service), interpreter)
        tts = TextToSpeech(context, null); speech = SpeechController(tts, context, Reader(context))
        changes = ScreenChangeController(ServiceController(service), speech, context, recovery)
        interceptor = SpeechInterceptor(speech, changes)
        setFocus(old); recovery.start(); changes.start()
        dispatch(event(AccessibilityEvent.TYPE_TOUCH_INTERACTION_START))
        dispatch(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, container).apply {
            scrollX = 496; scrollY = 0; isScrollable = true
        })
        dispatch(event(AccessibilityEvent.TYPE_TOUCH_INTERACTION_END))
    }
    @After fun cleanup() {
        if (::changes.isInitialized) changes.destroy()
        if (::recovery.isInitialized) recovery.finish()
        if (::callbacks.isInitialized) callbacks.finish()
        if (::speech.isInitialized) speech.shutdown()
        NavigationNodeShadow.refreshBehaviors.clear()
    }
    @Test fun `page waits past its old deadline then matching genuine focus produces one combined utterance`() {
        transition(); advance(180) // Past both the old page deadline and recovery settling.
        assertEquals(listOf(camera), requests); assertTrue(spoken().isEmpty())
        gainFocus(camera)
        assertEquals(listOf("Page 2 of 6, Camera"), spoken())
        advance(1000); assertEquals(1, spoken().size)
        assertEquals(TextToSpeech.QUEUE_FLUSH, shadowOf(tts).queueMode)
    }
    @Test fun `reverse transition composes with its own recovered target`() {
        transition("Page 1 of 6", 0); advance(180); gainFocus(ezviz)
        assertEquals(listOf(ezviz), requests)
        assertEquals(listOf("Page 1 of 6, EZVIZ"), spoken())
    }
    @Test fun `persistent valid focus gives page only without repeating the item`() {
        page(); advance(80); scroll(1056); advance(300)
        assertTrue(requests.isEmpty()); assertEquals(listOf("Page 2 of 6"), spoken())
    }
    @Test fun `page without a recovery remains page only`() {
        page(); advance(300)
        assertEquals(listOf("Page 2 of 6"), spoken()); assertTrue(requests.isEmpty())
    }
    @Test fun `rejected recovery action still permits page only`() {
        actionAccepted = false; transition(); advance(500)
        assertEquals(listOf("Page 2 of 6"), spoken())
    }
    @Test fun `accepted action without focus event falls back to page only once`() {
        transition(); advance(800)
        assertEquals(listOf("Page 2 of 6"), spoken()); advance(1000); assertEquals(1, spoken().size)
    }
    @Test fun `late provider announcement combines with held normal recovery focus`() {
        loseFocus(); scroll(1056); advance(180); gainFocus(camera)
        assertTrue(spoken().isEmpty()); advance(50); page()
        assertEquals(listOf("Page 2 of 6, Camera"), spoken()); advance(1000); assertEquals(1, spoken().size)
    }
    @Test fun `no provider page after recovered focus speaks the item once`() {
        loseFocus(); scroll(1056); advance(180); gainFocus(camera); advance(300)
        assertEquals(listOf("Camera"), spoken())
    }
    @Test fun `slow genuine focus event still speaks once when its page wait expires`() {
        loseFocus(); scroll(1056); advance(600); gainFocus(camera); advance(300)
        assertEquals(listOf("Camera"), spoken())
    }
    @Test fun `duplicate matching focus events cannot repeat combined speech`() {
        transition(); advance(180); gainFocus(camera); gainFocus(camera); advance(1000)
        assertEquals(listOf("Page 2 of 6, Camera"), spoken())
    }
    @Test fun `unrelated focus within same container does not acquire page prefix`() {
        transition(); advance(180); gainFocus(ezviz); advance(1000)
        assertEquals(listOf("EZVIZ"), spoken())
    }
    @Test fun `unrelated focus before recovery cancels page and pending action`() {
        transition(); gainFocus(ezviz); advance(1000)
        assertTrue(requests.isEmpty()); assertEquals(listOf("EZVIZ"), spoken())
    }
    @Test fun `new touch clears stale page even if old focus event arrives later`() {
        transition(); advance(180)
        dispatch(event(AccessibilityEvent.TYPE_TOUCH_INTERACTION_START))
        gainFocus(camera); advance(1000)
        assertEquals(listOf("Camera"), spoken())
    }
    @Test fun `navigation command cancels old page context without changing gesture mappings`() {
        transition(); advance(180); changes.cancelPageFeedback()
        gainFocus(camera); advance(1000)
        assertEquals(listOf("Camera"), spoken())
    }
    @Test fun `window change prevents late target from using previous page`() {
        transition(); advance(180)
        dispatch(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED))
        gainFocus(camera); advance(1000)
        assertEquals(listOf("Camera"), spoken())
    }
    @Test fun `replacement root prevents stale combined announcement`() {
        transition(); advance(180); service.root = node(); advance(1000)
        assertTrue(spoken().isEmpty())
    }
    @Test fun `new speech invalidates pending page generation`() {
        transition(); advance(180); speech.speak(Text("Screen curtain off"))
        gainFocus(camera); advance(1000)
        assertEquals(listOf("Screen curtain off", "Camera"), spoken())
    }
    @Test fun `rapid page transitions retain only latest context and final focus`() {
        transition(); advance(40); page("Page 3 of 6"); scroll(2113)
        advance(40); page("Page 4 of 6"); scroll(3170); advance(180)
        gainFocus(camera); advance(1000)
        assertEquals(listOf("Page 4 of 6, Camera"), spoken()); assertEquals(1, requests.size)
    }
    @Test fun `duplicate provider page after composition stays silent`() {
        transition(); advance(180); gainFocus(camera); page(); advance(1000)
        assertEquals(listOf("Page 2 of 6, Camera"), spoken())
    }
    @Test fun `next page cannot reuse a target already announced for previous transition`() {
        transition(); advance(180); gainFocus(camera)
        page("Page 3 of 6"); advance(300)
        assertEquals(listOf("Page 2 of 6, Camera", "Page 3 of 6"), spoken())
    }
    @Test fun `vertical recovery speech is not held for a nonexistent horizontal page`() {
        loseFocus()
        dispatch(event(AccessibilityEvent.TYPE_VIEW_SCROLLED, container).apply { scrollX = 496; scrollY = 100; maxScrollY = 1000; isScrollable = true })
        advance(180); gainFocus(camera)
        assertEquals(listOf("Camera"), spoken())
    }
    @Test fun `stale recovered node cannot contribute an icon to page speech`() {
        transition(); advance(180)
        nodeShadow(camera).setRefreshReturnValue(false)
        setFocus(camera)
        // Validate through the normal hook without asking Reader to read a deliberately stale provider.
        assertFalse(changes.onFocused(androidx.core.view.accessibility.AccessibilityNodeInfoCompat.wrap(camera)))
        advance(1000); assertTrue(spoken().isEmpty())
    }
    @Test fun `refresh exception rejects page composition without breaking event handling`() {
        transition(); advance(180); setFocus(camera)
        NavigationNodeShadow.refreshBehaviors[camera] = { throw IllegalStateException("Provider gone") }
        assertFalse(changes.onFocused(androidx.core.view.accessibility.AccessibilityNodeInfoCompat.wrap(camera)))
        advance(1000); assertTrue(spoken().isEmpty())
    }
    @Test fun `destroy clears both pending context and held recovery speech`() {
        loseFocus(); scroll(1056); advance(180); gainFocus(camera)
        changes.destroy(); advance(1000); assertTrue(spoken().isEmpty())
    }
}
