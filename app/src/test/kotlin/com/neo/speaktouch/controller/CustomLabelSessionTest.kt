package com.neo.speaktouch.controller

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.neo.speaktouch.utils.CustomLabelKey
import com.neo.speaktouch.utils.CustomLabelStore
import com.neo.speaktouch.utils.Reader
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [22, 31], shadows = [SliderNodeShadow::class])
class CustomLabelSessionTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var target: AccessibilityNodeInfoCompat
    private lateinit var store: CustomLabelStore
    private val key = CustomLabelKey("example.app", "example.app:id/share")
    private var windowValid = true
    private fun shadow(): SliderNodeShadow = Shadow.extract(target.unwrap())
    private val labelOperations = setOf(MenuOperation.ADD_LABEL, MenuOperation.EDIT_LABEL, MenuOperation.REMOVE_LABEL)
    @Before fun setup() {
        context.getSharedPreferences(CustomLabelStore.FILE_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        store = CustomLabelStore(context)
        target = AccessibilityNodeInfoCompat.wrap(AccessibilityNodeInfo.obtain()).apply {
            className = "android.widget.ImageButton"; isVisibleToUser = true; isEnabled = true; isAccessibilityFocused = true
            packageName = key.packageName; viewIdResourceName = key.resourceName; isClickable = true; addAction(16)
        }
        val window = AccessibilityWindowInfo.obtain(); shadowOf(window).setId(1)
        shadowOf(target.unwrap()).setAccessibilityWindowInfo(window)
        shadow().setRefreshReturnValue(true)
    }
    private fun session(): ContextMenuSession {
        val root = AccessibilityNodeInfo.obtain(); val rootShadow: SliderNodeShadow = Shadow.extract(root)
        rootShadow.focused = target.unwrap()
        val window = AccessibilityWindowInfo.obtain(); shadowOf(window).setId(1)
        shadowOf(root).setAccessibilityWindowInfo(window)
        val service = SliderTestService().apply { this.root = root }
        val snapshot = ActionController(ServiceController(service), context).discover()!!
        return ContextMenuSession(target.unwrap(), snapshot, 1, { windowValid }, context)
    }
    private fun commands(captured: ContextMenuSession) = captured.commands.filter { it.operation in labelOperations }
    @Test fun `valid ID offers Add label alongside unchanged Click`() {
        assertEquals(listOf("Click", "Add label"), session().commands.map { it.label })
    }
    @Test fun `stored label offers Edit and Remove instead of Add`() {
        store.put(key, "Share")
        assertEquals(listOf("Edit label", "Remove label"), commands(session()).map { it.label })
    }
    @Test fun `missing ID or package offers no labeling`() {
        target.viewIdResourceName = null; assertTrue(commands(session()).isEmpty())
        target.viewIdResourceName = key.resourceName; target.packageName = null
        assertTrue(commands(session()).isEmpty())
    }
    @Test fun `password and sensitive nodes offer no labeling`() {
        target.isPassword = true; assertTrue(commands(session()).isEmpty())
        target.isPassword = false; target.isAccessibilityDataSensitive = true
        assertTrue(commands(session()).isEmpty())
    }
    @Test fun `app labeled comparison can store fallback without overriding speech`() {
        target.text = "App label"; val captured = session()
        assertEquals(MenuResult.LABEL_SAVED, captured.saveLabel(commands(captured).single(), "Share"))
        assertEquals("App label, button", Reader(context).read(target))
    }
    @Test fun `save labels original target despite editor accessibility focus`() {
        val captured = session(); target.isAccessibilityFocused = false
        assertEquals(MenuResult.LABEL_SAVED, captured.saveLabel(commands(captured).single(), "  Share "))
        assertEquals("Share", store.get(key))
        assertTrue(shadow().performedActions.isEmpty())
    }
    private fun rejected(result: MenuResult = MenuResult.ACTION_GONE, change: () -> Unit) {
        val captured = session(); val command = commands(captured).single(); change()
        assertEquals(result, captured.saveLabel(command, "Share")); assertNull(store.get(key))
    }
    @Test fun `changed resource ID rejects save`() = rejected { target.viewIdResourceName = "example.app:id/other" }
    @Test fun `changed package rejects save`() = rejected { target.packageName = "other.app" }
    @Test fun `password acquired after capture rejects save`() = rejected { target.isPassword = true }
    @Test fun `sensitive acquired after capture rejects save`() = rejected { target.isAccessibilityDataSensitive = true }
    @Test fun `removed target rejects save`() = rejected(MenuResult.TARGET_GONE) { shadow().setRefreshReturnValue(false) }
    @Test fun `window change rejects save`() = rejected(MenuResult.TARGET_GONE) { windowValid = false }
    @Test fun `refresh exposes changed identity before save`() {
        val captured = session(); val command = commands(captured).single()
        shadow().onRefresh = { target.viewIdResourceName = "example.app:id/replaced"; true }
        assertEquals(MenuResult.ACTION_GONE, captured.saveLabel(command, "Share")); assertNull(store.get(key))
    }
    @Test fun `Cancel stores nothing and makes later Save unusable`() {
        val captured = session(); val command = commands(captured).single(); captured.cancel()
        assertEquals(MenuResult.TARGET_GONE, captured.saveLabel(command, "Share")); assertNull(store.get(key))
    }
    @Test fun `replacement invalidation prevents save`() {
        val captured = session(); val command = commands(captured).single(); captured.invalidate()
        assertEquals(MenuResult.TARGET_GONE, captured.saveLabel(command, "Share")); assertNull(store.get(key))
    }
    @Test fun `editing changes exactly one key and duplicate Save is rejected`() {
        val other = key.copy(resourceName = "example.app:id/share_other")
        store.put(key, "Share"); store.put(other, "Other")
        val captured = session(); val edit = commands(captured).first { it.operation == MenuOperation.EDIT_LABEL }
        assertEquals("Share", captured.customLabel)
        assertEquals(MenuResult.LABEL_SAVED, captured.saveLabel(edit, "Send"))
        assertEquals(MenuResult.TARGET_GONE, captured.saveLabel(edit, "Wrong"))
        assertEquals("Send", store.get(key)); assertEquals("Other", store.get(other))
    }
    @Test fun `removal deletes exactly one mapping`() {
        val other = key.copy(resourceName = "example.app:id/share_other")
        store.put(key, "Share"); store.put(other, "Other")
        val captured = session(); val remove = commands(captured).first { it.operation == MenuOperation.REMOVE_LABEL }
        assertEquals(MenuResult.LABEL_REMOVED, captured.execute(remove))
        assertNull(store.get(key)); assertEquals("Other", store.get(other))
        assertEquals("button", Reader(context).read(target))
        assertTrue(shadow().performedActions.isEmpty())
    }
    @Test fun `changed ID rejects removal without touching old mapping`() {
        store.put(key, "Share"); val captured = session()
        val remove = commands(captured).first { it.operation == MenuOperation.REMOVE_LABEL }
        target.viewIdResourceName = "example.app:id/other"
        assertEquals(MenuResult.ACTION_GONE, captured.execute(remove)); assertEquals("Share", store.get(key))
    }
    @Test fun `changed package rejects removal`() {
        store.put(key, "Share"); val captured = session()
        val remove = commands(captured).first { it.operation == MenuOperation.REMOVE_LABEL }
        target.packageName = "other.app"
        assertEquals(MenuResult.ACTION_GONE, captured.execute(remove)); assertEquals("Share", store.get(key))
    }
    @Test fun `empty Save does not write`() {
        val captured = session()
        assertEquals(MenuResult.FAILED, captured.saveLabel(commands(captured).single(), " \n"))
        assertNull(store.get(key))
    }
    @Test fun `Copy and custom action commands remain alongside labels`() {
        target.className = "android.widget.TextView"; target.text = "Sample"; target.isClickable = false
        target.removeAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK)
        assertEquals(listOf("Copy text", "Add label"), session().commands.map { it.label })
        target.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(0x7f010001, "Retry"))
        assertEquals(listOf("Retry", "Add label"), session().commands.map { it.label })
    }
    private fun obscuredEditor(): Pair<ContextMenuSession, MenuCommand> {
        val captured = session()
        val command = commands(captured).first { it.operation in setOf(MenuOperation.ADD_LABEL, MenuOperation.EDIT_LABEL) }
        assertTrue(captured.openLabelEditor(command))
        target.isVisibleToUser = false
        return captured to command
    }
    @Test fun `opened label editor permits hidden target Save but strict validation remains false`() {
        target.text = "App provided label"
        val (captured, command) = obscuredEditor()
        assertFalse(captured.valid()); assertTrue(captured.validForCurrentView())
        assertEquals(MenuResult.LABEL_SAVED, captured.saveLabel(command, "Fallback"))
        assertEquals("Fallback", store.get(key))
        target.isVisibleToUser = true
        assertEquals("App provided label, button", Reader(context).read(target))
        assertTrue(shadow().performedActions.isEmpty())
    }
    @Test fun `Save without validated editor entry still requires visibility`() {
        val captured = session(); val command = commands(captured).single()
        target.isVisibleToUser = false
        assertFalse(captured.openLabelEditor(command)); assertFalse(captured.validForCurrentView())
        assertEquals(MenuResult.TARGET_GONE, captured.saveLabel(command, "Wrong"))
        assertNull(store.get(key))
    }
    @Test fun `ordinary Click retains visibility requirement even during editor session`() {
        val (captured, _) = obscuredEditor()
        assertEquals(MenuResult.TARGET_GONE, captured.execute(captured.commands.first { it.operation == MenuOperation.CLICK }))
        assertTrue(shadow().performedActions.isEmpty())
    }
    @Test fun `Remove label retains visibility requirement even during editor session`() {
        store.put(key, "Share"); val (captured, _) = obscuredEditor()
        assertEquals(MenuResult.TARGET_GONE, captured.execute(commands(captured).first { it.operation == MenuOperation.REMOVE_LABEL }))
        assertEquals("Share", store.get(key))
    }
    @Test fun `obscured editor Cancel never focuses hidden original target`() {
        val (captured, _) = obscuredEditor(); target.isAccessibilityFocused = false
        captured.cancel(); assertTrue(shadow().performedActions.isEmpty())
    }
    private fun rejectObscuredSave(result: MenuResult, change: () -> Unit) {
        val (captured, command) = obscuredEditor(); change()
        assertFalse(captured.validForCurrentView())
        assertEquals(result, captured.saveLabel(command, "Wrong"))
        assertNull(store.get(key)); assertTrue(shadow().performedActions.isEmpty())
    }
    @Test fun `removed obscured editor target cannot Save`() = rejectObscuredSave(MenuResult.TARGET_GONE) {
        shadow().setRefreshReturnValue(false)
    }
    @Test fun `replaced obscured editor target cannot Save`() = rejectObscuredSave(MenuResult.ACTION_GONE) {
        target.viewIdResourceName = "example.app:id/replaced"
    }
    @Test fun `changed package on obscured editor target cannot Save`() = rejectObscuredSave(MenuResult.ACTION_GONE) {
        target.packageName = "other.app"
    }
    @Test fun `changed owner window on obscured editor target cannot Save`() = rejectObscuredSave(MenuResult.TARGET_GONE) {
        windowValid = false
    }
    @Test fun `changed target window on obscured editor target cannot Save`() = rejectObscuredSave(MenuResult.TARGET_GONE) {
        val other = AccessibilityWindowInfo.obtain(); shadowOf(other).setId(2)
        shadowOf(target.unwrap()).setAccessibilityWindowInfo(other)
    }
    @Test fun `disabled obscured editor target cannot Save`() = rejectObscuredSave(MenuResult.ACTION_GONE) {
        target.isEnabled = false
    }
    @Test fun `password obscured editor target cannot Save`() = rejectObscuredSave(MenuResult.ACTION_GONE) {
        target.isPassword = true
    }
    @Test fun `sensitive obscured editor target cannot Save`() = rejectObscuredSave(MenuResult.ACTION_GONE) {
        target.isAccessibilityDataSensitive = true
    }
}
