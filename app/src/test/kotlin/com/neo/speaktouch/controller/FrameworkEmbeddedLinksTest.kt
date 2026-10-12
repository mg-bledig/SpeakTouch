package com.neo.speaktouch.controller

import android.text.SpannableString
import android.text.Spanned
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadows.ShadowAccessibilityNodeInfo
import org.robolectric.util.reflector.Direct
import org.robolectric.util.reflector.ForType
import org.robolectric.util.reflector.Reflector.reflector

/** Use Android's actual setText/getText span conversion, which the default shadow bypasses. */
@Implements(AccessibilityNodeInfo::class)
class FrameworkSpanNodeShadow : ShadowAccessibilityNodeInfo() {
    @RealObject private lateinit var node: AccessibilityNodeInfo
    @Implementation override fun setText(text: CharSequence?) {
        reflector(FrameworkNodeText::class.java, node).setText(text)
    }
    @Implementation override fun getText(): CharSequence? =
        reflector(FrameworkNodeText::class.java, node).getText()
}

@ForType(AccessibilityNodeInfo::class)
interface FrameworkNodeText {
    @Direct fun setText(text: CharSequence?)
    @Direct fun getText(): CharSequence?
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 31], manifest = Config.NONE, shadows = [FrameworkSpanNodeShadow::class])
class FrameworkEmbeddedLinksTest {
    private lateinit var node: AccessibilityNodeInfoCompat
    private fun links() = EmbeddedLinks.discover(node, 3)
    private fun text(span: ClickableSpan, start: Int = 5, end: Int = 9, value: String = "Read Help") {
        node.text = SpannableString(value).apply { setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
    }
    @Before fun setup() {
        node = AccessibilityNodeInfoCompat.wrap(AccessibilityNodeInfo.obtain()).apply {
            isEnabled = true; isVisibleToUser = true
        }
    }
    @Test fun `native URLSpan becomes supported service proxy with text label`() {
        text(URLSpan("https://example.com/help"))
        val link = links().single()
        assertEquals("Help", link.label)
        assertEquals(5, link.start); assertEquals(9, link.end)
        assertNull(link.actionId)
    }
    @Test fun `native ClickableSpan becomes supported service proxy`() {
        text(object : ClickableSpan() { override fun onClick(widget: View) = fail("App callback") })
        assertEquals("Help", links().single().label)
    }
    @Test fun `new proxy for same original span keeps stable identity`() {
        val span = URLSpan("https://example.com/help"); text(span)
        val saved = links().single(); text(span)
        assertEquals(saved, links().single())
    }
    @Test fun `same label URL and range with replacement original span changes identity`() {
        text(URLSpan("https://example.com/help")); val saved = links().single()
        text(URLSpan("https://example.com/help"))
        assertNotEquals(saved.identity, links().single().identity)
        assertFalse(EmbeddedLinks.activate(node, saved))
    }
    @Test fun `changed URL changes identity`() {
        text(URLSpan("https://example.com/help")); val saved = links().single()
        text(URLSpan("https://example.com/license"))
        assertNotEquals(saved.identity, links().single().identity)
    }
    @Test fun `native duplicate labels have distinct identities`() {
        node.text = SpannableString("Help Help").apply {
            setSpan(URLSpan("https://example.com/help"), 0, 4, 33)
            setSpan(URLSpan("https://example.com/help"), 5, 9, 33)
        }
        assertEquals(listOf("Help", "Help"), links().map { it.label })
        assertNotEquals(links()[0].identity, links()[1].identity)
    }
    @Test fun `native provider span order preserved`() {
        node.text = SpannableString("Help License").apply {
            setSpan(URLSpan("https://example.com/license"), 5, 12, 33)
            setSpan(URLSpan("https://example.com/help"), 0, 4, 33)
        }
        assertEquals(listOf("License", "Help"), links().map { it.label })
    }
    @Test fun `unconnected native bridge fails without directly calling app callback`() {
        var calls = 0
        text(object : ClickableSpan() { override fun onClick(widget: View) { calls++ } })
        assertFalse(EmbeddedLinks.activate(node, links().single()))
        assertEquals(0, calls)
        // A real AccessibilityService connection is required for cross-process verification.
    }
}
