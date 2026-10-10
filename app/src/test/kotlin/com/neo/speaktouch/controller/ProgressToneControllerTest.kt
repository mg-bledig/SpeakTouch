package com.neo.speaktouch.controller

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAudioTrack

@Implements(AudioTrack::class)
class ProgressAudioShadow : ShadowAudioTrack() {
    var stops = 0
    var releases = 0
    var marker = 0
    var listener: AudioTrack.OnPlaybackPositionUpdateListener? = null
    @Implementation fun stop() { stops++ }
    @Implementation fun release() { releases++ }
    @Implementation fun native_set_marker_pos(position: Int): Int { marker = position; return 0 }
    @Implementation fun setPlaybackPositionUpdateListener(value: AudioTrack.OnPlaybackPositionUpdateListener?, handler: Handler?) {
        listener = value
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 28, 31], shadows = [ProgressAudioShadow::class])
class ProgressToneControllerTest {
    private lateinit var controller: ProgressToneController
    private val tracks = mutableListOf<AudioTrack>()
    private val data = mutableListOf<ByteArray>()
    private val recorder = ShadowAudioTrack.OnAudioDataWrittenListener { track, bytes, _ ->
        tracks.add(track); data.add(bytes.copyOf())
    }
    private fun shadow(track: AudioTrack): ProgressAudioShadow = Shadow.extract(track)
    @Before fun setup() { controller = ProgressToneController(); ShadowAudioTrack.addAudioDataListener(recorder) }
    @After fun cleanup() { controller.stop(); ShadowAudioTrack.removeAudioDataListener(recorder) }

    @Test fun `low progress has a lower frequency and completion has the highest pitch`() {
        assertEquals(220.0, ProgressToneController.frequency(0.0)!!, 0.0)
        assertEquals(880.0, ProgressToneController.frequency(1.0)!!, 0.0)
        assertTrue(ProgressToneController.frequency(0.1)!! < ProgressToneController.frequency(0.9)!!)
        val frequencies = (0..100).map { ProgressToneController.frequency(it / 100.0)!! }
        assertTrue(frequencies.zipWithNext().all { (a, b) -> b > a })
    }
    @Test fun `invalid fractions do not create an audio track`() {
        for (value in listOf(-0.1, 1.1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertNull(ProgressToneController.frequency(value)); assertFalse(controller.play(value))
        }
        assertTrue(tracks.isEmpty())
    }
    @Test fun `generated PCM is short mono and uses accessibility sonification audio`() {
        assertTrue(controller.play(0.5))
        val track = tracks.single()
        // The constructor supports API 21+; inspect its attributes through the API 29+ getter.
        if (Build.VERSION.SDK_INT >= 29) {
            assertEquals(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY, track.audioAttributes.usage)
            assertEquals(AudioAttributes.CONTENT_TYPE_SONIFICATION, track.audioAttributes.contentType)
        }
        assertEquals(AudioFormat.CHANNEL_OUT_MONO, track.channelConfiguration)
        assertEquals(AudioFormat.ENCODING_PCM_16BIT, track.audioFormat)
        assertEquals(16000, track.sampleRate)
        assertEquals(1920, data.single().size)
        assertEquals(960, shadow(track).marker)
    }
    @Test fun `PCM pitch increases and short fades avoid sharp buffer edges`() {
        controller.play(0.0); controller.play(1.0)
        fun samples(bytes: ByteArray) = bytes.asList().chunked(2).map {
            (((it[1].toInt() and 255) shl 8) or (it[0].toInt() and 255)).toShort().toInt()
        }
        val low = samples(data[0]); val high = samples(data[1])
        fun crossings(values: List<Int>) = values.zipWithNext().count { (a, b) -> a <= 0 && b > 0 }
        assertTrue(crossings(high) > crossings(low))
        for (values in listOf(low, high)) {
            assertEquals(0, values.first()); assertEquals(0, values.last())
            assertTrue(values.maxOf { kotlin.math.abs(it) } <= 3933)
        }
    }
    @Test fun `new tone stops and releases the old track instead of building a backlog`() {
        controller.play(0.1); val old = tracks.last()
        controller.play(0.9)
        assertEquals(1, shadow(old).stops); assertEquals(1, shadow(old).releases)
        assertEquals(0, shadow(tracks.last()).releases)
    }
    @Test fun `completion marker releases the finished track`() {
        controller.play(0.5); val track = tracks.single()
        shadow(track).listener!!.onMarkerReached(track)
        assertEquals(1, shadow(track).releases)
        controller.stop(); assertEquals(1, shadow(track).releases)
    }
    @Test fun `late marker from an old track cannot stop its replacement`() {
        controller.play(0.1); val old = tracks.last(); val callback = shadow(old).listener!!
        controller.play(0.8); callback.onMarkerReached(old)
        assertEquals(0, shadow(tracks.last()).releases)
    }
    @Test fun `explicit stop is safe and releases only the active tone`() {
        controller.play(0.5); controller.stop(); controller.stop()
        assertEquals(1, shadow(tracks.single()).stops); assertEquals(1, shadow(tracks.single()).releases)
    }
    @Test fun `audio play failure is contained and its track is released`() {
        ShadowAudioTrack.enableIllegalStateOnPlay(true)
        try { assertFalse(controller.play(0.5)); assertEquals(1, shadow(tracks.single()).releases) }
        finally { ShadowAudioTrack.enableIllegalStateOnPlay(false) }
    }
}
