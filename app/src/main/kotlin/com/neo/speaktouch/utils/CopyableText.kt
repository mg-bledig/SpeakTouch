package com.neo.speaktouch.utils

import android.os.Build
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.neo.speaktouch.model.Type

/** Copy readable leaf content, never control labels or a partial grouped announcement. */
internal fun copyableText(node: AccessibilityNodeInfoCompat): String? {
    if (Build.VERSION.SDK_INT >= 34 && node.unwrap().isAccessibilityDataSensitive) return null
    if (node.isPassword || node.isEditable || node.isCheckable ||
        node.isClickable || node.isLongClickable || node.isScrollable ||
        node.isShowingHintText || node.childCount != 0 ||
        node.collectionInfo != null || node.rangeInfo != null) return null
    when (Type.get(node)) {
        Type.Button, Type.EditField, Type.Slider, Type.ProgressBar,
        Type.DropdownList, Type.List -> return null
        is Type.Checkable -> return null
        else -> Unit
    }
    // Advertised app commands count even if a provider omits interaction flags.
    // Focus, text selection and movement actions alone do not make text an app control.
    if (node.actionList.any { it.id !in copyTextActions }) return null
    val content = node.contentDescription?.takeIf { it.isNotBlank() } ?: node.text
    return content?.toString()?.takeIf { it.isNotBlank() }
}

private val copyTextActions = setOf(
    AccessibilityNodeInfoCompat.ACTION_FOCUS, AccessibilityNodeInfoCompat.ACTION_CLEAR_FOCUS,
    AccessibilityNodeInfoCompat.ACTION_SELECT, AccessibilityNodeInfoCompat.ACTION_CLEAR_SELECTION,
    AccessibilityNodeInfoCompat.ACTION_ACCESSIBILITY_FOCUS,
    AccessibilityNodeInfoCompat.ACTION_CLEAR_ACCESSIBILITY_FOCUS,
    AccessibilityNodeInfoCompat.ACTION_NEXT_AT_MOVEMENT_GRANULARITY,
    AccessibilityNodeInfoCompat.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY,
    AccessibilityNodeInfoCompat.ACTION_NEXT_HTML_ELEMENT, AccessibilityNodeInfoCompat.ACTION_PREVIOUS_HTML_ELEMENT,
    AccessibilityNodeInfoCompat.ACTION_SET_SELECTION, AccessibilityNodeInfoCompat.ACTION_COPY,
    16908342 // Show on screen
)