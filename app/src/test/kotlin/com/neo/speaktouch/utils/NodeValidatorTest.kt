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
@Config(sdk = [28, 31])
class NodeValidatorTest {
    private fun node(text: String? = null): AccessibilityNodeInfoCompat =
        AccessibilityNodeInfoCompat.wrap(AccessibilityNodeInfo.obtain()).apply {
            className = "android.view.View"
            this.text = text
            isVisibleToUser = true
        }

    private fun addChild(parent: AccessibilityNodeInfoCompat, child: AccessibilityNodeInfoCompat) {
        shadowOf(parent.unwrap()).addChild(child.unwrap())
    }

    private fun collection(): AccessibilityNodeInfoCompat = node("Collection").apply {
        isClickable = true
        setCollectionInfo(AccessibilityNodeInfoCompat.CollectionInfoCompat.obtain(1, 1, false))
    }

    @Test
    fun `invisible generic nodes reject content focus and grouping despite readable properties`() {
        for (kind in listOf("text", "editable", "checkable", "children")) {
            val node = node(if (kind == "text") "Label" else null).apply {
                isVisibleToUser = false
                isClickable = true
                isEditable = kind == "editable"
                isCheckable = kind == "checkable"
            }
            if (kind == "children") addChild(node, node("Child"))
            assertFalse(kind, NodeValidator.hasReadableContent(node))
            assertFalse(kind, NodeValidator.hasReadableChild(node))
            assertFalse(kind, NodeValidator.mustReadContent(node))
            assertFalse(kind, NodeValidator.mustReadChildren(node))
            assertFalse(kind, NodeValidator.mustFocus(node))
            assertFalse(kind, NodeValidator.isReadableAsChild(node))
            assertFalse(kind, NodeFilter.Focusable.filter(node.unwrap()))
        }
    }

    @Test
    fun `visible text without interaction is readable and traversable but not mandatory focus`() {
        val node = node("Label")
        assertTrue(NodeValidator.hasTextToRead(node))
        assertTrue(NodeValidator.hasReadableContent(node))
        assertTrue(NodeValidator.isReadableAsChild(node))
        assertTrue(NodeFilter.Focusable.filter(node.unwrap()))
        assertFalse(NodeValidator.mustReadContent(node))
        assertFalse(NodeValidator.mustFocus(node))
    }

    @Test
    fun `noneditable description counts while noneditable hint does not`() {
        val described = node().apply { contentDescription = "Description" }
        assertTrue(NodeValidator.hasTextToRead(described))
        assertTrue(NodeValidator.hasReadableContent(described))
        val hinted = node().apply { hintText = "Hint" }
        assertFalse(NodeValidator.hasTextToRead(hinted))
        assertFalse(NodeValidator.hasReadableContent(hinted))
    }

    @Test
    fun `editable hint counts but editable description alone is not text to read`() {
        val editable = node().apply { isEditable = true; contentDescription = "Description" }
        assertFalse(NodeValidator.hasTextToRead(editable))
        assertTrue(NodeValidator.hasReadableContent(editable))
        editable.hintText = "Hint"
        assertTrue(NodeValidator.hasTextToRead(editable))
        editable.hintText = null
        editable.text = "Value"
        assertTrue(NodeValidator.hasTextToRead(editable))
    }

    @Test
    fun `editable and checkable nodes are readable without labels but need interaction for mustFocus`() {
        for (editable in listOf(false, true)) {
            val node = node().apply { isEditable = editable; isCheckable = !editable }
            assertFalse(NodeValidator.hasTextToRead(node))
            assertTrue(NodeValidator.hasReadableContent(node))
            assertTrue(NodeValidator.isReadableAsChild(node))
            assertFalse(NodeValidator.mustFocus(node))
            node.isFocusable = true
            assertTrue(NodeValidator.mustReadContent(node))
            assertTrue(NodeValidator.mustFocus(node))
            assertFalse(NodeValidator.isReadableAsChild(node))
        }
    }

    @Test
    fun `click long click and focus each require focus when content is readable`() {
        for (interaction in listOf("click", "longClick", "focus")) {
            val node = node("Label").apply {
                isClickable = interaction == "click"
                isLongClickable = interaction == "longClick"
                isFocusable = interaction == "focus"
            }
            assertEquals(interaction != "focus", NodeValidator.isClickable(node))
            assertTrue(NodeValidator.hasInteraction(node))
            assertTrue(NodeValidator.mustReadContent(node))
            assertTrue(NodeValidator.mustFocus(node))
            assertFalse(NodeValidator.isReadableAsChild(node))
        }
    }

