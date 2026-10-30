package com.bajiao.notificationisland;

/** Monotonic timing contains identifiers only, never notification content. */
final class IslandRelayMetrics {
    private static final java.util.Map<String, Long> pending = new java.util.LinkedHashMap<>();
    static volatile long lastLatencyMs = -1;

    static void received(String key, long now) {
        pending.put(key, now);
        while (pending.size() > 64) pending.remove(pending.keySet().iterator().next());
    }

    static void published(String key) {
        Long received = pending.remove(key);
        if (received == null) return;
        lastLatencyMs = android.os.SystemClock.elapsedRealtime() - received;
        android.util.Log.i("NotificationIsland", "Relay submitted latencyMs=" + lastLatencyMs);
    }
}
