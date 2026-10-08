package com.neo.testcompose

import android.app.Activity
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/** Read-only platform-tree capture; leaves enabled screen readers running on API 24+. */
class HierarchyCapture : Instrumentation() {
    private var selectedCase = 0
    private var captureCurrent = false

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        selectedCase = arguments?.getString("case")?.toIntOrNull() ?: 0
        captureCurrent = arguments?.getString("current") == "true"
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            check(Build.VERSION.SDK_INT >= 24) { "Capture requires API 24+ to preserve running screen readers" }
            val automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            if (!captureCurrent) {
                startActivitySync(Intent(Intent.ACTION_MAIN).setClassName(targetContext, "com.neo.testcompose.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("case", selectedCase))
            }
            automation.waitForIdle(500, 5000)
            val root = checkNotNull(automation.rootInActiveWindow) { "No active window" }
            try {
                val expectedPackage = if (captureCurrent) "com.neo.speaktouch.test" else "com.neo.speaktouch.testcompose"
                check(root.packageName?.toString() == expectedPackage) {
                    "Expected $expectedPackage in foreground"
                }
                result.putString("hierarchy", capture(root).toString(2))
            } finally {
                root.recycle()
            }
            finish(Activity.RESULT_OK, result)
        } catch (error: Exception) {
            result.putString("error", error.toString())
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private data class Entry(
        val node: AccessibilityNodeInfo,
        val id: String,
        val parentId: String?,
        val childIndex: Int?,
        val children: MutableList<String> = mutableListOf()
    )

    private fun capture(root: AccessibilityNodeInfo): JSONObject {
        val entries = mutableListOf<Entry>()
        val ids = mutableMapOf<AccessibilityNodeInfo, String>()
        fun collect(node: AccessibilityNodeInfo, id: String, parentId: String?, index: Int?, depth: Int) {
            check(depth < 64) { "Tree too deep" }
            ids[node] = id
            val entry = Entry(node, id, parentId, index)
            entries.add(entry)
            for (childIndex in 0 until node.childCount) {
                val child = node.getChild(childIndex) ?: continue
                val existing = ids[child]
                if (existing != null) {
                    entry.children.add(existing)
                    child.recycle()
                } else {
                    val childId = "$id/$childIndex"
                    entry.children.add(childId)
                    collect(child, childId, id, childIndex, depth + 1)
                }
            }
        }
        fun reference(node: AccessibilityNodeInfo?): Any {
            if (node == null) return JSONObject.NULL
            try {
                return JSONObject().apply {
                    put("id", ids[node] ?: JSONObject.NULL)
                    put("outsideSnapshot", node !in ids)
                    put("className", node.className ?: JSONObject.NULL)
                    put("text", node.text ?: JSONObject.NULL)
                    put("contentDescription", node.contentDescription ?: JSONObject.NULL)
                    put("visible", node.isVisibleToUser)
                }
            } finally {
                node.recycle()
            }
        }
        try {
            collect(root, "root", null, null, 0)
            val nodes = JSONArray()
            for (entry in entries) {
                val node = entry.node
                val bounds = Rect().also { node.getBoundsInScreen(it) }
                nodes.put(JSONObject().apply {
                    put("id", entry.id)
                    put("parentId", entry.parentId ?: JSONObject.NULL)
                    put("childIndex", entry.childIndex ?: JSONObject.NULL)
                    put("windowId", node.windowId)
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
                    put("bounds", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
                    put("children", JSONArray(entry.children))
                    put("traversalBefore", reference(node.traversalBefore))
                    put("traversalAfter", reference(node.traversalAfter))
                })
            }
            return JSONObject().put("root", "root").put("nodes", nodes)
        } finally {
            // Keep nodes alive until references are resolved; root belongs to the caller.
            entries.drop(1).forEach { it.node.recycle() }
        }
    }
}
