package com.neo.test

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }
}
