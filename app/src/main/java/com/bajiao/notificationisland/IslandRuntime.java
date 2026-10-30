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
        return callWithRetry(() -> {
            // Reacquire after process death instead of retaining a dead provider binder.
            try (android.content.ContentProviderClient client = context.getContentResolver()
                    .acquireUnstableContentProviderClient(Uri.parse("content://" + context.getPackageName() + ".runtime"))) {
                if (client == null) throw new android.os.DeadObjectException();
                Bundle result = client.call(method, null, extras);
                if (result == null) throw new IllegalStateException("Runtime unavailable");
                return result;
            }
        });
    }

    interface Attempt { Bundle run() throws android.os.RemoteException; }

    static Bundle callWithRetry(Attempt attempt) {
        for (int retry = 0; ; retry++) {
            try { return attempt.run(); }
            catch (android.os.DeadObjectException error) {
                if (retry != 0) throw new IllegalStateException("Runtime disconnected", error);
                android.os.SystemClock.sleep(200);
            } catch (android.os.RemoteException error) {
                throw new IllegalStateException("Runtime transaction failed", error);
            }
        }
    }

    static void submit(Context context, String method, Bundle extras, Consumer<Bundle> callback) {
        Context app = context.getApplicationContext();
        IO.execute(() -> {
            Bundle result;
            try { result = call(app, method, extras); }
            catch (RuntimeException error) {
                android.util.Log.w("NotificationIsland", "Runtime IPC failed: " + method, error);
                result = new Bundle();
                result.putString("error", "暂时无法连接监测服务，已保存的 Root 状态未更改");
            }
            Bundle reply = result;
            if (callback != null) MAIN.post(() -> callback.accept(reply));
        });
    }
}
