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

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Build
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.neo.speaktouch.intercepter.event.SpeechInterceptor
import com.neo.speaktouch.model.Text
import com.neo.speaktouch.utils.Reader
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [22, 28, 31], shadows = [SliderNodeShadow::class, PauseServiceShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class ScreenChangeTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var service: SliderTestService
    private lateinit var tts: TextToSpeech
    private lateinit var speech: SpeechController
    private lateinit var changes: ScreenChangeController
    private lateinit var interceptor: SpeechInterceptor
    private lateinit var root: AccessibilityNodeInfoCompat
    private lateinit var focused: AccessibilityNodeInfoCompat
    private lateinit var window: AccessibilityWindowInfo
    private fun nodeShadow(node: AccessibilityNodeInfoCompat): SliderNodeShadow = Shadow.extract(node.unwrap())
    private val keyguard get() = shadowOf(context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager)
    private val power get() = shadowOf(context.getSystemService(Context.POWER_SERVICE) as PowerManager)
    private fun spoken() = shadowOf(tts).spokenTextList.toList()
    private fun advance(ms: Long = 200) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun register(pkg: String, label: String) {
        shadowOf(context.packageManager).addPackage(PackageInfo().apply {
            packageName = pkg
            applicationInfo = ApplicationInfo().apply { packageName = pkg; nonLocalizedLabel = label }
        })
    }
    private fun scene(pkg: String = "test.alpha", title: String? = "Home", id: Int = 1,
                      kind: Int = AccessibilityWindowInfo.TYPE_APPLICATION, content: String = "Open") {
        window = AccessibilityWindowInfo.obtain().apply {
            shadowOf(this).setId(id); shadowOf(this).setType(kind); shadowOf(this).setActive(true)
            if (Build.VERSION.SDK_INT >= 24) shadowOf(this).setTitle(title)
        }
        fun node(clazz: String, text: String?) = AccessibilityNodeInfoCompat.wrap(AccessibilityNodeInfo.obtain()).apply {
            className = clazz; this.text = text; packageName = pkg
            isVisibleToUser = true; isEnabled = true
            shadowOf(unwrap()).setAccessibilityWindowInfo(this@ScreenChangeTest.window)
            nodeShadow(this).setRefreshReturnValue(true)
        }
        root = node("android.view.View", null)
        focused = node("android.widget.Button", content).apply { isAccessibilityFocused = true; isFocusable = true }
        nodeShadow(root).addChild(focused.unwrap()); nodeShadow(root).focused = focused.unwrap()
        shadowOf(window).setRoot(root.unwrap())
        service.root = root.unwrap(); shadowOf(service).setWindows(listOf(window))
    }
    private fun event(type: Int = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, title: String? = "Home",
                      clazz: String = "android.app.Activity") = AccessibilityEvent.obtain(type).apply {
        packageName = root.packageName; className = clazz
        if (title != null) text.add(title)
        shadowOf(this).setSourceNode(root.unwrap())
    }
    private fun transition(title: String? = "Home", clazz: String = "android.app.Activity") = changes.observe(event(title = title, clazz = clazz))
    private fun focus() {
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
        shadowOf(event).setSourceNode(focused.unwrap())
        interceptor.handle(event)
    }
    @Before fun setup() {
        val permission = "${context.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
        assertNotNull(context.packageManager.getPermissionInfo(permission, 0))
        shadowOf(context).grantPermissions(permission)
        keyguard.setKeyguardLocked(false); keyguard.setIsDeviceLocked(false); power.setIsInteractive(true)
        register("test.alpha", "Alpha"); register("test.beta", "Beta")
        service = Robolectric.buildService(SliderTestService::class.java).create().get()
        tts = TextToSpeech(context, null); speech = SpeechController(tts, context, Reader(context))
        changes = ScreenChangeController(ServiceController(service), speech, context)
        interceptor = SpeechInterceptor(speech, changes)
        changes.start()
    }
    @After fun cleanup() { if (::changes.isInitialized) changes.destroy() }

    private fun home() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        shadowOf(context.packageManager).addResolveInfoForIntent(intent, android.content.pm.ResolveInfo().apply {
            isDefault = true
            activityInfo = android.content.pm.ActivityInfo().apply {
                packageName = "test.alpha"; name = "test.alpha.Home"; enabled = true; exported = true
            }
        })
        scene(); changes.start()
    }
    private fun page(text: String?, pkg: String = "test.alpha", clazz: String = "android.widget.ScrollView") {
        // Real Oppo announcements have a null source and unknown (-1) window ID.
        changes.observe(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_ANNOUNCEMENT).apply {
            packageName = pkg; className = clazz
            if (text != null) this.text.add(text)
        })
    }
    @Test fun `Home provider forward page announcements preserve exact text`() {
        home()
        for (index in 1..6) { page("Page $index of 6"); advance() }
        assertEquals((1..6).map { "Page $it of 6" }, spoken())
    }
    @Test fun `Home provider reverse pages are not suppressed`() {
        home()
        for (index in 6 downTo 1) { page("Page $index of 6"); advance() }
        assertEquals((6 downTo 1).map { "Page $it of 6" }, spoken())
    }
    @Test fun `duplicate Home page announcement is spoken once`() {
        home(); page("Page 2 of 6"); advance(); page("Page 2 of 6"); advance()
        assertEquals(listOf("Page 2 of 6"), spoken())
    }
    @Test fun `rapid pages replace pending feedback without backlog`() {
        home(); page("Page 2 of 6"); advance(50); page("Page 3 of 6"); advance(50)
        page("Page 4 of 6"); advance(199); assertTrue(spoken().isEmpty()); advance(1); advance(1000)
        assertEquals(listOf("Page 4 of 6"), spoken())
    }
    @Test fun `empty and invalid Home announcements are ignored`() {
        home(); page(null); page(""); page("  "); page("test.alpha"); page("Unknown window"); advance()
        assertTrue(spoken().isEmpty())
    }
    @Test fun `ordinary app scroll announcement is not page movement`() {
        scene(); changes.start(); page("Page 2 of 6"); advance()
        assertTrue(spoken().isEmpty())
    }
    @Test fun `unrelated Home announcement class is ignored`() {
        home(); page("Notification received", clazz = "android.app.Activity"); advance()
        assertTrue(spoken().isEmpty())
    }
    @Test fun `inactive package cannot supply Home page feedback`() {
        home(); page("Page 2 of 6", pkg = "test.beta"); advance()
        assertTrue(spoken().isEmpty())
    }
    @Test fun `localized Home text is passed through without English parsing`() {
        home(); page("Pagina 2 van 6"); advance()
        assertEquals(listOf("Pagina 2 van 6"), spoken())
    }
    @Test fun `new feedback cancels stale pending page speech`() {
        home(); page("Page 2 of 6"); speech.speak(Text("Screen curtain off")); advance()
        assertEquals(listOf("Screen curtain off"), spoken())
    }
    @Test fun `normal focus speech takes priority over pending page feedback`() {
        home(); page("Page 2 of 6"); focus(); advance()
        assertEquals(listOf("Open, button"), spoken())
    }
    @Test fun `window switch cancels pending page feedback`() {
        home(); page("Page 2 of 6"); scene("test.beta", "Browse", 2); transition("Browse"); advance()
        assertEquals(listOf("Beta, Browse"), spoken())
    }
    @Test fun `locked Home does not announce pages`() {
        home(); keyguard.setKeyguardLocked(true); page("Page 2 of 6"); advance()
        assertTrue(spoken().isEmpty())
    }

    @Test fun `app title and first focused item form one ordered FLUSH utterance`() {
        scene(); transition(); focus()
        assertTrue(spoken().isEmpty()); advance()
        assertEquals(listOf("Alpha, Home, Open, button"), spoken())
        assertEquals(TextToSpeech.QUEUE_FLUSH, shadowOf(tts).queueMode)
        assertTrue(nodeShadow(focused).performedActions.isEmpty())
    }
    @Test fun `context without a focused event is spoken once`() {
        scene(); transition(); advance(); transition(); advance()
        assertEquals(listOf("Alpha, Home"), spoken())
    }
    @Test fun `repeated same app screen and whitespace equivalent title remain silent`() {
        scene(); transition(); focus(); advance()
        scene(title = "  Home  "); transition("  Home  "); advance()
        assertEquals(1, spoken().size)
    }
    @Test fun `switching apps and returning announces app again`() {
        scene(); transition(); advance()
        scene("test.beta", "Browse", 2); transition("Browse"); advance()
        scene(); transition(); advance()
        assertEquals(listOf("Alpha, Home", "Beta, Browse", "Alpha, Home"), spoken())
    }
    @Test fun `same app new title omits app prefix`() {
        scene(); transition(); advance()
        scene(title = "Details"); transition("Details"); focus(); advance()
        assertEquals(listOf("Alpha, Home", "Details, Open, button"), spoken())
    }
    @Test fun `title equal to app label is not repeated`() {
        scene(title = "Alpha"); transition("Alpha"); focus(); advance()
        assertEquals(listOf("Alpha, Open, button"), spoken())
    }
    @Test fun `focused content already represents title once`() {
        scene(content = "Home"); transition(); focus(); advance()
        assertEquals(listOf("Alpha, Home, button"), spoken())
    }
    @Test fun `partial word does not suppress complete screen title`() {
        scene(title = "Name", content = "Username"); transition("Name"); focus(); advance()
        assertEquals(listOf("Alpha, Name, Username, button"), spoken())
    }
    @Test fun `meaningful app provided description suppresses equivalent title`() {
        scene(); focused.contentDescription = "Home"; transition(); focus(); advance()
        assertEquals(listOf("Alpha, Home, button"), spoken())
    }
    @Test fun `dialog title is announced once and closing does not repeat base title`() {
        scene(); transition(); advance()
        scene(title = "Delete item?", id = 2); transition("Delete item?", "android.app.AlertDialog"); advance()
        transition("Delete item?", "android.app.AlertDialog"); advance()
        scene(); transition(); advance()
        assertEquals(listOf("Alpha, Home", "Delete item?"), spoken())
    }
    @Test fun `dialog that disappears before delivery creates no extra announcement`() {
        scene(); transition(); advance()
        scene(title = "Working", id = 2); transition("Working", "android.app.AlertDialog")
        scene(); transition(); advance()
        assertEquals(listOf("Alpha, Home"), spoken())
    }
    @Test fun `IME window is not a foreground app switch`() {
        scene(); transition(); advance()
        scene("test.keyboard", "Keyboard", 3, AccessibilityWindowInfo.TYPE_INPUT_METHOD)
        transition("Keyboard"); advance()
        scene(); transition(); advance()
        assertEquals(listOf("Alpha, Home"), spoken())
    }
    @Test fun `SpeakTouch accessibility overlay is ignored`() {
        scene(); transition(); advance()
        scene(context.packageName, "SpeakTouch context menu", 3, AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY)
        transition("SpeakTouch context menu"); advance()
        scene(); transition(); advance()
        assertEquals(listOf("Alpha, Home"), spoken())
    }
    @Test fun `rotation and replacement window ID alone are silent`() {
        scene(); transition(); advance()
        scene(id = 2); transition(); advance()
        assertEquals(listOf("Alpha, Home"), spoken())
    }
    @Test fun `latest destination replaces intermediate screen without stale speech`() {
        scene(); transition(); focus()
        scene("test.beta", "Browse", 2); transition("Browse"); focus(); advance()
        assertEquals(listOf("Beta, Browse, Open, button"), spoken())
    }
    @Test fun `active window change without matching event rejects stale pending focus`() {
        scene(); transition(); focus()
        scene("test.beta", "Browse", 2); advance()
        assertTrue(spoken().isEmpty())
    }
    @Test fun `lost focus and failed refresh reject pending focused item`() {
        scene(); transition(); focus(); nodeShadow(focused).setRefreshReturnValue(false); advance()
        assertTrue(spoken().isEmpty())
    }
    @Test fun `new touch cancels pending context while subsequent normal focus still speaks`() {
        scene(); transition(); focus()
        val touch = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_TOUCH_INTERACTION_START)
        changes.observe(touch); interceptor.handle(touch); advance()
        assertTrue(spoken().isEmpty())
        focus(); assertEquals(listOf("Open, button"), spoken())
    }
    @Test fun `new ordinary feedback prevents delayed screen context replacing it`() {
        scene(); transition(); focus(); speech.speak(Text("Screen curtain on")); advance()
        assertEquals(listOf("Screen curtain on"), spoken())
    }
    @Test fun `missing application label fails silently while meaningful title remains`() {
        scene("test.unknown", "Details"); transition("Details"); focus(); advance()
        assertEquals(listOf("Details, Open, button"), spoken())
    }
    @Test fun `unknown class package and resource names are not screen titles`() {
        scene(title = "Unknown window"); transition("Unknown window"); advance()
        scene(title = "test.alpha.Screen"); transition("test.alpha.Screen"); advance()
        scene(title = "test.alpha:id/name"); transition("test.alpha:id/name"); advance()
        assertEquals(listOf("Alpha"), spoken())
    }
    @Test fun `scroll content change and arbitrary announcements are not screen transitions`() {
        scene()
        changes.observe(event(AccessibilityEvent.TYPE_VIEW_SCROLLED))
        changes.observe(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED))
        changes.observe(event(AccessibilityEvent.TYPE_ANNOUNCEMENT))
        advance(); assertTrue(spoken().isEmpty())
    }
    @Test @Config(sdk = [28, 31]) fun `explicit pane title is preferred over window title`() {
        scene(); root.paneTitle = "Account"
        val event = event().apply { contentChangeTypes = AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_APPEARED }
        changes.observe(event); focus(); advance()
        assertEquals(listOf("Alpha, Account, Open, button"), spoken())
    }
    @Test fun `screen on alone and repeated USER PRESENT never fabricate unlock`() {
        scene()
        service.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON)); advance()
        service.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT)); advance()
        service.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT)); advance()
        assertTrue(spoken().isEmpty())
    }
    @Test fun `screen off without verified lock does not claim device is locked`() {
        power.setIsInteractive(false)
        service.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF)); advance()
        assertTrue(spoken().isEmpty())
    }
    @Test fun `verified lock once then unlock once with restored focus`() {
        scene(); transition(); advance()
        keyguard.setIsDeviceLocked(true); keyguard.setKeyguardLocked(true); power.setIsInteractive(false)
        service.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF)); advance()
        service.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF)); advance()
        assertEquals("Device locked", spoken().last()); assertEquals(1, spoken().count { it == "Device locked" })
        power.setIsInteractive(true); service.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON)); advance()
        assertEquals("Device locked", spoken().last())
        keyguard.setIsDeviceLocked(false); keyguard.setKeyguardLocked(false)
        service.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT)); shadowOf(Looper.getMainLooper()).idle()
        focus(); advance()
        assertEquals("Device unlocked, Open, button", spoken().last())
        val count = spoken().size
        service.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT)); advance()
        assertEquals(count, spoken().size)
    }
    @Test fun `startup while already unlocked is silent and seeds existing app`() {
        changes.destroy(); scene(); changes.start(); transition(null); advance()
        assertTrue(spoken().isEmpty())
    }
    @Test fun `startup while locked does not announce unlock until accessibility is verified`() {
        changes.destroy(); keyguard.setIsDeviceLocked(true); keyguard.setKeyguardLocked(true)
        changes.start(); advance(); assertTrue(spoken().isEmpty())
        keyguard.setIsDeviceLocked(false); keyguard.setKeyguardLocked(false)
        service.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT)); advance()
        assertEquals(listOf("Device unlocked"), spoken())
    }
    @Test fun `destroy discards pending context and unregisters broadcasts`() {
        scene(); transition(); changes.destroy(); advance()
        keyguard.setIsDeviceLocked(true); service.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF)); advance()
        assertTrue(spoken().isEmpty())
    }
}
