package com.neo.speaktouch.controller

import android.accessibilityservice.AccessibilityService
import android.speech.tts.TextToSpeech
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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
import org.robolectric.shadows.ShadowAccessibilityNodeInfo
import org.robolectric.shadow.api.Shadow

@Implements(AccessibilityNodeInfo::class)
class SliderNodeShadow : ShadowAccessibilityNodeInfo() {
    var focused: AccessibilityNodeInfo? = null
    var onRefresh: (() -> Boolean)? = null
    @Implementation fun findFocus(kind: Int): AccessibilityNodeInfo? =
        if (kind == AccessibilityNodeInfo.FOCUS_ACCESSIBILITY) focused else null
    @Implementation override fun refresh(): Boolean = onRefresh?.invoke() ?: super.refresh()
}

class SliderTestService : AccessibilityService() {
    var root: AccessibilityNodeInfo? = null
    override fun getRootInActiveWindow(): AccessibilityNodeInfo? = root
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 31], shadows = [SliderNodeShadow::class])
class SliderGestureTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var service: SliderTestService
    private lateinit var root: AccessibilityNodeInfoCompat
    private lateinit var slider: AccessibilityNodeInfoCompat
    private lateinit var speech: SpeechController
    private lateinit var tts: TextToSpeech
    private lateinit var gestures: GestureInterceptor
    private fun node(type: String = "android.view.View") =
        AccessibilityNodeInfoCompat.wrap(AccessibilityNodeInfo.obtain()).apply {
            className = type
            isVisibleToUser = true
            isEnabled = true
        }
    private fun shadow(node: AccessibilityNodeInfoCompat): SliderNodeShadow = Shadow.extract(node.unwrap())
    private fun value(current: Float) {
        slider.rangeInfo = AccessibilityNodeInfoCompat.RangeInfoCompat.obtain(0, 0f, 100f, current)
    }
    private fun spoken() = shadowOf(tts).spokenTextList
    private fun up() = gestures.handle(AccessibilityService.GESTURE_SWIPE_UP)
    private fun down() = gestures.handle(AccessibilityService.GESTURE_SWIPE_DOWN)
    private fun actions(node: AccessibilityNodeInfoCompat = slider) = shadow(node).performedActions

    @Before fun setup() {
        service = SliderTestService()
        root = node()
        service.root = root.unwrap()
        slider = node("android.widget.SeekBar").apply {
            contentDescription = "Test level"
            isFocusable = true
            isAccessibilityFocused = true
            addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_FORWARD)
            addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_BACKWARD)
        }
        value(40f)
        shadow(root).addChild(slider.unwrap())
        shadow(root).focused = slider.unwrap()
        shadow(slider).setRefreshReturnValue(true)
        tts = TextToSpeech(context, null)
        speech = SpeechController(tts, context, Reader(context))
        val wrapper = ServiceController(service)
        gestures = GestureInterceptor(FocusController(CallbackInterceptor(), wrapper), wrapper,
            SliderController(wrapper, speech), RefocusController(wrapper, speech))
    }

    private fun simulateAdjustment(state: String? = null) {
        var pending: Float? = null
        shadow(slider).setOnPerformActionListener { action, _ ->
            pending = slider.rangeInfo!!.current + if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) 5 else -5
            true
        }
        shadow(slider).onRefresh = {
            pending?.let { value(it); slider.stateDescription = state; pending = null }
            true
        }
    }

    @Test fun `up and down use advertised actions and speak refreshed values once without moving focus`() {
        simulateAdjustment()
        assertTrue(up())
        assertEquals(45f, slider.rangeInfo!!.current, 0f)
        assertEquals(listOf("45"), spoken())
        assertTrue(down())
        assertEquals(40f, slider.rangeInfo!!.current, 0f)
        assertEquals(listOf("45", "40"), spoken())
        assertEquals(listOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD), actions())
        assertTrue(slider.isAccessibilityFocused)
        assertEquals(slider.unwrap(), shadow(root).focused)
        assertTrue(actions(root).isEmpty())
    }

    @Test fun `refreshed state description replaces generated value`() {
        simulateAdjustment("Quiet")
        assertTrue(up())
        assertEquals(listOf("Quiet"), spoken())
    }

    @Test fun `disabled slider consumes gestures without action or speech`() {
        slider.isEnabled = false
        assertTrue(up()); assertTrue(down())
        assertTrue(actions().isEmpty()); assertTrue(spoken().isEmpty())
    }

    @Test fun `invisible slider consumes gestures without action or speech`() {
        slider.isVisibleToUser = false
        assertTrue(up()); assertTrue(down())
        assertTrue(actions().isEmpty()); assertTrue(spoken().isEmpty())
    }

    @Test fun `min and max missing directional actions do not adjust or scroll parent`() {
        root.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_FORWARD)
        root.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_BACKWARD)
        value(0f)
        slider.removeAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_BACKWARD)
        assertTrue(down())
        value(100f)
        slider.removeAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_FORWARD)
        assertTrue(up())
        assertTrue(actions().isEmpty()); assertTrue(actions(root).isEmpty()); assertTrue(spoken().isEmpty())
    }

    @Test fun `missing actions in middle of range are consumed without attempts`() {
        slider.removeAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_FORWARD)
        slider.removeAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_BACKWARD)
        assertTrue(up()); assertTrue(down())
        assertTrue(actions().isEmpty()); assertTrue(spoken().isEmpty())
    }

    @Test fun `failed action is consumed without speech or ancestor retry`() {
        shadow(slider).setOnPerformActionListener { _, _ -> false }
        assertTrue(up())
        assertEquals(listOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD), actions())
        assertTrue(actions(root).isEmpty()); assertTrue(spoken().isEmpty())
    }

    @Test fun `failed initial or post-action refresh does not announce stale value`() {
        shadow(slider).setRefreshReturnValue(false)
        assertTrue(up()); assertTrue(actions().isEmpty())
        var refreshCount = 0
        shadow(slider).onRefresh = { ++refreshCount == 1 }
        shadow(slider).setOnPerformActionListener { _, _ -> true }
        assertTrue(up())
        assertEquals(listOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD), actions())
        assertTrue(spoken().isEmpty())
    }

    @Test fun `non-slider focus does not adjust slider ancestor and remains unhandled`() {
        val child = node().apply { text = "Child"; isAccessibilityFocused = true }
        shadow(slider).addChild(child.unwrap())
        shadow(root).focused = child.unwrap()
        assertFalse(up()); assertFalse(down())
        assertTrue(actions().isEmpty()); assertTrue(actions(child).isEmpty()); assertTrue(spoken().isEmpty())
    }

    @Test fun `no accessibility focus or active window leaves up down unhandled`() {
        shadow(root).focused = null
        assertFalse(up()); assertFalse(down())
        service.root = null
        assertFalse(up()); assertFalse(down())
        assertTrue(actions().isEmpty())
    }

    @Test fun `focus lost during action suppresses speech without restoring focus`() {
        shadow(slider).setOnPerformActionListener { _, _ ->
            slider.isAccessibilityFocused = false
            shadow(root).focused = null
            true
        }
        assertTrue(up())
        assertTrue(spoken().isEmpty())
        assertEquals(listOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD), actions())
    }

    @Test fun `progress events do not duplicate controller speech and Stage A reading stays intact`() {
        val before = Reader(context).read(slider)
        assertEquals("Test level, slider, 40", before)
        simulateAdjustment("45%")
        assertTrue(up())
        val interceptor = SpeechInterceptor(speech)
        for (type in listOf(AccessibilityEvent.TYPE_VIEW_SELECTED, AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)) {
            interceptor.handle(AccessibilityEvent.obtain(type))
        }
        assertEquals(listOf("45%"), spoken())
        assertEquals("Test level, slider, 45%", Reader(context).read(slider))
    }

    @Test fun `left right back and headset gesture mappings remain unchanged`() {
        val previous = node().apply { text = "Previous" }
        val next = node().apply { text = "Next" }
        root = node()
        shadow(root).addChild(previous.unwrap())
        shadow(root).addChild(slider.unwrap())
        shadow(root).addChild(next.unwrap())
        shadow(root).focused = slider.unwrap()
        service.root = root.unwrap()
        assertTrue(gestures.handle(AccessibilityService.GESTURE_SWIPE_LEFT))
        assertEquals(listOf(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS), actions(previous))
        assertTrue(gestures.handle(AccessibilityService.GESTURE_SWIPE_RIGHT))
        assertEquals(listOf(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS), actions(next))
        assertTrue(gestures.handle(AccessibilityService.GESTURE_SWIPE_DOWN_AND_LEFT))
        val headset = gestures.handle(AccessibilityService.GESTURE_2_FINGER_DOUBLE_TAP)
        assertEquals(android.os.Build.VERSION.SDK_INT >= 31, headset)
        val expected = mutableListOf(AccessibilityService.GLOBAL_ACTION_BACK)
        if (headset) expected.add(AccessibilityService.GLOBAL_ACTION_KEYCODE_HEADSETHOOK)
        assertEquals(expected, shadowOf(service).globalActionsPerformed)
        assertTrue(actions().isEmpty())
    }
}
