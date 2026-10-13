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

package com.neo.speaktouch

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.neo.speaktouch.utils.SpeechPreferences
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [22, 31])
class SettingsActivityTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun clear() {
        context.getSharedPreferences(SpeechPreferences.FILE_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }
    private fun switches(view: View): List<SwitchCompat> = when (view) {
        is SwitchCompat -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { switches(view.getChildAt(it)) }
        else -> emptyList()
    }
    @Test fun `Speak IDs default-off toggle exposes accessible label and checkable state`() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        val toggle = switches(activity.window.decorView).single { it.text.toString() == "Speak IDs" }
        val node = AccessibilityNodeInfoCompat.wrap(toggle.createAccessibilityNodeInfo())
        assertEquals("Speak IDs", toggle.text.toString())
        // Older SwitchCompat versions of the platform may include the native On/Off
        // description in accessibility text; the name and checked state must both remain available.
        assertTrue(node.text.toString().contains("Speak IDs"))
        assertTrue(node.isCheckable); assertFalse(node.isChecked)
        assertFalse(toggle.isChecked)
        toggle.performClick()
        assertTrue(toggle.isChecked); assertTrue(SpeechPreferences(context).speakIds)
        assertTrue(AccessibilityNodeInfoCompat.wrap(toggle.createAccessibilityNodeInfo()).isChecked)
        activity.finish()
    }
    @Test fun `recreated screen restores the chosen setting and can disable it`() {
        SpeechPreferences(context).speakIds = true
        val first = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        assertTrue(switches(first.get().window.decorView).single { it.text.toString() == "Speak IDs" }.isChecked)
        first.pause().stop().destroy()
        val second = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val toggle = switches(second.get().window.decorView).single { it.text.toString() == "Speak IDs" }
        assertTrue(toggle.isChecked); toggle.performClick()
        assertFalse(SpeechPreferences(context).speakIds)
        second.pause().stop().destroy()
    }
}
