package com.neo.speaktouch.utils

import android.app.Activity
import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ProgressBar
import android.widget.SeekBar
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.neo.speaktouch.R
import com.neo.speaktouch.model.NodeFilter
import com.neo.speaktouch.model.Type
import com.neo.speaktouch.utils.extension.toStateText
import java.text.NumberFormat
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 31])
class ProgressBarTest {
    class CustomProgressBar(context: Context) : ProgressBar(context)
    class CustomSeekBar(context: Context) : SeekBar(context)
    private val context get() = RuntimeEnvironment.getApplication()
    private val role get() = context.getString(R.string.text_progress_bar_type)
    private fun node() = AccessibilityNodeInfoCompat.wrap(AccessibilityNodeInfo.obtain()).apply {
        className = "android.widget.ProgressBar"
        contentDescription = "Download progress"
        isVisibleToUser = true
    }
    private fun range(node: AccessibilityNodeInfoCompat, min: Float = 0f, max: Float = 100f,
                      current: Float = 40f, type: Int = 0) {
        node.rangeInfo = AccessibilityNodeInfoCompat.RangeInfoCompat.obtain(type, min, max, current)
    }
    private fun state(node: AccessibilityNodeInfoCompat) = node.toStateText()?.resolved(context)
    private fun percent(fraction: Double) = NumberFormat.getPercentInstance().format(fraction)
    private fun read(node: AccessibilityNodeInfoCompat) = Reader(context).read(node)

    @Test fun `native progress bar and subclass have a distinct role while SeekBars stay sliders`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            for (view in listOf(ProgressBar(activity.get()), CustomProgressBar(activity.get()),
                SeekBar(activity.get()), CustomSeekBar(activity.get()))) {
                activity.get().setContentView(view)
                val type = Type.get(AccessibilityNodeInfoCompat.wrap(view.createAccessibilityNodeInfo()))
                assertEquals(if (view is SeekBar) Type.Slider else Type.ProgressBar, type)
            }
            assertEquals(Type.ProgressBar, Type.get(node().apply { className = CustomProgressBar::class.java.name }))
            assertEquals(Type.Slider, Type.get(node().apply { className = CustomSeekBar::class.java.name }))
        } finally { activity.pause().stop().destroy() }
    }
    @Test fun `zero to one hundred reads label role and percentage once`() {
        val node = node(); range(node)
        assertEquals("Download progress, $role, ${percent(0.4)}", read(node))
        assertEquals("Download progress, $role", Reader(context).read(node, Reader.Options(mustReadState = false)))
    }
    @Test fun `non hundred maximum normalizes the current value`() {
        val node = node(); range(node, max = 200f, current = 80f)
        assertEquals(percent(0.4), state(node))
    }
    @Test fun `nonzero minimum is subtracted before normalization`() {
        val node = node(); range(node, min = 20f, max = 220f, current = 100f)
        assertEquals(percent(0.4), state(node))
    }
    @Test fun `float ranges normalize and locale format percentages`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val node = node(); range(node, min = 0.5f, max = 2.5f, current = 1.5f, type = 1)
            assertEquals(percent(0.5), state(node))
        } finally { Locale.setDefault(previous) }
    }
    @Test fun `percentage valued range is not normalized twice`() {
        val node = node(); range(node, max = 200f, current = 40f, type = 2)
        assertEquals(percent(0.4), state(node))
    }
    @Test fun `endpoints and negative minimum are supported`() {
        val node = node()
        for ((current, expected) in listOf(-50f to 0.0, 0f to 0.5, 50f to 1.0)) {
            range(node, min = -50f, max = 50f, current = current)
            assertEquals(percent(expected), state(node))
        }
    }
    @Test fun `explicit description replaces fallback without duplicating percentage`() {
        val node = node(); range(node)
        for (description in listOf("Downloading", "40%")) {
            node.stateDescription = description
            assertEquals("Download progress, $role, $description", read(node))
        }
        node.stateDescription = ""
        assertEquals(percent(0.4), state(node))
        node.stateDescription = "   "
        assertEquals(percent(0.4), state(node))
    }
    @Test fun `missing range omits generated progress`() {
        val node = node()
        assertNull(state(node))
        assertEquals("Download progress, $role", read(node))
    }
    @Test fun `description precedence and empty description fallback work independently of role formatting`() {
        val node = node(); range(node, max = 200f, current = 80f)
        node.stateDescription = "Downloading"
        assertEquals("Downloading", state(node))
        node.stateDescription = "40%"
        assertEquals("40%", state(node))
        for (description in listOf("", "   ")) {
            node.stateDescription = description
            assertEquals(percent(0.4), state(node))
        }
    }
    @Test fun `invalid nonfinite and unknown ranges omit generated progress`() {
        val node = node()
        for ((min, max, current) in listOf(
            Triple(0f, 0f, 0f), Triple(100f, 0f, 40f), Triple(0f, 100f, -1f),
            Triple(0f, 100f, 101f), Triple(Float.NaN, 100f, 40f),
            Triple(0f, Float.POSITIVE_INFINITY, 40f), Triple(0f, 100f, Float.NaN),
            Triple(Float.NEGATIVE_INFINITY, 100f, 40f), Triple(0f, 100f, Float.POSITIVE_INFINITY))) {
            range(node, min, max, current)
            assertNull(state(node))
        }
        range(node, type = 99); assertNull(state(node))
        range(node, max = 200f, current = 150f, type = 2); assertNull(state(node))
    }
    @Test fun `large finite float ranges do not overflow during normalization`() {
        val node = node(); range(node, min = -Float.MAX_VALUE, max = Float.MAX_VALUE, current = 0f, type = 1)
        assertEquals(percent(0.5), state(node))
    }
    @Test fun `native indeterminate progress has no range fallback`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val view = ProgressBar(activity.get()).apply { isIndeterminate = true }
            activity.get().setContentView(view)
            val node = AccessibilityNodeInfoCompat.wrap(view.createAccessibilityNodeInfo())
            assertNull(node.rangeInfo)
            node.stateDescription = null // Test the fallback separately from the provider's description.
            assertNull(state(node))
        } finally { activity.pause().stop().destroy() }
        val node = node().apply { stateDescription = "In progress" }
        assertEquals("In progress", state(node))
    }
    @Test fun `range and state alone do not broaden eligibility`() {
        val node = node().apply { contentDescription = null; stateDescription = "40%" }
        range(node)
        assertFalse(NodeValidator.hasReadableContent(node))
        assertFalse(NodeFilter.Focusable.filter(node.unwrap()))
        node.contentDescription = "Download progress"
        assertTrue(NodeFilter.Focusable.filter(node.unwrap()))
        node.isVisibleToUser = false
        assertFalse(NodeFilter.Focusable.filter(node.unwrap()))
    }
    @Test fun `passive grouped progress retains generated state with existing grouping`() {
        for (marked in listOf(false, true)) {
            val parent = node().apply { className = "android.view.View"; contentDescription = null; isScreenReaderFocusable = marked }
            val child = node(); range(child, max = 200f, current = 80f)
            shadowOf(parent.unwrap()).addChild(child.unwrap())
            assertTrue(NodeValidator.isReadableAsChild(child))
            assertEquals("Download progress, $role, ${percent(0.4)}", read(parent))
        }
    }
    @Test fun `generic range node retains its existing classification and speech`() {
        val node = node().apply { className = "android.view.View" }; range(node)
        assertNull(Type.get(node))
        assertEquals("Download progress", read(node))
    }
}
