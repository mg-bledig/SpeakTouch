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

import android.app.AlertDialog
import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.ContextThemeWrapper
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.view.inputmethod.InputMethodManager
import com.neo.speaktouch.R
import com.neo.speaktouch.SettingsActivity
import com.neo.speaktouch.model.Text
import dagger.hilt.android.scopes.ServiceScoped
import javax.inject.Inject

@ServiceScoped
class ContextMenuController @Inject constructor(
    private val service: ServiceController,
    private val actions: ActionController,
    private val speech: SpeechController,
    private val screenCurtainController: ScreenCurtainController? = null
) {
    private var dialog: AlertDialog? = null
    private var session: ContextMenuSession? = null
    private var nextToken = 0L
    private val curtainFeedbackHandler = Handler(Looper.getMainLooper())
    private var pendingCurtainFeedback: Runnable? = null
    private var curtainFeedbackIssuedAt = 0L
    private var closingWindowId: Int? = null
    private var closingUntil = 0L
    val isOpen: Boolean get() = dialog != null

    fun open() {
        cancelCurtainFeedback()
        // A second invocation from inside our overlay must never capture a menu row.
        if (isOpen && service.getFocused()?.windowId != session?.windowId) {
            cancel(); return
        }
        release(false)
        val target = service.getFocused()
        val snapshot = actions.discover()
        val menuToken = ++nextToken
        val captured = if (target != null && snapshot != null && snapshot.matches(target)) {
            val ownerWindow = target.windowId
            ContextMenuSession(target, snapshot, menuToken,
                { windowValid(ownerWindow) }, service.getOrThrow()).takeIf { it.valid() }
        } else null
        session = captured
        fun currentMenu() = dialog != null && nextToken == menuToken
        val context = ContextThemeWrapper(service.getOrThrow(), android.R.style.Theme_Material_Light_Dialog_Alert)
        val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        fun execute(operation: () -> MenuResult) {
            if (!currentMenu() || captured == null || session !== captured) return
            rememberClosing(captured)
            session = null
            dialog?.dismiss(); dialog = null
            when (operation()) {
                MenuResult.TARGET_GONE -> feedback(R.string.menu_target_gone)
                MenuResult.ACTION_GONE -> feedback(R.string.menu_action_gone)
                MenuResult.FAILED -> feedback(R.string.menu_failed)
                MenuResult.COPIED -> feedback(R.string.menu_copied)
                MenuResult.COPY_UNAVAILABLE -> feedback(R.string.menu_copy_unavailable)
                MenuResult.COPY_FAILED -> feedback(R.string.menu_copy_failed)
                MenuResult.LABEL_SAVED -> feedback(R.string.menu_label_saved)
                MenuResult.LABEL_REMOVED -> feedback(R.string.menu_label_removed)
                MenuResult.SUCCESS, MenuResult.LINK_DISPATCHED -> Unit
            }
        }
        fun focusFirst(button: Button) = button.post {
            if (currentMenu() && session === captured && button.parent === rows) button.performAccessibilityAction(
                AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
        }
        val buttons = captured?.let { captured -> captured.commands.map { command ->
            Button(context).apply {
                text = command.label
                setOnClickListener {
                    if (!currentMenu() || session !== captured) return@setOnClickListener
                    if (command.operation == MenuOperation.LINKS) {
                        if (!captured.valid()) {
                            release(false); feedback(R.string.menu_target_gone)
                            return@setOnClickListener
                        }
                        // Change rows in the same overlay; keep the original target/session.
                        rows.removeAllViews()
                        dialog?.setTitle(R.string.menu_links)
                        captured.links.forEach { link ->
                            rows.addView(Button(context).apply {
                                text = link.label
                                setOnClickListener { execute { captured.executeLink(link) } }
                            })
                        }
                        focusFirst(rows.getChildAt(0) as Button)
                    } else if (command.operation == MenuOperation.ADD_LABEL || command.operation == MenuOperation.EDIT_LABEL) {
                        if (!captured.openLabelEditor(command)) {
                            release(false); feedback(R.string.menu_action_gone)
                            return@setOnClickListener
                        }
                        // Like Links, the editor stays in this overlay and original target session.
                        rows.removeAllViews()
                        dialog?.setTitle(command.label)
                        val editor = EditText(context).apply {
                            hint = context.getString(R.string.menu_label_hint)
                            inputType = InputType.TYPE_CLASS_TEXT
                            setSingleLine(true)
                            setText(if (command.operation == MenuOperation.EDIT_LABEL) captured.customLabel else "")
                            setSelection(length())
                        }
                        rows.addView(editor)
                        rows.addView(Button(context).apply {
                            setText(R.string.menu_save)
                            setOnClickListener save@{
                                if (!currentMenu() || session !== captured) return@save
                                val text = editor.text.toString()
                                if (text.isBlank()) {
                                    editor.error = context.getString(R.string.menu_label_empty)
                                    return@save
                                }
                                execute { captured.saveLabel(command, text) }
                            }
                        })
                        dialog?.window?.apply {
                            // The original button-only dialog had no text input. Remove the IME
                            // exclusion it acquired before inserting a real editor dynamically.
                            clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or
                                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                        }
                        editor.requestFocus()
                        editor.post {
                            if (session === captured && editor.parent === rows) {
                                editor.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
                                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                                    .showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
                            }
                        }
                    } else {
                        execute { captured.execute(command) }
                    }
                }
                rows.addView(this)
            }
        } }.orEmpty() + Button(context).apply {
            setText(R.string.menu_settings)
            setOnClickListener {
                if (!currentMenu()) return@setOnClickListener
                // A SpeakTouch command: release the app capability without executing or
                // refreshing its target, and do not restore focus before switching windows.
                release(false)
                val serviceContext = service.getOrThrow()
                try {
                    serviceContext.startActivity(Intent(serviceContext, SettingsActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: ActivityNotFoundException) {
                    feedback(R.string.menu_failed)
                } catch (_: SecurityException) {
                    feedback(R.string.menu_failed)
                }
            }
            rows.addView(this)
        }
        Button(context).apply {
            setText(R.string.screen_curtain)
            setOnClickListener {
                if (!currentMenu()) return@setOnClickListener
                release(true)
                // State changes now; only feedback waits for asynchronous menu/focus events.
                screenCurtainController?.toggle { queueCurtainFeedback(it, target?.windowId) }
            }
            rows.addView(this)
        }
        val overlay = AlertDialog.Builder(context)
            .setTitle(R.string.menu_title)
            .setView(ScrollView(context).apply { addView(rows) })
            .setNegativeButton(R.string.menu_cancel) { _, _ -> cancel() }
            .create().apply {
                window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
                setOnCancelListener { cancel() }
                setOnDismissListener {
                    if (currentMenu()) release(false)
                }
                setOnShowListener {
                    focusFirst(buttons.first())
                }
            }
        dialog = overlay
        try {
            overlay.show()
            screenCurtainController?.coverNewWindow()
        } catch (_: WindowManager.BadTokenException) {
            release(false)
            feedback(R.string.menu_failed)
        }
    }

    fun cancel() { cancelCurtainFeedback(); release(true) }
    fun destroy() { cancelCurtainFeedback(); release(false) }
    private fun release(restore: Boolean) {
        val old = session
        old?.let(::rememberClosing)
        session = null
        val oldDialog = dialog
        dialog = null
        oldDialog?.dismiss()
        if (restore) old?.cancel() else old?.invalidate()
    }

    /** Overlay/IME events are not application transitions. Validate the captured owner instead. */
    fun onEvent(event: AccessibilityEvent) {
        pendingCurtainFeedback?.let { pending ->
            when (event.eventType) {
                AccessibilityEvent.TYPE_TOUCH_INTERACTION_START,
                AccessibilityEvent.TYPE_GESTURE_DETECTION_START -> {
                    if (event.eventTime > curtainFeedbackIssuedAt) cancelCurtainFeedback()
                }
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_WINDOWS_CHANGED,
                AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
                AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED,
                AccessibilityEvent.TYPE_VIEW_FOCUSED,
                AccessibilityEvent.TYPE_TOUCH_INTERACTION_END -> {
                    // A late restoration event may flush speech or finish the activation touch.
                    // Run after the last such event, never in front of SpeechInterceptor.
                    curtainFeedbackHandler.removeCallbacks(pending)
                    curtainFeedbackHandler.postDelayed(pending, CURTAIN_FEEDBACK_SETTLE_MS)
                }
            }
        }
        val captured = session ?: return
        val ownerChanged = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.windowId == captured.windowId &&
            event.packageName?.toString() != service.getOrThrow().packageName
        if (ownerChanged || !captured.validForCurrentView()) {
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
    private fun queueCurtainFeedback(id: Int, ownerWindow: Int?) {
        cancelCurtainFeedback()
        curtainFeedbackIssuedAt = SystemClock.uptimeMillis()
        val pending = Runnable {
            pendingCurtainFeedback = null
            val currentWindow = service.getRoot()?.windowId
            val stateMatches = when (id) {
                R.string.screen_curtain_on -> screenCurtainController?.isEnabled == true
                R.string.screen_curtain_off -> screenCurtainController?.isEnabled == false
                else -> true
            }
            if (stateMatches && !isOpen && (ownerWindow == null || currentWindow == null || currentWindow == ownerWindow))
                feedback(id)
        }
        pendingCurtainFeedback = pending
        curtainFeedbackHandler.postDelayed(pending, CURTAIN_FEEDBACK_SETTLE_MS)
    }
    private fun cancelCurtainFeedback() {
        pendingCurtainFeedback?.let(curtainFeedbackHandler::removeCallbacks)
        pendingCurtainFeedback = null
    }
    private companion object {
        // Binder-delivered focus/window restoration is asynchronous to Dialog.dismiss().
        const val CURTAIN_FEEDBACK_SETTLE_MS = 150L
    }
    private fun feedback(id: Int) {
        speech.speak(Text(id))
    }
}