    @Test
    fun `interaction without content or children requires focus without becoming readable`() {
        for (interaction in listOf("click", "longClick", "focus")) {
            val node = node().apply {
                isClickable = interaction == "click"
                isLongClickable = interaction == "longClick"
                isFocusable = interaction == "focus"
            }
            assertTrue(NodeValidator.hasInteraction(node))
            assertFalse(NodeValidator.mustReadContent(node))
            assertFalse(NodeValidator.mustReadChildren(node))
            assertFalse(NodeValidator.hasReadableContent(node))
            assertTrue(NodeValidator.mustFocus(node))
            assertTrue(NodeFilter.Focusable.filter(node.unwrap()))
        }
    }

    @Test
    fun `interactive container groups noninteractive readable child`() {
        val parent = node().apply { isClickable = true }
        val child = node("Child")
        addChild(parent, child)
        assertTrue(NodeValidator.hasReadableChild(parent))
        assertTrue(NodeValidator.mustReadChildren(parent))
        assertTrue(NodeValidator.mustFocus(parent))
        assertFalse(NodeValidator.hasReadableContent(parent))
        assertTrue(NodeValidator.isReadableAsChild(child))
        assertEquals("Child", Reader(RuntimeEnvironment.getApplication()).read(parent))
    }

    @Test
    fun `noninteractive container is readable as child through descendants but not mustFocus`() {
        val parent = node()
        addChild(parent, node("Child"))
        assertTrue(NodeValidator.hasReadableChild(parent))
        assertTrue(NodeValidator.isReadableAsChild(parent))
        assertFalse(NodeValidator.mustReadChildren(parent))
        assertFalse(NodeValidator.mustFocus(parent))
    }

    @Test
    fun `nested noninteractive containers propagate readable descendants`() {
        val parent = node().apply { isFocusable = true }
        val intermediate = node()
        addChild(intermediate, node("Grandchild"))
        addChild(parent, intermediate)
        assertTrue(NodeValidator.hasReadableChild(parent))
        assertTrue(NodeValidator.mustReadChildren(parent))
        assertTrue(NodeValidator.mustFocus(parent))
    }

    @Test
    fun `independently focusable child is not grouped into parent`() {
        val parent = node().apply { isClickable = true }
        val child = node("Child").apply { isFocusable = true }
        addChild(parent, child)
        assertTrue(NodeValidator.mustFocus(child))
        assertFalse(NodeValidator.isReadableAsChild(child))
        assertFalse(NodeValidator.hasReadableChild(parent))
        assertFalse(NodeValidator.mustReadChildren(parent))
        assertFalse(NodeValidator.mustFocus(parent))
    }

    @Test
    fun `own readable interactive content takes precedence over child grouping`() {
        val parent = node("Parent").apply { isClickable = true }
        addChild(parent, node("Child"))
        assertTrue(NodeValidator.hasReadableChild(parent))
        assertTrue(NodeValidator.mustReadContent(parent))
        assertFalse(NodeValidator.mustReadChildren(parent))
        assertTrue(NodeValidator.mustFocus(parent))
    }

    @Test
    fun `collection with children excludes content and grouped children even when checkable editable interactive`() {
        val parent = collection().apply { isEditable = true; isCheckable = true }
        val child = node("Item")
        addChild(parent, child)
        assertTrue(NodeValidator.isExplorableCollection(parent))
        assertFalse(NodeValidator.hasReadableContent(parent))
        assertFalse(NodeValidator.hasReadableChild(parent))
        assertFalse(NodeValidator.mustReadContent(parent))
        assertFalse(NodeValidator.mustReadChildren(parent))
        assertFalse(NodeValidator.mustFocus(parent))
        assertFalse(NodeValidator.isReadableAsChild(parent))
        assertFalse(NodeFilter.Focusable.filter(parent.unwrap()))
        assertTrue(NodeFilter.Focusable.filter(child.unwrap()))
    }

    @Test
    fun `collection metadata without children does not trigger exclusion`() {
        val parent = collection()
        assertFalse(NodeValidator.isExplorableCollection(parent))
        assertTrue(NodeValidator.hasReadableContent(parent))
        assertTrue(NodeValidator.mustFocus(parent))
    }

