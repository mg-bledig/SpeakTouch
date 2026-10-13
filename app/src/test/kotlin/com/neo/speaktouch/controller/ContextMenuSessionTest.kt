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
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [22, 31], shadows = [SliderNodeShadow::class])
class ContextMenuSessionTest {
    private lateinit var target: AccessibilityNodeInfoCompat
    private var windowValid = true
    private val retry = 0x7f010001
    private val archive = 0x7f010002
    private fun shadow(): SliderNodeShadow = Shadow.extract(target.unwrap())
    private fun add(id: Int, label: String? = null) = target.unwrap().addAction(AccessibilityNodeInfo.AccessibilityAction(id, label))
    private fun session(): ContextMenuSession {
        val service = SliderTestService()
        val root = AccessibilityNodeInfo.obtain()
        val rootShadow: SliderNodeShadow = Shadow.extract(root)
        rootShadow.focused = target.unwrap()
        val window = AccessibilityWindowInfo.obtain()
        shadowOf(window).setId(1)
        shadowOf(root).setAccessibilityWindowInfo(window)
        service.root = root
        val snapshot = ActionController(ServiceController(service), RuntimeEnvironment.getApplication()).discover()!!
        return ContextMenuSession(target.unwrap(), snapshot, 1, { windowValid }, RuntimeEnvironment.getApplication())
    }
    @Before fun setup() {
        target = AccessibilityNodeInfoCompat.wrap(AccessibilityNodeInfo.obtain()).apply {
            isVisibleToUser = true; isEnabled = true; isAccessibilityFocused = true
        }
        val window = AccessibilityWindowInfo.obtain()
        shadowOf(window).setId(1)
        shadowOf(target.unwrap()).setAccessibilityWindowInfo(window)
        shadow().setRefreshReturnValue(true)
        shadow().setOnPerformActionListener { _, _ -> true }
    }
    @Test fun `clickable yields Click`() {
        target.isClickable = true; add(16)
        assertEquals(listOf("Click"), session().commands.map { it.label })
    }
    @Test fun `long clickable yields Long click`() {
        target.isLongClickable = true; add(32)
        assertEquals(listOf("Long click"), session().commands.map { it.label })
    }
    @Test fun `editable offers Paste and Clear`() {
        target.isEditable = true; add(32768); add(2097152)
        assertEquals(listOf("Paste", "Clear"), session().commands.map { it.label })
    }
    @Test fun `checked yields Uncheck`() {
        target.isCheckable = true; target.isChecked = true; add(16)
        assertEquals("Uncheck", session().commands.single().label)
    }
    @Test fun `unchecked yields Check`() {
        target.isCheckable = true; add(16)
        assertEquals("Check", session().commands.single().label)
    }
    @Test fun `properties alone do not invent actions`() {
        target.isClickable = true; target.isLongClickable = true; target.isEditable = true; target.isCheckable = true
        assertTrue(session().commands.isEmpty())
    }
    @Test fun `disabled yields no commands`() {
        target.isEnabled = false; add(retry, "Retry")
        assertTrue(session().commands.isEmpty())
    }
    @Test fun `custom order and duplicate labels preserved unsupported omitted`() {
        add(retry,"Retry"); add(archive,"Retry"); add(16909000,"Unknown standard"); add(0x7f010003,"")
        assertEquals(listOf(retry, archive), session().commands.map { it.actionId })
    }
    @Test fun `raw editing actions do not leak into Actions section`() {
        add(2097152,"Set text"); add(131072,"Selection"); add(16908349,"Progress"); add(16384,"Copy")
        assertTrue(session().commands.isEmpty())
    }
    private fun execute(id: Int, configure: () -> Unit = {}) {
        configure(); add(id)
        val captured = session()
        target.isAccessibilityFocused = false // Overlay owns focus now.
        assertEquals(MenuResult.SUCCESS, captured.execute(captured.commands.single()))
        assertEquals(listOf(id), shadow().performedActions)
    }
    @Test fun `Click executes original despite menu focus`() = execute(16) { target.isClickable = true }
    @Test fun `Long click executes once`() = execute(32) { target.isLongClickable = true }
    @Test fun `Paste delegates without clipboard or arguments`() = execute(32768) { target.isEditable = true }
    @Test fun `Clear supplies explicit empty text`() {
        target.isEditable = true; add(2097152)
        shadow().setOnPerformActionListener { id, args ->
            assertEquals(2097152,id)
            assertTrue(args!!.containsKey(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE))
            assertEquals("",args.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE))
            true
        }
        val captured = session()
        assertEquals(MenuResult.SUCCESS,captured.execute(captured.commands.single()))
    }
    @Test fun `checkable activation clicks exactly once`() = execute(16) { target.isCheckable = true }
    @Test fun `already changed check state does not toggle again`() {
        target.isCheckable = true; add(16)
        val captured=session(); target.isChecked=true
        assertEquals(MenuResult.SUCCESS,captured.execute(captured.commands.single()))
        assertTrue(shadow().performedActions.isEmpty())
    }
    @Test fun `custom action null args on original target`() {
        add(retry,"Retry")
        shadow().setOnPerformActionListener { id,args -> assertEquals(retry,id); assertNull(args); true }
        val captured=session(); target.isAccessibilityFocused=false
        assertEquals(MenuResult.SUCCESS,captured.execute(captured.commands.single()))
        assertEquals(listOf(retry),shadow().performedActions)
    }
    @Test fun `duplicate callback cannot execute twice`() {
        add(retry,"Retry"); val captured=session(); val command=captured.commands.single()
        assertEquals(MenuResult.SUCCESS,captured.execute(command))
        assertEquals(MenuResult.TARGET_GONE,captured.execute(command))
        assertEquals(1,shadow().performedActions.size)
    }
    @Test fun `provider failure reported`() {
        add(retry,"Retry"); shadow().setOnPerformActionListener { _,_ -> false }
        val captured=session()
        assertEquals(MenuResult.FAILED,captured.execute(captured.commands.single()))
    }
    @Test fun `removed action rejected without fallback`() {
        add(retry,"Retry"); val captured=session()
        target.unwrap().removeAction(AccessibilityNodeInfo.AccessibilityAction(retry,null))
        assertEquals(MenuResult.ACTION_GONE,captured.execute(captured.commands.single()))
        assertTrue(shadow().performedActions.isEmpty())
    }
    @Test fun `changed window rejected`() {
        add(retry,"Retry"); val captured=session(); windowValid=false
        assertEquals(MenuResult.TARGET_GONE,captured.execute(captured.commands.single()))
        assertTrue(shadow().performedActions.isEmpty())
    }
    @Test fun `vanished target rejected`() {
        add(retry,"Retry"); val captured=session(); shadow().setRefreshReturnValue(false)
        assertEquals(MenuResult.TARGET_GONE,captured.execute(captured.commands.single()))
    }
    @Test fun `invisible after capture rejected`() {
        add(retry,"Retry"); val captured=session(); target.isVisibleToUser=false
        assertEquals(MenuResult.TARGET_GONE,captured.execute(captured.commands.single()))
    }
    @Test fun `disabled after capture rejected`() {
        add(retry,"Retry"); val captured=session(); target.isEnabled=false
        assertEquals(MenuResult.ACTION_GONE,captured.execute(captured.commands.single()))
    }
    @Test fun `Cancel restores only original focus`() {
        val captured=session(); target.isAccessibilityFocused=false; captured.cancel()
        assertEquals(listOf(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS),shadow().performedActions)
    }
    @Test fun `Cancel after app transition does not restore`() {
        val captured=session(); target.isAccessibilityFocused=false; windowValid=false; captured.cancel()
        assertTrue(shadow().performedActions.isEmpty())
    }
    @Test fun `invalidated session cannot execute or restore`() {
        add(retry,"Retry"); val captured=session(); target.isAccessibilityFocused=false; captured.invalidate(); captured.cancel()
        assertEquals(MenuResult.TARGET_GONE,captured.execute(captured.commands.single()))
        assertTrue(shadow().performedActions.isEmpty())
    }
    @Test fun `lost custom label is unsupported`() {
        add(retry,"Retry"); val captured=session()
        target.unwrap().removeAction(AccessibilityNodeInfo.AccessibilityAction(retry,null)); add(retry,"")
        assertEquals(MenuResult.ACTION_GONE,captured.execute(captured.commands.single()))
    }
    @Test fun `editable with advertised Paste shows Paste`() {
        target.isEditable = true
        add(AccessibilityNodeInfo.ACTION_PASTE)
        assertEquals(listOf(MenuOperation.PASTE), session().commands.map { it.operation })
    }
    @Test fun `editable without advertised Paste omits Paste even with Clear available`() {
        target.isEditable = true
        add(AccessibilityNodeInfo.ACTION_SET_TEXT)
        val commands = session().commands
        assertFalse(commands.any { it.operation == MenuOperation.PASTE })
        assertEquals(listOf(MenuOperation.CLEAR), commands.map { it.operation })
    }
}
