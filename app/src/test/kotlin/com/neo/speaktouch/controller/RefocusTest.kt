package com.neo.speaktouch.controller

import android.accessibilityservice.AccessibilityService
import android.speech.tts.TextToSpeech
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.neo.speaktouch.intercepter.event.CallbackInterceptor
import com.neo.speaktouch.intercepter.event.SpeechInterceptor
import com.neo.speaktouch.intercepter.gesture.GestureInterceptor
import com.neo.speaktouch.utils.Reader
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
import org.robolectric.shadow.api.Shadow

// Robolectric records speech/stop calls but does not model an active TTS engine.
@Implements(TextToSpeech::class)
class RefocusSpeechShadow : ShadowTextToSpeech() {
    @Implementation fun isSpeaking(): Boolean = lastSpokenText != null && !isStopped
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 31], shadows = [SliderNodeShadow::class, RefocusSpeechShadow::class])
class RefocusTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var service: SliderTestService
    private lateinit var root: AccessibilityNodeInfoCompat
    private lateinit var focused: AccessibilityNodeInfoCompat
    private lateinit var tts: TextToSpeech
    private lateinit var speech: SpeechController
    private lateinit var command: RefocusController
    private lateinit var gestures: GestureInterceptor
    private lateinit var callbacks: CallbackInterceptor

    private fun node(text: String? = null, windowId: Int = 1) =
        AccessibilityNodeInfoCompat.wrap(AccessibilityNodeInfo.obtain()).apply {
            className = "android.view.View"
            this.text = text
            isVisibleToUser = true
            isEnabled = true
            val window = AccessibilityWindowInfo.obtain()
            shadowOf(window).setId(windowId)
            shadowOf(unwrap()).setAccessibilityWindowInfo(window)
        }
    private fun shadow(node: AccessibilityNodeInfoCompat): SliderNodeShadow = Shadow.extract(node.unwrap())
    private fun spoken() = shadowOf(tts).spokenTextList
    private fun reread() = gestures.handle(AccessibilityService.GESTURE_SWIPE_UP_AND_DOWN)

    @Before fun setup() {
        service = SliderTestService()
        root = node()
        focused = node("Original").apply { isAccessibilityFocused = true }
        shadow(root).addChild(focused.unwrap())
        shadow(root).focused = focused.unwrap()
        shadow(focused).setRefreshReturnValue(true)
        service.root = root.unwrap()
        tts = TextToSpeech(context, null)
        speech = SpeechController(tts, context, Reader(context))
        val wrapper = ServiceController(service)
        command = RefocusController(wrapper, speech)
        callbacks = CallbackInterceptor()
        gestures = GestureInterceptor(FocusController(callbacks, wrapper), wrapper,
            SliderController(wrapper, speech), command)
    }

    @Test fun `one invocation speaks once and repeat invocation works without moving focus`() {
        assertTrue(reread())
        assertEquals(listOf("Original"), spoken())
        assertTrue(reread())
        assertEquals(listOf("Original", "Original"), spoken())
        assertEquals(focused.unwrap(), shadow(root).focused)
        assertTrue(focused.isAccessibilityFocused)
        assertTrue(shadow(focused).performedActions.isEmpty())
        assertTrue(shadow(root).performedActions.isEmpty())
        assertTrue(shadowOf(service).globalActionsPerformed.isEmpty())
    }
    @Test fun `no accessibility focus does not fall back to root or input focus`() {
        root.text = "Root"
        focused.isFocused = true
        shadow(root).focused = null
        assertTrue(reread())
        assertTrue(spoken().isEmpty())
        assertTrue(shadow(root).performedActions.isEmpty())
    }
    @Test fun `no active root does nothing`() {
        service.root = null
        assertTrue(reread())
        assertTrue(spoken().isEmpty())
    }
    @Test fun `failed refresh suppresses stale speech`() {
        shadow(focused).setRefreshReturnValue(false)
        command.reread()
        assertTrue(spoken().isEmpty())
    }
    @Test fun `invisible after refresh does not speak`() {
        shadow(focused).onRefresh = { focused.isVisibleToUser = false; true }
        command.reread()
        assertTrue(spoken().isEmpty())
    }
    @Test fun `focus flag lost during refresh does not speak`() {
        shadow(focused).onRefresh = { focused.isAccessibilityFocused = false; true }
        command.reread()
        assertTrue(spoken().isEmpty())
    }
    @Test fun `different current focus suppresses old node even with stale focus flag`() {
        shadow(focused).onRefresh = { shadow(root).focused = node("Other").unwrap(); true }
        command.reread()
        assertTrue(spoken().isEmpty())
    }
    @Test fun `active window changes during refresh suppress speech`() {
        val replacement = node(windowId = 2)
        // Even a provider returning the old focused node cannot bypass the window check.
        shadow(replacement).focused = focused.unwrap()
        shadow(focused).onRefresh = { service.root = replacement.unwrap(); true }
        command.reread()
        assertTrue(spoken().isEmpty())
    }
    @Test fun `active root disappears during refresh suppress speech`() {
        shadow(focused).onRefresh = { service.root = null; true }
        command.reread()
        assertTrue(spoken().isEmpty())
    }
    @Test fun `refresh supplies current text`() {
        shadow(focused).onRefresh = { focused.text = "Updated"; true }
        command.reread()
        assertEquals(listOf("Updated"), spoken())
    }
    @Test fun `refresh supplies current explicit state once`() {
        focused.className = "android.widget.Button"
        focused.text = "Download"
        focused.stateDescription = "Old"
        shadow(focused).onRefresh = { focused.stateDescription = "Loading"; true }
        command.reread()
        assertEquals(listOf("Download, button, Loading"), spoken())
    }
    @Test fun `slider reread uses refreshed full speech without adjustment`() {
        focused.className = "android.widget.SeekBar"
        focused.contentDescription = "Test level"
        focused.text = null
        shadow(focused).onRefresh = {
            focused.rangeInfo = AccessibilityNodeInfoCompat.RangeInfoCompat.obtain(2, 0f, 100f, 45f)
            true
        }
        command.reread()
        assertEquals(listOf("Test level, slider, 45%"), spoken())
        assertTrue(shadow(focused).performedActions.isEmpty())
    }
    @Test fun `slider explicit state still precedes range fallback`() {
        focused.className = "android.widget.SeekBar"
        focused.text = "Test level"
        focused.rangeInfo = AccessibilityNodeInfoCompat.RangeInfoCompat.obtain(2, 0f, 100f, 45f)
        focused.stateDescription = "Quiet"
        command.reread()
        assertEquals(listOf("Test level, slider, Quiet"), spoken())
    }
    @Test fun `marked group keeps parent and passive descendants but excludes actions`() {
        focused.text = "Transfer"
        focused.isScreenReaderFocusable = true
        shadow(focused).addChild(node("Download").unwrap())
        val nested = node()
        shadow(nested).addChild(node("Loading").unwrap())
        shadow(focused).addChild(nested.unwrap())
        shadow(focused).addChild(node("Retry").apply { isClickable = true }.unwrap())
        command.reread()
        assertEquals(listOf("Transfer, Download, Loading"), spoken())
    }
    @Test fun `labeled edit field keeps hint value and role precedence`() {
        focused.className = "android.widget.EditText"
        focused.isEditable = true
        focused.text = null
        focused.hintText = "Enter your name"
        focused.contentDescription = "Ignored"
        shadow(focused).setLabeledBy(node("Name").unwrap())
        command.reread()
        shadow(focused).onRefresh = { focused.text = "Mike"; true }
        command.reread()
        assertEquals(listOf("Name, Enter your name, edit field", "Name, Mike, edit field"), spoken())
    }
    @Test fun `unlabeled button keeps existing role`() {
        focused.className = "android.widget.ImageButton"
        focused.text = null
        command.reread()
        assertEquals(listOf("button"), spoken())
    }
    @Test fun `disabled visible focused node is still read`() {
        focused.isEnabled = false
        command.reread()
        assertEquals(listOf("Original"), spoken())
    }
    @Test fun `reread neither registers scroll retries nor fires pending callbacks`() {
        var calls = 0
        callbacks.addCallback(object : CallbackInterceptor.Scroll(root.unwrap()) {
            override fun invoke() { calls++ }
        })
        command.reread()
        assertEquals(0, calls)
        val field = CallbackInterceptor::class.java.getDeclaredField("scrolls").apply { isAccessible = true }
        assertEquals(1, (field.get(callbacks) as List<*>).size)
        assertTrue(shadow(root).performedActions.isEmpty())
    }
    @Test fun `touch stops speech deliberate command restarts it and another touch stops again`() {
        val interceptor = SpeechInterceptor(speech)
        speech.speak(focused)
        assertTrue(speech.isSpeaking)
        interceptor.handle(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_TOUCH_INTERACTION_START))
        assertFalse(speech.isSpeaking)
        assertEquals(1, spoken().size)
        assertTrue(reread())
        assertTrue(speech.isSpeaking)
        assertEquals(listOf("Original", "Original"), spoken())
        interceptor.handle(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_TOUCH_INTERACTION_START))
        assertFalse(speech.isSpeaking)
        assertEquals(2, spoken().size)
    }
}
