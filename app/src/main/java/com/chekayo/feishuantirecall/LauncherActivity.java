package com.chekayo.feishuantirecall;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;

/**
 * 模块桌面入口：全屏设置页（非对话框）。
 * 核心逻辑在飞书进程内运行；此处提供配置开关、数据迁移与系统入口。
 * 配置经 Config 与飞书对齐；同事档案副本见 ConfigProvider.putFile / ArchiveSync。
 */
public class LauncherActivity extends Activity {
    private static final String STATE_ACTIVE_TAB = "active_tab";

    private android.content.BroadcastReceiver configReceiver;
    private FrameLayout host;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean resumed;
    private boolean skipNextResumeRefresh;
    private int activeTab = 0;

    private final SettingsPanel.TabChangedListener tabChangedListener = new SettingsPanel.TabChangedListener() {
        @Override public void onTabChanged(int index) {
            activeTab = index;
        }
    };

    private final Runnable refresh = new Runnable() {
        @Override public void run() { if (resumed) refreshPage(); }
    };

    private int archivePullAttempt;
    private final Runnable pullArchives = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            ArchiveSync.requestPull(LauncherActivity.this);
            archivePullAttempt++;
            if (archivePullAttempt < 3) ui.postDelayed(this, archivePullAttempt == 1 ? 2000 : 5000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            activeTab = savedInstanceState.getInt(STATE_ACTIVE_TAB, 0);
        }
        getWindow().setStatusBarColor(Ui.bg(this));
        getWindow().setNavigationBarColor(Ui.bg(this));
        if (Build.VERSION.SDK_INT >= 23) getWindow().getDecorView().setSystemUiVisibility(
                Ui.isNight(this) ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                        | (Build.VERSION.SDK_INT >= 26 ? View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0));
        Config.setFilesDir(getFilesDir());
        Config.setContext(this);
        configReceiver = new android.content.BroadcastReceiver() {
            @Override public void onReceive(android.content.Context c, Intent i) {
                if (!Config.ACTION_SYNC.equals(i.getAction())) return;
                // 接收飞书进程的配置；只 apply，不在此重建 UI（避免 onReceive 里 removeAllViews 掉帧）
                if (Config.onSyncReceive(i.getStringExtra("json"))) {
                    ui.removeCallbacks(refresh);
                    ui.postDelayed(refresh, 250);
                }
            }
        };
        android.content.IntentFilter configFilter = new android.content.IntentFilter(Config.ACTION_SYNC);
        // 必须 EXPORTED：发送方是飞书进程（另一 UID），NOT_EXPORTED 会收不到 → 两份配置分叉
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(configReceiver, configFilter, android.content.Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(configReceiver, configFilter);
        }
        Config.loadAndAnnounce();

        host = new FrameLayout(this);
        host.setBackgroundColor(Ui.bg(this));
        if (Build.VERSION.SDK_INT >= 20) {
            host.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                    int left;
                    int top;
                    int right;
                    int bottom;
                    if (Build.VERSION.SDK_INT >= 30) {
                        android.graphics.Insets bars = insets.getInsets(
                                WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                        left = bars.left;
                        top = bars.top;
                        right = bars.right;
                        bottom = bars.bottom;
                    } else {
                        left = insets.getSystemWindowInsetLeft();
                        top = insets.getSystemWindowInsetTop();
                        right = insets.getSystemWindowInsetRight();
                        bottom = insets.getSystemWindowInsetBottom();
                    }
                    v.setPadding(left, top, right, bottom);
                    return insets;
                }
            });
        }
        host.addView(SettingsPanel.buildStandalonePage(this, activeTab, tabChangedListener),
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(host);
        if (Build.VERSION.SDK_INT >= 20) {
            host.requestApplyInsets();
        }
        skipNextResumeRefresh = true;
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_ACTIVE_TAB, activeTab);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        DataMigration.handleActivityResult(this, requestCode, resultCode, data);
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (skipNextResumeRefresh) {
            skipNextResumeRefresh = false;
        } else {
            refreshPage();
        }
        // 前台已具备接收条件；启动阶段被系统拦截的推送在这里重新拉取。
        archivePullAttempt = 0;
        ui.removeCallbacks(pullArchives);
        ui.post(pullArchives);
    }

    @Override protected void onPause() {
        resumed = false;
        ui.removeCallbacks(refresh);
        ui.removeCallbacks(pullArchives);
        super.onPause();
    }

    private void refreshPage() {
        // 从无障碍设置或配置同步返回后刷新界面，保持当前页签
        if (host == null) return;
        try {
            Config.load();
            host.removeAllViews();
            host.addView(SettingsPanel.buildStandalonePage(this, activeTab, tabChangedListener),
                    new FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        } catch (Throwable ignored) { }
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(refresh);
        ui.removeCallbacks(pullArchives);
        if (configReceiver != null) {
            try { unregisterReceiver(configReceiver); } catch (Throwable ignored) { }
            configReceiver = null;
        }
        super.onDestroy();
    }
}
