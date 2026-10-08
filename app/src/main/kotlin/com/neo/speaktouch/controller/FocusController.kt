/*
 * Focus controller.
 *
 * Copyright (C) 2023-2025 Patryk Mis.
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

import android.view.accessibility.AccessibilityNodeInfo
import com.neo.speaktouch.intercepter.event.CallbackInterceptor
import com.neo.speaktouch.model.NodeFilter
import com.neo.speaktouch.utils.TraversalOrder
import com.neo.speaktouch.utils.extension.Direction
import com.neo.speaktouch.utils.extension.nodeScan
import com.neo.speaktouch.utils.extension.performFocus
import dagger.hilt.android.scopes.ServiceScoped
import javax.inject.Inject

@ServiceScoped
class FocusController @Inject constructor(
    private val callbackInterceptor: CallbackInterceptor,
    private val serviceController: ServiceController
) {

    fun getTarget(): AccessibilityNodeInfo {

        return serviceController.getFocused() ?: serviceController.getRoot()
    }

    private fun getTraversalOrder(): TraversalOrder? =
        serviceController.getOrThrow().rootInActiveWindow?.let { TraversalOrder(it) }

    private fun moveInOrder(
        order: TraversalOrder,
        target: AccessibilityNodeInfo,
        filter: NodeFilter,
        forward: Boolean
    ): Boolean {
        if (!order.isReordered) return false
        val nodes = order.nodes()
        val index = nodes.indexOf(target)
        if (index < 0) return false
        val remaining = if (forward) nodes.drop(index + 1) else nodes.take(index).asReversed()
        val candidates = remaining.filter { filter.filter(it) }
        val next = candidates.firstOrNull()
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        for (ancestor in order.ancestorsOf(target)) {
            if (next != null && order.contains(ancestor, next)) break
            // A cross-subtree leaf constraint can leave and later re-enter a container.
            // Scroll only when its last eligible item in this direction is exhausted.
            if (candidates.any { order.contains(ancestor, it) }) continue
            if (ancestor.performAction(action)) {
                callbackInterceptor.addCallback(object : CallbackInterceptor.Scroll(ancestor) {
                    override fun invoke() {
                        if (forward) moveFocusToNext() else moveFocusToPrevious()
                    }
                })
                return true
            }
        }
        next?.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        // Ordered boundaries must not fall through to a structural successor/predecessor.
        return true
    }

    fun moveFocusToPrevious(
        target: AccessibilityNodeInfo = getTarget(),
        nodeFilter: NodeFilter = NodeFilter.Focusable
    ) {
        val order = getTraversalOrder()
        if (order != null && moveInOrder(order, target, nodeFilter, forward = false)) return
        nodeScan {

            target.ancestors {

                current.descendants(
                    Direction.Left(
                        start = leftIndexOfChild()
                    )
                ) {

                    recursive()

                    if (nodeFilter.filter(current)) {
                        current.performFocus(this)
                    }
                }

                if (nodeFilter.filter(current)) {
                    current.performFocus(this)
                }

                if (current.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {

                    callbackInterceptor.addCallback(
                        object : CallbackInterceptor.Scroll(current) {
                            override fun invoke() {
                                moveFocusToPrevious()
                            }
                        }
                    )

                    this.stop()
                }
            }
        }
    }

    fun moveFocusToNext(
        target: AccessibilityNodeInfo = getTarget(),
        nodeFilter: NodeFilter = NodeFilter.Focusable
    ) {
        val order = getTraversalOrder()
        if (order != null && moveInOrder(order, target, nodeFilter, forward = true)) return
        nodeScan {

            target.descendants(Direction.Right()) {

                if (nodeFilter.filter(current)) {
                    current.performFocus(this)
                }

                recursive()
            }

            target.ancestors {

                current.descendants(
                    Direction.Right(
                        start = rightIndexOfChild()
                    )
                ) {

                    if (nodeFilter.filter(current)) {
                        current.performFocus(this)
                    }

                    recursive()
                }

                if (current.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {

                    callbackInterceptor.addCallback(
                        object : CallbackInterceptor.Scroll(current) {
                            override fun invoke() {
                                moveFocusToNext()
                            }
                        }
                    )

                    this.stop()
                }
            }
        }
    }

    fun moveFocusToFirst() {
        val root = getTarget() ?: return

        val currentFocus = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)

        if (currentFocus == null) {
            TraversalOrder(root).nodes().firstOrNull {
                it != root && NodeFilter.Focusable.filter(it)
            }?.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
            return
        }

        currentFocus.performAction(AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY)
    }
}
