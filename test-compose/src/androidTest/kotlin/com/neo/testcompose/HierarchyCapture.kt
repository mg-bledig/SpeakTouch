package com.neo.testcompose

import android.app.Activity
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/** Read-only platform-tree capture; leaves enabled screen readers running on API 24+. */
class HierarchyCapture : Instrumentation() {
    private var selectedCase = 0
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        selectedCase = arguments?.getString("case")?.toIntOrNull() ?: 0
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            check(Build.VERSION.SDK_INT >= 24) { "Capture requires API 24+ to preserve running screen readers" }
            val automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            startActivitySync(Intent(Intent.ACTION_MAIN).setClassName(targetContext, "com.neo.testcompose.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("case", selectedCase))
            automation.waitForIdle(500, 5000)
            val root = checkNotNull(automation.rootInActiveWindow) { "No active window" }
            check(root.packageName == "com.neo.speaktouch.testcompose") { "Compose test app is not foreground" }
            result.putString("hierarchy", capture(root).toString(2))
            root.recycle()
            finish(Activity.RESULT_OK, result)
        } catch (error: Exception) {
            result.putString("error", error.toString())
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun capture(node: AccessibilityNodeInfo, depth: Int = 0): JSONObject {
        check(depth < 64) { "Tree too deep" }
        val children = JSONArray()
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let {
                children.put(capture(it, depth + 1))
                it.recycle()
            }
        }
        return JSONObject().apply {
            put("className", node.className ?: JSONObject.NULL)
            put("text", node.text ?: JSONObject.NULL)
            put("contentDescription", node.contentDescription ?: JSONObject.NULL)
            put("screenReaderFocusable", if (Build.VERSION.SDK_INT >= 28) node.isScreenReaderFocusable else JSONObject.NULL)
            put("focusable", node.isFocusable)
            put("clickable", node.isClickable)
            put("visible", node.isVisibleToUser)
            put("importantForAccessibility", node.isImportantForAccessibility)
            put("accessibilityFocused", node.isAccessibilityFocused)
            put("childCount", node.childCount)
            put("collection", node.collectionInfo != null)
            put("actions", JSONArray(node.actionList.map { it.toString() }))
            put("children", children)
        }
    }
}
