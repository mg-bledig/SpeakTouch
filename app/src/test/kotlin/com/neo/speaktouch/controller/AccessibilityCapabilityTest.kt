package com.neo.speaktouch.controller

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.neo.speaktouch.service.SpeakTouchService
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 26, 28, 31])
class AccessibilityCapabilityTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private fun parsedInfo(): AccessibilityServiceInfo {
        val candidates = context.packageManager.queryIntentServices(
            Intent(AccessibilityService.SERVICE_INTERFACE).setPackage(context.packageName), PackageManager.GET_META_DATA)
        val service = candidates.single { it.serviceInfo.name == SpeakTouchService::class.java.name }
        // Android itself loads the manifest metadata and selects the device's XML qualifier.
        // The metadata-parsing constructor is hidden from the public SDK, but exists
        // in Robolectric's Android framework. Invoke it rather than setting capability bits.
        return AccessibilityServiceInfo::class.java.getConstructor(
            android.content.pm.ResolveInfo::class.java, android.content.Context::class.java
        ).newInstance(service, context)
    }

    @Test fun `Android parsed service metadata grants key filtering capability`() {
        val info = parsedInfo()
        val expected = AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT or
            AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_TOUCH_EXPLORATION or
            AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS
        assertEquals(expected, info.capabilities)
        assertTrue(info.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS != 0)
    }

    @Test fun `qualified metadata preserves existing flags while granting the capability`() {
        val info = parsedInfo()
        assertTrue(info.flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE != 0)
        assertEquals(Build.VERSION.SDK_INT >= 26,
            info.flags and AccessibilityServiceInfo.FLAG_ENABLE_ACCESSIBILITY_VOLUME != 0)
        assertEquals(Build.VERSION.SDK_INT >= 31,
            info.flags and AccessibilityServiceInfo.FLAG_REQUEST_MULTI_FINGER_GESTURES != 0)
        if (Build.VERSION.SDK_INT >= 31) assertTrue(info.isAccessibilityTool)
    }

    @Test fun `every source resource replacement explicitly declares key capability`() {
        val resources = File("src/main/res")
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val namespace = "http://schemas.android.com/apk/res/android"
        val configs = resources.listFiles()!!.filter { it.isDirectory && (it.name == "xml" || it.name.startsWith("xml-")) }
            .map { File(it, "accessibility_service_config.xml") }.filter { it.exists() }
        assertEquals(3, configs.size)
        for (config in configs) {
            val root = factory.newDocumentBuilder().parse(config).documentElement
            assertEquals(config.path, "accessibility-service", root.nodeName)
            assertEquals(config.path, "true", root.getAttributeNS(namespace, "canRequestFilterKeyEvents"))
        }
    }
}
