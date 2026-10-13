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

package com.neo.test

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat

/** Isolated #37 fixture. Counters update silently; no controls request accessibility focus. */
class ContextMenuActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setContentView(ScrollView(this).apply { addView(rows) })
        fun label(text: String) = TextView(this).apply {
            this.text = text
            // Each status/target is its own stop, not passive content for the ScrollView.
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            ViewCompat.setScreenReaderFocusable(this, true)
            rows.addView(this)
        }
        fun button(text: String, callback: () -> Unit) = Button(this).apply {
            this.text = text; setOnClickListener { callback() }; rows.addView(this)
        }
        var clicks = 0
        val clicksText = label("Clicks: 0")
        button("Normal button") { clicksText.text = "Clicks: ${++clicks}" }
        var holds = 0
        val holdsText = label("Long clicks: 0")
        button("Long-clickable button") { }.setOnLongClickListener {
            holdsText.text = "Long clicks: ${++holds}"; true
        }
        rows.addView(CheckBox(this).apply { text = "Test checkbox" })
        fun preparePaste(field: EditText) {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Paste fixture", "Paste test text"))
            // Native EditText advertises Paste only with clipboard content AND input focus.
            field.requestFocus()
            field.setSelection(field.length())
        }
        val filled = EditText(this).apply { setText("Filled field"); contentDescription = "Filled field" }
        button("Prepare Paste for filled field") { preparePaste(filled) }
        button("Select first word in filled field") {
            filled.requestFocus()
            filled.setSelection(0, minOf(6, filled.length()))
        }
        rows.addView(filled)
        val empty = EditText(this).apply { hint = "Empty field" }
        button("Prepare Paste for empty field") { preparePaste(empty) }
        rows.addView(empty)
        var retries = 0
        var archives = 0
        val retryText = label("Retry: 0")
        val archiveText = label("Archive: 0")
        val target = label("Action target").apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.context_retry, "Retry"))
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.context_archive, "Archive"))
                }
                override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean =
                    when (action) {
                        R.id.context_retry -> { retryText.text = "Retry: ${++retries}"; true }
                        R.id.context_archive -> { archiveText.text = "Archive: ${++archives}"; true }
                        else -> super.performAccessibilityAction(host, action, args)
                    }
            }
        }
        button("Remove Action target in 15 seconds") {
            handler.postDelayed({ rows.removeView(target) }, 15000)
        }
        label("Copy sample: café 👋, punctuation!")
        TextView(this).apply {
            contentDescription = "Description only"
            minHeight = 64
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            ViewCompat.setScreenReaderFocusable(this, true)
            rows.addView(this)
        }
        label("Visible text").contentDescription = "Preferred description"
        rows.addView(EditText(this).apply {
            hint = "Password field"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText("Secret test value")
        })
        if (android.os.Build.VERSION.SDK_INT >= 30) rows.addView(View(this).apply {
            stateDescription = "Loading"
            isFocusable = true
            minimumHeight = 64
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        })
        fun group(description: String?) {
            rows.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                contentDescription = description
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                ViewCompat.setScreenReaderFocusable(this, true)
                addView(TextView(this@ContextMenuActivity).apply { text = "Download" })
                addView(TextView(this@ContextMenuActivity).apply { text = "Loading" })
            })
        }
        group("Transfer")
        group(null)
        val verification = EditText(this).apply { hint = "Verification field" }
        button("Focus verification field for Paste") {
            // Keep the clipboard unchanged so the previous Copy can be verified.
            verification.requestFocus()
            verification.setSelection(verification.length())
        }
        rows.addView(verification)

        var help = 0
        var license = 0
        val helpText = label("Help links: 0")
        val licenseText = label("License links: 0")
        fun helpClicked() { helpText.text = "Help links: ${++help}" }
        fun licenseClicked() { licenseText.text = "License links: ${++license}" }
        val singleLink = label("Open help").apply {
            movementMethod = LinkMovementMethod.getInstance()
            ViewCompat.enableAccessibleClickableSpanSupport(this)
            text = SpannableString("Open help").apply {
                setSpan(object : URLSpan("https://example.com/help") {
                    // Provider callback is authoritative; this fixture does not launch a browser.
                    override fun onClick(widget: View) { helpClicked() }
                }, 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        fun linkedText() = SpannableString("Read Help and License").apply {
            setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) { helpClicked() }
            }, 5, 9, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) { licenseClicked() }
            }, 14, 21, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val multiLink = label("Read Help and License").apply {
            movementMethod = LinkMovementMethod.getInstance()
            ViewCompat.enableAccessibleClickableSpanSupport(this)
            text = linkedText()
        }
        var comparisonRetries = 0
        val comparisonText = label("Link comparison retries: 0")
        button("Retry link comparison") { comparisonText.text = "Link comparison retries: ${++comparisonRetries}" }
        button("Change link text in 15 seconds") {
            handler.postDelayed({ multiLink.text = "Links replaced" }, 15000)
        }
        button("Remove link text in 15 seconds") {
            handler.postDelayed({ rows.removeView(multiLink) }, 15000)
        }
        button("Restore link text") {
            if (multiLink.parent == null) rows.addView(multiLink, rows.indexOfChild(singleLink) + 1)
            multiLink.text = linkedText()
        }

        label("Custom labels: A, B, shared C and D, then no ID")
        fun unlabeled(idValue: Int, title: String): ImageButton {
            var count = 0
            val status = label("$title clicks: 0")
            return ImageButton(this).apply {
                id = idValue
                setImageResource(android.R.drawable.ic_menu_share)
                minimumHeight = (48 * resources.displayMetrics.density).toInt()
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                setOnClickListener { status.text = "$title clicks: ${++count}" }
                rows.addView(this)
            }
        }
        val share = unlabeled(R.id.custom_label_share, "Custom label A")
        val shareStatus = rows.getChildAt(rows.indexOfChild(share) - 1)
        unlabeled(R.id.custom_label_other, "Custom label B")
        // These two deliberately have one resource ID: assigning either labels both.
        unlabeled(R.id.custom_label_shared, "Shared label C")
        unlabeled(R.id.custom_label_shared, "Shared label D")
        unlabeled(View.NO_ID, "No ID")
        button("App provided label") { }.id = R.id.custom_label_named
        rows.addView(EditText(this).apply {
            id = R.id.custom_label_password
            hint = "Custom label password comparison"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        })
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            button("Sensitive label comparison") { }.apply {
                id = R.id.custom_label_sensitive
                setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES)
            }
        }
        val changeShareId = Runnable {
            share.id = R.id.custom_label_changed
            share.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        }
        val removeShare = Runnable { rows.removeView(share) }
        button("Change custom label A ID in 15 seconds") {
            handler.postDelayed(changeShareId, 15000)
        }
        button("Remove custom label A in 15 seconds") {
            handler.postDelayed(removeShare, 15000)
        }
        button("Restore custom label A") {
            handler.removeCallbacks(changeShareId)
            handler.removeCallbacks(removeShare)
            share.id = R.id.custom_label_share
            // Reinsert beside A's counter, not off-screen at the end of the ScrollView.
            if (share.parent == null) rows.addView(share, rows.indexOfChild(shareStatus) + 1)
        }
        // Compare literal ID pronunciation without adding names to the controls.
        label("ID speech pronunciation")
        unlabeled(R.id.sendButton, "sendButton")
        unlabeled(R.id.send_button, "send_button")
        unlabeled(R.id.IcNotificationBtn, "IcNotificationBtn")
        unlabeled(R.id.item2Button, "item2Button")
    }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }
}
