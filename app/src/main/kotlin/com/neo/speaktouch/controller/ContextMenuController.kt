package com.neo.speaktouch.controller

import android.app.AlertDialog
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import com.neo.speaktouch.R
import com.neo.speaktouch.model.Text
import dagger.hilt.android.scopes.ServiceScoped
import javax.inject.Inject

@ServiceScoped
class ContextMenuController @Inject constructor(
    private val service: ServiceController,
    private val actions: ActionController,
    private val speech: SpeechController
) {
    private var dialog: AlertDialog? = null
    private var session: ContextMenuSession? = null
    private var nextToken = 0L
    private var closingWindowId: Int? = null
    private var closingUntil = 0L
    val isOpen: Boolean get() = session != null

    fun open() {
        // A second invocation from inside our overlay must never capture a menu row.
        if (isOpen && service.getFocused()?.windowId != session?.windowId) {
            cancel(); return
        }
        release(false)
        val target = service.getFocused()
        val snapshot = actions.discover()
        if (target == null || snapshot == null || !snapshot.matches(target)) {
            feedback(R.string.menu_target_gone); return
        }
        val ownerWindow = target.windowId
        val captured = ContextMenuSession(target, snapshot, ++nextToken,
            { windowValid(ownerWindow) }, service.getOrThrow())
        if (!captured.valid()) { feedback(R.string.menu_target_gone); return }
        if (captured.commands.isEmpty()) { captured.invalidate(); feedback(R.string.menu_empty); return }
        session = captured
        val context = ContextThemeWrapper(service.getOrThrow(), android.R.style.Theme_Material_Light_Dialog_Alert)
        val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val buttons = captured.commands.map { command ->
            Button(context).apply {
                text = command.label
                setOnClickListener {
                    if (session !== captured) return@setOnClickListener
                    rememberClosing(captured)
                    session = null
                    dialog?.dismiss(); dialog = null
                    val result = captured.execute(command)
                    when (result) {
                        MenuResult.TARGET_GONE -> feedback(R.string.menu_target_gone)
                        MenuResult.ACTION_GONE -> feedback(R.string.menu_action_gone)
                        MenuResult.FAILED -> feedback(R.string.menu_failed)
                        MenuResult.COPIED -> feedback(R.string.menu_copied)
                        MenuResult.COPY_UNAVAILABLE -> feedback(R.string.menu_copy_unavailable)
                        MenuResult.COPY_FAILED -> feedback(R.string.menu_copy_failed)
                        MenuResult.SUCCESS -> Unit
                    }
                }
                rows.addView(this)
            }
        }
        val overlay = AlertDialog.Builder(context)
            .setTitle(R.string.menu_title)
            .setView(ScrollView(context).apply { addView(rows) })
            .setNegativeButton(R.string.menu_cancel) { _, _ -> cancel() }
            .create().apply {
                window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
                setOnCancelListener { cancel() }
                setOnDismissListener {
                    if (session === captured) release(false)
                }
                setOnShowListener {
                    buttons.first().post {
                        if (session === captured) buttons.first().performAccessibilityAction(
                            AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
                    }
                }
            }
        dialog = overlay
        try {
            overlay.show()
        } catch (_: WindowManager.BadTokenException) {
            release(false)
            feedback(R.string.menu_failed)
        }
    }

    fun cancel() = release(true)
    fun destroy() = release(false)
    private fun release(restore: Boolean) {
        val old = session
        old?.let(::rememberClosing)
        session = null
        dialog?.dismiss(); dialog = null
        if (restore) old?.cancel() else old?.invalidate()
    }

    /** Overlay/IME events are not application transitions. Validate the captured owner instead. */
    fun onEvent(event: AccessibilityEvent) {
        val captured = session ?: return
        val ownerChanged = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.windowId == captured.windowId &&
            event.packageName?.toString() != service.getOrThrow().packageName
        if (ownerChanged || !captured.valid()) {
            release(false); feedback(R.string.menu_target_gone)
        }
    }

    private fun rememberClosing(captured: ContextMenuSession) {
        closingWindowId = captured.windowId
        closingUntil = SystemClock.uptimeMillis() + 1000
    }

    /** Do not let the ordinary initial-focus path undo a menu dismissal handoff. */
    fun shouldInitializeFocus(event: AccessibilityEvent): Boolean {
        if (isOpen || event.packageName?.toString() == service.getOrThrow().packageName) return false
        val skip = event.windowId == closingWindowId && SystemClock.uptimeMillis() <= closingUntil
        closingWindowId = null
        return !skip
    }
    private fun windowValid(id: Int): Boolean {
        val current = service.getRoot()
        if (current?.windowId == id) return true
        val windows = service.getOrThrow().windows
        if (windows.any { it.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                it.isActive && it.id != id }) return false
        return windows.any { it.id == id && it.root != null }
    }
    private fun feedback(id: Int) = speech.speak(Text(id))
}
