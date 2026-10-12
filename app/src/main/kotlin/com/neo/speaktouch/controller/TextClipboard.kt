package com.neo.speaktouch.controller

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

internal fun interface TextClipboard {
    fun copy(text: String): Boolean
}

internal class AndroidTextClipboard(private val context: Context) : TextClipboard {
    override fun copy(text: String): Boolean {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return false
        return try {
            clipboard.setPrimaryClip(ClipData.newPlainText("SpeakTouch", text))
            true
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalStateException) {
            false
        }
    }
}
