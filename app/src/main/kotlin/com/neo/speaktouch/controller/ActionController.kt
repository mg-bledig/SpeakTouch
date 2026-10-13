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

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import com.neo.speaktouch.R
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ServiceScoped
import java.util.Collections
import javax.inject.Inject

enum class ActionKind { STANDARD, CUSTOM }
enum class ActionPolicy { SUPPORTED_NO_ARGUMENT, REQUIRES_DEDICATED_HANDLING, UNSUPPORTED }

data class NodeAction(
    val id: Int,
    val providerLabel: String?,
    val kind: ActionKind,
    val displayLabel: String?,
    val policy: ActionPolicy
)

/** Target identity is kept out of immutable action descriptors. */
class ActionSnapshot internal constructor(
    private val target: AccessibilityNodeInfo,
    private val windowId: Int,
    actions: List<NodeAction>
) {
    val actions: List<NodeAction> = Collections.unmodifiableList(ArrayList(actions))
    val supportedActions: List<NodeAction> get() = actions.filter {
        it.policy == ActionPolicy.SUPPORTED_NO_ARGUMENT
    }
    internal fun matches(node: AccessibilityNodeInfo): Boolean =
        target == node && windowId == node.windowId
}

@ServiceScoped
class ActionController @Inject constructor(
    private val serviceController: ServiceController,
    @ApplicationContext private val context: Context
) {
    fun discover(): ActionSnapshot? {
        val node = serviceController.getFocused() ?: return null
        if (!node.refresh() || !isCurrent(node)) return null
        return ActionSnapshot(node, node.windowId, node.actionList.map(::describe))
    }

    fun execute(snapshot: ActionSnapshot, action: NodeAction): Boolean {
        if (action !in snapshot.actions) return false
        return execute(snapshot, action.id)
    }

    fun execute(snapshot: ActionSnapshot, actionId: Int): Boolean {
        if (snapshot.supportedActions.none { it.id == actionId }) return false
        val node = serviceController.getFocused() ?: return false
        if (!snapshot.matches(node) || !node.refresh() || !snapshot.matches(node) ||
            !node.isEnabled || !isCurrent(node)) return false
        val action = node.actionList.firstOrNull { it.id == actionId } ?: return false
        if (describe(action).policy != ActionPolicy.SUPPORTED_NO_ARGUMENT) return false
        return node.performAction(actionId)
    }

    private fun isCurrent(node: AccessibilityNodeInfo): Boolean {
        if (!node.isVisibleToUser || !node.isAccessibilityFocused) return false
        val current = serviceController.getFocused() ?: return false
        val root = serviceController.getRoot() ?: return false
        return current == node && root.windowId == node.windowId
    }

    private fun describe(action: AccessibilityNodeInfo.AccessibilityAction): NodeAction {
        val label = action.label?.toString()
        val custom = action.id > 0x01FFFFFF
        val fallback = when (action.id) {
            AccessibilityNodeInfo.ACTION_DISMISS -> R.string.action_dismiss
            AccessibilityNodeInfo.ACTION_EXPAND -> R.string.action_expand
            AccessibilityNodeInfo.ACTION_COLLAPSE -> R.string.action_collapse
            else -> null
        }
        val policy = when {
            custom -> if (!label.isNullOrBlank()) ActionPolicy.SUPPORTED_NO_ARGUMENT
                else ActionPolicy.UNSUPPORTED
            fallback != null -> ActionPolicy.SUPPORTED_NO_ARGUMENT
            action.id in dedicatedActions -> ActionPolicy.REQUIRES_DEDICATED_HANDLING
            else -> ActionPolicy.UNSUPPORTED
        }
        return NodeAction(action.id, label,
            if (custom) ActionKind.CUSTOM else ActionKind.STANDARD,
            label?.takeIf { it.isNotBlank() } ?: fallback?.let(context::getString), policy)
    }

    private companion object {
        // These remain owned by activation, navigation, sliders or future editing UI.
        val dedicatedActions = setOf(
            AccessibilityNodeInfo.ACTION_CLICK, AccessibilityNodeInfo.ACTION_LONG_CLICK,
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
            AccessibilityNodeInfo.ACTION_SET_TEXT, AccessibilityNodeInfo.ACTION_SET_SELECTION,
            AccessibilityNodeInfo.ACTION_COPY, AccessibilityNodeInfo.ACTION_CUT,
            AccessibilityNodeInfo.ACTION_PASTE, AccessibilityNodeInfo.ACTION_SELECT,
            AccessibilityNodeInfo.ACTION_CLEAR_SELECTION,
            16908349, // ACTION_SET_PROGRESS (API 24)
            16908373, 16908374, 16908375 // Drag start/drop/cancel (API 32)
        )
    }
}
