package com.neo.speaktouch.utils

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [22, 31])
class CustomLabelReaderTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var reader: Reader
    private lateinit var target: AccessibilityNodeInfoCompat
    private val key = CustomLabelKey("example.app", "example.app:id/share")
    @Before fun setup() {
        context.getSharedPreferences(CustomLabelStore.FILE_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        CustomLabelStore(context).put(key, "Share")
        reader = Reader(context)
        target = AccessibilityNodeInfoCompat.wrap(AccessibilityNodeInfo.obtain()).apply {
            className = "android.widget.ImageButton"; isClickable = true; isVisibleToUser = true; isEnabled = true
            packageName = key.packageName; viewIdResourceName = key.resourceName
        }
    }
    @Test fun `unlabeled button uses label then role`() { assertEquals("Share, button", reader.read(target)) }
    @Test fun `explicit state retained after label and role`() {
        target.stateDescription = "Loading"
        assertEquals("Share, button, Loading", reader.read(target))
    }
    @Test fun `generated checkable state retained`() {
        target.className = "android.widget.CheckBox"; target.isCheckable = true; target.isChecked = true
        assertEquals("Share, checkbox, checked", reader.read(target))
    }
    @Test fun `app text takes precedence`() { target.text = "Send now"; assertEquals("Send now, button", reader.read(target)) }
    @Test fun `app content description takes precedence`() { target.contentDescription = "Send now"; assertEquals("Send now, button", reader.read(target)) }
    @Test fun `associated label takes precedence`() {
        val label = AccessibilityNodeInfo.obtain(); label.text = "Send now"; label.isVisibleToUser = true
        shadowOf(target.unwrap()).setLabeledBy(label)
        assertEquals("Send now, button", reader.read(target))
    }
    @Test fun `identical app and custom label spoken only once`() {
        target.text = "Share"; assertEquals("Share, button", reader.read(target))
    }
    @Test fun `hint remains primary content`() {
        target.className = "android.widget.EditText"; target.isEditable = true; target.hintText = "Enter name"
        assertEquals("Enter name, edit field", reader.read(target))
    }
    @Test fun `blank primary content permits fallback`() {
        target.text = " \n"; target.contentDescription = " "
        assertEquals("Share, button", reader.read(target))
    }
    @Test fun `missing ID never resolves saved label`() {
        target.viewIdResourceName = null; assertEquals("button", reader.read(target))
    }
    @Test fun `different ID does not receive label`() {
        target.viewIdResourceName = "example.app:id/other"; assertEquals("button", reader.read(target))
    }
    @Test fun `password and sensitive nodes never use fallback`() {
        target.isPassword = true; assertEquals("button", reader.read(target))
        target.isPassword = false; target.isAccessibilityDataSensitive = true; assertEquals("button", reader.read(target))
    }
    @Test fun `reader sees edits and removal without recreation`() {
        CustomLabelStore(context).put(key, "Send"); assertEquals("Send, button", reader.read(target))
        CustomLabelStore(context).remove(key); assertEquals("button", reader.read(target))
    }
    @Test fun `new reader uses persisted label and repeated ID shares it`() {
        assertEquals("Share, button", Reader(context).read(target))
        val other = AccessibilityNodeInfoCompat.wrap(AccessibilityNodeInfo.obtain()).apply {
            className = "android.widget.Button"; packageName = key.packageName; viewIdResourceName = key.resourceName
        }
        assertEquals("Share, button", reader.read(other))
    }
    @Test fun `grouped descendant content is not replaced by saved parent label`() {
        target.className = "android.view.View"; target.isScreenReaderFocusable = true
        val child = AccessibilityNodeInfo.obtain().apply { text = "Download"; isVisibleToUser = true }
        shadowOf(target.unwrap()).addChild(child)
        assertEquals("Download", reader.read(target))
    }
    @Test fun `native group own and descendant content retain ordering`() {
        target.className = "android.view.View"; target.isScreenReaderFocusable = true; target.text = "Transfer"
        val child = AccessibilityNodeInfo.obtain().apply { text = "Download"; isVisibleToUser = true }
        shadowOf(target.unwrap()).addChild(child)
        assertEquals("Transfer, Download", reader.read(target))
    }
    @Test fun `custom label does not change passive empty navigation eligibility`() {
        target.className = "android.view.View"; target.isClickable = false
        assertFalse(NodeValidator.mustFocus(target))
    }
}
