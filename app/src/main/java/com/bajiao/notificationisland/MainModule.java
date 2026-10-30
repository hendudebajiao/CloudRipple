package com.bajiao.notificationisland;

import android.util.Log;
import android.widget.TextView;
import android.app.Notification;

import androidx.annotation.NonNull;

import io.github.libxposed.api.XposedModule;

/** LSPosed entry point. The notification pipeline uses public Android APIs. */
public final class MainModule extends XposedModule {
    private static final String TAG = "NotificationIsland";

    @Override
    public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
        log(Log.INFO, TAG, "loaded in " + param.getProcessName()
                + ", libxposed API=" + getApiVersion());
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        if ("com.android.systemui".equals(param.getPackageName())) {
            try {
                hook(Notification.class.getDeclaredMethod("getShortCriticalText"))
                        .intercept(chain -> {
                            Object original = chain.proceed();
                            Notification notification = (Notification) chain.getThisObject();
                            if (notification.extras != null && "com.bajiao.notificationisland".equals(
                                    notification.extras.getString(IslandMarquee.KEY))) {
                                String full = notification.extras.getString(IslandMarquee.EXTRA_FULL_TEXT);
                                boolean scroll = notification.extras.getBoolean(IslandPresentation.MARQUEE, true);
                                IslandMarqueeHook.rememberCompact(full, scroll,
                                        notification.extras.getString(IslandMarquee.EXTRA_LEFT_LABEL),
                                        original == null ? "" : original.toString(), notification.getLargeIcon());
                                if (scroll && full != null && !full.isEmpty()) {
                                    return full;
                                }
                            }
                            return original;
                        });
            } catch (ReflectiveOperationException | LinkageError error) {
                log(Log.WARN, TAG, "Compact text hook unavailable: " + error);
            }
            try {
                hook(TextView.class.getDeclaredMethod("setText", CharSequence.class, TextView.BufferType.class))
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            try {
                                IslandMarqueeHook.afterSetText((TextView) chain.getThisObject(),
                                        (CharSequence) chain.getArg(0));
                            } catch (RuntimeException error) {
                                log(Log.WARN, TAG, "Marquee binding skipped: " + error);
                            }
                            return result;
                        });
                hook(TextView.class.getDeclaredMethod("setText", CharSequence.class))
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            try { IslandMarqueeHook.afterSetText((TextView) chain.getThisObject(),
                                    (CharSequence) chain.getArg(0)); }
                            catch (RuntimeException error) { log(Log.WARN, TAG, "Marquee binding skipped: " + error); }
                            return result;
                        });
                hook(TextView.class.getDeclaredMethod("onAttachedToWindow"))
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            try { IslandMarqueeHook.afterAttached((TextView) chain.getThisObject()); }
                            catch (RuntimeException error) { log(Log.WARN, TAG, "Marquee attach skipped: " + error); }
                            return result;
                        });
                log(Log.INFO, TAG, "SystemUI relay-text marquee hook installed");
            } catch (ReflectiveOperationException | LinkageError error) {
                log(Log.WARN, TAG, "Marquee hook unavailable: " + error);
            }
        }
    }
}
