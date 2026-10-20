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

package com.neo.speaktouch.utils

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.neo.speaktouch.model.Type
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [22, 28, 31])
class SupplementalDescriptionTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val reader get() = Reader(context)
    private val key = "androidx.view.accessibility.AccessibilityNodeInfoCompat.SUPPLEMENTAL_DESCRIPTION_KEY"

    @Before fun setup() {
        context.getSharedPreferences(CustomLabelStore.FILE_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        SpeechPreferences(context).speakIds = false
    }

    private fun node(className: String? = "android.view.View", name: String? = null) =
        AccessibilityNodeInfoCompat.wrap(AccessibilityNodeInfo.obtain()).apply {
            this.className = className
            isVisibleToUser = true
            packageName = "example.web"
            if (name != null) extras.putCharSequence(key, name)
        }

    private fun field(name: String? = "Your name", value: String? = null) =
        node("android.widget.EditText", name).apply {
            isEditable = true; text = value; roleDescription = "edit field"
        }

    private fun checkbox(name: String? = "Ham") = node("android.widget.CheckBox", name).apply {
        text = ""; hintText = ""; isCheckable = true; roleDescription = "checkbox"
    }

    private fun label(target: AccessibilityNodeInfoCompat, name: String) {
        shadowOf(target.unwrap()).setLabeledBy(node().apply { text = name }.unwrap())
    }

    @Test fun `captured Ham checkbox includes supplemental name with unchanged checked states`() {
        val target = checkbox()
        for (checked in listOf(false, true)) {
            target.isChecked = checked
            target.stateDescription = if (checked) "checked" else "not checked"
            assertEquals("Ham, checkbox, ${target.stateDescription}", reader.read(target))
        }
    }

    @Test fun `captured radio labels preserve provider group position and checked state`() {
        for ((name, index) in listOf("Delivery by post" to 1, "Collect in person" to 2)) {
            val target = node("android.widget.RadioButton", name).apply {
                text = ""; hintText = ""; isCheckable = true; roleDescription = "option button"
            }
            for (state in listOf("not ticked", "ticked")) {
                target.stateDescription = "$state, in group, option $index of 2"
                assertEquals("$name, option button, ${target.stateDescription}", reader.read(target))
            }
        }
    }

    @Test fun `empty web field reads supplemental name and role`() {
        assertEquals("Your name, edit field", reader.read(field(value = "")))
    }

    @Test fun `populated web field reads supplemental name before entered value`() {
        assertEquals("Your name, Test, edit field", reader.read(field(value = "Test")))
    }

    @Test fun `changed values and names are read on demand without cached nodes`() {
        val target = field(value = "Test")
        assertEquals("Your name, Test, edit field", reader.read(target))
        target.text = "Mike"
        target.extras.putCharSequence(key, "Full name")
        assertEquals("Full name, Mike, edit field", reader.read(target))
    }

    @Test fun `semantic editable node preserves name and value without changing Type`() {
        val target = node(null, "Your name").apply {
            isEditable = true; text = "Test"; roleDescription = "text field"
        }
        assertNull(Type.get(target))
        assertEquals("Your name, Test, text field", reader.read(target))
    }

    @Test fun `duplicate own text is spoken once`() {
        val target = checkbox().apply { text = "Ham" }
        assertEquals("Ham, checkbox, not checked", reader.read(target))
    }

    @Test fun `duplicate own description is spoken once using existing precedence`() {
        val target = checkbox().apply { text = "Other text"; contentDescription = "Ham" }
        assertEquals("Ham, checkbox, not checked", reader.read(target))
    }

    @Test fun `duplicate spoken hint is not repeated`() {
        val target = field().apply { hintText = "Your name" }
        assertEquals("Your name, edit field", reader.read(target))
    }

    @Test fun `unspoken hint cannot suppress supplemental name of populated field`() {
        val target = field(value = "Test").apply { hintText = "Your name" }
        assertEquals("Your name, Test, edit field", reader.read(target))
    }

    @Test fun `associated label already represents supplemental name`() {
        val target = field(value = "Test")
        label(target, "Your name")
        assertEquals("Your name, Test, edit field", reader.read(target))
    }

    @Test fun `whitespace normalized duplicate is not repeated`() {
        val target = field().apply { hintText = " Your\n name " }
        assertEquals(" Your\n name , edit field", reader.read(target))
    }

    @Test fun `complete phrase in spoken description suppresses duplicate`() {
        val target = checkbox().apply { contentDescription = "Choose Ham please" }
        assertEquals("Choose Ham please, checkbox, not checked", reader.read(target))
    }

    @Test fun `partial word is not treated as duplicate`() {
        val target = checkbox().apply { text = "Hamburger" }
        assertEquals("Hamburger, Ham, checkbox, not checked", reader.read(target))
    }

    @Test fun `supplemental information augments content rather than replacing it`() {
        val target = node(name = "More information").apply { text = "Main content" }
        assertEquals("Main content, More information", reader.read(target))
    }

    @Test fun `missing null empty and blank extras leave existing speech unchanged`() {
        for (name in listOf(null, "", " \n\t ")) {
            val target = checkbox(name).apply { text = "Native label" }
            assertEquals("Native label, checkbox, not checked", reader.read(target))
            assertNull(SupplementalDescription.get(target))
        }
    }

    @Test fun `unrelated hint and similarly named extras are not interpreted as supplemental name`() {
        val target = checkbox(null).apply {
            extras.putCharSequence("AccessibilityNodeInfo.hint", "Not part of this change")
            extras.putCharSequence("SUPPLEMENTAL_DESCRIPTION_KEY", "Wrong key")
        }
        assertEquals("checkbox, not checked", reader.read(target))
    }

    @Test fun `native EditText description handling stays unchanged without supplemental metadata`() {
        val target = field(name = null, value = "Test").apply {
            contentDescription = "Ignored description"; hintText = "Enter name"
        }
        assertEquals("Test, edit field", reader.read(target))
        target.text = ""
        assertEquals("Enter name, edit field", reader.read(target))
        target.hintText = null
        assertEquals("edit field", reader.read(target))
    }

    @Test fun `ignored native description cannot suppress explicit supplemental name`() {
        val target = field(value = "Test").apply { contentDescription = "Your name" }
        assertEquals("Your name, Test, edit field", reader.read(target))
    }

    @Test fun `role and state slots retain precedence and respect Reader options`() {
        val target = checkbox().apply {
            stateDescription = "Partially checked"; isChecked = true; isSelected = true
        }
        assertEquals("Ham, checkbox, Partially checked", reader.read(target))
        assertEquals("Ham", reader.read(target, Reader.Options(mustReadType = false, mustReadState = false)))
    }

    @Test fun `role or state text does not hide a real supplemental name`() {
        val target = node(name = "Current").apply { stateDescription = "Current" }
        assertEquals("Current, Current", reader.read(target))
    }

    @Test fun `custom labels remain fallback while explicit app supplemental name wins`() {
        val target = node("android.widget.Button").apply { viewIdResourceName = "example.web:id/control" }
        val labelKey = CustomLabelKey.from(target)!!
        CustomLabelStore(context).put(labelKey, "Saved label")
        assertEquals("Saved label, button", reader.read(target))
        target.extras.putCharSequence(key, "App name")
        assertEquals("App name, button", reader.read(target))
        target.extras.remove(key)
        assertEquals("Saved label, button", reader.read(target))
    }

    @Test fun `supplemental app name precedes Speak IDs fallback`() {
        SpeechPreferences(context).speakIds = true
        val target = node("android.widget.Button", "App name").apply {
            viewIdResourceName = "example.web:id/control"
        }
        assertEquals("App name, button", reader.read(target))
    }

    @Test fun `grouped content suppresses duplicate parent supplemental without global sibling deduplication`() {
        val parent = node(name = "Download").apply { isScreenReaderFocusable = true; text = "Transfer" }
        for (label in listOf("Download", "Loading", "Loading")) {
            shadowOf(parent.unwrap()).addChild(node().apply { text = label }.unwrap())
        }
        assertEquals("Transfer, Download, Loading, Loading", reader.read(parent))
    }

    @Test fun `name resolution needs no refresh focus action or new traversal`() {
        val target = field(value = "Test")
        shadowOf(target.unwrap()).setRefreshReturnValue(false)
        assertEquals("Your name, Test, edit field", reader.read(target))
        assertFalse(target.isAccessibilityFocused)
        assertTrue(shadowOf(target.unwrap()).performedActions.isEmpty())
    }
}
