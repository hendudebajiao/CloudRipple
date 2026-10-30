package com.bajiao.notificationisland;

/** Test APK only: root-driven source publishing avoids vendor broadcast deferral. */
public final class IslandTestSourceProvider extends android.content.ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public android.os.Bundle call(String method, String arg, android.os.Bundle extras) {
        if (android.os.Binder.getCallingUid() != 0) throw new SecurityException("Root test only");
        android.content.Intent intent = new android.content.Intent().putExtra("id", Integer.parseInt(arg));
        intent.putExtra("text", "连续转发验证 " + arg);
        intent.putExtra("remove", "remove".equals(method));
        new IslandTestSource().onReceive(requireContext(), intent);
        return new android.os.Bundle();
    }
    @Override public android.database.Cursor query(android.net.Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(android.net.Uri u) { return null; }
    @Override public android.net.Uri insert(android.net.Uri u, android.content.ContentValues v) { return null; }
    @Override public int delete(android.net.Uri u, String s, String[] a) { return 0; }
    @Override public int update(android.net.Uri u, android.content.ContentValues v, String s, String[] a) { return 0; }
}
