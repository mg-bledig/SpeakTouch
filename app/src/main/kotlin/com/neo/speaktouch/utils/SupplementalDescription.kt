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

import android.os.Build
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat

/** Read only explicit provider metadata; no inference, refresh or tree search. */
internal object SupplementalDescription {
    // Core 1.16 has no getter for this channel. WebView uses this newer AndroidX
    // compatibility key on older Android releases, as confirmed on the Oppo.
    private const val COMPAT_KEY =
        "androidx.view.accessibility.AccessibilityNodeInfoCompat.SUPPLEMENTAL_DESCRIPTION_KEY"

    fun get(node: AccessibilityNodeInfoCompat): CharSequence? {
        if (Build.VERSION.SDK_INT >= 36) {
            node.unwrap().supplementalDescription?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return node.extras.getCharSequence(COMPAT_KEY)?.takeIf { it.isNotBlank() }
    }
}
