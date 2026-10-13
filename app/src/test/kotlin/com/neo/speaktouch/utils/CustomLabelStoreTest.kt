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

package com.neo.speaktouch.utils

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.neo.speaktouch.service.SpeakTouchService
import com.neo.speaktouch.controller.PauseServiceShadow
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [22, 31], manifest = Config.NONE, shadows = [PauseServiceShadow::class])
class CustomLabelStoreTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var store: CustomLabelStore
    private val key = CustomLabelKey("example.app", "example.app:id/share")
    private fun node() = AccessibilityNodeInfoCompat.wrap(AccessibilityNodeInfo.obtain()).apply {
        packageName = key.packageName; viewIdResourceName = key.resourceName
    }
    @Before fun setup() {
        context.getSharedPreferences(CustomLabelStore.FILE_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        store = CustomLabelStore(context)
    }
    @Test fun `add retrieves exact trimmed user label including Unicode`() {
        assertTrue(store.put(key, "  Share café 👋!\n"))
        assertEquals("Share café 👋!", store.get(key))
    }
    @Test fun `empty labels are rejected without replacing old label`() {
        store.put(key, "Share")
        assertFalse(store.put(key, " \n\t")); assertEquals("Share", store.get(key))
    }
    @Test fun `edit replaces one mapping`() {
        store.put(key, "Share"); assertTrue(store.put(key, "Send"))
        assertEquals("Send", store.get(key))
    }
    @Test fun `remove deletes mapping completely`() {
        store.put(key, "Share"); assertTrue(store.remove(key)); assertNull(store.get(key))
        assertFalse(context.getSharedPreferences(CustomLabelStore.FILE_NAME, 0).contains(key.storageKey))
    }
    @Test fun `new store sees persisted data`() {
        store.put(key, "Share")
        assertEquals("Share", CustomLabelStore(context).get(key))
    }
    @Test fun `package name isolates even identical resource names`() {
        val other = key.copy(packageName = "another.app")
        store.put(key, "Share"); store.put(other, "Send")
        assertEquals("Share", store.get(key)); assertEquals("Send", store.get(other))
    }
    @Test fun `different and similar IDs remain independent`() {
        val other = key.copy(resourceName = "example.app:id/share_other")
        store.put(key, "Share"); store.put(other, "Other"); store.remove(key)
        assertNull(store.get(key)); assertEquals("Other", store.get(other))
    }
    @Test fun `separate nodes with same package and resource ID share label`() {
        store.put(CustomLabelKey.from(node())!!, "Share")
        assertEquals("Share", store.get(CustomLabelKey.from(node())!!))
    }
    @Test fun `class and unique identity changes do not change persistent key`() {
        val target = node(); store.put(CustomLabelKey.from(target)!!, "Share")
        target.className = "Different class"; target.uniqueId = "New instance"
        assertEquals(key, CustomLabelKey.from(target))
        assertEquals("Share", store.get(CustomLabelKey.from(target)!!))
    }
    @Test fun `only exact key and user text are stored`() {
        val target = node().apply { text = "Private captured content" }
        store.put(CustomLabelKey.from(target)!!, "Share")
        assertEquals(mapOf(key.storageKey to "Share"), context.getSharedPreferences(CustomLabelStore.FILE_NAME, 0).all)
    }
    @Test fun `missing package or resource ID cannot produce key`() {
        val target = node(); target.packageName = null; assertNull(CustomLabelKey.from(target))
        target.packageName = key.packageName; target.viewIdResourceName = null; assertNull(CustomLabelKey.from(target))
    }
    @Test fun `blank and non resource identifiers are rejected`() {
        val target = node()
        for (id in listOf("", " ", "42", "share", "id/share", "example.app:id/", "example.app:id/share ")) {
            target.viewIdResourceName = id; assertNull(id, CustomLabelKey.from(target))
        }
    }
    @Test fun `password and compat sensitive targets have no key`() {
        val target = node(); target.isPassword = true; assertNull(CustomLabelKey.from(target))
        target.isPassword = false; target.isAccessibilityDataSensitive = true; assertNull(CustomLabelKey.from(target))
    }
    @Test @Config(sdk = [34]) fun `native sensitive target has no key`() {
        val target = node(); target.unwrap().isAccessibilityDataSensitive = true
        assertNull(CustomLabelKey.from(target))
    }
    @Test fun `connection requests resource IDs while retaining existing service flags`() {
        val service = SpeakTouchService()
        service.serviceInfo = AccessibilityServiceInfo().apply { flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        SpeakTouchService::class.java.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }.invoke(service)
        assertTrue(service.serviceInfo.flags and AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS != 0)
        assertTrue(service.serviceInfo.flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS != 0)
    }
    @Test fun `custom label preferences are excluded from backup and transfer in source rules`() {
        val factory = DocumentBuilderFactory.newInstance()
        fun excluded(file: String, parent: String): Boolean {
            val document = factory.newDocumentBuilder().parse(File("src/main/res/xml/$file"))
            val section = document.getElementsByTagName(parent).item(0)
            return (0 until section.childNodes.length).any {
                val child = section.childNodes.item(it)
                child.nodeName == "exclude" && child.attributes.getNamedItem("domain")?.nodeValue == "sharedpref" &&
                    child.attributes.getNamedItem("path")?.nodeValue == "${CustomLabelStore.FILE_NAME}.xml"
            }
        }
        assertTrue(excluded("backup_rules.xml", "full-backup-content"))
        assertTrue(excluded("data_extraction_rules.xml", "cloud-backup"))
        assertTrue(excluded("data_extraction_rules.xml", "device-transfer"))
    }
}
