/*
 * Read node content.
 *
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

package com.neo.speaktouch.utils

import android.content.Context
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.neo.speaktouch.model.Type
import com.neo.speaktouch.model.toTypeText
import com.neo.speaktouch.utils.extension.getContent
import com.neo.speaktouch.utils.extension.isNotNullOrEmpty
import com.neo.speaktouch.utils.extension.iterator
import com.neo.speaktouch.utils.extension.toStateText
import javax.inject.Inject

class Reader @Inject constructor(
    private val context: Context
) {

    fun read(
        node: AccessibilityNodeInfoCompat,
        options: Options = Options()
    ) = read(node, options, grouped = false, ancestorLabels = emptyList())

    private fun read(
        node: AccessibilityNodeInfoCompat,
        options: Options,
        grouped: Boolean,
        ancestorLabels: List<CharSequence>
    ): String = with(node) {

        val type = Type.get(node)

        val ownContent = getContent(type)
        val composeGroup = (grouped || isScreenReaderFocusable) &&
                !isEditable && !isCheckable && type !is Type.Checkable &&
                type !is Type.EditField && type !is Type.Slider &&
                !NodeValidator.isExplorableCollection(node)
        val content = if (composeGroup) {
            buildList {
                if (ownContent != null && ownContent.isNotEmpty() &&
                    ancestorLabels.none { containsLabel(it, ownContent) }) {
                    add(ownContent)
                }
                val labels = ancestorLabels + listOfNotNull(ownContent)
                val children = readGroupedChildren(node, labels)
                if (children.isNotEmpty()) add(children)
            }.joinToString(", ")
        } else {
            ownContent ?: readChildren(node)
        }

        buildList {

            if (content.isNotNullOrEmpty()) {
                add(content)
            }

            if (options.mustReadType && type != null) {
                type.toTypeText()?.let {
                    add(it.resolved(context))
                }
            }

            if (options.mustReadState) {
                node.toStateText(type)?.let {
                    add(it.resolved(context))
                }
            }
        }.joinToString(
            separator = ", "
        )
    }

    private fun readGroupedChildren(
        node: AccessibilityNodeInfoCompat,
        ancestorLabels: List<CharSequence>
    ): String = buildList {
        for (child in node) {
            val type = Type.get(child)
            if (!child.isVisibleToUser || child.isEditable || child.isCheckable ||
                type is Type.EditField || type is Type.Checkable || type is Type.Slider ||
                NodeValidator.isExplorableCollection(child) ||
                !NodeValidator.isReadableAsChild(child)) continue

            val speech = read(child, Options(
                mustReadState = child.stateDescription.isNotNullOrEmpty(),
                mustReadType = type !is Type.Image
            ), grouped = true, ancestorLabels = ancestorLabels)
            if (speech.isNotEmpty()) add(speech)
        }
    }.joinToString(", ")

    // Compare labels only, never rendered role/state speech or earlier siblings.
    private fun containsLabel(parent: CharSequence, child: CharSequence): Boolean {
        fun normalize(value: CharSequence) = value.toString().trim().replace(Regex("\\s+"), " ")
        val label = normalize(child)
        if (label.isEmpty()) return false
        return Regex("(?<![\\p{L}\\p{N}_])${Regex.escape(label)}(?![\\p{L}\\p{N}_])")
            .containsMatchIn(normalize(parent))
    }

    fun readChildren(
        node: AccessibilityNodeInfoCompat
    ): CharSequence {
        return buildList {
            for (child in node) {

                if (!NodeValidator.isReadableAsChild(child)) continue

                val type = Type.get(child)

                add(
                    read(
                        node = child,
                        options = Options(
                            // Announce explicit descriptions and checkable/slider fallbacks.
                            mustReadState = child.stateDescription.isNotNullOrEmpty() ||
                                    type is Type.Checkable || type is Type.Slider,
                            // Should not announce the type image of children
                            mustReadType = type !is Type.Image
                        )
                    )
                )
            }
        }.joinToString(
            separator = ", "
        )
    }

    data class Options(
        val mustReadState: Boolean = true,
        val mustReadType: Boolean = true,
    )
}
