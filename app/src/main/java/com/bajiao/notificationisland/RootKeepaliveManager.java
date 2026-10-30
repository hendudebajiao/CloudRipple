package com.bajiao.notificationisland;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** All root operations belong to the APK; KernelSU grants su to this package. */
final class RootKeepaliveManager {
    private static final String KEY_ENABLED = "root_keepalive_enabled";
    private static final String ROOT_DIR = "/data/adb/notification_island";
    private static final String SCRIPT = ROOT_DIR + "/watchdog-v4.sh";
    private static final String BOOT_SCRIPT = "/data/adb/service.d/notification-island.sh";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static long lastRetry;
    private static long lastRepair;

    static void listenerHeartbeat(Context context) {
        try {
            new File(context.getFilesDir(), "listener-heartbeat").createNewFile();
            new File(context.getFilesDir(), "listener-heartbeat").setLastModified(System.currentTimeMillis());
        } catch (IOException error) { Log.w("NotificationIsland", "Heartbeat failed", error); }
    }

    static void monitorCreated(Context context) {
        try {
            Files.writeString(new File(context.getFilesDir(), "monitor-pid").toPath(),
                    String.valueOf(android.os.Process.myPid()), StandardCharsets.UTF_8);
        } catch (IOException error) { Log.w("NotificationIsland", "Monitor PID write failed", error); }
    }

