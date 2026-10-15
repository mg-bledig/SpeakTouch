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
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.neo.speaktouch.intercepter.event.CallbackInterceptor
import com.neo.speaktouch.intercepter.event.NavigationSoundInterceptor
import com.neo.speaktouch.intercepter.gesture.GestureInterceptor
import com.neo.speaktouch.utils.Reader
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadows.ShadowAccessibilityNodeInfo
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAudioTrack

// Event.getSource() returns a node copy. Model provider refresh by native node identity,
// rather than storing a callback on one shadow instance which is lost during copying.
@Implements(AccessibilityNodeInfo::class)
class NavigationNodeShadow : ShadowAccessibilityNodeInfo() {
    @RealObject private lateinit var node: AccessibilityNodeInfo
    var focused: AccessibilityNodeInfo? = null
    @Implementation fun findFocus(kind: Int): AccessibilityNodeInfo? =
        if (kind == AccessibilityNodeInfo.FOCUS_ACCESSIBILITY) focused else null
    @Implementation override fun refresh(): Boolean =
        refreshBehaviors[node]?.invoke(node) ?: super.refresh()
    companion object {
        val refreshBehaviors = mutableMapOf<AccessibilityNodeInfo, (AccessibilityNodeInfo) -> Boolean>()
    }
}

