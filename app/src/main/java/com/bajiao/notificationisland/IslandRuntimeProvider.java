package com.bajiao.notificationisland;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Sole settings owner in :monitor; commands are serialized with listener/service callbacks. */
public final class IslandRuntimeProvider extends ContentProvider {
    private final Handler main = new Handler(Looper.getMainLooper());
    private String rootOperationId = "";
    private boolean rootOperationPending;
    private boolean rootOperationSuccess;
    private long rootOperationStarted;

    @Override public boolean onCreate() {
        RootKeepaliveManager.monitorCreated(context());
        return true;
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        int caller = Binder.getCallingUid();
        if (caller != 0 && caller != android.os.Process.myUid()) throw new SecurityException("Private runtime");
        if (Looper.myLooper() == Looper.getMainLooper()) return execute(method, extras);
        FutureTask<Bundle> command = new FutureTask<>(() -> execute(method, extras));
        main.post(command);
        try { return command.get(10, TimeUnit.SECONDS); }
        catch (InterruptedException error) {
            command.cancel(false);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Runtime command interrupted", error);
        } catch (java.util.concurrent.TimeoutException error) {
            command.cancel(false);
            throw new IllegalStateException("Runtime command timed out", error);
        } catch (java.util.concurrent.ExecutionException error) {
            throw new IllegalStateException("Runtime command failed", error.getCause());
        }
    }

    private android.content.Context context() { return java.util.Objects.requireNonNull(getContext()); }

    // IPC setters must reach disk before replying: callers can immediately restart the monitor.
    @android.annotation.SuppressLint("ApplySharedPref")
    private Bundle execute(String method, Bundle extras) {
        var context = context();
        var prefs = context.getSharedPreferences(IslandNotificationListener.PREFS, 0);
        switch (method) {
            case "status": break;
            case "refresh":
                IslandRecoveryJob.schedule(context);
                IslandNotificationListener.refresh(context);
                break;
            case "selection":
                ArrayList<String> packages = extras == null ? null : extras.getStringArrayList("packages");
                if (packages == null) throw new IllegalArgumentException("Missing selection");
                if (!prefs.edit().putStringSet(IslandNotificationListener.KEY_ALLOWED, new HashSet<>(packages)).commit())
                    throw new IllegalStateException("Could not save selection");
                IslandNotificationListener.refresh(context);
                break;
            case "duration":
                if (extras == null || !extras.containsKey("seconds")) throw new IllegalArgumentException("Missing duration");
                IslandLifetime.setSeconds(context, extras.getInt("seconds"));
                IslandForegroundService.render(context);
                break;
            case "stop": IslandActionReceiver.dismiss(context, null); break;
            case "presentation":
                if (extras == null) throw new IllegalArgumentException("Missing presentation options");
                var edit = prefs.edit();
                for (String key : new String[]{IslandPresentation.PUSH, IslandPresentation.MANUAL_STOP, IslandPresentation.MARQUEE}) {
                    if (extras.containsKey(key)) edit.putBoolean(key, extras.getBoolean(key));
                }
                if (!edit.commit()) throw new IllegalStateException("Could not save presentation");
                IslandForegroundService.render(context);
                if (extras.containsKey(IslandPresentation.PUSH) && extras.getBoolean(IslandPresentation.PUSH))
                    IslandNotificationListener.refresh(context);
                break;
            case "test": IslandForegroundService.test(context); break;
            case "root_initialize":
                if (RootKeepaliveManager.isUninstalling(context) && !rootOperationPending)
                    RootKeepaliveManager.cancelUninstall(context);
                // Serialize and persist before dispatch: repeated launches/recreation cannot
                // request su again. Existing installations already enabled need no setup.
                if (!prefs.getBoolean("root_setup_attempted", false)) {
                    if (!prefs.edit().putBoolean("root_setup_attempted", true).commit())
                        throw new IllegalStateException("Could not save root setup state");
                    if (!RootKeepaliveManager.isEnabled(context)) {
                        prefs.edit().putString("root_status", "等待首次 Root 授权").apply();
                        RootKeepaliveManager.enable(context, ok -> prefs.edit()
                                .putString("root_status", ok ? "已授权" : "Root 未启用")
                                .apply());
                    }
                }
                break;
            case "uninstall_cancel":
                if (rootOperationPending) throw new IllegalStateException("Cleanup still running");
                RootKeepaliveManager.cancelUninstall(context);
                break;
            case "uninstall_prepare":
            case "root_enable":
            case "root_disable":
            case "restart_system_ui":
                // A Binder reply can be lost after the command was accepted. Retrying
                // the same ID must not tear down/redeploy the watchdog twice.
                String operation = extras == null ? null : extras.getString("operation_id");
                if (!"restart_system_ui".equals(method)) {
                    if (rootOperationPending && android.os.SystemClock.elapsedRealtime() - rootOperationStarted < 45000) break;
                    if (operation != null && operation.equals(rootOperationId) && !rootOperationPending) break;
                    rootOperationId = operation == null ? java.util.UUID.randomUUID().toString() : operation;
                    rootOperationPending = true;
                    rootOperationStarted = android.os.SystemClock.elapsedRealtime();
                }
                java.util.function.Consumer<Boolean> done = ok -> {
                    if (!"restart_system_ui".equals(method)) {
                        rootOperationPending = false;
                        rootOperationStarted = 0;
                        rootOperationSuccess = ok;
                        prefs.edit().putString("root_status", ok
                                ? ("uninstall_prepare".equals(method) ? "环境已清理，可以卸载"
                                : "root_enable".equals(method) ? "已授权" : "看护已停止")
                                : "uninstall_prepare".equals(method) ? "环境清理失败，请检查 KernelSU 授权后重试"
                                : "Root 操作失败，请检查 KernelSU").apply();
                    }
                };
                if ("uninstall_prepare".equals(method)) RootKeepaliveManager.prepareUninstall(context, done);
                else if ("root_enable".equals(method)) RootKeepaliveManager.enable(context, done);
                else if ("root_disable".equals(method)) RootKeepaliveManager.disable(context, done);
                else RootKeepaliveManager.restartSystemUi(context, done);
                break;
            default: throw new IllegalArgumentException("Unknown runtime command");
        }
        return snapshot();
    }

