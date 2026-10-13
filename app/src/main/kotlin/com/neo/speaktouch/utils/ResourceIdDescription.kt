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

package com.neo.speaktouch.utils

import androidx.core.view.accessibility.AccessibilityNodeInfoCompat

/** A naming fallback, never a synthetic identifier or a guessed description. */
internal object ResourceIdDescription {
    private val resourceId = Regex("[A-Za-z0-9_.]+:id/([A-Za-z0-9_]+)")

    fun from(node: AccessibilityNodeInfoCompat): String? {
        if (node.isPassword || node.isAccessibilityDataSensitive) return null
        return node.viewIdResourceName?.let { resourceId.matchEntire(it)?.groupValues?.get(1) }
    }
}
