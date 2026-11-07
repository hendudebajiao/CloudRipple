package com.bajiao.notificationisland;

/** Extras shared with the SystemUI hook and the compact notification text limit. */
final class IslandMarquee {
    static final String KEY = "com.bajiao.notificationisland.marquee.v1";
    static final String EXTRA_FULL_TEXT = KEY + ".full_text";
    static final String EXTRA_LEFT_LABEL = KEY + ".left_label";

    static String shortText(String text) {
        // Keep supplementary characters intact when limiting the compact text.
        return text.substring(0, text.offsetByCodePoints(0, Math.min(7, text.codePointCount(0, text.length()))));
    }

}
