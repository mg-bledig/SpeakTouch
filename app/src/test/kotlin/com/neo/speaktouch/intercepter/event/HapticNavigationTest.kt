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

package com.neo.speaktouch.intercepter.event

import android.content.Context
import android.os.Vibrator
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.neo.speaktouch.controller.VibratorController
import com.neo.speaktouch.utils.SpeechPreferences
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [22, 26, 29, 31, 33])
@LooperMode(LooperMode.Mode.PAUSED)
class HapticNavigationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var vibrator: Vibrator
    private lateinit var interceptor: HapticInterceptor
    @Before fun setup() {
        context.getSharedPreferences(SpeechPreferences.FILE_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        shadowOf(vibrator).setHasVibrator(true)
        interceptor = HapticInterceptor(VibratorController(vibrator), context)
    }
    @After fun cleanup() { if (::interceptor.isInitialized) interceptor.finish() }
    private fun focus(clickable: Boolean = false) {
        val node = AccessibilityNodeInfo.obtain().apply { isClickable = clickable }
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
        shadowOf(event).setSourceNode(node)
        interceptor.handle(event)
    }
    @Test fun `default enabled haptics preserve focus feedback`() {
        focus(); assertTrue(shadowOf(vibrator).isVibrating)
    }
    @Test fun `enabled clickable focus preserves feedback`() {
        SpeechPreferences(context).hapticNavigationFeedback = true
        focus(true); assertTrue(shadowOf(vibrator).isVibrating)
    }
    @Test fun `disabled haptics suppress passive and clickable focus`() {
        SpeechPreferences(context).hapticNavigationFeedback = false
        focus(); focus(true); assertFalse(shadowOf(vibrator).isVibrating)
    }
    @Test fun `existing interceptor observes changes without restart`() {
        focus(); assertTrue(shadowOf(vibrator).isVibrating); vibrator.cancel()
        SpeechPreferences(context).hapticNavigationFeedback = false
        focus(); assertFalse(shadowOf(vibrator).isVibrating)
        SpeechPreferences(context).hapticNavigationFeedback = true
        focus(); assertTrue(shadowOf(vibrator).isVibrating)
    }
    @Test fun `audible enabled does not override haptics disabled`() {
        SpeechPreferences(context).apply { hapticNavigationFeedback = false; audibleNavigationFeedback = true }
        focus(true); assertFalse(shadowOf(vibrator).isVibrating)
    }
    @Test fun `absent vibrator remains silent`() {
        shadowOf(vibrator).setHasVibrator(false)
        focus(); assertFalse(shadowOf(vibrator).isVibrating)
    }
    @Test fun `non focus events do not vibrate`() {
        interceptor.handle(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_SCROLLED))
        assertFalse(shadowOf(vibrator).isVibrating)
    }
}