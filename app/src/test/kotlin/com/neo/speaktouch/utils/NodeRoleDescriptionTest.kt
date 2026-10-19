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

import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.neo.speaktouch.R
import com.neo.speaktouch.model.Type
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
@Config(sdk = [22, 28, 31])
class NodeRoleDescriptionTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val reader get() = Reader(context)
    private fun node(className: String? = "android.view.View") =
        AccessibilityNodeInfoCompat.wrap(AccessibilityNodeInfo.obtain()).apply {
            this.className = className
            text = "Homepage"
            isVisibleToUser = true
        }
    private fun linkMetadata(node: AccessibilityNodeInfoCompat, role: String = "link") {
        node.extras.putCharSequence("AccessibilityNodeInfo.chromeRole", role)
    }

    @Test fun `provider role is spoken for an otherwise unclassified virtual node`() {
        val target = node().apply { roleDescription = "link" }
        assertNull(Type.get(target))
        assertEquals("Homepage, link", reader.read(target))
    }

    @Test fun `provider role works without a class name`() {
        assertEquals("Homepage, link", reader.read(node(null).apply { roleDescription = "link" }))
    }

    @Test fun `provider role replaces native role instead of appending another role`() {
        val target = node("android.widget.Button").apply { roleDescription = "menu button" }
        assertEquals(Type.Button, Type.get(target))
        assertEquals("Homepage, menu button", reader.read(target))
    }

    @Test fun `matching provider native and machine roles produce one role`() {
        val target = node("android.widget.Button").apply { roleDescription = "button" }
        linkMetadata(target)
        assertEquals("Homepage, button", reader.read(target))
    }

    @Test fun `null empty and whitespace provider roles retain native fallback`() {
        for (description in listOf(null, "", " \n\t ")) {
            val target = node("android.widget.Button").apply { roleDescription = description }
            assertEquals("Homepage, ${context.getString(R.string.text_button_type)}", reader.read(target))
        }
    }

    @Test fun `provider whitespace is trimmed and localized wording retained`() {
        val target = node().apply { roleDescription = "  lien personnalisé  " }
        assertEquals("Homepage, lien personnalisé", reader.read(target))
    }

    @Test fun `explicit machine link role supplies localized fallback`() {
        val target = node().also(::linkMetadata)
        assertEquals("Homepage, ${context.getString(R.string.text_link_type)}", reader.read(target))
    }

    @Test fun `machine link works with missing class and blank provider description`() {
        val target = node(null).apply { roleDescription = " \n" }.also(::linkMetadata)
        assertEquals("Homepage, ${context.getString(R.string.text_link_type)}", reader.read(target))
    }

    @Test fun `native fallback takes precedence over machine link fallback`() {
        val target = node("android.widget.Button").also(::linkMetadata)
        assertEquals("Homepage, ${context.getString(R.string.text_button_type)}", reader.read(target))
    }

    @Test fun `unrecognized machine roles are not guessed`() {
        for (role in listOf("button", "LINK", "linkish", "", " link ")) {
            assertEquals("Homepage", reader.read(node().also { linkMetadata(it, role) }))
        }
    }

    @Test fun `clickability click actions and URL text do not imply link or button`() {
        val target = node().apply {
            text = "https://example.org"
            isClickable = true
            addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
        }
        assertEquals("https://example.org", reader.read(target))
        assertNull(Type.get(target))
    }

    @Test fun `type option suppresses provider and machine roles`() {
        val target = node().apply { roleDescription = "link" }.also(::linkMetadata)
        assertEquals("Homepage", reader.read(target, Reader.Options(mustReadType = false)))
        target.roleDescription = null
        assertEquals("Homepage", reader.read(target, Reader.Options(mustReadType = false)))
    }

    @Test fun `provider heading level is preserved without a generic heading duplicate`() {
        val target = node().apply { isHeading = true; roleDescription = "heading level 2" }
        assertEquals("Homepage, heading level 2", reader.read(target))
    }

    @Test fun `heading without provider level retains existing native description`() {
        val target = node().apply { isHeading = true }
        assertEquals("Homepage, ${context.getString(R.string.text_title_type)}", reader.read(target))
    }

    @Test fun `explicit state still replaces generated checked and selected state`() {
        val target = node("android.widget.CheckBox").apply {
            roleDescription = "checkbox"; isCheckable = true; isChecked = true
            isSelected = true; stateDescription = "Partially checked"
        }
        assertEquals("Homepage, checkbox, Partially checked", reader.read(target))
    }

    @Test fun `generated checkbox radio and selected state remain intact`() {
        val checkbox = node("android.widget.CheckBox").apply { isCheckable = true; isChecked = true }
        assertEquals("Homepage, checkbox, checked", reader.read(checkbox))
        val radio = node("android.widget.RadioButton").apply {
            isCheckable = true; isChecked = true; roleDescription = "radio button"
        }
        assertEquals("Homepage, radio button, selected", reader.read(radio))
        assertEquals("Homepage, link, selected", reader.read(node().apply {
            roleDescription = "link"; isSelected = true
        }))
    }

    @Test fun `editable value and hint precedence still ignore content description`() {
        val target = node("android.widget.EditText").apply {
            isEditable = true; roleDescription = "text field"
            contentDescription = "Ignored description"; hintText = "Enter your name"
        }
        assertEquals("Homepage, text field", reader.read(target))
        target.text = ""
        assertEquals("Enter your name, text field", reader.read(target))
    }

    @Test fun `noneditable description retains precedence over text`() {
        val target = node().apply { roleDescription = "link"; contentDescription = "Home" }
        assertEquals("Home, link", reader.read(target))
    }

    @Test fun `associated label stays before content role and state`() {
        val target = node().apply { roleDescription = "link"; stateDescription = "Current page" }
        shadowOf(target.unwrap()).setLabeledBy(node().apply { text = "Navigation" }.unwrap())
        assertEquals("Navigation, Homepage, link, Current page", reader.read(target))
    }

    @Test fun `slider and progress values remain in existing state slot`() {
        for ((className, role, value) in listOf(
            Triple("android.widget.SeekBar", "slider", "40"),
            Triple("android.widget.ProgressBar", "progress bar", "40%")
        )) {
            val target = node(className).apply {
                roleDescription = role
                rangeInfo = AccessibilityNodeInfoCompat.RangeInfoCompat.obtain(0, 0f, 100f, 40f)
            }
            assertEquals("Homepage, $role, $value", reader.read(target))
        }
    }

    @Test fun `role lookup does not need refresh or change focus and actions`() {
        val target = node().apply { roleDescription = "link" }
        shadowOf(target.unwrap()).setRefreshReturnValue(false)
        val actions = target.actionList.toList()
        assertEquals("Homepage, link", reader.read(target))
        assertFalse(target.isAccessibilityFocused)
        assertEquals(actions, target.actionList)
    }

    @Test fun `role on parent is not inherited by child`() {
        val parent = node().apply { roleDescription = "link" }
        val child = node().apply { text = "Child" }
        shadowOf(parent.unwrap()).addChild(child.unwrap())
        assertEquals("Child", reader.read(child))
    }
}
