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
import com.neo.speaktouch.R
import com.neo.speaktouch.model.Text
import com.neo.speaktouch.model.Type
import com.neo.speaktouch.model.toTypeText

/** Speech-only metadata: never changes a node's type, grouping or action eligibility. */
internal object NodeRoleDescription {
    fun get(node: AccessibilityNodeInfoCompat, type: Type?): Text? {
        node.roleDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
            return Text(it)
        }
        type?.toTypeText()?.let { return it }

        // Chromium's explicit machine role, not clickability or the node's label/URL.
        // https://chromium.googlesource.com/chromium/src/+/HEAD/content/public/android/java/src/org/chromium/content/browser/accessibility/AccessibilityNodeInfoBuilder.java
        if (node.extras.getCharSequence("AccessibilityNodeInfo.chromeRole")?.toString() == "link") {
            return Text(R.string.text_link_type)
        }
        return null
    }
}
