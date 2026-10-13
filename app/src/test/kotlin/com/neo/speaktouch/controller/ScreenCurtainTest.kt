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

package com.neo.speaktouch.controller

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.DialogInterface
import android.os.Build
import android.speech.tts.TextToSpeech
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.appcompat.widget.SwitchCompat
import com.neo.speaktouch.SettingsActivity
import com.neo.speaktouch.intercepter.event.CallbackInterceptor
import com.neo.speaktouch.intercepter.gesture.GestureInterceptor
import com.neo.speaktouch.utils.Reader
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [22, 28, 31, 33])
class ScreenCurtainTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var service: SliderTestService
    private lateinit var speech: SpeechController
    private lateinit var tts: TextToSpeech
    private lateinit var curtain: ScreenCurtainController
    private lateinit var host: TestCurtainWindow
    private lateinit var gestures: GestureInterceptor
    private fun spoken() = shadowOf(tts).spokenTextList
    private class TestCurtainWindow : CurtainWindow {
        var attached = false
        var adds = 0
        var removes = 0
        var resizes = 0
        var raises = 0
        var failAttach = false
        var failRemove = false
        override fun attach() {
            if (failAttach) throw WindowManager.BadTokenException()
            attached = true; adds++
        }
        override fun remove() {
            if (failRemove) throw IllegalStateException("remove failed")
            if (attached) removes++
            attached = false
        }
        override fun resize() { resizes++ }
        override fun raise() { raises++ }
    }
    @Before fun setup() {
        // AndroidX's signature permission is present in the merged manifest, but
        // Robolectric does not automatically grant it on pre-33 receiver paths.
        val receiverPermission = "${context.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
        val packageManager = context.packageManager
        assertEquals(android.content.pm.PermissionInfo.PROTECTION_SIGNATURE,
            packageManager.getPermissionInfo(receiverPermission, 0).protectionLevel and
                android.content.pm.PermissionInfo.PROTECTION_MASK_BASE)
        assertTrue(packageManager.getPackageInfo(context.packageName,
            android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().contains(receiverPermission))
        shadowOf(context).grantPermissions(receiverPermission)
        assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED,
            androidx.core.content.ContextCompat.checkSelfPermission(context, receiverPermission))
        service = Robolectric.buildService(SliderTestService::class.java).create().get()
        shadowOf(context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).setKeyguardLocked(false)
        tts = TextToSpeech(context, null)
        speech = SpeechController(tts, context, Reader(context))
        host = TestCurtainWindow()
        curtain = ScreenCurtainController(service, speech).apply { window = host }
        curtain.start()
        val wrapper = ServiceController(service)
        gestures = GestureInterceptor(FocusController(CallbackInterceptor(), wrapper), wrapper,
            SliderController(wrapper, speech), RefocusController(wrapper, speech), speech,
            screenCurtainController = curtain)
    }
    @After fun cleanup() {
        // Preserve the original setup failure instead of adding a lateinit failure.
        if (::host.isInitialized) host.failRemove = false
        if (::curtain.isInitialized) curtain.destroy()
    }
    @Test fun `enable disable are idempotent and announce once after success`() {
        curtain.enable(); curtain.enable()
        assertTrue(curtain.isEnabled); assertTrue(host.attached); assertEquals(1, host.adds)
        curtain.disable(); curtain.disable()
        assertFalse(curtain.isEnabled); assertFalse(host.attached); assertEquals(1, host.removes)
        assertEquals(listOf("Screen curtain on", "Screen curtain off"), spoken())
    }
    @Test fun `attachment failure leaves OFF and never announces ON`() {
        host.failAttach = true; curtain.enable()
        assertFalse(curtain.isEnabled); assertFalse(host.attached)
        assertEquals(listOf("Screen curtain could not be changed"), spoken())
    }
    @Test fun `failed removal never announces OFF`() {
        curtain.enable(); host.failRemove = true; curtain.disable()
        assertTrue(curtain.isEnabled)
        assertEquals(listOf("Screen curtain on", "Screen curtain could not be changed"), spoken())
    }
    @Test fun `window is opaque noninteractive and excluded from accessibility`() {
        val window = BlackCurtainWindow(service)
        val flags = window.params.flags
        assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, window.params.type)
        assertTrue(flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
        assertTrue(flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        assertEquals(android.graphics.PixelFormat.OPAQUE, window.params.format)
        assertEquals(1f, window.params.alpha, 0f)
        assertEquals(android.graphics.Color.BLACK, (window.view.background as android.graphics.drawable.ColorDrawable).color)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, window.view.importantForAccessibility)
        assertFalse(window.view.isFocusable); assertFalse(window.view.isClickable)
        assertNull(window.view.contentDescription)
        assertEquals(-1f, window.params.screenBrightness, 0f)
        assertEquals(0, flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    @Test fun `actual window uses display size and updates coverage`() {
        val window = BlackCurtainWindow(service)
        window.attach()
        val size = android.graphics.Point()
        (service.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealSize(size)
        assertEquals(size.x, window.params.width); assertEquals(size.y, window.params.height)
        val display = (service.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        shadowOf(display).setRealWidth(720); shadowOf(display).setRealHeight(1280)
        window.resize()
        assertEquals(720, window.params.width); assertEquals(1280, window.params.height)
        window.raise(); window.remove(); window.remove()
    }
    @Test fun `reconnect and destroy reset state and availability`() {
        curtain.enable(); curtain.start()
        assertFalse(curtain.isEnabled); assertFalse(host.attached)
        curtain.enable(); curtain.destroy(); curtain.destroy()
        assertFalse(curtain.isEnabled); assertNull(ScreenCurtainSession.controller)
        assertEquals(listOf("Screen curtain on", "Screen curtain on"), spoken())
    }
    @Test fun `screen off clears curtain and screen on does not restore it`() {
        curtain.enable()
        service.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse(curtain.isEnabled)
        service.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse(curtain.isEnabled)
    }
    @Test fun `lock clears curtain and prevents enable`() {
        curtain.enable()
        shadowOf(context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).setKeyguardLocked(true)
        curtain.onEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED))
        assertFalse(curtain.isEnabled); curtain.enable(); assertFalse(curtain.isEnabled)
        assertEquals(1, host.adds)
    }
    @Test fun `configuration resizes enabled curtain only`() {
        curtain.onConfigurationChanged(); assertEquals(0, host.resizes)
        curtain.enable(); curtain.onConfigurationChanged(); assertEquals(1, host.resizes)
        assertTrue(curtain.isEnabled); assertEquals(1, host.adds)
    }
    @Test fun `app and IME window changes keep curtain on top without toggling state`() {
        curtain.enable(); curtain.onEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED))
        assertEquals(1, host.raises); assertTrue(curtain.isEnabled)
        assertEquals(listOf("Screen curtain on"), spoken())
    }
    @Test fun `native gesture is guarded by API and preserves idle slider behavior`() {
        val handled = gestures.handle(AccessibilityService.GESTURE_3_FINGER_DOUBLE_TAP)
        assertEquals(Build.VERSION.SDK_INT >= 30, handled)
        assertEquals(Build.VERSION.SDK_INT >= 30, curtain.isEnabled)
        if (handled) { gestures.handle(AccessibilityService.GESTURE_3_FINGER_DOUBLE_TAP); assertFalse(curtain.isEnabled) }
        assertFalse(gestures.handle(AccessibilityService.GESTURE_SWIPE_UP))
        assertFalse(gestures.handle(AccessibilityService.GESTURE_SWIPE_DOWN))
    }
    @Test
    // Context attachment does not create Android's accessibility-manager connection.
    // Reuse the existing serviceInfo shadow; execute the real SpeakTouch lifecycle hooks.
    @Config(shadows = [PauseServiceShadow::class])
    fun `service reconnect configuration and unbind invoke curtain lifecycle`() {
        // buildService attaches the base context. Do not call create(): this
        // isolated lifecycle test supplies its dependency instead of invoking Hilt.
        val target = Robolectric.buildService(com.neo.speaktouch.service.SpeakTouchService::class.java).get().apply {
            screenCurtainController = curtain
            serviceInfo = android.accessibilityservice.AccessibilityServiceInfo()
        }
        curtain.enable()
        assertNotNull(target.serviceInfo)
        target.javaClass.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }.invoke(target)
        assertFalse(curtain.isEnabled)
        curtain.enable()
        target.onConfigurationChanged(android.content.res.Configuration())
        assertEquals(1, host.resizes)
        target.onUnbind(null)
        assertFalse(curtain.isEnabled); assertNull(ScreenCurtainSession.controller)
    }
    private fun toggle(view: View): SwitchCompat? = when (view) {
        is SwitchCompat -> view.takeIf { it.text.toString() == "Screen Curtain" }
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { toggle(view.getChildAt(it)) }
        else -> null
    }
    @Test fun `Settings confirmation cancel and success reflect live state`() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val switch = toggle(activity.get().window.decorView)!!
        assertTrue(switch.isEnabled); assertFalse(switch.isChecked)
        switch.performClick()
        val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        assertTrue(dialog.isShowing); assertFalse(switch.isChecked); assertFalse(curtain.isEnabled)
        val message = dialog.findViewById<android.widget.TextView>(android.R.id.message)!!.text.toString()
        assertEquals(Build.VERSION.SDK_INT >= 30, message.contains("three-finger double tap"))
        if (Build.VERSION.SDK_INT < 30) assertTrue(message.contains("swiping down then right"))
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        // AlertController dispatches button callbacks and dismissal through its Handler.
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse(curtain.isEnabled); assertFalse(switch.isChecked)
        switch.performClick()
        (ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog)
            .getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(curtain.isEnabled); assertTrue(switch.isChecked)
        switch.performClick(); assertFalse(curtain.isEnabled); assertFalse(switch.isChecked)
        activity.pause().stop().destroy()
    }
    @Test fun `Settings follows gesture and service state without persisting ON`() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val switch = toggle(activity.get().window.decorView)!!
        curtain.enable(); assertTrue(switch.isChecked)
        curtain.start(); assertFalse(switch.isChecked)
        curtain.destroy(); assertFalse(switch.isEnabled); assertFalse(switch.isChecked)
        activity.pause().stop().destroy()
        val next = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        assertFalse(toggle(next.get().window.decorView)!!.isChecked)
        next.pause().stop().destroy()
    }
    @Test fun `Settings unavailable service is disabled and OFF`() {
        curtain.destroy()
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val switch = toggle(activity.get().window.decorView)!!
        assertFalse(switch.isEnabled); assertFalse(switch.isChecked)
        activity.pause().stop().destroy()
    }
}
