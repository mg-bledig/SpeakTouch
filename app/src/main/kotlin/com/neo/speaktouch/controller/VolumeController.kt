package com.neo.speaktouch.controller

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.view.KeyEvent
import dagger.hilt.android.scopes.ServiceScoped
import javax.inject.Inject

@ServiceScoped
class VolumeController @Inject constructor(
    context: Context,
    private val speechController: SpeechController
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private data class Key(val deviceId: Int, val code: Int)
    private data class Press(val downTime: Long, val claimed: Boolean)
    private val presses = mutableMapOf<Key, Press>()

    fun handle(event: KeyEvent): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            (event.keyCode != KeyEvent.KEYCODE_VOLUME_UP && event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN)) return false
        val key = Key(event.deviceId, event.keyCode)
        val press = presses[key]?.takeIf { it.downTime == event.downTime }
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount < 0 || event.downTime > event.eventTime) return false
                if (event.repeatCount == 0) {
                    // Duplicate initial downs must not adjust twice or steal a silent press.
                    if (press != null) return press.claimed
                    val claimed = !event.isCanceled && speechController.isActivelySpeaking && adjust(event.keyCode)
                    presses[key] = Press(event.downTime, claimed)
                    claimed
                } else {
                    if (press?.claimed != true) return false
                    if (!event.isCanceled && speechController.isActivelySpeaking) adjust(event.keyCode)
                    true
                }
            }
            KeyEvent.ACTION_UP -> {
                if (press == null) return false
                presses.remove(key)
                press.claimed
            }
            else -> false
        }
    }

    private fun adjust(code: Int): Boolean {
        val direction = if (code == KeyEvent.KEYCODE_VOLUME_UP) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        return try {
            audioManager.adjustStreamVolume(AudioManager.STREAM_ACCESSIBILITY, direction, 0)
            true
        } catch (error: SecurityException) {
            // On an initial denial, leave the whole press to Android. An already
            // owned press must still be consumed through its matching key-up.
            false
        }
    }
}
