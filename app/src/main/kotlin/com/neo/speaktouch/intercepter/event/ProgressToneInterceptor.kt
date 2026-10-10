package com.neo.speaktouch.intercepter.event

import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.neo.speaktouch.controller.ProgressToneController
import com.neo.speaktouch.controller.ServiceController
import com.neo.speaktouch.intercepter.event.contract.EventInterceptor
import com.neo.speaktouch.model.Type
import com.neo.speaktouch.utils.extension.progressFraction
import dagger.hilt.android.scopes.ServiceScoped
import javax.inject.Inject

/** Tone feedback has its own cadence and lifecycle, independent of percentage speech. */
@ServiceScoped
class ProgressToneInterceptor @Inject constructor(
    private val service: ServiceController,
    private val tones: ProgressToneController
) : EventInterceptor {
    private var target: AccessibilityNodeInfoCompat? = null
    private var lastValue: Double? = null
    private var lastToneAt: Long? = null
    private var touching = false

    override fun handle(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> { touching = true; tones.stop(); return }
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_END -> { touching = false; return }
            AccessibilityEvent.TYPE_GESTURE_DETECTION_START -> { tones.stop(); return }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> { clear(); return }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P ||
                    event.windowChanges and AccessibilityEvent.WINDOWS_CHANGE_ACTIVE != 0) clear()
                return
            }
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED -> {
                if (event.source == null || event.source == target?.unwrap()) clear()
                return
            }
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED -> {
                clear()
                val node = event.source?.let(AccessibilityNodeInfoCompat::wrap) ?: return
                if (eligible(node)) { target = node; lastValue = node.progressFraction() }
                return
            }
        }
        if (touching || event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event.contentChangeTypes and AccessibilityEvent.CONTENT_CHANGE_TYPE_STATE_DESCRIPTION == 0) return
        val node = event.source?.let(AccessibilityNodeInfoCompat::wrap) ?: return
        if (!eligible(node)) { if (node == target) clear(); return }
        if (target != node) { clear(); target = node }
        val fraction = node.progressFraction() ?: run { tones.stop(); lastValue = null; return }
        if (fraction == lastValue) return
        lastValue = fraction
        val now = SystemClock.uptimeMillis()
        // Drop rapid intermediate updates, never defer or queue them. Completion may replace a tone immediately.
        if (fraction != 1.0 && lastToneAt?.let { now - it < 100L } == true) return
        if (tones.play(fraction)) lastToneAt = now
    }

    private fun eligible(node: AccessibilityNodeInfoCompat): Boolean {
        if (!node.isAccessibilityFocused || !node.isVisibleToUser || Type.get(node) != Type.ProgressBar) return false
        val root = service.getRoot() ?: return false
        return root.windowId == node.windowId && service.getFocused() == node.unwrap()
    }
    private fun clear() { tones.stop(); target = null; lastValue = null; lastToneAt = null }
    override fun finish() { clear() }
}
