package com.neo.speaktouch.controller

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.neo.speaktouch.intercepter.event.CallbackInterceptor
import com.neo.speaktouch.intercepter.event.SpeechInterceptor
import com.neo.speaktouch.intercepter.gesture.GestureInterceptor
import com.neo.speaktouch.model.Text
import com.neo.speaktouch.utils.Reader
import com.neo.speaktouch.service.SpeakTouchService
import java.time.Duration
import java.util.Locale
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowTextToSpeech
import org.robolectric.shadows.ShadowAccessibilityService
import org.robolectric.shadow.api.Shadow

@Implements(TextToSpeech::class)
class PauseSpeechShadow : ShadowTextToSpeech() {
    data class Submission(val text: String, val mode: Int, val id: String?)
    val submissions = mutableListOf<Submission>()
    var failSubmission = false
    var stops = 0
    var rateValue = 1f
    var pitchValue = 1f
    var settingsCalls = 0
    @Implementation override fun speak(text: CharSequence, mode: Int, params: Bundle?, id: String?): Int {
        submissions.add(Submission(text.toString(), mode, id))
        return if (failSubmission) TextToSpeech.ERROR else super.speak(text, mode, params, id)
    }
    @Implementation override fun stop(): Int { stops++; return super.stop() }
    @Implementation fun isSpeaking(): Boolean = lastSpokenText != null && !isStopped
    @Implementation fun setSpeechRate(value: Float): Int { rateValue = value; settingsCalls++; return TextToSpeech.SUCCESS }
    @Implementation fun setPitch(value: Float): Int { pitchValue = value; settingsCalls++; return TextToSpeech.SUCCESS }
}

@Implements(AccessibilityService::class)
class PauseServiceShadow : ShadowAccessibilityService() {
    var info = AccessibilityServiceInfo()
    @Implementation fun getServiceInfo(): AccessibilityServiceInfo = info
    @Implementation fun setServiceInfo(value: AccessibilityServiceInfo) { info = value }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27, 28, 31], shadows = [PauseSpeechShadow::class, SliderNodeShadow::class, PauseServiceShadow::class])
class PauseResumeTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var tts: TextToSpeech
    private lateinit var speech: SpeechController
    private lateinit var interceptor: SpeechInterceptor
    private lateinit var gestures: GestureInterceptor
    private lateinit var service: SliderTestService
    private lateinit var root: AccessibilityNodeInfoCompat
    private lateinit var focused: AccessibilityNodeInfoCompat
    private val engine: PauseSpeechShadow get() = Shadow.extract(tts)
    private val listener get() = engine.utteranceProgressListener
    private val submitted get() = engine.submissions
    private val id get() = submitted.last().id!!
    private fun node(text: String? = null) = AccessibilityNodeInfoCompat.wrap(AccessibilityNodeInfo.obtain()).apply {
        className = "android.view.View"; this.text = text; isVisibleToUser = true; isEnabled = true
    }
    private fun shadow(node: AccessibilityNodeInfoCompat): SliderNodeShadow = Shadow.extract(node.unwrap())
    private fun say(text: String = "One, two, three.") { speech.speak(Text(text)); listener.onStart(id) }
    private fun range(start: Int, end: Int, frame: Int = 0, callbackId: String = id) = listener.onRangeStart(callbackId, start, end, frame)
    private fun toggle() = speech.togglePauseResume()
    private fun event(type: Int) = interceptor.handle(AccessibilityEvent.obtain(type))
    private fun touchStart() = event(AccessibilityEvent.TYPE_TOUCH_INTERACTION_START)
    private fun touchEnd() = event(AccessibilityEvent.TYPE_TOUCH_INTERACTION_END)
    private fun waitForGesture() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))

    @Before fun setup() {
        tts = TextToSpeech(context, null)
        speech = SpeechController(tts, context, Reader(context))
        interceptor = SpeechInterceptor(speech)
        service = SliderTestService()
        root = node()
        focused = node("Current").apply { isAccessibilityFocused = true }
        shadow(root).addChild(focused.unwrap()); shadow(root).focused = focused.unwrap()
        shadow(focused).setRefreshReturnValue(true)
        service.root = root.unwrap()
        val wrapper = ServiceController(service)
        gestures = GestureInterceptor(FocusController(CallbackInterceptor(), wrapper), wrapper,
            SliderController(wrapper, speech), RefocusController(wrapper, speech), speech)
    }

    @Test fun `speaking single command pauses and second command restarts exact full announcement`() {
        say(); val original = id
        toggle(); assertFalse(speech.isSpeaking); assertEquals(1, submitted.size)
        toggle(); assertEquals("One, two, three.", submitted.last().text)
        assertNotEquals(original, id)
        assertTrue(submitted.all { it.mode == TextToSpeech.QUEUE_FLUSH })
    }
    @Test fun `repeated pause restart cycles always use full saved text and fresh ids`() {
        say("A long paragraph, including punctuation. 😀")
        repeat(4) { toggle(); toggle() }
        assertEquals(5, submitted.map { it.id }.toSet().size)
        assertTrue(submitted.all { it.text == "A long paragraph, including punctuation. 😀" })
    }
    @Test fun `range callbacks never affect restart text`() {
        say(); range(5, 8); range(-1, 99); toggle(); toggle()
        assertEquals("One, two, three.", submitted.last().text)
    }
    @Test fun `native gesture survives touch end and delayed delivery without cleanup timer`() {
        say(); touchStart(); assertFalse(speech.isSpeaking)
        event(AccessibilityEvent.TYPE_GESTURE_DETECTION_START)
        event(AccessibilityEvent.TYPE_GESTURE_DETECTION_END)
        touchEnd(); waitForGesture(); toggle()
        assertEquals(1, submitted.size)
        touchStart(); event(AccessibilityEvent.TYPE_GESTURE_DETECTION_START)
        event(AccessibilityEvent.TYPE_GESTURE_DETECTION_END); touchEnd(); waitForGesture(); toggle()
        assertEquals(listOf("One, two, three.", "One, two, three."), submitted.map { it.text })
    }
    @Test fun `gesture delivered before touch end also pauses without auto restarting`() {
        say(); touchStart(); event(AccessibilityEvent.TYPE_GESTURE_DETECTION_START); toggle(); touchEnd()
        assertEquals(1, submitted.size); assertFalse(speech.isSpeaking)
        toggle(); assertEquals(2, submitted.size)
    }
    @Test fun `ordinary touch stops immediately and completion discards stopped text`() {
        say(); touchStart(); assertFalse(speech.isSpeaking); touchEnd(); waitForGesture()
        toggle(); toggle(); assertEquals(1, submitted.size)
    }
    @Test fun `a later single tap cannot revive speech stopped by ordinary touch`() {
        say(); touchStart(); touchEnd()
        touchStart(); event(AccessibilityEvent.TYPE_GESTURE_DETECTION_START); touchEnd(); toggle()
        assertEquals(1, submitted.size)
    }
    @Test fun `next interaction cannot reuse cancelled native gesture text`() {
        say(); touchStart(); event(AccessibilityEvent.TYPE_GESTURE_DETECTION_START)
        event(AccessibilityEvent.TYPE_GESTURE_DETECTION_END); touchEnd()
        touchStart(); event(AccessibilityEvent.TYPE_GESTURE_DETECTION_START); touchEnd(); toggle()
        assertEquals(1, submitted.size)
    }
    @Test fun `plain touch while explicitly paused never automatically restarts`() {
        say(); toggle(); touchStart(); touchEnd(); waitForGesture()
        assertEquals(1, submitted.size); toggle(); assertEquals(2, submitted.size)
    }
    @Test fun `idle native single tap is a no-op`() {
        assertEquals(Build.VERSION.SDK_INT >= 30, gestures.handle(AccessibilityService.GESTURE_2_FINGER_SINGLE_TAP))
        toggle(); assertTrue(submitted.isEmpty()); assertEquals(0, engine.stops)
    }
    @Test fun `completed utterance cannot be restarted by later single tap`() {
        say(); listener.onDone(id); toggle(); assertEquals(1, submitted.size)
    }
    @Test fun `failed submission cannot create saved pause`() {
        engine.failSubmission = true; say(); toggle(); toggle(); assertEquals(1, submitted.size)
    }
    @Test fun `failed restart discards old pause`() {
        say(); toggle(); engine.failSubmission = true; toggle(); engine.failSubmission = false; toggle()
        assertEquals(2, submitted.size)
    }
    @Test fun `engine errors and unexpected stop discard active utterance`() {
        say(); listener.onError(id); toggle()
        say(); listener.onError(id, 1); toggle()
        say(); listener.onStop(id, true); toggle()
        assertEquals(3, submitted.size)
    }
    @Test fun `late callbacks cannot destroy explicitly saved pause`() {
        say(); val old = id; toggle()
        listener.onStop(old, true); listener.onDone(old); listener.onError(old, 1); range(10, 15, callbackId = old)
        toggle(); assertEquals("One, two, three.", submitted.last().text)
    }
    @Test fun `stale callbacks cannot alter newer playing utterance`() {
        say(); val old = id; say("Fresh")
        listener.onDone(old); listener.onError(old); listener.onStop(old, true); range(5, 8, callbackId = old)
        toggle(); toggle(); assertEquals("Fresh", submitted.last().text)
    }
    @Test fun `callbacks from background threads do not clobber replacement speech`() {
        say(); val old = id; say("Replacement")
        val threads = listOf(Thread { listener.onDone(old) }, Thread { listener.onStop(old, true) },
            Thread { listener.onError(old, 1) })
        threads.forEach { it.start() }; threads.forEach { it.join() }
        toggle(); toggle(); assertEquals("Replacement", submitted.last().text)
    }
    @Test fun `new speech cancels paused text and becomes the new saved announcement`() {
        say(); toggle(); say("New announcement")
        toggle(); toggle(); assertEquals(listOf("One, two, three.", "New announcement", "New announcement"), submitted.map { it.text })
    }
    @Test fun `new speech discards text interrupted during earlier gesture`() {
        say(); touchStart(); event(AccessibilityEvent.TYPE_GESTURE_DETECTION_START)
        say("New announcement"); toggle(); toggle()
        assertEquals("New announcement", submitted.last().text)
    }
    @Test fun `focus change cancels paused text including silent target`() {
        for (type in listOf(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED,
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED, AccessibilityEvent.TYPE_VIEW_FOCUSED)) {
            say(); toggle(); event(type); toggle()
        }
        assertEquals(3, submitted.size)
    }
    @Test fun `window state and active window changes cancel paused text`() {
        say(); toggle(); event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED); toggle()
        say(); toggle()
        val changed = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOWS_CHANGED)
        if (Build.VERSION.SDK_INT >= 28) AccessibilityEvent::class.java
            .getDeclaredMethod("setWindowChanges", Int::class.javaPrimitiveType)
            .invoke(changed, AccessibilityEvent.WINDOWS_CHANGE_ACTIVE)
        interceptor.handle(changed); toggle(); assertEquals(2, submitted.size)
    }
    @Test fun `reread cancels saved pause and reads current refreshed node`() {
        say(); toggle(); focused.text = "Updated"
        assertTrue(gestures.handle(AccessibilityService.GESTURE_SWIPE_UP_AND_DOWN))
        assertEquals("Updated", submitted.last().text)
        toggle(); toggle(); assertEquals("Updated", submitted.last().text)
    }
    @Test fun `unavailable reread still cancels old saved pause`() {
        say(); toggle(); service.root = null
        assertTrue(gestures.handle(AccessibilityService.GESTURE_SWIPE_UP_AND_DOWN))
        toggle(); assertEquals(1, submitted.size)
    }
    @Test fun `slider gesture cancels old pause and saves generated feedback`() {
        say(); toggle(); focused.className = "android.widget.SeekBar"
        focused.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_FORWARD)
        focused.rangeInfo = AccessibilityNodeInfoCompat.RangeInfoCompat.obtain(0, 0f, 100f, 40f)
        shadow(focused).setOnPerformActionListener { _, _ ->
            focused.rangeInfo = AccessibilityNodeInfoCompat.RangeInfoCompat.obtain(0, 0f, 100f, 45f); true
        }
        assertTrue(gestures.handle(AccessibilityService.GESTURE_SWIPE_UP))
        toggle(); toggle(); assertEquals("45", submitted.last().text)
    }
    @Test fun `disabled slider gesture without feedback cannot leave stale pause`() {
        say(); toggle(); focused.className = "android.widget.SeekBar"; focused.isEnabled = false
        assertTrue(gestures.handle(AccessibilityService.GESTURE_SWIPE_UP))
        toggle(); assertEquals(1, submitted.size)
    }
    @Test fun `two finger double tap remains media only and discards saved pause`() {
        say(); toggle(); touchStart(); event(AccessibilityEvent.TYPE_GESTURE_DETECTION_START); touchEnd()
        val supported = Build.VERSION.SDK_INT >= 31
        assertEquals(supported, gestures.handle(AccessibilityService.GESTURE_2_FINGER_DOUBLE_TAP))
        toggle(); assertEquals(1, submitted.size)
        assertEquals(if (supported) listOf(AccessibilityService.GLOBAL_ACTION_KEYCODE_HEADSETHOOK) else emptyList<Int>(),
            shadowOf(service).globalActionsPerformed)
    }
    @Test fun `unrelated gestures discard saved pause without changing their mapping`() {
        say(); toggle(); assertFalse(gestures.handle(AccessibilityService.GESTURE_SWIPE_LEFT_AND_UP))
        toggle(); assertEquals(1, submitted.size)
    }
    @Test fun `native single tap routes pause restart only on supported platforms`() {
        say(); val supported = Build.VERSION.SDK_INT >= 30
        assertEquals(supported, gestures.handle(AccessibilityService.GESTURE_2_FINGER_SINGLE_TAP))
        assertEquals(supported, gestures.handle(AccessibilityService.GESTURE_2_FINGER_SINGLE_TAP))
        assertEquals(if (supported) 2 else 1, submitted.size)
        if (supported) assertEquals(submitted.first().text, submitted.last().text)
    }
    @Test fun `pause restart never acts on focus clicks scrolls or navigation callbacks`() {
        say(); toggle(); toggle()
        assertTrue(shadow(root).performedActions.isEmpty()); assertTrue(shadow(focused).performedActions.isEmpty())
        assertEquals(focused.unwrap(), shadow(root).focused); assertTrue(focused.isAccessibilityFocused)
        assertTrue(shadowOf(service).globalActionsPerformed.isEmpty())
    }
    @Test fun `same engine settings remain unchanged`() {
        ShadowTextToSpeech.addLanguageAvailability(Locale.US)
        tts.setSpeechRate(0.7f); tts.setPitch(1.2f); tts.setLanguage(Locale.US)
        val voice = Voice("Test voice", Locale.US, 400, 400, false, emptySet()); tts.setVoice(voice)
        say(); toggle(); toggle()
        assertEquals(0.7f, engine.rateValue, 0f); assertEquals(1.2f, engine.pitchValue, 0f)
        assertEquals(2, engine.settingsCalls); assertEquals(Locale.US, engine.currentLanguage); assertEquals(voice, tts.voice)
    }
    @Test fun `group restart uses exact saved composition without rerunning Reader`() {
        focused.text = "Transfer"; focused.isScreenReaderFocusable = true
        val child = node("Download"); shadow(focused).addChild(child.unwrap()); shadow(focused).addChild(node("Loading").unwrap())
        speech.speak(focused); toggle(); child.text = "Changed"; toggle()
        assertEquals(listOf("Transfer, Download, Loading", "Transfer, Download, Loading"), submitted.map { it.text })
    }
    @Test fun `edit field restart preserves original label value and role`() {
        focused.className = "android.widget.EditText"; focused.isEditable = true; focused.text = "Mike"
        shadow(focused).setLabeledBy(node("Name").unwrap())
        speech.speak(focused); toggle(); focused.text = "Other"; toggle()
        assertEquals(listOf("Name, Mike, edit field", "Name, Mike, edit field"), submitted.map { it.text })
    }
    @Test fun `slider restart saves full original role and state`() {
        focused.className = "android.widget.SeekBar"; focused.text = "Test level"; focused.stateDescription = "Quiet"
        speech.speak(focused); toggle(); focused.stateDescription = "Loud"; toggle()
        assertEquals(listOf("Test level, slider, Quiet", "Test level, slider, Quiet"), submitted.map { it.text })
    }
    @Test fun `empty replacement speech stops output and clears paused text`() {
        say(); toggle(); speech.speak(Text("")); toggle(); assertEquals(1, submitted.size)
        say(); speech.speak(Text("")); assertFalse(speech.isSpeaking)
    }
    @Test fun `explicit stop and shutdown cancel saved pause`() {
        say(); toggle(); speech.stop(); toggle(); assertEquals(1, submitted.size)
        say(); toggle(); speech.shutdown(); toggle(); assertEquals(2, submitted.size)
    }
    @Test fun `service requests native gestures and retains passthrough and existing flags`() {
        val target = SpeakTouchService()
        target.serviceInfo = AccessibilityServiceInfo().apply { flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        SpeakTouchService::class.java.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }.invoke(target)
        var expected = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        if (Build.VERSION.SDK_INT >= 30) expected = expected or AccessibilityServiceInfo.FLAG_REQUEST_MULTI_FINGER_GESTURES
        if (Build.VERSION.SDK_INT >= 31) expected = expected or AccessibilityServiceInfo.FLAG_REQUEST_2_FINGER_PASSTHROUGH
        assertEquals(expected, target.serviceInfo.flags)
    }
}
