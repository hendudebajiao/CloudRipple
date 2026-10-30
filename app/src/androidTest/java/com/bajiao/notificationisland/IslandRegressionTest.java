package com.bajiao.notificationisland;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.Notification;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Process;
import android.service.notification.StatusBarNotification;
import android.text.Spanned;
import android.text.TextUtils;
import android.widget.TextView;

import java.util.Set;

/** Standalone Android regression runner: no network or test-library dependencies. */
public final class IslandRegressionTest extends Instrumentation {
    private boolean serviceTest;
    private boolean relayTest;
    private String command;
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        serviceTest = arguments != null && "true".equals(arguments.getString("service"));
        relayTest = arguments != null && "true".equals(arguments.getString("relay"));
        command = arguments == null ? null : arguments.getString("command");
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            if ("relay_prepare".equals(command) || "relay_restore".equals(command)) {
                var context = getTargetContext();
                var backup = context.getSharedPreferences("regression_setup", 0);
                Bundle selection = new Bundle();
                Bundle duration = new Bundle();
                if ("relay_prepare".equals(command)) {
                    Bundle state = IslandRuntime.call(context, "status", null);
                    var allowed = state.getStringArrayList("packages");
                    java.util.Set<String> stored = allowed == null ? java.util.Set.of() : new java.util.HashSet<>(allowed);
                    if (!backup.contains("seconds")) backup.edit().putInt("seconds", state.getInt("seconds"))
                            .putStringSet("packages", stored).commit();
                    var testing = new java.util.ArrayList<>(stored);
                    testing.add(getContext().getPackageName());
                    selection.putStringArrayList("packages", testing);
                    duration.putInt("seconds", 60);
                } else {
                    selection.putStringArrayList("packages", new java.util.ArrayList<>(backup.getStringSet("packages", java.util.Set.of())));
                    duration.putInt("seconds", backup.getInt("seconds", 5));
                }
                IslandRuntime.call(context, "selection", selection);
                IslandRuntime.call(context, "duration", duration);
                if ("relay_restore".equals(command)) backup.edit().clear().commit();
                result.putString("result", "PASS: " + command);
                finish(Activity.RESULT_OK, result);
                return;
            }
            if ("restore".equals(command)) {
                Bundle selection = new Bundle();
                selection.putStringArrayList("packages", new java.util.ArrayList<>(java.util.List.of("com.tencent.mobileqq")));
                IslandRuntime.call(getTargetContext(), "selection", selection);
                Bundle duration = new Bundle();
                duration.putInt("seconds", 5);
                IslandRuntime.call(getTargetContext(), "duration", duration);
                IslandRuntime.call(getTargetContext(), "stop", null);
                result.putString("result", "Restored QQ selection and 5 second duration; " + IslandRuntime.call(getTargetContext(), "status", null));
                finish(Activity.RESULT_OK, result);
                return;
            }
            final int[] attempts = {0};
            Bundle retried = IslandRuntime.callWithRetry(() -> {
                if (attempts[0]++ == 0) throw new android.os.DeadObjectException();
                Bundle ok = new Bundle(); ok.putBoolean("recovered", true); return ok;
            });
            require(retried.getBoolean("recovered") && attempts[0] == 2, "Dead provider reacquired once");
            attempts[0] = 0;
            try {
                IslandRuntime.callWithRetry(() -> { attempts[0]++; throw new SecurityException("revoked"); });
                throw new AssertionError("SecurityException expected");
            } catch (SecurityException expected) {
                require(attempts[0] == 1, "Access denial never retried");
            }
            attempts[0] = 0;
            try {
                IslandRuntime.callWithRetry(() -> { attempts[0]++; throw new android.os.DeadObjectException(); });
                throw new AssertionError("Bounded disconnect expected");
            } catch (IllegalStateException expected) {
                require(attempts[0] == 2, "Dead provider recovery bounded");
            }
            // Model checks must never write the live runtime's settings from this process.
            android.content.Context modelContext = new android.content.ContextWrapper(getTargetContext()) {
                @Override public android.content.Context getApplicationContext() { return this; }
                @Override public android.content.SharedPreferences getSharedPreferences(String name, int mode) {
                    return super.getSharedPreferences("regression_" + name, mode);
                }
            };
            runOnMainSync(() -> {
                IslandState.init(modelContext);
                IslandState.clearStack();
                StatusBarNotification first = notification(901, "第一条", "第一条消息内容");
                StatusBarNotification next = notification(902, "第二条", "第二条消息内容");
                IslandState.remove(first.getKey());
                IslandState.remove(next.getKey());
                try {
                    var prefs = modelContext.getSharedPreferences(IslandNotificationListener.PREFS, 0);
                    boolean hadDuration = prefs.contains(IslandLifetime.KEY_SECONDS);
                    int savedDuration = IslandLifetime.seconds(modelContext);
                    String lifetimeKey = "regression-lifetime";
                    try {
                        prefs.edit().putInt(IslandLifetime.KEY_SECONDS, 5).commit();
                        IslandLifetime.forget(modelContext, lifetimeKey);
                        require(IslandLifetime.deadline(modelContext, lifetimeKey, 1000) == 6000,
                                "Deadline starts at first display");
                        require(IslandLifetime.deadline(modelContext, lifetimeKey, 4000) == 6000,
                                "Update and reload preserve deadline");
                        IslandLifetime.prune(modelContext, Set.of(lifetimeKey));
                        require(IslandLifetime.deadline(modelContext, lifetimeKey, 5000) == 6000,
                                "Active source keeps deadline");
                        IslandLifetime.forget(modelContext, lifetimeKey);
                        require(IslandLifetime.deadline(modelContext, lifetimeKey, 9000) == 14000,
                                "Removed source gets new deadline");
                        prefs.edit().putInt(IslandLifetime.KEY_SECONDS, 0).commit();
                        IslandLifetime.forget(modelContext, lifetimeKey);
                        require(IslandLifetime.deadline(modelContext, lifetimeKey, 10000) == 0,
                                "Zero disables expiration");
                    } finally {
                        IslandLifetime.forget(modelContext, lifetimeKey);
                        var edit = prefs.edit();
                        if (hadDuration) edit.putInt(IslandLifetime.KEY_SECONDS, savedDuration);
                        else edit.remove(IslandLifetime.KEY_SECONDS);
                        edit.commit();
                    }
                    IslandState.upsert(first);
                    IslandState.upsert(next);
                    require(IslandState.current().sourceKey.equals(next.getKey()), "Newest wins");
                    IslandState.suppress(next.getKey());
                    IslandState.upsert(notification(902, "第二条", "内容已经更新"));
                    require(IslandState.current().sourceKey.equals(first.getKey()), "Update stays suppressed");
                    IslandState.remove(next.getKey());
                    IslandState.upsert(next);
                    require(IslandState.current().sourceKey.equals(next.getKey()), "Removal resets suppression");
                    IslandState.reconcile(Set.of(first.getKey(), next.getKey()), Set.of());
                    require(IslandState.current() == null, "Deselected apps pruned");

                    require(IslandMarquee.shortText("😀😀😀😀😀😀😀😀").codePointCount(0,
                            IslandMarquee.shortText("😀😀😀😀😀😀😀😀").length()) == 7, "Emoji safe truncation");
                    Notification original = new Notification.Builder(getTargetContext(), "test")
                            .setSmallIcon(android.R.drawable.ic_dialog_info)
                            .setContentText(IslandMarquee.mark("超过四字应该平滑滚动"))
                            .build();
                    Parcel parcel = Parcel.obtain();
                    Notification restored;
                    try {
                        original.writeToParcel(parcel, 0);
                        parcel.setDataPosition(0);
                        restored = Notification.CREATOR.createFromParcel(parcel);
                    } finally { parcel.recycle(); }
                    CharSequence text = restored.extras.getCharSequence(Notification.EXTRA_TEXT);
                    require(text instanceof Spanned, "Marquee annotation survives notification parceling");
                    TextView view = new TextView(getContext());
                    view.setText(text);
                    IslandMarqueeHook.afterSetText(view, text);
                    require(view.getEllipsize() != TextUtils.TruncateAt.MARQUEE, "Expanded text stays normal");
                    android.widget.FrameLayout right = new android.widget.FrameLayout(getContext());
                    right.setId(getContext().getResources().getIdentifier("right_section", "id", getContext().getPackageName()));
                    right.addView(view);
                    IslandMarqueeHook.rememberCompact(text.toString(), true, "联系人", "超过四字", null);
                    view.setText(text);
                    IslandMarqueeHook.afterSetText(view, text);
                    require(view.getEllipsize() == TextUtils.TruncateAt.MARQUEE && view.isSelected(), "Right section marquee enabled");
                    android.text.Layout stable = view.getLayout();
                    final int[] textChanges = {0};
                    view.addTextChangedListener(new android.text.TextWatcher() {
                        public void beforeTextChanged(CharSequence v, int start, int count, int after) {}
                        public void onTextChanged(CharSequence v, int start, int before, int count) { textChanges[0]++; }
                        public void afterTextChanged(android.text.Editable v) {}
                    });
                    view.getViewTreeObserver().dispatchOnPreDraw();
                    view.getViewTreeObserver().dispatchOnPreDraw();
                    require(textChanges[0] == 0 && stable == view.getLayout(), "Unchanged frames never reset marquee text/layout");
                    view.setSelected(false);
                    view.setEllipsize(TextUtils.TruncateAt.END);
                    view.setMaxLines(2);
                    view.getViewTreeObserver().dispatchOnPreDraw();
                    require(view.isSelected() && view.getEllipsize() == TextUtils.TruncateAt.MARQUEE
                            && view.getMaxLines() == 1, "System template property reset repaired before draw");
                    IslandMarqueeHook.rememberCompact(text.toString(), false, "联系人", "超过四字", null);
                    view.setText(text);
                    IslandMarqueeHook.afterSetText(view, text);
                    require(view.getEllipsize() != TextUtils.TruncateAt.MARQUEE, "Right section marquee disabled");
                    view.setText("普通通知");
                    IslandMarqueeHook.afterSetText(view, "普通通知");
                    require(view.getEllipsize() != TextUtils.TruncateAt.MARQUEE && !view.isSelected(), "Reused view restored");
                    view.setText("四个汉字");
                    IslandMarqueeHook.afterSetText(view, IslandMarquee.mark("四个汉字"));
                    require(view.getEllipsize() != TextUtils.TruncateAt.MARQUEE, "Four characters stay static");
                    IslandMarqueeHook.rememberCompact("iiiiiiii", true, "联系人", "iiiiiii", null);
                    view.setText("iiiiiiii");
                    IslandMarqueeHook.afterAttached(view);
                    require(view.getMaxWidth() == IslandMarqueeHook.slotWidth(view), "Fixed five-glyph viewport for narrow text");
                    int fixedWidth = view.getMaxWidth();
                    IslandMarqueeHook.rememberCompact("iiiiiiii", false, "联系人", "iiiiiii", null);
                    IslandMarqueeHook.afterAttached(view);
                    require(view.getEllipsize() != TextUtils.TruncateAt.MARQUEE && !view.isSelected(), "Disabled reattach restores view");
                    IslandMarqueeHook.rememberCompact("iiiiiiii", true, "联系人", "iiiiiii", null);
                    IslandMarqueeHook.afterSetText(view, view.getText());
                    view.setText("其他软件的通知");
                    IslandMarqueeHook.afterAttached(view);
                    require(view.getEllipsize() != TextUtils.TruncateAt.MARQUEE && !view.isSelected(), "Recycled view reattach revalidates source");
                    IslandMarqueeHook.rememberCompact("四个汉字", true, "联系人", "四个汉字", null);
                    view.setText("四个汉字");
                    IslandMarqueeHook.afterSetText(view, view.getText());
                    require(view.getEllipsize() != TextUtils.TruncateAt.MARQUEE, "Enabled four-character message stays static");
                    require(view.getMinWidth() == fixedWidth && view.getMaxWidth() == fixedWidth, "Short message keeps identical slot width");
                    Notification legacy = new Notification.Builder(getTargetContext(), "test")
                            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("周末群")
                            .setContentText("小明：今晚20:30见面").build();
                    IslandState.Conversation split = IslandState.conversation("com.tencent.mobileqq", legacy, "周末群", "小明：今晚20:30见面", "QQ");
                    require(split.name.equals("小明") && split.body.equals("今晚20:30见面"), "Group full-width colon split once");
                    split = IslandState.conversation("com.tencent.mm", legacy, "周末群", "小红: https://example.com/a:b", "微信");
                    require(split.name.equals("小红") && split.body.equals("https://example.com/a:b"), "ASCII delimiter preserves URL in body");
                    split = IslandState.conversation("com.tencent.mm", legacy, "周末群", "[2条]小红：收到", "微信");
                    require(split.name.equals("小红") && split.body.equals("收到"), "Legacy unread count prefix removed");
                    split = IslandState.conversation("com.tencent.mm", legacy, "小明", "https://example.com", "微信");
                    require(split.name.equals("小明") && split.body.equals("https://example.com"), "URL without sender stays intact");
                    split = IslandState.conversation("test.download", legacy, "下载", "小明：文件完成", "下载工具");
                    require(split.name.equals("下载工具") && split.body.equals("小明：文件完成"), "Non-chat body never split");
                    require(legacy.extras.getCharSequence(Notification.EXTRA_TEXT).toString().equals("小明：今晚20:30见面"), "Expanded original remains intact");
                    Notification chat = new Notification.Builder(getTargetContext(), "test")
                            .setSmallIcon(android.R.drawable.ic_dialog_info)
                            .setStyle(new Notification.MessagingStyle(new android.app.Person.Builder().setName("自己").build())
                                    .addMessage("正文", System.currentTimeMillis(), new android.app.Person.Builder().setName("小明").build()))
                            .build();
                    split = IslandState.conversation("com.tencent.mobileqq", chat, "群聊", "小明：摘要", "QQ");
                    require(split.name.equals("小明") && split.body.equals("正文"), "MessagingStyle latest message has priority");
                    require("小明".equals(IslandState.contactLabel("com.tencent.mobileqq", chat, "群聊", "QQ")), "QQ uses sender");
                    require("小明".equals(IslandState.contactLabel("com.tencent.mm", chat, "群聊", "微信")), "WeChat uses sender");
                    require("下载工具".equals(IslandState.contactLabel("test.download", chat, "下载完成", "下载工具")), "Other apps use app label");
                    require("联系人".equals(IslandState.contactLabel("com.tencent.mm", original, "联系人", "微信")), "Legacy title fallback");
                    android.graphics.Bitmap avatar = android.graphics.Bitmap.createBitmap(24, 24, android.graphics.Bitmap.Config.ARGB_8888);
                    avatar.eraseColor(android.graphics.Color.MAGENTA);
                    Notification withAvatar = new Notification.Builder(getTargetContext(), "test")
                            .setSmallIcon(android.R.drawable.ic_dialog_info).setLargeIcon(avatar).build();
                    require(iconItem(withAvatar).sourceIcon.getPixel(48, 48) == android.graphics.Color.MAGENTA,
                            "Notification large icon wins over small/application icon");
                    require(iconItem(new Notification.Builder(getTargetContext(), "test")
                            .setSmallIcon(android.R.drawable.ic_dialog_info).build()).sourceIcon != null,
                            "Notification small icon is usable without large icon");
                    require(iconItem(new Notification()).sourceIcon != null, "Missing notification icon falls back to app icon");
                } finally {
                    IslandState.remove(first.getKey());
                    IslandState.remove(next.getKey());
                }
            });
            if (serviceTest) {
                var context = getTargetContext();
                Bundle initial = IslandRuntime.call(context, "status", null);
                int saved = initial.getInt("seconds");
                try {
                    Bundle pushOptions = new Bundle();
                    pushOptions.putBoolean(IslandPresentation.PUSH, true);
                    IslandRuntime.call(context, "presentation", pushOptions);
                    IslandRuntime.call(context, "refresh", null);
                    for (int retry = 0; retry < 20 && !IslandRuntime.call(context, "status", null).getBoolean("connected"); retry++) {
                        android.os.SystemClock.sleep(500);
                    }
                    require(IslandRuntime.call(context, "status", null).getBoolean("connected"), "Real listener is connected");
                    require(initial.getInt("runtime_pid") != Process.myPid(), "Runtime is isolated from settings process");
                    Bundle seconds = new Bundle();
                    seconds.putInt("seconds", 60);
                    IslandRuntime.call(context, "duration", seconds);
                    IslandRuntime.call(context, "test", null);
                    for (int retry = 0; retry < 30 && !IslandRuntime.call(context, "status", null).getBoolean("presenting"); retry++)
                        android.os.SystemClock.sleep(200);
                    android.os.SystemClock.sleep(200);
                    Bundle showing = IslandRuntime.call(context, "status", null);
                    require(showing.getBoolean("presenting") && showing.getBoolean("test"),
                            "Test FGS starts");
                    pushOptions.putBoolean(IslandPresentation.PUSH, false);
                    IslandRuntime.call(context, "presentation", pushOptions);
                    android.os.SystemClock.sleep(500);
                    Bundle paused = IslandRuntime.call(context, "status", null);
                    require(!paused.getBoolean(IslandPresentation.PUSH, true) && !paused.getBoolean("presenting")
                            && !paused.getBoolean("test"), "Push off removes presenter and test state");
                    require(paused.getBoolean("connected"), "Push off retains listener");
                    require(paused.getBoolean("root_enabled") == initial.getBoolean("root_enabled"), "Push off retains root setting");
                    IslandRuntime.call(context, "test", null);
                    android.os.SystemClock.sleep(500);
                    require(!IslandRuntime.call(context, "status", null).getBoolean("presenting"), "Disabled push blocks test start");
                    pushOptions.putBoolean(IslandPresentation.PUSH, true);
                    IslandRuntime.call(context, "presentation", pushOptions);
                    IslandRuntime.call(context, "test", null);
                    android.os.SystemClock.sleep(600);
                    require(IslandRuntime.call(context, "status", null).getBoolean("presenting"), "Enabled push can present again");
                    // Change real runtime settings and inspect the submitted notification.
                    seconds.putInt("seconds", 60);
                    IslandRuntime.call(context, "duration", seconds);
                    Bundle options = new Bundle();
                    options.putBoolean(IslandPresentation.MANUAL_STOP, false);
                    options.putBoolean(IslandPresentation.MARQUEE, false);
                    IslandRuntime.call(context, "presentation", options);
                    Notification plain = awaitPresentation(false);
                    require(plain.actions == null || plain.actions.length == 0, "Manual stop action removed");
                    require(!(plain.extras.getCharSequence(Notification.EXTRA_TEXT) instanceof Spanned),
                            "Disabled scrolling removes expanded marker");
                    require(!plain.extras.getBoolean(IslandPresentation.MARQUEE, true), "Compact scroll disabled");
                    options.putBoolean(IslandPresentation.MANUAL_STOP, true);
                    options.putBoolean(IslandPresentation.MARQUEE, true);
                    IslandRuntime.call(context, "presentation", options);
                    Notification scrolling = awaitPresentation(true);
                    require(scrolling.actions != null && scrolling.actions.length == 1, "Manual stop action restored");
                    require(!(scrolling.extras.getCharSequence(Notification.EXTRA_TEXT) instanceof Spanned),
                            "Expanded content remains normal with scrolling enabled");
                    require(scrolling.extras.getBoolean(IslandPresentation.MARQUEE), "Compact scroll enabled");
                    seconds.putInt("seconds", 2);
                    IslandRuntime.call(context, "duration", seconds);
                    android.os.SystemClock.sleep(3000);
                    Bundle expired = IslandRuntime.call(context, "status", null);
                    require(!expired.getBoolean("test") && !expired.getBoolean("presenting"),
                            "Test expires and removes FGS without a standby notification");
                    require(expired.getBoolean("connected"), "Expiration retains listener");
                    seconds.putInt("seconds", 60);
                    IslandRuntime.call(context, "duration", seconds);
                    IslandRuntime.call(context, "test", null);
                    android.os.SystemClock.sleep(600);
                    IslandRuntime.call(context, "stop", null);
                    android.os.SystemClock.sleep(600);
                    Bundle stopped = IslandRuntime.call(context, "status", null);
                    require(!stopped.getBoolean("presenting") && !stopped.getBoolean("test"), "Manual stop unregisters presenter");
                    require(stopped.getBoolean("connected"), "Manual stop retains listener");
                    IslandRuntime.call(context, "test", null);
                    android.os.SystemClock.sleep(600);
                    require(IslandRuntime.call(context, "status", null).getBoolean("presenting"), "Presenter restarts after dismissal");
                    IslandRuntime.call(context, "stop", null);
                    if (relayTest) {
                    Bundle selection = new Bundle();
                    var selected = initial.getStringArrayList("packages");
                    var testing = selected == null ? new java.util.ArrayList<String>() : new java.util.ArrayList<>(selected);
                    testing.add(getContext().getPackageName());
                    selection.putStringArrayList("packages", testing);
                    IslandRuntime.call(context, "selection", selection);
                    try {
                        publish(9901, "首次消息", false);
                        awaitSource(true);
                        IslandRuntime.call(context, "stop", null);
                        android.os.SystemClock.sleep(700);
                        require(IslandRuntime.call(context, "status", null).getBoolean("connected"), "Source dismissal retains listener");
                        publish(9901, "同一通知更新应保持下岛", false);
                        android.os.SystemClock.sleep(3500);
                        require(!getContext().getPackageName().equals(IslandRuntime.call(context, "status", null).getString("source_package")),
                                "Same source update stays suppressed");
                        publish(9901, "", true);
                        android.os.SystemClock.sleep(700);
                        publish(9901, "移除后重新发布应再次上岛", false);
                        awaitSource(true);
                    } finally {
                        publish(9901, "", true);
                        android.os.SystemClock.sleep(700);
                        selection.putStringArrayList("packages", selected == null ? new java.util.ArrayList<>() : selected);
                        IslandRuntime.call(context, "selection", selection);
                    }
                    }
                } finally {
                    Bundle options = new Bundle();
                    options.putBoolean(IslandPresentation.PUSH, initial.getBoolean(IslandPresentation.PUSH, true));
                    options.putBoolean(IslandPresentation.MANUAL_STOP, initial.getBoolean(IslandPresentation.MANUAL_STOP, true));
                    options.putBoolean(IslandPresentation.MARQUEE, initial.getBoolean(IslandPresentation.MARQUEE, true));
                    IslandRuntime.call(context, "presentation", options);
                    IslandRuntime.call(context, "stop", null);
                    Bundle seconds = new Bundle();
                    seconds.putInt("seconds", saved);
                    IslandRuntime.call(context, "duration", seconds);
                }
            }
            result.putString("result", "PASS: model regressions" + (serviceTest
                    ? ", isolated runtime, expiration/stop retain listener, presenter restart"
                        + (relayTest ? ", separate APK source relay/suppression/repost" : "") : ""));
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("result", "FAIL: " + error);
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private Notification relayNotification() {
        for (StatusBarNotification sbn : getTargetContext().getSystemService(android.app.NotificationManager.class).getActiveNotifications()) {
            if (sbn.getId() == IslandForegroundService.NOTIFICATION_ID) return sbn.getNotification();
        }
        throw new AssertionError("Missing relay notification");
    }

