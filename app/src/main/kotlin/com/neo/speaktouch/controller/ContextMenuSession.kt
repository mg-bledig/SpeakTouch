package com.neo.speaktouch.controller

import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.neo.speaktouch.R

internal enum class MenuOperation { CLICK, LONG_CLICK, PASTE, CLEAR, CHECK, ACTION }
internal data class MenuCommand(val label: String, val actionId: Int,
    val operation: MenuOperation, val checked: Boolean? = null)
internal enum class MenuResult { SUCCESS, TARGET_GONE, ACTION_GONE, FAILED }

/** A one-shot capability for the target captured before the overlay receives focus. */
internal class ContextMenuSession(
    private val target: AccessibilityNodeInfo,
    private val snapshot: ActionSnapshot,
    val token: Long,
    private val windowValid: () -> Boolean,
    private val context: Context
) {
    val windowId: Int = target.windowId
    private var active = true
    val commands: List<MenuCommand> = buildCommands()

    fun invalidate() { active = false }
    fun valid(): Boolean = active && target.refresh() &&
        snapshot.matches(target) && target.isVisibleToUser && windowValid()
    fun cancel() {
        val restore = valid()
        active = false
        if (restore && !target.isAccessibilityFocused)
            target.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
    }
    fun execute(command: MenuCommand): MenuResult {
        if (!valid()) { active = false; return MenuResult.TARGET_GONE }
        active = false // Reject duplicate callbacks before invoking the provider.
        if (command !in commands || !target.isEnabled ||
            !target.actionList.any { it.id == command.actionId }) return MenuResult.ACTION_GONE
        val eligible = when (command.operation) {
            MenuOperation.CLICK -> target.isClickable
            MenuOperation.LONG_CLICK -> target.isLongClickable
            MenuOperation.PASTE, MenuOperation.CLEAR -> target.isEditable
            MenuOperation.CHECK -> target.isCheckable
            MenuOperation.ACTION -> snapshot.supportedActions.any { it.id == command.actionId } &&
                target.actionList.any { it.id == command.actionId &&
                    (it.id <= 0x01ffffff || !it.label.isNullOrBlank()) }
        }
        if (!eligible) return MenuResult.ACTION_GONE
        // Another actor may already have made the requested checked-state change.
        if (command.operation == MenuOperation.CHECK && target.isChecked != command.checked)
            return MenuResult.SUCCESS
        val args = if (command.operation == MenuOperation.CLEAR) Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
        } else null
        return if (target.performAction(command.actionId, args)) MenuResult.SUCCESS else MenuResult.FAILED
    }
    private fun buildCommands(): List<MenuCommand> {
        if (!target.isVisibleToUser || !target.isEnabled) return emptyList()
        val ids = target.actionList.map { it.id }.toSet()
        val result = mutableListOf<MenuCommand>()
        fun add(condition: Boolean, id: Int, label: Int, operation: MenuOperation, checked: Boolean? = null) {
            if (condition && id in ids) result.add(MenuCommand(context.getString(label), id, operation, checked))
        }
        add(target.isClickable, AccessibilityNodeInfo.ACTION_CLICK, R.string.menu_click, MenuOperation.CLICK)
        add(target.isLongClickable, AccessibilityNodeInfo.ACTION_LONG_CLICK, R.string.menu_long_click, MenuOperation.LONG_CLICK)
        add(target.isEditable, AccessibilityNodeInfo.ACTION_PASTE, R.string.menu_paste, MenuOperation.PASTE)
        add(target.isEditable, AccessibilityNodeInfo.ACTION_SET_TEXT, R.string.menu_clear, MenuOperation.CLEAR)
        add(target.isCheckable, AccessibilityNodeInfo.ACTION_CLICK,
            if (target.isChecked) R.string.menu_uncheck else R.string.menu_check,
            MenuOperation.CHECK, target.isChecked)
        snapshot.supportedActions.forEach {
            result.add(MenuCommand(it.displayLabel!!, it.id, MenuOperation.ACTION))
        }
        return result.toList()
    }
}
