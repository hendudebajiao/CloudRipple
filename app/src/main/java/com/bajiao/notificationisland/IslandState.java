package com.bajiao.notificationisland;

import android.app.PendingIntent;
import android.app.Notification;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.content.Context;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Thread-safe single-slot notification stack shared by the listener and FGS. */
final class IslandState {
    static final class Item {
        final String sourcePackage;
        final String sourceLabel;
        final String sourceKey;
        final String title;
        final String text;
        final String shortText;
        final String leftLabel;
        final long postedAt;
        final PendingIntent contentIntent;
        final StatusBarNotification original;
        final boolean isSuppressed;
        final Bitmap sourceIcon;

        Item(StatusBarNotification sbn) {
            sourcePackage = sbn.getPackageName();
            String label = labels.get(sourcePackage);
            if (label == null && appContext != null) {
                try {
                    var pm = appContext.getPackageManager();
                    label = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(sourcePackage, 0)));
                    labels.put(sourcePackage, label);
                } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {}
            }
            sourceLabel = label == null ? "新通知" : label;
            sourceKey = sbn.getKey();
            var n = sbn.getNotification();
            sourceIcon = notificationIcon(n, sourcePackage);
            var extras = n.extras;
            CharSequence titleValue = extras == null ? null : extras.getCharSequence("android.title");
            CharSequence textValue = extras == null ? null : extras.getCharSequence("android.text");
            CharSequence big = extras == null ? null : extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
            title = nonEmpty(titleValue == null ? null : titleValue.toString(), sourceLabel);
            leftLabel = contactLabel(sourcePackage, n, title, sourceLabel);
            text = nonEmpty(textValue == null ? null : textValue.toString(), big == null ? "" : big.toString());
            // Keep full content: SystemUI scrolls the view, never re-posts text fragments.
            shortText = text.isEmpty() ? title : text;
            postedAt = sbn.getPostTime();
            contentIntent = n.contentIntent;
            original = sbn;
            isSuppressed = suppressed.contains(sourceKey);
        }

        private static Bitmap iconFor(String packageName) {
            if (appContext == null) return null;
            Bitmap cached = icons.get(packageName);
            if (cached != null) return cached;
            try {
                Drawable drawable = appContext.getPackageManager().getApplicationIcon(packageName);
                int size = 96;
                Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap);
                drawable.setBounds(0, 0, size, size);
                drawable.draw(canvas);
                icons.put(packageName, bitmap);
                return bitmap;
            } catch (Throwable ignored) {
                return null;
            }
        }

        private static Bitmap notificationIcon(Notification notification, String pkg) {
            if (appContext != null) {
                for (Icon icon : new Icon[]{notification.getLargeIcon(), notification.getSmallIcon()}) {
                    if (icon == null) continue;
                    try {
                        Drawable drawable = icon.loadDrawable(appContext);
                        if (drawable == null) continue;
                        Bitmap bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
                        drawable.setBounds(0, 0, 96, 96);
                        drawable.draw(new Canvas(bitmap));
                        return bitmap;
                    } catch (RuntimeException ignored) {}
                }
            }
            return iconFor(pkg);
        }

        private static String nonEmpty(String value, String fallback) {
            return value == null || value.trim().isEmpty() ? fallback : value;
        }

    }

    private static final List<Item> stack = new ArrayList<>();
    private static final Set<String> suppressed = new HashSet<>();
    private static final android.util.LruCache<String, Bitmap> icons = new android.util.LruCache<>(32);
    private static final android.util.LruCache<String, String> labels = new android.util.LruCache<>(64);
    private static Context appContext;

    private IslandState() {}

    static String contactLabel(String pkg, Notification notification, String title, String appLabel) {
        if (!"com.tencent.mobileqq".equals(pkg) && !"com.tencent.mm".equals(pkg)) return appLabel;
        android.os.Parcelable[] messages = notification.extras == null ? null
                : notification.extras.getParcelableArray(Notification.EXTRA_MESSAGES);
        if (messages != null) {
            for (int i = messages.length - 1; i >= 0; i--) {
                if (!(messages[i] instanceof android.os.Bundle message)) continue;
                android.app.Person person = message.getParcelable("sender_person");
                CharSequence sender = person == null ? message.getCharSequence("sender") : person.getName();
                if (sender != null && !sender.toString().trim().isEmpty()) return sender.toString();
            }
        }
        return title == null || title.trim().isEmpty() ? appLabel : title;
    }

    static synchronized void init(Context context) {
        if (appContext != null) return;
        appContext = context.getApplicationContext();
        Set<String> saved = appContext.getSharedPreferences(IslandNotificationListener.PREFS, Context.MODE_PRIVATE)
                .getStringSet("suppressed_keys", Set.of());
        suppressed.clear();
        if (saved != null) suppressed.addAll(saved);
    }

    private static void persistSuppressed() {
        if (appContext == null) return;
        appContext.getSharedPreferences(IslandNotificationListener.PREFS, Context.MODE_PRIVATE)
                .edit().putStringSet("suppressed_keys", new HashSet<>(suppressed)).commit();
    }

    static synchronized void upsert(StatusBarNotification sbn) {
        String key = sbn.getKey();
        stack.removeIf(item -> item.sourceKey.equals(key));
        stack.add(0, new Item(sbn));
        while (stack.size() > 64) stack.remove(stack.size() - 1);
    }

    static synchronized void clearStack() {
        stack.clear();
    }

    static synchronized void sync(StatusBarNotification sbn) {
        for (Item item : stack) {
            if (item.sourceKey.equals(sbn.getKey()) && item.postedAt == sbn.getPostTime()) return;
        }
        upsert(sbn);
        stack.sort(java.util.Comparator.comparingLong((Item item) -> item.postedAt).reversed());
    }

    static synchronized void reconcile(Set<String> activeKeys, Set<String> allowed) {
        if (appContext != null) IslandLifetime.prune(appContext, activeKeys);
        stack.removeIf(item -> !activeKeys.contains(item.sourceKey) || !allowed.contains(item.sourcePackage));
        if (suppressed.retainAll(activeKeys)) persistSuppressed();
    }

    static synchronized void remove(String key) {
        if (appContext != null) IslandLifetime.forget(appContext, key);
        stack.removeIf(item -> item.sourceKey.equals(key));
        suppressed.remove(key);
        persistSuppressed();
    }

    static synchronized void suppress(String key) {
        if (suppressed.add(key)) persistSuppressed();
    }

    static synchronized boolean isSuppressed(String key) {
        return suppressed.contains(key);
    }

    static synchronized Item current() {
        for (Item item : stack) {
            if (!suppressed.contains(item.sourceKey)) return item;
        }
        return null;
    }

    static synchronized List<Item> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(stack));
    }
}
