package com.bajiao.notificationisland;

import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.os.Handler;
import android.os.Looper;
import android.content.Context;
import android.app.NotificationManager;

import java.util.HashSet;
import java.util.Set;
import java.util.Arrays;
import java.util.Comparator;

/** Reads source notifications and feeds the public Live Update service. */
public final class IslandNotificationListener extends NotificationListenerService {
    static final String PREFS = "notification_island";
    static final String KEY_ALLOWED = "allowed_packages";
    private static final String TAG = "NotificationIsland";
    private static volatile IslandNotificationListener connected;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable activeSync = new Runnable() {
        @Override public void run() {
            syncActive();
            main.postDelayed(this, 3000L);
        }
    };
    private boolean firstSync = true;
    private boolean bound;
    private final Runnable reconnect = () -> {
        if (isConnected()) return;
        try { requestRebind(new ComponentName(this, IslandNotificationListener.class)); }
        catch (RuntimeException error) { Log.w(TAG, "Rebind failed", error); }
        RootKeepaliveManager.repairListener(this);
    };

    @Override public void onCreate() {
        super.onCreate();
        RootKeepaliveManager.monitorCreated(this);
        IslandState.init(this);
    }

    @Override
    public void onListenerConnected() {
        connected = this;
        bound = true;
        main.removeCallbacks(reconnect);
        Log.i(TAG, "Listener connected; allowed=" + allowedPackages(this));
        IslandState.init(this);
        main.removeCallbacks(activeSync);
        main.post(activeSync);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (accepts(sbn)) {
            long received = android.os.SystemClock.elapsedRealtime();
            IslandRelayMetrics.received(sbn.getKey(), received);
            IslandForegroundService.stopTest(this);
            IslandState.init(this);
            IslandState.upsert(sbn);
            IslandForegroundService.render(this);
            // UsageStats IPC is kept out of the incoming notification fast path.
            main.post(() -> {
                IslandState.Item before = IslandState.current();
                IslandOpenedSources.suppressOpened(this);
                if (before != IslandState.current()) IslandForegroundService.render(this);
            });
            Log.i(TAG, "Source accepted id=" + sbn.getId() + " eventMs="
                    + (android.os.SystemClock.elapsedRealtime() - received));
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        // Never respond to our own notification, or idle/live transitions create a loop.
        if (sbn == null || sbn.getPackageName().equals(getPackageName())) return;
        if (IslandState.snapshot().stream().noneMatch(item -> item.sourceKey.equals(sbn.getKey()))
                && !IslandState.isSuppressed(sbn.getKey())) return;
        IslandState.remove(sbn.getKey());
        IslandForegroundService.render(this);
    }

    @Override
    public void onListenerDisconnected() {
        connected = null;
        bound = false;
        main.removeCallbacks(activeSync);
        Log.w(TAG, "notification listener disconnected");
        main.removeCallbacks(reconnect);
        main.postDelayed(reconnect, 2000);
    }

    @Override public void onDestroy() {
        if (connected == this) connected = null;
        bound = false;
        main.removeCallbacks(activeSync);
        main.removeCallbacks(reconnect);
        super.onDestroy();
    }

    private void syncActive() {
        if (!bound) return;
        try {
            IslandState.Item before = IslandState.current();
            StatusBarNotification[] active = getActiveNotifications();
            if (active == null) return;
            RootKeepaliveManager.listenerHeartbeat(this);
            Set<String> allowed = allowedPackages(this);
            Set<String> keys = new HashSet<>();
            for (StatusBarNotification sbn : active) keys.add(sbn.getKey());
            int accepted = 0;
            IslandState.reconcile(keys, allowed);
            Arrays.sort(active, Comparator.comparingLong(StatusBarNotification::getPostTime));
            for (StatusBarNotification sbn : active) if (accepts(sbn)) { accepted++; IslandState.sync(sbn); }
            IslandOpenedSources.suppressOpened(this);
            if (firstSync || !same(before, IslandState.current()))
                Log.i(TAG, "Active sync total=" + active.length + ", accepted=" + accepted);
            IslandState.Item after = IslandState.current();
            // Always render a newly selected source, including when the previous
            // source had the same title/text. This avoids ColorOS retaining idle.
            if (firstSync || !same(before, after) || (!IslandForegroundService.isRunning()
                    && (after != null || IslandForegroundService.testActive(this)))) {
                IslandForegroundService.render(this);
            }
            firstSync = false;
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot read active notifications", e);
        }
    }

    private static boolean same(IslandState.Item a, IslandState.Item b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.sourceKey.equals(b.sourceKey) && a.postedAt == b.postedAt
                && a.title.equals(b.title) && a.text.equals(b.text)
                && a.isSuppressed == b.isSuppressed;
    }

    static void refresh(Context context) {
        IslandState.init(context);
        IslandNotificationListener listener = connected;
        if (listener != null) listener.main.post(listener::syncActive);
        else if (context.getSystemService(NotificationManager.class).isNotificationListenerAccessGranted(
                new ComponentName(context, IslandNotificationListener.class))) {
            try { requestRebind(new ComponentName(context, IslandNotificationListener.class)); }
            catch (RuntimeException e) { Log.w(TAG, "Listener rebind failed", e); }
            // Give the normal system rebind time to finish; do not cycle permission here.
        }
    }

    static boolean isConnected() { return connected != null && connected.bound; }

    static Set<String> allowedPackages(android.content.Context context) {
        Set<String> stored = context.getSharedPreferences(PREFS, MODE_PRIVATE)
                .getStringSet(KEY_ALLOWED, Set.of());
        return stored == null ? new HashSet<>() : new HashSet<>(stored);
    }

    private boolean accepts(StatusBarNotification sbn) {
        return sbn != null && !sbn.getPackageName().equals(getPackageName())
                && (sbn.getNotification().flags & android.app.Notification.FLAG_GROUP_SUMMARY) == 0
                && allowedPackages(this).contains(sbn.getPackageName());
    }

    static void stopIsland(android.content.Context context) {
        context.sendBroadcast(new Intent(IslandActionReceiver.ACTION_STOP)
                .setPackage(context.getPackageName()));
    }
}
