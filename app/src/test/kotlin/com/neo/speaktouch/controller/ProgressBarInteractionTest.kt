package com.neo.speaktouch.controller

import android.speech.tts.TextToSpeech
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.neo.speaktouch.R
import com.neo.speaktouch.intercepter.event.SpeechInterceptor
import com.neo.speaktouch.utils.Reader
import java.text.NumberFormat
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 31], shadows = [SliderNodeShadow::class])
class ProgressBarInteractionTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var service: SliderTestService
    private lateinit var root: AccessibilityNodeInfoCompat
    private lateinit var progress: AccessibilityNodeInfoCompat
    private lateinit var speech: SpeechController
    private lateinit var tts: TextToSpeech
    private fun shadow(node: AccessibilityNodeInfoCompat): SliderNodeShadow = Shadow.extract(node.unwrap())
    private fun value(current: Float) {
        progress.rangeInfo = AccessibilityNodeInfoCompat.RangeInfoCompat.obtain(0, 0f, 200f, current)
    }
    @Before fun setup() {
        root = AccessibilityNodeInfoCompat.wrap(AccessibilityNodeInfo.obtain())
        progress = AccessibilityNodeInfoCompat.wrap(AccessibilityNodeInfo.obtain()).apply {
            className = "android.widget.ProgressBar"
            contentDescription = "Download progress"
            isVisibleToUser = true; isEnabled = true; isAccessibilityFocused = true
            // Even a provider advertising these actions must not be treated as a slider.
            addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_FORWARD)
            addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_BACKWARD)
        }
        value(80f)
        shadow(root).addChild(progress.unwrap()); shadow(root).focused = progress.unwrap()
        shadow(progress).setRefreshReturnValue(true)
        service = SliderTestService().apply { this.root = this@ProgressBarInteractionTest.root.unwrap() }
        tts = TextToSpeech(context, null)
        speech = SpeechController(tts, context, Reader(context))
    }
    @Test fun `progress bar never uses slider adjustment or moves focus`() {
        val controller = SliderController(ServiceController(service), speech)
        assertFalse(controller.adjust(true)); assertFalse(controller.adjust(false))
        assertTrue(shadow(progress).performedActions.isEmpty())
        assertTrue(shadowOf(tts).spokenTextList.isEmpty())
        assertEquals(progress.unwrap(), shadow(root).focused)
        assertEquals(80f, progress.rangeInfo!!.current, 0f)
    }
    @Test fun `reread refreshes actual progress without focus actions`() {
        val controller = RefocusController(ServiceController(service), speech)
        val role = context.getString(R.string.text_progress_bar_type)
        controller.reread()
        shadow(progress).onRefresh = { value(100f); true }
        controller.reread()
        assertEquals(listOf("Download progress, $role, ${NumberFormat.getPercentInstance().format(0.4)}",
            "Download progress, $role, ${NumberFormat.getPercentInstance().format(0.5)}"), shadowOf(tts).spokenTextList)
        assertEquals(progress.unwrap(), shadow(root).focused)
        assertTrue(shadow(progress).performedActions.isEmpty())
    }
    @Test fun `progress events still do not trigger automatic speech`() {
        val interceptor = SpeechInterceptor(speech)
        for (type in listOf(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, AccessibilityEvent.TYPE_VIEW_SELECTED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED, AccessibilityEvent.TYPE_ANNOUNCEMENT)) {
            interceptor.handle(AccessibilityEvent.obtain(type))
        }
        assertTrue(shadowOf(tts).spokenTextList.isEmpty())
    }
}