@Implements(AudioTrack::class)
class NavigationAudioShadow : ShadowAudioTrack() {
    @RealObject private lateinit var track: AudioTrack
    companion object {
        var api22WriteRecorder: ((AudioTrack, ByteArray) -> Unit)? = null
    }
    var setupCalls = 0
    var writeCalls = 0
    var stops = 0
    var releases = 0
    var marker = 0
    var listener: AudioTrack.OnPlaybackPositionUpdateListener? = null
    // Robolectric 4.15.1 implements native_setup from API 23 onward, but not this
    // API 22 signature. Let the framework constructor/write perform real state transitions.
    @Implementation(minSdk = 22, maxSdk = 22)
    fun native_setup(reference: Any?, attributes: Any?, sampleRate: Int, channelMask: Int,
                     format: Int, bufferSize: Int, mode: Int, sessionId: IntArray): Int {
        setupCalls++
        return AudioTrack.SUCCESS
    }
    // Keep the native-write test boundary local on API 22: the inherited recorder
    // calls AudioTrack.getFormat(), introduced in API 23. Framework write() still
    // performs the static-track state transition from the returned byte count.
    @Implementation(minSdk = 22, maxSdk = 22)
    public override fun native_write_byte(bytes: ByteArray, offset: Int, size: Int, format: Int, blocking: Boolean): Int {
        writeCalls++
        api22WriteRecorder?.invoke(track, bytes.copyOfRange(offset, offset + size))
        return size
    }
    @Implementation fun stop() { stops++ }
    @Implementation fun release() { releases++ }
    @Implementation fun native_set_marker_pos(position: Int): Int { marker = position; return 0 }
    @Implementation fun setPlaybackPositionUpdateListener(value: AudioTrack.OnPlaybackPositionUpdateListener?, handler: Handler?) {
        listener = value
    }
}
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [22, 28, 31], shadows = [NavigationAudioShadow::class, NavigationNodeShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class NavigationSoundTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var service: SliderTestService
    private lateinit var wrapper: ServiceController
    private lateinit var window: AccessibilityWindowInfo
    private lateinit var root: AccessibilityNodeInfoCompat
    private lateinit var first: AccessibilityNodeInfoCompat
    private lateinit var second: AccessibilityNodeInfoCompat
    private lateinit var third: AccessibilityNodeInfoCompat
    private lateinit var sounds: NavigationSoundController
    private lateinit var navigation: NavigationSoundInterceptor
    private val tracks = mutableListOf<AudioTrack>()
    private val data = mutableListOf<ByteArray>()
    private val recorder = ShadowAudioTrack.OnAudioDataWrittenListener { track, bytes, _ ->
        tracks.add(track); data.add(bytes.copyOf())
    }
    private fun nodeShadow(node: AccessibilityNodeInfoCompat): NavigationNodeShadow = Shadow.extract(node.unwrap())
    private fun audio(track: AudioTrack): NavigationAudioShadow = Shadow.extract(track)
    private fun node(text: String) = AccessibilityNodeInfoCompat.wrap(AccessibilityNodeInfo.obtain()).apply {
        className = "android.widget.Button"; this.text = text; packageName = "test.navigation"
        isVisibleToUser = true; isEnabled = true; isFocusable = true
        shadowOf(unwrap()).setAccessibilityWindowInfo(this@NavigationSoundTest.window)
        nodeShadow(this).setRefreshReturnValue(true)
    }
    private fun event(type: Int = AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
                      source: AccessibilityNodeInfoCompat? = second) = AccessibilityEvent.obtain(type).apply {
        if (source != null) shadowOf(this).setSourceNode(source.unwrap())
    }
    private fun move(node: AccessibilityNodeInfoCompat = second) {
        nodeShadow(root).focused?.isAccessibilityFocused = false
        node.isAccessibilityFocused = true
        nodeShadow(root).focused = node.unwrap()
        navigation.handle(event(source = node))
    }
    @Before fun setup() {
        NavigationNodeShadow.refreshBehaviors.clear()
        context.getSharedPreferences(SpeechPreferences.FILE_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        window = AccessibilityWindowInfo.obtain().apply {
            shadowOf(this).setId(1); shadowOf(this).setType(AccessibilityWindowInfo.TYPE_APPLICATION)
        }
        root = node("Root").apply { className = "android.view.View"; text = null; isFocusable = false }
        first = node("First").apply { isAccessibilityFocused = true }
        second = node("Second"); third = node("Third")
        for (node in listOf(first, second, third)) nodeShadow(root).addChild(node.unwrap())
        nodeShadow(root).focused = first.unwrap(); shadowOf(window).setRoot(root.unwrap())
        service = SliderTestService().apply { this.root = this@NavigationSoundTest.root.unwrap() }
        wrapper = ServiceController(service)
        sounds = NavigationSoundController()
        navigation = NavigationSoundInterceptor(wrapper, sounds, context)
        ShadowAudioTrack.addAudioDataListener(recorder)
        NavigationAudioShadow.api22WriteRecorder = { track, bytes ->
            tracks.add(track); data.add(bytes.copyOf())
        }
        navigation.onServiceConnected()
    }
    @After fun cleanup() {
        if (::navigation.isInitialized) navigation.finish()
        NavigationNodeShadow.refreshBehaviors.clear()
        NavigationAudioShadow.api22WriteRecorder = null
        ShadowAudioTrack.removeAudioDataListener(recorder)
        ShadowAudioTrack.enableIllegalStateOnPlay(false)
    }
    @Test fun `static PCM playback writes initializes plays and releases on every supported SDK`() {
        val player = ShortPcmPlayer()
        try {
            val pcm = byteArrayOf(0, 0, 1, 0, 0, 0)
            assertTrue(player.play(pcm, 16000))
            val track = tracks.single()
            assertArrayEquals(pcm, data.single())
            assertEquals(AudioTrack.STATE_INITIALIZED, track.state)
            assertEquals(AudioTrack.PLAYSTATE_PLAYING, track.playState)
            assertEquals(3, audio(track).marker)
            assertNotNull(audio(track).listener)
            if (Build.VERSION.SDK_INT == 22) {
                assertEquals(1, audio(track).setupCalls)
                assertEquals(1, audio(track).writeCalls)
            }
            player.stop()
            assertEquals(1, audio(track).stops)
            assertEquals(1, audio(track).releases)
            assertNull(audio(track).listener)
            player.stop()
            assertEquals(1, audio(track).releases)
        } finally { player.stop() }
    }
    @Test fun `enabled confirmed movement plays one click without focus actions`() {
        move()
        assertEquals(1, tracks.size)
        assertEquals(second.unwrap(), wrapper.getFocused())
        assertTrue(nodeShadow(second).performedActions.isEmpty())
    }
    @Test fun `failed navigation request does not produce a movement click`() {
        nodeShadow(second).setOnPerformActionListener { _, _ -> false }
        FocusController(CallbackInterceptor(), wrapper).moveFocusToNext()
        assertTrue(nodeShadow(second).performedActions.contains(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
        assertEquals(first.unwrap(), wrapper.getFocused())
        assertTrue(tracks.isEmpty())
    }
    @Test fun `disabled sound preference prevents movement clicks`() {
        SpeechPreferences(context).audibleNavigationFeedback = false
        move(); move(third)
        assertTrue(tracks.isEmpty())
    }
    @Test fun `live changes do not need restart and do not produce catch up clicks`() {
        SpeechPreferences(context).audibleNavigationFeedback = false
        move()
        SpeechPreferences(context).audibleNavigationFeedback = true
        navigation.handle(event())
        assertTrue(tracks.isEmpty())
        move(third); assertEquals(1, tracks.size)
        SpeechPreferences(context).audibleNavigationFeedback = false
        move(first); assertEquals(1, tracks.size)
    }
    @Test fun `same target and window including duplicate focus events click only once`() {
        move(); navigation.handle(event()); navigation.handle(event())
        assertEquals(1, tracks.size)
    }
    @Test fun `different targets with identical labels and resource IDs still click`() {
        second.text = "Same"; third.text = "Same"
        second.viewIdResourceName = "test.navigation:id/shared"
        third.viewIdResourceName = "test.navigation:id/shared"
        move(); move(third); move(second)
        assertEquals(3, tracks.size)
    }
    @Test fun `service activation and initial focus only establish a silent baseline`() {
        assertTrue(tracks.isEmpty())
        navigation.handle(event(source = first)); assertTrue(tracks.isEmpty())
        navigation.finish(); nodeShadow(root).focused = null; navigation.onServiceConnected()
        move(first); assertTrue(tracks.isEmpty())
        move(); assertEquals(1, tracks.size)
    }
    @Test fun `stale event whose source is not actually focused produces no click`() {
        second.isAccessibilityFocused = true
        navigation.handle(event())
        assertTrue(tracks.isEmpty())
    }
    @Test fun `failed refresh and invisible sources produce no click`() {
        nodeShadow(second).setRefreshReturnValue(false); move()
        third.isVisibleToUser = false; move(third)
        assertTrue(tracks.isEmpty())
    }
    @Test fun `focus lost during refresh produces no click`() {
        NavigationNodeShadow.refreshBehaviors[second.unwrap()] = { it.isAccessibilityFocused = false; true }
        move(); assertTrue(tracks.isEmpty())
    }
    @Test fun `refresh exception is contained without a movement click`() {
        NavigationNodeShadow.refreshBehaviors[second.unwrap()] = { throw IllegalStateException("Provider unavailable") }
        move(); assertTrue(tracks.isEmpty())
    }
    @Test fun `source from a different window is rejected`() {
        val other = AccessibilityWindowInfo.obtain().apply { shadowOf(this).setId(2) }
        shadowOf(second.unwrap()).setAccessibilityWindowInfo(other)
        move(); assertTrue(tracks.isEmpty())
    }
    @Test fun `missing source or active root produces no click`() {
        navigation.handle(event(source = null))
        second.isAccessibilityFocused = true
        service.root = null; navigation.handle(event())
        assertTrue(tracks.isEmpty())
    }
    @Test fun `context menu overlay focus is not a movement click`() {
        shadowOf(window).setType(AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY)
        move(); assertTrue(tracks.isEmpty())
    }
    @Test fun `focus clear and restore of same target stays silent`() {
        navigation.handle(event(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED, first))
        navigation.handle(event(source = first))
        assertTrue(tracks.isEmpty())
    }
    @Test fun `progress page touch and window feedback do not create movement sounds`() {
        for (type in listOf(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                AccessibilityEvent.TYPE_ANNOUNCEMENT, AccessibilityEvent.TYPE_VIEW_SCROLLED,
                AccessibilityEvent.TYPE_TOUCH_INTERACTION_START,
                AccessibilityEvent.TYPE_GESTURE_DETECTION_START,
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)) navigation.handle(event(type))
        assertTrue(tracks.isEmpty())
    }
    @Test fun `reread gesture speaks current content without a movement sound`() {
        val tts = TextToSpeech(context, null)
        val speech = SpeechController(tts, context, Reader(context))
        try {
            val gestures = GestureInterceptor(FocusController(CallbackInterceptor(), wrapper), wrapper,
                SliderController(wrapper, speech), RefocusController(wrapper, speech), speech)
            assertTrue(gestures.handle(AccessibilityService.GESTURE_SWIPE_UP_AND_DOWN))
            navigation.handle(event(source = first))
            assertTrue(shadowOf(tts).spokenTextList.isNotEmpty())
            assertTrue(tracks.isEmpty())
        } finally { speech.shutdown() }
    }
    @Test fun `slider adjustment changes value and speech without a movement sound`() {
        first.className = "android.widget.SeekBar"
        first.rangeInfo = AccessibilityNodeInfoCompat.RangeInfoCompat.obtain(0, 0f, 100f, 40f)
        first.addAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        nodeShadow(first).setOnPerformActionListener { action, _ ->
            if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) {
                first.rangeInfo = AccessibilityNodeInfoCompat.RangeInfoCompat.obtain(0, 0f, 100f, 45f)
                true
            } else false
        }
        val tts = TextToSpeech(context, null)
        val speech = SpeechController(tts, context, Reader(context))
        try {
            assertTrue(SliderController(wrapper, speech).adjust(true))
            navigation.handle(event(source = first))
            assertEquals(45f, first.rangeInfo!!.current, 0f)
            assertTrue(shadowOf(tts).spokenTextList.isNotEmpty())
            assertTrue(tracks.isEmpty())
        } finally { speech.shutdown() }
    }
    @Test fun `navigation sound is independent of haptic preference`() {
        SpeechPreferences(context).hapticNavigationFeedback = false
        move(); assertEquals(1, tracks.size)
        assertFalse(SpeechPreferences(context).hapticNavigationFeedback)
    }
    @Test fun `rapid movement replaces playback with no delayed backlog`() {
        move(); move(third); move(first)
        assertEquals(3, tracks.size)
        assertEquals(1, audio(tracks[0]).releases); assertEquals(1, audio(tracks[1]).releases)
        assertEquals(0, audio(tracks[2]).releases)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(3, tracks.size)
    }
    @Test fun `touch interruption stops current playback without another click`() {
        move(); navigation.handle(event(AccessibilityEvent.TYPE_TOUCH_INTERACTION_START))
        assertEquals(1, tracks.size); assertEquals(1, audio(tracks.single()).releases)
    }
    @Test fun `teardown cancels playback and ignores late events or marker callbacks`() {
        move(); val track = tracks.single(); val callback = audio(track).listener!!
        navigation.finish(); navigation.finish()
        move(third); callback.onMarkerReached(track)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(1, tracks.size); assertEquals(1, audio(track).releases)
        assertFalse(sounds.play())
    }
    @Test fun `reconnect starts fresh baseline without activation click`() {
        move(); navigation.finish(); navigation.onServiceConnected()
        navigation.handle(event()); assertEquals(1, tracks.size)
        move(third); assertEquals(2, tracks.size)
    }
    @Test fun `click is shorter and quieter than progress with safe buffer fades`() {
        move()
        assertEquals(512, data.single().size)
        assertEquals(256, audio(tracks.single()).marker)
        val samples = data.single().asList().chunked(2).map {
            (((it[1].toInt() and 255) shl 8) or (it[0].toInt() and 255)).toShort().toInt()
        }
        assertEquals(0, samples.first()); assertEquals(0, samples.last())
        assertTrue(samples.maxOf { kotlin.math.abs(it) } <= 1966)
        assertEquals(AudioFormat.CHANNEL_OUT_MONO, tracks.single().channelConfiguration)
        if (Build.VERSION.SDK_INT >= 29) {
            assertEquals(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY, tracks.single().audioAttributes.usage)
        }
    }
    @Test fun `movement and progress playback use independent track ownership`() {
        val progress = ProgressToneController()
        try {
            assertTrue(progress.play(0.5)); val progressTrack = tracks.single()
            move(); assertEquals(2, tracks.size)
            assertEquals(0, audio(progressTrack).releases)
            navigation.finish(); assertEquals(0, audio(progressTrack).releases)
            assertTrue(progress.play(0.8)); assertEquals(3, tracks.size)
            assertEquals(1, audio(progressTrack).releases)
        } finally { progress.stop() }
    }
    @Test fun `late completion marker cannot stop a newer movement click`() {
        move(); val old = tracks.single(); val callback = audio(old).listener!!
        move(third); callback.onMarkerReached(old)
        assertEquals(0, audio(tracks.last()).releases)
        audio(tracks.last()).listener!!.onMarkerReached(tracks.last())
        assertEquals(1, audio(tracks.last()).releases)
    }
    @Test fun `failed audio playback is contained and does not retry duplicate focus`() {
        ShadowAudioTrack.enableIllegalStateOnPlay(true)
        move(); navigation.handle(event())
        assertEquals(1, tracks.size); assertEquals(1, audio(tracks.single()).releases)
        ShadowAudioTrack.enableIllegalStateOnPlay(false)
        move(third); assertEquals(2, tracks.size)
    }
}
