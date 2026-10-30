package com.bajiao.notificationisland;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Reconnects notification access after boot/update without starting an empty FGS. */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction()))) return;
        IslandRecoveryJob.schedule(context);
        IslandNotificationListener.refresh(context);
        if (RootKeepaliveManager.isEnabled(context)) {
            PendingResult pending = goAsync();
            RootKeepaliveManager.enable(context, success -> pending.finish());
        }
    }
}
