package com.bajiao.notificationisland;

import android.app.Activity;
import android.app.PendingIntent;
import android.os.Bundle;
import android.widget.Toast;

/** Activity PendingIntent avoids Android's broadcast/service notification trampoline restriction. */
public final class IslandOpenActivity extends Activity {
    static final String EXTRA_ORIGINAL = "original_content_intent";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        IslandState.init(this);
        String key = getIntent().getStringExtra(IslandForegroundService.EXTRA_SOURCE_KEY);
        if (key != null) IslandState.suppress(key);
        IslandForegroundService.stopTest(this);
        IslandForegroundService.render(this);
        PendingIntent original = getIntent().getParcelableExtra(EXTRA_ORIGINAL, PendingIntent.class);
        try {
            if (original != null) original.send();
        } catch (PendingIntent.CanceledException error) {
            Toast.makeText(this, "原通知已失效", Toast.LENGTH_SHORT).show();
        }
        finish();
    }
}
