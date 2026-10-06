package com.neo.speaktouch.utils

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.neo.speaktouch.R
import com.neo.speaktouch.model.Type
import com.neo.speaktouch.utils.extension.toStateText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 31])
class StateDescriptionTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val reader get() = Reader(context)

    private fun node(
        className: String? = "android.view.View",
        label: String? = "Label",
        state: String? = null
    ): AccessibilityNodeInfoCompat = AccessibilityNodeInfoCompat.wrap(
        AccessibilityNodeInfo.obtain()
    ).apply {
        this.className = className
        text = label
        stateDescription = state
        isVisibleToUser = true
    }

    @Test
    fun `unselected button reads explicit state after label and type`() {
        val node = node("android.widget.Button", state = "Loading")
        assertEquals("Label, ${context.getString(R.string.text_button_type)}, Loading", reader.read(node))
    }

    @Test
    fun `unknown and null types still read explicit state`() {
        for (className in listOf("android.view.View", null)) {
            val node = node(className, state = "Expanded")
            assertNull(Type.get(node))
            assertEquals("Label, Expanded", reader.read(node))
        }
    }

    @Test
    fun `unchecked checked text view reads explicit state`() {
        val node = node("android.widget.CheckedTextView", state = "Available")
        node.isChecked = false
        assertEquals("Label, Available", reader.read(node))
    }

    @Test
    fun `explicit state replaces generated state for every checkable type`() {
        val types = listOf(Type.Checkable.Checkbox, Type.Checkable.Radio,
            Type.Checkable.Switch, Type.Checkable.Toggle, Type.Checkable.TextView,
            Type.Checkable.Custom)
        for (type in types) {
            for (checked in listOf(false, true)) {
                val node = node(state = "Custom state").apply {
                    isCheckable = true
                    isChecked = checked
                    isSelected = true
                }
                assertEquals("Custom state", node.toStateText(type)?.resolved(context))
            }
        }
    }

    @Test
    fun `selected node uses explicit state without generated selected suffix`() {
        val node = node(state = "Current page").apply { isSelected = true }
        assertEquals("Label, Current page", reader.read(node))
    }

    @Test
    fun `null and empty descriptions preserve all generated checkable fallbacks`() {
        val fallbacks = listOf(
            Triple(Type.Checkable.Checkbox, R.string.text_checked, R.string.text_not_checked),
            Triple(Type.Checkable.Radio, R.string.text_selected, R.string.text_not_selected),
            Triple(Type.Checkable.Switch, R.string.text_enabled, R.string.text_disabled),
            Triple(Type.Checkable.Toggle, R.string.text_pressed, R.string.text_not_pressed),
            Triple(Type.Checkable.Custom, R.string.text_selected, R.string.text_not_selected)
        )
        for (state in listOf(null, "")) {
            for ((type, checkedText, uncheckedText) in fallbacks) {
                for (checked in listOf(false, true)) {
                    val node = node(state = state).apply { isChecked = checked }
                    assertEquals(context.getString(if (checked) checkedText else uncheckedText),
                        node.toStateText(type)?.resolved(context))
                }
            }
            val node = node(state = state)
            assertNull(node.toStateText(Type.Checkable.TextView))
            node.isChecked = true
            assertEquals(context.getString(R.string.text_selected),
                node.toStateText(Type.Checkable.TextView)?.resolved(context))
        }
    }

    @Test
    fun `selected fallback and no state behavior are preserved`() {
        for (state in listOf(null, "")) {
            val node = node(state = state)
            assertNull(node.toStateText())
            node.isSelected = true
            assertEquals("Label, ${context.getString(R.string.text_selected)}", reader.read(node))
        }
    }

    @Test
    fun `reader option can still suppress explicit state`() {
        assertEquals("Label", reader.read(node(state = "Expanded"),
            Reader.Options(mustReadState = false)))
    }

    private fun group(child: AccessibilityNodeInfoCompat): AccessibilityNodeInfoCompat {
        val parent = node(label = null).apply { isClickable = true }
        shadowOf(parent.unwrap()).addChild(child.unwrap())
        return parent
    }

    @Test
    fun `grouped readable noncheckable child retains explicit state`() {
        val child = node(state = "Expanded")
        assertEquals("Label, Expanded", reader.read(group(child)))
    }

    @Test
    fun `grouped selected child without explicit state stays suppressed`() {
        val child = node().apply { isSelected = true }
        assertEquals("Label", reader.read(group(child)))
    }

    @Test
    fun `grouped checkable child retains generated fallback`() {
        val child = node().apply { isCheckable = true; isChecked = true }
        assertEquals("Label, ${context.getString(R.string.text_selected)}", reader.read(group(child)))
    }

    @Test
    fun `grouped explicit child and parent states are composed once each`() {
        val parent = group(node(state = "Child state")).apply { stateDescription = "Parent state" }
        assertEquals("Label, Child state, Parent state", reader.read(parent))
    }

    @Test
    fun `state alone does not expand navigation or grouped child eligibility`() {
        val child = node(label = null, state = "Expanded")
        assertFalse(NodeValidator.hasReadableContent(child))
        assertFalse(NodeValidator.isReadableAsChild(child))
        assertEquals("", reader.read(group(child)))
    }
}
