package com.bajiao.notificationisland;

import android.content.Context;

/** Presentation preferences are owned by the monitor process. */
final class IslandPresentation {
    static final String PUSH = "island_push_enabled";
    static final String MANUAL_STOP = "show_manual_stop";
    static final String MARQUEE = "marquee_enabled";

    static boolean enabled(Context context, String key) {
        return context.getSharedPreferences(IslandNotificationListener.PREFS, 0).getBoolean(key, true);
    }

    static CharSequence text(Context context, String value) {
        return enabled(context, MARQUEE) ? IslandMarquee.mark(value) : value;
    }
}
