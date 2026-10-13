package com.neo.speaktouch.utils

import android.content.Context

/** Read on demand so a running Reader sees settings changes without a service restart. */
internal class SpeechPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    var speakIds: Boolean
        get() = preferences.getBoolean("speak_ids", false)
        set(value) { preferences.edit().putBoolean("speak_ids", value).apply() }

    companion object { const val FILE_NAME = "speech_preferences" }
}
