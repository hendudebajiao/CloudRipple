package com.bajiao.notificationisland;

import android.text.Annotation;
import android.text.SpannableString;
import android.text.Spanned;

/** Framework Annotation survives Notification parceling without custom Parcelable classes. */
final class IslandMarquee {
    static final String KEY = "com.bajiao.notificationisland.marquee.v1";
    static final String EXTRA_FULL_TEXT = KEY + ".full_text";
    static final String EXTRA_LEFT_LABEL = KEY + ".left_label";

    static CharSequence mark(String text) { return annotate(text, text, KEY); }

    static String shortText(String text) {
        // Android accepts only String here. Expanded content carries a parcelable marker.
        return text.substring(0, text.offsetByCodePoints(0, Math.min(7, text.codePointCount(0, text.length()))));
    }

    private static CharSequence annotate(String visible, String full, String key) {
        if (full.codePointCount(0, full.length()) <= 4) return visible;
        SpannableString value = new SpannableString(visible);
        value.setSpan(new Annotation(key, full), 0, value.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return value;
    }
}
