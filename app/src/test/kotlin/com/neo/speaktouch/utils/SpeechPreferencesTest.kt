package com.neo.speaktouch.utils

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [22, 31], manifest = Config.NONE)
class SpeechPreferencesTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun clear() {
        context.getSharedPreferences(SpeechPreferences.FILE_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }
    @Test fun `Speak IDs defaults off`() { assertFalse(SpeechPreferences(context).speakIds) }
    @Test fun `new preference instance retains chosen setting`() {
        SpeechPreferences(context).speakIds = true
        assertTrue(SpeechPreferences(context).speakIds)
        SpeechPreferences(context).speakIds = false
        assertFalse(SpeechPreferences(context).speakIds)
    }
    @Test fun `existing instance immediately observes another instance changing the setting`() {
        val existing = SpeechPreferences(context)
        SpeechPreferences(context).speakIds = true; assertTrue(existing.speakIds)
        SpeechPreferences(context).speakIds = false; assertFalse(existing.speakIds)
    }
    @Test fun `setting does not change custom label storage`() {
        val key = CustomLabelKey("example.app", "example.app:id/sendButton")
        CustomLabelStore(context).put(key, "Send message")
        SpeechPreferences(context).speakIds = true
        SpeechPreferences(context).speakIds = false
        assertEquals("Send message", CustomLabelStore(context).get(key))
    }
}
