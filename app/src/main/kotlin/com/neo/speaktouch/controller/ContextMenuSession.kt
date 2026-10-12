package com.neo.speaktouch.controller

import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.neo.speaktouch.R
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.neo.speaktouch.utils.copyableText

internal enum class MenuOperation { CLICK, LONG_CLICK, PASTE, CLEAR, CHECK, ACTION, COPY, LINKS }
internal data class MenuCommand(val label: String, val actionId: Int?,
    val operation: MenuOperation, val checked: Boolean? = null)
internal enum class MenuResult { SUCCESS, LINK_DISPATCHED, COPIED, COPY_UNAVAILABLE, COPY_FAILED, TARGET_GONE, ACTION_GONE, FAILED }

/** A one-shot capability for the target captured before the overlay receives focus. */
internal class ContextMenuSession(
    private val target: AccessibilityNodeInfo,
    private val snapshot: ActionSnapshot,
    val token: Long,
    private val windowValid: () -> Boolean,
    private val context: Context,
    private val clipboard: TextClipboard = AndroidTextClipboard(context)
) {
    val windowId: Int = target.windowId
    private var active = true
    val links: List<EmbeddedLink> = java.util.Collections.unmodifiableList(
        EmbeddedLinks.discover(AccessibilityNodeInfoCompat.wrap(target), token))
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
    fun executeLink(link: EmbeddedLink): MenuResult {
        if (!valid()) { active = false; return MenuResult.TARGET_GONE }
        active = false
        val node = AccessibilityNodeInfoCompat.wrap(target)
        if (link !in links || link.sessionToken != token ||
            link !in EmbeddedLinks.discover(node, token)) return MenuResult.ACTION_GONE
        return if (EmbeddedLinks.activate(node, link)) MenuResult.LINK_DISPATCHED else MenuResult.FAILED
    }
    fun execute(command: MenuCommand): MenuResult {
        if (!valid()) { active = false; return MenuResult.TARGET_GONE }
        active = false // Reject duplicate callbacks before invoking the provider.
        if (command !in commands || !target.isEnabled) return MenuResult.ACTION_GONE
        if (command.operation == MenuOperation.COPY) {
            val text = ownCopyableText()
                ?: return MenuResult.COPY_UNAVAILABLE
            return if (clipboard.copy(text)) MenuResult.COPIED else MenuResult.COPY_FAILED
        }
        val actionId = command.actionId ?: return MenuResult.ACTION_GONE
        if (!target.actionList.any { it.id == actionId }) return MenuResult.ACTION_GONE
        val eligible = when (command.operation) {
            MenuOperation.COPY, MenuOperation.LINKS -> false // Dedicated paths.
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
        return result.toList()
    }
    // Malformed provider span extras can make AndroidX getText() throw. Do not crash the menu.
    private fun ownCopyableText(): String? = runCatching {
        copyableText(AccessibilityNodeInfoCompat.wrap(target))
    }.getOrNull()
}
