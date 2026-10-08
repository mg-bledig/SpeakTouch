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
import com.neo.speaktouch.utils.extension.getAssociatedLabels
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
        ancestorLabels: List<CharSequence>,
        spokenContent: MutableList<CharSequence>? = null
    ): String = with(node) {

        val type = Type.get(node)

        val ownContent = getContent(type)
        // Keep content separate from rendered roles/states for associated-label comparison.
        val contentLabels = mutableListOf<CharSequence>()
        val composeGroup = (grouped || isScreenReaderFocusable) &&
                !isEditable && !isCheckable && type !is Type.Checkable &&
                type !is Type.EditField && type !is Type.Slider &&
                !NodeValidator.isExplorableCollection(node)
        val content = if (composeGroup) {
            buildList {
                if (ownContent != null && ownContent.isNotEmpty() &&
                    ancestorLabels.none { containsLabel(it, ownContent) }) {
                    add(ownContent)
                    contentLabels.add(ownContent)
                }
                val labels = ancestorLabels + listOfNotNull(ownContent)
                val children = readGroupedChildren(node, labels, contentLabels)
                if (children.isNotEmpty()) add(children)
            }.joinToString(", ")
        } else {
            ownContent?.also { contentLabels.add(it) } ?: readChildren(node, contentLabels)
        }

        val associatedLabels = associatedLabelContent(node, node.getAssociatedLabels(), contentLabels)
        spokenContent?.addAll(associatedLabels)
        spokenContent?.addAll(contentLabels)

        buildList {

            addAll(associatedLabels)

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
        ancestorLabels: List<CharSequence>,
        spokenContent: MutableList<CharSequence>
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
            ), grouped = true, ancestorLabels = ancestorLabels, spokenContent = spokenContent)
            if (speech.isNotEmpty()) add(speech)
        }
    }.joinToString(", ")

    // A separate composition step also permits list-policy tests on pre-36 test runtimes.
    internal fun associatedLabelContent(
        node: AccessibilityNodeInfoCompat,
        labelNodes: List<AccessibilityNodeInfoCompat?>,
        spokenContent: List<CharSequence>
    ): List<CharSequence> = buildList {
        val seen = mutableSetOf<String>()
        for (labelNode in labelNodes) {
            if (labelNode == null || labelNode == node || !labelNode.isVisibleToUser) continue
            val label = labelNode.getContent() ?: continue
            val normalized = normalizeLabel(label)
            if (normalized.isEmpty() || !seen.add(normalized)) continue
            if (spokenContent.none { containsLabel(it, label) }) add(label)
        }
    }

    private fun normalizeLabel(value: CharSequence) =
        value.toString().trim().replace(Regex("[\\s\\p{Z}]+"), " ")

    // Compare labels only, never rendered role/state speech or earlier siblings.
    private fun containsLabel(parent: CharSequence, child: CharSequence): Boolean {
        val label = normalizeLabel(child)
        if (label.isEmpty()) return false
        return Regex("(?<![\\p{L}\\p{N}_])${Regex.escape(label)}(?![\\p{L}\\p{N}_])")
            .containsMatchIn(normalizeLabel(parent))
    }

    fun readChildren(
        node: AccessibilityNodeInfoCompat
    ): CharSequence = readChildren(node, spokenContent = null)

    private fun readChildren(
        node: AccessibilityNodeInfoCompat,
        spokenContent: MutableList<CharSequence>?
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
                        ),
                        grouped = false,
                        ancestorLabels = emptyList(),
                        spokenContent = spokenContent
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
