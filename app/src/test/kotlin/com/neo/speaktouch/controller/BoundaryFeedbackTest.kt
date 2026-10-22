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
import android.speech.tts.TextToSpeech
import com.neo.speaktouch.intercepter.gesture.GestureInterceptor
import com.neo.speaktouch.utils.Reader
import android.content.Context
import android.media.AudioTrack
import android.os.Looper
import android.os.Vibrator
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.neo.speaktouch.intercepter.event.CallbackInterceptor
import com.neo.speaktouch.intercepter.event.NavigationSoundInterceptor
import com.neo.speaktouch.utils.SpeechPreferences
import com.neo.speaktouch.utils.CustomLabelKey
import com.neo.speaktouch.utils.CustomLabelStore
import com.neo.speaktouch.model.NodeFilter
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
class BoundaryFeedbackTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var window: AccessibilityWindowInfo
    private lateinit var root: AccessibilityNodeInfo
    private lateinit var first: AccessibilityNodeInfo
    private lateinit var last: AccessibilityNodeInfo
    private lateinit var service: SliderTestService
    private lateinit var callbacks: CallbackInterceptor
    private lateinit var focus: FocusController
    private lateinit var boundary: BoundaryFeedbackController
    private lateinit var movement: NavigationSoundInterceptor
    private lateinit var vibrator: Vibrator
    private val tracks = mutableListOf<AudioTrack>()
    private val data = mutableListOf<ByteArray>()
    private val recorder = ShadowAudioTrack.OnAudioDataWrittenListener { track, bytes, _ ->
        tracks.add(track); data.add(bytes.copyOf())
    }
    private fun nodeShadow(node: AccessibilityNodeInfo): NavigationNodeShadow = Shadow.extract(node)
    private fun audio(track: AudioTrack): NavigationAudioShadow = Shadow.extract(track)
    private fun event(type: Int, source: AccessibilityNodeInfo? = null) = AccessibilityEvent.obtain(type).apply {
        if (source != null) shadowOf(this).setSourceNode(source)
    }
    private fun node(label: String? = null): AccessibilityNodeInfo = AccessibilityNodeInfo.obtain().apply {
        className = "android.view.View"; text = label; packageName = "test.boundary"
        isVisibleToUser = true; isEnabled = true; isFocusable = label != null
        shadowOf(this).setAccessibilityWindowInfo(this@BoundaryFeedbackTest.window)
        nodeShadow(this).setRefreshReturnValue(true)
        nodeShadow(this).setOnPerformActionListener { action, _ ->
            if (action == AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) { setFocus(this); true } else false
        }
    }
    private fun setFocus(node: AccessibilityNodeInfo) {
        nodeShadow(root).focused?.isAccessibilityFocused = false
        node.isAccessibilityFocused = true
        nodeShadow(root).focused = node
    }
    private fun prefs(sound: Boolean, haptic: Boolean) {
        SpeechPreferences(context).apply { audibleNavigationFeedback = sound; hapticNavigationFeedback = haptic }
    }
    @Before fun setup() {
        context.getSharedPreferences(SpeechPreferences.FILE_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        NavigationNodeShadow.refreshBehaviors.clear()
        prefs(true, false)
        window = AccessibilityWindowInfo.obtain().apply {
            shadowOf(this).setId(1); shadowOf(this).setType(AccessibilityWindowInfo.TYPE_APPLICATION)
        }
        root = node(); first = node("First"); last = node("Last")
        nodeShadow(root).addChild(first); nodeShadow(root).addChild(last)
        shadowOf(window).setRoot(root)
        setFocus(last)
        service = SliderTestService().apply { this.root = this@BoundaryFeedbackTest.root }
        val wrapper = ServiceController(service)
        callbacks = CallbackInterceptor(); focus = FocusController(callbacks, wrapper)
        vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        shadowOf(vibrator).setHasVibrator(true)
        boundary = BoundaryFeedbackController(focus, wrapper, VibratorController(vibrator), context)
        movement = NavigationSoundInterceptor(wrapper, NavigationSoundController(), context)
        movement.onServiceConnected()
        ShadowAudioTrack.addAudioDataListener(recorder)
        NavigationAudioShadow.api22WriteRecorder = { track, bytes -> tracks.add(track); data.add(bytes.copyOf()) }
    }
    @After fun cleanup() {
        if (::boundary.isInitialized) boundary.finish()
        if (::movement.isInitialized) movement.finish()
        if (::callbacks.isInitialized) callbacks.finish()
        if (::vibrator.isInitialized) vibrator.cancel()
        NavigationNodeShadow.refreshBehaviors.clear()
        NavigationAudioShadow.api22WriteRecorder = null
        ShadowAudioTrack.removeAudioDataListener(recorder)
    }
    private fun scrollPending(): () -> Unit {
        root.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        var available = true
        nodeShadow(root).setOnPerformActionListener { action, _ ->
            available && action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        }
        boundary.navigate(true)
        assertTrue(tracks.isEmpty())
        return { available = false; root.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD) }
    }
    private fun scrollEvent() = callbacks.handle(event(AccessibilityEvent.TYPE_VIEW_SCROLLED, root))

    private fun gestureTest(block: (GestureInterceptor) -> Unit) {
        val wrapper = ServiceController(service)
        val speech = SpeechController(TextToSpeech(context, null), context, Reader(context))
        try {
            block(GestureInterceptor(focus, wrapper, SliderController(wrapper, speech),
                RefocusController(wrapper, speech), speech, boundaryFeedback = boundary))
        } finally { speech.shutdown() }
    }
    @Test fun `right and left gestures each emit one cue at their true edge`() = gestureTest { gestures ->
        assertTrue(gestures.handle(AccessibilityService.GESTURE_SWIPE_RIGHT)); assertEquals(1, tracks.size)
        setFocus(first)
        assertTrue(gestures.handle(AccessibilityService.GESTURE_SWIPE_LEFT)); assertEquals(2, tracks.size)
    }
    @Test fun `reread cancels pending edge without producing feedback`() = gestureTest { gestures ->
        val endScroll = scrollPending()
        assertTrue(gestures.handle(AccessibilityService.GESTURE_SWIPE_UP_AND_DOWN))
        endScroll(); scrollEvent(); assertTrue(tracks.isEmpty())
    }
    @Test fun `slider gesture never emits boundary feedback`() = gestureTest { gestures ->
        last.className = "android.widget.SeekBar"
        last.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(0, 0f, 100f, 40f)
        last.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        nodeShadow(last).setOnPerformActionListener { action, _ ->
            if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) {
                last.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(0, 0f, 100f, 45f); true
            } else false
        }
        assertTrue(gestures.handle(AccessibilityService.GESTURE_SWIPE_UP))
        assertEquals(45f, last.rangeInfo.current, 0f); assertTrue(tracks.isEmpty())
    }
    @Test fun `navigation outcome distinguishes accepted focus from exhaustion and rejected action`() {
        setFocus(first)
        assertEquals(FocusController.NavigationOutcome.FOCUS_REQUESTED, focus.moveFocusToNext())
        assertEquals(FocusController.NavigationOutcome.EXHAUSTED, focus.moveFocusToNext())
        setFocus(first); nodeShadow(last).setOnPerformActionListener { _, _ -> false }
        assertEquals(FocusController.NavigationOutcome.ACTION_FAILED, focus.moveFocusToNext())
    }
    @Test fun `haptic boundary waveform is distinct and non repeating`() {
        prefs(false, true); boundary.navigate(true)
        // Newer composed effects omit zero-duration segments.
        assertArrayEquals(longArrayOf(18, 30, 18), shadowOf(vibrator).pattern.filter { it > 0 }.toLongArray())
        assertEquals(-1, shadowOf(vibrator).repeat)
    }
    @Test fun `changed window ID rejects pending retry`() {
        assertEquals(1, root.windowId); assertEquals(1, last.windowId)
        val endScroll = scrollPending()
        // Deliberately bypass a window-change event to test the retry guard itself.
        shadowOf(window).setId(2)
        assertEquals(2, root.windowId); assertEquals(2, last.windowId)
        val completed = event(AccessibilityEvent.TYPE_VIEW_SCROLLED, root)
        shadowOf(completed).setWindowId(2)
        assertEquals(2, completed.windowId)
        assertEquals(2, completed.source!!.windowId)
        val actions = nodeShadow(root).performedActions.toList()
        endScroll(); callbacks.handle(completed)
        assertEquals(actions, nodeShadow(root).performedActions)
        assertEquals(last, nodeShadow(root).focused)
        assertTrue(tracks.isEmpty())
        // Even if the old window ID returns, a late event must not revive the request.
        shadowOf(window).setId(1)
        val more = node("More"); nodeShadow(root).addChild(more)
        scrollEvent()
        assertEquals(last, nodeShadow(root).focused)
        assertFalse(nodeShadow(more).performedActions.contains(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
        assertTrue(tracks.isEmpty())
    }
    @Test fun `first exhaustion cues without moving focus and second exhaustion wraps forward`() {
        boundary.navigate(true)
        assertEquals(1, tracks.size); assertEquals(last, nodeShadow(root).focused)
        assertFalse(nodeShadow(first).performedActions.contains(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
        boundary.navigate(true)
        assertEquals(first, nodeShadow(root).focused)
        assertEquals(1, tracks.size)
        assertEquals(1, nodeShadow(first).performedActions.count { it == AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS })
    }
    @Test fun `backward second exhaustion wraps to last eligible target`() {
        setFocus(first)
        boundary.navigate(false)
        assertEquals(first, nodeShadow(root).focused); assertEquals(1, tracks.size)
        boundary.navigate(false)
        assertEquals(last, nodeShadow(root).focused); assertEquals(1, tracks.size)
    }
    @Test fun `accepted unchanged forward scroll cues then second request wraps despite advertised action`() {
        var scrolls = 0
        root.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        nodeShadow(root).setOnPerformActionListener { action, _ ->
            if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) { scrolls++; true } else false
        }
        boundary.navigate(true)
        assertEquals(1, scrolls); assertTrue(tracks.isEmpty())
        scrollEvent()
        assertEquals(1, scrolls); assertEquals(1, tracks.size)
        assertEquals(last, nodeShadow(root).focused)
        boundary.navigate(true)
        assertEquals(first, nodeShadow(root).focused)
        assertEquals(1, scrolls); assertEquals(1, tracks.size)
    }
    @Test fun `window cancellation discards completed no progress proof`() {
        var scrolls = 0
        root.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        nodeShadow(root).setOnPerformActionListener { action, _ ->
            if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) { scrolls++; true } else false
        }
        boundary.navigate(true); scrollEvent(); assertEquals(1, tracks.size)
        boundary.handle(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED))
        boundary.navigate(true)
        assertEquals(2, scrolls); assertEquals(last, nodeShadow(root).focused)
        assertEquals(1, tracks.size)
    }
    @Test fun `wrap click is produced once only by confirmed focus event`() {
        boundary.navigate(true); boundary.navigate(true)
        assertEquals(1, tracks.size)
        val focused = event(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED, first)
        boundary.handle(focused); movement.handle(focused); movement.handle(focused)
        assertEquals(2, tracks.size)
        assertEquals(NavigationSoundController.FRAMES * 2, data.last().size)
    }
    @Test fun `reverse direction discards previously armed edge`() {
        boundary.navigate(true)
        nodeShadow(first).setOnPerformActionListener { _, _ -> false }
        boundary.navigate(false)
        assertEquals(last, nodeShadow(root).focused)
        boundary.navigate(true)
        assertEquals(last, nodeShadow(root).focused); assertEquals(2, tracks.size)
    }
    @Test fun `opposite direction exhaustion is a fresh edge even at the same node`() {
        first.isVisibleToUser = false
        boundary.navigate(true)
        assertEquals(1, tracks.size)
        boundary.navigate(false)
        assertEquals(last, nodeShadow(root).focused); assertEquals(2, tracks.size)
    }
    @Test fun `focus change away and back cancels armed edge`() {
        boundary.navigate(true)
        setFocus(first); boundary.handle(event(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED, first))
        setFocus(last); boundary.handle(event(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED, last))
        boundary.navigate(true)
        assertEquals(last, nodeShadow(root).focused); assertEquals(2, tracks.size)
    }
    @Test fun `duplicate focus event on armed origin does not count as a focus change`() {
        boundary.navigate(true)
        boundary.handle(event(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED, last))
        boundary.navigate(true)
        assertEquals(first, nodeShadow(root).focused); assertEquals(1, tracks.size)
    }
    @Test fun `window event clears armed edge even when original focus returns`() {
        boundary.navigate(true)
        boundary.handle(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED))
        boundary.navigate(true)
        assertEquals(last, nodeShadow(root).focused); assertEquals(2, tracks.size)
    }
    @Test fun `root replacement without window event cannot reuse armed edge`() {
        boundary.navigate(true)
        val replacement = node()
        val other = node("Other")
        nodeShadow(replacement).addChild(other)
        nodeShadow(replacement).focused = other; other.isAccessibilityFocused = true
        service.root = replacement
        boundary.navigate(true)
        assertEquals(other, nodeShadow(replacement).focused); assertEquals(2, tracks.size)
    }
    @Test fun `window ID change without an event cannot reuse armed edge`() {
        boundary.navigate(true)
        shadowOf(window).setId(2)
        boundary.navigate(true)
        assertEquals(last, nodeShadow(root).focused); assertEquals(2, tracks.size)
        shadowOf(window).setId(1)
        boundary.navigate(true)
        assertEquals(last, nodeShadow(root).focused); assertEquals(3, tracks.size)
    }
    @Test fun `successful ordinary navigation clears armed edge`() {
        boundary.navigate(true)
        val arrived = node("Arrived")
        nodeShadow(root).addChild(arrived)
        boundary.navigate(true)
        assertEquals(arrived, nodeShadow(root).focused); assertEquals(1, tracks.size)
        arrived.isVisibleToUser = false; setFocus(last)
        boundary.navigate(true)
        assertEquals(last, nodeShadow(root).focused); assertEquals(2, tracks.size)
    }
    @Test fun `pending scroll cancels armed edge and never wraps from pending outcome`() {
        boundary.navigate(true)
        root.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        nodeShadow(root).setOnPerformActionListener { action, _ -> action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD }
        boundary.navigate(true)
        assertEquals(last, nodeShadow(root).focused); assertEquals(1, tracks.size)
        root.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        nodeShadow(root).setOnPerformActionListener { _, _ -> false }
        scrollEvent()
        assertEquals(last, nodeShadow(root).focused); assertEquals(2, tracks.size)
    }
    @Test fun `advertised failed scroll clears armed edge without wrapping`() {
        boundary.navigate(true)
        root.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        boundary.navigate(true)
        assertEquals(last, nodeShadow(root).focused); assertEquals(1, tracks.size)
        root.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        boundary.navigate(true)
        assertEquals(last, nodeShadow(root).focused); assertEquals(2, tracks.size)
    }
    @Test fun `invalid origin clears armed edge`() {
        boundary.navigate(true)
        last.isVisibleToUser = false; boundary.navigate(true)
        assertEquals(1, tracks.size)
        last.isVisibleToUser = true; boundary.navigate(true)
        assertEquals(last, nodeShadow(root).focused); assertEquals(2, tracks.size)
    }
    @Test fun `navigation context change clears armed edge`() {
        boundary.navigate(true)
        boundary.handle(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, root))
        boundary.navigate(true)
        assertEquals(last, nodeShadow(root).focused); assertEquals(2, tracks.size)
    }
    @Test fun `scroll context change clears armed edge`() {
        boundary.navigate(true)
        boundary.handle(event(AccessibilityEvent.TYPE_VIEW_SCROLLED, root))
        boundary.navigate(true)
        assertEquals(last, nodeShadow(root).focused); assertEquals(2, tracks.size)
    }
    @Test fun `second swipe touch and gesture lifecycle preserves the proven edge`() = gestureTest { gestures ->
        gestures.handle(AccessibilityService.GESTURE_SWIPE_RIGHT)
        boundary.handle(event(AccessibilityEvent.TYPE_TOUCH_INTERACTION_START))
        boundary.handle(event(AccessibilityEvent.TYPE_GESTURE_DETECTION_START))
        boundary.handle(event(AccessibilityEvent.TYPE_GESTURE_DETECTION_END))
        boundary.handle(event(AccessibilityEvent.TYPE_TOUCH_INTERACTION_END))
        gestures.handle(AccessibilityService.GESTURE_SWIPE_RIGHT)
        assertEquals(first, nodeShadow(root).focused); assertEquals(1, tracks.size)
    }
    @Test fun `unrelated reread gesture cancels armed edge`() = gestureTest { gestures ->
        gestures.handle(AccessibilityService.GESTURE_SWIPE_RIGHT)
        gestures.handle(AccessibilityService.GESTURE_SWIPE_UP_AND_DOWN)
        gestures.handle(AccessibilityService.GESTURE_SWIPE_RIGHT)
        assertEquals(last, nodeShadow(root).focused); assertEquals(2, tracks.size)
    }
    @Test fun `forward wrap uses resolved traversal order`() {
        nodeShadow(last).setTraversalBefore(first)
        setFocus(first)
        boundary.navigate(true); boundary.navigate(true)
        assertEquals(last, nodeShadow(root).focused); assertEquals(1, tracks.size)
    }
    @Test fun `backward wrap uses resolved traversal order`() {
        nodeShadow(last).setTraversalBefore(first)
        setFocus(last)
        boundary.navigate(false); boundary.navigate(false)
        assertEquals(first, nodeShadow(root).focused); assertEquals(1, tracks.size)
    }
    @Test fun `wrap skips invisible and passive blank nodes`() {
        first.isVisibleToUser = false
        nodeShadow(root).addChild(node())
        val end = node("End"); nodeShadow(root).addChild(end); setFocus(end)
        boundary.navigate(true); boundary.navigate(true)
        assertEquals(last, nodeShadow(root).focused); assertEquals(1, tracks.size)
    }
    private fun backwardWrapWithTail(tail: AccessibilityNodeInfo) {
        nodeShadow(root).addChild(tail); setFocus(first)
        boundary.navigate(false)
        assertEquals(first, nodeShadow(root).focused); assertEquals(1, tracks.size)
        boundary.navigate(false)
    }
    @Test fun `backward wrap skips empty passive input focusable leaf after meaningful content`() {
        val passive = node().apply { isFocusable = true }
        // #88 ordinary eligibility is deliberately unchanged; this is a wrap-only policy.
        assertTrue(NodeFilter.Focusable.filter(passive))
        assertEquals("", Reader(context).read(AccessibilityNodeInfoCompat.wrap(passive)))
        backwardWrapWithTail(passive)
        assertEquals(last, nodeShadow(root).focused)
        assertFalse(nodeShadow(passive).performedActions.contains(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
    }
    @Test fun `focus actions alone do not make a blank wrap destination usable`() {
        val passive = node().apply {
            isFocusable = true
            addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_FOCUS)
            addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS)
        }
        backwardWrapWithTail(passive)
        assertEquals(last, nodeShadow(root).focused)
    }
    @Test fun `forward wrap skips empty input focusable leaf before meaningful content`() {
        first.text = null; first.isFocusable = true
        val end = node("End"); nodeShadow(root).addChild(end); setFocus(end)
        boundary.navigate(true); boundary.navigate(true)
        assertEquals(last, nodeShadow(root).focused)
        assertFalse(nodeShadow(first).performedActions.contains(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
    }
    @Test fun `unlabeled clickable leaf remains a usable backward wrap destination`() {
        val control = node().apply { isClickable = true }
        backwardWrapWithTail(control)
        assertEquals(control, nodeShadow(root).focused)
    }
    @Test fun `unlabeled long clickable leaf remains a usable backward wrap destination`() {
        val control = node().apply { isLongClickable = true }
        backwardWrapWithTail(control)
        assertEquals(control, nodeShadow(root).focused)
    }
    @Test fun `unlabeled button retains role based wrap eligibility without click flags`() {
        val control = node().apply { className = "android.widget.Button"; isFocusable = true }
        backwardWrapWithTail(control)
        assertEquals(control, nodeShadow(root).focused)
    }
    @Test fun `advertised click preserves unlabeled actionable wrap destination`() {
        val control = node().apply {
            isFocusable = true; addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
        }
        backwardWrapWithTail(control)
        assertEquals(control, nodeShadow(root).focused)
    }
    @Test fun `labeled custom action preserves unlabeled actionable wrap destination`() {
        val control = node().apply {
            isFocusable = true; addAction(AccessibilityNodeInfo.AccessibilityAction(0x7f0a0010, "Retry"))
        }
        backwardWrapWithTail(control)
        assertEquals(control, nodeShadow(root).focused)
        assertFalse(nodeShadow(control).performedActions.contains(0x7f0a0010))
    }
    @Test fun `custom label makes otherwise blank focusable destination usable`() {
        val control = node().apply { isFocusable = true; viewIdResourceName = "test.boundary:id/labelled" }
        val store = CustomLabelStore(context)
        val key = CustomLabelKey.from(AccessibilityNodeInfoCompat.wrap(control))!!
        assertTrue(store.put(key, "Saved label"))
        try {
            backwardWrapWithTail(control)
            assertEquals(control, nodeShadow(root).focused)
        } finally { store.remove(key) }
    }
    @Test fun `grouped readable content remains a usable wrap destination`() {
        val group = node().apply { isFocusable = true }
        nodeShadow(group).addChild(node("Group content").apply { isFocusable = false })
        backwardWrapWithTail(group)
        assertEquals(group, nodeShadow(root).focused)
    }
    @Test fun `window edge focus helper performs no scroll actions`() {
        root.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        val before = nodeShadow(root).performedActions.toList()
        assertEquals(FocusController.NavigationOutcome.FOCUS_REQUESTED,
            focus.wrapFocusToWindowEdge(root, last, true) { true })
        assertEquals(before, nodeShadow(root).performedActions)
        assertEquals(first, nodeShadow(root).focused); assertTrue(tracks.isEmpty())
    }
    @Test fun `wrap helper rejects changed context and performs no action`() {
        assertEquals(FocusController.NavigationOutcome.UNAVAILABLE,
            focus.wrapFocusToWindowEdge(root, last, true) { false })
        assertEquals(last, nodeShadow(root).focused)
        assertFalse(nodeShadow(first).performedActions.contains(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
    }
    @Test fun `failed wrap focus does not cue again or retain the arm`() {
        boundary.navigate(true)
        nodeShadow(first).setOnPerformActionListener { _, _ -> false }
        boundary.navigate(true)
        assertEquals(last, nodeShadow(root).focused); assertEquals(1, tracks.size)
        boundary.navigate(true)
        assertEquals(last, nodeShadow(root).focused); assertEquals(2, tracks.size)
    }
    @Test fun `one eligible node is not re focused by wrap`() {
        first.isVisibleToUser = false
        boundary.navigate(true); boundary.navigate(true)
        assertEquals(last, nodeShadow(root).focused); assertEquals(1, tracks.size)
        assertFalse(nodeShadow(last).performedActions.contains(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
    }
    @Test fun `wrap remains available when both feedback preferences are off`() {
        prefs(false, false)
        boundary.navigate(true); boundary.navigate(true)
        assertEquals(first, nodeShadow(root).focused)
        assertTrue(tracks.isEmpty()); assertFalse(shadowOf(vibrator).isVibrating)
    }
    @Test fun `next at final item emits one boundary cue`() {
        boundary.navigate(true); assertEquals(1, tracks.size)
        assertArrayEquals(BoundaryFeedbackController.PCM, data.single())
    }
    @Test fun `previous at first item emits one boundary cue`() {
        setFocus(first); boundary.navigate(false); assertEquals(1, tracks.size)
    }
    @Test fun `successful navigation emits only confirmed movement click`() {
        setFocus(first); movement.onServiceConnected()
        boundary.navigate(true); assertTrue(tracks.isEmpty())
        movement.handle(event(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED, last))
        assertEquals(1, tracks.size)
        assertEquals(NavigationSoundController.FRAMES * 2, data.single().size)
    }
    @Test fun `pending scroll never turns elapsed time into a boundary`() {
        scrollPending(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(1))
        assertTrue(tracks.isEmpty())
    }
    @Test fun `completed scroll with new target navigates without boundary`() {
        val endScroll = scrollPending(); val more = node("More"); nodeShadow(root).addChild(more)
        endScroll(); scrollEvent(); assertEquals(more, nodeShadow(root).focused)
        assertTrue(tracks.isEmpty())
        movement.handle(event(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED, more))
        assertEquals(1, tracks.size); assertEquals(512, data.single().size)
    }
    @Test fun `completed scroll retry confirming end emits exactly one boundary`() {
        val endScroll = scrollPending(); endScroll(); scrollEvent(); scrollEvent()
        assertEquals(1, tracks.size)
    }
    @Test fun `stale focused target emits no false edge`() {
        nodeShadow(last).setRefreshReturnValue(false); boundary.navigate(true); assertTrue(tracks.isEmpty())
    }
    @Test fun `invisible target emits no false edge`() {
        last.isVisibleToUser = false; boundary.navigate(true); assertTrue(tracks.isEmpty())
    }
    @Test fun `refresh exception contained without feedback`() {
        NavigationNodeShadow.refreshBehaviors[last] = { throw IllegalStateException("Stale") }
        boundary.navigate(true); assertTrue(tracks.isEmpty())
    }
    @Test fun `content arriving during final refresh is not a boundary`() {
        var added = false
        NavigationNodeShadow.refreshBehaviors[root] = {
            if (!added) { added = true; nodeShadow(root).addChild(node("Arrived")) }
            true
        }
        boundary.navigate(true); assertTrue(added); assertTrue(tracks.isEmpty())
    }
    @Test fun `scroll continuation appearing during refresh prevents edge cue`() {
        NavigationNodeShadow.refreshBehaviors[root] = {
            root.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD); true
        }
        boundary.navigate(true); assertTrue(tracks.isEmpty())
    }    @Test fun `stale child cannot establish complete traversal boundary`() {
        nodeShadow(first).setRefreshReturnValue(false); boundary.navigate(true); assertTrue(tracks.isEmpty())
    }
    @Test fun `failed focus request is not a boundary`() {
        setFocus(first); nodeShadow(last).setOnPerformActionListener { _, _ -> false }
        boundary.navigate(true); assertTrue(tracks.isEmpty())
    }
    @Test fun `advertised but rejected scroll is not a boundary`() {
        root.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        boundary.navigate(true); assertTrue(tracks.isEmpty())
    }
    @Test fun `window change cancels callback and late boundary`() {
        val endScroll = scrollPending(); boundary.handle(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED))
        endScroll(); scrollEvent(); assertTrue(tracks.isEmpty())
    }
    @Test fun `active root replacement rejects pending retry even without event`() {
        val endScroll = scrollPending(); service.root = node(); endScroll(); scrollEvent()
        assertTrue(tracks.isEmpty())
    }
    @Test fun `focus change cancels pending callback`() {
        val endScroll = scrollPending(); setFocus(first)
        boundary.handle(event(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED, first))
        endScroll(); scrollEvent(); assertTrue(tracks.isEmpty()); assertEquals(first, nodeShadow(root).focused)
    }
    @Test fun `new request replaces old callback ownership`() {
        val endScroll = scrollPending(); boundary.navigate(true); endScroll(); scrollEvent(); scrollEvent()
        assertEquals(1, tracks.size)
    }
    @Test fun `rapid navigation alternates edge cue wrap and normal movement without backlog`() {
        boundary.navigate(true) // cue at Last
        boundary.navigate(true) // wrap to First
        assertEquals(first, nodeShadow(root).focused)
        boundary.navigate(true) // ordinary navigation to Last
        boundary.navigate(true) // cue at Last again
        assertEquals(last, nodeShadow(root).focused)
        assertEquals(2, tracks.size)
        assertEquals(1, audio(tracks.first()).releases)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(2, tracks.size)
    }
    @Test fun `audible off and haptic on are independent`() {
        prefs(false, true); boundary.navigate(true); assertTrue(tracks.isEmpty()); assertTrue(shadowOf(vibrator).isVibrating)
    }
    @Test fun `audible on and haptic off are independent`() {
        prefs(true, false); boundary.navigate(true); assertEquals(1, tracks.size); assertFalse(shadowOf(vibrator).isVibrating)
    }
    @Test fun `both feedback channels enabled`() {
        prefs(true, true); boundary.navigate(true); assertEquals(1, tracks.size); assertTrue(shadowOf(vibrator).isVibrating)
    }
    @Test fun `both feedback channels disabled`() {
        prefs(false, false); boundary.navigate(true); assertTrue(tracks.isEmpty()); assertFalse(shadowOf(vibrator).isVibrating)
    }
    @Test fun `preference changes are live`() {
        prefs(false, false); boundary.navigate(true); assertTrue(tracks.isEmpty())
        // Start a fresh edge in each phase; the second stage itself emits no boundary cue.
        boundary.cancel(); prefs(true, false); boundary.navigate(true); assertEquals(1, tracks.size)
        boundary.cancel(); prefs(false, true); boundary.navigate(true); assertEquals(1, tracks.size); assertTrue(shadowOf(vibrator).isVibrating)
    }
    @Test fun `teardown cancels callback and late completion`() {
        val endScroll = scrollPending(); boundary.finish(); endScroll(); scrollEvent(); boundary.navigate(true)
        assertTrue(tracks.isEmpty())
    }
    @Test fun `progress playback has independent ownership and remains unchanged`() {
        val progress = ProgressToneController()
        try {
            assertTrue(progress.play(0.5)); val track = tracks.single()
            boundary.navigate(true); boundary.finish(); assertEquals(0, audio(track).releases)
            assertEquals(ProgressToneController.SAMPLE_RATE * ProgressToneController.DURATION_MS / 1000 * 2, data.first().size)
        } finally { progress.stop() }
    }
    @Test fun `boundary PCM is two lower pitched faded pulses separated by silence`() {
        val samples = BoundaryFeedbackController.PCM.toList().chunked(2).map {
            (it[0].toInt() and 255) or (it[1].toInt() shl 8)
        }
        assertEquals(1600, samples.size)
        assertTrue(samples.subList(640, 960).all { it == 0 })
        assertEquals(0, samples.first()); assertEquals(0, samples[639]); assertEquals(0, samples[960]); assertEquals(0, samples.last())
        assertTrue(samples.maxOf { kotlin.math.abs(it) } in 2600..2621)
        fun crossings(part: List<Int>) = part.zipWithNext().count { (a, b) -> a <= 0 && b > 0 }
        assertEquals(crossings(samples.take(640)), crossings(samples.drop(960)))
    }
    @Test fun `ordered traversal exhaustion uses same boundary path`() {
        nodeShadow(last).setTraversalBefore(first)
        setFocus(first); boundary.navigate(true); assertEquals(1, tracks.size)
        setFocus(last); boundary.navigate(false); assertEquals(2, tracks.size)
    }
    private fun headingGestureTest(accepted: Boolean = false,
        block: (GestureInterceptor, TextToSpeech, NavigationGranularityController) -> Unit) {
        val web = node("Document").apply { className = "android.webkit.WebView" }
        for (target in listOf(web, last)) {
            target.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_NEXT_HTML_ELEMENT)
            target.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_PREVIOUS_HTML_ELEMENT)
        }
        nodeShadow(root).addChild(web); nodeShadow(web).addChild(last)
        nodeShadow(last).setOnPerformActionListener { action, args ->
            assertTrue(action == AccessibilityNodeInfo.ACTION_NEXT_HTML_ELEMENT || action == AccessibilityNodeInfo.ACTION_PREVIOUS_HTML_ELEMENT)
            assertEquals("HEADING", args!!.getString(AccessibilityNodeInfo.ACTION_ARGUMENT_HTML_ELEMENT_STRING)); accepted
        }
        val wrapper = ServiceController(service)
        val tts = TextToSpeech(context, null)
        val speech = SpeechController(tts, context, Reader(context))
        val granularity = NavigationGranularityController(wrapper, speech, callbacks, context)
        granularity.start(); granularity.cycle(true)
        assertEquals(NavigationGranularityController.Mode.HEADINGS, granularity.selectedMode)
        try {
            block(GestureInterceptor(focus, wrapper, SliderController(wrapper, speech),
                RefocusController(wrapper, speech), speech, boundaryFeedback = boundary, granularity = granularity), tts, granularity)
        } finally { granularity.finish(); speech.shutdown() }
    }
    @Test fun `heading boundaries speak and play exactly one existing tone in both directions`() = headingGestureTest { gestures, tts, granularity ->
        val count = shadowOf(tts).spokenTextList.size
        gestures.handle(AccessibilityService.GESTURE_SWIPE_DOWN)
        assertEquals(1, tracks.size); assertArrayEquals(BoundaryFeedbackController.PCM, data.single())
        assertEquals(context.getString(com.neo.speaktouch.R.string.navigation_no_next_heading), shadowOf(tts).spokenTextList.last())
        gestures.handle(AccessibilityService.GESTURE_SWIPE_UP)
        assertEquals(2, tracks.size); assertEquals(count + 2, shadowOf(tts).spokenTextList.size)
        assertEquals(context.getString(com.neo.speaktouch.R.string.navigation_no_previous_heading), shadowOf(tts).spokenTextList.last())
        assertEquals(last, nodeShadow(root).focused)
        assertEquals(NavigationGranularityController.Mode.HEADINGS, granularity.selectedMode)
        assertFalse(nodeShadow(last).performedActions.contains(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2)); assertEquals(2, tracks.size)
    }
    @Test fun `accepted heading with delayed focus never emits a premature boundary`() = headingGestureTest(accepted = true) { gestures, tts, _ ->
        val count = shadowOf(tts).spokenTextList.size
        gestures.handle(AccessibilityService.GESTURE_SWIPE_DOWN)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertTrue(tracks.isEmpty()); assertEquals(count, shadowOf(tts).spokenTextList.size)
        setFocus(first); assertTrue(tracks.isEmpty())
    }
    @Test fun `stale heading target produces neither speech nor a boundary tone`() = headingGestureTest { gestures, tts, _ ->
        val count = shadowOf(tts).spokenTextList.size
        nodeShadow(last).setRefreshReturnValue(false); gestures.handle(AccessibilityService.GESTURE_SWIPE_DOWN)
        assertTrue(tracks.isEmpty()); assertEquals(count, shadowOf(tts).spokenTextList.size)
    }
    @Test fun `missing heading action produces neither speech nor a boundary tone`() = headingGestureTest { gestures, tts, _ ->
        val count = shadowOf(tts).spokenTextList.size
        last.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_NEXT_HTML_ELEMENT)
        gestures.handle(AccessibilityService.GESTURE_SWIPE_DOWN)
        assertTrue(tracks.isEmpty()); assertEquals(count, shadowOf(tts).spokenTextList.size)
    }
    @Test fun `heading boundary speech remains audible when navigation sounds are disabled`() = headingGestureTest { gestures, tts, _ ->
        prefs(false, true); gestures.handle(AccessibilityService.GESTURE_SWIPE_DOWN)
        assertTrue(tracks.isEmpty()); assertFalse(shadowOf(vibrator).isVibrating)
        assertEquals(context.getString(com.neo.speaktouch.R.string.navigation_no_next_heading), shadowOf(tts).spokenTextList.last())
    }
    @Test fun `repeated heading boundary attempts replace sound without wrapping or backlog`() = headingGestureTest { gestures, tts, _ ->
        val count = shadowOf(tts).spokenTextList.size
        repeat(3) { gestures.handle(AccessibilityService.GESTURE_SWIPE_DOWN) }
        assertEquals(3, tracks.size); assertEquals(count + 3, shadowOf(tts).spokenTextList.size)
        assertEquals(1, audio(tracks.first()).releases); assertEquals(last, nodeShadow(root).focused)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2)); assertEquals(3, tracks.size)
    }
    @Test fun `standalone boundary sound cancels old wrap arming without adding haptics`() {
        boundary.navigate(true); assertEquals(1, tracks.size)
        boundary.playBoundarySound(); assertEquals(2, tracks.size)
        boundary.navigate(true); assertEquals(3, tracks.size); assertEquals(last, nodeShadow(root).focused)
        assertFalse(shadowOf(vibrator).isVibrating)
    }
    @Test fun `standalone boundary sound is unavailable after teardown`() {
        boundary.finish(); boundary.playBoundarySound(); assertTrue(tracks.isEmpty())
    }

}
