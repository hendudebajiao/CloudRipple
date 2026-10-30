package com.bajiao.notificationisland;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Switch;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Configuration remains responsive while packages and root are queried. */
public final class MainActivity extends Activity {
    private final List<AppRow> apps = new ArrayList<>(), filtered = new ArrayList<>();
    private final Set<String> selected = new HashSet<>();
    private final android.util.LruCache<String, android.graphics.drawable.Drawable> appIcons = new android.util.LruCache<>(80);
    private AppAdapter adapter;
    private android.widget.ListView appList;
    private android.widget.FrameLayout pageHost;
    private final View[] pages = new View[3];
    private CloudUi.Navigation navigation;
    private int currentPage;
    private Bundle restoredUi;
    private TextView heroTitle, heroSubtitle, serviceState, selectionSummary, durationSummary, rootSummary;
    private TextView permissionSummary, rootStatus;
    private Button enableRoot, disableRoot;
    private boolean rootOperationPending;
    private String rootRequestId;
    private final Runnable rootTimeout = () -> {
        if (!rootOperationPending || isDestroyed()) return;
        rootOperationPending = false;
        enableRoot.setEnabled(true);
        disableRoot.setEnabled(true);
        rootStatus.setText("操作结果尚未返回，正在读取已保存状态…");
        queryRuntime("status", null);
    };
    private final java.util.List<View> insetScrolls = new ArrayList<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView count;
    private EditText search;
    private Button access, promoted, notificationPermission;
    private Button usageAccess;
    private Button duration;
    private Switch manualStop, marquee;
    private boolean bindingOptions;
    private Bundle runtimeState = new Bundle();
    private final Runnable pollRuntime = new Runnable() {
        @Override public void run() {
            queryRuntime("status", null);
            main.postDelayed(this, 3000);
        }
    };
    private boolean appsLoaded;

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putInt("page", currentPage);
        if (appsLoaded) out.putStringArrayList("draft_selection", new ArrayList<>(selected));
        out.putString("query", search.getText().toString());
        super.onSaveInstanceState(out);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        restoredUi = state;
        if (state != null) currentPage = state.getInt("page", 0);
        buildUi();
        if (state != null) search.setText(state.getString("query", ""));
        // The monitor owns a persistent one-shot setup guard, including activity recreation.
        queryRuntime("root_initialize", null);
    }

    @Override protected void onResume() {
        super.onResume();
        refreshPermissions();
        queryRuntime("refresh", null);
        main.post(pollRuntime);
    }

    @Override protected void onPause() {
        main.removeCallbacks(pollRuntime);
        super.onPause();
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        main.removeCallbacks(rootTimeout);
        super.onDestroy();
    }

    private void buildUi() {
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().setNavigationBarContrastEnforced(false);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        root.setBackgroundColor(CloudUi.BG);
        pageHost = new android.widget.FrameLayout(this);
        root.addView(pageHost, new android.widget.FrameLayout.LayoutParams(-1, -1));
        pages[0] = buildHome();
        pages[1] = buildApps();
        pages[2] = buildSettings();
        for (View page : pages) pageHost.addView(page, new android.widget.FrameLayout.LayoutParams(-1, -1));
        navigation = new CloudUi.Navigation(this, index -> showPage(index, true));
        android.widget.FrameLayout.LayoutParams navLp = new android.widget.FrameLayout.LayoutParams(dp(312), dp(70), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        navLp.bottomMargin = dp(24);
        root.addView(navigation, navLp);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars()
                    | android.view.WindowInsets.Type.displayCutout());
            android.graphics.Insets ime = insets.getInsets(android.view.WindowInsets.Type.ime());
            pageHost.setPadding(bars.left, bars.top, bars.right, 0);
            int bottom = Math.max(bars.bottom, ime.bottom);
            for (View scroll : insetScrolls) scroll.setPadding(scroll.getPaddingLeft(), scroll.getPaddingTop(),
                    scroll.getPaddingRight(), dp(110) + bottom);
            android.widget.FrameLayout.LayoutParams lp = (android.widget.FrameLayout.LayoutParams) navigation.getLayoutParams();
            lp.width = Math.min(dp(336), Math.max(dp(240), getResources().getDisplayMetrics().widthPixels - bars.left - bars.right - dp(48)));
            lp.bottomMargin = dp(18) + bottom;
            navigation.setLayoutParams(lp);
            return insets;
        });
        setContentView(root);
        showPage(currentPage, false);
        root.requestApplyInsets();
        loadApps();
    }

    private ScrollView scrollPage(LinearLayout content) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setPadding(dp(22), dp(8), dp(22), dp(124));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        insetScrolls.add(scroll);
        return scroll;
    }

    private void heading(LinearLayout page, String title, String subtitle) {
        TextView eyebrow = CloudUi.text(this, "云漾  /  CLOUD RIPPLE", 10, CloudUi.BLUE, true);
        eyebrow.setLetterSpacing(.16f);
        page.addView(eyebrow, space(-1, -2, 12, 12));
        page.addView(CloudUi.text(this, title, 32, CloudUi.INK, true), space(-1, -2, 0, 6));
        page.addView(CloudUi.text(this, subtitle, 13, CloudUi.MUTED, false), space(-1, -2, 0, 26));
    }

    private View buildHome() {
        LinearLayout page = CloudUi.column(this);
        heading(page, "云漾", "让重要消息，轻轻浮现");
        LinearLayout hero = CloudUi.card(this);
        hero.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                new int[]{0xffD9EAFE, 0xffEDF5FF}));
        ((android.graphics.drawable.GradientDrawable) hero.getBackground()).setCornerRadius(dp(28));
        LinearLayout intro = new LinearLayout(this);
        intro.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout words = CloudUi.column(this);
        words.addView(CloudUi.text(this, "原生流体云", 12, CloudUi.BLUE, true));
        heroTitle = CloudUi.text(this, "正在连接", 26, CloudUi.INK, true);
        words.addView(heroTitle, space(-1, -2, 10, 7));
        heroSubtitle = CloudUi.text(this, "读取通知监测状态…", 12, CloudUi.MUTED, false);
        words.addView(heroSubtitle);
        intro.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        android.widget.ImageView logo = new android.widget.ImageView(this);
        logo.setImageResource(R.drawable.ic_app_icon);
        logo.setBackground(CloudUi.shape(this, 0xffFFFFFF, 22));
        logo.setClipToOutline(true);
        intro.addView(logo, new LinearLayout.LayoutParams(dp(78), dp(78)));
        hero.addView(intro);
        TextView hint = CloudUi.text(this, "消息监测独立运行，关闭界面后仍可接收通知", 12, 0xff6584AA, false);
        hero.addView(hint, space(-1, -2, 22, 0));
        page.addView(hero);

        LinearLayout stats = new LinearLayout(this);
        LinearLayout selectedCard = CloudUi.card(this);
        selectedCard.addView(CloudUi.text(this, "已选应用", 12, CloudUi.MUTED, false));
        selectionSummary = CloudUi.text(this, "—", 24, CloudUi.INK, true);
        selectedCard.addView(selectionSummary, space(-1, -2, 9, 0));
        selectedCard.setOnClickListener(v -> showPage(1, true));
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, -1, 1);
        half.rightMargin = dp(7);
        stats.addView(selectedCard, half);
        LinearLayout timeCard = CloudUi.card(this);
        timeCard.addView(CloudUi.text(this, "停留时长", 12, CloudUi.MUTED, false));
        durationSummary = CloudUi.text(this, "—", 24, CloudUi.INK, true);
        timeCard.addView(durationSummary, space(-1, -2, 9, 0));
        timeCard.setOnClickListener(v -> { showPage(2, true); editDuration(); });
        half = new LinearLayout.LayoutParams(0, -1, 1);
        half.leftMargin = dp(7);
        stats.addView(timeCard, half);
        page.addView(stats, space(-1, dp(108), 0, 14));

        LinearLayout health = CloudUi.card(this);
        section(health, "运行状态");
        serviceState = CloudUi.text(this, "正在读取状态…", 14, CloudUi.INK, false);
        serviceState.setLineSpacing(dp(9), 1);
        health.addView(serviceState);
        rootSummary = CloudUi.text(this, "", 12, CloudUi.MUTED, false);
        health.addView(rootSummary, space(-1, -2, 15, 0));
        page.addView(health);
        LinearLayout actions = CloudUi.card(this);
        section(actions, "快捷操作");
        Button test = button("测试上岛");
        test.setOnClickListener(v -> queryRuntime("test", null));
        actions.addView(test);
        Button stop = button("立即下岛");
        secondary(stop);
        stop.setOnClickListener(v -> queryRuntime("stop", null));
        actions.addView(stop, space(-1, dp(48), 10, 0));
        page.addView(actions);
        page.addView(CloudUi.text(this, "ColorOS 17 · Live Update", 11, CloudUi.MUTED, false), space(-1, -2, 4, 0));
        return scrollPage(page);
    }

    private View buildApps() {
        LinearLayout page = CloudUi.column(this);
        page.setPadding(dp(22), dp(8), dp(22), 0);
        heading(page, "应用", "选择可以通过流体云提醒你的应用");
        search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("搜索应用名称或包名");
        search.setTextSize(14);
        search.setTextColor(CloudUi.INK);
        search.setHintTextColor(CloudUi.MUTED);
        search.setPadding(dp(18), 0, dp(18), 0);
        search.setBackground(CloudUi.shape(this, 0xffE9F0F9, 18));
        search.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        page.addView(search, space(-1, dp(52), 0, 12));
        LinearLayout summary = new LinearLayout(this);
        summary.setGravity(Gravity.CENTER_VERTICAL);
        count = CloudUi.text(this, "正在读取应用…", 12, CloudUi.MUTED, false);
        summary.addView(count, new LinearLayout.LayoutParams(0, -2, 1));
        Button save = button("保存选择");
        save.setTextSize(12);
        save.setOnClickListener(v -> saveSelection());
        summary.addView(save, new LinearLayout.LayoutParams(dp(100), dp(40)));
        page.addView(summary, space(-1, -2, 0, 12));
        appList = new android.widget.ListView(this);
        appList.setDivider(null);
        appList.setSelector(new android.graphics.drawable.ColorDrawable(0x00000000));
        appList.setVerticalScrollBarEnabled(false);
        appList.setClipToPadding(false);
        appList.setPadding(0, 0, 0, dp(124));
        adapter = new AppAdapter();
        appList.setAdapter(adapter);
        page.addView(appList, new LinearLayout.LayoutParams(-1, 0, 1));
        insetScrolls.add(appList);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { filterApps(); }
            @Override public void afterTextChanged(Editable value) {}
        });
        return page;
    }

    private View buildSettings() {
        LinearLayout page = CloudUi.column(this);
        heading(page, "设置", "把每一次提醒，调成喜欢的样子");
        LinearLayout display = CloudUi.card(this);
        section(display, "流体云显示");
        duration = button(durationLabel());
        secondary(duration);
        duration.setOnClickListener(v -> editDuration());
        display.addView(duration);
        manualStop = option("显示手动下岛按钮", IslandPresentation.MANUAL_STOP);
        display.addView(manualStop, space(-1, dp(62), 8, 0));
        display.addView(CloudUi.text(this, "在展开的流体云中显示下岛操作", 12, CloudUi.MUTED, false));
        divider(display);
        marquee = option("岛右侧文字滚动", IslandPresentation.MARQUEE);
        display.addView(marquee, space(-1, dp(54), 0, 0));
        display.addView(CloudUi.text(this, "正文超过四个字时滚动，展开后正常显示", 12, CloudUi.MUTED, false));
        page.addView(display);

        LinearLayout permissions = CloudUi.card(this);
        section(permissions, "权限与连接");
        permissionSummary = CloudUi.text(this, "正在检查权限…", 13, CloudUi.MUTED, false);
        permissionSummary.setLineSpacing(dp(7), 1);
        permissions.addView(permissionSummary, space(-1, -2, 0, 10));
        access = button("开启通知访问");
        access.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        permissions.addView(access);
        notificationPermission = button("开启通知权限");
        notificationPermission.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 17010);
            else openNotificationSettings();
        });
        permissions.addView(notificationPermission);
        promoted = button("开启 Live Update");
        promoted.setOnClickListener(v -> openPromotedSettings());
        permissions.addView(promoted);
        usageAccess = button("开启使用情况访问");
        usageAccess.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                .setData(Uri.parse("package:" + getPackageName()))));
        permissions.addView(usageAccess);
        page.addView(permissions);

        LinearLayout rootCard = CloudUi.card(this);
        section(rootCard, "Root 保活");
        rootStatus = CloudUi.text(this, "正在读取已保存状态…", 13, CloudUi.MUTED, false);
        rootStatus.setLineSpacing(dp(5), 1);
        rootCard.addView(rootStatus, space(-1, -2, 0, 14));
        enableRoot = button("启用 / 刷新 Root 保活");
        enableRoot.setEnabled(false);
        enableRoot.setOnClickListener(v -> rootCommand("root_enable"));
        rootCard.addView(enableRoot);
        disableRoot = button("停止 Root 保活");
        disableRoot.setEnabled(false);
        secondary(disableRoot);
        disableRoot.setOnClickListener(v -> rootCommand("root_disable"));
        rootCard.addView(disableRoot, space(-1, dp(48), 10, 0));
        page.addView(rootCard);

        LinearLayout about = CloudUi.card(this);
        section(about, "关于云漾");
        about.addView(CloudUi.text(this, "云漾  1.8.1", 15, CloudUi.INK, true));
        about.addView(CloudUi.text(this, "Android 17 / ColorOS 17", 12, CloudUi.MUTED, false), space(-1, -2, 6, 14));
        about.addView(CloudUi.text(this, "作者  八角", 14, CloudUi.INK, true), space(-1, -2, 0, 10));
        about.addView(profileLink("GitHub", "hendudebajiao", "https://github.com/hendudebajiao"));
        about.addView(profileLink("酷安", "八角 · 37544079", "https://www.coolapk.com/u/37544079"));
        divider(about);
        TextView credits = CloudUi.text(this, "界面参考  miuix  ↗", 13, CloudUi.BLUE, false);
        credits.setPadding(0, dp(12), 0, dp(12));
        credits.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://github.com/miuix-kotlin-multiplatform/miuix"))));
        about.addView(credits);
        page.addView(about);
        return scrollPage(page);
    }

    private View profileLink(String platform, String account, String url) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(60));
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        CloudUi.pressable(row, CloudUi.PALE, 16);
        LinearLayout words = CloudUi.column(this);
        words.addView(CloudUi.text(this, platform, 14, CloudUi.BLUE, true));
        words.addView(CloudUi.text(this, account, 12, CloudUi.MUTED, false), space(-1, -2, 4, 0));
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(CloudUi.text(this, "↗", 20, CloudUi.BLUE, false));
        row.setLayoutParams(space(-1, -2, 0, 8));
        row.setFocusable(true);
        row.setContentDescription("打开作者八角的" + platform + "主页");
        row.setOnClickListener(v -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
            catch (android.content.ActivityNotFoundException error) {
                android.widget.Toast.makeText(this, "未找到可打开链接的应用", android.widget.Toast.LENGTH_SHORT).show();
            }
        });
        return row;
    }

    private void showPage(int index, boolean animate) {
        boolean changed = currentPage != index;
        currentPage = index;
        for (int i = 0; i < pages.length; i++) pages[i].setVisibility(i == index ? View.VISIBLE : View.GONE);
        navigation.select(index, animate);
        if (changed && animate) {
            pages[index].setAlpha(.55f);
            pages[index].setTranslationY(dp(8));
            pages[index].animate().alpha(1).translationY(0).setDuration(200).start();
            navigation.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
        }
        if (index != 1) {
            search.clearFocus();
            getSystemService(android.view.inputmethod.InputMethodManager.class)
                    .hideSoftInputFromWindow(search.getWindowToken(), 0);
        }
    }

    private void editDuration() {
        EditText seconds = new EditText(this);
        seconds.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        seconds.setText(String.valueOf(runtimeState.getInt("seconds", IslandLifetime.DEFAULT_SECONDS)));
        seconds.setSelectAllOnFocus(true);
        seconds.setPadding(dp(24), dp(12), dp(24), dp(12));
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this).setTitle("上岛停留时长")
                .setMessage("单位：秒；0 表示不自动下岛。同一条通知更新不重新计时。")
                .setView(seconds).setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                int value = Integer.parseInt(seconds.getText().toString());
                if (value < 0 || value > 86400) throw new IllegalArgumentException();
                Bundle params = new Bundle();
                params.putInt("seconds", value);
                queryRuntime("duration", params);
                dialog.dismiss();
            } catch (IllegalArgumentException error) { seconds.setError("请输入 0～86400 的整数"); }
        }));
        dialog.show();
    }

    private void section(LinearLayout parent, String title) {
        parent.addView(CloudUi.text(this, title, 17, CloudUi.INK, true), space(-1, -2, 0, 18));
    }

    private void divider(LinearLayout parent) {
        View line = new View(this);
        line.setBackgroundColor(CloudUi.LINE);
        parent.addView(line, space(-1, dp(1), 16, 8));
    }

    private LinearLayout.LayoutParams space(int width, int height, int top, int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(width, height);
        lp.topMargin = dp(top);
        lp.bottomMargin = dp(bottom);
        return lp;
    }

    private Button button(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(14);
        button.setTextColor(0xffFFFFFF);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(12), dp(8), dp(12), dp(8));
        button.setStateListAnimator(null);
        CloudUi.pressable(button, CloudUi.BLUE, 16);
        button.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(48)));
        return button;
    }

    private void secondary(Button button) {
        button.setTextColor(CloudUi.BLUE);
        CloudUi.pressable(button, CloudUi.PALE, 16);
    }

    private Switch option(String label, String key) {
        Switch toggle = new Switch(this);
        toggle.setText(label);
        toggle.setTextColor(CloudUi.INK);
        toggle.setTextSize(14);
        toggle.setSwitchPadding(dp(16));
        toggle.setThumbTintList(new android.content.res.ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}}, new int[]{CloudUi.BLUE, 0xffFFFFFF}));
        toggle.setTrackTintList(new android.content.res.ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}}, new int[]{0xffB9D5F8, 0xffCFD9E7}));
        toggle.setChecked(true);
        toggle.setEnabled(false);
        toggle.setOnCheckedChangeListener((view, enabled) -> {
            if (bindingOptions) return;
            Bundle params = new Bundle();
            params.putBoolean(key, enabled);
            toggle.setEnabled(false);
            IslandRuntime.submit(this, "presentation", params, reply -> {
                if (isDestroyed()) return;
                if (reply.containsKey("error")) {
                    bindingOptions = true;
                    toggle.setChecked(runtimeState.getBoolean(key, true));
                    bindingOptions = false;
                }
                toggle.setEnabled(true);
                acceptState(reply);
            });
        });
        return toggle;
    }

    private String durationLabel() {
        int seconds = runtimeState.getInt("seconds", IslandLifetime.DEFAULT_SECONDS);
        return "上岛停留时长：" + (seconds == 0 ? "不自动下岛" : seconds + " 秒");
    }

    private static final class AppRow {
        final String label, pkg, query;
        final ApplicationInfo info;
        AppRow(String name, ApplicationInfo app) {
            label = name; pkg = app.packageName; info = app;
            query = (label + " " + pkg).toLowerCase(Locale.ROOT);
        }
    }

    private void loadApps() {
        worker.execute(() -> {
            Bundle state;
            try { state = IslandRuntime.call(this, "status", null); }
            catch (RuntimeException error) { state = new Bundle(); }
            ArrayList<String> stored = state.getStringArrayList("packages");
            PackageManager pm = getPackageManager();
            List<AppRow> rows = new ArrayList<>();
            for (ApplicationInfo info : pm.getInstalledApplications(0)) {
                if (info.packageName.equals(getPackageName())) continue;
                rows.add(new AppRow(String.valueOf(info.loadLabel(pm)), info));
            }
            Set<String> saved = stored == null ? Set.of() : new HashSet<>(stored);
            java.text.Collator collator = java.text.Collator.getInstance(Locale.CHINA);
            rows.sort((a, b) -> {
                int pinned = Boolean.compare(saved.contains(b.pkg), saved.contains(a.pkg));
                return pinned != 0 ? pinned : collator.compare(a.label, b.label);
            });
            main.post(() -> {
                if (isDestroyed()) return;
                ArrayList<String> draft = restoredUi == null ? null : restoredUi.getStringArrayList("draft_selection");
                selected.addAll(draft == null ? saved : draft);
                apps.addAll(rows);
                appsLoaded = true;
                filterApps();
            });
        });
    }

    private void filterApps() {
        if (!appsLoaded) return;
        String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        filtered.clear();
        for (AppRow row : apps) if (row.query.contains(query)) filtered.add(row);
        count.setText(filtered.size() + " 个应用 · 已选 " + selected.size());
        adapter.notifyDataSetChanged();
    }

    private void saveSelection() {
        if (!appsLoaded) return;
        Bundle params = new Bundle();
        params.putStringArrayList("packages", new ArrayList<>(selected));
        IslandRuntime.submit(this, "selection", params, reply -> {
            if (isDestroyed()) return;
            acceptState(reply);
            android.widget.Toast.makeText(this, reply.containsKey("error") ? reply.getString("error")
                    : "已保存 " + selected.size() + " 个应用", android.widget.Toast.LENGTH_SHORT).show();
        });
    }

    private final class AppAdapter extends android.widget.BaseAdapter {
        @Override public int getCount() { return filtered.size(); }
        @Override public Object getItem(int position) { return filtered.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View reuse, android.view.ViewGroup parent) {
            AppView holder;
            if (reuse == null) {
                LinearLayout outer = CloudUi.column(MainActivity.this);
                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(16), dp(16), dp(14), dp(16));
                CloudUi.pressable(row, 0xffFFFFFF, 22);
                outer.addView(row, space(-1, -2, 0, 8));
                holder = new AppView();
                holder.icon = new android.widget.ImageView(MainActivity.this);
                row.addView(holder.icon, new LinearLayout.LayoutParams(dp(44), dp(44)));
                LinearLayout words = CloudUi.column(MainActivity.this);
                LinearLayout.LayoutParams wordsLp = new LinearLayout.LayoutParams(0, -2, 1);
                wordsLp.setMarginStart(dp(14)); wordsLp.setMarginEnd(dp(8));
                row.addView(words, wordsLp);
                holder.title = CloudUi.text(MainActivity.this, "", 15, CloudUi.INK, true);
                holder.title.setMaxLines(1); holder.title.setEllipsize(android.text.TextUtils.TruncateAt.END);
                words.addView(holder.title);
                holder.pkg = CloudUi.text(MainActivity.this, "", 11, CloudUi.MUTED, false);
                holder.pkg.setMaxLines(1); holder.pkg.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                words.addView(holder.pkg, space(-1, -2, 5, 0));
                holder.check = new CheckBox(MainActivity.this);
                holder.check.setButtonTintList(android.content.res.ColorStateList.valueOf(CloudUi.BLUE));
                holder.check.setClickable(false); holder.check.setFocusable(false);
                holder.check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                row.addView(holder.check, new LinearLayout.LayoutParams(dp(32), dp(40)));
                holder.row = row;
                outer.setTag(holder);
                reuse = outer;
            } else holder = (AppView) reuse.getTag();
            AppRow app = filtered.get(position);
            holder.title.setText(app.label); holder.pkg.setText(app.pkg);
            holder.check.setChecked(selected.contains(app.pkg));
            holder.row.setContentDescription(app.label + (holder.check.isChecked() ? "，已选择" : "，未选择"));
            AppView bound = holder;
            holder.row.setOnClickListener(v -> {
                if (!selected.remove(app.pkg)) selected.add(app.pkg);
                bound.check.setChecked(selected.contains(app.pkg));
                bound.row.setContentDescription(app.label + (bound.check.isChecked() ? "，已选择" : "，未选择"));
                count.setText(filtered.size() + " 个应用 · 已选 " + selected.size() + " · 待保存");
            });
            holder.icon.setTag(app.pkg);
            holder.icon.setImageDrawable(appIcons.get(app.pkg));
            if (appIcons.get(app.pkg) == null) worker.execute(() -> {
                android.graphics.drawable.Drawable icon;
                try { icon = app.info.loadIcon(getPackageManager()); } catch (RuntimeException error) { return; }
                appIcons.put(app.pkg, icon);
                main.post(() -> {
                    if (!isDestroyed() && app.pkg.equals(bound.icon.getTag())) bound.icon.setImageDrawable(icon);
                });
            });
            return reuse;
        }
    }

    private static final class AppView {
        android.widget.ImageView icon;
        TextView title, pkg;
        CheckBox check;
        View row;
    }

    private void refreshPermissions() {
        if (permissionSummary == null) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        boolean listener = nm.isNotificationListenerAccessGranted(new ComponentName(this, IslandNotificationListener.class));
        boolean notifications = nm.areNotificationsEnabled();
        boolean live = Build.VERSION.SDK_INT >= 36 && nm.canPostPromotedNotifications();
        access.setVisibility(listener ? View.GONE : View.VISIBLE);
        notificationPermission.setVisibility(notifications ? View.GONE : View.VISIBLE);
        promoted.setVisibility(live ? View.GONE : View.VISIBLE);
        usageAccess.setVisibility(IslandOpenedSources.allowed(this) ? View.GONE : View.VISIBLE);
        boolean connected = runtimeState.getBoolean("connected");
        boolean presenting = runtimeState.getBoolean("presenting");
        boolean root = runtimeState.getBoolean("root_enabled");
        heroTitle.setText(connected ? (presenting ? "消息已上岛" : "随时待命") : "等待连接");
        heroSubtitle.setText(connected ? "通知监测已连接 · ColorOS 17" : "请在设置中检查通知访问权限");
        serviceState.setText("消息监测     " + (connected ? "独立运行中" : "等待连接")
                + "\n流体云         " + (presenting ? "正在显示通知" : "等待新消息"));
        rootSummary.setText("Root 保活  " + (root ? "已启用" : "未启用") + "  ·  "
                + (getSystemService(android.os.PowerManager.class).isIgnoringBatteryOptimizations(getPackageName()) ? "电池优化已豁免" : "电池优化未豁免"));
        if (!rootOperationPending) {
            rootStatus.setText(runtimeState.getString("root_status", "正在读取已保存状态…")
                    + "\n仅保护云漾的通知监测服务");
        }
        permissionSummary.setText("通知访问  " + (listener ? "已授权" : "未开启") + "  ·  通知权限  " + (notifications ? "已开启" : "未开启")
                + "\nLive Update  " + (live ? "可用" : "未开启") + "  ·  自动下岛识别  " + (IslandOpenedSources.allowed(this) ? "已授权" : "未开启"));
        ArrayList<String> packages = runtimeState.getStringArrayList("packages");
        selectionSummary.setText((packages == null ? 0 : packages.size()) + " 个");
        int seconds = runtimeState.getInt("seconds", IslandLifetime.DEFAULT_SECONDS);
        durationSummary.setText(seconds == 0 ? "不限时" : seconds + " 秒");
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        refreshPermissions();
        queryRuntime("refresh", null);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void openNotificationSettings() {
        startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
    }

    private void openPromotedSettings() {
        if (Build.VERSION.SDK_INT >= 36) {
            try {
                startActivity(new Intent("android.settings.MANAGE_APP_PROMOTED_NOTIFICATIONS")
                        .setData(Uri.parse("package:" + getPackageName())));
                return;
            } catch (android.content.ActivityNotFoundException ignored) {}
        }
        openNotificationSettings();
    }

    private void rootCommand(String method) {
        if (rootOperationPending) return;
        rootOperationPending = true;
        enableRoot.setEnabled(false);
        disableRoot.setEnabled(false);
        rootStatus.setText("root_enable".equals(method) ? "正在请求授权并启动保活…" : "正在停止保活…");
        Bundle params = new Bundle();
        rootRequestId = java.util.UUID.randomUUID().toString();
        String requestId = rootRequestId;
        params.putString("operation_id", requestId);
        main.removeCallbacks(rootTimeout);
        main.postDelayed(rootTimeout, 45000);
        params.putParcelable("callback", new android.os.ResultReceiver(main) {
            @Override protected void onReceiveResult(int code, Bundle state) {
                if (isDestroyed() || !requestId.equals(rootRequestId)) return;
                rootOperationPending = false;
                main.removeCallbacks(rootTimeout);
                acceptState(state);
                android.widget.Toast.makeText(MainActivity.this,
                        code == 1 ? ("root_enable".equals(method) ? "Root 保活已启用" : "Root 保活已停止")
                                : "Root 操作失败，请检查 KernelSU 授权", android.widget.Toast.LENGTH_SHORT).show();
            }
        });
        IslandRuntime.submit(this, method, params, reply -> {
            if (isDestroyed() || !requestId.equals(rootRequestId)) return;
            if (reply.containsKey("error")) {
                rootOperationPending = false;
                main.removeCallbacks(rootTimeout);
                enableRoot.setEnabled(true);
                disableRoot.setEnabled(true);
                rootStatus.setText("暂时无法连接监测服务，已保存的 Root 状态未更改");
                main.postDelayed(() -> { if (!isDestroyed()) queryRuntime("status", null); }, 1000);
            }
            acceptState(reply);
        });
    }

    private void queryRuntime(String method, Bundle params) {
        IslandRuntime.submit(this, method, params, this::acceptState);
    }

    private void acceptState(Bundle state) {
        if (isDestroyed()) return;
        if (state.containsKey("error")) {
            android.widget.Toast.makeText(this, state.getString("error"), android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if (rootOperationPending && rootRequestId != null
                && rootRequestId.equals(state.getString("root_operation_id"))
                && !state.getBoolean("root_operation_pending")) {
            rootOperationPending = false;
            main.removeCallbacks(rootTimeout);
        }
        runtimeState = state;
        enableRoot.setEnabled(!rootOperationPending);
        disableRoot.setEnabled(!rootOperationPending);
        if (duration != null) duration.setText(durationLabel());
        bindingOptions = true;
        manualStop.setChecked(state.getBoolean(IslandPresentation.MANUAL_STOP, true));
        marquee.setChecked(state.getBoolean(IslandPresentation.MARQUEE, true));
        manualStop.setEnabled(true);
        marquee.setEnabled(true);
        bindingOptions = false;
        refreshPermissions();
    }

}
