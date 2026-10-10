/*
 * Extensions for NodeInfo (AccessibilityNodeInfoCompat).
 *
 * Copyright (C) 2023-2025 Patryk Mis.
 * Copyright (C) 2023 Irineu A. Silva.
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

package com.neo.speaktouch.utils.extension

import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import com.neo.speaktouch.R
import com.neo.speaktouch.model.Type
import com.neo.speaktouch.model.Text
import com.neo.speaktouch.utils.NodeValidator
import java.lang.reflect.Method
import java.text.NumberFormat

fun AccessibilityNodeInfoCompat.getNearestAncestor(
    predicate: (AccessibilityNodeInfoCompat) -> Boolean
): AccessibilityNodeInfoCompat? {
    var current: AccessibilityNodeInfoCompat? = this.parent

    while (current != null && !predicate(current)) {
        current = current.parent
    }

    return current
}

operator fun AccessibilityNodeInfoCompat.iterator() =
    object : Iterator<AccessibilityNodeInfoCompat> {

        var index = -1

        override fun hasNext(): Boolean {
            return childCount != index + 1
        }

        override fun next(): AccessibilityNodeInfoCompat {
            return getChild(++index) ?: throw NoSuchElementException("No more child nodes available")
        }

    }

fun AccessibilityNodeInfo.performFocus(scope: NodeScanScope, mustStop: Boolean = true) {

    performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)

    if (mustStop) scope.stop(result = this)
}

fun AccessibilityNodeInfo.getFocusedOrNull(): AccessibilityNodeInfo? {
    return findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
}

fun AccessibilityNodeInfo.indexOfChild(
    target: AccessibilityNodeInfo
): Int {

    for (index in 0 until childCount) {

        val child = getChild(index)

        if (child == target) {
            return index
        }
    }

    error("Child not found")
}

val AccessibilityNodeInfo.lastIndex get() = childCount - 1

/** Explicit labels only; callers decide which label content is suitable for speech. */
fun AccessibilityNodeInfoCompat.getAssociatedLabels(): List<AccessibilityNodeInfoCompat?> {
    return if (Build.VERSION.SDK_INT >= 36) {
        unwrap().labeledByList.map { label -> label?.let { AccessibilityNodeInfoCompat.wrap(it) } }
    } else {
        listOfNotNull(labeledBy)
    }
}

fun AccessibilityNodeInfoCompat.toStateText(
    type: Type? = Type.get(this)
): Text? {

    if (stateDescription.isNotNullOrEmpty() &&
        (type !is Type.ProgressBar || stateDescription.toString().isNotBlank())) {
        return Text(stateDescription.toString())
    }

    if (type is Type.Slider) {
        return toSliderStateText()
    }

    if (type is Type.ProgressBar) {
        return toProgressBarStateText()
    }

    if (type is Type.Checkable) {
        return toCheckableStateText(type)
    }

    // The selection state has a deliberately different behavior from talkback.
    // Discussion at: https://github.com/NeoA11y/SpeakTouch/discussions/115

    if (isSelected) {
        return Text(R.string.text_selected)
    }

    return null
}

private fun AccessibilityNodeInfoCompat.toProgressBarStateText(): Text? {
    // Native indeterminate ProgressBars expose no range. Keep any provider state
    // description above, but never invent a percentage when the range is absent.
    val fraction = progressFraction() ?: return null
    return Text(NumberFormat.getPercentInstance().format(fraction))
}

/** Valid determinate progress, shared by reading and completion feedback. */
internal fun AccessibilityNodeInfoCompat.progressFraction(): Double? {
    val range = rangeInfo ?: return null
    val min = range.min.toDouble()
    val max = range.max.toDouble()
    val current = range.current.toDouble()
    if (!min.isFinite() || !max.isFinite() || !current.isFinite() ||
        min >= max || current < min || current > max) return null

    val fraction = when (range.type) {
        AccessibilityNodeInfoCompat.RangeInfoCompat.RANGE_TYPE_INT,
        AccessibilityNodeInfoCompat.RangeInfoCompat.RANGE_TYPE_FLOAT -> (current - min) / (max - min)
        AccessibilityNodeInfoCompat.RangeInfoCompat.RANGE_TYPE_PERCENT -> {
            if (current < 0.0 || current > 100.0) return null
            current / 100.0
        }
        else -> return null
    }
    return fraction
}

