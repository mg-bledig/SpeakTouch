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
import com.neo.speaktouch.utils.extension.NodeScanScope
import dagger.hilt.android.scopes.ServiceScoped
import javax.inject.Inject

@ServiceScoped
class FocusController @Inject constructor(
    private val callbackInterceptor: CallbackInterceptor,
    private val serviceController: ServiceController
) {
    internal val webNavigation = WebNavigationController(serviceController, callbackInterceptor)
    private val forwardProgress = ForwardScrollProgress()

    fun clearForwardScrollProofs() = forwardProgress.clear()

    fun hasExhaustedForwardScroll(
        container: AccessibilityNodeInfo, root: AccessibilityNodeInfo, origin: AccessibilityNodeInfo
    ): Boolean = forwardProgress.matches(root, origin) && forwardProgress.hasProof(container) && forwardProgress.proven(container,
        forwardProgress.snapshot(container, callbackInterceptor.position(container)))

    private fun scrollForward(
        container: AccessibilityNodeInfo,
        origin: AccessibilityNodeInfo,
        retry: () -> Unit,
        owner: Any?,
        canContinue: () -> Boolean,
        onResult: ((NavigationOutcome) -> Unit)?
    ): NavigationOutcome? {
        val root = serviceController.getRoot()
        if (root != null && hasExhaustedForwardScroll(container, root, origin)) return null
        val before = forwardProgress.snapshot(container, callbackInterceptor.position(container))
        if (!container.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
            return if (container.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD })
                NavigationOutcome.ACTION_FAILED else null
        }
        callbackInterceptor.addCallback(object : CallbackInterceptor.Scroll(container, owner) {
            override fun invoke() {
                if (!canContinue()) return
                if (root == null || serviceController.getRoot() != root ||
                    !forwardProgress.matches(root, origin) || getTarget() != origin) {
                    onResult?.invoke(NavigationOutcome.UNAVAILABLE)
                    return
                }
                val after = forwardProgress.snapshot(container, callbackInterceptor.position(container))
                if (before == null || after == null || before.items.isEmpty() || after.items.isEmpty()) {
                    onResult?.invoke(NavigationOutcome.UNAVAILABLE)
                    return
                }
                val originIndex = after.items.indexOfFirst { it.node == origin }
                val successorAvailable = originIndex >= 0 && originIndex < after.items.lastIndex
                if (!successorAvailable && !forwardProgress.remember(container, before, after)) {
                    onResult?.invoke(NavigationOutcome.UNAVAILABLE)
                    return
                }
                retry()
            }
        })
        return NavigationOutcome.SCROLL_PENDING
    }

    fun getTarget(): AccessibilityNodeInfo? {

        return serviceController.getFocused() ?: serviceController.getRoot()
    }

    private fun getTraversalOrder(): TraversalOrder? =
        serviceController.getOrThrow().rootInActiveWindow?.let { TraversalOrder(it) }

    enum class NavigationOutcome { PROVIDER_HANDLED, FOCUS_REQUESTED, SCROLL_PENDING, EXHAUSTED, UNAVAILABLE, ACTION_FAILED }

    private fun focusOutcome(node: AccessibilityNodeInfo, validate: Boolean = false): NavigationOutcome {
        if (validate && (!node.refresh() || !node.isVisibleToUser || !NodeFilter.Focusable.filter(node))) {
            return NavigationOutcome.UNAVAILABLE
        }
        val accepted = node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        return if (accepted) NavigationOutcome.FOCUS_REQUESTED
        else NavigationOutcome.ACTION_FAILED
    }

    fun cancelPendingNavigation(owner: Any) = callbackInterceptor.cancel(owner)

    /** Focus the opposite window edge without performing any scroll action. */
    fun wrapFocusToWindowEdge(
        root: AccessibilityNodeInfo,
        origin: AccessibilityNodeInfo,
        forward: Boolean,
        isUsableTarget: (AccessibilityNodeInfo) -> Boolean = { true },
        canContinue: () -> Boolean
    ): NavigationOutcome {
        val window = root.windowId
        fun current(): Boolean = canContinue() && serviceController.getRoot() == root &&
            serviceController.getRoot()?.windowId == window && serviceController.getFocused() == origin &&
            origin.refresh() && origin.isAccessibilityFocused && origin.windowId == window &&
            navigationOrigin(origin)
        return try {
            if (!current()) return NavigationOutcome.UNAVAILABLE
            val order = TraversalOrder(root)
            if (origin !in order.nodes()) return NavigationOutcome.UNAVAILABLE
            val nodes = if (forward) order.nodes() else order.nodes().asReversed()
            val target = nodes.firstOrNull {
                (it != root || webNavigation.isContainer(it)) && it.refresh() && it.windowId == window &&
                    it.packageName?.toString() == root.packageName?.toString() && navigationCandidate(it) &&
                    (webNavigation.isContainer(it) || isUsableTarget(it))
            } ?: return NavigationOutcome.EXHAUSTED
            // A single eligible target is not a focus movement or a reason to re-read it.
            if (target == origin) return NavigationOutcome.EXHAUSTED
            if (!target.refresh() || !navigationCandidate(target) || (!webNavigation.isContainer(target) && !isUsableTarget(target)) || target.windowId != window ||
                target.packageName?.toString() != root.packageName?.toString() ||
                !TraversalOrder(root).contains(root, target) || !current()) return NavigationOutcome.UNAVAILABLE
            if (webNavigation.isContainer(target)) {
                when (webNavigation.navigate(target, forward, entering = true)) {
                    WebNavigationController.Result.ACCEPTED -> NavigationOutcome.PROVIDER_HANDLED
                    WebNavigationController.Result.EXHAUSTED -> NavigationOutcome.EXHAUSTED
                    else -> NavigationOutcome.UNAVAILABLE
                }
            } else enter(target, forward, false)
        } catch (_: RuntimeException) {
            NavigationOutcome.UNAVAILABLE
        }
    }

    private fun moveInOrder(
        order: TraversalOrder,
        target: AccessibilityNodeInfo,
        filter: NodeFilter,
        forward: Boolean,
        retry: () -> Unit,
        owner: Any?,
        canContinue: () -> Boolean = { true },
        onResult: ((NavigationOutcome) -> Unit)? = null,
        skipDescendants: Boolean = false,
        scrollOrigin: AccessibilityNodeInfo = target
    ): NavigationOutcome? {
        val nodes = order.nodes()
        val index = nodes.indexOf(target)
        if (index < 0) {
            return NavigationOutcome.UNAVAILABLE
        }
        if (!order.isReordered) {
            return null
        }
        val remaining = if (forward) nodes.drop(index + 1) else nodes.take(index).asReversed()
        val candidates = remaining.filter {
            (!skipDescendants || !order.contains(target, it)) && navigationCandidate(it, filter)
        }
        val next = candidates.firstOrNull()
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        var failedScroll = false
        for (ancestor in order.ancestorsOf(target)) {
            if (next != null && order.contains(ancestor, next)) break
            // A cross-subtree leaf constraint can leave and later re-enter a container.
            // Scroll only when its last eligible item in this direction is exhausted.
            if (candidates.any { order.contains(ancestor, it) }) continue
            if (forward) {
                when (val result = scrollForward(ancestor, scrollOrigin, retry, owner, canContinue, onResult)) {
                    NavigationOutcome.SCROLL_PENDING -> return result
                    NavigationOutcome.ACTION_FAILED -> failedScroll = true
                    else -> Unit
                }
                continue
            }
            val scrolled = ancestor.performAction(action)
            if (scrolled) {
                callbackInterceptor.addCallback(object : CallbackInterceptor.Scroll(ancestor, owner) {
                    override fun invoke() {
                        retry()
                    }
                })
                return NavigationOutcome.SCROLL_PENDING
            }
            if (ancestor.actionList.any { it.id == action }) {
                failedScroll = true
            }
        }
        if (next != null) return enter(next, forward, owner != null)
        // Ordered boundaries must not fall through to a structural successor/predecessor.
        return if (failedScroll) NavigationOutcome.ACTION_FAILED else NavigationOutcome.EXHAUSTED
    }

    internal fun navigationOrigin(node: AccessibilityNodeInfo): Boolean {
        val root = serviceController.getRoot() ?: return false
        return if (webNavigation.supports(node)) webNavigation.valid(node, root)
            else node.isVisibleToUser && NodeFilter.Focusable.filter(node)
    }

    /** Native traversal sees a provider-owned WebView as one directional entry point. */
    internal fun navigationCandidate(node: AccessibilityNodeInfo, filter: NodeFilter = NodeFilter.Focusable): Boolean {
        if (webNavigation.isDocumentHost(node)) return false
        if (!webNavigation.supports(node)) return filter.filter(node)
        val root = serviceController.getRoot() ?: return false
        return webNavigation.isContainer(node) && webNavigation.valid(node, root)
    }

    private fun enter(node: AccessibilityNodeInfo, forward: Boolean, validate: Boolean): NavigationOutcome {
        val webContainer = webNavigation.isContainer(node)
        if (!webContainer || forward) return focusOutcome(node, validate && !webContainer)
        return when (webNavigation.navigate(node, forward = false, entering = true)) {
            WebNavigationController.Result.ACCEPTED -> NavigationOutcome.PROVIDER_HANDLED
            WebNavigationController.Result.EXHAUSTED -> focusOutcome(node)
            else -> NavigationOutcome.UNAVAILABLE
        }
    }

    fun moveFocusToNext(target: AccessibilityNodeInfo? = getTarget(), nodeFilter: NodeFilter = NodeFilter.Focusable,
        onResult: ((NavigationOutcome) -> Unit)? = null, canContinue: () -> Boolean = { true }): NavigationOutcome =
        moveSequential(target, nodeFilter, true, onResult, canContinue)

    fun moveFocusToPrevious(target: AccessibilityNodeInfo? = getTarget(), nodeFilter: NodeFilter = NodeFilter.Focusable,
        onResult: ((NavigationOutcome) -> Unit)? = null, canContinue: () -> Boolean = { true }): NavigationOutcome =
        moveSequential(target, nodeFilter, false, onResult, canContinue)

    private fun moveSequential(target: AccessibilityNodeInfo?, filter: NodeFilter, forward: Boolean,
        onResult: ((NavigationOutcome) -> Unit)?, canContinue: () -> Boolean): NavigationOutcome {
        if (!canContinue()) return NavigationOutcome.UNAVAILABLE.also { onResult?.invoke(it) }
        var pivot = target
        var outsideWeb = false
        if (target != null && webNavigation.supports(target)) {
            when (webNavigation.navigate(target, forward)) {
                WebNavigationController.Result.ACCEPTED ->
                    return NavigationOutcome.PROVIDER_HANDLED.also { onResult?.invoke(it) }
                WebNavigationController.Result.EXHAUSTED -> {
                    pivot = webNavigation.boundary(target); outsideWeb = true
                }
                else -> return NavigationOutcome.UNAVAILABLE.also { onResult?.invoke(it) }
            }
        } else webNavigation.clear()
        return if (forward) moveNativeNext(pivot, filter, onResult, canContinue, skipDescendants = outsideWeb)
            else moveNativePrevious(pivot, filter, onResult, canContinue)
    }

    private fun moveNativePrevious(
        target: AccessibilityNodeInfo? = getTarget(),
        nodeFilter: NodeFilter = NodeFilter.Focusable,
        onResult: ((NavigationOutcome) -> Unit)? = null,
        canContinue: () -> Boolean = { true }
    ): NavigationOutcome {
        forwardProgress.clear()
        val resolvedTarget = target ?: return NavigationOutcome.UNAVAILABLE.also { onResult?.invoke(it) }
        val order = getTraversalOrder()
        val retry = { if (canContinue()) moveFocusToPrevious(onResult = onResult, canContinue = canContinue); Unit }
        if (order != null) moveInOrder(order, resolvedTarget, nodeFilter, forward = false, retry = retry, owner = onResult)?.let {
            onResult?.invoke(it); return it
        }
        var outcome = NavigationOutcome.EXHAUSTED
        fun requestFocus(node: AccessibilityNodeInfo, scope: NodeScanScope) {
            outcome = enter(node, false, onResult != null)
            scope.stop(node)
        }
        nodeScan {

            resolvedTarget.ancestors {

                current.descendants(
                    Direction.Left(
                        start = leftIndexOfChild()
                    )
                ) {

                    if (webNavigation.isContainer(current) && navigationCandidate(current, nodeFilter)) {
                        requestFocus(current, this)
                    }
                    if (!webNavigation.supports(current)) recursive()

                    if (navigationCandidate(current, nodeFilter)) {
                        requestFocus(current, this)
                    }
                }

                if (navigationCandidate(current, nodeFilter)) {
                    requestFocus(current, this)
                }

                val scrolled = current.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                if (scrolled) {
                    outcome = NavigationOutcome.SCROLL_PENDING

                    callbackInterceptor.addCallback(
                        object : CallbackInterceptor.Scroll(current, onResult) {
                            override fun invoke() {
                                retry()
                            }
                        }
                    )

                    this.stop()
                }
                if (current.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD }) {
                    outcome = NavigationOutcome.ACTION_FAILED
                }
            }
        }
        onResult?.invoke(outcome)
        return outcome
    }

    private fun moveNativeNext(
        target: AccessibilityNodeInfo? = getTarget(),
        nodeFilter: NodeFilter = NodeFilter.Focusable,
        onResult: ((NavigationOutcome) -> Unit)? = null,
        canContinue: () -> Boolean = { true },
        skipDescendants: Boolean = false
    ): NavigationOutcome {
        val resolvedTarget = target ?: return NavigationOutcome.UNAVAILABLE.also { onResult?.invoke(it) }
        val navigationOrigin = if (skipDescendants) serviceController.getFocused() ?: resolvedTarget else resolvedTarget
        forwardProgress.begin(serviceController.getRoot(), navigationOrigin, nodeFilter)
        val order = getTraversalOrder()
        val retry = {
            val allowed = canContinue()
            if (allowed) moveFocusToNext(onResult = onResult, canContinue = canContinue)
            Unit
        }
        if (order != null) moveInOrder(order, resolvedTarget, nodeFilter, forward = true, retry = retry,
            owner = onResult, canContinue = canContinue, onResult = onResult,
            skipDescendants = skipDescendants, scrollOrigin = navigationOrigin)?.let {
            onResult?.invoke(it); return it
        }
        var outcome = NavigationOutcome.EXHAUSTED
        fun requestFocus(node: AccessibilityNodeInfo, scope: NodeScanScope) {
            outcome = enter(node, true, onResult != null)
            scope.stop(node)
        }
        nodeScan {

            if (!skipDescendants && !webNavigation.supports(resolvedTarget)) resolvedTarget.descendants(Direction.Right()) {

                if (navigationCandidate(current, nodeFilter)) {
                    requestFocus(current, this)
                }

                if (!webNavigation.supports(current)) recursive()
            }

            resolvedTarget.ancestors {

                current.descendants(
                    Direction.Right(
                        start = rightIndexOfChild()
                    )
                ) {

                    if (navigationCandidate(current, nodeFilter)) {
                        requestFocus(current, this)
                    }

                    if (!webNavigation.supports(current)) recursive()
                }

                when (val result = scrollForward(current, navigationOrigin, retry, onResult, canContinue, onResult)) {
                    NavigationOutcome.SCROLL_PENDING -> { outcome = result; this.stop() }
                    NavigationOutcome.ACTION_FAILED -> outcome = result
                    else -> Unit
                }
            }
        }
        onResult?.invoke(outcome)
        return outcome
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
