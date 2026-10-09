/*
 * Speech Controller.
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

package com.neo.speaktouch.controller

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.neo.speaktouch.model.Text
import com.neo.speaktouch.utils.Node
import com.neo.speaktouch.utils.Reader
import timber.log.Timber

class SpeechController(
    private val textToSpeech: TextToSpeech,
    private val context: Context,
    private val reader: Reader
) {
    val isSpeaking: Boolean get() = textToSpeech.isSpeaking
    val isActivelySpeaking: Boolean
        @Synchronized get() = playing != null && pausedText == null && textToSpeech.isSpeaking

    private data class Playing(val text: String, val id: String)
    private var playing: Playing? = null
    private var pausedText: String? = null
    private var sequence = 0L
    // Touch-to-stop precedes native gesture recognition. This is scoped to that
    // interaction, not a user-resumable pause or a history of stopped speech.
    private var touchStoppedText: String? = null
    private var detectingGesture = false

    init {
        textToSpeech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = finish(utteranceId)
            override fun onError(utteranceId: String?) = finish(utteranceId)
            override fun onError(utteranceId: String?, errorCode: Int) = finish(utteranceId)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = finish(utteranceId)
        })
    }

    @Synchronized private fun finish(id: String?) {
        if (id != null && playing?.id == id) playing = null
    }

    private fun clearTouch() {
        touchStoppedText = null
        detectingGesture = false
    }

    private fun clearState() {
        playing = null
        pausedText = null
        clearTouch()
    }

    @Synchronized private fun speak(text: CharSequence) {
        Timber.i("speak:  \"$text\"")
        clearState()
        val saved = text.toString()
        if (saved.isEmpty()) {
            textToSpeech.stop()
            return
        }
        val current = Playing(saved, "speaktouch-${++sequence}")
        playing = current
        if (textToSpeech.speak(saved, TextToSpeech.QUEUE_FLUSH, null, current.id) != TextToSpeech.SUCCESS &&
            playing === current) clearState()
    }

    fun speak(text: Text) = speak(text.resolved(context))

    fun speak(nodeInfo: AccessibilityNodeInfoCompat) {
        Node.log(nodeInfo)
        speak(reader.read(nodeInfo))
    }

    @Synchronized fun stop() {
        clearState()
        textToSpeech.stop()
    }

    /** Discard saved pause/session text without stopping a new announcement. */
    @Synchronized fun cancelResume() {
        pausedText = null
        clearTouch()
    }

    /** Called only for a recognized native two-finger single tap. */
    @Synchronized fun togglePauseResume() {
        pausedText?.let {
            speak(it)
            return
        }
        val text = touchStoppedText ?: playing?.takeIf { isSpeaking }?.text ?: return
        playing = null // Ignore late callbacks for the output we are stopping.
        pausedText = text
        clearTouch()
        textToSpeech.stop()
    }

    @Synchronized fun onTouchStart() {
        clearTouch()
        val audible = isSpeaking
        if (audible) touchStoppedText = playing?.text
        val submitted = playing != null
        playing = null
        if (audible || submitted) textToSpeech.stop()
    }

    @Synchronized fun onGestureDetectionStart() {
        detectingGesture = true
    }

    @Synchronized fun onTouchEnd() {
        // Android sends touch-end before delivering a completed gesture. Retain
        // only this native gesture's text until its callback (or next interaction).
        // Ordinary exploration ends here and cannot later be restarted.
        if (!detectingGesture) clearTouch()
    }

    @Synchronized fun shutdown() {
        clearState()
        textToSpeech.shutdown()
    }
}
