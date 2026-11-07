package com.bajiao.notificationisland;

import android.content.Context;

/** Presentation preferences are owned by the monitor process. */
final class IslandPresentation {
    static final String PUSH = "island_push_enabled";
    static final String MANUAL_STOP = "show_manual_stop";
    static final String MARQUEE = "marquee_enabled";

    static boolean enabled(Context context, String key) {
        if (PUSH.equals(key) && RootKeepaliveManager.isUninstalling(context)) return false;
        return context.getSharedPreferences(IslandNotificationListener.PREFS, 0).getBoolean(key, true);
    }
}
