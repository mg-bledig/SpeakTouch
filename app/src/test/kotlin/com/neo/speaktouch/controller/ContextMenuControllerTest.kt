package com.neo.speaktouch.controller

import android.accessibilityservice.AccessibilityService
import android.app.AlertDialog
import android.speech.tts.TextToSpeech
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
        target.isClickable=false; target.removeAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK)
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
}
