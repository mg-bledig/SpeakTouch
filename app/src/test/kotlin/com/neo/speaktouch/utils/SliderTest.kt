package com.neo.speaktouch.utils

import android.content.Context
import android.app.Activity
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ProgressBar
import android.widget.SeekBar
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.neo.speaktouch.R
import com.neo.speaktouch.model.NodeFilter
import com.neo.speaktouch.model.Type
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 31])
class SliderTest {
    class CustomSeekBar(context: Context) : SeekBar(context)

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val role get() = context.getString(R.string.text_slider_type)
    private fun node() = AccessibilityNodeInfoCompat.wrap(AccessibilityNodeInfo.obtain()).apply {
        className = "android.widget.SeekBar"
        contentDescription = "Test level"
        isVisibleToUser = true
        isFocusable = true
    }
    private fun range(node: AccessibilityNodeInfoCompat, type: Int = 0,
                      min: Float = 0f, max: Float = 100f, current: Float = 40f) {
        node.rangeInfo = AccessibilityNodeInfoCompat.RangeInfoCompat.obtain(type, min, max, current)
    }
    private fun read(node: AccessibilityNodeInfoCompat) = Reader(context).read(node)

    @Test fun `native SeekBar and subclass are sliders but ProgressBar is not`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            for (view in listOf(SeekBar(activity), CustomSeekBar(activity), ProgressBar(activity))) {
                activity.setContentView(view)
                val type = Type.get(AccessibilityNodeInfoCompat.wrap(view.createAccessibilityNodeInfo()))
                if (view is SeekBar) assertEquals(Type.Slider, type) else assertNotEquals(Type.Slider, type)
            }
            // Also exercise a subclass reporting its own class rather than inherited SeekBar class.
            assertEquals(Type.Slider, Type.get(node().apply { className = CustomSeekBar::class.java.name }))
        } finally { controller.pause().stop().destroy() }
        val generic = node().apply { className = "android.view.View" }
        range(generic)
        assertNull(Type.get(generic))
        assertEquals("Test level", read(generic))
    }

    @Test fun `label and slider role are read without a range`() {
        assertEquals("Test level, $role", read(node()))
    }

    @Test fun `integer range reads current value without endpoints`() {
        val slider = node()
        range(slider, min = 10f, max = 90f)
        assertEquals("Test level, $role, 40", read(slider))
    }

    @Test fun `float range uses locale formatting including small values`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val slider = node()
            range(slider, type = 1, max = 10f, current = 1.25f)
            assertEquals("Test level, $role, 1,25", read(slider))
            range(slider, type = 1, max = 1f, current = 0.0001f)
            assertEquals("Test level, $role, 0,0001", read(slider))
        } finally { Locale.setDefault(previous) }
    }

    @Test fun `percentage range reads current as percentage not fraction`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            val slider = node()
            range(slider, type = 2, current = 40f)
            assertEquals("Test level, $role, 40%", read(slider))
        } finally { Locale.setDefault(previous) }
    }

    @Test fun `nonempty description replaces range value while empty description permits fallback`() {
        val slider = node()
        range(slider)
        for (state in listOf("Quiet", "40%")) {
            slider.stateDescription = state
            assertEquals("Test level, $role, $state", read(slider))
        }
        slider.stateDescription = ""
        assertEquals("Test level, $role, 40", read(slider))
        assertEquals("Test level, $role", Reader(context).read(slider, Reader.Options(mustReadState = false)))
    }

    @Test fun `invalid zero-width nonfinite and unknown ranges omit generated value`() {
        val slider = node()
        val cases = listOf(
            Triple(0f, 0f, 0f), Triple(100f, 0f, 40f), Triple(0f, 100f, 101f),
            Triple(0f, 100f, -1f), Triple(0f, 100f, Float.NaN),
            Triple(Float.NEGATIVE_INFINITY, 100f, 40f), Triple(0f, Float.POSITIVE_INFINITY, 40f)
        )
        for ((min, max, current) in cases) {
            range(slider, min = min, max = max, current = current)
            assertEquals("Test level, $role", read(slider))
        }
        range(slider, type = 99)
        assertEquals("Test level, $role", read(slider))
    }

    @Test fun `unlabeled slider retains issue 88 focus and invisible slider is excluded`() {
        val slider = node().apply { contentDescription = null }
        range(slider)
        assertFalse(NodeValidator.hasReadableContent(slider))
        assertTrue(NodeValidator.mustFocus(slider))
        assertTrue(NodeFilter.Focusable.filter(slider.unwrap()))
        assertEquals("$role, 40", read(slider))
        slider.isVisibleToUser = false
        assertFalse(NodeValidator.mustFocus(slider))
        assertFalse(NodeFilter.Focusable.filter(slider.unwrap()))
    }

    @Test fun `slider remains independent inside screen reader group and collection`() {
        for (collection in listOf(false, true)) {
            val parent = node().apply {
                className = "android.view.View"
                contentDescription = null
                isFocusable = false
                isScreenReaderFocusable = true
                if (collection) setCollectionInfo(AccessibilityNodeInfoCompat.CollectionInfoCompat.obtain(1, 1, false))
            }
            val passive = node().apply { className = "android.view.View"; contentDescription = "Group label"; isFocusable = false }
            val slider = node()
            range(slider)
            shadowOf(parent.unwrap()).addChild(passive.unwrap())
            shadowOf(parent.unwrap()).addChild(slider.unwrap())
            assertFalse(NodeValidator.isReadableAsChild(slider))
            assertTrue(NodeFilter.Focusable.filter(slider.unwrap()))
            assertEquals(!collection, NodeValidator.mustFocus(parent))
            assertEquals(collection, NodeFilter.Focusable.filter(passive.unwrap()))
            if (!collection) assertEquals("Group label", read(parent))
        }
    }

    @Test fun `passive labeled slider remains grouped and retains range value`() {
        val parent = node().apply { className = "android.view.View"; contentDescription = null }
        val slider = node().apply { isFocusable = false }
        range(slider)
        shadowOf(parent.unwrap()).addChild(slider.unwrap())
        assertTrue(NodeValidator.isReadableAsChild(slider))
        assertFalse(NodeFilter.Focusable.filter(slider.unwrap()))
        assertEquals("Test level, $role, 40", read(parent))
    }
}
