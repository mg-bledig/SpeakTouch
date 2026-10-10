package com.neo.speaktouch.controller

import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.neo.speaktouch.intercepter.event.ProgressInterceptor
import com.neo.speaktouch.intercepter.event.SpeechInterceptor
import com.neo.speaktouch.model.Text
import com.neo.speaktouch.utils.Reader
import java.time.Duration
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
@Config(sdk = [28, 31], shadows = [SliderNodeShadow::class, RefocusSpeechShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class ProgressUpdatesTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var root: AccessibilityNodeInfoCompat
    private lateinit var bar: AccessibilityNodeInfoCompat
    private lateinit var service: SliderTestService
    private lateinit var speech: SpeechController
    private lateinit var tts: TextToSpeech
    private lateinit var updates: ProgressInterceptor
    private fun shadow(node: AccessibilityNodeInfoCompat): SliderNodeShadow = Shadow.extract(node.unwrap())
    private fun node(windowId: Int = 1) = AccessibilityNodeInfoCompat.wrap(AccessibilityNodeInfo.obtain()).apply {
        className = "android.widget.ProgressBar"; isVisibleToUser = true; isAccessibilityFocused = true
        val window = AccessibilityWindowInfo.obtain()
        shadowOf(window).setId(windowId)
        shadowOf(unwrap()).setAccessibilityWindowInfo(window)
    }
    private fun event(type: Int = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                      mask: Int = AccessibilityEvent.CONTENT_CHANGE_TYPE_STATE_DESCRIPTION,
                      source: AccessibilityNodeInfoCompat? = bar) = AccessibilityEvent.obtain(type).apply {
        contentChangeTypes = mask
        if (source != null) shadowOf(this).setSourceNode(source.unwrap())
    }
    private fun change(value: Int, text: String = "$value%") {
        bar.rangeInfo = AccessibilityNodeInfoCompat.RangeInfoCompat.obtain(0, 0f, 200f, value * 2f)
        bar.stateDescription = text
        updates.handle(event())
    }
    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun spoken() = shadowOf(tts).spokenTextList.toList()
    private fun focus() = updates.handle(event(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED))
    @Before fun setup() {
        root = node().apply { className = "android.view.View"; isAccessibilityFocused = false }
        bar = node().apply { contentDescription = "Download progress" }
        bar.rangeInfo = AccessibilityNodeInfoCompat.RangeInfoCompat.obtain(0, 0f, 200f, 80f)
        bar.stateDescription = "40%"
        shadow(root).addChild(bar.unwrap()); shadow(root).focused = bar.unwrap()
        service = SliderTestService().apply { this.root = this@ProgressUpdatesTest.root.unwrap() }
        tts = TextToSpeech(context, null)
        speech = SpeechController(tts, context, Reader(context))
        updates = ProgressInterceptor(ServiceController(service), speech, context)
    }
    @Test fun `focused native progress update speaks state only`() {
        change(50)
        assertEquals(listOf("50%"), spoken())
        assertEquals(bar.unwrap(), shadow(root).focused)
        assertTrue(shadow(bar).performedActions.isEmpty())
    }
    @Test fun `focus announcement seeds duplicate suppression and initial throttle`() {
        val interceptor = SpeechInterceptor(speech)
        val event = event(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
        interceptor.handle(event); updates.handle(event)
        change(40); change(50)
        assertEquals(listOf("Download progress, progress bar, 40%"), spoken())
        advance(4999); assertEquals(1, spoken().size)
        advance(1); assertEquals("50%", spoken().last())
    }
    @Test fun `unfocused source is ignored even when it has a progress range`() {
        bar.isAccessibilityFocused = false; change(50)
        assertTrue(spoken().isEmpty())
    }
    @Test fun `source focus flag alone cannot speak for a different actual focused node`() {
        shadow(root).focused = node().unwrap(); change(50)
        assertTrue(spoken().isEmpty())
    }
    @Test fun `sliders and generic controls are ignored`() {
        for (name in listOf("android.widget.SeekBar", "android.view.View", "android.widget.Button")) {
            bar.className = name; change(50)
        }
        assertTrue(spoken().isEmpty())
    }
    @Test fun `text subtree selection and announcement events do not drive speech`() {
        for (mask in listOf(0, AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT,
            AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE, AccessibilityEvent.CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION)) {
            updates.handle(event(mask = mask))
        }
        for (type in listOf(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED, AccessibilityEvent.TYPE_VIEW_SELECTED,
            AccessibilityEvent.TYPE_ANNOUNCEMENT)) updates.handle(event(type))
        val companion = node().apply { className = "android.widget.TextView"; text = "Value: 100 / 200" }
        updates.handle(event(source = companion))
        assertTrue(spoken().isEmpty())
    }
    @Test fun `state description bit is accepted in a combined mask`() {
        updates.handle(event(mask = AccessibilityEvent.CONTENT_CHANGE_TYPE_STATE_DESCRIPTION or
            AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE))
        assertEquals(listOf("40%"), spoken())
    }
    @Test fun `duplicate value is never repeated within a focus session`() {
        change(50); advance(5000); change(50); advance(10000)
        assertEquals(listOf("50%"), spoken())
    }
    @Test fun `latest value replaces older pending values at the five second boundary`() {
        change(50); advance(1000); change(60); advance(1000); change(70); advance(2999)
        assertEquals(listOf("50%"), spoken())
        advance(1); assertEquals(listOf("50%", "70%"), spoken())
        advance(10000); assertEquals(2, spoken().size)
    }
    @Test fun `subsequent throttle windows start when speech is submitted`() {
        change(50); advance(5000); change(60); advance(100); change(70)
        advance(4899); assertEquals(listOf("50%", "60%"), spoken())
        advance(1); assertEquals(listOf("50%", "60%", "70%"), spoken())
    }
    @Test fun `completion bypasses throttle and cancels pending intermediate values`() {
        change(50); advance(1000); change(60); change(100)
        assertEquals(listOf("50%", "100%"), spoken())
        advance(10000); change(100); assertEquals(2, spoken().size)
    }
    @Test fun `completion respects explicit descriptions and non hundred maximum`() {
        change(50); change(100, "Complete")
        assertEquals(listOf("50%", "Complete"), spoken())
    }
    @Test fun `return to an already spoken value cancels an obsolete pending value`() {
        change(50); change(60); change(50); advance(5000)
        assertEquals(listOf("50%"), spoken())
    }
    @Test fun `focus loss clears pending and further unfocused updates`() {
        change(50); change(60)
        bar.isAccessibilityFocused = false; shadow(root).focused = null
        updates.handle(event(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED))
        advance(5000); change(70)
        assertEquals(listOf("50%"), spoken())
    }
    @Test fun `delayed delivery rechecks actual focus even when no clearing event arrived`() {
        change(50); change(60); shadow(root).focused = node().unwrap()
        advance(5000); assertEquals(listOf("50%"), spoken())
    }
    @Test fun `active window changes clear pending`() {
        change(50); change(60)
        updates.handle(event(AccessibilityEvent.TYPE_WINDOWS_CHANGED).apply {
            AccessibilityEvent::class.java.getDeclaredMethod("setWindowChanges", Int::class.javaPrimitiveType)
                .invoke(this, AccessibilityEvent.WINDOWS_CHANGE_ACTIVE)
        })
        advance(5000); assertEquals(listOf("50%"), spoken())
    }
    @Test fun `cross window and invisible sources do not speak`() {
        val another = node(2)
        shadow(root).focused = another.unwrap(); updates.handle(event(source = another))
        shadow(root).focused = bar.unwrap(); bar.isVisibleToUser = false; change(50)
        assertTrue(spoken().isEmpty())
    }
    @Test fun `focus returning starts a new session without stale pending speech`() {
        change(50); change(60)
        updates.handle(event(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED))
        focus(); change(50); advance(5000); assertEquals(listOf("50%", "50%"), spoken())
    }
    @Test fun `missing source or unusable range is ignored`() {
        updates.handle(event(source = null))
        bar.unwrap().rangeInfo = null; updates.handle(event())
        bar.rangeInfo = AccessibilityNodeInfoCompat.RangeInfoCompat.obtain(0, 0f, 0f, 0f)
        updates.handle(event()); assertTrue(spoken().isEmpty())
    }
    @Test fun `touch start cancels pending and prevents speech during the interaction`() {
        change(50); change(60)
        val touch = event(AccessibilityEvent.TYPE_TOUCH_INTERACTION_START)
        SpeechInterceptor(speech).handle(touch); updates.handle(touch)
        change(70); advance(5000); assertEquals(listOf("50%"), spoken())
        updates.handle(event(AccessibilityEvent.TYPE_TOUCH_INTERACTION_END))
        change(80); assertEquals(listOf("50%", "80%"), spoken())
    }
    @Test fun `ordinary progress speech preserves pause restart replacement semantics`() {
        speech.speak(Text("Previous announcement")); speech.togglePauseResume()
        change(50); speech.togglePauseResume(); speech.togglePauseResume()
        assertEquals(listOf("Previous announcement", "50%", "50%"), spoken())
    }
    @Test fun `shutdown cancels the scheduled callback`() {
        change(50); change(60); updates.finish(); advance(5000)
        assertEquals(listOf("50%"), spoken())
    }
    @Test fun `becoming indeterminate cancels obsolete pending percentage`() {
        change(50); change(60)
        bar.unwrap().rangeInfo = null; bar.stateDescription = "In progress"
        updates.handle(event()); advance(5000)
        assertEquals(listOf("50%"), spoken())
    }
    @Test fun `pending percentage is not delivered after range becomes unusable without an event`() {
        change(50); change(60); bar.unwrap().rangeInfo = null; advance(5000)
        assertEquals(listOf("50%"), spoken())
    }
    @Test fun `percentage style completion uses the already percentage valued range`() {
        bar.rangeInfo = AccessibilityNodeInfoCompat.RangeInfoCompat.obtain(2, 0f, 100f, 50f)
        bar.stateDescription = "50%"; updates.handle(event())
        bar.rangeInfo = AccessibilityNodeInfoCompat.RangeInfoCompat.obtain(2, 0f, 100f, 100f)
        bar.stateDescription = "100%"; updates.handle(event())
        assertEquals(listOf("50%", "100%"), spoken())
    }
    @Test fun `equal formatted percentages are not repeated for different raw values`() {
        change(50)
        bar.rangeInfo = AccessibilityNodeInfoCompat.RangeInfoCompat.obtain(1, 0f, 200f, 100.5f)
        bar.stateDescription = "50%"; advance(5000); updates.handle(event())
        assertEquals(listOf("50%"), spoken())
    }
}
