package com.neo.speaktouch.controller

import android.accessibilityservice.AccessibilityService
import android.app.AlertDialog
import android.content.DialogInterface
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.neo.speaktouch.intercepter.event.CallbackInterceptor
import com.neo.speaktouch.intercepter.gesture.GestureInterceptor
import com.neo.speaktouch.utils.Reader
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
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
    @Test fun `down right opens accessible overlay without speech or actions`() {
        assertTrue(gestures.handle(AccessibilityService.GESTURE_SWIPE_DOWN_AND_RIGHT))
        assertTrue(controller.isOpen)
        assertTrue(dialog().isShowing)
        assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,dialog().window!!.attributes.type)
        assertTrue(shadow(target).performedActions.isEmpty())
        assertTrue(shadowOf(tts).spokenTextList.isEmpty())
        controller.destroy()
    }
    @Test fun `empty command set provides feedback without opening`() {
        target.text=null; target.isClickable=false; target.removeAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK)
        controller.open()
        assertFalse(controller.isOpen)
        assertEquals(listOf("No actions available"),shadowOf(tts).spokenTextList)
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
    private fun buttons(view: android.view.View): List<android.widget.Button> = when (view) {
        is android.widget.Button -> listOf(view)
        is android.view.ViewGroup -> (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) }
        else -> emptyList()
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
}
