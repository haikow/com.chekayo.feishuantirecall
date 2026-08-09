package com.chekayo.feishuantirecall;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;

/**
 * 模块入口占位 Activity —— 仅用于给模块一个桌面图标 + launcher,
 * 让系统「无障碍 → 已下载的应用」列表能列出「fuck lark 组织巡游」开关。
 *
 * 本模块是无界面的 Xposed 模块(核心逻辑都在飞书进程内跑),这个 Activity 启动后
 * 直接跳转到系统无障碍设置页(便于用户开启 OrgWalkerService),然后立即 finish。
 */
public class LauncherActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable ignored) {}
        finish();
    }
}
