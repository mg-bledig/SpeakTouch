package com.neo.speaktouch.controller

import android.app.AlertDialog
import android.content.Context
import android.os.SystemClock
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
        fun execute(operation: () -> MenuResult) {
            if (session !== captured) return
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
            if (session === captured && button.parent === rows) button.performAccessibilityAction(
                AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
        }
        val buttons = captured.commands.map { command ->
            Button(context).apply {
                text = command.label
                setOnClickListener {
                    if (session !== captured) return@setOnClickListener
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
                                if (session !== captured) return@save
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
                    focusFirst(buttons.first())
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
    private fun feedback(id: Int) {
        speech.speak(Text(id))
    }
}
