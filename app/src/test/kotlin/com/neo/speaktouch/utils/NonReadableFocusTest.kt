package com.neo.speaktouch.utils

import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.neo.speaktouch.R
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
class NonReadableFocusTest {
    private fun node() = AccessibilityNodeInfoCompat.wrap(AccessibilityNodeInfo.obtain()).apply {
        className = "android.view.View"
        isVisibleToUser = true
    }

    private fun addChild(parent: AccessibilityNodeInfoCompat, child: AccessibilityNodeInfoCompat) {
        shadowOf(parent.unwrap()).addChild(child.unwrap())
    }

    private fun read(node: AccessibilityNodeInfoCompat) =
        Reader(RuntimeEnvironment.getApplication()).read(node)

    @Test
    fun `blank interactive leaves accept null and empty content without changing readability`() {
        for (interaction in listOf("click", "longClick", "focus")) {
            for (content in listOf(null, "")) {
                val leaf = node().apply {
                    text = content
                    contentDescription = content
                    isClickable = interaction == "click"
                    isLongClickable = interaction == "longClick"
                    isFocusable = interaction == "focus"
                }
                assertFalse(NodeValidator.hasReadableContent(leaf))
                assertFalse(NodeValidator.mustReadContent(leaf))
                assertFalse(NodeValidator.mustReadChildren(leaf))
                assertTrue(NodeValidator.mustFocus(leaf))
                assertTrue(NodeFilter.Focusable.filter(leaf.unwrap()))
                assertFalse(NodeValidator.isReadableAsChild(leaf))
                assertEquals("", read(leaf))
                leaf.isVisibleToUser = false
                assertFalse(NodeValidator.mustFocus(leaf))
                assertFalse(NodeFilter.Focusable.filter(leaf.unwrap()))
            }
        }
    }

    @Test
    fun `unlabeled buttons receive focus and existing button role speech`() {
        for (type in listOf("android.widget.Button", "android.widget.ImageButton")) {
            val button = node().apply { className = type; isClickable = true }
            assertFalse(NodeValidator.hasReadableContent(button))
            assertTrue(NodeValidator.mustFocus(button))
            assertTrue(NodeFilter.Focusable.filter(button.unwrap()))
            assertEquals(RuntimeEnvironment.getApplication().getString(R.string.text_button_type), read(button))
            button.isVisibleToUser = false
            assertFalse(NodeValidator.mustFocus(button))
            assertFalse(NodeFilter.Focusable.filter(button.unwrap()))
        }
    }

    @Test
    fun `passive and screen reader only blank or state-only leaves remain excluded`() {
        for (marked in listOf(false, true)) {
            for (state in listOf(null, "Loading")) {
                val leaf = node().apply { isScreenReaderFocusable = marked; stateDescription = state }
                assertFalse(NodeValidator.hasReadableContent(leaf))
                assertFalse(NodeValidator.mustFocus(leaf))
                assertFalse(NodeFilter.Focusable.filter(leaf.unwrap()))
            }
        }
    }

    @Test
    fun `interactive state-only leaves receive focus and explicit state speech`() {
        for (interaction in listOf("click", "longClick", "focus")) {
            val leaf = node().apply {
                stateDescription = "Loading"
                isClickable = interaction == "click"
                isLongClickable = interaction == "longClick"
                isFocusable = interaction == "focus"
            }
            assertFalse(NodeValidator.hasReadableContent(leaf))
            assertTrue(NodeValidator.mustFocus(leaf))
            assertTrue(NodeFilter.Focusable.filter(leaf.unwrap()))
            assertEquals("Loading", read(leaf))
        }
    }

    @Test
    fun `blank collections remain excluded with or without children`() {
        for (withChild in listOf(false, true)) {
            val collection = node().apply {
                isClickable = true
                isFocusable = true
                setCollectionInfo(AccessibilityNodeInfoCompat.CollectionInfoCompat.obtain(1, 1, false))
            }
            if (withChild) addChild(collection, node().apply { text = "Item" })
            assertFalse(NodeValidator.mustFocus(collection))
            assertFalse(NodeFilter.Focusable.filter(collection.unwrap()))
        }
    }

    @Test
    fun `interactive containers with unreadable children remain excluded`() {
        for (interactiveChild in listOf(false, true)) {
            val parent = node().apply { isClickable = true; isFocusable = true }
            val child = node().apply { isClickable = interactiveChild }
            addChild(parent, child)
            assertFalse(NodeValidator.hasReadableChild(parent))
            assertFalse(NodeValidator.mustFocus(parent))
            assertFalse(NodeFilter.Focusable.filter(parent.unwrap()))
            assertEquals(interactiveChild, NodeFilter.Focusable.filter(child.unwrap()))
        }
    }

    @Test
    fun `screen reader group keeps passive speech and unlabeled control has separate focus`() {
        val parent = node().apply { isScreenReaderFocusable = true }
        val download = node().apply { text = "Download" }
        val loading = node().apply { text = "Loading" }
        val button = node().apply { className = "android.widget.ImageButton"; isClickable = true }
        addChild(parent, download)
        addChild(parent, button)
        addChild(parent, loading)
        assertTrue(NodeValidator.mustFocus(parent))
        assertFalse(NodeFilter.Focusable.filter(download.unwrap()))
        assertFalse(NodeFilter.Focusable.filter(loading.unwrap()))
        assertTrue(NodeFilter.Focusable.filter(button.unwrap()))
        assertFalse(NodeValidator.isReadableAsChild(button))
        assertEquals("Download, Loading", read(parent))
        assertEquals(RuntimeEnvironment.getApplication().getString(R.string.text_button_type), read(button))
    }
}
