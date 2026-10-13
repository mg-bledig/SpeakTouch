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

import android.content.Context
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import org.json.JSONArray

/** Resource IDs name control kinds: repeated IDs share labels, even across app updates. */
internal data class CustomLabelKey(val packageName: String, val resourceName: String) {
    internal val storageKey: String get() = JSONArray().put(packageName).put(resourceName).toString()

    companion object {
        fun from(node: AccessibilityNodeInfoCompat): CustomLabelKey? {
            if (node.isPassword || node.isAccessibilityDataSensitive) return null
            val packageName = node.packageName?.toString() ?: return null
            val resourceName = node.viewIdResourceName ?: return null
            if (!packageName.matches(Regex("[A-Za-z0-9_.]+")) ||
                !resourceName.matches(Regex("[A-Za-z0-9_.]+:id/[A-Za-z0-9_]+"))) return null
            return CustomLabelKey(packageName, resourceName)
        }
    }
}

/** Only the key and user-entered text are saved; no node content, version or instance identity. */
internal class CustomLabelStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun get(key: CustomLabelKey): String? = preferences.getString(key.storageKey, null)?.takeIf { it.isNotBlank() }

    fun put(key: CustomLabelKey, text: String): Boolean {
        val label = text.trim()
        if (label.isEmpty()) return false
        // A small synchronous write acknowledges persistence before the editor reports success.
        return preferences.edit().putString(key.storageKey, label).commit()
    }

    fun remove(key: CustomLabelKey): Boolean = preferences.edit().remove(key.storageKey).commit()

    companion object { const val FILE_NAME = "custom_labels" }
}
