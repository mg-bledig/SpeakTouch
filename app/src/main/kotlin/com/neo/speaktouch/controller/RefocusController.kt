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

import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import dagger.hilt.android.scopes.ServiceScoped
import javax.inject.Inject

@ServiceScoped
class RefocusController @Inject constructor(
    private val serviceController: ServiceController,
    private val speechController: SpeechController
) {
    fun reread() {
        val focused = serviceController.getFocused() ?: return
        val node = AccessibilityNodeInfoCompat.wrap(focused)
        if (!node.refresh() || !node.isVisibleToUser || !node.isAccessibilityFocused) return

        // Refresh can race with a focus or window change. Never read an old target.
        val current = serviceController.getFocused() ?: return
        val root = serviceController.getOrThrow().rootInActiveWindow ?: return
        if (current != focused || root.windowId != node.windowId) return

        speechController.speak(node)
    }
}
