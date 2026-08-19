package com.chekayo.feishuantirecall;

import android.app.Activity;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 主页顶部更新横幅 —— 参考 XAuxiliary: 往宿主 MainActivity 内容区(android.R.id.content)顶部注入一条
 * 可点击 / 可关闭的横幅(LinearLayout: 文案占权重 + ✕ 关闭)。有新版时提示, 点击去更新, ✕ 记住忽略该版本。
 * 复用现有 version.json 检查(FuckLarkSettings.UPDATE_MIRRORS)。开关 Config.updatebanner(默认开)。
 */
public class UpdateBanner {

    static volatile boolean INSTALLED = false;
    static volatile boolean checkedThisRun = false;      // 一次进程只查一次, 避免每次 onResume 都拉网
    static final int BANNER_ID = 0x7ACC0001;             // 防重复添加

    public static void install(ClassLoader cl) {
        if (INSTALLED) return;
        INSTALLED = true;
        try {
            XposedHelpers.findAndHookMethod("com.ss.android.lark.main.app.MainActivity", cl,
                    "onResume", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        if (!Config.updatebanner || checkedThisRun) return;
                        checkedThisRun = true;
                        checkAsync((Activity) p.thisObject);
                    } catch (Throwable ignored) {}
                }
            });
            XposedBridge.log("[fucklark] 更新横幅: MainActivity.onResume 已 hook (进程 " + AntiRecall.currentProcessName() + ")");
        } catch (Throwable t) {
            XposedBridge.log("[fucklark] update banner install failed: " + t);
        }
    }

    static void checkAsync(final Activity act) {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                String json = fetch();
                if (json == null) return;
                try {
                    org.json.JSONObject o = new org.json.JSONObject(json);
                    final int vc = o.optInt("versionCode", 0);
                    final String vn = o.optString("versionName", "");
                    String dl = "";
                    org.json.JSONArray da = o.optJSONArray("downloads");
                    if (da != null && da.length() > 0) dl = da.optString(0, "");
                    if (dl.isEmpty()) dl = o.optString("download", "");
                    final String durl = dl;
                    if (vc <= AntiRecall.MODULE_VERSION_CODE) return;   // 不是新版
                    if (vc == Config.dismissedUpc) return;              // 已忽略该版本
                    act.runOnUiThread(new Runnable() {
                        @Override public void run() { showBanner(act, vc, vn, durl); }
                    });
                } catch (Throwable ignored) {}
            }
        }, "fucklark-upbanner");
        t.setDaemon(true); t.start();
    }

    static String fetch() {
        for (String u : FuckLarkSettings.UPDATE_MIRRORS) {
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(u).openConnection();
                c.setConnectTimeout(5000); c.setReadTimeout(8000);
                c.setRequestProperty("User-Agent", "fucklark");
                if (c.getResponseCode() != 200) { c.disconnect(); continue; }
                java.io.InputStream is = c.getInputStream();
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192]; int n;
                while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
                is.close(); c.disconnect();
                return new String(bo.toByteArray(), "UTF-8");
            } catch (Throwable ignore) { /* 换下一个镜像 */ }
        }
        return null;
    }

    static void showBanner(Activity act, final int vc, String vn, final String durl) {
        try {
            ViewGroup content = (ViewGroup) act.findViewById(android.R.id.content);
            if (content == null || content.findViewById(BANNER_ID) != null) return;   // 无内容/已存在

            LinearLayout bar = new LinearLayout(act);
            bar.setId(BANNER_ID);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            bar.setBackgroundColor(0xFF2B7FFF);
            bar.setPadding(dp(act, 14), statusBarH(act) + dp(act, 8), dp(act, 10), dp(act, 8));
            bar.setClickable(true);

            TextView tv = new TextView(act);
            tv.setText("🔄 fuck lark 有新版 v" + vn + "（当前 v" + AntiRecall.MODULE_VERSION + "）· 点击更新");
            tv.setTextColor(Color.WHITE);
            tv.setTextSize(14f);
            bar.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView close = new TextView(act);
            close.setText("✕");
            close.setTextColor(0xFFEAF2FF);
            close.setTextSize(16f);
            close.setPadding(dp(act, 14), 0, dp(act, 6), 0);
            bar.addView(close, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            bar.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (durl != null && !durl.isEmpty()) FuckLarkSettings.openUrl(v.getContext(), durl);
                }
            });
            close.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Config.setDismissed(vc);                       // 记住忽略该版本, 不再唠叨
                    ViewGroup barV = (ViewGroup) v.getParent();    // = bar
                    ViewGroup parent = barV != null ? (ViewGroup) barV.getParent() : null;  // = content
                    if (parent != null) parent.removeView(barV);
                }
            });

            content.addView(bar, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        } catch (Throwable t) {
            XposedBridge.log("[fucklark] show update banner failed: " + t);
        }
    }

    static int dp(Activity a, float v) { return (int) (v * a.getResources().getDisplayMetrics().density + 0.5f); }
    static int statusBarH(Activity a) {
        int id = a.getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? a.getResources().getDimensionPixelSize(id) : dp(a, 24);
    }
}
