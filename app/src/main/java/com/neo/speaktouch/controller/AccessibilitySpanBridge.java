package com.neo.speaktouch.controller;

import android.text.style.ClickableSpan;

/** Native accessibility proxies ignore the View; the SDK's base span annotation requires one. */
final class AccessibilitySpanBridge {
    private AccessibilitySpanBridge() {}

    static void click(ClickableSpan span) {
        span.onClick(null);
    }
}
