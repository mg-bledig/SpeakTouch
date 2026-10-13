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

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.ViewCompat
import com.neo.speaktouch.utils.SpeechPreferences

/** Reached through Android's accessibility-service settings entry; no launcher entry. */
class SettingsActivity : AppCompatActivity() {
    private lateinit var preferences: SpeechPreferences
    private lateinit var speakIds: SwitchCompat

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.settings_title)
        preferences = SpeechPreferences(this)
        val padding = (16 * resources.displayMetrics.density).toInt()
        val rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        speakIds = SwitchCompat(this).apply {
            setText(R.string.settings_speak_ids)
            minimumHeight = (48 * resources.displayMetrics.density).toInt()
            isChecked = preferences.speakIds
            setOnCheckedChangeListener { _, enabled -> preferences.speakIds = enabled }
        }
        rows.addView(speakIds, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        rows.addView(TextView(this).apply {
            setText(R.string.settings_speak_ids_summary)
            // Keep the explanation separate instead of making the scroll container a group.
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            ViewCompat.setScreenReaderFocusable(this, true)
        })
        setContentView(ScrollView(this).apply { addView(rows) })
    }

    override fun onResume() {
        super.onResume()
        speakIds.isChecked = preferences.speakIds
    }
}
