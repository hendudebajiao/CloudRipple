package com.bajiao.notificationisland;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Private entry point used by the APK's authorized root worker. */
public final class RecoveryReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!RootKeepaliveManager.isEnabled(context)) return;
        IslandNotificationListener.refresh(context);
        // Notifications are always built by this APK, even when root wakes it.
        IslandForegroundService.ensureStarted(context);
    }
}
