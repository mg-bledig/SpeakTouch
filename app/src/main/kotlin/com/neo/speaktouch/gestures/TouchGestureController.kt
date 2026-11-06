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

package com.neo.speaktouch.gestures

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.TouchInteractionController
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.SystemClock
import android.view.Display
import android.view.MotionEvent
import com.neo.speaktouch.controller.DragFeedbackController
import androidx.annotation.RequiresApi
import dagger.hilt.android.scopes.ServiceScoped
import javax.inject.Inject

/** Keeps API-33 callback classes out of the service's pre-33 class-loading path. */
@ServiceScoped
class TouchGestureController @Inject constructor(private val service: AccessibilityService,
    private val dragFeedback: DragFeedbackController? = null,
    private val relocation: com.neo.speaktouch.controller.RelocationFeedbackController? = null) {
    private interface Session {
        fun close()
        fun configurationChanged()
    }
    private var session: Session? = null
    val available: Boolean get() = session != null

    fun start(onGesture: (Int) -> Boolean, onDoubleTap: () -> Boolean, onGestureStarted: () -> Unit): Boolean {
        finish()
        if (Build.VERSION.SDK_INT < 33) return false
        return try {
            session = Api33Session(service, onGesture, onDoubleTap, onGestureStarted, dragFeedback, relocation)
            true
        } catch (_: RuntimeException) { false }
    }

    fun configurationChanged() { session?.configurationChanged() }
    fun finish() {
        dragFeedback?.finish()
        val previous = session
        session = null
        previous?.close()
    }

    @RequiresApi(33)
    private class Api33Session(
        private val service: AccessibilityService,
        private val onGesture: (Int) -> Boolean,
        private val onDoubleTap: () -> Boolean,
        private val onGestureStarted: () -> Unit,
        private val dragFeedback: DragFeedbackController?,
        private val relocation: com.neo.speaktouch.controller.RelocationFeedbackController?
    ) : Session {
        private data class Entry(val display: Display, val controller: TouchInteractionController,
                                 val monitor: TouchInteractionMonitor,
                                 val callback: TouchInteractionController.Callback)
        private val entries = mutableListOf<Entry>()
        private var active = true
        private val flag = AccessibilityServiceInfo.FLAG_SERVICE_HANDLES_DOUBLE_TAP
        private val originallyOwned = service.serviceInfo.flags and flag != 0

        init {
            try {
                service.serviceInfo = service.serviceInfo.apply { flags = flags or flag }
                val displays = service.getSystemService(DisplayManager::class.java).displays
                for (display in displays) {
                    val controller = service.getTouchInteractionController(display.displayId)
                    val driver = object : TouchInteractionMonitor.Driver {
                        override fun getState() = controller.state
                        override fun getMaxPointerCount() = controller.maxPointerCount
                        override fun requestTouchExploration() = controller.requestTouchExploration()
                        override fun requestDragging(pointerId: Int) = controller.requestDragging(pointerId)
                        override fun requestDelegating() = controller.requestDelegating()
                        override fun performClick() {
                            relocation?.noteActivation()
                            controller.performClick()
                        }
                        override fun performLongClickAndStartDrag() {
                            val requestedAt = SystemClock.uptimeMillis()
                            controller.performLongClickAndStartDrag()
                            dragFeedback?.begin(display.displayId, requestedAt) {
                                when (controller.state) {
                                    TouchInteractionController.STATE_DRAGGING,
                                    TouchInteractionController.STATE_DELEGATING -> true
                                    else -> false
                                }
                            }
                        }
                    }
                    val monitor = TouchInteractionMonitor(service.createDisplayContext(display), driver,
                        object : TouchInteractionMonitor.Callbacks {
                            override fun onDoubleTap(): Boolean = active &&
                                service.rootInActiveWindow?.window?.displayId == display.displayId && onDoubleTap.invoke()
                            override fun onGesture(gestureId: Int) {
                                // SpeakTouch's existing gesture policy handles the default display.
                                if (active && display.displayId == Display.DEFAULT_DISPLAY) onGesture.invoke(gestureId)
                            }
                            override fun onGestureStarted() { if (active) onGestureStarted.invoke() }
                        })
                    // Observe drag ownership; forward original events unchanged, on the same executor.
                    val callback = object : TouchInteractionController.Callback {
                        override fun onMotionEvent(event: MotionEvent) {
                            monitor.onMotionEvent(event)
                        }
                        override fun onStateChanged(state: Int) {
                            if (active) dragFeedback?.onInteractionStateChanged(display.displayId, state)
                            monitor.onStateChanged(state)
                        }
                    }
                    entries.add(Entry(display, controller, monitor, callback))
                    // As in BackTalk, callback events and recognizer timers share the main thread.
                    controller.registerCallback(null, callback)
                }
                check(entries.any { it.display.displayId == Display.DEFAULT_DISPLAY })
            } catch (failure: RuntimeException) {
                close()
                throw failure
            }
        }

        override fun configurationChanged() {
            for (entry in entries) entry.monitor.configurationChanged(service.createDisplayContext(entry.display))
        }

        override fun close() {
            if (!active) return
            active = false
            for (entry in entries) {
                entry.monitor.stop()
                try { entry.controller.unregisterCallback(entry.callback) } catch (_: RuntimeException) { }
            }
            entries.clear()
            if (!originallyOwned) try {
                service.serviceInfo = service.serviceInfo.apply { flags = flags and flag.inv() }
            } catch (_: RuntimeException) { }
        }
    }
}
