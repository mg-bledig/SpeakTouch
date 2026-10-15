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

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/** PCM generation needs no Android runtime or AudioTrack shadow. */
class ScrollPcmTest {
    private fun samples(bytes: ByteArray) = bytes.toList().chunked(2).map {
        (it[0].toInt() and 255) or (it[1].toInt() shl 8)
    }
    @Test fun `position pitch follows exponential mapping and increases monotonically`() {
        val frequencies = listOf(0.0, .25, .5, .75, 1.0).map { ScrollFeedbackController.frequency(it)!! }
        val expected = listOf(900.0, 1022.6, 1161.9, 1320.2, 1500.0)
        frequencies.zip(expected).forEach { (actual, value) -> assertEquals(value, actual, 1.0) }
        assertTrue(frequencies.zipWithNext().all { (a, b) -> a < b })
    }
    @Test fun `reverse positions decrease between individual tones`() {
        val frequencies = listOf(1.0, .75, .5, .25, 0.0).map { ScrollFeedbackController.frequency(it)!! }
        assertTrue(frequencies.zipWithNext().all { (a, b) -> a > b })
    }
    @Test fun `each beep has constant period throughout its audible middle`() {
        for (p in listOf(0.0, .25, .5, .75, 1.0)) {
            val values = samples(ScrollFeedbackController.pcm(p)!!)
            val crossings = (65 until values.size - 65).filter { values[it - 1] <= 0 && values[it] > 0 }
            val periods = crossings.zipWithNext().map { (a, b) -> b - a }
            val expected = ScrollFeedbackController.SAMPLE_RATE / ScrollFeedbackController.frequency(p)!!
            assertTrue(periods.size > 20)
            assertTrue(periods.all { abs(it - expected) < 1.1 })
            assertEquals(periods.take(8).average(), periods.takeLast(8).average(), .3)
        }
    }
    @Test fun `beeps are forty milliseconds quiet and faded at both ends`() {
        for (p in listOf(0.0, .25, .5, .75, 1.0)) {
            val values = samples(ScrollFeedbackController.pcm(p)!!)
            assertEquals(640, values.size)
            assertEquals(0, values.first()); assertEquals(0, values.last())
            assertTrue(values.maxOf { abs(it) } in 1600..1638)
            assertTrue(abs(values[1]) < 30); assertTrue(abs(values[values.lastIndex - 1]) < 30)
        }
    }
    @Test fun `invalid positions do not generate fabricated pitch or PCM`() {
        for (p in listOf(-.1, 1.1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertNull(ScrollFeedbackController.frequency(p)); assertNull(ScrollFeedbackController.pcm(p))
        }
    }
}