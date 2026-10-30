package com.bajiao.notificationisland;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.app.PendingIntent;

/** Handles actions from the promoted notification. */
public final class IslandActionReceiver extends BroadcastReceiver {
    static final String ACTION_STOP = "com.bajiao.notificationisland.ACTION_ISLAND_STOP";
    static final String ACTION_OPEN = "com.bajiao.notificationisland.ACTION_ISLAND_OPEN";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        IslandState.init(context);
        String key = intent.getStringExtra(IslandForegroundService.EXTRA_SOURCE_KEY);
        if (ACTION_STOP.equals(intent.getAction())) {
            dismiss(context, key);
        } else if (ACTION_OPEN.equals(intent.getAction()) && key != null) {
            for (IslandState.Item item : IslandState.snapshot()) {
                if (key.equals(item.sourceKey) && item.contentIntent != null) {
                    try { item.contentIntent.send(); } catch (PendingIntent.CanceledException ignored) {}
                    break;
                }
            }
        }
    }

    static void dismiss(Context context, String key) {
        IslandState.init(context);
        boolean test = IslandForegroundService.testActive(context);
        IslandState.Item current = IslandState.current();
        IslandForegroundService.stopTest(context);
        if (key != null) IslandState.suppress(key);
        else if (!test && current != null) IslandState.suppress(current.sourceKey);
        // Stop only the presenter. The system's listener binding stays intact.
        if (IslandState.current() == null) {
            context.stopService(new Intent(context, IslandForegroundService.class));
        } else {
            // A queued source takes the single slot immediately.
            IslandForegroundService.render(context);
        }
    }
}
