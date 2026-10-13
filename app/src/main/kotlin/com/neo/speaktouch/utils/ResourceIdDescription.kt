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