    private Notification awaitPresentation(boolean enabled) {
        // NotificationManager enqueues updates asynchronously in system_server.
        for (int retry = 0; retry < 30; retry++) {
            Notification notification = relayNotification();
            boolean action = notification.actions != null && notification.actions.length != 0;
            if (action == enabled && notification.extras.getBoolean(IslandPresentation.MARQUEE, !enabled) == enabled)
                return notification;
            android.os.SystemClock.sleep(100);
        }
        throw new AssertionError("Presentation update not applied");
    }

    private StatusBarNotification notification(int id, String title, String text) {
        Notification notification = new Notification.Builder(getTargetContext(), "test")
                .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(text).build();
        return new StatusBarNotification("com.bajiao.island.test", "com.bajiao.island.test", id, null,
                Process.myUid(), 0, 0, notification, Process.myUserHandle(), System.currentTimeMillis());
    }

    private IslandState.Item iconItem(Notification notification) {
        String pkg = getTargetContext().getPackageName();
        return new IslandState.Item(new StatusBarNotification(pkg, pkg, 903, null, Process.myUid(),
                0, 0, notification, Process.myUserHandle(), System.currentTimeMillis()));
    }

    private void publish(int id, String text, boolean remove) {
        getTargetContext().sendBroadcast(new android.content.Intent()
                .addFlags(android.content.Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                .addFlags(android.content.Intent.FLAG_RECEIVER_FOREGROUND)
                .setComponent(new android.content.ComponentName(getContext().getPackageName(), IslandTestSource.class.getName()))
                .putExtra("id", id).putExtra("text", text).putExtra("remove", remove));
    }

    private void awaitSource(boolean presenting) {
        for (int retry = 0; retry < 20; retry++) {
            Bundle state = IslandRuntime.call(getTargetContext(), "status", null);
            if (state.getBoolean("presenting") == presenting
                    && getContext().getPackageName().equals(state.getString("source_package"))) return;
            android.os.SystemClock.sleep(500);
        }
        throw new AssertionError("Separate APK notification was not relayed");
    }


    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
