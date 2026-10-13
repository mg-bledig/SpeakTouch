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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import com.neo.speaktouch.R
import com.neo.speaktouch.model.Text
import dagger.hilt.android.scopes.ServiceScoped
import java.lang.ref.WeakReference
import javax.inject.Inject

/** Session-only bridge: Settings never stores or restores an ON preference. */
internal object ScreenCurtainSession {
    private var owner = WeakReference<ScreenCurtainController>(null)
    private val listeners = mutableSetOf<() -> Unit>()
    val controller: ScreenCurtainController? get() = owner.get()
    fun attach(controller: ScreenCurtainController) { owner = WeakReference(controller); changed() }
    fun detach(controller: ScreenCurtainController) {
        if (owner.get() === controller) { owner.clear(); changed() }
    }
    fun observe(listener: () -> Unit) { listeners.add(listener) }
    fun removeObserver(listener: () -> Unit) { listeners.remove(listener) }
    fun changed() { listeners.toList().forEach { it() } }
}

internal interface CurtainWindow {
    fun attach()
    fun remove()
    fun resize()
    fun raise()
}

/** All calls come from the service/UI main thread. No global display state is changed. */
@ServiceScoped
class ScreenCurtainController @Inject constructor(
    private val service: AccessibilityService,
    private val speech: SpeechController
) {
    internal var window: CurtainWindow = BlackCurtainWindow(service)
    var isEnabled: Boolean = false
        private set
    private var registered = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF || isLocked()) disable(announce = false)
        }
    }

    fun start() {
        disable(announce = false)
        if (!registered) {
            ContextCompat.registerReceiver(service, receiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
        }
        ScreenCurtainSession.attach(this)
    }

    fun toggle(feedback: (Int) -> Unit = ::feedback) { if (isEnabled) disable(feedback = feedback) else enable(feedback) }
    fun enable(feedback: (Int) -> Unit = ::feedback) {
        if (isEnabled) return
        if (isLocked()) { feedback(R.string.screen_curtain_unavailable); return }
        try {
            window.attach()
            isEnabled = true
            ScreenCurtainSession.changed()
            feedback(R.string.screen_curtain_on)
        } catch (_: RuntimeException) {
            runCatching { window.remove() }
            feedback(R.string.screen_curtain_failed)
        }
    }
    fun disable(announce: Boolean = true, feedback: (Int) -> Unit = ::feedback) {
        if (!isEnabled) return
        try {
            window.remove()
            isEnabled = false
            ScreenCurtainSession.changed()
            if (announce) feedback(R.string.screen_curtain_off)
        } catch (_: RuntimeException) {
            // Do not report OFF if WindowManager did not remove the window.
            if (announce) feedback(R.string.screen_curtain_failed)
        }
    }
    fun onConfigurationChanged() {
        if (isEnabled) runCatching { window.resize() }.onFailure { disable() }
    }
    fun onEvent(event: AccessibilityEvent) {
        if (!isEnabled) return
        if (isLocked()) { disable(announce = false); return }
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) coverNewWindow()
    }
    /** Keep newly opened menu/IME/app windows beneath the curtain, without taking focus. */
    fun coverNewWindow() {
        if (isEnabled) runCatching { window.raise() }.onFailure { disable() }
    }
    fun destroy() {
        disable(announce = false)
        if (registered) { service.unregisterReceiver(receiver); registered = false }
        ScreenCurtainSession.detach(this)
    }
    private fun isLocked(): Boolean =
        (service.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
    private fun feedback(id: Int) = speech.speak(Text(id))
}

internal class BlackCurtainWindow(service: AccessibilityService) : CurtainWindow {
    private val manager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    internal val view = View(service).apply {
        setBackgroundColor(Color.BLACK)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        isFocusable = false
        isClickable = false
        setAccessibilityDelegate(object : View.AccessibilityDelegate() {
            override fun sendAccessibilityEvent(host: View, eventType: Int) {
                // This visual-only surface must not trigger initial focus or speech.
            }
        })
    }
    internal val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
        PixelFormat.OPAQUE
    ).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        title = "SpeakTouch Screen Curtain"
        if (Build.VERSION.SDK_INT >= 28)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        if (Build.VERSION.SDK_INT >= 30) {
            setFitInsetsTypes(0)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
    }
    private var attached = false
    private fun size() {
        val size = Point()
        @Suppress("DEPRECATION")
        manager.defaultDisplay.getRealSize(size)
        params.width = size.x
        params.height = size.y
    }
    override fun attach() {
        if (attached) return
        size()
        manager.addView(view, params)
        attached = true
    }
    override fun remove() {
        if (!attached) return
        if (view.parent != null) manager.removeViewImmediate(view)
        attached = false
    }
    override fun resize() { size(); if (attached) manager.updateViewLayout(view, params) }
    override fun raise() { remove(); attach() }
}