    static synchronized void repairListener(Context context) {
        if (IslandNotificationListener.isConnected() || !isEnabled(context)
                || android.os.SystemClock.elapsedRealtime() - lastRepair < 20000) return;
        lastRepair = android.os.SystemClock.elapsedRealtime();
        Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> runRoot(rebindCommand(app)));
    }

    private static String rebindCommand(Context app) {
        String component = app.getPackageName() + "/" + IslandNotificationListener.class.getName();
        int user = android.os.Process.myUid() / 100000;
        // Never grant access after the user has revoked it.
        return "if settings --user " + user + " get secure enabled_notification_listeners | tr ':' '\\n' | grep -Fxq "
                + quote(component) + "; then cmd notification disallow_listener " + quote(component) + " " + user
                + "; cmd notification allow_listener " + quote(component) + " " + user + "; fi";
    }

    private static String watchdog(Context app) throws IOException {
        try (var input = app.getAssets().open("watchdog-v4.sh")) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("@PACKAGE@", app.getPackageName())
                    .replace("@UID@", String.valueOf(android.os.Process.myUid()))
                    .replace("@USER@", String.valueOf(android.os.Process.myUid() / 100000))
                    .replace("@FILES@", quote(app.getFilesDir().getAbsolutePath()));
        }
    }
    static boolean isEnabled(Context context) {
        return context.getSharedPreferences(IslandNotificationListener.PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false);
    }

    static void enable(Context context, Consumer<Boolean> callback) {
        Context app = context.getApplicationContext();
        IslandRecoveryJob.schedule(app);
        EXECUTOR.execute(() -> {
            boolean ok;
            try {
                File script = new File(app.getFilesDir(), "watchdog-v4.sh");
                Files.writeString(script.toPath(), watchdog(app), StandardCharsets.UTF_8);
                File boot = new File(app.getFilesDir(), "notification-island-boot.sh");
                int user = android.os.Process.myUid() / 100000;
                Files.writeString(boot.toPath(), "#!/system/bin/sh\n(\n"
                        + "until [ \"$(getprop sys.boot_completed)\" = 1 ]; do sleep 5; done\n"
                        + "until cmd activity get-started-user-state " + user
                        + " 2>/dev/null | grep -q RUNNING_UNLOCKED; do sleep 5; done\n"
                        + "[ -f " + ROOT_DIR + "/enabled ] || exit 0\n"
                        + "dumpsys deviceidle whitelist +" + app.getPackageName() + " >/dev/null 2>&1\n"
                        + "sh " + ROOT_DIR + "/watchdog-v4.sh\n) >/dev/null 2>&1 &\n", StandardCharsets.UTF_8);
                ok = runRoot(cleanupLegacy() + cleanupWatchdogs()
                        + "mkdir -p " + ROOT_DIR + "; rm -rf " + ROOT_DIR + "/lock; "
                        + "cp " + quote(script.getAbsolutePath()) + " " + ROOT_DIR + "/watchdog-v4.sh && "
                        + "chmod 700 " + ROOT_DIR + "/watchdog-v4.sh && "
                        + "mkdir -p /data/adb/service.d && cp " + quote(boot.getAbsolutePath()) + " " + BOOT_SCRIPT
                        + " && chmod 700 " + BOOT_SCRIPT + " && touch " + ROOT_DIR + "/enabled && "
                        + "dumpsys deviceidle whitelist +" + app.getPackageName() + " >/dev/null 2>&1 && "
                        + "cmd appops set --user " + user + " " + app.getPackageName() + " GET_USAGE_STATS allow && "
                        + "(nohup sh " + ROOT_DIR + "/watchdog-v4.sh >/dev/null 2>&1 </dev/null &)");
                if (ok && !IslandNotificationListener.isConnected()) runRoot(rebindCommand(app));
            } catch (IOException | RuntimeException error) {
                Log.w("NotificationIsland", "Root whitelist operation failed", error);
                ok = false;
            }
            if (ok) {
                app.getSharedPreferences(IslandNotificationListener.PREFS, Context.MODE_PRIVATE)
                        .edit().putBoolean(KEY_ENABLED, true).commit();
                MAIN.post(() -> {
                    if (IslandState.current() != null) IslandForegroundService.ensureStarted(app);
                    IslandNotificationListener.refresh(app);
                });
            }
            complete(callback, ok);
        });
    }

    static void disable(Context context, Consumer<Boolean> callback) {
        Context app = context.getApplicationContext();
        IslandRecoveryJob.cancel(app);
        EXECUTOR.execute(() -> {
            boolean ok = runRoot(cleanupWatchdogs() + cleanupLegacy()
                    + "p=$(cat " + quote(new File(app.getFilesDir(), "monitor-pid").getAbsolutePath()) + " 2>/dev/null); "
                    + "case $p in ''|*[!0-9]*) ;; *) if [ \"$(tr -d '\\000' < /proc/$p/cmdline 2>/dev/null)\" = "
                    + quote(app.getPackageName() + ":monitor")
                    + " ]; then echo 250 > /proc/$p/oom_score_adj 2>/dev/null; fi;; esac; "
                    + "rm -f " + SCRIPT + " " + BOOT_SCRIPT + " " + ROOT_DIR + "/enabled "
                    + ROOT_DIR + "/watchdog-v4.sh; rm -rf " + ROOT_DIR + "/lock; "
                    + "dumpsys deviceidle whitelist -" + app.getPackageName());
            if (ok) app.getSharedPreferences(IslandNotificationListener.PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_ENABLED, false).apply();
            complete(callback, ok);
        });
    }

    static void restartSystemUi(Context context, Consumer<Boolean> callback) {
        EXECUTOR.execute(() -> {
            boolean ok = runRoot("cmd activity force-stop com.android.systemui");
            complete(callback, ok);
        });
    }

    /** Root fallback is allowed only for a real live item; never start an empty FGS. */
    static synchronized void retryService(Context context) {
        if (!isEnabled(context) || (IslandState.current() == null && !IslandForegroundService.testActive(context))
                || android.os.SystemClock.elapsedRealtime() - lastRetry < 1000) return;
        lastRetry = android.os.SystemClock.elapsedRealtime();
        Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> runRoot(startCommand(app)));
    }

    private static String startCommand(Context app) {
        // Hans can proxy a start even with cgroup.freeze=0. Its per-UID dump
        // command repairs vendor state; always restore the original diagnostic flag.
        return "(saved=$(getprop persist.sys.hans.dump); "
                + "trap 'setprop persist.sys.hans.dump \"$saved\"' EXIT; "
                + "setprop persist.sys.hans.dump true; timeout 3 dumpsys activity hans forceUnfreeze "
                + android.os.Process.myUid() + " >/dev/null 2>&1); "
                + "cmd activity start-foreground-service --user " + (android.os.Process.myUid() / 100000)
                + " -n " + app.getPackageName() + "/.IslandForegroundService"
                + " -a " + IslandForegroundService.ACTION_START;
    }

    private static String cleanupWatchdogs() {
        return "ps -A -o PID,ARGS | while read p exe script rest; do "
                + "case \"$exe\" in sh|/system/bin/sh) case \"$script\" in "
                + ROOT_DIR + "/watchdog-v[234].sh) "
                + "c=$(tr '\\000' ' ' < /proc/$p/cmdline 2>/dev/null); "
                + "case \"$c\" in \"$exe $script \") kill -9 $p 2>/dev/null || true;; esac;; esac;; esac; done; ";
    }
    private static String cleanupLegacy() {
        return "if [ -f /data/local/tmp/notification_island.keepalive.pid ]; then "
                + "p=$(cat /data/local/tmp/notification_island.keepalive.pid); "
                + "case $p in ''|*[!0-9]*) ;; *) "
                + "if tr '\\000' ' ' < /proc/$p/cmdline 2>/dev/null | grep -Fq 'notification_island.keepalive.pid'; "
                + "then kill $p 2>/dev/null || true; fi;; esac; "
                + "rm -f /data/local/tmp/notification_island.keepalive.pid; fi; ";
    }

    private static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }

    private static void complete(Consumer<Boolean> callback, boolean result) {
        if (callback != null) MAIN.post(() -> callback.accept(result));
    }

    private static boolean runRoot(String command) {
        Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", command)
                    .redirectOutput(new File("/dev/null"))
                    .redirectError(new File("/dev/null")).start();
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException error) {
            Log.w("NotificationIsland", "su execution failed", error);
            return false;
        } catch (InterruptedException error) {
            if (process != null) process.destroyForcibly();
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
