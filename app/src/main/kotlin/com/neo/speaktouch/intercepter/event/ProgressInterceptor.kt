package com.neo.speaktouch.intercepter.event

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.neo.speaktouch.controller.ServiceController
import com.neo.speaktouch.controller.SpeechController
import com.neo.speaktouch.intercepter.event.contract.EventInterceptor
import com.neo.speaktouch.model.Text
import com.neo.speaktouch.model.Type
import com.neo.speaktouch.utils.extension.progressFraction
import com.neo.speaktouch.utils.extension.toStateText
import dagger.hilt.android.scopes.ServiceScoped
import javax.inject.Inject

/** One latest-value callback per focused native progress bar, on the service's main looper. */
@ServiceScoped
class ProgressInterceptor @Inject constructor(
    private val service: ServiceController,
    private val speech: SpeechController,
    private val context: Context
) : EventInterceptor {
    private data class Update(val fraction: Double, val text: String)
    private val handler = Handler(Looper.getMainLooper())
    private var target: AccessibilityNodeInfoCompat? = null
    private var lastSpokenAt: Long? = null
    private val spokenValues = mutableSetOf<Double>()
    private val spokenText = mutableSetOf<String>()
    private var pending: Update? = null
    private var touching = false
    private val deliver = Runnable {
        val update = pending
        clearPending()
        val focused = stillFocused()
        if (!focused) clear()
        else if (update != null && !touching) announce(update)
    }

    override fun handle(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> {
                touching = true; clearPending(); return
            }
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_END -> { touching = false; return }
            AccessibilityEvent.TYPE_GESTURE_DETECTION_START -> { clearPending(); return }
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
                if (!eligible(node)) return
                target = node
                // SpeechInterceptor has already read this state as part of the focus announcement.
                update(node)?.let {
                    remember(it)
                    lastSpokenAt = SystemClock.uptimeMillis()
                }
                return
            }
        }
        if (touching || event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event.contentChangeTypes and AccessibilityEvent.CONTENT_CHANGE_TYPE_STATE_DESCRIPTION == 0) return
        val node = event.source?.let(AccessibilityNodeInfoCompat::wrap) ?: return
        if (!eligible(node)) return
        if (target != node) { clear(); target = node }
        val update = update(node) ?: run { clearPending(); return }
        // An already-spoken latest value also supersedes an older pending value.
        if (update.fraction in spokenValues || update.text in spokenText) { clearPending(); return }
        val deadline = lastSpokenAt?.plus(INTERVAL_MS)
        if (update.fraction == 1.0 || deadline == null || SystemClock.uptimeMillis() >= deadline) {
            clearPending()
            announce(update)
        } else {
            pending = update
            handler.removeCallbacks(deliver)
            handler.postAtTime(deliver, deadline)
        }
    }

    private fun eligible(node: AccessibilityNodeInfoCompat): Boolean {
        if (!node.isAccessibilityFocused || !node.isVisibleToUser || Type.get(node) != Type.ProgressBar) return false
        val root = service.getRoot() ?: return false
        return root.windowId == node.windowId && service.getFocused() == node.unwrap()
    }
    private fun stillFocused(): Boolean {
        val current = service.getFocused()?.let(AccessibilityNodeInfoCompat::wrap) ?: return false
        return current == target && eligible(current) && current.progressFraction() != null
    }
    private fun update(node: AccessibilityNodeInfoCompat): Update? {
        val fraction = node.progressFraction() ?: return null
        val text = node.toStateText(Type.ProgressBar)?.resolved(context)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return Update(fraction, text)
    }
    private fun remember(update: Update) {
        spokenValues.add(update.fraction)
        spokenText.add(update.text)
    }
    private fun announce(update: Update) {
        remember(update)
        lastSpokenAt = SystemClock.uptimeMillis()
        speech.speak(Text(update.text))
    }
    private fun clearPending() { handler.removeCallbacks(deliver); pending = null }
    private fun clear() {
        clearPending(); target = null; lastSpokenAt = null; spokenValues.clear(); spokenText.clear()
    }
    override fun finish() { clear() }
    private companion object { const val INTERVAL_MS = 5000L }
}
