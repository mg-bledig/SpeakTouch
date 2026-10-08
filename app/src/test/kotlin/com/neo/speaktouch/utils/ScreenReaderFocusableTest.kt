package com.neo.speaktouch.utils

import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.neo.speaktouch.model.NodeFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27, 28, 31])
class ScreenReaderFocusableTest {
    private fun node(label: String? = null, marked: Boolean = false) =
        AccessibilityNodeInfoCompat.wrap(AccessibilityNodeInfo.obtain()).apply {
            className = "android.view.View"
            text = label
            isVisibleToUser = true
            isScreenReaderFocusable = marked
        }

    private fun addChild(parent: AccessibilityNodeInfoCompat, child: AccessibilityNodeInfoCompat) {
        shadowOf(parent.unwrap()).addChild(child.unwrap())
    }

    private fun read(node: AccessibilityNodeInfoCompat) =
        Reader(RuntimeEnvironment.getApplication()).read(node)

    @Test
    fun `explicit flag makes readable node require focus without input focus or click`() {
        val node = node("Download", marked = true)
        assertTrue(node.isScreenReaderFocusable)
        assertFalse(node.isFocusable)
        assertFalse(NodeValidator.isClickable(node))
        assertTrue(NodeValidator.hasInteraction(node))
        assertTrue(NodeValidator.mustReadContent(node))
        assertTrue(NodeValidator.mustFocus(node))
        assertTrue(NodeFilter.Focusable.filter(node.unwrap()))
    }

    @Test
    fun `false flag preserves text traversal without mandatory focus`() {
        val node = node("Download")
        assertFalse(NodeValidator.hasInteraction(node))
        assertFalse(NodeValidator.mustFocus(node))
        assertTrue(NodeValidator.isReadableAsChild(node))
        assertTrue(NodeFilter.Focusable.filter(node.unwrap()))
        node.isClickable = true
        assertTrue(NodeValidator.mustFocus(node))
    }

    @Test
    fun `explicit flag groups passive children into one focus target`() {
        val parent = node(marked = true)
        val first = node("Download")
        val second = node("Loading")
        addChild(parent, first)
        addChild(parent, second)
        assertFalse(NodeValidator.hasReadableContent(parent))
        assertTrue(NodeValidator.mustReadChildren(parent))
        assertTrue(NodeValidator.mustFocus(parent))
        assertTrue(NodeFilter.Focusable.filter(parent.unwrap()))
        for (child in listOf(first, second)) {
            assertTrue(NodeValidator.isReadableAsChild(child))
            assertFalse(NodeFilter.Focusable.filter(child.unwrap()))
        }
        assertEquals("Download, Loading", read(parent))
    }

    @Test
    fun `unmarked passive container leaves children as separate targets`() {
        val parent = node()
        val child = node("Download")
        addChild(parent, child)
        assertFalse(NodeValidator.mustFocus(parent))
        assertFalse(NodeFilter.Focusable.filter(parent.unwrap()))
        assertTrue(NodeFilter.Focusable.filter(child.unwrap()))
    }

    @Test
    fun `own text or description precedes grouped children`() {
        for (description in listOf(false, true)) {
            val parent = node(marked = true).apply {
                if (description) contentDescription = "Group label" else text = "Group label"
            }
            addChild(parent, node("Child label"))
            assertTrue(NodeValidator.mustReadContent(parent))
            assertFalse(NodeValidator.mustReadChildren(parent))
            assertEquals("Group label, Child label", read(parent))
        }
    }

    @Test
    fun `nested passive containers aggregate readable descendants`() {
        val parent = node(marked = true)
        val nested = node()
        addChild(nested, node("Download"))
        addChild(nested, node("Loading"))
        addChild(parent, nested)
        assertTrue(NodeValidator.mustReadChildren(parent))
        assertEquals("Download, Loading", read(parent))
    }

    @Test
    fun `independent interactive children remain separate and are not spoken by parent`() {
        for (interaction in listOf("click", "longClick", "focus", "screenReader")) {
            val parent = node(marked = true)
            val passive = node("Download")
            val control = node("Action").apply {
                isClickable = interaction == "click"
                isLongClickable = interaction == "longClick"
                isFocusable = interaction == "focus"
                isScreenReaderFocusable = interaction == "screenReader"
            }
            addChild(parent, passive)
            addChild(parent, control)
            assertTrue(NodeValidator.mustFocus(parent))
            assertTrue(NodeValidator.mustFocus(control))
            assertFalse(NodeValidator.isReadableAsChild(control))
            assertTrue(NodeFilter.Focusable.filter(control.unwrap()))
            assertEquals("Download", read(parent))
        }
    }

    @Test
    fun `container with only independently focusable children does not become focus target`() {
        val parent = node(marked = true)
        addChild(parent, node("Action").apply { isFocusable = true })
        assertFalse(NodeValidator.hasReadableChild(parent))
        assertFalse(NodeValidator.mustFocus(parent))
        assertFalse(NodeFilter.Focusable.filter(parent.unwrap()))
    }

    @Test
    fun `invisible marked parent remains excluded despite readable children`() {
        val parent = node("Parent", marked = true).apply { isVisibleToUser = false }
        addChild(parent, node("Child"))
        assertFalse(NodeValidator.hasReadableContent(parent))
        assertFalse(NodeValidator.hasReadableChild(parent))
        assertFalse(NodeValidator.mustFocus(parent))
        assertFalse(NodeFilter.Focusable.filter(parent.unwrap()))
    }

    @Test
    fun `empty and state-only marked generic nodes remain excluded`() {
        for (state in listOf(null, "Loading")) {
            val node = node(marked = true).apply { stateDescription = state }
            assertFalse(NodeValidator.hasReadableContent(node))
            assertFalse(NodeValidator.hasReadableChild(node))
            assertFalse(NodeValidator.mustFocus(node))
            assertFalse(NodeFilter.Focusable.filter(node.unwrap()))
        }
    }

    @Test
    fun `collection with children keeps exclusion and individual item traversal`() {
        val parent = node("Collection", marked = true).apply {
            setCollectionInfo(AccessibilityNodeInfoCompat.CollectionInfoCompat.obtain(1, 1, false))
        }
        val item = node("Item")
        addChild(parent, item)
        assertFalse(NodeValidator.hasReadableContent(parent))
        assertFalse(NodeValidator.hasReadableChild(parent))
        assertFalse(NodeValidator.mustFocus(parent))
        assertFalse(NodeFilter.Focusable.filter(parent.unwrap()))
        assertTrue(NodeFilter.Focusable.filter(item.unwrap()))
    }

    @Test
    fun `collection without children retains ordinary readability rules`() {
        val parent = node("Collection", marked = true).apply {
            setCollectionInfo(AccessibilityNodeInfoCompat.CollectionInfoCompat.obtain(1, 1, false))
        }
        assertFalse(NodeValidator.isExplorableCollection(parent))
        assertTrue(NodeValidator.mustFocus(parent))
    }

    @Test
    fun `editable and checkable marked nodes preserve existing readability exceptions`() {
        for (editable in listOf(false, true)) {
            val node = node(marked = true).apply {
                isEditable = editable
                isCheckable = !editable
            }
            assertFalse(NodeValidator.hasTextToRead(node))
            assertTrue(NodeValidator.hasReadableContent(node))
            assertTrue(NodeValidator.mustFocus(node))
            node.isVisibleToUser = false
            assertFalse(NodeValidator.mustFocus(node))
        }
    }
}
