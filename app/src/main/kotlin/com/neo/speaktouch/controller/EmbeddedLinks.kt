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

import android.os.Build
import android.os.Bundle
import android.os.Parcel
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ClickableSpan
import android.util.Base64
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat

/** Immutable capability data; never retain an executable span across menu focus changes. */
internal data class EmbeddedLink(
    val sessionToken: Long,
    val order: Int,
    val label: String,
    val start: Int,
    val end: Int,
    val sourceText: String,
    val identity: String,
    val actionId: Int? = null,
    val spanId: Int? = null
)

internal object EmbeddedLinks {
    // AndroidX's provider wire format. It is needed to validate identity and malformed extras
    // before getText() reconstructs its spans; no private fields or hidden methods are accessed.
    private const val PREFIX = "androidx.view.accessibility.AccessibilityNodeInfoCompat."
    private const val SPAN_ARGUMENT = "ACCESSIBILITY_CLICKABLE_SPAN_ID"
    private val frameworkSpanClasses = setOf(
        "android.text.style.AccessibilityClickableSpan", "android.text.style.AccessibilityURLSpan"
    )

    fun discover(node: AccessibilityNodeInfoCompat, token: Long): List<EmbeddedLink> =
        read(node, token).map { it.first }

    /** Dispatch a freshly resolved provider span. A void native callback cannot prove it opened. */
    fun activate(node: AccessibilityNodeInfoCompat, saved: EmbeddedLink): Boolean {
        val current = read(node, saved.sessionToken).firstOrNull { it.first == saved } ?: return false
        return try {
            if (saved.actionId != null && saved.spanId != null) {
                node.performAction(saved.actionId, Bundle().apply { putInt(SPAN_ARGUMENT, saved.spanId) })
            } else {
                AccessibilitySpanBridge.click(current.second ?: return false)
                true
            }
        } catch (_: RuntimeException) { false }
    }

    private fun read(node: AccessibilityNodeInfoCompat, token: Long): List<Pair<EmbeddedLink, ClickableSpan?>> {
        if (node.isPassword || node.isAccessibilityDataSensitive ||
            !node.isVisibleToUser || !node.isEnabled) return emptyList()
        return try {
            val raw = node.unwrap().text ?: return emptyList()
            val text = raw.toString()
            val extras = node.extras
            val starts = extras.getIntegerArrayList(PREFIX + "SPANS_START_KEY")
            if (!starts.isNullOrEmpty()) {
                val ends = extras.getIntegerArrayList(PREFIX + "SPANS_END_KEY") ?: return emptyList()
                val flags = extras.getIntegerArrayList(PREFIX + "SPANS_FLAGS_KEY") ?: return emptyList()
                val ids = extras.getIntegerArrayList(PREFIX + "SPANS_ID_KEY") ?: return emptyList()
                val action = extras.getInt(PREFIX + "SPANS_ACTION_ID_KEY", 0)
                if (ends.size != starts.size || flags.size != starts.size || ids.size != starts.size ||
                    action == 0 || node.actionList.none { it.id == action }) return emptyList()
                starts.indices.mapNotNull { i ->
                    val start = starts[i]; val end = ends[i]; val id = ids[i]
                    val label = label(text, start, end) ?: return@mapNotNull null
                    if (id < 0) return@mapNotNull null
                    // Validate flags/range with the same public Spannable API used by AndroidX.
                    SpannableString(text).setSpan(Any(), start, end, flags[i])
                    EmbeddedLink(token, i, label, start, end, text,
                        "compat:$action:$id:${flags[i]}", action, id) to null
                }
            } else {
                if (Build.VERSION.SDK_INT < 26) return emptyList()
                val spanned = node.text as? Spanned ?: return emptyList()
                spanned.getSpans(0, spanned.length, ClickableSpan::class.java).mapIndexedNotNull { i, span ->
                    // Raw app-process spans (including raw URLSpan) are not service callbacks.
                    if (span.javaClass.name !in frameworkSpanClasses) return@mapIndexedNotNull null
                    val start = spanned.getSpanStart(span); val end = spanned.getSpanEnd(span)
                    val label = label(text, start, end) ?: return@mapIndexedNotNull null
                    val identity = frameworkIdentity(span, label, spanned.getSpanFlags(span))
                    EmbeddedLink(token, i, label, start, end, text, identity) to span
                }
            }
        } catch (_: RuntimeException) { emptyList() }
    }

    private fun label(text: String, start: Int, end: Int): String? =
        if (start >= 0 && end > start && end <= text.length)
            text.substring(start, end).takeIf { it.isNotBlank() } else null

    private fun frameworkIdentity(span: ClickableSpan, label: String, flags: Int): String {
        // Public text parceling preserves the framework proxy's original span ID (and URL),
        // but omits transient connection data. Thus a refresh can match a new proxy safely.
        val text = SpannableString(label).apply { setSpan(span, 0, length, flags) }
        val parcel = Parcel.obtain()
        return try {
            TextUtils.writeToParcel(text, parcel, 0)
            Base64.encodeToString(parcel.marshall(), Base64.NO_WRAP)
        } finally { parcel.recycle() }
    }
}
