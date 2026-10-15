/*
 * Callbacks interceptor.
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

package com.neo.speaktouch.intercepter.event

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.neo.speaktouch.intercepter.event.contract.EventInterceptor
import dagger.hilt.android.scopes.ServiceScoped
import javax.inject.Inject

@ServiceScoped
class CallbackInterceptor @Inject constructor() : EventInterceptor {

    private val scrolls = mutableListOf<Scroll>()
    data class Position(val from: Int, val to: Int, val count: Int, val y: Int, val maxY: Int)
    private val positions = linkedMapOf<AccessibilityNodeInfo, Position>()

    @Synchronized
    fun position(target: AccessibilityNodeInfo): Position? = positions[target]

    @Synchronized
    override fun handle(event: AccessibilityEvent) {

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            event.source?.let {
                positions[it] = Position(event.fromIndex, event.toIndex, event.itemCount, event.scrollY, event.maxScrollY)
                if (positions.size > 16) positions.remove(positions.keys.first())
            }

            scrolls.lastOrNull {
                it.target == event.source
            }?.let { scroll ->
                scrolls.remove(scroll)
                scroll.invoke()
            }
        }
    }

    @Synchronized
    fun hasPendingScroll(target: AccessibilityNodeInfo): Boolean = scrolls.any { it.target == target }

    fun addCallback(callback: Scroll) {
        scrolls.add(callback)
    }

    @Synchronized
    fun cancel(owner: Any) {
        scrolls.removeAll { it.owner === owner }
    }

    override fun finish() { scrolls.clear(); positions.clear() }

    abstract class Scroll(val target: AccessibilityNodeInfo, val owner: Any? = null) {
        abstract operator fun invoke()
    }
}
