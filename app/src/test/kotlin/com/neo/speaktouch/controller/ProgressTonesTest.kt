package com.neo.speaktouch.controller

import android.media.AudioTrack
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.neo.speaktouch.intercepter.event.ProgressInterceptor
import com.neo.speaktouch.intercepter.event.ProgressToneInterceptor
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
import org.robolectric.shadows.ShadowAudioTrack

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 31], shadows = [ProgressAudioShadow::class, SliderNodeShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class ProgressTonesTest {
    private lateinit var root: AccessibilityNodeInfoCompat
    private lateinit var bar: AccessibilityNodeInfoCompat
    private lateinit var service: ServiceController
    private lateinit var tones: ProgressToneInterceptor
    private val tracks = mutableListOf<AudioTrack>()
    private val recorder = ShadowAudioTrack.OnAudioDataWrittenListener { track, _, _ -> tracks.add(track) }
    private fun shadow(node: AccessibilityNodeInfoCompat): SliderNodeShadow = Shadow.extract(node.unwrap())
    private fun audio(track: AudioTrack): ProgressAudioShadow = Shadow.extract(track)
    private fun event(type: Int = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                      mask: Int = AccessibilityEvent.CONTENT_CHANGE_TYPE_STATE_DESCRIPTION,
                      source: AccessibilityNodeInfoCompat? = bar) = AccessibilityEvent.obtain(type).apply {
        contentChangeTypes = mask
        if (source != null) shadowOf(this).setSourceNode(source.unwrap())
    }
    private fun change(value: Double) {
        bar.rangeInfo = AccessibilityNodeInfoCompat.RangeInfoCompat.obtain(1, 0f, 200f, (value * 2).toFloat())
        bar.stateDescription = "$value%"
        tones.handle(event())
    }
    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    @Before fun setup() {
        fun node() = AccessibilityNodeInfoCompat.wrap(AccessibilityNodeInfo.obtain()).apply {
            isVisibleToUser = true; isAccessibilityFocused = true
            val window = AccessibilityWindowInfo.obtain(); shadowOf(window).setId(1)
            shadowOf(unwrap()).setAccessibilityWindowInfo(window)
        }
        root = node().apply { className = "android.view.View"; isAccessibilityFocused = false }
        bar = node().apply { className = "android.widget.ProgressBar" }
        shadow(root).addChild(bar.unwrap()); shadow(root).focused = bar.unwrap()
        service = ServiceController(SliderTestService().apply { this.root = this@ProgressTonesTest.root.unwrap() })
        tones = ProgressToneInterceptor(service, ProgressToneController())
        ShadowAudioTrack.addAudioDataListener(recorder)
    }
    @After fun cleanup() { tones.finish(); ShadowAudioTrack.removeAudioDataListener(recorder) }

    @Test fun `changed focused progress produces short audio without focus actions`() {
        change(20.0); advance(100); change(80.0)
        assertEquals(2, tracks.size)
        assertTrue(shadow(bar).performedActions.isEmpty())
        assertEquals(bar.unwrap(), shadow(root).focused)
    }
    @Test fun `unchanged value produces no duplicate tone`() {
        change(40.0); advance(1000); change(40.0)
        assertEquals(1, tracks.size)
    }
    @Test fun `focus acquisition seeds value silently`() {
        bar.rangeInfo = AccessibilityNodeInfoCompat.RangeInfoCompat.obtain(0, 0f, 200f, 80f)
        tones.handle(event(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED))
        change(40.0); assertTrue(tracks.isEmpty())
        change(50.0); assertEquals(1, tracks.size)
    }
    @Test fun `unfocused and actually different focused nodes do not produce tones`() {
        bar.isAccessibilityFocused = false; change(20.0)
        bar.isAccessibilityFocused = true; shadow(root).focused = root.unwrap(); change(40.0)
        assertTrue(tracks.isEmpty())
    }
    @Test fun `slider and companion text changes produce no tone`() {
        for (name in listOf("android.widget.SeekBar", "android.widget.TextView", "android.view.View")) {
            bar.className = name; change(40.0)
        }
        assertTrue(tracks.isEmpty())
    }
    @Test fun `non state description and selected events produce no tone`() {
        tones.handle(event(mask = AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT))
        tones.handle(event(mask = AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE))
        tones.handle(event(AccessibilityEvent.TYPE_VIEW_SELECTED))
        tones.handle(event(source = null))
        assertTrue(tracks.isEmpty())
    }
    @Test fun `invalid and indeterminate progress produce no tone and stop an old tone`() {
        change(40.0); bar.unwrap().rangeInfo = null; tones.handle(event())
        assertEquals(1, audio(tracks.single()).releases)
        change(Double.NaN); change(120.0)
        bar.rangeInfo = AccessibilityNodeInfoCompat.RangeInfoCompat.obtain(0, 0f, 0f, 0f)
        tones.handle(event()); assertEquals(1, tracks.size)
    }
    @Test fun `focus loss stops the tone and further updates are ignored`() {
        change(40.0); bar.isAccessibilityFocused = false; shadow(root).focused = null
        tones.handle(event(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED))
        assertEquals(1, audio(tracks.single()).releases)
        change(50.0); assertEquals(1, tracks.size)
    }
    @Test fun `touch stops tones and prevents them during exploration`() {
        change(40.0); tones.handle(event(AccessibilityEvent.TYPE_TOUCH_INTERACTION_START))
        change(50.0); advance(1000); assertEquals(1, tracks.size)
        assertEquals(1, audio(tracks.single()).releases)
        tones.handle(event(AccessibilityEvent.TYPE_TOUCH_INTERACTION_END)); change(60.0)
        assertEquals(2, tracks.size)
    }
    @Test fun `rapid updates are dropped without delayed audio backlog`() {
        change(40.0); change(50.0); advance(99); change(60.0)
        assertEquals(1, tracks.size)
        advance(1); change(70.0); assertEquals(2, tracks.size)
        advance(5000); assertEquals(2, tracks.size)
    }
    @Test fun `completion bypasses rapid update limit and unchanged completion is silent`() {
        change(90.0); change(100.0); change(100.0)
        assertEquals(2, tracks.size)
        assertEquals(1, audio(tracks.first()).releases)
    }
    @Test fun `tone cadence does not alter speech coalescing or completion feedback`() {
        val context = RuntimeEnvironment.getApplication()
        val tts = TextToSpeech(context, null)
        val updates = ProgressInterceptor(service, SpeechController(tts, context, Reader(context)), context)
        fun update(value: Int) {
            change(value.toDouble()); bar.stateDescription = "$value%"; updates.handle(event())
        }
        update(50); advance(1000); update(60); advance(1000); update(70)
        assertEquals(3, tracks.size); assertEquals(listOf("50%"), shadowOf(tts).spokenTextList)
        advance(3000); assertEquals(listOf("50%", "70%"), shadowOf(tts).spokenTextList)
        update(100); assertEquals(listOf("50%", "70%", "100%"), shadowOf(tts).spokenTextList)
        updates.finish()
    }
    @Test fun `tone failure does not change existing speech`() {
        val context = RuntimeEnvironment.getApplication()
        val tts = TextToSpeech(context, null)
        val updates = ProgressInterceptor(service, SpeechController(tts, context, Reader(context)), context)
        ShadowAudioTrack.enableIllegalStateOnPlay(true)
        try { change(50.0); updates.handle(event()); assertEquals(listOf("50.0%"), shadowOf(tts).spokenTextList) }
        finally { ShadowAudioTrack.enableIllegalStateOnPlay(false); updates.finish() }
    }
    @Test fun `shutdown releases current audio`() {
        change(40.0); tones.finish()
        assertEquals(1, audio(tracks.single()).releases)
    }
}
