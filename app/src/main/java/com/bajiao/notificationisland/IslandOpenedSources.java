package com.bajiao.notificationisland;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;

/** Also dismisses relays when the user opens the source app directly. */
final class IslandOpenedSources {
    private static String foreground = "";
    private static long checkedAt;

    static boolean allowed(Context context) {
        return context.getSystemService(AppOpsManager.class).unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.getPackageName())
                == AppOpsManager.MODE_ALLOWED;
    }

    static void suppressOpened(Context context) {
        if (IslandState.current() == null || !allowed(context)) return;
        long now = System.currentTimeMillis();
        UsageEvents events = context.getSystemService(UsageStatsManager.class)
                .queryEvents(checkedAt == 0 ? now - 10 * 60 * 1000L : checkedAt, now);
        UsageEvents.Event event = new UsageEvents.Event();
        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            if (event.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) foreground = event.getPackageName();
            else if (event.getEventType() == UsageEvents.Event.ACTIVITY_PAUSED
                    && foreground.equals(event.getPackageName())) foreground = "";
        }
        checkedAt = now;
        for (IslandState.Item item : IslandState.snapshot()) {
            if (foreground.equals(item.sourcePackage) && !IslandState.isSuppressed(item.sourceKey)) {
                IslandState.suppress(item.sourceKey);
            }
        }
    }
}
