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

import android.accessibilityservice.AccessibilityService
import android.app.AlertDialog
import android.content.DialogInterface
import android.content.Intent
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.neo.speaktouch.SettingsActivity
import com.neo.speaktouch.intercepter.event.CallbackInterceptor
import com.neo.speaktouch.intercepter.gesture.GestureInterceptor
import com.neo.speaktouch.utils.Reader
import com.neo.speaktouch.utils.CustomLabelKey
import com.neo.speaktouch.utils.CustomLabelStore
import org.junit.Assert.*
import org.junit.After
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
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], shadows = [SliderNodeShadow::class])
class ContextMenuControllerTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var service: SliderTestService
    private lateinit var target: AccessibilityNodeInfoCompat
    private lateinit var root: AccessibilityNodeInfoCompat
    private lateinit var controller: ContextMenuController
    private lateinit var tts: TextToSpeech
    private lateinit var speech: SpeechController
    private lateinit var gestures: GestureInterceptor
    private var previousFramePause: Boolean? = null
    private fun shadow(node: AccessibilityNodeInfoCompat): SliderNodeShadow = Shadow.extract(node.unwrap())
    private fun dialog() = ShadowDialog.getLatestDialog() as AlertDialog
    @Before fun setup() {
        service=Robolectric.buildService(SliderTestService::class.java).create().get()
        fun node()=AccessibilityNodeInfoCompat.wrap(AccessibilityNodeInfo.obtain()).apply {
            isVisibleToUser=true; isEnabled=true
            val window=AccessibilityWindowInfo.obtain(); shadowOf(window).setId(1)
            shadowOf(unwrap()).setAccessibilityWindowInfo(window)
            shadow(this).setRefreshReturnValue(true)
        }
        root=node(); target=node().apply { isAccessibilityFocused=true; isClickable=true; text="Target"; addAction(16) }
        shadow(root).focused=target.unwrap(); service.root=root.unwrap()
        tts=TextToSpeech(context,null)
        speech=SpeechController(tts,context,Reader(context))
        val wrapper=ServiceController(service)
        controller=ContextMenuController(wrapper,ActionController(wrapper,context),speech)
        gestures=GestureInterceptor(FocusController(CallbackInterceptor(),wrapper),wrapper,
            SliderController(wrapper,speech),RefocusController(wrapper,speech),speech,controller)
    }
    @After fun cleanup() {
        // Cancel this fixture's timers and dismiss any menu even after a failed assertion.
        if (::controller.isInitialized) controller.destroy()
        if (::speech.isInitialized) speech.shutdown()
        previousFramePause?.let { ShadowChoreographer.setPaused(it) }
    }
    @Test fun `down right opens accessible overlay without speech or actions`() {
        assertTrue(gestures.handle(AccessibilityService.GESTURE_SWIPE_DOWN_AND_RIGHT))
        assertTrue(controller.isOpen)
        assertTrue(dialog().isShowing)
        assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,dialog().window!!.attributes.type)
        assertTrue(shadow(target).performedActions.isEmpty())
        assertTrue(shadowOf(tts).spokenTextList.isEmpty())
        controller.destroy()
    }
    @Test fun `empty app command set still offers Settings without changing the target`() {
        target.text=null; target.isClickable=false; target.removeAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK)
        controller.open()
        assertTrue(controller.isOpen)
        assertEquals(listOf("Settings", "Screen Curtain", "Cancel"), buttons(dialog().window!!.decorView)
            .filter { it.isShown }.map { it.text.toString() })
        assertTrue(shadowOf(tts).spokenTextList.isEmpty())
        assertTrue(shadow(target).performedActions.isEmpty())
        controller.cancel()
    }
    @Test fun `Cancel dismisses and restores original when safe`() {
        controller.open(); val old=dialog(); target.isAccessibilityFocused=false
        controller.cancel()
        assertFalse(old.isShowing); assertFalse(controller.isOpen)
        assertEquals(listOf(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS),shadow(target).performedActions)
    }
    @Test fun `replacement closes old dialog and destruction releases latest`() {
        controller.open(); val old=dialog()
        controller.open(); val replacement=dialog()
        assertFalse(old.isShowing); assertTrue(replacement.isShowing)
        controller.destroy()
        assertFalse(replacement.isShowing); assertFalse(controller.isOpen)
        assertTrue(shadow(target).performedActions.isEmpty())
    }
    @Test fun `target disappearance dismisses without restoring focus`() {
        controller.open(); shadow(target).setRefreshReturnValue(false)
        controller.onEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED))
        assertFalse(controller.isOpen)
        assertTrue(shadow(target).performedActions.isEmpty())
        assertEquals(listOf("Target no longer available"),shadowOf(tts).spokenTextList)
    }
    @Test fun `down left still Back and up down still rereads`() {
        assertTrue(gestures.handle(AccessibilityService.GESTURE_SWIPE_DOWN_AND_LEFT))
        assertEquals(listOf(AccessibilityService.GLOBAL_ACTION_BACK),shadowOf(service).globalActionsPerformed)
        assertTrue(gestures.handle(AccessibilityService.GESTURE_SWIPE_UP_AND_DOWN))
        assertTrue(shadowOf(tts).spokenTextList.isNotEmpty())
        assertFalse(controller.isOpen)
    }
    @Test fun `global commands remain available with no focused app node`() {
        shadow(root).focused = null
        controller.open()
        assertTrue(controller.isOpen)
        assertEquals(listOf("Settings", "Screen Curtain", "Cancel"), buttons(dialog().window!!.decorView)
            .filter { it.isShown }.map { it.text.toString() })
        buttons(dialog().window!!.decorView).first { it.text.toString() == "Settings" }.performClick()
        assertEquals(SettingsActivity::class.java.name, shadowOf(service).nextStartedActivity.component!!.className)
        assertTrue(shadow(target).performedActions.isEmpty())
    }
    @Test fun `invalid initial app target gets global commands without app commands`() {
        shadow(target).setRefreshReturnValue(false)
        controller.open()
        assertEquals(listOf("Settings", "Screen Curtain", "Cancel"), buttons(dialog().window!!.decorView)
            .filter { it.isShown }.map { it.text.toString() })
        assertTrue(shadow(target).performedActions.isEmpty())
        controller.cancel()
    }
    @Test fun `curtain command toggles with removed target and rejects stale row callbacks`() {
        val curtain = ScreenCurtainController(service, speech)
        var attached = false
        curtain.window = object : CurtainWindow {
            override fun attach() { attached = true }
            override fun remove() { attached = false }
            override fun resize() = Unit
            override fun raise() = Unit
        }
        controller = ContextMenuController(ServiceController(service),
            ActionController(ServiceController(service), context), speech, curtain)
        controller.open()
        val button = buttons(dialog().window!!.decorView).first { it.text.toString() == "Screen Curtain" }
        shadow(target).setRefreshReturnValue(false)
        button.performClick(); button.performClick()
        assertTrue(attached); assertTrue(curtain.isEnabled)
        assertFalse(controller.isOpen)
        assertTrue(shadow(target).performedActions.isEmpty())
        curtain.destroy()
    }
    @Test fun `curtain can turn off through menu when no app window exists`() {
        val curtain = ScreenCurtainController(service, speech)
        curtain.window = object : CurtainWindow {
            override fun attach() = Unit
            override fun remove() = Unit
            override fun resize() = Unit
            override fun raise() = Unit
        }
        curtain.enable()
        service.root = null
        controller = ContextMenuController(ServiceController(service),
            ActionController(ServiceController(service), context), speech, curtain)
        controller.open()
        buttons(dialog().window!!.decorView).first { it.text.toString() == "Screen Curtain" }.performClick()
        assertFalse(curtain.isEnabled); assertFalse(controller.isOpen)
        assertTrue(shadow(target).performedActions.isEmpty())
        curtain.destroy()
    }
    private fun curtainMenu(initiallyOn: Boolean = false): ScreenCurtainController {
        val curtain = ScreenCurtainController(service, speech)
        curtain.window = object : CurtainWindow {
            override fun attach() = Unit
            override fun remove() = Unit
            override fun resize() = Unit
            override fun raise() = Unit
        }
        if (initiallyOn) curtain.enable()
        controller = ContextMenuController(ServiceController(service),
            ActionController(ServiceController(service), context), speech, curtain)
        return curtain
    }
    private fun chooseCurtain() = buttons(dialog().window!!.decorView)
        .first { it.text.toString() == "Screen Curtain" }.performClick()
    private fun restorationSpeech() {
        target.isAccessibilityFocused = true
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
        shadowOf(event).setSourceNode(target.unwrap())
        controller.onEvent(event)
        com.neo.speaktouch.intercepter.event.SpeechInterceptor(speech).handle(event)
    }
    private fun waitForCurtainFeedback() = shadowOf(Looper.getMainLooper())
        .idleFor(java.time.Duration.ofMillis(150))
    @Test fun `curtain ON announcement follows dismissal and restored node speech once`() {
        val curtain = curtainMenu()
        controller.open(); val menu = dialog(); target.isAccessibilityFocused = false
        chooseCurtain()
        assertFalse(menu.isShowing); assertFalse(controller.isOpen); assertTrue(curtain.isEnabled)
        assertTrue(shadowOf(tts).spokenTextList.isEmpty())
        assertEquals(listOf(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS), shadow(target).performedActions)
        restorationSpeech()
        assertTrue(shadowOf(tts).spokenTextList.last().contains("Target"))
        waitForCurtainFeedback()
        assertEquals("Screen curtain on", shadowOf(tts).spokenTextList.last())
        assertEquals(1, shadowOf(tts).spokenTextList.count { it == "Screen curtain on" })
        waitForCurtainFeedback()
        assertEquals(1, shadowOf(tts).spokenTextList.count { it == "Screen curtain on" })
        curtain.destroy()
    }
    @Test @LooperMode(LooperMode.Mode.PAUSED)
    fun `curtain OFF announcement follows restored speech and late window event`() {
        // A paused main looper alone does not stop automatic vsync from advancing
        // Robolectric's shared clock during dialog layout/dismissal callbacks.
        previousFramePause = ShadowChoreographer.isPaused()
        ShadowChoreographer.setPaused(true)
        val mainLooper = shadowOf(Looper.getMainLooper())
        fun advance(ms: Long) = mainLooper.idleFor(java.time.Duration.ofMillis(ms))
        fun assertNoOff() = assertFalse(shadowOf(tts).spokenTextList.contains("Screen curtain off"))
        val curtain = curtainMenu(initiallyOn = true)
        try {
            val previous = shadowOf(tts).spokenTextList.size
            controller.open(); target.isAccessibilityFocused = false; chooseCurtain()
            assertFalse(curtain.isEnabled)
            assertEquals(previous, shadowOf(tts).spokenTextList.size)
            restorationSpeech()
            // Drain immediate UI/TTS callbacks; delayed curtain feedback stays pending.
            mainLooper.idle()
            assertTrue(shadowOf(tts).spokenTextList.last().contains("Target"))
            advance(100)
            assertNoOff()
            val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
            controller.onEvent(event)
            com.neo.speaktouch.intercepter.event.SpeechInterceptor(speech).handle(event)
            mainLooper.idle()
            advance(50) // The original 150 ms deadline must have been cancelled.
            assertNoOff()
            advance(99) // Still before the new 150 ms settling interval expires.
            assertNoOff()
            advance(1) // Cross the late event's settling deadline.
            assertEquals("Screen curtain off", shadowOf(tts).spokenTextList.last())
            assertEquals(1, shadowOf(tts).spokenTextList.count { it == "Screen curtain off" })
            advance(1000)
            assertEquals(1, shadowOf(tts).spokenTextList.count { it == "Screen curtain off" })
        } finally {
            curtain.destroy()
        }
    }
    @Test fun `destroy cancels pending curtain menu feedback`() {
        val curtain = curtainMenu()
        controller.open(); chooseCurtain(); controller.destroy()
        waitForCurtainFeedback()
        assertTrue(shadowOf(tts).spokenTextList.isEmpty())
        curtain.destroy()
    }
    @Test fun `new menu cancels prior curtain confirmation`() {
        val curtain = curtainMenu()
        controller.open(); chooseCurtain(); controller.open()
        waitForCurtainFeedback()
        assertTrue(shadowOf(tts).spokenTextList.isEmpty())
        controller.destroy(); curtain.destroy()
    }
    @Test fun `changed curtain state cancels obsolete ON confirmation`() {
        val curtain = curtainMenu()
        controller.open(); chooseCurtain()
        curtain.disable(announce = false)
        waitForCurtainFeedback()
        assertTrue(shadowOf(tts).spokenTextList.isEmpty())
        controller.destroy(); curtain.destroy()
    }
    @Test fun `different app window cancels pending curtain confirmation`() {
        val curtain = curtainMenu()
        controller.open(); chooseCurtain()
        val otherRoot = AccessibilityNodeInfo.obtain()
        val window = AccessibilityWindowInfo.obtain(); shadowOf(window).setId(2)
        shadowOf(otherRoot).setAccessibilityWindowInfo(window)
        service.root = otherRoot
        waitForCurtainFeedback()
        assertTrue(shadowOf(tts).spokenTextList.isEmpty())
        controller.destroy(); curtain.destroy()
    }
    private fun buttons(view: android.view.View): List<android.widget.Button> = when (view) {
        is android.widget.Button -> listOf(view)
        is android.view.ViewGroup -> (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) }
        else -> emptyList()
    }
    @Test fun `Settings follows existing app commands in the main menu`() {
        controller.open()
        assertEquals(listOf("Click", "Settings", "Screen Curtain", "Cancel"), buttons(dialog().window!!.decorView)
            .filter { it.isShown }.map { it.text.toString() })
        controller.destroy()
    }
    @Test fun `Settings launches local activity once without refreshing or acting on app target`() {
        controller.open(); val originalDialog = dialog()
        val settings = buttons(originalDialog.window!!.decorView).first { it.text.toString() == "Settings" }
        val menuNode = AccessibilityNodeInfo.obtain(); shadow(root).focused = menuNode
        target.isAccessibilityFocused = false
        shadow(target).onRefresh = { fail("Settings must not revalidate the retained app target"); false }
        settings.performClick(); settings.performClick()
        val intent = shadowOf(service).nextStartedActivity
        assertNotNull(intent)
        assertEquals(SettingsActivity::class.java.name, intent.component!!.className)
        assertEquals(service.packageName, intent.component!!.packageName)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertNull(shadowOf(service).nextStartedActivity)
        assertFalse(controller.isOpen); assertFalse(originalDialog.isShowing)
        assertTrue(shadow(target).performedActions.isEmpty())
        val menuShadow: SliderNodeShadow = Shadow.extract(menuNode)
        assertTrue(menuShadow.performedActions.isEmpty())
        assertTrue(shadowOf(tts).spokenTextList.isEmpty())
    }
    @Test fun `cancelled Settings row cannot launch later`() {
        controller.open()
        val settings = buttons(dialog().window!!.decorView).first { it.text.toString() == "Settings" }
        controller.cancel(); settings.performClick()
        assertNull(shadowOf(service).nextStartedActivity)
        assertTrue(shadow(target).performedActions.isEmpty())
    }
    @Test fun `replaced Settings row cannot launch from an old session`() {
        controller.open()
        val settings = buttons(dialog().window!!.decorView).first { it.text.toString() == "Settings" }
        controller.open(); settings.performClick()
        assertNull(shadowOf(service).nextStartedActivity)
        assertTrue(controller.isOpen)
        controller.destroy()
    }
    @Test fun `selecting Click invokes original once without reread or restoration`() {
        shadow(target).setOnPerformActionListener { _,_ -> true }
        controller.open()
        val click=buttons(dialog().window!!.decorView).first { it.text.toString()=="Click" }
        val menuNode=AccessibilityNodeInfo.obtain()
        shadow(root).focused=menuNode
        target.isAccessibilityFocused=false
        click.performClick(); click.performClick()
        assertEquals(listOf(16),shadow(target).performedActions)
        assertFalse(controller.isOpen)
        assertTrue(shadowOf(tts).spokenTextList.isEmpty())
        val menuShadow: SliderNodeShadow=Shadow.extract(menuNode)
        assertTrue(menuShadow.performedActions.isEmpty())
    }
    @Test fun `provider rejection produces concise failure feedback`() {
        shadow(target).setOnPerformActionListener { _,_ -> false }
        controller.open()
        buttons(dialog().window!!.decorView).first { it.text.toString()=="Click" }.performClick()
        assertEquals(listOf("Action failed"),shadowOf(tts).spokenTextList)
        assertEquals(listOf(16),shadow(target).performedActions)
    }
    @Test fun `closing owner-window event does not override focus handoff`() {
        val event=AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
            shadowOf(this).setWindowId(1); packageName="fixture"
        }
        controller.open()
        assertFalse(controller.shouldInitializeFocus(event))
        controller.cancel()
        assertFalse(controller.shouldInitializeFocus(event))
        assertTrue(controller.shouldInitializeFocus(event))
    }
    @Test fun `Copy text announces only concise success`() {
        target.isClickable=false
        target.removeAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK)
        controller.open()
        buttons(dialog().window!!.decorView).first { it.text.toString()=="Copy text" }.performClick()
        assertEquals(listOf("Copied"),shadowOf(tts).spokenTextList)
        assertTrue(shadow(target).performedActions.isEmpty())
    }
    private fun embeddedLinks() {
        target.text="Help License"
        val prefix="androidx.view.accessibility.AccessibilityNodeInfoCompat."
        target.extras.putIntegerArrayList(prefix+"SPANS_START_KEY",arrayListOf(0,5))
        target.extras.putIntegerArrayList(prefix+"SPANS_END_KEY",arrayListOf(4,12))
        target.extras.putIntegerArrayList(prefix+"SPANS_FLAGS_KEY",arrayListOf(33,33))
        target.extras.putIntegerArrayList(prefix+"SPANS_ID_KEY",arrayListOf(7,8))
        target.extras.putInt(prefix+"SPANS_ACTION_ID_KEY",0x7f010200)
        target.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(0x7f010200,null))
    }
    private fun chooseLinks() = buttons(dialog().window!!.decorView).first { it.text.toString()=="Links" }.performClick()
    @Test fun `Links changes accessible rows in same overlay and dispatches on original target`() {
        embeddedLinks()
        shadow(target).setOnPerformActionListener { action,args ->
            assertEquals(0x7f010200,action); assertEquals(8,args!!.getInt("ACCESSIBILITY_CLICKABLE_SPAN_ID")); true
        }
        controller.open(); val originalDialog=dialog()
        val menuNode=AccessibilityNodeInfo.obtain(); shadow(root).focused=menuNode
        target.isAccessibilityFocused=false
        chooseLinks()
        assertSame(originalDialog,dialog()); assertTrue(controller.isOpen)
        assertEquals(listOf("Help","License","Cancel"),buttons(dialog().window!!.decorView)
            .filter { it.isShown }.map { it.text.toString() })
        // AlertDialog retains unused standard buttons in its view tree, but hides them.
        assertEquals(View.GONE, originalDialog.getButton(DialogInterface.BUTTON_POSITIVE).visibility)
        assertEquals(View.GONE, originalDialog.getButton(DialogInterface.BUTTON_NEUTRAL).visibility)
        val selected=buttons(dialog().window!!.decorView).first { it.text.toString()=="License" }
        assertTrue(selected.text is String)
        selected.performClick(); selected.performClick()
        assertEquals(listOf(0x7f010200),shadow(target).performedActions)
        assertFalse(controller.isOpen)
        assertTrue(shadowOf(tts).spokenTextList.isEmpty()) // No claim that the link opened.
        val menuShadow: SliderNodeShadow=Shadow.extract(menuNode)
        assertTrue(menuShadow.performedActions.isEmpty())
    }
    @Test fun `Cancel from Links closes same session and restores only original target`() {
        embeddedLinks(); controller.open(); val originalDialog=dialog(); chooseLinks()
        assertSame(originalDialog,dialog())
        val menuNode=AccessibilityNodeInfo.obtain(); shadow(root).focused=menuNode
        target.isAccessibilityFocused=false
        originalDialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        // Framework dialog buttons deliver their listener and dismissal via Handler messages.
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(controller.isOpen)
        assertFalse(originalDialog.isShowing)
        assertEquals(listOf(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS),shadow(target).performedActions)
        val menuShadow: SliderNodeShadow=Shadow.extract(menuNode)
        assertTrue(menuShadow.performedActions.isEmpty())
    }
    @Test fun `Cancel from Links does not restore a disappeared original target`() {
        embeddedLinks(); controller.open(); chooseLinks(); val originalDialog=dialog()
        target.isAccessibilityFocused=false; shadow(target).setRefreshReturnValue(false)
        originalDialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(controller.isOpen)
        assertFalse(originalDialog.isShowing)
        assertTrue(shadow(target).performedActions.isEmpty())
    }
    @Test fun `Cancel from Links does not restore after active window changes`() {
        embeddedLinks(); controller.open(); chooseLinks(); val originalDialog=dialog()
        target.isAccessibilityFocused=false
        val otherWindow=AccessibilityWindowInfo.obtain(); shadowOf(otherWindow).setId(2)
        shadowOf(root.unwrap()).setAccessibilityWindowInfo(otherWindow)
        originalDialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(controller.isOpen)
        assertFalse(originalDialog.isShowing)
        assertTrue(shadow(target).performedActions.isEmpty())
    }
    @Test fun `stale link text fails without dispatch or focus restoration`() {
        embeddedLinks(); controller.open(); chooseLinks()
        val oldRow=buttons(dialog().window!!.decorView).first { it.text.toString()=="Help" }
        target.text="Else License"; oldRow.performClick()
        assertTrue(shadow(target).performedActions.isEmpty())
        assertEquals(listOf("Action no longer available"),shadowOf(tts).spokenTextList)
    }
    @Test fun `cancelled Links row cannot execute later`() {
        embeddedLinks(); controller.open(); chooseLinks()
        val oldRow=buttons(dialog().window!!.decorView).first { it.text.toString()=="Help" }
        controller.cancel(); oldRow.performClick()
        assertTrue(shadow(target).performedActions.isEmpty())
    }
    @Test fun `replaced Links session cannot execute its old row`() {
        embeddedLinks(); controller.open(); chooseLinks()
        val oldRow=buttons(dialog().window!!.decorView).first { it.text.toString()=="Help" }
        controller.open(); oldRow.performClick()
        assertTrue(shadow(target).performedActions.isEmpty())
        controller.destroy()
    }
    private val customKey = CustomLabelKey("example.app", "example.app:id/share")
    private fun customLabelTarget(saved: String? = null) {
        context.getSharedPreferences(CustomLabelStore.FILE_NAME, android.content.Context.MODE_PRIVATE).edit().clear().commit()
        saved?.let { CustomLabelStore(context).put(customKey, it) }
        target.className="android.widget.ImageButton"; target.text=null
        target.packageName=customKey.packageName; target.viewIdResourceName=customKey.resourceName
    }
    private fun fields(view: View): List<EditText> = when(view) {
        is EditText -> listOf(view)
        is android.view.ViewGroup -> (0 until view.childCount).flatMap { fields(view.getChildAt(it)) }
        else -> emptyList()
    }
    private fun choose(text: String) = buttons(dialog().window!!.decorView).first { it.text.toString()==text }.performClick()
    @Test fun `Add editor stays in same overlay has input focus and allows keyboard`() {
        customLabelTarget(); controller.open(); val original=dialog(); choose("Add label")
        assertSame(original,dialog()); assertTrue(controller.isOpen)
        val editor=fields(dialog().window!!.decorView).single()
        assertEquals("",editor.text.toString()); assertEquals("Custom label",editor.hint.toString())
        assertTrue(editor.hasFocus())
        assertEquals(0,dialog().window!!.attributes.flags and WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        assertEquals(0,dialog().window!!.attributes.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,dialog().window!!.attributes.type)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(shadowOf(context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .isSoftInputVisible)
        controller.cancel(); shadowOf(Looper.getMainLooper()).idle()
        assertNull(CustomLabelStore(context).get(customKey))
    }
    @Test fun `Save uses original identity instead of editor focus and closes session once`() {
        customLabelTarget(); controller.open(); choose("Add label")
        val editor=fields(dialog().window!!.decorView).single(); editor.setText("  Share  ")
        val editorNode=AccessibilityNodeInfo.obtain().apply {
            packageName="editor.app"; viewIdResourceName="editor.app:id/input"
        }
        shadow(root).focused=editorNode; target.isAccessibilityFocused=false
        val save=buttons(dialog().window!!.decorView).first { it.text.toString()=="Save" }
        save.performClick(); save.performClick()
        assertEquals("Share",CustomLabelStore(context).get(customKey))
        assertNull(CustomLabelStore(context).get(CustomLabelKey("editor.app","editor.app:id/input")))
        assertFalse(controller.isOpen); assertFalse(dialog().isShowing)
        assertTrue(shadow(target).performedActions.isEmpty())
        assertEquals(listOf("Label saved"),shadowOf(tts).spokenTextList)
    }
    @Test fun `Edit prepopulates and replaces saved label`() {
        customLabelTarget("Share"); controller.open(); choose("Edit label")
        val editor=fields(dialog().window!!.decorView).single()
        assertEquals("Share",editor.text.toString()); editor.setText("Send"); choose("Save")
        assertEquals("Send",CustomLabelStore(context).get(customKey))
        assertEquals("Send, button",Reader(context).read(target))
    }
    @Test fun `editor Cancel changes nothing and safely returns through original session`() {
        customLabelTarget("Share"); controller.open(); choose("Edit label")
        fields(dialog().window!!.decorView).single().setText("Unsaved")
        target.isAccessibilityFocused=false
        dialog().getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Share",CustomLabelStore(context).get(customKey))
        assertFalse(controller.isOpen)
        assertEquals(listOf(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS),shadow(target).performedActions)
    }
    @Test fun `empty editor Save stays open and stores nothing`() {
        customLabelTarget(); controller.open(); choose("Add label")
        val editor=fields(dialog().window!!.decorView).single(); editor.setText(" \n"); choose("Save")
        assertTrue(controller.isOpen); assertEquals("Enter a label",editor.error.toString())
        assertNull(CustomLabelStore(context).get(customKey)); controller.cancel()
    }
    @Test fun `changed original ID while editor open fails without storing`() {
        customLabelTarget(); controller.open(); choose("Add label")
        fields(dialog().window!!.decorView).single().setText("Share")
        target.viewIdResourceName="example.app:id/other"; choose("Save")
        assertNull(CustomLabelStore(context).get(customKey))
        assertNull(CustomLabelStore(context).get(customKey.copy(resourceName="example.app:id/other")))
        assertEquals(listOf("Action no longer available"),shadowOf(tts).spokenTextList)
    }
    @Test fun `Remove updates speech without performing app actions`() {
        customLabelTarget("Share"); controller.open(); choose("Remove label")
        assertNull(CustomLabelStore(context).get(customKey))
        assertEquals("button",Reader(context).read(target))
        assertTrue(shadow(target).performedActions.isEmpty())
        assertEquals(listOf("Label removed"),shadowOf(tts).spokenTextList)
    }
    @Test fun `app provided button opens Add editor and saves fallback without replacing app speech`() {
        customLabelTarget()
        target.className="android.widget.Button"; target.text="App provided label"
        controller.open(); val original=dialog()
        assertTrue(buttons(original.window!!.decorView).any { it.isShown && it.text.toString()=="Add label" })
        target.isAccessibilityFocused=false
        val menuNode=AccessibilityNodeInfo.obtain(); shadow(root).focused=menuNode
        choose("Add label")
        assertSame(original,dialog()); assertTrue(original.isShowing); assertTrue(controller.isOpen)
        val editor=fields(original.window!!.decorView).single()
        assertEquals("",editor.text.toString())
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(controller.isOpen); assertTrue(original.isShowing)
        assertTrue(shadowOf(tts).spokenTextList.isEmpty())
        editor.setText("User fallback"); choose("Save")
        assertEquals("User fallback",CustomLabelStore(context).get(customKey))
        assertEquals("App provided label, button",Reader(context).read(target))
        assertEquals(listOf("Label saved"),shadowOf(tts).spokenTextList)
        assertTrue(shadow(target).performedActions.isEmpty())
        target.isAccessibilityFocused=true; shadow(root).focused=target.unwrap()
        controller.open()
        val visible=buttons(dialog().window!!.decorView).filter { it.isShown }.map { it.text.toString() }
        assertTrue("Edit label" in visible); assertTrue("Remove label" in visible); assertFalse("Add label" in visible)
        choose("Edit label")
        assertEquals("User fallback",fields(dialog().window!!.decorView).single().text.toString())
        controller.destroy()
    }
    @Test fun `app provided button editor rejects changed identity before opening with feedback`() {
        customLabelTarget(); target.className="android.widget.Button"; target.text="App provided label"
        controller.open()
        target.viewIdResourceName="example.app:id/replaced"
        choose("Add label")
        assertFalse(controller.isOpen); assertFalse(dialog().isShowing)
        assertEquals(listOf("Action no longer available"),shadowOf(tts).spokenTextList)
        assertNull(CustomLabelStore(context).get(customKey))
        assertTrue(shadow(target).performedActions.isEmpty())
    }
    @Test fun `label editor survives original visibility loss and saves without replacing app speech`() {
        customLabelTarget(); target.className="android.widget.Button"; target.text="App provided label"
        controller.open(); choose("Add label"); val original=dialog()
        shadowOf(Looper.getMainLooper()).idle()
        target.isVisibleToUser=false
        controller.onEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED))
        assertTrue(controller.isOpen); assertTrue(original.isShowing)
        fields(original.window!!.decorView).single().setText("User fallback"); choose("Save")
        assertFalse(controller.isOpen); assertFalse(original.isShowing)
        assertEquals("User fallback",CustomLabelStore(context).get(customKey))
        target.isVisibleToUser=true
        assertEquals("App provided label, button",Reader(context).read(target))
        assertEquals(listOf("Label saved"),shadowOf(tts).spokenTextList)
        assertTrue(shadow(target).performedActions.isEmpty())
    }
    @Test fun `Edit editor also survives original visibility loss`() {
        customLabelTarget("Share"); controller.open(); choose("Edit label")
        target.isVisibleToUser=false
        controller.onEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED))
        assertTrue(controller.isOpen)
        fields(dialog().window!!.decorView).single().setText("Send"); choose("Save")
        assertEquals("Send",CustomLabelStore(context).get(customKey))
        assertEquals(listOf("Label saved"),shadowOf(tts).spokenTextList)
    }
    @Test fun `visibility loss before editor opening still rejects Add`() {
        customLabelTarget(); controller.open(); target.isVisibleToUser=false; choose("Add label")
        assertFalse(controller.isOpen); assertNull(CustomLabelStore(context).get(customKey))
        assertTrue(fields(dialog().window!!.decorView).isEmpty())
    }
    @Test fun `Cancel from obscured label editor does not restore invisible target`() {
        customLabelTarget(); controller.open(); choose("Add label"); target.isVisibleToUser=false
        target.isAccessibilityFocused=false; controller.cancel()
        assertFalse(controller.isOpen); assertTrue(shadow(target).performedActions.isEmpty())
        assertNull(CustomLabelStore(context).get(customKey))
    }
    @Test fun `removed target still closes obscured label editor`() {
        customLabelTarget(); controller.open(); choose("Add label")
        target.isVisibleToUser=false; shadow(target).setRefreshReturnValue(false)
        controller.onEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED))
        assertFalse(controller.isOpen); assertFalse(dialog().isShowing)
        assertEquals(listOf("Target no longer available"),shadowOf(tts).spokenTextList)
        assertNull(CustomLabelStore(context).get(customKey))
    }
    @Test fun `changed identity still closes obscured label editor`() {
        customLabelTarget(); controller.open(); choose("Add label")
        target.isVisibleToUser=false; target.viewIdResourceName="example.app:id/replaced"
        controller.onEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED))
        assertFalse(controller.isOpen); assertNull(CustomLabelStore(context).get(customKey))
        assertNull(CustomLabelStore(context).get(customKey.copy(resourceName="example.app:id/replaced")))
    }
    @Test fun `changed active window still closes obscured label editor`() {
        customLabelTarget(); controller.open(); choose("Add label"); target.isVisibleToUser=false
        val otherWindow=AccessibilityWindowInfo.obtain(); shadowOf(otherWindow).setId(2)
        shadowOf(root.unwrap()).setAccessibilityWindowInfo(otherWindow)
        controller.onEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOWS_CHANGED))
        assertFalse(controller.isOpen); assertFalse(dialog().isShowing)
        assertEquals(listOf("Target no longer available"),shadowOf(tts).spokenTextList)
        assertNull(CustomLabelStore(context).get(customKey))
    }
}
