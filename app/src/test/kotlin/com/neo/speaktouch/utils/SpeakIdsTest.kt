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
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [22, 31])
class SpeakIdsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val key = CustomLabelKey("example.app", "example.app:id/sendButton")
    private lateinit var target: AccessibilityNodeInfoCompat
    private lateinit var preferences: SpeechPreferences
    private lateinit var reader: Reader
    private fun node(text: String? = null) = AccessibilityNodeInfoCompat.wrap(AccessibilityNodeInfo.obtain()).apply {
        isVisibleToUser = true; this.text = text
    }
    @Before fun setup() {
        context.getSharedPreferences(CustomLabelStore.FILE_NAME, android.content.Context.MODE_PRIVATE).edit().clear().commit()
        preferences = SpeechPreferences(context); preferences.speakIds = false
        target = node().apply {
            className = "android.widget.Button"; isClickable = true; isEnabled = true
            packageName = key.packageName; viewIdResourceName = key.resourceName
        }
        reader = Reader(context)
    }
    @Test fun `off preserves role-only speech`() { assertEquals("button", reader.read(target)) }
    @Test fun `on supplies final resource name before role`() {
        preferences.speakIds = true; assertEquals("sendButton, button", reader.read(target))
    }
    @Test fun `setting changes are observed by the same reader`() {
        assertEquals("button", reader.read(target))
        preferences.speakIds = true; assertEquals("sendButton, button", reader.read(target))
        preferences.speakIds = false; assertEquals("button", reader.read(target))
        preferences.speakIds = true; assertEquals("sendButton, button", Reader(context).read(target))
    }
    @Test fun `app text wins`() {
        preferences.speakIds = true; target.text = "Send"
        assertEquals("Send, button", reader.read(target))
    }
    @Test fun `content description wins`() {
        preferences.speakIds = true; target.contentDescription = "Send message"
        assertEquals("Send message, button", reader.read(target))
    }
    @Test fun `EditText hint wins and its ignored description stays ignored`() {
        preferences.speakIds = true; target.className = "android.widget.EditText"; target.isEditable = true
        target.contentDescription = "Ignored"; target.hintText = "Enter name"
        assertEquals("Enter name, edit field", reader.read(target))
        target.text = "Mike"; assertEquals("Mike, edit field", reader.read(target))
    }
    @Test fun `associated label wins`() {
        preferences.speakIds = true
        shadowOf(target.unwrap()).setLabeledBy(node("Name").unwrap())
        assertEquals("Name, button", reader.read(target))
    }
    @Test fun `grouped content wins for a parent without own content`() {
        preferences.speakIds = true; target.className = "android.view.View"; target.isScreenReaderFocusable = true
        shadowOf(target.unwrap()).addChild(node("Download").unwrap())
        shadowOf(target.unwrap()).addChild(node("Loading").unwrap())
        assertEquals("Download, Loading", reader.read(target))
    }
    @Test fun `grouped parent own and descendant content keep their precedence`() {
        preferences.speakIds = true; target.className = "android.view.View"; target.isScreenReaderFocusable = true
        target.text = "Transfer"; shadowOf(target.unwrap()).addChild(node("Download").unwrap())
        assertEquals("Transfer, Download", reader.read(target))
    }
    @Test fun `custom label wins with no appended ID and removal reveals fallback`() {
        preferences.speakIds = true; CustomLabelStore(context).put(key, "Send message")
        assertEquals("Send message, button", reader.read(target))
        CustomLabelStore(context).remove(key)
        assertEquals("sendButton, button", reader.read(target))
    }
    @Test fun `custom label already represented in ancestor does not leak an ID`() {
        preferences.speakIds = true; CustomLabelStore(context).put(key, "Transfer")
        target.isClickable = false; target.isFocusable = false
        val group = node("Transfer").apply { isScreenReaderFocusable = true }
        shadowOf(group.unwrap()).addChild(target.unwrap())
        assertTrue(NodeValidator.isReadableAsChild(target))
        assertEquals("Transfer, button", reader.read(group))
    }
    @Test fun `explicit state and role are retained once`() {
        preferences.speakIds = true; target.stateDescription = "Loading"
        assertEquals("sendButton, button, Loading", reader.read(target))
    }
    @Test fun `generated checkable state is retained once`() {
        preferences.speakIds = true; target.className = "android.widget.CheckBox"; target.isCheckable = true; target.isChecked = true
        assertEquals("sendButton, checkbox, checked", reader.read(target))
    }
    @Test fun `slider range speech remains intact`() {
        preferences.speakIds = true; target.className = "android.widget.SeekBar"
        target.rangeInfo = AccessibilityNodeInfoCompat.RangeInfoCompat.obtain(2, 0f, 100f, 40f)
        assertEquals("sendButton, slider, 40%", reader.read(target))
    }
    @Test fun `missing malformed password and sensitive IDs retain role-only speech`() {
        preferences.speakIds = true
        for (id in listOf(null, "", " ", "sendButton", "example.app:id/")) {
            target.viewIdResourceName = id; assertEquals("button", reader.read(target))
        }
        target.viewIdResourceName = key.resourceName; target.isPassword = true
        assertEquals("button", reader.read(target))
        target.isPassword = false; target.isAccessibilityDataSensitive = true
        assertEquals("button", reader.read(target))
    }
    @Test fun `blank naming content permits fallback`() {
        preferences.speakIds = true; target.text = " \n"; target.contentDescription = " "
        assertEquals("sendButton, button", reader.read(target))
    }
    @Test fun `shared IDs still read independently`() {
        preferences.speakIds = true
        val second = node().apply {
            className = "android.widget.Button"; isClickable = true
            packageName = key.packageName; viewIdResourceName = key.resourceName
        }
        assertEquals("sendButton, button", reader.read(target))
        assertEquals("sendButton, button", reader.read(second))
        assertTrue(NodeValidator.mustFocus(target)); assertTrue(NodeValidator.mustFocus(second))
    }
    @Test fun `ID speech does not broaden focus eligibility`() {
        target.className = "android.view.View"; target.isClickable = false
        assertFalse(NodeValidator.mustFocus(target))
        preferences.speakIds = true; assertFalse(NodeValidator.mustFocus(target))
        target.isClickable = true; assertTrue(NodeValidator.mustFocus(target))
        target.isVisibleToUser = false; assertFalse(NodeValidator.mustFocus(target))
    }
}
