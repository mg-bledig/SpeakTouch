package com.neo.speaktouch.utils

import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.neo.speaktouch.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27, 28, 31])
class GroupedContentTest {
    private fun node(label: String? = null, marked: Boolean = false) =
        AccessibilityNodeInfoCompat.wrap(AccessibilityNodeInfo.obtain()).apply {
            className = "android.view.View"
            text = label
            isVisibleToUser = true
            isScreenReaderFocusable = marked
        }
    private fun add(parent: AccessibilityNodeInfoCompat, child: AccessibilityNodeInfoCompat) {
        shadowOf(parent.unwrap()).addChild(child.unwrap())
    }
    private fun read(node: AccessibilityNodeInfoCompat) = Reader(RuntimeEnvironment.getApplication()).read(node)

    @Test fun `parent text precedes passive children`() {
        val parent = node("Transfer", true)
        add(parent, node("Download")); add(parent, node("Loading"))
        assertEquals("Transfer, Download, Loading", read(parent))
    }
    @Test fun `parent description precedes passive children`() {
        val parent = node(marked = true).apply { contentDescription = "Transfer" }
        add(parent, node("Download"))
        assertEquals("Transfer, Download", read(parent))
    }
    @Test fun `exact duplicate normalizes whitespace`() {
        val parent = node("Download Loading", true)
        add(parent, node("  Download\nLoading  "))
        assertEquals("Download Loading", read(parent))
    }
    @Test fun `complete phrases already in parent are omitted`() {
        val parent = node("Transfer, Download, Loading", true)
        add(parent, node("Download")); add(parent, node("Loading"))
        assertEquals("Transfer, Download, Loading", read(parent))
    }
    @Test fun `partial words are not duplicates in either direction`() {
        for ((parentLabel, childLabel) in listOf("Download" to "Downloading", "Downloading" to "Download")) {
            val parent = node(parentLabel, true); add(parent, node(childLabel))
            assertEquals("$parentLabel, $childLabel", read(parent))
        }
    }
    @Test fun `distinct children retain identical labels`() {
        val parent = node("Transfer", true)
        add(parent, node("Download")); add(parent, node("Download"))
        assertEquals("Transfer, Download, Download", read(parent))
    }
    @Test fun `duplicate label retains child role and explicit state`() {
        val parent = node("Download", true)
        add(parent, node("Download").apply { className = "android.widget.Button"; stateDescription = "Loading" })
        val role = RuntimeEnvironment.getApplication().getString(R.string.text_button_type)
        assertEquals("Download, $role, Loading", read(parent))
    }
    @Test fun `actionable descendants stay separate`() {
        for (signal in listOf("click", "long", "focus", "screen")) {
            val parent = node("Transfer", true)
            val child = node("Retry").apply {
                isClickable = signal == "click"; isLongClickable = signal == "long"
                isFocusable = signal == "focus"; isScreenReaderFocusable = signal == "screen"
            }
            add(parent, child)
            assertTrue(NodeValidator.mustFocus(child))
            assertEquals("Transfer", read(parent))
        }
    }
    @Test fun `invisible descendants including buttons are omitted`() {
        val parent = node("Transfer", true)
        for (klass in listOf("android.view.View", "android.widget.Button")) {
            add(parent, node("Hidden").apply { className = klass; isVisibleToUser = false })
        }
        assertEquals("Transfer", read(parent))
    }
    @Test fun `nested passive labels and descendants keep parent first order`() {
        val parent = node("Transfer", true)
        val nested = node("Details")
        add(nested, node("Download")); add(nested, node("Loading")); add(parent, nested)
        assertEquals("Transfer, Details, Download, Loading", read(parent))
    }
    @Test fun `nested duplicates compare against ancestor labels`() {
        val parent = node("Download", true)
        val nested = node("Details"); add(nested, node("Download")); add(parent, nested)
        assertEquals("Download, Details", read(parent))
    }
    @Test fun `unmarked parent retains content precedence`() {
        for (description in listOf(false, true)) {
            val parent = node().apply { if (description) contentDescription = "Group label" else text = "Group label" }
            add(parent, node("Child label"))
            assertEquals("Group label", read(parent))
        }
    }
    @Test fun `marked controls and collections retain original own content precedence`() {
        for (kind in listOf("edit", "check", "slider", "collection")) {
            val parent = node("Control", true).apply {
                isEditable = kind == "edit"; isCheckable = kind == "check"
                if (kind == "slider") className = "android.widget.SeekBar"
                if (kind == "collection") setCollectionInfo(AccessibilityNodeInfoCompat.CollectionInfoCompat.obtain(1, 1, false))
            }
            val before = read(parent)
            add(parent, node("Child"))
            assertEquals(before, read(parent))
        }
    }
    @Test fun `independent controls and collections are not absorbed`() {
        val parent = node("Transfer", true)
        for (kind in listOf("edit", "check", "slider", "collection")) {
            val child = node("Control").apply {
                isEditable = kind == "edit"; isCheckable = kind == "check"
                if (kind == "slider") className = "android.widget.SeekBar"
                if (kind == "collection") setCollectionInfo(AccessibilityNodeInfoCompat.CollectionInfoCompat.obtain(1, 1, false))
            }
            add(child, node("Inner")); add(parent, child)
        }
        assertEquals("Transfer", read(parent))
    }
}
