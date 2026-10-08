package com.neo.speaktouch.utils

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.neo.speaktouch.R
import com.neo.speaktouch.model.NodeFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27, 28, 31])
class ContentPairsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val editRole get() = context.getString(R.string.text_editfield_type)
    private val buttonRole get() = context.getString(R.string.text_button_type)

    private fun node(text: String? = null) =
        AccessibilityNodeInfoCompat.wrap(AccessibilityNodeInfo.obtain()).apply {
            className = "android.view.View"
            this.text = text
            isVisibleToUser = true
        }

    private fun field(value: String? = null, hint: String? = null) = node(value).apply {
        className = "android.widget.EditText"
        hintText = hint
        isEditable = true
        isFocusable = true
    }

    private fun label(target: AccessibilityNodeInfoCompat, label: AccessibilityNodeInfoCompat = node("Name")) {
        shadowOf(target.unwrap()).setLabeledBy(label.unwrap())
        shadowOf(label.unwrap()).setLabelFor(target.unwrap())
    }

    private fun add(parent: AccessibilityNodeInfoCompat, child: AccessibilityNodeInfoCompat) {
        shadowOf(parent.unwrap()).addChild(child.unwrap())
    }

    private fun read(node: AccessibilityNodeInfoCompat) = Reader(context).read(node)

    @Test
    fun `empty field retains label hint and role`() {
        val field = field(hint = "Enter your name")
        label(field)
        assertEquals("Name, Enter your name, $editRole", read(field))
    }

    @Test
    fun `empty field without hint retains label and role`() {
        val field = field()
        label(field)
        assertEquals("Name, $editRole", read(field))
    }

    @Test
    fun `field value precedes hint after associated label`() {
        val field = field("Mike", "Enter your name")
        label(field)
        assertEquals("Name, Mike, $editRole", read(field))
    }

    @Test
    fun `label description takes precedence over its text`() {
        val field = field()
        label(field, node("Wrong label").apply { contentDescription = "Name" })
        assertEquals("Name, $editRole", read(field))
    }

    @Test
    fun `identical label and value are spoken once`() {
        val field = field("Name")
        label(field)
        assertEquals("Name, $editRole", read(field))
    }

    @Test
    fun `duplicate comparison normalizes whitespace`() {
        for (labelText in listOf("  Full\nName  ", "Full\tName", "Full\u00a0Name")) {
            val field = field("Full Name")
            label(field, node(labelText))
            assertEquals("Full Name, $editRole", read(field))
        }
    }

    @Test
    fun `complete label phrase already in hint is omitted`() {
        val field = field(hint = "Enter Name here")
        label(field)
        assertEquals("Enter Name here, $editRole", read(field))
    }

    @Test
    fun `partial word is not a duplicate`() {
        for ((value, labelText) in listOf("Username" to "Name", "UserName" to "Name", "Username" to "name")) {
            val field = field(value)
            label(field, node(labelText))
            assertEquals("$labelText, $value, $editRole", read(field))
        }
    }

    @Test
    fun `ignored field description does not suppress label`() {
        val field = field("Mike").apply { contentDescription = "Name" }
        label(field)
        assertEquals("Name, Mike, $editRole", read(field))
    }

    @Test
    fun `unspoken hint does not suppress label when value exists`() {
        val field = field("Mike", "Name")
        label(field)
        assertEquals("Name, Mike, $editRole", read(field))
    }

    @Test
    fun `role and explicit state remain once and in existing order`() {
        val field = field("Mike").apply { stateDescription = "Invalid"; isSelected = true }
        label(field)
        assertEquals("Name, Mike, $editRole, Invalid", read(field))
    }

    @Test
    fun `labels are not compared against role or state`() {
        for (labelText in listOf(editRole, "Invalid")) {
            val field = field("Mike").apply { stateDescription = "Invalid" }
            label(field, node(labelText))
            assertEquals("$labelText, Mike, $editRole, Invalid", read(field))
        }
    }

    @Test
    fun `missing relationship preserves existing speech`() {
        assertEquals("Enter your name, $editRole", read(field(hint = "Enter your name")))
    }

    @Test
    fun `empty and whitespace only labels are omitted`() {
        for (text in listOf(null, "", " \n ")) {
            val field = field("Mike")
            label(field, node(text))
            assertEquals("Mike, $editRole", read(field))
        }
    }

    @Test
    fun `self reference is ignored`() {
        val field = field("Mike")
        shadowOf(field.unwrap()).setLabeledBy(field.unwrap())
        assertEquals("Mike, $editRole", read(field))
    }

    @Test
    fun `invisible label is omitted`() {
        val field = field("Mike")
        label(field, node("Name").apply { isVisibleToUser = false })
        assertEquals("Mike, $editRole", read(field))
    }

    @Test
    fun `noneditable button reads associated label`() {
        val button = node("Retry").apply { className = "android.widget.Button"; isClickable = true }
        label(button, node("Transfer"))
        assertEquals("Transfer, Retry, $buttonRole", read(button))
    }

    @Test
    fun `label subtree and label relationship are not read`() {
        val field = field()
        val name = node("Name").apply { stateDescription = "Wrong state" }
        add(name, node("Wrong child"))
        label(name, node("Wrong associated label"))
        label(field, name)
        assertEquals("Name, $editRole", read(field))
    }

    @Test
    fun `ordinary label and field remain separate navigation targets`() {
        val parent = node()
        val name = node("Name")
        val field = field()
        add(parent, name)
        add(parent, field)
        label(field, name)
        assertTrue(NodeFilter.Focusable.filter(name.unwrap()))
        assertTrue(NodeFilter.Focusable.filter(field.unwrap()))
        assertEquals("Name", read(name))
    }

    @Test
    fun `actionable label stays independently navigable`() {
        val name = node("Name").apply { isClickable = true }
        val field = field()
        label(field, name)
        assertTrue(NodeValidator.mustFocus(name))
        assertTrue(NodeFilter.Focusable.filter(name.unwrap()))
        assertEquals("Name", read(name))
        assertEquals("Name, $editRole", read(field))
    }

    @Test
    fun `unlabeled slider keeps explicit state and role`() {
        val slider = node("Test level").apply {
            className = "android.widget.SeekBar"
            stateDescription = "40%"
        }
        assertEquals("Test level, ${context.getString(R.string.text_slider_type)}, 40%", read(slider))
    }

    @Test
    fun `marked group retains parent and passive descendants after associated label`() {
        val parent = node("Transfer").apply { isScreenReaderFocusable = true }
        add(parent, node("Download"))
        add(parent, node("Loading"))
        label(parent, node("Status"))
        assertEquals("Status, Transfer, Download, Loading", read(parent))
    }

    @Test
    fun `label already spoken in passive descendant is omitted`() {
        val parent = node("Transfer").apply { isScreenReaderFocusable = true }
        add(parent, node("Download"))
        label(parent, node("Download"))
        assertEquals("Transfer, Download", read(parent))
    }

    @Test
    fun `descendant role and state are not mistaken for duplicate content`() {
        val parent = node("Transfer").apply { isScreenReaderFocusable = true }
        add(parent, node("Download").apply { className = "android.widget.Button"; stateDescription = "Loading" })
        label(parent, node("Loading"))
        assertEquals("Loading, Transfer, Download, $buttonRole, Loading", read(parent))
    }

    @Test
    fun `multiple label composition preserves provider order`() {
        val field = field("Mike")
        assertEquals(listOf("Account", "Name"), Reader(context).associatedLabelContent(
            field, listOf(node("Account"), node("Name")), listOf("Mike")
        ))
    }

    @Test
    fun `multiple label composition suppresses only repeated normalized labels`() {
        val field = field()
        assertEquals(listOf("Full Name", "Account"), Reader(context).associatedLabelContent(
            field, listOf(node("Full Name"), node("Full\nName"), node("Account")), emptyList()
        ))
    }

    @Test
    fun `multiple label composition skips unavailable self invisible and empty references`() {
        val field = field("Mike")
        assertEquals(listOf("Name"), Reader(context).associatedLabelContent(
            field, listOf(null, field, node("Hidden").apply { isVisibleToUser = false }, node(""), node("Name")),
            listOf("Mike")
        ))
    }
}
