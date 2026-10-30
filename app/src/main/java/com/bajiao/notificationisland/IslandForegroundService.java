package com.bajiao.notificationisland;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.net.Uri;
import android.util.Log;

/** Owns the promoted ongoing notification shown by ColorOS Live Updates. */
public final class IslandForegroundService extends Service {
    static final String CHANNEL_ID = "notification_island_v1";
    static final int NOTIFICATION_ID = 17001;
    static final String ACTION_RENDER = "com.bajiao.notificationisland.RENDER";
    static final String ACTION_START = "com.bajiao.notificationisland.START";
    static final String ACTION_TEST = "com.bajiao.notificationisland.TEST";
    private static final String KEY_TEST = "test_island_active";
    static final String EXTRA_SOURCE_KEY = "source_key";
    private static volatile IslandForegroundService running;
    private static boolean starting;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean live;
    private final Runnable expire = this::renderCurrent;

    @Override
    public void onCreate() {
        super.onCreate();
        IslandState.init(this);
        createChannel();
        // The listener starts this service only after a source item is present.
        // Enter foreground immediately to satisfy Android 17's start deadline.
        running = this;
        starting = false;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_TEST.equals(intent.getAction())) {
            IslandLifetime.forget(this, IslandLifetime.TEST_KEY);
            getSharedPreferences(IslandNotificationListener.PREFS, MODE_PRIVATE)
                    .edit().putBoolean(KEY_TEST, true).commit();
        }
        // Even an already-expired item must satisfy the FGS start contract before
        // renderCurrent removes it. Otherwise Android later crashes the process.
        IslandState.Item initial = IslandState.current();
        postForeground(testActive(this) ? buildTest() : initial == null ? buildPlaceholder() : buildLive(initial));
        live = testActive(this) || initial != null;
        if (IslandState.current() == null && !testActive(this)) {
            // A restarted process first needs its listener snapshot. Meet the FGS
            // deadline, then remove this transient record if no source exists.
            main.postDelayed(this::renderCurrent, 4000L);
        } else {
            main.post(this::renderCurrent);
        }
        if (!IslandNotificationListener.isConnected()) IslandNotificationListener.refresh(this);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = null;
        starting = false;
        main.removeCallbacksAndMessages(null);
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    static void ensureStarted(Context context) {
        if (IslandState.current() == null && !testActive(context)) {
            IslandForegroundService service = running;
            if (service != null) service.requestRender();
            return;
        }
        IslandForegroundService service = running;
        if (service != null) {
            service.requestRender();
            return;
        }
        if (starting) return;
        starting = true;
        // A rejected or interrupted start must not permanently block later sources.
        new Handler(Looper.getMainLooper()).postDelayed(() -> { if (running == null) starting = false; }, 5000);
        Intent intent = new Intent(context, IslandForegroundService.class).setAction(ACTION_RENDER);
        try {
            context.startForegroundService(intent);
            // ColorOS may accept the request while Hans silently proxies the start.
            // A missing onCreate must trigger recovery too, not just thrown errors.
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (running == null && IslandState.current() != null) RootKeepaliveManager.retryService(context);
            }, 50);
        } catch (RuntimeException error) {
            starting = false;
            Log.w("NotificationIsland", "Background FGS start denied; attempting authorized root fallback", error);
            RootKeepaliveManager.retryService(context);
        }
    }

    static void render(Context context) {
        ensureStarted(context);
    }

    private void requestRender() {
        main.removeCallbacks(expire);
        if (Looper.myLooper() == Looper.getMainLooper()) renderCurrent();
        else main.post(expire);
    }

    static boolean isRunning() { return running != null; }

    static void test(Context context) {
        Intent intent = new Intent(context, IslandForegroundService.class).setAction(ACTION_TEST);
        try {
            context.startForegroundService(intent);
        } catch (RuntimeException error) {
            Log.w("NotificationIsland", "Test Live Update start failed", error);
        }
    }

    static boolean testActive(Context context) {
        return context.getSharedPreferences(IslandNotificationListener.PREFS, MODE_PRIVATE)
                .getBoolean(KEY_TEST, false);
    }

    static void stopTest(Context context) {
        if (!testActive(context)) return;
        context.getSharedPreferences(IslandNotificationListener.PREFS, MODE_PRIVATE)
                .edit().putBoolean(KEY_TEST, false).commit();
        IslandLifetime.forget(context, IslandLifetime.TEST_KEY);
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        // Removing the settings task must not stop the relay service.
        super.onTaskRemoved(rootIntent);
    }

    private void renderCurrent() {
        main.removeCallbacks(expire);
        IslandState.Item item = IslandState.current();
        if (testActive(this)) {
            if (scheduleExpiry(IslandLifetime.TEST_KEY)) {
                postForeground(buildTest());
                live = true;
                return;
            }
            stopTest(this);
        }
        while (item != null && !scheduleExpiry(item.sourceKey)) {
            IslandState.suppress(item.sourceKey);
            item = IslandState.current();
        }
        boolean nextLive = item != null;
        if (live != nextLive) {
            // A fresh live record is required on some ColorOS builds after an idle FGS.
            stopForeground(STOP_FOREGROUND_REMOVE);
        }
        if (item == null) {
            stopWhenIdle();
        } else {
            postForeground(buildLive(item));
            IslandRelayMetrics.published(item.sourceKey);
        }
        live = nextLive;
        Log.i("NotificationIsland", nextLive ? "Live notification updated" : "Idle notification removed");
    }

    private boolean scheduleExpiry(String key) {
        long now = System.currentTimeMillis();
        long deadline = IslandLifetime.deadline(this, key, now);
        if (deadline == 0) return true;
        if (deadline <= now) return false;
        main.postDelayed(expire, deadline - now);
        return true;
    }

    private void stopWhenIdle() {
        live = false;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void postForeground(Notification notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildLive(IslandState.Item item) {
        Intent stop = new Intent(this, IslandActionReceiver.class)
                .setAction(IslandActionReceiver.ACTION_STOP)
                .setData(Uri.parse("island://hide/" + Uri.encode(item.sourceKey)))
                .putExtra(EXTRA_SOURCE_KEY, item.sourceKey);
        PendingIntent stopPi = PendingIntent.getBroadcast(this, 17002, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_island)
                .setContentTitle(item.leftLabel)
                .setContentText(item.text)
                .setSubText(item.sourceLabel)
                .setWhen(item.postedAt)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_PROGRESS);
        if (IslandPresentation.enabled(this, IslandPresentation.MANUAL_STOP))
            builder.addAction(new Notification.Action.Builder(null, "下岛", stopPi).build());
        if (item.sourceIcon != null) {
            builder.setLargeIcon(item.sourceIcon);
        }
        if (item.contentIntent != null) {
            Intent open = new Intent(this, IslandOpenActivity.class)
                    .setData(Uri.parse("island://open/" + Uri.encode(item.sourceKey)))
                    .putExtra(EXTRA_SOURCE_KEY, item.sourceKey)
                    .putExtra(IslandOpenActivity.EXTRA_ORIGINAL, item.contentIntent);
            PendingIntent click = PendingIntent.getActivity(this, 17004, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.setContentIntent(click);
            builder.addAction(new Notification.Action.Builder(null, "打开", click).build());
        }
        if (Build.VERSION.SDK_INT >= 37) {
            builder.setRequestPromotedOngoing(true);
            builder.setShortCriticalText(IslandMarquee.shortText(item.shortText));
            builder.getExtras().putString(IslandMarquee.EXTRA_FULL_TEXT, item.shortText);
            builder.getExtras().putString(IslandMarquee.KEY, getPackageName());
            builder.getExtras().putBoolean(IslandPresentation.MARQUEE, IslandPresentation.enabled(this, IslandPresentation.MARQUEE));
            builder.getExtras().putString(IslandMarquee.EXTRA_LEFT_LABEL, item.leftLabel);
        }
        return builder.build();
    }

    private Notification buildPlaceholder() {
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_island)
                .setContentTitle(getString(R.string.app_name))
                .setContentText("")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private Notification buildTest() {
        PendingIntent stop = PendingIntent.getBroadcast(this, 17003,
                new Intent(this, IslandActionReceiver.class).setAction(IslandActionReceiver.ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                // A foreground notification always needs a valid small resource
                // icon. The app icon is placed in the large-icon slot below.
                .setSmallIcon(R.drawable.ic_notification_island)
                .setContentTitle(getString(R.string.app_name))
                .setContentText("测试通知：如果能看到流体云，说明 Live Update 权限正常")
                .setSubText(getString(R.string.app_name))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_PROGRESS);
        if (IslandPresentation.enabled(this, IslandPresentation.MANUAL_STOP))
            builder.addAction(new Notification.Action.Builder(null, "下岛", stop).build());
        Bitmap appIcon = applicationIconBitmap();
        if (appIcon != null) builder.setLargeIcon(appIcon);
        if (Build.VERSION.SDK_INT >= 37) {
            builder.setRequestPromotedOngoing(true);
            builder.setShortCriticalText(IslandMarquee.shortText("这是一条超过四字的滚动测试通知"));
            builder.getExtras().putString(IslandMarquee.KEY, getPackageName());
            builder.getExtras().putString(IslandMarquee.EXTRA_FULL_TEXT, "这是一条超过四字的滚动测试通知");
            builder.getExtras().putString(IslandMarquee.EXTRA_LEFT_LABEL, getString(R.string.app_name));
            builder.getExtras().putBoolean(IslandPresentation.MARQUEE, IslandPresentation.enabled(this, IslandPresentation.MARQUEE));
        }
        return builder.build();
    }

    private Bitmap applicationIconBitmap() {
        try {
            Drawable drawable = getApplicationInfo().loadIcon(getPackageManager());
            int size = 96;
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            drawable.setBounds(0, 0, size, size);
            drawable.draw(canvas);
            return bitmap;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void createChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.channel_description));
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
        }
    }
}
