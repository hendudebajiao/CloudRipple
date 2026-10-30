package com.bajiao.notificationisland;

import android.content.Context;
import android.content.SharedPreferences;

/** Persists first-display deadlines so updates and process restarts cannot reset them. */
final class IslandLifetime {
    static final String TEST_KEY = "__island_test__";
    static final String KEY_SECONDS = "island_duration_seconds";
    static final int DEFAULT_SECONDS = 15;
    private static final String PREFIX = "island_deadline:";

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(IslandNotificationListener.PREFS, Context.MODE_PRIVATE);
    }

    static int seconds(Context context) {
        return prefs(context).getInt(KEY_SECONDS, DEFAULT_SECONDS);
    }

    static synchronized long deadline(Context context, String key, long now) {
        SharedPreferences p = prefs(context);
        if (p.contains(PREFIX + key)) return p.getLong(PREFIX + key, 0);
        long deadline = seconds(context) == 0 ? 0 : now + seconds(context) * 1000L;
        p.edit().putLong(PREFIX + key, deadline).commit();
        return deadline;
    }

    static synchronized void forget(Context context, String key) {
        prefs(context).edit().remove(PREFIX + key).apply();
    }

    static synchronized void prune(Context context, java.util.Set<String> activeKeys) {
        SharedPreferences p = prefs(context);
        SharedPreferences.Editor edit = p.edit();
        for (String name : p.getAll().keySet()) {
            if (name.startsWith(PREFIX)) {
                String key = name.substring(PREFIX.length());
                if (!TEST_KEY.equals(key) && !activeKeys.contains(key)) edit.remove(name);
            }
        }
        edit.apply();
    }

    static synchronized void setSeconds(Context context, int seconds) {
        if (seconds < 0 || seconds > 86400) throw new IllegalArgumentException("Invalid duration");
        SharedPreferences p = prefs(context);
        SharedPreferences.Editor edit = p.edit().putInt(KEY_SECONDS, seconds);
        // Explicit changes apply to currently visible/queued notifications too.
        for (String key : p.getAll().keySet()) if (key.startsWith(PREFIX)) edit.remove(key);
        edit.commit();
    }
}
