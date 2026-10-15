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
import android.os.Build
import android.os.SystemClock
import android.os.Looper
import android.os.Vibrator
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.neo.speaktouch.intercepter.event.CallbackInterceptor
import com.neo.speaktouch.intercepter.event.NavigationSoundInterceptor
import com.neo.speaktouch.utils.SpeechPreferences
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
@Config(sdk = [22, 28, 31], shadows = [NavigationAudioShadow::class, NavigationNodeShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class ScrollFeedbackTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var window: AccessibilityWindowInfo
    private lateinit var root: AccessibilityNodeInfo
    private lateinit var list: AccessibilityNodeInfo
    private lateinit var service: SliderTestService
    private lateinit var callbacks: CallbackInterceptor
    private lateinit var feedback: ScrollFeedbackController
    private val tracks = mutableListOf<AudioTrack>()
    private val data = mutableListOf<ByteArray>()
    private var time = 100L
    private val recorder = ShadowAudioTrack.OnAudioDataWrittenListener { track, bytes, _ ->
        tracks.add(track); data.add(bytes.copyOf())
    }
    private fun nodeShadow(node: AccessibilityNodeInfo): NavigationNodeShadow = Shadow.extract(node)
    private fun audio(track: AudioTrack): NavigationAudioShadow = Shadow.extract(track)
    private fun node(label: String? = null) = AccessibilityNodeInfo.obtain().apply {
        className = "android.view.View"; text = label; packageName = "test.scroll"
        isVisibleToUser = true; isEnabled = true; isFocusable = label != null
        shadowOf(this).setAccessibilityWindowInfo(this@ScrollFeedbackTest.window)
        nodeShadow(this).setRefreshReturnValue(true)
    }
    private fun signal(type: Int) = AccessibilityEvent.obtain(type)
    private fun scroll(y: Int = -1, max: Int = 0, from: Int = -1, to: Int = -1, count: Int = -1,
                       delta: Int? = null, x: Int = -1, source: AccessibilityNodeInfo = list): AccessibilityEvent =
        AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_SCROLLED).apply {
            eventTime = time++; scrollY = y; maxScrollY = max; scrollX = x
            fromIndex = from; toIndex = to; itemCount = count; isScrollable = true
            packageName = "test.scroll"
            shadowOf(this).setSourceNode(source); shadowOf(this).setWindowId(window.id)
            if (Build.VERSION.SDK_INT >= 28 && delta != null) scrollDeltaY = delta
        }
    @Before fun setup() {
        context.getSharedPreferences(SpeechPreferences.FILE_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        NavigationNodeShadow.refreshBehaviors.clear()
        window = AccessibilityWindowInfo.obtain().apply {
            shadowOf(this).setId(1); shadowOf(this).setType(AccessibilityWindowInfo.TYPE_APPLICATION)
        }
        root = node(); list = node().apply { className = "android.widget.ScrollView"; isScrollable = true }
        nodeShadow(root).addChild(list); shadowOf(window).setRoot(root)
        service = SliderTestService().apply { this.root = this@ScrollFeedbackTest.root }
        callbacks = CallbackInterceptor()
        feedback = ScrollFeedbackController(ServiceController(service), callbacks, context)
        ShadowAudioTrack.addAudioDataListener(recorder)
        NavigationAudioShadow.api22WriteRecorder = { track, bytes -> tracks.add(track); data.add(bytes.copyOf()) }
    }
    @After fun cleanup() {
        if (::feedback.isInitialized) feedback.finish()
        if (::callbacks.isInitialized) callbacks.finish()
        NavigationNodeShadow.refreshBehaviors.clear()
        NavigationAudioShadow.api22WriteRecorder = null
        ShadowAudioTrack.removeAudioDataListener(recorder)
    }
    private fun baseline(y: Int = 0) = feedback.handle(scroll(y, 500))
    private fun verticalIndices() {
        list.collectionInfo = AccessibilityNodeInfo.CollectionInfo.obtain(100, 1, false)
    }
    private fun advance(ms: Long = 250) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun expected(p: Double) = ScrollFeedbackController.pcm(p)!!

    @Test @Config(sdk = [28, 31]) fun `Oppo vertical grid uses indices with zero pixel range in both directions`() {
        list.collectionInfo = AccessibilityNodeInfo.CollectionInfo.obtain(39, 4, false)
        feedback.handle(scroll(0, 0, 15, 42, 156, delta = -766))
        assertArrayEquals(expected(15.0 / 156), data.single())
        advance()
        feedback.handle(scroll(0, 0, 3, 34, 156, delta = -500))
        assertArrayEquals(expected(3.0 / 156), data.last())
        advance()
        feedback.handle(scroll(0, 0, 15, 42, 156, delta = 500))
        assertEquals(3, data.size)
        assertArrayEquals(expected(15.0 / 156), data.last())
    }
    @Test fun `grid indices without vertical evidence remain silent`() {
        list.collectionInfo = AccessibilityNodeInfo.CollectionInfo.obtain(39, 4, false)
        feedback.handle(scroll(0, 0, 3, 34, 156))
        feedback.handle(scroll(0, 0, 15, 42, 156))
        assertTrue(data.isEmpty())
    }
    @Test @Config(sdk = [28, 31]) fun `established vertical grid can continue using index movement without deltas`() {
        list.collectionInfo = AccessibilityNodeInfo.CollectionInfo.obtain(39, 4, false)
        feedback.handle(scroll(0, 0, 3, 34, 156, delta = 100))
        advance()
        feedback.handle(scroll(0, 0, 15, 42, 156))
        assertEquals(2, data.size)
        assertArrayEquals(expected(15.0 / 156), data.last())
    }
    @Test @Config(sdk = [28, 31]) fun `horizontal grid movement does not establish vertical position`() {
        list.collectionInfo = AccessibilityNodeInfo.CollectionInfo.obtain(39, 4, false)
        feedback.handle(scroll(0, 0, 3, 34, 156, delta = 100).apply { scrollDeltaX = 100 })
        advance()
        feedback.handle(scroll(0, 0, 15, 42, 156))
        assertTrue(data.isEmpty())
    }
    @Test @Config(sdk = [28, 31]) fun `grid column change rebaselines instead of producing a pitch jump`() {
        list.collectionInfo = AccessibilityNodeInfo.CollectionInfo.obtain(39, 4, false)
        feedback.handle(scroll(0, 0, 3, 34, 156, delta = 100))
        advance()
        list.collectionInfo = AccessibilityNodeInfo.CollectionInfo.obtain(52, 3, false)
        feedback.handle(scroll(0, 0, 15, 42, 156, delta = 100))
        assertEquals(1, data.size)
        feedback.handle(scroll(0, 0, 18, 45, 156, delta = 100))
        assertEquals(2, data.size)
    }
    @Test @Config(sdk = [28, 31]) fun `invalid grid indices and unknown dimensions stay silent`() {
        list.collectionInfo = AccessibilityNodeInfo.CollectionInfo.obtain(39, 4, false)
        feedback.handle(scroll(0, 0, 15, 160, 156, delta = 100))
        list.collectionInfo = AccessibilityNodeInfo.CollectionInfo.obtain(39, 0, false)
        feedback.handle(scroll(0, 0, 15, 42, 156, delta = 100))
        assertTrue(data.isEmpty())
    }
    @Test fun `one confirmed offset movement submits one positional fixed pitch beep`() {
        baseline(); feedback.handle(scroll(250, 500))
        assertEquals(1, tracks.size); assertEquals(1280, data.single().size)
        assertArrayEquals(expected(.5), data.single())
    }
    @Test fun `increasing positions select successively higher discrete tones`() {
        baseline(50)
        for (y in listOf(0, 125, 250, 375, 500)) { feedback.handle(scroll(y, 500)); advance() }
        assertEquals(5, tracks.size)
        data.zip(listOf(0.0, .25, .5, .75, 1.0)).forEach { (pcm, p) -> assertArrayEquals(expected(p), pcm) }
    }
    @Test fun `decreasing positions select successively lower discrete tones`() {
        baseline(400)
        for (y in listOf(500, 375, 250, 125, 0)) { feedback.handle(scroll(y, 500)); advance() }
        assertEquals(5, tracks.size)
        data.zip(listOf(1.0, .75, .5, .25, 0.0)).forEach { (pcm, p) -> assertArrayEquals(expected(p), pcm) }
    }
    @Test fun `same position chooses same pitch regardless of movement direction`() {
        baseline(50); feedback.handle(scroll(100, 500))
        feedback.start(); baseline(150); feedback.handle(scroll(100, 500))
        assertEquals(2, tracks.size); assertArrayEquals(data[0], data[1])
    }
    @Test fun `offset only first event establishes silent baseline`() {
        baseline(100); assertTrue(tracks.isEmpty())
    }
    @Test fun `unchanged position with new timestamp produces no tone`() {
        baseline(); feedback.handle(scroll(0, 500)); assertTrue(tracks.isEmpty())
    }
    @Test fun `identical scroll event is not played twice even after separation interval`() {
        baseline(); val event = scroll(50, 500); feedback.handle(event); advance(); feedback.handle(event)
        assertEquals(1, tracks.size)
    }
    @Test fun `minimum separation includes 249 silent milliseconds and accepts 250`() {
        baseline(); feedback.handle(scroll(50, 500))
        val submittedAt = SystemClock.uptimeMillis()
        advance(249); feedback.handle(scroll(100, 500)); assertEquals(1, tracks.size)
        advance(1); feedback.handle(scroll(150, 500)); assertEquals(2, tracks.size)
        assertTrue(SystemClock.uptimeMillis() - submittedAt >= 250)
        assertArrayEquals(expected(.3), data.last())
    }
    @Test fun `intermediate events update latest position but never queue sounds`() {
        baseline(); feedback.handle(scroll(100, 500))
        advance(100); feedback.handle(scroll(150, 500))
        advance(100); feedback.handle(scroll(200, 500)); assertEquals(1, tracks.size)
        advance(50); feedback.handle(scroll(250, 500)); assertEquals(2, tracks.size)
        assertArrayEquals(expected(.2), data.first()); assertArrayEquals(expected(.5), data.last())
        advance(5000); assertEquals(2, tracks.size)
    }
    @Test fun `throttled latest position is remembered so stationary event cannot catch up`() {
        baseline(); feedback.handle(scroll(100, 500))
        advance(100); feedback.handle(scroll(200, 500))
        advance(200); feedback.handle(scroll(200, 500)); assertEquals(1, tracks.size)
        feedback.handle(scroll(225, 500)); assertEquals(2, tracks.size)
        assertArrayEquals(expected(.45), data.last())
    }
    @Test @Config(sdk = [28, 31]) fun `explicit movement with known position can produce first tone`() {
        feedback.handle(scroll(100, 500, delta = 20)); assertArrayEquals(expected(.2), data.single())
    }
    @Test @Config(sdk = [28, 31]) fun `negative movement with known position selects position not direction waveform`() {
        feedback.handle(scroll(100, 500, delta = -20)); assertArrayEquals(expected(.2), data.single())
    }
    @Test @Config(sdk = [28, 31]) fun `delta without absolute position cannot fabricate positional progression`() {
        feedback.handle(scroll(delta = 30)); advance(); feedback.handle(scroll(delta = 50))
        advance(); feedback.handle(scroll(delta = -20)); assertTrue(tracks.isEmpty())
    }
    @Test @Config(sdk = [28, 31]) fun `unchanged exact pixel position is not fabricated progress despite delta`() {
        feedback.handle(scroll(100, 500, delta = 10)); advance()
        feedback.handle(scroll(100, 500, delta = 10)); assertEquals(1, tracks.size)
    }
    @Test @Config(sdk = [28, 31]) fun `confirmed motion inside same indexed item can repeat its fixed pitch`() {
        verticalIndices()
        feedback.handle(scroll(from = 10, to = 14, count = 100, delta = 10)); advance()
        feedback.handle(scroll(from = 10, to = 14, count = 100, delta = 20))
        assertEquals(2, tracks.size); data.forEach { assertArrayEquals(expected(.1), it) }
    }
    @Test @Config(sdk = [28, 31]) fun `contradictory delta and offset direction stays silent`() {
        baseline(50); feedback.handle(scroll(80, 500, delta = -20)); assertTrue(tracks.isEmpty())
    }
    @Test @Config(sdk = [28, 31]) fun `unspecified minus one delta uses known one pixel reverse offset`() {
        baseline(50); feedback.handle(scroll(49, 500, delta = -1)); assertArrayEquals(expected(.098), data.single())
    }
    @Test fun `offset changes without trustworthy maximum stay silent`() {
        feedback.handle(scroll(0)); feedback.handle(scroll(80)); advance(); feedback.handle(scroll(180))
        assertTrue(tracks.isEmpty())
    }
    @Test fun `valid offset range takes precedence over collection indices`() {
        verticalIndices()
        feedback.handle(scroll(0, 500, 10, 14, 100)); feedback.handle(scroll(250, 500, 20, 24, 100))
        assertArrayEquals(expected(.5), data.single())
    }
    @Test fun `single column indices provide positional fallback`() {
        verticalIndices()
        feedback.handle(scroll(from = 2, to = 6, count = 100)); feedback.handle(scroll(from = 3, to = 7, count = 100))
        advance(); feedback.handle(scroll(from = 2, to = 6, count = 100))
        assertEquals(2, tracks.size); assertArrayEquals(expected(.03), data[0]); assertArrayEquals(expected(.02), data[1])
    }
    @Test fun `indices without known vertical orientation do not guess position`() {
        feedback.handle(scroll(from = 2, to = 6, count = 100)); feedback.handle(scroll(from = 3, to = 7, count = 100))
        assertTrue(tracks.isEmpty())
    }
    @Test fun `changed item count rebaselines before further index feedback`() {
        verticalIndices()
        feedback.handle(scroll(from = 2, to = 6, count = 100)); feedback.handle(scroll(from = 3, to = 7, count = 100))
        advance(); feedback.handle(scroll(from = 4, to = 8, count = 200)); assertEquals(1, tracks.size)
        feedback.handle(scroll(from = 5, to = 9, count = 200)); assertEquals(2, tracks.size)
        assertArrayEquals(expected(.025), data.last())
    }
    @Test fun `changed pixel range rebaselines before further offset feedback`() {
        baseline(); feedback.handle(scroll(100, 500)); advance()
        feedback.handle(scroll(150, 1000)); assertEquals(1, tracks.size)
        feedback.handle(scroll(200, 1000)); assertEquals(2, tracks.size); assertArrayEquals(expected(.2), data.last())
    }
    @Test fun `switching normalization basis does not invent a pitch jump`() {
        verticalIndices(); baseline(); feedback.handle(scroll(100, 500)); advance()
        feedback.handle(scroll(from = 10, to = 14, count = 100)); assertEquals(1, tracks.size)
        feedback.handle(scroll(from = 11, to = 15, count = 100)); assertEquals(2, tracks.size)
        assertArrayEquals(expected(.11), data.last())
    }
    @Test fun `position becoming known after unknown movement establishes baseline first`() {
        feedback.handle(scroll(0)); feedback.handle(scroll(50))
        feedback.handle(scroll(100, 500)); assertTrue(tracks.isEmpty())
        feedback.handle(scroll(150, 500)); assertArrayEquals(expected(.3), data.single())
    }
    @Test fun `invalid indices are ignored`() {
        verticalIndices()
        feedback.handle(scroll(from = 2, to = 6, count = 4)); feedback.handle(scroll(from = 3, to = 7, count = 4))
        assertTrue(tracks.isEmpty())
    }
    @Test fun `out of range offsets without fallback do not make endpoint pitches`() {
        baseline(); feedback.handle(scroll(600, 500)); advance(); feedback.handle(scroll(-30, 500))
        assertTrue(tracks.isEmpty())
    }
    @Test fun `horizontal movement is not assigned vertical feedback`() {
        feedback.handle(scroll(0, x = 0)); feedback.handle(scroll(0, x = 100)); assertTrue(tracks.isEmpty())
    }
    @Test fun `horizontal metadata does not turn missing vertical offset into reverse movement`() {
        feedback.handle(scroll(50, x = 0)); feedback.handle(scroll(0, x = 100)); assertTrue(tracks.isEmpty())
    }
    @Test fun `older scroll event cannot reverse current feedback or baseline`() {
        baseline(); val old = scroll(30, 500); feedback.handle(scroll(50, 500))
        advance(); feedback.handle(old); feedback.handle(scroll(50, 500)); assertEquals(1, tracks.size)
    }
    @Test fun `stale source cannot play feedback`() {
        baseline(); nodeShadow(list).setRefreshReturnValue(false); feedback.handle(scroll(50, 500))
        assertTrue(tracks.isEmpty())
    }
    @Test fun `source refresh exception is contained`() {
        baseline(); NavigationNodeShadow.refreshBehaviors[list] = { throw IllegalStateException("Gone") }
        feedback.handle(scroll(50, 500)); assertTrue(tracks.isEmpty())
    }
    @Test fun `invisible source cannot play feedback`() {
        baseline(); list.isVisibleToUser = false; feedback.handle(scroll(50, 500)); assertTrue(tracks.isEmpty())
    }
    @Test fun `detached source cannot play feedback`() {
        baseline(); val detached = node().apply { isScrollable = true }
        feedback.handle(scroll(50, 500, source = detached)); assertTrue(tracks.isEmpty())
    }
    @Test fun `non scrollable source and event cannot play feedback`() {
        baseline(); list.isScrollable = false
        feedback.handle(scroll(50, 500).apply { isScrollable = false }); assertTrue(tracks.isEmpty())
    }
    @Test fun `mismatched event window is ignored`() {
        baseline(); feedback.handle(scroll(50, 500).apply { shadowOf(this).setWindowId(2) }); assertTrue(tracks.isEmpty())
    }
    @Test fun `window change resets position and cancels playback`() {
        baseline(); feedback.handle(scroll(50, 500)); val track = tracks.single()
        feedback.handle(signal(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)); assertEquals(1, audio(track).releases)
        advance(); feedback.handle(scroll(100, 500)); assertEquals(1, tracks.size)
        feedback.handle(scroll(150, 500)); assertEquals(2, tracks.size); assertArrayEquals(expected(.3), data.last())
    }
    @Test fun `changed root window ID also resets without a window event`() {
        baseline(); shadowOf(window).setId(2); feedback.handle(scroll(100, 500)); assertTrue(tracks.isEmpty())
    }
    @Test fun `source change does not reuse another source position`() {
        baseline(); feedback.handle(scroll(100, 500)); advance()
        val other = node().apply { isScrollable = true }; nodeShadow(root).addChild(other)
        feedback.handle(scroll(150, 500, source = other)); assertEquals(1, tracks.size)
        feedback.handle(scroll(200, 500, source = other)); assertEquals(2, tracks.size)
        advance(); feedback.handle(scroll(150, 500)); assertEquals(2, tracks.size)
        feedback.handle(scroll(175, 500)); assertEquals(3, tracks.size); assertArrayEquals(expected(.35), data.last())
    }
    @Test @Config(sdk = [28, 31]) fun `source change cannot bypass global submission separation`() {
        feedback.handle(scroll(50, 500, delta = 20)); advance(10)
        val other = node().apply { isScrollable = true }; nodeShadow(root).addChild(other)
        feedback.handle(scroll(100, 500, delta = 20, source = other)); assertEquals(1, tracks.size)
        advance(240); feedback.handle(scroll(150, 500, delta = 20, source = other)); assertEquals(2, tracks.size)
    }
    @Test fun `audible preference updates live without replaying suppressed movement`() {
        baseline(); SpeechPreferences(context).audibleNavigationFeedback = false
        feedback.handle(scroll(50, 500)); assertTrue(tracks.isEmpty())
        SpeechPreferences(context).audibleNavigationFeedback = true
        feedback.handle(scroll(50, 500)); assertTrue(tracks.isEmpty())
        feedback.handle(scroll(100, 500)); assertEquals(1, tracks.size)
    }
    @Test fun `haptic preference has no influence and scrolling does not vibrate`() {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        shadowOf(vibrator).setHasVibrator(true)
        baseline(); SpeechPreferences(context).hapticNavigationFeedback = false
        feedback.handle(scroll(50, 500)); advance(); SpeechPreferences(context).hapticNavigationFeedback = true
        feedback.handle(scroll(100, 500)); assertEquals(2, tracks.size); assertFalse(shadowOf(vibrator).isVibrating)
    }
    @Test fun `rapid events drop intermediate sounds rather than queue or restart each tone`() {
        feedback.handle(scroll(0, 1000)); repeat(5) { feedback.handle(scroll((it + 1) * 100, 1000)) }
        assertEquals(1, tracks.size); assertEquals(0, audio(tracks.single()).releases)
        advance(); feedback.handle(scroll(600, 1000)); assertEquals(2, tracks.size)
        assertEquals(1, audio(tracks.first()).releases); assertArrayEquals(expected(.6), data.last())
        advance(5000); assertEquals(2, tracks.size)
    }
    @Test fun `navigation helper scroll and inertial tail remain silent`() {
        var retries = 0
        callbacks.addCallback(object : CallbackInterceptor.Scroll(list) { override fun invoke() { retries++ } })
        val event = scroll(50, 500); feedback.handle(event); callbacks.handle(event)
        assertEquals(1, retries); advance(); feedback.handle(scroll(80, 500)); assertTrue(tracks.isEmpty())
        feedback.handle(signal(AccessibilityEvent.TYPE_TOUCH_INTERACTION_START))
        feedback.handle(scroll(100, 500)); assertEquals(1, tracks.size)
    }
    @Test fun `duplicate helper event still suppresses its later inertial tail`() {
        baseline(); val event = scroll(50, 500); feedback.handle(event); advance()
        var retries = 0
        callbacks.addCallback(object : CallbackInterceptor.Scroll(list) { override fun invoke() { retries++ } })
        feedback.handle(event); callbacks.handle(event)
        feedback.handle(scroll(80, 500))
        assertEquals(1, retries); assertEquals(1, tracks.size)
    }    @Test fun `successful helper focus move retains only normal movement click`() {
        val a = node("A").apply { className = "android.widget.Button"; isAccessibilityFocused = true }
        val b = node("B").apply { className = "android.widget.Button" }
        nodeShadow(list).addChild(a); nodeShadow(list).addChild(b); nodeShadow(root).focused = a
        val movement = NavigationSoundInterceptor(ServiceController(service), NavigationSoundController(), context)
        movement.onServiceConnected()
        try {
            callbacks.addCallback(object : CallbackInterceptor.Scroll(list) {
                override fun invoke() {
                    a.isAccessibilityFocused = false; b.isAccessibilityFocused = true; nodeShadow(root).focused = b
                    val focused = signal(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
                    shadowOf(focused).setSourceNode(b); movement.handle(focused)
                }
            })
            val event = scroll(50, 500); feedback.handle(event); callbacks.handle(event)
            assertEquals(1, tracks.size); assertEquals(NavigationSoundController.FRAMES * 2, data.single().size)
        } finally { movement.finish() }
    }
    @Test fun `endpoints do not invent manual boundary feedback`() {
        baseline(50); feedback.handle(scroll(0, 500)); advance(); feedback.handle(scroll(0, 500))
        feedback.handle(scroll(500, 500)); advance(); feedback.handle(scroll(500, 500))
        assertEquals(2, tracks.size); assertArrayEquals(expected(0.0), data.first()); assertArrayEquals(expected(1.0), data.last())
    }
    @Test fun `missing events and pending scroll never become timeout boundaries`() {
        callbacks.addCallback(object : CallbackInterceptor.Scroll(list) { override fun invoke() = Unit })
        feedback.handle(scroll(0, 500)); advance(60000); assertTrue(tracks.isEmpty())
    }
    @Test fun `slider and progress bar scroll events remain excluded`() {
        baseline(); list.className = "android.widget.SeekBar"; feedback.handle(scroll(50, 500))
        list.className = "android.widget.ProgressBar"; feedback.handle(scroll(100, 500)); assertTrue(tracks.isEmpty())
    }
    @Test fun `teardown releases playback and prevents stale feedback`() {
        baseline(); feedback.handle(scroll(50, 500)); feedback.finish()
        assertEquals(1, audio(tracks.single()).releases)
        feedback.handle(scroll(100, 500)); assertEquals(1, tracks.size)
    }
    @Test fun `reconnect starts with fresh silent baseline`() {
        baseline(); feedback.handle(scroll(50, 500)); feedback.start(); feedback.handle(scroll(100, 500))
        assertEquals(1, tracks.size)
    }
    @Test fun `progress tone ownership remains independent`() {
        val progress = ProgressToneController()
        try {
            assertTrue(progress.play(0.5)); val tone = tracks.single()
            baseline(); feedback.handle(scroll(50, 500)); feedback.finish()
            assertEquals(0, audio(tone).releases); assertEquals(ProgressToneController.FRAMES * 2, data.first().size)
        } finally { progress.stop() }
    }
}