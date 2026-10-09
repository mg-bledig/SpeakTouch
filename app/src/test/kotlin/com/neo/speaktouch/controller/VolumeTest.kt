package com.neo.speaktouch.controller

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.neo.speaktouch.intercepter.event.CallbackInterceptor
import com.neo.speaktouch.intercepter.gesture.GestureInterceptor
import com.neo.speaktouch.model.Text
import com.neo.speaktouch.service.SpeakTouchService
import com.neo.speaktouch.utils.Reader
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
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
import org.robolectric.shadows.ShadowAudioManager
import org.robolectric.shadows.ShadowTextToSpeech
import org.robolectric.shadow.api.Shadow

@Implements(AudioManager::class)
class VolumeAudioShadow : ShadowAudioManager() {
    data class Adjustment(val stream: Int, val direction: Int, val flags: Int)
    val adjustments = mutableListOf<Adjustment>()
    var deny = false
    @Implementation override fun adjustStreamVolume(stream: Int, direction: Int, flags: Int) {
        adjustments.add(Adjustment(stream, direction, flags))
        if (deny) throw SecurityException("Test denial")
    }
}

@Implements(TextToSpeech::class)
class VolumeSpeechShadow : ShadowTextToSpeech() {
    var busy = false
    var fail = false
    val ids = mutableListOf<String?>()
    @Implementation fun isSpeaking(): Boolean = busy
    @Implementation override fun speak(text: CharSequence, mode: Int, params: Bundle?, id: String?): Int {
        ids.add(id)
        if (fail) return TextToSpeech.ERROR
        busy = true
        return super.speak(text, mode, params, id)
    }
    @Implementation override fun stop(): Int { busy = false; return super.stop() }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 28, 31], shadows = [VolumeAudioShadow::class, VolumeSpeechShadow::class,
    PauseServiceShadow::class, SliderNodeShadow::class])
class VolumeTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var tts: TextToSpeech
    private lateinit var speech: SpeechController
    private lateinit var volume: VolumeController
    private lateinit var audio: VolumeAudioShadow
    private val engine: VolumeSpeechShadow get() = Shadow.extract(tts)
    private val listener get() = engine.utteranceProgressListener
    private val id get() = engine.ids.last()!!
    private val calls get() = audio.adjustments
    private fun say(text: String = "Announcement") { speech.speak(Text(text)); listener.onStart(id) }
    private fun key(action: Int, code: Int = KeyEvent.KEYCODE_VOLUME_UP, repeat: Int = 0,
        time: Long = 10, device: Int = 1, flags: Int = 0) =
        KeyEvent(time, time + 5, action, code, repeat, 0, device, 0, flags)
    private fun down(code: Int = KeyEvent.KEYCODE_VOLUME_UP, repeat: Int = 0, time: Long = 10,
        device: Int = 1, flags: Int = 0) = volume.handle(key(KeyEvent.ACTION_DOWN, code, repeat, time, device, flags))
    private fun up(code: Int = KeyEvent.KEYCODE_VOLUME_UP, time: Long = 10, device: Int = 1, flags: Int = 0) =
        volume.handle(key(KeyEvent.ACTION_UP, code, time = time, device = device, flags = flags))

    @Before fun setup() {
        tts = TextToSpeech(context, null)
        speech = SpeechController(tts, context, Reader(context))
        audio = Shadow.extract(context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
        volume = VolumeController(context, speech)
    }

    @Test fun `volume up and down target only accessibility with flags zero`() {
        say(); assertTrue(down()); assertTrue(up())
        assertTrue(down(KeyEvent.KEYCODE_VOLUME_DOWN)); assertTrue(up(KeyEvent.KEYCODE_VOLUME_DOWN))
        assertEquals(listOf(VolumeAudioShadow.Adjustment(AudioManager.STREAM_ACCESSIBILITY, AudioManager.ADJUST_RAISE, 0),
            VolumeAudioShadow.Adjustment(AudioManager.STREAM_ACCESSIBILITY, AudioManager.ADJUST_LOWER, 0)), calls)
    }
    @Test fun `held repeats adjust once each and key up does not adjust`() {
        say(); assertTrue(down()); assertTrue(down(repeat = 1, flags = KeyEvent.FLAG_LONG_PRESS)); assertTrue(down(repeat = 2))
        assertTrue(up()); assertEquals(3, calls.size)
    }
    @Test fun `silent fresh press and its repeats and up pass through`() {
        assertFalse(down()); assertFalse(down(repeat = 1)); assertFalse(up()); assertTrue(calls.isEmpty())
    }
    @Test fun `paused fresh press passes through even with stale busy engine`() {
        say(); speech.togglePauseResume(); engine.busy = true
        assertFalse(speech.isActivelySpeaking)
        assertFalse(down()); assertFalse(down(repeat = 1)); assertFalse(up()); assertTrue(calls.isEmpty())
    }
    @Test fun `restart speech makes fresh presses eligible without changing saved text`() {
        say("Saved paragraph"); speech.togglePauseResume(); speech.togglePauseResume()
        assertTrue(speech.isActivelySpeaking); assertTrue(down()); assertTrue(up())
        assertEquals(listOf("Saved paragraph", "Saved paragraph"), engine.spokenTextList)
    }
    @Test fun `completion during hold stops adjustments but keeps ownership until up`() {
        say(); assertTrue(down()); listener.onDone(id)
        assertTrue(down(repeat = 1)); assertTrue(up()); assertEquals(1, calls.size)
        assertFalse(down(time = 20)); assertFalse(up(time = 20))
    }
    @Test fun `pause during hold preserves ownership but stops repeat adjustments`() {
        say(); assertTrue(down()); speech.togglePauseResume()
        assertTrue(down(repeat = 1)); assertTrue(up()); assertEquals(1, calls.size)
        assertFalse(down(time = 20)); assertFalse(up(time = 20))
    }
    @Test fun `speech beginning during silent hold cannot steal repeats or duplicate down`() {
        assertFalse(down()); say(); assertFalse(down(repeat = 1)); assertFalse(down()); assertFalse(up())
        assertTrue(calls.isEmpty()); assertTrue(down(time = 20))
    }
    @Test fun `completed errored and stopped speech return fresh presses to normal`() {
        say(); listener.onDone(id); assertFalse(down(time = 10)); assertFalse(up(time = 10))
        say(); listener.onError(id, 1); assertFalse(down(time = 20)); assertFalse(up(time = 20))
        say(); speech.stop(); assertFalse(down(time = 30)); assertFalse(up(time = 30))
        assertTrue(calls.isEmpty())
    }
    @Test fun `stale tracked submission with silent TTS is ineligible`() {
        say(); engine.busy = false
        assertFalse(speech.isActivelySpeaking); assertFalse(down()); assertFalse(up()); assertTrue(calls.isEmpty())
    }
    @Test fun `busy TTS without current tracked utterance is ineligible`() {
        engine.busy = true
        assertFalse(speech.isActivelySpeaking); assertFalse(down()); assertFalse(up())
        say(); listener.onDone(id); assertTrue(speech.isSpeaking); assertFalse(speech.isActivelySpeaking)
        assertFalse(down(time = 20)); assertFalse(up(time = 20)); assertTrue(calls.isEmpty())
    }
    @Test fun `submission failure with stale busy TTS is ineligible`() {
        say(); engine.fail = true; speech.speak(Text("Failure"))
        assertFalse(speech.isActivelySpeaking); assertFalse(down()); assertFalse(up()); assertTrue(calls.isEmpty())
    }
    @Test fun `late callbacks do not disable newer announcement volume handling`() {
        say(); val old = id; say("New")
        listener.onDone(old); listener.onStop(old, true); listener.onError(old)
        assertTrue(speech.isActivelySpeaking); assertTrue(down()); assertTrue(up())
    }
    @Test fun `touch stopped speech is not active and does not regain eligibility from stale TTS`() {
        say(); speech.onTouchStart(); engine.busy = true
        assertFalse(speech.isActivelySpeaking); assertFalse(down()); assertFalse(up()); assertTrue(calls.isEmpty())
    }
    @Test fun `non-volume and media keys always pass through`() {
        say()
        for (code in listOf(KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_A)) {
            assertFalse(down(code)); assertFalse(down(code, repeat = 1)); assertFalse(up(code))
        }
        assertTrue(calls.isEmpty())
    }
    @Test fun `unmatched repeats and up pass through even during speech`() {
        say(); assertFalse(down(repeat = 1)); assertFalse(up()); assertTrue(calls.isEmpty())
    }
    @Test fun `wrong down time or device cannot adjust or release another press`() {
        say(); assertTrue(down()); assertFalse(down(repeat = 1, time = 20)); assertFalse(up(time = 20))
        assertFalse(up(device = 2)); assertFalse(down(repeat = 1, device = 2))
        assertTrue(down(repeat = 1)); assertTrue(up()); assertEquals(2, calls.size)
    }
    @Test fun `duplicate first down never adjusts twice`() {
        say(); assertTrue(down()); assertTrue(down()); assertTrue(up()); assertEquals(1, calls.size)
    }
    @Test fun `cancelled up consumes owned press and releases ownership`() {
        say(); assertTrue(down()); assertTrue(up(flags = KeyEvent.FLAG_CANCELED))
        assertFalse(down(repeat = 1)); assertFalse(up()); assertEquals(1, calls.size)
    }
    @Test fun `cancelled initial down and malformed events never adjust`() {
        say(); assertFalse(down(flags = KeyEvent.FLAG_CANCELED)); assertFalse(up(flags = KeyEvent.FLAG_CANCELED))
        assertFalse(down(repeat = -1)); assertFalse(volume.handle(KeyEvent(30, 20, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP, 0)))
        assertFalse(volume.handle(key(KeyEvent.ACTION_MULTIPLE))); assertTrue(calls.isEmpty())
    }
    @Test fun `cancelled repeat stays consumed but does not adjust again`() {
        say(); assertTrue(down()); assertTrue(down(repeat = 1, flags = KeyEvent.FLAG_CANCELED)); assertTrue(up())
        assertEquals(1, calls.size)
    }
    @Test fun `independent devices and volume keys have independent ownership`() {
        say(); assertTrue(down(device = 1)); assertTrue(down(device = 2)); assertTrue(down(KeyEvent.KEYCODE_VOLUME_DOWN))
        assertTrue(up(device = 2)); assertTrue(up(KeyEvent.KEYCODE_VOLUME_DOWN)); assertTrue(down(repeat = 1, device = 1)); assertTrue(up(device = 1))
        assertEquals(4, calls.size)
    }
    @Test fun `new initial down recovers from missing up without stealing a silent press`() {
        say(); assertTrue(down()); speech.stop(); assertFalse(down(time = 20)); say()
        assertFalse(down(repeat = 1, time = 20)); assertFalse(up(time = 20)); assertEquals(1, calls.size)
    }
    @Test fun `initial audio denial leaves whole press to Android`() {
        say(); audio.deny = true; assertFalse(down()); assertFalse(down(repeat = 1)); assertFalse(up())
        assertEquals(1, calls.size)
    }
    @Test fun `denial after claimed down does not leak partial key stream`() {
        say(); assertTrue(down()); audio.deny = true; assertTrue(down(repeat = 1)); assertTrue(up()); assertEquals(2, calls.size)
    }
    @Test fun `reread slider feedback and media action retain existing behavior`() {
        val service = SliderTestService()
        val root = AccessibilityNodeInfoCompat.wrap(AccessibilityNodeInfo.obtain())
        val focused = AccessibilityNodeInfoCompat.wrap(AccessibilityNodeInfo.obtain()).apply {
            className = "android.widget.SeekBar"; contentDescription = "Test level"
            isVisibleToUser = true; isEnabled = true; isAccessibilityFocused = true
            rangeInfo = AccessibilityNodeInfoCompat.RangeInfoCompat.obtain(0, 0f, 100f, 40f)
            addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_FORWARD)
        }
        val rootShadow: SliderNodeShadow = Shadow.extract(root.unwrap())
        val nodeShadow: SliderNodeShadow = Shadow.extract(focused.unwrap())
        rootShadow.focused = focused.unwrap(); rootShadow.addChild(focused.unwrap()); service.root = root.unwrap()
        nodeShadow.setRefreshReturnValue(true)
        val wrapper = ServiceController(service)
        val gestures = GestureInterceptor(FocusController(CallbackInterceptor(), wrapper), wrapper,
            SliderController(wrapper, speech), RefocusController(wrapper, speech), speech)
        assertTrue(gestures.handle(AccessibilityService.GESTURE_SWIPE_UP_AND_DOWN))
        assertTrue(down()); assertTrue(up()); assertTrue(nodeShadow.performedActions.isEmpty())
        nodeShadow.setOnPerformActionListener { _, _ ->
            focused.rangeInfo = AccessibilityNodeInfoCompat.RangeInfoCompat.obtain(0, 0f, 100f, 45f); true
        }
        assertTrue(gestures.handle(AccessibilityService.GESTURE_SWIPE_UP)); assertTrue(down(time = 20)); assertTrue(up(time = 20))
        assertEquals(listOf("Test level, slider, 40", "45"), engine.spokenTextList)
        val mediaHandled = gestures.handle(AccessibilityService.GESTURE_2_FINGER_DOUBLE_TAP)
        assertEquals(Build.VERSION.SDK_INT >= 31, mediaHandled)
        assertEquals(if (mediaHandled) listOf(AccessibilityService.GLOBAL_ACTION_KEYCODE_HEADSETHOOK) else emptyList<Int>(),
            shadowOf(service).globalActionsPerformed)
    }
    @Test fun `service key callback delegates and preserves flags`() {
        val service = SpeakTouchService().apply { volumeController = volume }
        service.serviceInfo = AccessibilityServiceInfo().apply { flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        SpeakTouchService::class.java.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }.invoke(service)
        var expected = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
            AccessibilityServiceInfo.FLAG_ENABLE_ACCESSIBILITY_VOLUME or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        if (Build.VERSION.SDK_INT >= 30) expected = expected or AccessibilityServiceInfo.FLAG_REQUEST_MULTI_FINGER_GESTURES
        if (Build.VERSION.SDK_INT >= 31) expected = expected or AccessibilityServiceInfo.FLAG_REQUEST_2_FINGER_PASSTHROUGH
        assertEquals(expected, service.serviceInfo.flags)
        val callback = SpeakTouchService::class.java.getDeclaredMethod("onKeyEvent", KeyEvent::class.java).apply { isAccessible = true }
        assertEquals(false, callback.invoke(service, key(KeyEvent.ACTION_DOWN)))
        callback.invoke(service, key(KeyEvent.ACTION_UP)); say()
        assertEquals(true, callback.invoke(service, key(KeyEvent.ACTION_DOWN, time = 20)))
        assertEquals(true, callback.invoke(service, key(KeyEvent.ACTION_UP, time = 20)))
        assertEquals(1, calls.size)
    }
    @Test fun `source manifest declares normal audio permission and key capability metadata`() {
        // Complements Android's metadata parsing checks in AccessibilityCapabilityTest.
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val manifest = factory.newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val namespace = "http://schemas.android.com/apk/res/android"
        val permissions = manifest.getElementsByTagName("uses-permission")
        val names = (0 until permissions.length).map { permissions.item(it).attributes.getNamedItemNS(namespace, "name").nodeValue }
        assertTrue("android.permission.MODIFY_AUDIO_SETTINGS" in names)
        assertFalse("android.permission.CHANGE_ACCESSIBILITY_VOLUME" in names)
        val metadata = manifest.getElementsByTagName("meta-data")
        assertTrue((0 until metadata.length).any { metadata.item(it).attributes.getNamedItemNS(namespace, "resource")?.nodeValue == "@xml/accessibility_service_config" })
        val config = factory.newDocumentBuilder().parse(File("src/main/res/xml/accessibility_service_config.xml"))
        assertEquals("true", config.documentElement.getAttributeNS(namespace, "canRequestFilterKeyEvents"))
    }
    @Test @Config(sdk = [25]) fun `API 25 passes all volume events through and requests no new flags`() {
        say(); assertFalse(down()); assertFalse(down(repeat = 1)); assertFalse(up())
        assertFalse(down(KeyEvent.KEYCODE_VOLUME_DOWN)); assertFalse(up(KeyEvent.KEYCODE_VOLUME_DOWN))
        speech.togglePauseResume(); assertFalse(down(time = 20)); assertFalse(up(time = 20)); assertTrue(calls.isEmpty())
        val service = SpeakTouchService().apply { volumeController = volume }
        service.serviceInfo = AccessibilityServiceInfo().apply { flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        SpeakTouchService::class.java.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }.invoke(service)
        assertEquals(AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS, service.serviceInfo.flags)
    }
}
