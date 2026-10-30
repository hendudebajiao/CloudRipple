package com.bajiao.notificationisland;

/** Root-only local diagnostics; excluded from release APKs. */
public final class RuntimeProbeReceiver extends android.content.BroadcastReceiver {
    @Override public void onReceive(android.content.Context context, android.content.Intent intent) {
        String command = intent.getStringExtra("command");
        if ("test".equals(command)) IslandForegroundService.test(context);
        else if ("stop".equals(command)) IslandActionReceiver.dismiss(context, null);
        var state = IslandRuntime.call(context, "status", null);
        setResultData("pid=" + state.getInt("runtime_pid") + " connected=" + state.getBoolean("connected")
                + " presenting=" + state.getBoolean("presenting") + " latencyMs=" + state.getLong("relay_latency_ms")
                + " sourceKey=" + state.getString("source_key"));
    }
}
