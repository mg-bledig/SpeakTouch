package com.neo.speaktouch.utils

import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [22, 31], manifest = Config.NONE)
class ResourceIdDescriptionTest {
    private fun node(id: String? = "example.app:id/sendButton") =
        AccessibilityNodeInfoCompat.wrap(AccessibilityNodeInfo.obtain()).apply { viewIdResourceName = id }

    @Test fun `resource component is preserved without humanization`() {
        for (name in listOf("sendButton", "send_button", "IcNotificationBtn", "item2Button", "a_123"))
            assertEquals(name, ResourceIdDescription.from(node("example.app:id/$name")))
    }
    @Test fun `missing blank or malformed resource IDs produce no description`() {
        for (id in listOf(null, "", " ", "sendButton", "id/sendButton", "example.app:id/",
            "example.app:id/a/b", "example.app:id/send button", "example.app:layout/sendButton",
            " example.app:id/sendButton", "example.app:id/sendButton "))
            assertNull(id, ResourceIdDescription.from(node(id)))
    }
    @Test fun `password and compat sensitive nodes are excluded`() {
        val node = node(); node.isPassword = true; assertNull(ResourceIdDescription.from(node))
        node.isPassword = false; node.isAccessibilityDataSensitive = true
        assertNull(ResourceIdDescription.from(node))
    }
    @Test @Config(sdk = [34]) fun `native sensitive nodes are excluded`() {
        val node = node(); node.unwrap().isAccessibilityDataSensitive = true
        assertNull(ResourceIdDescription.from(node))
    }
    @Test fun `only the exposed resource ID supplies the name`() {
        val node = node(null).apply {
            className = "android.webkit.WebView"; uniqueId = "sendButton"
            extras.putString("testTag", "sendButton"); extras.putString("htmlId", "sendButton")
        }
        assertNull(ResourceIdDescription.from(node))
        node.viewIdResourceName = "example.app:id/sendButton"
        assertEquals("sendButton", ResourceIdDescription.from(node))
    }
    @Test fun `identical IDs on different nodes independently yield the same name`() {
        assertEquals("sendButton", ResourceIdDescription.from(node()))
        assertEquals("sendButton", ResourceIdDescription.from(node()))
    }
}
