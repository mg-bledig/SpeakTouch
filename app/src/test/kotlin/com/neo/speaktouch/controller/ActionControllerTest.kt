package com.neo.speaktouch.controller

import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
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
class ActionControllerTest {
    private lateinit var service: SliderTestService
    private lateinit var root: AccessibilityNodeInfoCompat
    private lateinit var target: AccessibilityNodeInfoCompat
    private lateinit var controller: ActionController
    private val retry = 0x7f010001
    private val archive = 0x7f010002
    private fun shadow(node: AccessibilityNodeInfoCompat): SliderNodeShadow = Shadow.extract(node.unwrap())
    private fun node(windowId: Int = 1) = AccessibilityNodeInfoCompat.wrap(AccessibilityNodeInfo.obtain()).apply {
        isVisibleToUser = true
        isEnabled = true
        isAccessibilityFocused = true
        val window = AccessibilityWindowInfo.obtain()
        shadowOf(window).setId(windowId)
        shadowOf(unwrap()).setAccessibilityWindowInfo(window)
        shadow(this).setRefreshReturnValue(true)
    }
    private fun add(id: Int, label: CharSequence? = null) = target.unwrap().addAction(
        AccessibilityNodeInfo.AccessibilityAction(id, label))
    private fun snapshot() = controller.discover()!!
    private fun execute(snapshot: ActionSnapshot = snapshot(), id: Int = retry) = controller.execute(snapshot, id)
    @Before fun setup() {
        service = SliderTestService()
        root = node()
        target = node()
        shadow(root).focused = target.unwrap()
        service.root = root.unwrap()
        controller = ActionController(ServiceController(service), RuntimeEnvironment.getApplication())
        add(retry, "Retry")
    }
    @Test fun `raw order IDs labels and duplicate labels are preserved`() {
        add(archive, "Retry")
        add(AccessibilityNodeInfo.ACTION_CLICK, "Activate")
        val actions = snapshot().actions
        assertEquals(listOf(retry, archive, AccessibilityNodeInfo.ACTION_CLICK), actions.map { it.id })
        assertEquals(listOf("Retry", "Retry", "Activate"), actions.map { it.providerLabel })
        assertEquals(2, snapshot().supportedActions.size)
    }
    @Test fun `label is copied and list cannot be mutated`() {
        val label = StringBuilder("Archive")
        add(archive, label)
        val saved = snapshot()
        label.append(" changed")
        assertEquals("Archive", saved.actions.last().providerLabel)
        try { (saved.actions as MutableList<NodeAction>).clear(); fail("Mutable snapshot") }
        catch (_: UnsupportedOperationException) { }
    }
    @Test fun `blank custom labels remain raw but cannot execute`() {
        for (label in listOf(null, "", "  ")) {
            target.unwrap().removeAction(AccessibilityNodeInfo.AccessibilityAction(retry, null))
            add(retry, label)
            assertEquals(ActionKind.CUSTOM, snapshot().actions.single().kind)
            assertTrue(snapshot().supportedActions.isEmpty())
            assertFalse(execute())
        }
    }
    @Test fun `standard fallbacks and overridden labels`() {
        add(AccessibilityNodeInfo.ACTION_DISMISS)
        add(AccessibilityNodeInfo.ACTION_EXPAND, "Open details")
        add(AccessibilityNodeInfo.ACTION_COLLAPSE, " ")
        assertEquals(listOf("Retry", "Dismiss", "Open details", "Collapse"), snapshot().actions.map { it.displayLabel })
        assertEquals("Open details", snapshot().actions[2].providerLabel)
    }
    @Test fun `activation scroll editing progress drag and unknown standard excluded`() {
        val excluded = listOf(16, 32, 4096, 8192, 2097152, 131072, 16384, 32768, 65536,
            4, 8, 16908349, 16908373, 16908374, 16908375, 16909000)
        for (id in excluded) add(id, "Provider label")
        val saved = snapshot()
        for (id in excluded) {
            assertEquals(ActionKind.STANDARD, saved.actions.first { it.id == id }.kind)
            assertFalse(execute(saved, id))
        }
        assertTrue(shadow(target).performedActions.isEmpty())
    }
    @Test fun `classification boundary is standard below custom resource range`() {
        add(0x01ffffff, "Unknown")
        add(0x02000000, "Custom")
        assertEquals(ActionKind.STANDARD, snapshot().actions[1].kind)
        assertEquals(ActionKind.CUSTOM, snapshot().actions[2].kind)
    }
    @Test fun `successful selection calls provider once with no arguments`() {
        shadow(target).setOnPerformActionListener { action, args ->
            assertEquals(retry, action); assertNull(args); true
        }
        assertTrue(controller.execute(snapshot(), snapshot().actions.single()))
        assertEquals(listOf(retry), shadow(target).performedActions)
        assertTrue(shadow(root).performedActions.isEmpty())
        assertTrue(target.isAccessibilityFocused)
        assertEquals(target.unwrap(), shadow(root).focused)
    }
    @Test fun `provider rejection is returned without retry`() {
        shadow(target).setOnPerformActionListener { _, _ -> false }
        assertFalse(execute())
        assertEquals(listOf(retry), shadow(target).performedActions)
    }
    @Test fun `supported standard actions execute`() {
        for (id in listOf(AccessibilityNodeInfo.ACTION_DISMISS, AccessibilityNodeInfo.ACTION_EXPAND, AccessibilityNodeInfo.ACTION_COLLAPSE)) {
            add(id)
            shadow(target).setOnPerformActionListener { _, args -> assertNull(args); true }
            assertTrue(execute(id = id))
        }
        assertEquals(3, shadow(target).performedActions.size)
    }
    @Test fun `removed action fails`() {
        val saved = snapshot()
        target.unwrap().removeAction(AccessibilityNodeInfo.AccessibilityAction(retry, null))
        assertFalse(execute(saved))
    }
    @Test fun `custom label becoming blank fails`() {
        val saved = snapshot()
        target.unwrap().removeAction(AccessibilityNodeInfo.AccessibilityAction(retry, null))
        add(retry, "")
        assertFalse(execute(saved))
    }
    @Test fun `failed refresh fails discovery and execution`() {
        val saved = snapshot()
        shadow(target).setRefreshReturnValue(false)
        assertNull(controller.discover())
        assertFalse(execute(saved))
    }
    @Test fun `changed focus rejects old snapshot`() {
        val saved = snapshot()
        shadow(root).focused = node().unwrap()
        assertFalse(execute(saved))
    }
    @Test fun `focus changes during refresh rejects execution`() {
        val saved = snapshot()
        shadow(target).onRefresh = { shadow(root).focused = node().unwrap(); true }
        assertFalse(execute(saved))
    }
    @Test fun `active window changes during refresh rejects execution`() {
        val saved = snapshot()
        val replacement = node(2)
        shadow(replacement).focused = target.unwrap()
        shadow(target).onRefresh = { service.root = replacement.unwrap(); true }
        assertFalse(execute(saved))
    }
    @Test fun `focus flag lost rejects execution`() {
        val saved = snapshot()
        target.isAccessibilityFocused = false
        assertFalse(execute(saved))
    }
    @Test fun `invisible or disabled rejects execution`() {
        val saved = snapshot()
        target.isVisibleToUser = false
        assertFalse(execute(saved))
        target.isVisibleToUser = true
        target.isEnabled = false
        assertFalse(execute(saved))
    }
    @Test fun `no focus or root never falls back`() {
        shadow(root).focused = null
        assertNull(controller.discover())
        service.root = null
        assertNull(controller.discover())
        assertTrue(shadow(root).performedActions.isEmpty())
    }
    @Test fun `unadvertised and forged descriptors cannot execute`() {
        val saved = snapshot()
        assertFalse(execute(saved, archive))
        assertFalse(controller.execute(saved, saved.actions.single().copy(id = archive)))
        assertTrue(shadow(target).performedActions.isEmpty())
    }
    @Test fun `ancestor actions are not discovered`() {
        root.unwrap().addAction(AccessibilityNodeInfo.AccessibilityAction(archive, "Archive"))
        assertEquals(listOf(retry), snapshot().actions.map { it.id })
        assertFalse(execute(id = archive))
        assertTrue(shadow(root).performedActions.isEmpty())
    }
}