    @Test
    fun `children without collection metadata still follow ordinary grouping`() {
        val parent = node().apply { isFocusable = true }
        addChild(parent, node("Item"))
        assertFalse(NodeValidator.isExplorableCollection(parent))
        assertTrue(NodeValidator.mustReadChildren(parent))
    }

    @Test
    fun `readable child under mustFocus ancestor stays groupable but filter excludes separate traversal`() {
        val ancestor = node("Parent").apply { isClickable = true }
        val intermediate = node()
        val child = node("Child")
        addChild(intermediate, child)
        addChild(ancestor, intermediate)
        assertTrue(NodeValidator.mustFocus(ancestor))
        assertTrue(NodeValidator.hasReadableContent(child))
        assertTrue(NodeValidator.isReadableAsChild(child))
        assertFalse(NodeFilter.Focusable.filter(child.unwrap()))
    }

    @Test
    fun `child requiring its own focus remains traversable under mustFocus ancestor`() {
        val parent = node("Parent").apply { isClickable = true }
        val child = node("Child").apply { isFocusable = true }
        addChild(parent, child)
        assertTrue(NodeValidator.mustFocus(parent))
        assertTrue(NodeValidator.mustFocus(child))
        assertFalse(NodeValidator.isReadableAsChild(child))
        assertTrue(NodeFilter.Focusable.filter(child.unwrap()))
    }

    @Test
    fun `interactive state-only leaf gains focus without gaining readability`() {
        val child = node().apply { stateDescription = "Loading"; isClickable = true }
        assertFalse(NodeValidator.hasTextToRead(child))
        assertFalse(NodeValidator.hasReadableContent(child))
        assertFalse(NodeValidator.isReadableAsChild(child))
        assertTrue(NodeValidator.mustFocus(child))
        assertTrue(NodeFilter.Focusable.filter(child.unwrap()))
        assertEquals("Loading", Reader(RuntimeEnvironment.getApplication()).read(child))
        val parent = node().apply { isClickable = true }
        addChild(parent, child)
        assertFalse(NodeValidator.hasReadableChild(parent))
    }

    @Test
    fun `null and empty text descriptions and hints do not provide readable content`() {
        for (content in listOf(null, "")) {
            val node = node(content).apply { contentDescription = content; hintText = content }
            assertFalse(NodeValidator.hasTextToRead(node))
            assertFalse(NodeValidator.hasReadableContent(node))
            assertFalse(NodeValidator.hasReadableChild(node))
            assertFalse(NodeValidator.isReadableAsChild(node))
            assertFalse(NodeValidator.mustFocus(node))
        }
    }

    @Test
    fun `whitespace counts as nonempty content`() {
        assertTrue(NodeValidator.hasReadableContent(node(" ")))
        assertTrue(NodeValidator.hasReadableContent(node().apply { contentDescription = " " }))
    }

    @Test
    fun `button type fallback accepts unlabeled and invisible child nodes but filter rejects invisibility`() {
        val button = node().apply { className = "android.widget.Button" }
        assertFalse(NodeValidator.hasReadableContent(button))
        assertFalse(NodeValidator.mustFocus(button))
        assertTrue(NodeValidator.isReadableAsChild(button))
        button.isVisibleToUser = false
        assertTrue(NodeValidator.isReadableAsChild(button))
        assertFalse(NodeFilter.Focusable.filter(button.unwrap()))
        val parent = node().apply { isClickable = true }
        addChild(parent, button)
        assertTrue(NodeValidator.hasReadableChild(parent))
        assertTrue(NodeValidator.mustFocus(parent))
    }

    @Test
    fun `visibility does not alter text detection or interaction helper results`() {
        val node = node("Label").apply { isVisibleToUser = false; isLongClickable = true }
        assertTrue(NodeValidator.hasTextToRead(node))
        assertTrue(NodeValidator.isClickable(node))
        assertTrue(NodeValidator.hasInteraction(node))
        assertFalse(NodeValidator.hasReadableContent(node))
    }

    @Test
    fun `importance and enabled flags are not additional validator eligibility gates`() {
        val node = node("Label").apply {
            isImportantForAccessibility = false
            isEnabled = false
            isFocusable = true
        }
        assertTrue(NodeValidator.hasReadableContent(node))
        assertTrue(NodeValidator.mustFocus(node))
    }
}
