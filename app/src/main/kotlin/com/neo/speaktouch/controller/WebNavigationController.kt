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

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.neo.speaktouch.intercepter.event.CallbackInterceptor

/** Provider-owned reading order. No role guesses, tree search, focus requests or speech. */
class WebNavigationController(
    private val service: ServiceController,
    private val callbacks: CallbackInterceptor
) {
    enum class Result { UNSUPPORTED, ACCEPTED, EXHAUSTED, UNAVAILABLE }
    private data class Edge(val root: AccessibilityNodeInfo, val window: Int,
        val origin: AccessibilityNodeInfo, val container: AccessibilityNodeInfo, val forward: Boolean)
    private var edge: Edge? = null

    fun supports(node: AccessibilityNodeInfo): Boolean = node.actionList.any {
        it.id == AccessibilityNodeInfo.ACTION_NEXT_HTML_ELEMENT ||
            it.id == AccessibilityNodeInfo.ACTION_PREVIOUS_HTML_ELEMENT
    }

    /** Use the action-capable document root, not an enclosing native WebView host. */
    fun container(node: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        if (supports(node)) findWebView(node, requireActions = true) else null

    internal fun boundary(node: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        if (supports(node)) findWebView(node, requireActions = false) else null

    // A native host with a single provider document is a wrapper, not a second
    // reading destination. Inspect only that direct child, never scan the page.
    internal fun isDocumentHost(node: AccessibilityNodeInfo): Boolean =
        !supports(node) && node.className?.toString() == "android.webkit.WebView" && node.childCount == 1 &&
            node.getChild(0)?.let { it.className?.toString() == "android.webkit.WebView" && supports(it) } == true

    private fun findWebView(node: AccessibilityNodeInfo, requireActions: Boolean): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var found: AccessibilityNodeInfo? = null
        val seen = mutableSetOf<AccessibilityNodeInfo>()
        while (current != null && seen.add(current)) {
            if (current.className?.toString() == "android.webkit.WebView" &&
                (!requireActions || supports(current))) found = current
            current = current.parent
        }
        return found
    }

    fun valid(node: AccessibilityNodeInfo, root: AccessibilityNodeInfo): Boolean = try {
        val container = container(node)
        val host = findWebView(node, requireActions = false)
        container != null && host != null && root.refresh() && node.refresh() && container.refresh() &&
            host.refresh() && host.isVisibleToUser && host.windowId == root.windowId && node.windowId == root.windowId &&
            container.windowId == root.windowId && !root.packageName.isNullOrBlank() &&
            node.packageName?.toString() == root.packageName?.toString() &&
            container.packageName?.toString() == root.packageName?.toString() && container(node) == container &&
            belongsTo(container, root) &&
            service.getRoot() == root && service.getRoot()?.windowId == root.windowId
    } catch (_: RuntimeException) { false }

    fun isContainer(node: AccessibilityNodeInfo) = container(node) == node

    fun navigate(node: AccessibilityNodeInfo, forward: Boolean, entering: Boolean = false): Result {
        edge = null
        if (!supports(node)) return Result.UNSUPPORTED
        val root = service.getRoot() ?: return Result.UNAVAILABLE
        val container = container(node) ?: return Result.UNAVAILABLE
        val originalFocus = service.getFocused()
        val window = root.windowId
        fun current() = valid(node, root) && root.windowId == window &&
            service.getRoot()?.windowId == window &&
            (entering || originalFocus == node && node.isAccessibilityFocused) &&
            service.getFocused() == originalFocus
        return try {
            if (!current()) return Result.UNAVAILABLE
            // Previous from the native container exits the web document. Previous *into*
            // that container asks the provider for its last reading destination instead.
            if (!entering && !forward && node == container) {
                edge = Edge(root, window, node, container, forward)
                return Result.EXHAUSTED
            }
            val action = if (forward) AccessibilityNodeInfo.ACTION_NEXT_HTML_ELEMENT
                else AccessibilityNodeInfo.ACTION_PREVIOUS_HTML_ELEMENT
            if (node.actionList.none { it.id == action }) return Result.UNSUPPORTED
            // Scroll events may come from the native host or its virtual document root.
            callbacks.markProviderNavigation(root, findWebView(node, requireActions = false) ?: container)
            val accepted = node.performAction(action, Bundle().apply {
                putString(AccessibilityNodeInfo.ACTION_ARGUMENT_HTML_ELEMENT_STRING, "")
            })
            if (accepted) Result.ACCEPTED // Focus may arrive later; never fall through.
            else if (!current() || node.actionList.none { it.id == action }) Result.UNAVAILABLE
            else {
                edge = Edge(root, window, node, container, forward)
                Result.EXHAUSTED
            }
        } catch (_: RuntimeException) { Result.UNAVAILABLE }
    }

    fun exhausted(root: AccessibilityNodeInfo, origin: AccessibilityNodeInfo, forward: Boolean): Boolean {
        val proof = edge ?: return false
        return proof.root == root && proof.window == root.windowId && proof.origin == origin &&
            proof.forward == forward && container(origin) == proof.container && valid(origin, root)
    }

    fun clear() { edge = null }

    private fun belongsTo(node: AccessibilityNodeInfo, root: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        val seen = mutableSetOf<AccessibilityNodeInfo>()
        while (current != null && seen.add(current)) {
            if (current == root) return true
            current = current.parent
        }
        return false
    }
}
