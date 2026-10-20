/*
 * Speak Touch accessibility service.
 *
 * Copyright (C) 2023 Irineu A. Silva.
 * Copyright (C) 2023 Patryk Miś.
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

package com.neo.speaktouch.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Build
import android.content.Intent
import android.content.res.Configuration
import com.neo.speaktouch.controller.ScreenCurtainController
import com.neo.speaktouch.controller.ScreenChangeController
import android.view.accessibility.AccessibilityEvent
import android.view.KeyEvent
import com.neo.speaktouch.controller.ContextMenuController
import com.neo.speaktouch.controller.Controllers
import com.neo.speaktouch.controller.FocusController
import com.neo.speaktouch.controller.VolumeController
import com.neo.speaktouch.intercepter.Interceptors
import com.neo.speaktouch.intercepter.event.contract.EventInterceptor
import com.neo.speaktouch.utils.extension.addFlags
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class SpeakTouchService : AccessibilityService () {

    @Inject
    lateinit var interceptors: Interceptors

    @Inject
    lateinit var focusController: FocusController

    @Inject
    lateinit var volumeController: VolumeController

    @Inject lateinit var screenChangeController: ScreenChangeController

    @Inject lateinit var screenCurtainController: ScreenCurtainController

    @Inject lateinit var contextMenuController: ContextMenuController

    override fun onCreate() {
        super.onCreate()

        Controllers.install(this)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (::interceptors.isInitialized) interceptors.navigationSound.onServiceConnected()
        if (::interceptors.isInitialized) interceptors.speech.start()
        if (::interceptors.isInitialized) interceptors.checkState.start()
        if (::interceptors.isInitialized) interceptors.boundaryFeedback.start()
        if (::interceptors.isInitialized) interceptors.scrollFeedback.start()
        if (::interceptors.isInitialized) interceptors.manualScrollFocus.start()
        if (::screenCurtainController.isInitialized) screenCurtainController.start()
        if (::screenChangeController.isInitialized) screenChangeController.start()

        addFlags(AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            addFlags(AccessibilityServiceInfo.FLAG_ENABLE_ACCESSIBILITY_VOLUME,
                AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            addFlags(AccessibilityServiceInfo.FLAG_REQUEST_MULTI_FINGER_GESTURES)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            addFlags(AccessibilityServiceInfo.FLAG_REQUEST_2_FINGER_PASSTHROUGH)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        screenChangeController.observe(event)
        screenCurtainController.onEvent(event)
        contextMenuController.onEvent(event)
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (contextMenuController.shouldInitializeFocus(event)) focusController.moveFocusToFirst()
            }
        }

        interceptors.event.forEach {
            it.handle(event)
        }
    }

    override fun onDestroy() {
        if (::screenChangeController.isInitialized) screenChangeController.destroy()
        screenCurtainController.destroy()
        contextMenuController.destroy()
        super.onDestroy()

        interceptors.event.forEach(EventInterceptor::finish)

        Controllers.uninstall()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (::interceptors.isInitialized) interceptors.navigationSound.finish()
        if (::interceptors.isInitialized) interceptors.speech.clearEditing()
        if (::interceptors.isInitialized) interceptors.checkState.finish()
        if (::interceptors.isInitialized) interceptors.boundaryFeedback.finish()
        if (::interceptors.isInitialized) interceptors.scrollFeedback.finish()
        if (::interceptors.isInitialized) interceptors.manualScrollFocus.finish()
        if (::screenChangeController.isInitialized) screenChangeController.destroy()
        if (::screenCurtainController.isInitialized) screenCurtainController.destroy()
        if (::contextMenuController.isInitialized) contextMenuController.destroy()
        return super.onUnbind(intent)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::screenCurtainController.isInitialized) screenCurtainController.onConfigurationChanged()
    }

    override fun onInterrupt() = Unit

    override fun onKeyEvent(event: KeyEvent): Boolean = volumeController.handle(event)

    @Deprecated("Deprecated in Java")
    override fun onGesture(gestureId: Int): Boolean {
        if (::screenChangeController.isInitialized) screenChangeController.cancelPageFeedback()
        return interceptors.gesture.handle(gestureId)
    }
}
