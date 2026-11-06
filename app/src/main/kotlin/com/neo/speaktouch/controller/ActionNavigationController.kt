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

import android.os.Build
import android.view.accessibility.AccessibilityEvent
import com.neo.speaktouch.R
import com.neo.speaktouch.intercepter.event.contract.EventInterceptor
import com.neo.speaktouch.model.Text
import dagger.hilt.android.scopes.ServiceScoped
import javax.inject.Inject

/** A selection belongs to one focused node, never to a menu or a later focus target. */
@ServiceScoped
class ActionNavigationController @Inject constructor(
    private val actions: ActionController,
    private val speech: SpeechController
) : EventInterceptor {
    private data class Selection(val snapshot: ActionSnapshot, val action: NodeAction)
    private var selected: Selection? = null
    var activationAvailable = false
        private set

    fun setActivationAvailable(available: Boolean) {
        activationAvailable = available
        reset()
    }

    fun reset() { selected = null }

    fun available(): Boolean = activationAvailable && try {
        actions.discover()?.supportedActions?.isNotEmpty() == true
    } catch (_: RuntimeException) { false }

    /** Activate is the normal framework click; it is not a synthetic custom action. */
    fun move(forward: Boolean): Boolean {
        if (!activationAvailable) return false
        return try {
            val snapshot = actions.discover() ?: run { reset(); return false }
            val choices = snapshot.supportedActions
            if (choices.isEmpty()) { reset(); return false }
            val previous = selected?.takeIf { old -> old.snapshot.sameTarget(snapshot) }
            val index = previous?.let { old -> choices.indexOf(old.action) + 1 } ?: 0
            val next = (index + if (forward) 1 else choices.size) % (choices.size + 1)
            selected = if (next == 0) null else Selection(snapshot, choices[next - 1])
            speech.speak(selected?.action?.displayLabel?.let { Text(it) } ?: Text(R.string.navigation_activate))
            true
        } catch (_: RuntimeException) { reset(); false }
    }

    /** True means consumed, including a selected action which has become unavailable. */
    fun activate(): Boolean {
        val selection = selected ?: return false
        if (!activationAvailable) { reset(); return false }
        val result = try {
            val now = actions.discover()
            if (now == null || !selection.snapshot.sameTarget(now) ||
                selection.action !in now.supportedActions) {
                R.string.menu_action_gone
            } else if (!actions.execute(selection.snapshot, selection.action)) R.string.menu_failed else null
        } catch (_: RuntimeException) { R.string.menu_failed }
        if (result != null) {
            reset()
            speech.speak(Text(result))
        }
        return true
    }

    override fun handle(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED -> {
                val selection = selected ?: return
                // Duplicate focus on the same node does not erase the selected action.
                if (event.eventType == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED ||
                    event.source?.let(selection.snapshot::matches) != true) reset()
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> reset()
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> if (Build.VERSION.SDK_INT < 28 ||
                event.windowChanges and AccessibilityEvent.WINDOWS_CHANGE_ACTIVE != 0) reset()
        }
    }

    override fun finish() { setActivationAvailable(false) }
}
