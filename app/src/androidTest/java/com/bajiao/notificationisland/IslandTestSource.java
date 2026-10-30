package com.bajiao.notificationisland;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Separate test APK/UID publishes source notifications through its own NotificationManager. */
public final class IslandTestSource extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        int id = intent.getIntExtra("id", 9901);
        android.util.Log.i("IslandTestSource", "Publishing id=" + id + " elapsed=" + android.os.SystemClock.elapsedRealtime());
        if (intent.getBooleanExtra("remove", false)) {
            manager.cancel(id);
            return;
        }
        manager.createNotificationChannel(new NotificationChannel("source", "回归测试", NotificationManager.IMPORTANCE_LOW));
        android.graphics.Bitmap avatar = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(avatar);
        android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        paint.setColor(android.graphics.Color.MAGENTA);
        canvas.drawCircle(48, 48, 46, paint);
        paint.setColor(android.graphics.Color.YELLOW);
        canvas.drawCircle(48, 48, 22, paint);
        manager.notify(id, new Notification.Builder(context, "source")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setLargeIcon(avatar)
                .setContentTitle("独立来源通知 " + id)
                .setContentText(intent.getStringExtra("text"))
                .build());
    }
}