    private Bundle snapshot() {
        var context = context();
        Bundle state = new Bundle();
        state.putInt("seconds", IslandLifetime.seconds(context));
        state.putBoolean(IslandPresentation.PUSH, IslandPresentation.enabled(context, IslandPresentation.PUSH));
        state.putBoolean(IslandPresentation.MANUAL_STOP, IslandPresentation.enabled(context, IslandPresentation.MANUAL_STOP));
        state.putBoolean(IslandPresentation.MARQUEE, IslandPresentation.enabled(context, IslandPresentation.MARQUEE));
        state.putStringArrayList("packages", new ArrayList<>(IslandNotificationListener.allowedPackages(context)));
        state.putBoolean("connected", IslandNotificationListener.isConnected());
        state.putBoolean("presenting", IslandForegroundService.isRunning());
        state.putBoolean("test", IslandForegroundService.testActive(context));
        IslandState.Item current = IslandState.current();
        state.putString("source_package", current == null ? null : current.sourcePackage);
        state.putString("source_key", current == null ? null : current.sourceKey);
        // Status deliberately carries no notification text or PendingIntent.
        state.putBoolean("root_enabled", RootKeepaliveManager.isEnabled(context));
        state.putBoolean("uninstall_preparing", RootKeepaliveManager.isUninstalling(context));
        state.putBoolean("uninstall_ready", RootKeepaliveManager.isUninstallReady(context));
        state.putString("root_operation_id", rootOperationId);
        state.putBoolean("root_operation_pending", rootOperationPending);
        state.putBoolean("root_operation_success", rootOperationSuccess);
        state.putBoolean("root_setup_attempted", context.getSharedPreferences(IslandNotificationListener.PREFS, 0)
                .getBoolean("root_setup_attempted", false));
        state.putString("root_status", context.getSharedPreferences(IslandNotificationListener.PREFS, 0)
                .getString("root_status", "未授权（点击启用时请求授权）"));
        state.putInt("runtime_pid", android.os.Process.myPid());
        state.putLong("relay_latency_ms", IslandRelayMetrics.lastLatencyMs);
        return state;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
