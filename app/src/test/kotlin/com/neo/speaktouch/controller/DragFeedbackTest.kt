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

import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.neo.speaktouch.model.Text
import com.neo.speaktouch.utils.Reader
import java.time.Duration
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
@Config(sdk = [22, 28, 33], shadows = [NavigationNodeShadow::class, PauseSpeechShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class DragFeedbackTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var service: SliderTestService
    private lateinit var speech: SpeechController
    private lateinit var feedback: DragFeedbackController
    private lateinit var tts: TextToSpeech
    private lateinit var window: AccessibilityWindowInfo
    private lateinit var root: AccessibilityNodeInfo
    private lateinit var destination: AccessibilityNodeInfo
    private var dragging = true
    private var actions = 0
    private val engine: PauseSpeechShadow get() = Shadow.extract(tts)
    private fun output() = engine.submissions.map { it.text }
    private fun ns(node: AccessibilityNodeInfo): NavigationNodeShadow = Shadow.extract(node)
    private fun advance() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
    private fun node() = AccessibilityNodeInfo.obtain().apply {
        packageName = "test.drag"; className = "android.widget.FrameLayout"
        isVisibleToUser = true; isEnabled = true
        shadowOf(this).setAccessibilityWindowInfo(this@DragFeedbackTest.window)
        ns(this).setRefreshReturnValue(true)
        ns(this).setOnPerformActionListener { _, _ -> this@DragFeedbackTest.actions++; false }
    }
    private fun event(type: Int = AccessibilityEvent.TYPE_VIEW_SELECTED, source: AccessibilityNodeInfo = destination) =
        AccessibilityEvent.obtain(type).apply {
            eventTime = SystemClock.uptimeMillis(); packageName = source.packageName; className = source.className
            shadowOf(this).setSourceNode(source); shadowOf(this).setWindowId(source.windowId)
        }
    private fun selected(value: String, time: Long = SystemClock.uptimeMillis()) {
        destination.contentDescription = value
        feedback.handle(event().apply { contentDescription = value; eventTime = time })
    }
    private fun begin() = feedback.begin(0) { dragging }.also {
        assertTrue("Valid fixture must establish drag ownership before testing feedback", it)
    }

    @Before fun setup() {
        NavigationNodeShadow.refreshBehaviors.clear(); dragging = true; actions = 0
        window = AccessibilityWindowInfo.obtain().apply {
            shadowOf(this).setId(7); shadowOf(this).setType(AccessibilityWindowInfo.TYPE_APPLICATION)
            shadowOf(this).setActive(true)
            if (Build.VERSION.SDK_INT >= 30) shadowOf(this).setDisplayId(0)
        }
        root = node(); destination = node(); ns(root).addChild(destination); shadowOf(window).setRoot(root)
        service = SliderTestService().apply { this.root = this@DragFeedbackTest.root }
        tts = TextToSpeech(context, null); speech = SpeechController(tts, context, Reader(context))
        feedback = DragFeedbackController(ServiceController(service), speech)
        assertNotNull("Fixture nodes must belong to the configured application window", root.window)
        assertEquals(7, root.windowId)
        assertEquals(AccessibilityWindowInfo.TYPE_APPLICATION, root.window?.type)
    }
    @After fun cleanup() {
        if (::feedback.isInitialized) feedback.finish()
        if (::speech.isInitialized) speech.shutdown()
        NavigationNodeShadow.refreshBehaviors.clear()
    }

    @Test fun `ordinary selection without an explicit drag is silent`() {
        selected("Unrelated item"); assertTrue(output().isEmpty()); assertEquals(0, actions)
    }
    @Test fun `selected destination speaks provider description without focus selected flag or actions`() {
        assertTrue(begin()); assertFalse(destination.isSelected); assertFalse(destination.isAccessibilityFocused)
        selected("Créer un dossier avec Café 👋")
        assertEquals(listOf("Créer un dossier avec Café 👋"), output()); assertEquals(0, actions)
        assertNull(ServiceController(service).getFocused())
    }
    @Test fun `handoff must have entered framework dragging or delegation before feedback`() {
        dragging = false; begin(); selected("Before delegation"); assertTrue(output().isEmpty())
        dragging = true; feedback.onInteractionStateChanged(0, 4); selected("Destination")
        assertEquals(listOf("Destination"), output())
    }
    @Test fun `consecutive duplicates and matching content changes do not repeat speech`() {
        begin(); selected("Destination A"); selected("Destination A")
        feedback.handle(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply { contentChangeTypes = 4 })
        feedback.handle(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply { contentChangeTypes = 64 })
        assertEquals(listOf("Destination A"), output())
    }
    @Test fun `leaving and returning to a destination announces it again`() {
        begin(); selected("A"); selected("B"); selected("A")
        assertEquals(listOf("A", "B", "A"), output())
    }
    @Test fun `rapid destinations replace speech with no delayed backlog`() {
        begin(); repeat(12) { selected("Destination $it") }
        assertEquals(12, output().size); assertEquals("Destination 11", output().last())
        assertTrue(engine.submissions.all { it.mode == TextToSpeech.QUEUE_FLUSH })
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertEquals(12, output().size)
    }
    @Test fun `framework clear ends feedback and stops only the active destination`() {
        begin(); selected("A"); val stops = engine.stops
        feedback.onInteractionStateChanged(0, 0); selected("B")
        assertEquals(listOf("A"), output()); assertEquals(stops + 1, engine.stops)
    }
    @Test fun `touch end new touch and new gesture expire the drag context`() {
        for (type in listOf(AccessibilityEvent.TYPE_TOUCH_INTERACTION_END,
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START, AccessibilityEvent.TYPE_GESTURE_DETECTION_START)) {
            begin(); feedback.handle(event(type)); selected("Stale")
        }
        assertTrue(output().isEmpty())
    }
    @Test fun `drag dropped and cancelled content flags end context without speaking content`() {
        for (flag in listOf(256, 512)) {
            begin(); feedback.handle(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply { contentChangeTypes = flag })
            selected("Late destination")
        }
        assertTrue(output().isEmpty())
    }
    @Test fun `source description is used when selection event has no description`() {
        begin(); destination.contentDescription = "Provider destination"
        feedback.handle(event()); assertEquals(listOf("Provider destination"), output())
    }
    @Test fun `old and out of order selections cannot replace a newer destination`() {
        begin(); val earlier = SystemClock.uptimeMillis(); advance(); selected("New")
        selected("Old", earlier); assertEquals(listOf("New"), output())
    }
    @Test fun `expired old drag events cannot speak in a new drag session`() {
        begin(); val old = SystemClock.uptimeMillis(); feedback.finish(); advance(); begin()
        selected("Old", old); selected("Current")
        assertEquals(listOf("Current"), output())
    }
    @Test fun `changed root or window invalidates pending drag ownership`() {
        begin(); service.root = node(); feedback.handle(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED))
        service.root = root; selected("Old root"); assertTrue(output().isEmpty())
        begin(); shadowOf(window).setId(8); selected("Old window"); assertTrue(output().isEmpty())
    }
    @Test fun `same window state changes do not end a continuing framework drag`() {
        begin(); feedback.handle(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, root))
        selected("Destination"); assertEquals(listOf("Destination"), output())
    }
    @Test fun `missing source unrelated source window and package selections are ignored`() {
        begin(); feedback.handle(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_SELECTED))
        val unrelated = node().apply { contentDescription = "Unrelated" }
        feedback.handle(event(source = unrelated).apply { contentDescription = "Unrelated" })
        feedback.handle(event().apply { shadowOf(this).setWindowId(99) })
        feedback.handle(event().apply { packageName = "other.app" })
        assertTrue(output().isEmpty())
    }
    @Test fun `stale invisible disabled and sensitive destinations never speak`() {
        begin(); ns(destination).setRefreshReturnValue(false); selected("Stale")
        ns(destination).setRefreshReturnValue(true); destination.isVisibleToUser = false; selected("Hidden")
        destination.isVisibleToUser = true; destination.isEnabled = false; selected("Disabled")
        destination.isEnabled = true; destination.isPassword = true; selected("Password")
        destination.isPassword = false; destination.isEditable = true; selected("Editing")
        destination.isEditable = false
        AccessibilityNodeInfoCompat.wrap(destination).isAccessibilityDataSensitive = true; selected("Sensitive")
        assertTrue(output().isEmpty())
    }
    @Test fun `source refresh exceptions are contained`() {
        begin(); NavigationNodeShadow.refreshBehaviors[destination] = { throw IllegalStateException("Removed") }
        selected("Stale"); NavigationNodeShadow.refreshBehaviors.clear(); selected("Late")
        assertTrue(output().isEmpty())
    }
    @Test fun `updated provider description rejects an older event snapshot`() {
        begin(); destination.contentDescription = "Current"
        feedback.handle(event().apply { contentDescription = "Old" })
        assertTrue(output().isEmpty()); selected("Current"); assertEquals(listOf("Current"), output())
    }
    @Test fun `empty descriptions and stale focus events do not synthesize destinations`() {
        begin(); feedback.handle(event()); selected("  ")
        feedback.handle(event(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED))
        assertTrue(output().isEmpty()); assertEquals(0, actions)
    }
    @Test fun `ended framework state is checked even if callback has not arrived`() {
        begin(); selected("A"); dragging = false; selected("Late")
        assertEquals(listOf("A"), output())
    }
    @Test fun `cleanup never interrupts newer ordinary speech`() {
        begin(); selected("A"); speech.speak(Text("Short cuts, App info")); val stops = engine.stops
        feedback.finish(); feedback.finish(); selected("Late")
        assertEquals(stops, engine.stops); assertEquals(listOf("A", "Short cuts, App info"), output())
    }
    @Test fun `unavailable roots and other display callbacks cannot create false feedback`() {
        service.root = null; assertFalse(feedback.begin(0) { dragging }); selected("Invalid"); assertTrue(output().isEmpty())
        service.root = root; assertTrue(begin()); feedback.onInteractionStateChanged(1, 0); selected("Valid")
        assertEquals(listOf("Valid"), output())
    }
}
