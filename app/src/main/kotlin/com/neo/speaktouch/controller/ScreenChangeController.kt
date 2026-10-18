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

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.neo.speaktouch.R
import com.neo.speaktouch.model.Text
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ServiceScoped
import javax.inject.Inject

/** Interprets context, never moves focus. Pending context belongs to one active window. */
@ServiceScoped
class ScreenChangeController @Inject constructor(
    private val service: ServiceController,
    private val speech: SpeechController,
    @ApplicationContext private val context: Context,
    private val manualFocus: ManualScrollFocusController? = null
) {
    private data class Destination(val pkg: String, val window: Int, val title: String?)
    private data class Pending(val destination: Destination?, val app: String?, val title: String?,
        val unlocked: Boolean, val started: Long, val speechGeneration: Long,
        var focus: android.view.accessibility.AccessibilityNodeInfo? = null)
    private val handler = Handler(Looper.getMainLooper())
    private var pending: Pending? = null
    private var current: Destination? = null
    private var base: Destination? = null
    private var dialogWindow: Int? = null
    private var blocked = false
    private var screenOffAnnounced = false
    private var registered = false
    private data class Page(val pkg: String, val window: Int, val text: String, val generation: Long,
        val root: AccessibilityNodeInfo, val readyAt: Long,
        var recoveryId: Long? = null, var source: AccessibilityNodeInfo? = null)
    private var pendingPage: Page? = null
    private var lastPage: Page? = null
    private data class PageFocus(val recovery: ManualScrollFocusController.Recovery,
        val node: AccessibilityNodeInfo, val generation: Long)
    private var heldPageFocus: PageFocus? = null
    private var deliveredPageFocus: PageFocus? = null
    private var ignoredRecovery: Long? = null
    private val deliverPage = Runnable { deliverPagePending() }
    private val deliverPageFocus = Runnable {
        val focus = heldPageFocus
        heldPageFocus = null
        if (focus != null && speech.generation == focus.generation && validPageFocus(focus.recovery, focus.node, recent = false)) {
            val page = pendingPage
            if (page != null && matches(page, focus.recovery) && validPage(page)) composePage(page, focus)
            else {
                cancelPage()
                speech.speak(AccessibilityNodeInfoCompat.wrap(focus.node))
                deliveredPageFocus = focus.copy(generation = speech.generation)
            }
        }
    }
    private val keyguard get() = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    private val power get() = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action in setOf(Intent.ACTION_SCREEN_OFF, Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT))
                observeLock()
            if (intent?.action == Intent.ACTION_SCREEN_ON && power.isInteractive) screenOffAnnounced = false
            if (intent?.action == Intent.ACTION_SCREEN_OFF && !power.isInteractive && !screenOffAnnounced) {
                screenOffAnnounced = true
                speech.speak(Text(R.string.screen_off))
            }
        }
    }
    private val deliver = Runnable { deliverPending() }

    fun start() {
        cancelPageFeedback(); lastPage = null
        manualFocus?.recoveryObserver = ::onRecoveryChanged
        cancelPending()
        current = activeDestination()
        base = current
        dialogWindow = null
        blocked = keyguard.isKeyguardLocked || keyguard.isDeviceLocked
        screenOffAnnounced = !power.isInteractive
        if (!registered) {
            ContextCompat.registerReceiver(service.getOrThrow(), receiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
        }
    }
    fun destroy() {
        manualFocus?.recoveryObserver = null
        cancelPageFeedback(); lastPage = null
        cancelPending()
        if (registered) { service.getOrThrow().unregisterReceiver(receiver); registered = false }
        current = null; base = null; dialogWindow = null
    }
    fun observe(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_ANNOUNCEMENT) {
            observePage(event); return
        }
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED &&
            (manualFocus?.recovery?.target == null || event.source != manualFocus.recovery?.target))
            cancelPageFeedback()
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED &&
            manualFocus?.recovery?.target != null) cancelPageFeedback()
        if (event.eventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START ||
            event.eventType == AccessibilityEvent.TYPE_GESTURE_DETECTION_START ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) cancelPageFeedback()
        when (event.eventType) {
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START,
            AccessibilityEvent.TYPE_GESTURE_DETECTION_START -> { cancelPending(); return }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> Unit
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                if (Build.VERSION.SDK_INT < 28 || event.windowChanges and
                    (AccessibilityEvent.WINDOWS_CHANGE_ACTIVE or AccessibilityEvent.WINDOWS_CHANGE_TITLE) == 0) return
            }
            else -> return
        }
        observeLock()
        if (!accessible()) return
        val sourceWindow = event.source?.window
            ?: service.getOrThrow().windows.firstOrNull { it.id == event.windowId }
        if (sourceWindow != null && sourceWindow.type != AccessibilityWindowInfo.TYPE_APPLICATION) return
        val destination = activeDestination(event) ?: return
        // Ignore events from IME/overlays/inactive apps; their package is not the destination.
        if (event.packageName != null && event.packageName.toString() != destination.pkg) return
        if (event.windowId >= 0 && event.windowId != destination.window &&
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (lastPage?.let { it.pkg != destination.pkg || it.window != destination.window } == true) lastPage = null
        accept(destination, isDialog(event, destination))
    }
    /** True only when this first focused-item announcement is held for atomic composition. */
    fun onFocused(node: AccessibilityNodeInfoCompat): Boolean {
        if (onPageFocused(node.unwrap())) return true
        cancelPageFeedback()

        if (!accessible()) return false
        val destination = activeDestination() ?: run { cancelPending(); return false }
        if (node.windowId != destination.window || node.packageName?.toString() != destination.pkg) {
            cancelPending(); return false
        }
        if (current?.pkg != destination.pkg) accept(destination, false)
        val update = pending ?: return false
        if (update.destination != null && update.destination.window != node.windowId) {
            cancelPending(); return false
        }
        update.focus = node.unwrap()
        return true
    }
    private fun accessible() = power.isInteractive && !keyguard.isKeyguardLocked && !keyguard.isDeviceLocked
    private fun observeLock() {
        val lockedNow = keyguard.isDeviceLocked
        val blockedNow = keyguard.isKeyguardLocked || lockedNow
        val becameAccessible = blocked && !blockedNow && power.isInteractive
        blocked = blockedNow
        if (blockedNow || !power.isInteractive) { cancelPending(); cancelPageFeedback() }
        if (becameAccessible) {
            val destination = activeDestination()
            val old = pending
            pending = Pending(destination, old?.app, old?.title, true,
                SystemClock.uptimeMillis(), speech.generation)
            schedule()
        }
    }
    private fun activeDestination(event: AccessibilityEvent? = null): Destination? {
        val root = service.getRoot() ?: return null
        val window = service.getOrThrow().windows.firstOrNull { it.isActive }
            ?: root.window ?: return null
        if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION || window.id != root.windowId) return null
        val pkg = root.packageName?.toString()?.takeIf { it.isNotBlank() } ?: return null
        val pane = if (Build.VERSION.SDK_INT >= 28 && event != null && event.contentChangeTypes and
            (AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_APPEARED or AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_TITLE) != 0)
            event.source?.let(AccessibilityNodeInfoCompat::wrap)?.paneTitle else null
        val windowTitle = if (Build.VERSION.SDK_INT >= 24) window.title else null
        val eventTitle = if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            (event.windowId < 0 || event.windowId == root.windowId)) event.text.singleOrNull() else null
        val title = sequenceOf(pane, windowTitle, eventTitle).mapNotNull {
            meaningfulTitle(it, pkg, event?.className?.toString(), root.className?.toString())
        }.firstOrNull()
        return Destination(pkg, root.windowId, title)
    }
    private fun meaningfulTitle(value: CharSequence?, pkg: String, eventClass: String?, rootClass: String?): String? {
        val title = value?.toString()?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (title.equals("Unknown window", true) || title == pkg || title == eventClass || title == rootClass ||
            title.startsWith("$pkg.") || title.startsWith("$pkg/") || title.contains(":id/") ||
            title.startsWith("android.") || title.startsWith("androidx.")) return null
        return title
    }
    private fun isDialog(event: AccessibilityEvent, destination: Destination): Boolean {
        if (destination.window == dialogWindow) return true
        if (event.className?.toString() in setOf("android.app.Dialog", "android.app.AlertDialog",
                "androidx.appcompat.app.AlertDialog", "android.widget.PopupWindow")) return true
        val owner = base ?: return false
        return owner.pkg == destination.pkg && owner.window != destination.window &&
            service.getOrThrow().windows.any { it.id == owner.window && it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
    }
    private fun appLabel(pkg: String): String? = try {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0))
            .toString().trim().takeIf { it.isNotEmpty() && it != pkg }
    } catch (_: android.content.pm.PackageManager.NameNotFoundException) { null }
      catch (_: SecurityException) { null }

    private fun cancelPage() {
        handler.removeCallbacks(deliverPage); pendingPage = null
    }

    /** Navigation/other user commands must not carry a previous page into their focus speech. */
    fun cancelPageFeedback() {
        cancelPage()
        handler.removeCallbacks(deliverPageFocus); heldPageFocus = null; deliveredPageFocus = null
        ignoredRecovery = manualFocus?.recovery?.id
    }

    private fun validPage(page: Page): Boolean {
        return try {
            val destination = activeDestination()
            pendingPage === page && accessible() && pending == null && speech.generation == page.generation &&
                service.getRoot() == page.root && destination?.pkg == page.pkg && destination.window == page.window
        } catch (_: RuntimeException) { false }
    }

    private fun matches(page: Page, recovery: ManualScrollFocusController.Recovery) =
        recovery.id != ignoredRecovery && recovery.scroll.root == page.root &&
            recovery.scroll.windowId == page.window && recovery.scroll.root.packageName?.toString() == page.pkg &&
            (page.source == null || page.source == recovery.scroll.source) &&
            (page.recoveryId == null || page.recoveryId == recovery.id)

    private fun bindPage(page: Page, recovery: ManualScrollFocusController.Recovery) {
        page.recoveryId = recovery.id; page.source = recovery.scroll.source
        handler.removeCallbacks(deliverPage)
        recovery.requestedAt?.let {
            handler.postDelayed(deliverPage, (it + FOCUS_EVENT_WAIT_MS - SystemClock.uptimeMillis()).coerceAtLeast(0))
        }
    }

    private fun onRecoveryChanged(recovery: ManualScrollFocusController.Recovery?) {
        val page = pendingPage ?: return
        if (!validPage(page)) { cancelPage(); return }
        if (recovery == null) {
            page.recoveryId = null
            handler.removeCallbacks(deliverPage)
            handler.postDelayed(deliverPage, (page.readyAt - SystemClock.uptimeMillis()).coerceAtLeast(0))
        } else {
            // Repeated scroll events replace the settling request within the same container.
            page.recoveryId = null
            if (matches(page, recovery) && deliveredPageFocus?.recovery?.id != recovery.id) bindPage(page, recovery)
            else cancelPage()
        }
    }

    private fun deliverPagePending() {
        val page = pendingPage ?: return
        if (!validPage(page)) { cancelPage(); return }
        val recovery = manualFocus?.recovery
        if (recovery != null && matches(page, recovery) && deliveredPageFocus?.recovery?.id != recovery.id &&
            (recovery.requestedAt == null || SystemClock.uptimeMillis() < recovery.requestedAt + FOCUS_EVENT_WAIT_MS)) {
            bindPage(page, recovery)
            return
        }
        cancelPage()

        speech.speak(Text(page.text))
        lastPage = page
        ignoredRecovery = manualFocus?.recovery?.id
    }

    private fun validPageFocus(recovery: ManualScrollFocusController.Recovery, node: AccessibilityNodeInfo,
        recent: Boolean = true): Boolean {
        return try {
            val requestedAt = recovery.requestedAt ?: return false
            if (recovery.id == ignoredRecovery || manualFocus?.recovery?.id != recovery.id ||
                recovery.target != node || !accessible() || pending != null ||
                recent && SystemClock.uptimeMillis() - requestedAt > FOCUS_EVENT_WAIT_MS) return false
            val destination = activeDestination() ?: return false
            val root = recovery.scroll.root
            if (service.getRoot() != root || destination.window != recovery.scroll.windowId ||
                destination.pkg != root.packageName?.toString()) return false
            val source = recovery.scroll.source
            source.refresh() && source.isVisibleToUser && source.windowId == destination.window &&
                source.packageName?.toString() == destination.pkg && belongsTo(source, root) &&
                node.refresh() && node.isVisibleToUser && node.isAccessibilityFocused &&
                node.windowId == destination.window && node.packageName?.toString() == destination.pkg &&
                belongsTo(node, source) && service.getFocused() == node && service.getRoot() == root &&
                manualFocus.recovery?.id == recovery.id
        } catch (_: RuntimeException) { false }
    }

    private fun onPageFocused(node: AccessibilityNodeInfo): Boolean {
        val recovery = manualFocus?.recovery ?: return false
        if (!validPageFocus(recovery, node)) return false
        val delivered = deliveredPageFocus
        if (delivered?.recovery?.id == recovery.id && delivered.node == node && delivered.generation == speech.generation)
            return true
        if (heldPageFocus?.let { it.recovery.id == recovery.id && it.node == node && it.generation == speech.generation } == true)
            return true
        val page = pendingPage
        if (page != null) {
            if (!matches(page, recovery) || !validPage(page)) return false
            return composePage(page, PageFocus(recovery, node, speech.generation))
        }
        // A provider may announce its page just after the genuine focus event. Hold
        // only a proven horizontal recovery, not ordinary focus or vertical list speech.
        val requested = recovery.requestedAt ?: return false
        if (SystemClock.uptimeMillis() - requested > FOCUS_EVENT_WAIT_MS ||
            recovery.scroll.movement.axis != ManualScrollInterpreter.Axis.HORIZONTAL ||
            homePackage() != node.packageName?.toString()) return false
        handler.removeCallbacks(deliverPageFocus)
        heldPageFocus = PageFocus(recovery, node, speech.generation)
        handler.postDelayed(deliverPageFocus, SETTLE_MS)
        return true
    }

    private fun composePage(page: Page, focus: PageFocus): Boolean {
        if (!validPage(page) || !validPageFocus(focus.recovery, focus.node, recent = false) || speech.generation != focus.generation) {
            cancelPageFeedback(); return false
        }
        cancelPage()
        handler.removeCallbacks(deliverPageFocus); heldPageFocus = null

        speech.speak(AccessibilityNodeInfoCompat.wrap(focus.node), listOf(page.text))
        lastPage = page
        deliveredPageFocus = focus.copy(generation = speech.generation)
        return true
    }

    private fun belongsTo(node: AccessibilityNodeInfo, ancestor: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        val seen = mutableSetOf<AccessibilityNodeInfo>()
        while (current != null && seen.add(current)) {
            if (current == ancestor) return true
            current = current.parent
        }
        return false
    }

    private fun homePackage(): String? = try {
        context.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
    } catch (_: SecurityException) { null }

    /** Home-provider scroll announcements, not a general announcement interpreter. */
    private fun observePage(event: AccessibilityEvent) {
        if (!accessible() || pending != null) return
        val destination = activeDestination() ?: return
        if (event.packageName?.toString() != destination.pkg ||
            (event.windowId >= 0 && event.windowId != destination.window)) return
        // The Oppo supplies no source/window ID. Its standard scroll-container class
        // is the only page-related semantic available without language/OEM parsing.
        if (event.className?.toString() !in setOf("android.widget.ScrollView",
                "android.widget.HorizontalScrollView")) return
        val source = event.source
        if (source != null && (source.windowId != destination.window ||
                source.packageName?.toString() != destination.pkg)) return
        val home = homePackage()
        if (home != destination.pkg) return
        val text = event.text.singleOrNull()?.toString()?.takeIf { it.isNotBlank() } ?: return
        if (meaningfulTitle(text, destination.pkg, event.className?.toString(), null) == null) return
        val root = service.getRoot() ?: return
        if (pendingPage?.let { it.root == root && it.text == text && it.generation == speech.generation } == true) return
        val page = Page(destination.pkg, destination.window, text, speech.generation, root,
            SystemClock.uptimeMillis() + SETTLE_MS, source = source)
        val previous = lastPage
        cancelPage()
        if (previous?.pkg == page.pkg && previous.window == page.window && previous.text == page.text) { return }
        pendingPage = page
        val recovery = manualFocus?.recovery
        if (recovery != null && matches(page, recovery) && deliveredPageFocus?.recovery?.id != recovery.id) {
            val focus = heldPageFocus
            if (focus?.recovery?.id == recovery.id && validPageFocus(recovery, focus.node, recent = false)) {
                composePage(page, focus); return
            }
            bindPage(page, recovery); return
        }

        handler.postDelayed(deliverPage, SETTLE_MS)
    }

    private fun accept(destination: Destination, dialog: Boolean) {
        val previous = current
        if (previous == destination) { if (pending != null) schedule(); return }
        val changedApp = previous?.pkg != destination.pkg
        val closingDialog = !changedApp && dialogWindow != null && destination.window == base?.window &&
            destination.title == base?.title
        val old = pending
        current = destination
        if (changedApp) { base = destination; dialogWindow = null }
        else if (closingDialog) dialogWindow = null
        else if (dialog) dialogWindow = destination.window
        else base = destination
        val label = appLabel(destination.pkg)
        val app = if (changedApp) label else old?.app?.takeIf { old.destination?.pkg == destination.pkg }
        val title = destination.title?.takeIf {
            !closingDialog && (changedApp || it != previous?.title) && !it.equals(label, true)
        }
        val unlocked = old?.unlocked == true
        if (app == null && title == null && !unlocked) { cancelPending(); return }
        val sameWindow = old?.destination?.pkg == destination.pkg && old?.destination?.window == destination.window
        pending = Pending(destination, app, title, unlocked,
            if (sameWindow) old!!.started else SystemClock.uptimeMillis(),
            if (sameWindow) old!!.speechGeneration else speech.generation,
            if (sameWindow) old?.focus else null)
        schedule()
    }
    private fun schedule() {
        val update = pending ?: return
        handler.removeCallbacks(deliver)
        val remaining = (update.started + MAX_WAIT_MS - SystemClock.uptimeMillis()).coerceAtLeast(0)
        handler.postDelayed(deliver, minOf(SETTLE_MS, remaining))
    }
    private fun cancelPending() { handler.removeCallbacks(deliver); pending = null }
    private fun deliverPending() {
        val update = pending ?: return
        pending = null
        if (!accessible() || speech.generation != update.speechGeneration) return
        val destination = activeDestination()
        if (update.destination != null && (destination?.pkg != update.destination.pkg ||
                destination?.window != update.destination.window)) return
        val parts = listOfNotNull(if (update.unlocked) context.getString(R.string.device_unlocked) else null,
            update.app, update.title)
        val focus = update.focus
        if (focus != null) {
            val currentFocus = service.getFocused() ?: return
            if (currentFocus != focus || !currentFocus.refresh() || !currentFocus.isVisibleToUser ||
                !currentFocus.isAccessibilityFocused || currentFocus.windowId != destination?.window) return
            speech.speak(AccessibilityNodeInfoCompat.wrap(currentFocus), parts)
        } else if (parts.isNotEmpty()) speech.speak(Text(parts.joinToString(", ")))
    }
    private companion object {
        const val SETTLE_MS = 200L
        const val MAX_WAIT_MS = 500L
        const val FOCUS_EVENT_WAIT_MS = 500L
    }
}