private fun AccessibilityNodeInfoCompat.toSliderStateText(): Text? {
    val range = rangeInfo ?: return null
    val current = range.current
    if (!range.min.isFinite() || !range.max.isFinite() || !current.isFinite() ||
        range.min >= range.max || current < range.min || current > range.max) return null

    val value = current.toString().toBigDecimal()
    val formatted = when (range.type) {
        AccessibilityNodeInfoCompat.RangeInfoCompat.RANGE_TYPE_INT ->
            NumberFormat.getIntegerInstance().format(value.toBigInteger())
        AccessibilityNodeInfoCompat.RangeInfoCompat.RANGE_TYPE_FLOAT ->
            NumberFormat.getNumberInstance().apply { maximumFractionDigits = 340 }.format(value)
        AccessibilityNodeInfoCompat.RangeInfoCompat.RANGE_TYPE_PERCENT ->
            NumberFormat.getPercentInstance().apply { maximumFractionDigits = 340 }
                .format(value.movePointLeft(2))
        else -> return null
    }
    return Text(formatted)
}

fun AccessibilityNodeInfoCompat.toCheckableStateText(
    type: Type.Checkable
): Text? {
    when (type) {
        Type.Checkable.Checkbox -> {
            if (stateDescription.isNullOrEmpty()) {
                return if (isChecked) {
                    Text(R.string.text_checked)
                } else {
                    Text(R.string.text_not_checked)
                }
            }

            return Text(stateDescription.toString())
        }

        Type.Checkable.Radio -> {
            if (stateDescription.isNullOrEmpty()) {
                return if (isChecked) {
                    Text(R.string.text_selected)
                } else {
                    Text(R.string.text_not_selected)
                }
            }

            return Text(stateDescription.toString())
        }

        Type.Checkable.Switch -> {
            if (stateDescription.isNullOrEmpty()) {
                return if (isChecked) {
                    Text(R.string.text_enabled)
                } else {
                    Text(R.string.text_disabled)
                }
            }

            return Text(stateDescription.toString())
        }

        Type.Checkable.Toggle -> {
            if (stateDescription.isNullOrEmpty()) {
                return if (isChecked) {
                    Text(R.string.text_pressed)
                } else {
                    Text(R.string.text_not_pressed)
                }
            }

            return Text(stateDescription.toString())
        }

        Type.Checkable.TextView -> {

            if (!isChecked) return null /* don't speak */

            if (stateDescription.isNullOrEmpty()) {
                return Text(R.string.text_selected)
            }

            return Text(stateDescription.toString())
        }

        Type.Checkable.Custom -> {
            if (stateDescription.isNullOrEmpty()) {
                return if (isChecked) {
                    Text(R.string.text_selected)
                } else {
                    Text(R.string.text_not_selected)
                }
            }

            return Text(stateDescription.toString())
        }
    }
}

fun AccessibilityNodeInfoCompat.getContent(
    type: Type? = Type.get(this)
): CharSequence? {

    // Deliberately different behavior from talkback
    // https://github.com/NeoA11y/SpeakTouch/discussions/119
    // https://github.com/NeoA11y/SpeakTouch/discussions/121

    if (type is Type.EditField) {

        return when {
            text.isNotNullOrEmpty() -> text
            hintText.isNotNullOrEmpty() -> hintText
            else -> null
        }
    }

    if (contentDescription.isNotNullOrEmpty()) {
        return contentDescription
    }

    if (text.isNotNullOrEmpty()) {
        return text
    }

    return null
}

fun AccessibilityNodeInfoCompat.AccessibilityActionCompat.getName(): String {
    val clazz = AccessibilityNodeInfoCompat::class.java

    val method: Method = clazz.getDeclaredMethod(
        "getActionSymbolicName",
        Int::class.javaPrimitiveType
    )

    method.isAccessible = true

    return method.invoke(null, id) as String
}
