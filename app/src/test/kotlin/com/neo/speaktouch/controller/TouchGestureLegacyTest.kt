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

import com.neo.speaktouch.gestures.TouchGestureController
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [22, 28, 31])
class TouchGestureLegacyTest {
    @Test fun `legacy versions keep framework ownership without loading API 33 callbacks`() {
        val controller = TouchGestureController(SliderTestService())
        assertFalse(controller.start({ fail("Unexpected gesture"); false },
            { fail("Unexpected double tap"); false }, { fail("Unexpected callback") }))
        assertFalse(controller.available)
        controller.finish(); controller.finish()
    }
}
