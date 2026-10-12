package com.neo.speaktouch.controller

import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.neo.speaktouch.R
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.neo.speaktouch.utils.copyableText
import com.neo.speaktouch.utils.CustomLabelKey
import com.neo.speaktouch.utils.CustomLabelStore

internal enum class MenuOperation { CLICK, LONG_CLICK, PASTE, CLEAR, CHECK, ACTION, COPY, LINKS, ADD_LABEL, EDIT_LABEL, REMOVE_LABEL }
internal data class MenuCommand(val label: String, val actionId: Int?,
    val operation: MenuOperation, val checked: Boolean? = null)
internal enum class MenuResult { SUCCESS, LINK_DISPATCHED, COPIED, COPY_UNAVAILABLE, COPY_FAILED, LABEL_SAVED, LABEL_REMOVED, TARGET_GONE, ACTION_GONE, FAILED }

/** A one-shot capability for the target captured before the overlay receives focus. */
internal class ContextMenuSession(
    private val target: AccessibilityNodeInfo,
    private val snapshot: ActionSnapshot,
    val token: Long,
    private val windowValid: () -> Boolean,
    private val context: Context,
    private val clipboard: TextClipboard = AndroidTextClipboard(context)
) {
    private val customLabels = CustomLabelStore(context)
    private val labelKey = CustomLabelKey.from(AccessibilityNodeInfoCompat.wrap(target))
    val customLabel: String? = labelKey?.let(customLabels::get)
    val windowId: Int = target.windowId
    private var active = true
    private var labelEditorOpen = false
    val links: List<EmbeddedLink> = java.util.Collections.unmodifiableList(
        EmbeddedLinks.discover(AccessibilityNodeInfoCompat.wrap(target), token))
    val commands: List<MenuCommand> = buildCommands()

    fun invalidate() { active = false }
    fun valid(): Boolean = valid(requireVisible = true)
    private fun valid(requireVisible: Boolean): Boolean = active && target.refresh() &&
        snapshot.matches(target) && (!requireVisible || target.isVisibleToUser) && windowValid()

    // The IME may obscure the original target while editing a label. Other commands
    // and focus restoration continue to use strict visible-target validation.
    fun validForCurrentView(): Boolean = if (labelEditorOpen)
        valid(requireVisible = false) && labelTargetMatches() else valid()
    fun cancel() {
        val restore = valid()
        active = false
        if (restore && !target.isAccessibilityFocused)
            target.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
    }
    fun executeLink(link: EmbeddedLink): MenuResult {
        if (!valid()) { active = false; return MenuResult.TARGET_GONE }
        active = false
        val node = AccessibilityNodeInfoCompat.wrap(target)
        if (link !in links || link.sessionToken != token ||
            link !in EmbeddedLinks.discover(node, token)) return MenuResult.ACTION_GONE
        return if (EmbeddedLinks.activate(node, link)) MenuResult.LINK_DISPATCHED else MenuResult.FAILED
    }
    fun openLabelEditor(command: MenuCommand): Boolean {
        if (command !in commands ||
            command.operation !in setOf(MenuOperation.ADD_LABEL, MenuOperation.EDIT_LABEL) ||
            !valid() || !labelTargetMatches()) return false
        labelEditorOpen = true
        return true
    }

    fun saveLabel(command: MenuCommand, text: String): MenuResult {
        if (!valid(requireVisible = !labelEditorOpen)) { active = false; return MenuResult.TARGET_GONE }
        active = false
        if (command !in commands || command.operation !in setOf(MenuOperation.ADD_LABEL, MenuOperation.EDIT_LABEL) ||
            !labelTargetMatches()) return MenuResult.ACTION_GONE
        return if (customLabels.put(labelKey!!, text)) MenuResult.LABEL_SAVED else MenuResult.FAILED
    }

    private fun labelTargetMatches(): Boolean = target.isEnabled && labelKey != null &&
        CustomLabelKey.from(AccessibilityNodeInfoCompat.wrap(target)) == labelKey
    fun execute(command: MenuCommand): MenuResult {
        if (!valid()) { active = false; return MenuResult.TARGET_GONE }
        active = false // Reject duplicate callbacks before invoking the provider.
        if (command !in commands || !target.isEnabled) return MenuResult.ACTION_GONE
        if (command.operation == MenuOperation.REMOVE_LABEL) {
            if (!labelTargetMatches()) return MenuResult.ACTION_GONE
            return if (customLabels.remove(labelKey!!)) MenuResult.LABEL_REMOVED else MenuResult.FAILED
        }
        if (command.operation == MenuOperation.COPY) {
            val text = ownCopyableText()
                ?: return MenuResult.COPY_UNAVAILABLE
            return if (clipboard.copy(text)) MenuResult.COPIED else MenuResult.COPY_FAILED
        }
        val actionId = command.actionId ?: return MenuResult.ACTION_GONE
        if (!target.actionList.any { it.id == actionId }) return MenuResult.ACTION_GONE
        val eligible = when (command.operation) {
            MenuOperation.COPY, MenuOperation.LINKS, MenuOperation.ADD_LABEL,
            MenuOperation.EDIT_LABEL, MenuOperation.REMOVE_LABEL -> false // Dedicated paths.
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
        return if (target.performAction(actionId, args)) MenuResult.SUCCESS else MenuResult.FAILED
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
        if (ownCopyableText() != null)
            result.add(MenuCommand(context.getString(R.string.menu_copy_text), null, MenuOperation.COPY))
        if (links.isNotEmpty())
            result.add(MenuCommand(context.getString(R.string.menu_links), null, MenuOperation.LINKS))
        snapshot.supportedActions.forEach {
            result.add(MenuCommand(it.displayLabel!!, it.id, MenuOperation.ACTION))
        }
        if (labelKey != null) {
            if (customLabel == null) {
                result.add(MenuCommand(context.getString(R.string.menu_add_label), null, MenuOperation.ADD_LABEL))
            } else {
                result.add(MenuCommand(context.getString(R.string.menu_edit_label), null, MenuOperation.EDIT_LABEL))
                result.add(MenuCommand(context.getString(R.string.menu_remove_label), null, MenuOperation.REMOVE_LABEL))
            }
        }
        return result.toList()
    }
    // Malformed provider span extras can make AndroidX getText() throw. Do not crash the menu.
    private fun ownCopyableText(): String? = runCatching {
        copyableText(AccessibilityNodeInfoCompat.wrap(target))
    }.getOrNull()
}
