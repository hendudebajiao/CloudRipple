package com.bajiao.notificationisland;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Private IPC client: the settings process never opens the runtime preference file. */
final class IslandRuntime {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static Bundle call(Context context, String method, Bundle extras) {
        Bundle result = context.getContentResolver().call(
                Uri.parse("content://" + context.getPackageName() + ".runtime"), method, null, extras);
        if (result == null) throw new IllegalStateException("Runtime unavailable");
        return result;
    }

    static void submit(Context context, String method, Bundle extras, Consumer<Bundle> callback) {
        Context app = context.getApplicationContext();
        IO.execute(() -> {
            Bundle result;
            try { result = call(app, method, extras); }
            catch (RuntimeException error) {
                android.util.Log.w("NotificationIsland", "Runtime IPC failed: " + method, error);
                result = new Bundle();
                result.putString("error", "后台通信失败，请重试");
            }
            Bundle reply = result;
            if (callback != null) MAIN.post(() -> callback.accept(reply));
        });
    }
}
