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
import android.os.Build
import androidx.appcompat.app.AlertDialog
import com.neo.speaktouch.controller.ScreenCurtainSession
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
    private lateinit var audibleNavigation: SwitchCompat
    private lateinit var hapticNavigation: SwitchCompat
    private lateinit var curtain: SwitchCompat
    private var syncingCurtain = false
    private var confirmation: AlertDialog? = null
    private val curtainChanged: () -> Unit = { syncCurtain() }

    private fun syncCurtain() {
        if (!::curtain.isInitialized) return
        syncingCurtain = true
        val controller = ScreenCurtainSession.controller
        curtain.isEnabled = controller != null
        curtain.isChecked = controller?.isEnabled == true
        syncingCurtain = false
    }

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
        audibleNavigation = SwitchCompat(this).apply {
            setText(R.string.settings_audible_navigation_feedback)
            minimumHeight = (48 * resources.displayMetrics.density).toInt()
            isChecked = preferences.audibleNavigationFeedback
            setOnCheckedChangeListener { _, enabled -> preferences.audibleNavigationFeedback = enabled }
        }
        rows.addView(audibleNavigation, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        hapticNavigation = SwitchCompat(this).apply {
            setText(R.string.settings_haptic_navigation_feedback)
            minimumHeight = (48 * resources.displayMetrics.density).toInt()
            isChecked = preferences.hapticNavigationFeedback
            setOnCheckedChangeListener { _, enabled -> preferences.hapticNavigationFeedback = enabled }
        }
        rows.addView(hapticNavigation, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        curtain = SwitchCompat(this).apply {
            setText(R.string.screen_curtain)
            minimumHeight = (48 * resources.displayMetrics.density).toInt()
            setOnCheckedChangeListener { _, enabled ->
                if (!syncingCurtain) {
                    val controller = ScreenCurtainSession.controller
                    if (!enabled) controller?.disable()
                    else if (controller != null && !controller.isEnabled && confirmation == null) {
                        // Keep the switch OFF until the service successfully attaches its window.
                        syncCurtain()
                        confirmation = AlertDialog.Builder(this@SettingsActivity)
                            .setTitle(R.string.screen_curtain_confirm_title)
                            .setMessage(if (Build.VERSION.SDK_INT >= 30) R.string.screen_curtain_confirm_message
                                else R.string.screen_curtain_confirm_message_legacy)
                            .setPositiveButton(R.string.screen_curtain_turn_on) { _, _ ->
                                if (ScreenCurtainSession.controller === controller) controller.enable()
                            }
                            .setNegativeButton(R.string.screen_curtain_cancel, null)
                            .create().apply {
                                setOnDismissListener { confirmation = null; syncCurtain() }
                                show()
                            }
                    }
                    syncCurtain()
                }
            }
        }
        rows.addView(curtain, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        syncCurtain()
        setContentView(ScrollView(this).apply { addView(rows) })
    }

    override fun onResume() {
        super.onResume()
        speakIds.isChecked = preferences.speakIds
        audibleNavigation.isChecked = preferences.audibleNavigationFeedback
        hapticNavigation.isChecked = preferences.hapticNavigationFeedback
        ScreenCurtainSession.observe(curtainChanged)
        syncCurtain()
    }
    override fun onPause() {
        ScreenCurtainSession.removeObserver(curtainChanged)
        super.onPause()
    }

    override fun onDestroy() {
        confirmation?.dismiss()
        super.onDestroy()
    }
}
