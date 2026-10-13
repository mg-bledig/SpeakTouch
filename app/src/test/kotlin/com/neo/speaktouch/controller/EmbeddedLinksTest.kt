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

package com.neo.speaktouch.controller

import android.text.SpannableString
import android.text.Spanned
import android.text.style.ClickableSpan
import android.text.style.StyleSpan
import android.text.style.URLSpan
import android.view.View
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

/** Tests the provider wire format/dispatch, not a same-process app span callback. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [22, 25, 31], shadows = [SliderNodeShadow::class])
class EmbeddedLinksTest {
    private lateinit var node: AccessibilityNodeInfoCompat
    private val prefix = "androidx.view.accessibility.AccessibilityNodeInfoCompat."
    private val spanAction = 0x7f010200
    private var windowValid = true
    private fun shadow(): SliderNodeShadow = Shadow.extract(node.unwrap())
    @Before fun setup() {
        node = AccessibilityNodeInfoCompat.wrap(AccessibilityNodeInfo.obtain()).apply {
            className = "android.widget.TextView"; text = "Read Help and License"
            isEnabled = true; isVisibleToUser = true; isAccessibilityFocused = true
        }
        val window = AccessibilityWindowInfo.obtain(); shadowOf(window).setId(1)
        shadowOf(node.unwrap()).setAccessibilityWindowInfo(window)
        shadow().setRefreshReturnValue(true)
        shadow().setOnPerformActionListener { _, _ -> true }
    }
    private fun spans(starts: List<Int> = listOf(5, 14), ends: List<Int> = listOf(9, 21),
        ids: List<Int> = listOf(7, 8)) {
        node.extras.putIntegerArrayList(prefix + "SPANS_START_KEY", ArrayList(starts))
        node.extras.putIntegerArrayList(prefix + "SPANS_END_KEY", ArrayList(ends))
        node.extras.putIntegerArrayList(prefix + "SPANS_FLAGS_KEY", ArrayList(starts.map { Spanned.SPAN_EXCLUSIVE_EXCLUSIVE }))
        node.extras.putIntegerArrayList(prefix + "SPANS_ID_KEY", ArrayList(ids))
        node.extras.putInt(prefix + "SPANS_ACTION_ID_KEY", spanAction)
        node.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(spanAction, null))
    }
    private fun links() = EmbeddedLinks.discover(node, 42)
    private fun session(): ContextMenuSession {
        val root = AccessibilityNodeInfo.obtain()
        val rootShadow: SliderNodeShadow = Shadow.extract(root); rootShadow.focused = node.unwrap()
        val window = AccessibilityWindowInfo.obtain(); shadowOf(window).setId(1)
        shadowOf(root).setAccessibilityWindowInfo(window)
        val service = SliderTestService().apply { this.root = root }
        val snapshot = ActionController(ServiceController(service), RuntimeEnvironment.getApplication()).discover()!!
        return ContextMenuSession(node.unwrap(), snapshot, 42, { windowValid }, RuntimeEnvironment.getApplication())
    }
    @Test fun `multiple compat links have exact ranges and plain string labels`() {
        spans()
        assertEquals(listOf("Help", "License"), links().map { it.label })
        assertEquals(listOf(5 to 9, 14 to 21), links().map { it.start to it.end })
        assertEquals(listOf(42L, 42L), links().map { it.sessionToken })
        assertTrue(session().commands.any { it.operation == MenuOperation.LINKS })
    }
    @Test fun `provider order is preserved rather than sorted by range`() {
        spans(listOf(14, 5), listOf(21, 9), listOf(8, 7))
        assertEquals(listOf("License", "Help"), links().map { it.label })
    }
    @Test fun `compat activation supplies only selected span ID once`() {
        spans(); val selected = links()[1]
        shadow().setOnPerformActionListener { action, args ->
            assertEquals(spanAction, action)
            assertEquals(setOf("ACCESSIBILITY_CLICKABLE_SPAN_ID"), args!!.keySet())
            assertEquals(8, args.getInt("ACCESSIBILITY_CLICKABLE_SPAN_ID")); true
        }
        assertTrue(EmbeddedLinks.activate(node, selected))
        assertEquals(listOf(spanAction), shadow().performedActions)
    }
    @Test fun `compat activation rejects forged or stale descriptor without fallback`() {
        spans(); val saved = links().first()
        assertFalse(EmbeddedLinks.activate(node, saved.copy(spanId = 8)))
        spans(ids = listOf(90, 8))
        assertFalse(EmbeddedLinks.activate(node, saved))
        assertTrue(shadow().performedActions.isEmpty())
    }
    @Test fun `compat activation rejects changed source or range`() {
        spans(); val saved = links().first()
        node.text = "Else Help and License"
        assertFalse(EmbeddedLinks.activate(node, saved))
        node.text = "Read Help and License"; spans(listOf(4, 14), listOf(9, 21))
        assertFalse(EmbeddedLinks.activate(node, saved))
        assertTrue(shadow().performedActions.isEmpty())
    }
    @Test fun `compat action failure does not trigger another link or Click`() {
        spans(); shadow().setOnPerformActionListener { _, _ -> false }
        assertFalse(EmbeddedLinks.activate(node, links().first()))
        assertEquals(listOf(spanAction), shadow().performedActions)
    }
    @Test fun `removed compat action prevents dispatch`() {
        spans(); val saved = links().first()
        node.removeAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(spanAction, null))
        assertFalse(EmbeddedLinks.activate(node, saved))
        assertTrue(shadow().performedActions.isEmpty())
    }
    @Test fun `duplicate visible labels keep distinct identities`() {
        node.text = "Help Help"; spans(listOf(0, 5), listOf(4, 9))
        assertEquals(listOf("Help", "Help"), links().map { it.label })
        assertNotEquals(links()[0], links()[1])
    }
    @Test fun `plain text and styled text have no links`() {
        assertTrue(links().isEmpty())
        node.text = SpannableString("Styled").apply { setSpan(StyleSpan(1), 0, length, 33) }
        assertTrue(links().isEmpty())
    }
    @Test fun `raw app spans without a service bridge are not fabricated support`() {
        node.text = SpannableString("Help").apply {
            setSpan(object : ClickableSpan() { override fun onClick(widget: View) = fail("Raw callback") }, 0, 4, 33)
        }
        assertTrue(links().isEmpty())
    }
    @Test fun `blank and invalid ranges are omitted`() {
        node.text = "    Help"; spans(listOf(0, -1, 4, 4), listOf(4, 3, 9, 8), listOf(1, 2, 3, 4))
        assertEquals(listOf("Help"), links().map { it.label })
    }
    @Test fun `malformed metadata lengths or missing IDs are ignored`() {
        spans(ids = listOf(7)); assertTrue(links().isEmpty())
        spans(); node.extras.remove(prefix + "SPANS_ID_KEY"); assertTrue(links().isEmpty())
    }
    @Test fun `malformed span metadata does not crash menu construction`() {
        spans(ids = listOf(7))
        assertTrue(session().commands.none { it.operation == MenuOperation.LINKS })
    }
    @Test fun `missing advertised compat action is ignored`() {
        spans(); node.removeAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(spanAction, null))
        assertTrue(links().isEmpty())
    }
    @Test fun `password has no links`() { spans(); node.isPassword = true; assertTrue(links().isEmpty()) }
    @Test fun `compat sensitive content has no links`() {
        spans(); node.isAccessibilityDataSensitive = true; assertTrue(links().isEmpty())
    }
    @Test @Config(sdk = [34]) fun `sensitive content has no links`() {
        spans(); node.unwrap().isAccessibilityDataSensitive = true; assertTrue(links().isEmpty())
    }
    @Test fun `only selected link is dispatched once on original node despite menu focus`() {
        spans(); val captured = session(); val selected = captured.links[1]
        node.isAccessibilityFocused = false
        shadow().setOnPerformActionListener { action, args ->
            assertEquals(spanAction, action)
            assertEquals(8, args!!.getInt("ACCESSIBILITY_CLICKABLE_SPAN_ID")); true
        }
        assertEquals(MenuResult.LINK_DISPATCHED, captured.executeLink(selected))
        assertEquals(MenuResult.TARGET_GONE, captured.executeLink(selected))
        assertEquals(listOf(spanAction), shadow().performedActions)
    }
    private fun rejects(change: () -> Unit) {
        spans(); val captured = session(); val selected = captured.links.first(); change()
        assertEquals(MenuResult.ACTION_GONE, captured.executeLink(selected))
        assertTrue(shadow().performedActions.isEmpty())
    }
    @Test fun `changed source text rejected even with same label and ranges`() = rejects { node.text = "Else Help and License" }
    @Test fun `changed range rejected`() = rejects { spans(listOf(4, 14), listOf(9, 21)) }
    @Test fun `replacement span identity rejected`() = rejects { spans(ids = listOf(90, 8)) }
    @Test fun `removed span identity rejected`() = rejects { spans(listOf(14), listOf(21), listOf(8)) }
    @Test fun `removed span action rejected`() = rejects {
        node.removeAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(spanAction, null))
    }
    @Test fun `became password rejected`() = rejects { node.isPassword = true }
    @Test fun `disabled target rejected`() = rejects { node.isEnabled = false }
    @Test fun `descriptor from another session rejected`() {
        spans(); val captured = session()
        assertEquals(MenuResult.ACTION_GONE, captured.executeLink(captured.links.first().copy(sessionToken = 99)))
        assertTrue(shadow().performedActions.isEmpty())
    }
    @Test fun `forged link descriptor does not select another link`() {
        spans(); val captured = session()
        assertEquals(MenuResult.ACTION_GONE, captured.executeLink(captured.links.first().copy(spanId = 8)))
        assertTrue(shadow().performedActions.isEmpty())
    }
    @Test fun `removed original target rejected`() {
        spans(); val captured = session(); shadow().setRefreshReturnValue(false)
        assertEquals(MenuResult.TARGET_GONE, captured.executeLink(captured.links.first()))
        assertTrue(shadow().performedActions.isEmpty())
    }
    @Test fun `window change rejected`() {
        spans(); val captured = session(); windowValid = false
        assertEquals(MenuResult.TARGET_GONE, captured.executeLink(captured.links.first()))
        assertTrue(shadow().performedActions.isEmpty())
    }
    @Test fun `cancel restores original and makes links unusable`() {
        spans(); val captured = session(); node.isAccessibilityFocused = false; captured.cancel()
        assertEquals(listOf(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS), shadow().performedActions)
        assertEquals(MenuResult.TARGET_GONE, captured.executeLink(captured.links.first()))
    }
    @Test fun `invalidated replacement session cannot dispatch`() {
        spans(); val captured = session(); captured.invalidate()
        assertEquals(MenuResult.TARGET_GONE, captured.executeLink(captured.links.first()))
        assertTrue(shadow().performedActions.isEmpty())
    }
    @Test fun `provider rejection is failure without fallback`() {
        spans(); val captured = session(); shadow().setOnPerformActionListener { _, _ -> false }
        assertEquals(MenuResult.FAILED, captured.executeLink(captured.links.first()))
        assertEquals(listOf(spanAction), shadow().performedActions)
    }
    @Test fun `standalone clickable control has Click without Links`() {
        node.isClickable = true; node.addAction(16)
        assertEquals(listOf(MenuOperation.CLICK), session().commands.map { it.operation })
    }
    @Test fun `descendants and content description are not scanned`() {
        node.text = null
        node.contentDescription = SpannableString("Help").apply { setSpan(URLSpan("https://example.com"), 0, 4, 33) }
        val child = AccessibilityNodeInfo.obtain(); child.text = node.contentDescription
        shadow().addChild(child)
        assertTrue(links().isEmpty())
    }
}
