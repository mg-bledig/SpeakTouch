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

import android.content.ClipboardManager
import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.neo.speaktouch.utils.copyableText
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
@Config(sdk=[22,31], shadows=[SliderNodeShadow::class])
class CopyTextTest {
    private lateinit var node: AccessibilityNodeInfoCompat
    private lateinit var root: AccessibilityNodeInfoCompat
    private lateinit var service: SliderTestService
    private val context get()=RuntimeEnvironment.getApplication()
    private val writes=mutableListOf<String>()
    private var available=true
    private var accepts=true
    private fun shadow(): SliderNodeShadow=Shadow.extract(node.unwrap())
    private fun session(): ContextMenuSession {
        val snapshot=ActionController(ServiceController(service),context).discover()!!
        return ContextMenuSession(node.unwrap(),snapshot,1,{available},context,TextClipboard {
            writes.add(it); accepts
        })
    }
    private fun copyCommand(session: ContextMenuSession)=session.commands.single { it.operation==MenuOperation.COPY }
    @Before fun setup() {
        writes.clear()
        node=AccessibilityNodeInfoCompat.wrap(AccessibilityNodeInfo.obtain()).apply {
            className="android.widget.TextView"; text="Original"; isVisibleToUser=true; isEnabled=true; isAccessibilityFocused=true
        }
        val window=AccessibilityWindowInfo.obtain(); shadowOf(window).setId(1)
        shadowOf(node.unwrap()).setAccessibilityWindowInfo(window)
        shadow().setRefreshReturnValue(true)
        root=AccessibilityNodeInfoCompat.wrap(AccessibilityNodeInfo.obtain())
        shadowOf(root.unwrap()).setAccessibilityWindowInfo(window)
        val rootShadow: SliderNodeShadow=Shadow.extract(root.unwrap()); rootShadow.focused=node.unwrap()
        service=SliderTestService().apply { this.root=this@CopyTextTest.root.unwrap() }
    }
    @Test fun `TextView own text`() { assertEquals("Original",copyableText(node)) }
    @Test fun `Button label never copyable`() { node.className="android.widget.Button"; assertNull(copyableText(node)); assertTrue(session().commands.none { it.operation==MenuOperation.COPY }) }
    @Test fun `description wins over text`() { node.contentDescription="Description"; assertEquals("Description",copyableText(node)) }
    @Test fun `description alone works`() { node.text=null; node.contentDescription="Description"; assertEquals("Description",copyableText(node)) }
    @Test fun `blank description falls back to exact text`() { node.contentDescription=" \n"; assertEquals("Original",copyableText(node)) }
    @Test fun `editable never offers Copy even with value and selection`() {
        node.isEditable=true; node.className="android.widget.EditText"; node.contentDescription="Wrong"
        node.setTextSelection(1,3)
        assertNull(copyableText(node))
    }
    @Test fun `editable empty never copies hint fallback`() { node.isEditable=true; node.text=null; node.hintText="Hint"; assertNull(copyableText(node)) }
    @Test fun `hint in text excluded`() { node.isEditable=true; node.text="Hint"; node.isShowingHintText=true; assertNull(copyableText(node)) }
    @Test fun `editable with real value equal to hint still excluded`() {
        node.isEditable=true; node.hintText="Original"; node.isShowingHintText=false
        assertNull(copyableText(node))
    }
    @Test fun `password never copyable`() { node.isPassword=true; node.contentDescription="Description"; assertNull(copyableText(node)); assertTrue(session().commands.none { it.operation==MenuOperation.COPY }) }
    @Test fun `blank content omitted`() { node.text=" \n\t"; assertNull(copyableText(node)) }
    @Test fun `state and custom action label are not content`() {
        node.text=null; node.stateDescription="Loading"; node.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(0x7f010001,"Retry"))
        assertNull(copyableText(node))
    }
    @Test fun `slider and progress labels excluded`() {
        node.rangeInfo=AccessibilityNodeInfoCompat.RangeInfoCompat.obtain(0,0f,100f,40f)
        for(type in listOf("android.widget.SeekBar","android.widget.ProgressBar")) {
            node.className=type; node.contentDescription="Test level"
            assertNull(copyableText(node))
        }
    }
    @Test fun `containers never offer partial Copy`() {
        val child=AccessibilityNodeInfo.obtain().apply { text="Child" }
        shadow().addChild(child)
        assertNull(copyableText(node))
        assertTrue(session().commands.none { it.operation==MenuOperation.COPY })
        node.text=null; assertNull(copyableText(node))
    }
    @Test fun `Unicode punctuation whitespace preserved`() {
        val value="  café 👩🏽‍💻,\n世界!\t"; node.text=value; assertEquals(value,copyableText(node))
    }
    @Test fun `no advertised Copy required and one write with refreshed original`() {
        val captured=session(); val command=copyCommand(captured)
        node.isAccessibilityFocused=false
        val rootShadow: SliderNodeShadow=Shadow.extract(root.unwrap()); rootShadow.focused=AccessibilityNodeInfo.obtain()
        shadow().onRefresh={node.text="Updated";true}
        assertEquals(MenuResult.COPIED,captured.execute(command))
        assertEquals(listOf("Updated"),writes)
        assertTrue(shadow().performedActions.isEmpty())
        assertEquals(MenuResult.TARGET_GONE,captured.execute(command)); assertEquals(1,writes.size)
    }
    @Test fun `stale target and window rejected without clipboard write`() {
        val captured=session(); available=false
        assertEquals(MenuResult.TARGET_GONE,captured.execute(copyCommand(captured))); assertTrue(writes.isEmpty())
    }
    @Test fun `disappeared target rejected`() {
        val captured=session(); shadow().setRefreshReturnValue(false)
        assertEquals(MenuResult.TARGET_GONE,captured.execute(copyCommand(captured))); assertTrue(writes.isEmpty())
    }
    @Test fun `password or empty after opening rejected`() {
        val first=session(); node.isPassword=true
        assertEquals(MenuResult.COPY_UNAVAILABLE,first.execute(copyCommand(first)))
        node.isPassword=false; val second=session(); node.text=null
        assertEquals(MenuResult.COPY_UNAVAILABLE,second.execute(copyCommand(second))); assertTrue(writes.isEmpty())
    }
    @Test fun `clipboard rejection reported`() {
        val captured=session(); accepts=false
        assertEquals(MenuResult.COPY_FAILED,captured.execute(copyCommand(captured))); assertEquals(1,writes.size)
    }
    @Test fun `Android clipboard receives one plain text item`() {
        assertTrue(AndroidTextClipboard(context).copy("Exact 👋"))
        val clipboard=context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(1,clipboard.primaryClip!!.itemCount)
        assertEquals("Exact 👋",clipboard.primaryClip!!.getItemAt(0).text.toString())
    }
    @Test fun `disabled and invisible targets omitted`() {
        node.isEnabled=false; assertTrue(session().commands.none { it.operation==MenuOperation.COPY })
        node.isEnabled=true; val saved=session(); node.isVisibleToUser=false
        assertEquals(MenuResult.TARGET_GONE,saved.execute(copyCommand(saved))); assertTrue(writes.isEmpty())
    }
    @Test fun `clickable generic label is not Copy content`() {
        node.isClickable=true
        assertNull(copyableText(node)); assertTrue(session().commands.none { it.operation==MenuOperation.COPY })
    }
    @Test fun `long clickable label excluded`() {
        node.isLongClickable=true
        assertNull(copyableText(node)); assertTrue(session().commands.none { it.operation==MenuOperation.COPY })
    }
    @Test fun `checkable switch checkbox excluded`() {
        for(type in listOf("android.widget.Switch","android.widget.CheckBox","android.widget.CheckedTextView")) {
            node.className=type; assertNull(copyableText(node))
        }
        node.className="android.view.View"; node.isCheckable=true
        assertNull(copyableText(node))
    }
    @Test fun `advertised app actions exclude otherwise passive text`() {
        for(id in listOf(16,32,4096,32768,2097152,524288,1048576,16908344,16909000,0x7f010001)) {
            val action=AccessibilityNodeInfoCompat.AccessibilityActionCompat(id,"Command")
            node.addAction(action); assertNull(copyableText(node)); node.removeAction(action)
        }
    }
    @Test fun `ordinary focus and text actions do not exclude readable text`() {
        node.isFocusable=true
        node.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_ACCESSIBILITY_FOCUS)
        node.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SET_SELECTION)
        node.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_COPY)
        assertEquals("Original",copyableText(node))
        assertTrue(session().commands.any { it.operation==MenuOperation.COPY })
    }
    @Test fun `group with own label and interactive child omitted`() {
        node.className="android.widget.LinearLayout"; node.text=null; node.contentDescription="Transfer"
        node.isScreenReaderFocusable=true
        val child=AccessibilityNodeInfo.obtain().apply { text="Download"; isClickable=true }
        shadow().addChild(child)
        assertNull(copyableText(node)); assertTrue(session().commands.none { it.operation==MenuOperation.COPY })
    }
    @Test fun `copied sample contains only own text not roles states or focus speech`() {
        val value="Copy sample: café 👋, punctuation!"
        node.text=value; node.stateDescription="Loading"
        val captured=session()
        assertEquals(MenuResult.COPIED,captured.execute(copyCommand(captured)))
        assertEquals(listOf(value),writes); assertTrue(shadow().performedActions.isEmpty())
    }
    @Test fun `becoming interactive before execution prevents clipboard write`() {
        val captured=session(); node.isClickable=true
        assertEquals(MenuResult.COPY_UNAVAILABLE,captured.execute(copyCommand(captured)))
        assertTrue(writes.isEmpty())
    }
    @Test @Config(sdk=[34]) fun `sensitive accessibility data never offers Copy`() {
        node.unwrap().isAccessibilityDataSensitive=true
        assertNull(copyableText(node))
        assertTrue(session().commands.none { it.operation==MenuOperation.COPY })
    }
}
