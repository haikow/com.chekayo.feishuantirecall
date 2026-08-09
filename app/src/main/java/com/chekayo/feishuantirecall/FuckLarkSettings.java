package com.chekayo.feishuantirecall;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.graphics.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import org.json.JSONObject;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.ImageView;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.util.zip.ZipFile;
import java.util.zip.ZipEntry;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import android.text.Editable;
import android.text.TextWatcher;

import java.io.File;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * fuck lark 设置入口：hook 飞书设置页, 注入一行 "🐱 fuck lark 设置" → 弹自建开关面板。
 * 开关直接读写 Config(同进程), 改了即时生效。
 */
public class FuckLarkSettings implements IXposedHookLoadPackage {

    static final String PKG_FEISHU = "com.ss.android.lark";
    static final String PKG_LARK = "com.larksuite.suite";   // 国际版应用包名(其内部类仍沿用 com.ss.android.lark.* 历史前缀)
    static volatile String PKG = PKG_FEISHU;   // 运行时锁定当前目标应用包名(setClassName 用)
    static boolean isLarkFamily(String pkg) { return PKG_FEISHU.equals(pkg) || PKG_LARK.equals(pkg); }
    // 注: 国际版 dex 实测同样保留此类名(com.ss.android.lark.setting.page.function.SettingPageFragment), 故无需按包名分流。
    static final String SETTING_FRAGMENT = "com.ss.android.lark.setting.page.function.SettingPageFragment";
    static final String ROW_TAG = "fucklark_row";

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (!isLarkFamily(lpparam.packageName) && !AntiRecall.isLarkApp(lpparam.classLoader)) return;
        PKG = lpparam.packageName;   // 锁定当前应用包名(国际版=com.larksuite.suite; setClassName 第一参数用它)
        try {
            // 设置页 onResume 时注入(幂等)
            XposedHelpers.findAndHookMethod(SETTING_FRAGMENT, lpparam.classLoader, "onResume",
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        try { inject(param.thisObject); }
                        catch (Throwable t) { XposedBridge.log("[fucklark] inject err " + t); }
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log("[fucklark] hook SettingPageFragment 失败 " + t);
        }
    }

    static void inject(Object fragment) {
        Object v = XposedHelpers.callMethod(fragment, "getView");
        if (!(v instanceof View)) return;
        View root = (View) v;
        if (root.findViewWithTag(ROW_TAG) != null) return;   // 已注入(幂等)
        if (!(root instanceof ViewGroup)) return;
        final Context ctx = root.getContext();

        // 找滚动列表(RecyclerView 优先, 否则 ScrollView), 把行插到列表【上方】=标题栏下第一行
        View list = findScrollable((ViewGroup) root);
        ViewGroup parent; int idx;
        if (list != null && (list.getParent() instanceof ViewGroup)) {
            parent = (ViewGroup) list.getParent();
            idx = parent.indexOfChild(list);
        } else {
            parent = (ViewGroup) root; idx = 0;   // 兜底
        }

        TextView row = new TextView(ctx);
        row.setTag(ROW_TAG);
        row.setText("🐱  fuck lark 设置");
        row.setTextSize(16);
        row.setGravity(Gravity.CENTER);
        row.setTextColor(Color.parseColor("#3B9EFF"));
        row.setPadding(dp(ctx, 20), dp(ctx, 14), dp(ctx, 20), dp(ctx, 14));
        row.setBackgroundColor(Color.parseColor("#F21C1C1E"));  // 近不透明深色, 钉底部清晰可读
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { showPanel(ctx); }
        });
        String pcn = parent.getClass().getName();
        try {
            if (pcn.contains("ConstraintLayout")) {
                // ConstraintLayout: 无约束子 view 会落 (0,0) 盖住标题 -> 反射设约束贴顶部, 留出标题栏高度
                Class<?> lpc = Class.forName("androidx.constraintlayout.widget.ConstraintLayout$LayoutParams",
                        true, parent.getClass().getClassLoader());
                Object clp = lpc.getConstructor(int.class, int.class).newInstance(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lpc.getField("bottomToBottom").setInt(clp, 0);   // PARENT_ID=0, 钉底部
                lpc.getField("leftToLeft").setInt(clp, 0);
                lpc.getField("rightToRight").setInt(clp, 0);
                row.setLayoutParams((ViewGroup.LayoutParams) clp);
                parent.addView(row);
            } else {
                row.setLayoutParams(new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                parent.addView(row, idx);
            }
        } catch (Throwable t) {
            try { ((ViewGroup) root).addView(row, 0); } catch (Throwable ignored) {}
        }
        XposedBridge.log("[fucklark] 设置入口已注入 parent=" + pcn + " idx=" + idx);
    }

    // 深度优先找第一个 RecyclerView; 没有则找 ScrollView
    static View findScrollable(ViewGroup vg) {
        View sv = null;
        for (int i = 0; i < vg.getChildCount(); i++) {
            View c = vg.getChildAt(i);
            String cn = c.getClass().getName();
            if (cn.contains("RecyclerView")) return c;
            if (sv == null && cn.contains("ScrollView")) sv = c;
            if (c instanceof ViewGroup) {
                View r = findScrollable((ViewGroup) c);
                if (r != null && r.getClass().getName().contains("RecyclerView")) return r;
                if (sv == null && r != null) sv = r;
            }
        }
        return sv;
    }

    static void showPanel(final Context ctx) {
        Config.load();
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(ctx, 20);
        box.setPadding(p, p, p, p);

        final boolean tampered = (AntiRecall.TAMPER == 2);
        if (tampered) {
            TextView warn = new TextView(ctx);
            warn.setPadding(0, 0, 0, dp(ctx, 10));
            warn.setTextSize(15);
            warn.setTextColor(Color.parseColor("#FF3B30"));
            warn.setText("⚠️ 本模块签名异常，疑似被篡改/重打包，核心功能已禁用。\n请从作者官方渠道重新下载安装。");
            box.addView(warn);
        }

        // ── 消息 & 隐私 ──
        box.addView(groupHeader(ctx, "消息 · 隐私"));
        box.addView(switchRow(ctx, "防撤回",
                "被撤回的消息原文继续显示，含你从未打开过的聊天", Config.antirecall, new OnToggle() {
            public void on(boolean b) {
                Config.set("antirecall", b);
                try { AntiRecall.nativeSetRecall(b); } catch (Throwable ignored) {}
            }
        }));
        box.addView(switchRow(ctx, "后台消息存档（防后台撤回）",
                "后台/离线时对方撤回，服务器不下发原文、防撤回救不了。本功能从通知回捞【被撤回】那条的原文存档（普通消息不记）。\n需飞书开启「消息预览」，仅文本可靠。默认关。", Config.notifarchive, new OnToggle() {
            public void on(boolean b) { Config.set("notifarchive", b); }
        }));
        box.addView(switchRow(ctx, "防对方已读",
                "只看不回=未读，回复后才标记已读（实验）", Config.antiread, new OnToggle() {
            public void on(boolean b) { Config.set("antiread", b); }
        }));

        // ── 群聊记录 ──
        box.addView(groupHeader(ctx, "群聊记录"));
        box.addView(switchRow(ctx, "保留被踢群聊天记录",
                "被移出群时本地记录不清除，并自动导出（实验）", Config.keepkicked, new OnToggle() {
            public void on(boolean b) {
                Config.set("keepkicked", b);
                try { AntiRecall.nativeSetKeepKicked(b); } catch (Throwable ignored) {}
            }
        }));
        box.addView(switchRow(ctx, "退群 / 被移出提醒",
                "别人退群/被移出时群内持久显示 + Toast（实验）", Config.leavenotify, new OnToggle() {
            public void on(boolean b) {
                Config.set("leavenotify", b);
                try { AntiRecall.nativeSetLeaveNotify(b); } catch (Throwable ignored) {}
            }
        }));

        // ── 同事 ──
        box.addView(groupHeader(ctx, "同事"));
        box.addView(switchRow(ctx, "离职同事统计",
                "同事离职前于本地快照，累计归档离职名单", Config.resign, new OnToggle() {
            public void on(boolean b) { Config.set("resign", b); }
        }));
        // 组织架构自动巡游: 无障碍服务,跑在模块自己的进程(非飞书进程)。做成【开关行】与其它功能一致——
        // 开关状态 = 系统「无障碍」里本服务是否已开启(读 Settings.Secure,跨进程可靠)。拨动开关跳系统
        // 无障碍页去开/关(程序无法代开关无障碍)。仅在已开启时,才在下方展开「停止/从头开始」子项。
        final boolean walkerOn = walkerBound(ctx);
        box.addView(switchRow(ctx, "🧭 组织架构自动巡游",
                "进入飞书【通讯录→组织内联系人】时自动走遍全部部门，触发全员懒加载归档。\n"
                + (walkerOn
                   ? "已开启：进组织架构页即自动巡游。停止后点下方『继续巡游』再进该页即从断点接着走。"
                   : "⚠️ 未开启：拨动开关 → 系统无障碍 → 找「fuck lark 组织巡游」打开。"),
                walkerOn, new OnToggle() {
            @Override public void on(boolean b) {
                // 无论开/关都跳系统无障碍页(程序不能直接开关无障碍服务);回来重开面板即刷新状态。
                try {
                    Intent i = new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(i);
                } catch (Throwable t) {
                    android.widget.Toast.makeText(ctx, "打开无障碍设置失败: " + t, android.widget.Toast.LENGTH_LONG).show();
                }
            }
        }));
        // 子项(缩进): 仅在巡游已开启时显示——停止 / 从头开始。
        if (walkerOn) {
            // 停止巡游: 发【显式广播】给巡游服务(它跑在模块进程,与飞书进程不同 UID —— 旧的写
            // walker_stop.flag 方式被 Android 10+ SELinux 拒读,故改广播)。
            View stopRow = actionRow(ctx, "⏹ 停止正在进行的巡游",
                    "巡游中点此暂停（走完当前部门即退出，且不再自动重走）。\n💡 更方便：巡游时直接按【音量+ / 音量-】即可停止。\n已走完的部门记为断点，点下方『继续巡游』再进组织架构页即接着走。",
                    0xFFFF3B30, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    try {
                        android.content.Intent bi = new android.content.Intent("com.chekayo.feishuantirecall.WALK_STOP");
                        bi.setPackage("com.chekayo.feishuantirecall");
                        ctx.sendBroadcast(bi);
                        android.widget.Toast.makeText(ctx, "已请求停止巡游，走完当前部门即退出…", android.widget.Toast.LENGTH_SHORT).show();
                    } catch (Throwable t) {
                        android.widget.Toast.makeText(ctx, "停止失败: " + t, android.widget.Toast.LENGTH_LONG).show();
                    }
                }
            });
            indentSubRow(ctx, stopRow); box.addView(stopRow);
            // 继续巡游: 解除暂停(ACTION_RESUME)。点完再进【通讯录→组织内联系人】页即从断点接着走。
            View resumeRow = actionRow(ctx, "▶️ 继续巡游（从断点续跑）",
                    "点『停止』暂停后，点此解除暂停，再进入组织架构页即从上次中断的部门接着走。",
                    0xFF34C759, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    try {
                        android.content.Intent bi = new android.content.Intent("com.chekayo.feishuantirecall.WALK_RESUME");
                        bi.setPackage("com.chekayo.feishuantirecall");
                        ctx.sendBroadcast(bi);
                        android.widget.Toast.makeText(ctx, "已恢复，进入组织架构页即从断点续跑", android.widget.Toast.LENGTH_SHORT).show();
                    } catch (Throwable t) {
                        android.widget.Toast.makeText(ctx, "继续失败: " + t, android.widget.Toast.LENGTH_LONG).show();
                    }
                }
            });
            indentSubRow(ctx, resumeRow); box.addView(resumeRow);
            // 从头开始巡游: 清空断点(visited),下次进组织页重走全树。发广播让巡游服务清内存断点。
            View resetRow = actionRow(ctx, "🔄 从头开始巡游（清除断点）",
                    "巡游默认从上次中断处续跑。点此清除断点，下次进组织架构页重新走遍全树。",
                    0xFF8E8E93, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    try {
                        android.content.Intent bi = new android.content.Intent("com.chekayo.feishuantirecall.WALK_RESET");
                        bi.setPackage("com.chekayo.feishuantirecall");
                        ctx.sendBroadcast(bi);
                        android.widget.Toast.makeText(ctx, "断点已清除，下次进组织架构页将从总部重新开始", android.widget.Toast.LENGTH_SHORT).show();
                    } catch (Throwable t) {
                        android.widget.Toast.makeText(ctx, "清除失败: " + t, android.widget.Toast.LENGTH_LONG).show();
                    }
                }
            });
            indentSubRow(ctx, resetRow); box.addView(resetRow);
        }

        // ── 其他 ──
        box.addView(groupHeader(ctx, "其他"));
        box.addView(switchRow(ctx, "诊断日志",
                "排查『防撤回不生效』用，默认不写任何文件", Config.diaglog, new OnToggle() {
            public void on(boolean b) {
                Config.set("diaglog", b);
                try { AntiRecall.nativeSetDiag(b); } catch (Throwable ignored) {}
            }
        }));

        // ── 更多: 数据查看/关于类动作全部收进二级窗口, 主面板只留开关(参考 微信X/TIMTool 的动作隔离) ──
        box.addView(groupHeader(ctx, "更多"));
        TextView tools = new TextView(ctx);
        tools.setPadding(0, dp(ctx, 10), 0, dp(ctx, 10));
        tools.setTextSize(16);
        tools.setTextColor(0xFF3B9EFF);
        tools.setText("🗂  记录与工具  ›");
        tools.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showToolsPanel(ctx, tampered); }
        });
        box.addView(tools);

        // 版本 footer
        TextView ver = new TextView(ctx);
        ver.setPadding(0, dp(ctx, 16), 0, 0);
        ver.setTextSize(12);
        ver.setTextColor(isNight(ctx) ? 0xFF888888 : 0xFF999999);
        ver.setText("fuck lark  v" + AntiRecall.MODULE_VERSION);
        box.addView(ver);

        ScrollView sv = new ScrollView(ctx);
        sv.addView(box);
        new AlertDialog.Builder(ctx)
                .setTitle("🐱 fuck lark 设置")
                .setView(sv)
                .setPositiveButton("完成", null)
                .show();

        // 参考锤锤: 每次飞书启动后, 首次打开设置面板时弹一次赞赏(本会话不再弹, 重启才再来)。被篡改则不弹。
        if (!tampered && !donateShownThisSession) {
            donateShownThisSession = true;
            showReward(ctx);
        }

        // 首次打开设置面板时静默检查一次更新(有新版/公告才弹), 本会话不再自动查。
        if (!updateCheckedThisSession) {
            updateCheckedThisSession = true;
            checkUpdate(ctx, true);
        }
    }

    // ── 二级窗口: 记录与工具(把数据查看/关于类动作从开关面板里隔离出来) ──
    static void showToolsPanel(final Context ctx, final boolean tampered) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(ctx, 20);
        box.setPadding(p, p, p, p);

        box.addView(groupHeader(ctx, "记录 / 列表"));
        box.addView(actionRow(ctx, "📋 离职名单（" + resignCount() + " 人）", 0xFF3B9EFF, new View.OnClickListener() {
            @Override public void onClick(View v) { showResignedList(ctx); }
        }));
        box.addView(actionRow(ctx, "📇 全员档案（" + profilesCount() + " 人）", 0xFF3B9EFF, new View.OnClickListener() {
            @Override public void onClick(View v) { showAllProfiles(ctx); }
        }));
        box.addView(actionRow(ctx, "📤 被踢群聊天记录（导出）", 0xFF3B9EFF, new View.OnClickListener() {
            @Override public void onClick(View v) { showKickedExports(ctx); }
        }));
        box.addView(actionRow(ctx, "📨 后台消息存档（" + NotifArchive.count() + " 条）", 0xFF3B9EFF, new View.OnClickListener() {
            @Override public void onClick(View v) { showNotifArchive(ctx); }
        }));
        box.addView(actionRow(ctx, "👋 退群 / 移除记录", 0xFF3B9EFF, new View.OnClickListener() {
            @Override public void onClick(View v) { showLeaveLog(ctx); }
        }));

        box.addView(groupHeader(ctx, "关于"));
        box.addView(actionRow(ctx, "🔄 检查更新", 0xFF3B9EFF, new View.OnClickListener() {
            @Override public void onClick(View v) { checkUpdate(ctx, false); }
        }));
        box.addView(actionRow(ctx, "🧾 查看诊断日志", 0xFF3B9EFF, new View.OnClickListener() {
            @Override public void onClick(View v) { showDiagLog(ctx); }
        }));
        box.addView(actionRow(ctx, "💬 加入讨论群（Telegram）", 0xFF3B9EFF, new View.OnClickListener() {
            @Override public void onClick(View v) { openUrl(ctx, "https://t.me/fucklark"); }
        }));
        if (!tampered) {
            box.addView(actionRow(ctx, "❤️ 赞赏作者（微信）", 0xFFFF8C69, new View.OnClickListener() {
                @Override public void onClick(View v) { showReward(ctx); }
            }));
        }

        ScrollView sv = new ScrollView(ctx);
        sv.addView(box);
        new AlertDialog.Builder(ctx)
                .setTitle("🗂 记录与工具")
                .setView(sv)
                .setPositiveButton("返回", null)
                .show();
    }

    // 二级窗口里的动作行: 标题 + 右侧 › 指示
    static View actionRow(Context ctx, String text, int color, View.OnClickListener cl) {
        TextView tv = new TextView(ctx);
        tv.setPadding(0, dp(ctx, 12), 0, dp(ctx, 12));
        tv.setTextSize(16);
        tv.setTextColor(color);
        tv.setText(text + "   ›");
        tv.setOnClickListener(cl);
        return tv;
    }

    // 带副标题(灰字说明)的动作行: 标题 + 下方说明 | 右侧 ›(用于组织巡游这种需解释的入口)
    static View actionRow(Context ctx, String text, String sub, int color, View.OnClickListener cl) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(ctx, 10), 0, dp(ctx, 10));
        row.setOnClickListener(cl);
        TextView tv = new TextView(ctx);
        tv.setText(text + "   ›");
        tv.setTextSize(16);
        tv.setTextColor(color);
        row.addView(tv);
        if (sub != null && sub.length() > 0) {
            TextView st = new TextView(ctx);
            st.setText(sub);
            st.setTextSize(12);
            st.setTextColor(isNight(ctx) ? 0xFF9AA0A6 : 0xFF888888);
            st.setPadding(0, dp(ctx, 3), 0, 0);
            row.addView(st);
        }
        return row;
    }

    // 把一行缩进成「子项」样式(左侧留白 + 左边一道竖线感的内边距),用于开关行下挂的二级操作。
    static void indentSubRow(Context ctx, View row) {
        row.setPadding(dp(ctx, 22), dp(ctx, 10), 0, dp(ctx, 10));
    }

    // 组织巡游服务当前是否已在系统无障碍里开启并绑定(从 secure settings 读)
    static boolean walkerBound(Context ctx) {
        try {
            String s = android.provider.Settings.Secure.getString(
                    ctx.getContentResolver(), android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return s != null && s.indexOf("OrgWalkerService") >= 0;
        } catch (Throwable t) { return false; }
    }

    static volatile boolean updateCheckedThisSession = false;

    // ── 应用内更新检查(多镜像只读拉 version.json; 不采集、不上传) ──
    //   silent=true: 首次打开面板自动查, 仅在"有新版或有公告"时弹; false: 手动查, 无更新也提示。
    static final String[] UPDATE_MIRRORS = {
        "https://cdn.jsdelivr.net/gh/haikow/com.chekayo.feishuantirecall@main/version.json",
        "https://raw.githubusercontent.com/haikow/com.chekayo.feishuantirecall/main/version.json"
    };

    static void checkUpdate(final Context ctx, final boolean silent) {
        if (!silent) android.widget.Toast.makeText(ctx, "检查更新中…", android.widget.Toast.LENGTH_SHORT).show();
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                String json = null;
                for (String u : UPDATE_MIRRORS) {
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
                        json = new String(bo.toByteArray(), "UTF-8");
                        break;
                    } catch (Throwable ignore) { /* 换下一个镜像 */ }
                }
                final String result = json;
                new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                    @Override public void run() { showUpdateResult(ctx, result, silent); }
                });
            }
        }, "fucklark-update");
        t.setDaemon(true); t.start();
    }

    static void showUpdateResult(final Context ctx, String json, boolean silent) {
        try {
            if (json == null) { if (!silent) android.widget.Toast.makeText(ctx, "检查更新失败（网络不可达）", android.widget.Toast.LENGTH_LONG).show(); return; }
            org.json.JSONObject o = new org.json.JSONObject(json);
            int vc = o.optInt("versionCode", 0);
            String vn = o.optString("versionName", "");
            String notice = o.optString("notice", "").trim();
            String changelog = o.optString("changelog", "").trim();
            String channel = o.optString("channel", "").trim();
            String dl = "";
            org.json.JSONArray da = o.optJSONArray("downloads");
            if (da != null && da.length() > 0) dl = da.optString(0, "");
            if (dl.isEmpty()) dl = o.optString("download", "");

            boolean newer = vc > AntiRecall.MODULE_VERSION_CODE;
            boolean hasNotice = !notice.isEmpty();
            if (!newer && !hasNotice) {
                if (!silent) android.widget.Toast.makeText(ctx, "已是最新（v" + AntiRecall.MODULE_VERSION + "）", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            StringBuilder msg = new StringBuilder();
            if (hasNotice) msg.append("📢 ").append(notice).append("\n\n");
            if (newer) {
                msg.append("发现新版 v").append(vn).append("（当前 v").append(AntiRecall.MODULE_VERSION).append("）");
                if (!changelog.isEmpty()) msg.append("\n\n").append(changelog);
            }
            AlertDialog.Builder b = new AlertDialog.Builder(ctx)
                    .setTitle(newer ? "🔄 有新版本" : "📢 公告")
                    .setMessage(msg.toString());
            final String durl = dl, churl = channel;
            if (newer && !dl.isEmpty())
                b.setPositiveButton("去下载", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) { openUrl(ctx, durl); }
                });
            if (!channel.isEmpty())
                b.setNeutralButton("讨论群", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) { openUrl(ctx, churl); }
                });
            b.setNegativeButton("关闭", null).show();
        } catch (Throwable t) {
            if (!silent) android.widget.Toast.makeText(ctx, "更新信息解析失败", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    // 诊断日志弹窗: 显示 fucklark_log.txt 尾部 + 复制到剪贴板 + 清空。
    // 终端用户反馈“防撤回不生效”时, 让其点开→复制→发作者, 一看便知(版本/安装/拦截)。
    static void showDiagLog(final Context ctx) {
        final String log = Diag.read();
        // 只显示尾部(最近的更相关), 避免弹窗过长
        String shown = log;
        int MAX = 12000;
        if (shown.length() > MAX) shown = "...(仅显示最近部分, 复制可得完整)...\n" + shown.substring(shown.length() - MAX);

        final TextView tv = new TextView(ctx);
        tv.setText(shown);
        tv.setTextSize(11);
        tv.setTextColor(Color.parseColor("#DDDDDD"));
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        int p = dp(ctx, 12);
        tv.setPadding(p, p, p, p);
        tv.setTextIsSelectable(true);
        ScrollView sv = new ScrollView(ctx);
        sv.addView(tv);

        new AlertDialog.Builder(ctx)
                .setTitle("🧾 诊断日志")
                .setView(sv)
                .setPositiveButton("复制到剪贴板", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        try {
                            String header = "【fuck lark 诊断日志】\n";
                            android.content.ClipboardManager cm =
                                    (android.content.ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("fucklark_log", header + log));
                            android.widget.Toast.makeText(ctx, "已复制，粘贴到聊天发给作者即可", android.widget.Toast.LENGTH_LONG).show();
                        } catch (Throwable t) {
                            android.widget.Toast.makeText(ctx, "复制失败: " + t, android.widget.Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNeutralButton("清空", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        Diag.clear();
                        android.widget.Toast.makeText(ctx, "日志已清空", android.widget.Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    // 后台消息存档: 读 notif_archive.txt(每行 "时间\t发送人\t正文"), 倒序显示 + 复制 + 清空。
    // 救"后台/离线被撤回"的消息 —— 撤回后服务器不下发原文, 但原文弹过通知, 在这里能翻到。
    static void showNotifArchive(final Context ctx) {
        final String content = NotifArchive.read();
        if (content.trim().isEmpty()) {
            new AlertDialog.Builder(ctx).setTitle("📨 后台消息存档")
                    .setMessage("暂无记录。\n\n开启「后台消息存档」开关后，后台/离线时【被撤回】的消息会在这里留底原文（普通消息不记）。\n\n前提：飞书通知里开着「消息预览」（否则通知没有正文，无从抓取）。")
                    .setPositiveButton("关闭", null).show();
            return;
        }
        String[] lines = content.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = lines.length - 1; i >= 0; i--) {   // 倒序: 最新在上
            String ln = lines[i].trim();
            if (ln.isEmpty()) continue;
            String[] c = ln.split("\t", 3);
            if (c.length >= 3)      sb.append(c[0]).append("  ·  ").append(c[1]).append("\n").append(c[2]).append("\n\n");
            else                    sb.append(ln.replace("\t", "  ·  ")).append("\n\n");
        }
        final TextView tv = new TextView(ctx);
        tv.setText(sb.toString());
        tv.setTextSize(13);
        tv.setTextColor(Color.parseColor("#DDDDDD"));
        int p = dp(ctx, 12);
        tv.setPadding(p, p, p, p);
        tv.setTextIsSelectable(true);
        ScrollView sv = new ScrollView(ctx);
        sv.addView(tv);
        new AlertDialog.Builder(ctx)
                .setTitle("📨 后台消息存档")
                .setView(sv)
                .setPositiveButton("复制全部", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        try {
                            android.content.ClipboardManager cm =
                                    (android.content.ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("notif_archive", content));
                            android.widget.Toast.makeText(ctx, "已复制", android.widget.Toast.LENGTH_SHORT).show();
                        } catch (Throwable t) {
                            android.widget.Toast.makeText(ctx, "复制失败: " + t, android.widget.Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNeutralButton("清空", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        NotifArchive.clear();
                        android.widget.Toast.makeText(ctx, "存档已清空", android.widget.Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    // 退群/被移除记录: 读 leave_log.txt(每行 "时间\t文案"), 倒序显示 + 复制 + 清空。
    static void showLeaveLog(final Context ctx) {
        final java.io.File f = new java.io.File("/data/data/" + PKG + "/files/leave_log.txt");
        String content = "";
        try { if (f.exists()) content = new String(Diag.readBytes(f), "UTF-8"); } catch (Throwable ignored) {}
        if (content.trim().isEmpty()) {
            new AlertDialog.Builder(ctx).setTitle("👋 退群/移除记录")
                    .setMessage("暂无记录。开启「退群提醒」开关后，有人退群/被移出你所在的群时会记到这里（并弹 Toast）。")
                    .setPositiveButton("关闭", null).show();
            return;
        }
        String[] lines = content.split("\n");
        StringBuilder sb = new StringBuilder();
        int cnt = 0;
        for (int i = lines.length - 1; i >= 0; i--) {   // 倒序: 最新在上
            String ln = lines[i].trim();
            if (ln.isEmpty()) continue;
            sb.append(ln.replace("\t", "  ·  ")).append("\n");
            cnt++;
        }
        final String full = content;
        final TextView tv = new TextView(ctx);
        tv.setText(sb.toString());
        tv.setTextSize(13);
        tv.setTextColor(Color.parseColor("#DDDDDD"));
        int p = dp(ctx, 12);
        tv.setPadding(p, p, p, p);
        tv.setTextIsSelectable(true);
        ScrollView sv = new ScrollView(ctx);
        sv.addView(tv);
        new AlertDialog.Builder(ctx)
                .setTitle("👋 退群/移除记录（" + cnt + "）")
                .setView(sv)
                .setPositiveButton("复制", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        try {
                            android.content.ClipboardManager cm =
                                    (android.content.ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("fucklark_leave", full));
                            android.widget.Toast.makeText(ctx, "已复制", android.widget.Toast.LENGTH_SHORT).show();
                        } catch (Throwable t) {}
                    }
                })
                .setNeutralButton("清空", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        try { f.delete(); } catch (Throwable t) {}
                        android.widget.Toast.makeText(ctx, "已清空", android.widget.Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    // 被踢群聊天记录: 列出 /data/data/PKG/files/kicked_*.txt, 点开看内容 + 复制 + 分享。
    static void showKickedExports(final Context ctx) {
        java.io.File dir = new java.io.File("/data/data/" + PKG + "/files");
        final java.io.File[] files = dir.listFiles(new java.io.FilenameFilter() {
            @Override public boolean accept(java.io.File d, String n) { return n.startsWith("kicked_") && n.endsWith(".txt"); }
        });
        if (files == null || files.length == 0) {
            new AlertDialog.Builder(ctx).setTitle("📤 被踢群聊天记录")
                    .setMessage("暂无记录。被移出群聊时（需开启「保留被踢群聊天记录」开关）会自动导出到这里。")
                    .setPositiveButton("好", null).show();
            return;
        }
        java.util.Arrays.sort(files, new Comparator<java.io.File>() {
            @Override public int compare(java.io.File a, java.io.File b) { return Long.compare(b.lastModified(), a.lastModified()); }
        });
        // 预读每个群的 群名 + 全文(小文件, 供搜索群名/消息内容)
        final String[] gname = new String[files.length];
        final String[] lower = new String[files.length];   // 群名+内容 小写, 供匹配
        for (int i = 0; i < files.length; i++) {
            String label = files[i].getName(), content = "";
            try {
                content = new String(Diag.readBytes(files[i]), "UTF-8");
                int nl = content.indexOf('\n');
                String first = nl > 0 ? content.substring(0, nl) : content;
                if (first.startsWith("群: ")) label = first.substring(3).trim();
            } catch (Throwable t) {}
            gname[i] = label;
            lower[i] = (label + " " + content).toLowerCase();
        }

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(ctx, 14);
        root.setPadding(p, p, p, dp(ctx, 6));
        final EditText search = new EditText(ctx);
        search.setHint("🔍 搜索群名 / 消息内容");
        search.setTextSize(14);
        search.setSingleLine(true);
        root.addView(search);
        final LinearLayout listBox = new LinearLayout(ctx);
        listBox.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(ctx);
        sv.addView(listBox);
        LinearLayout.LayoutParams svlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 380));
        sv.setLayoutParams(svlp);
        root.addView(sv);

        final AlertDialog dlg = new AlertDialog.Builder(ctx)
                .setTitle("📤 被踢群聊天记录（" + files.length + "）")
                .setView(root).setNegativeButton("关闭", null).create();

        final Runnable[] repop = new Runnable[1];
        repop[0] = new Runnable() {
            @Override public void run() {
                String q = search.getText().toString().trim().toLowerCase();
                listBox.removeAllViews();
                int shown = 0;
                for (int i = 0; i < files.length; i++) {
                    if (q.length() > 0 && lower[i].indexOf(q) < 0) continue;
                    final java.io.File f = files[i];
                    TextView row = new TextView(ctx);
                    row.setText("💬 " + gname[i] + "   (" + (f.length() / 1024 + 1) + "KB)");
                    row.setTextSize(16);
                    row.setTextColor(Color.parseColor("#3B9EFF"));
                    row.setPadding(0, dp(ctx, 12), 0, dp(ctx, 12));
                    row.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) { dlg.dismiss(); showKickedFile(ctx, f); }
                    });
                    listBox.addView(row);
                    shown++;
                }
                if (shown == 0) {
                    TextView e = new TextView(ctx); e.setText("无匹配"); e.setTextColor(Color.parseColor("#9AA0A6"));
                    e.setPadding(0, dp(ctx, 12), 0, 0); listBox.addView(e);
                }
            }
        };
        repop[0].run();
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { repop[0].run(); }
            @Override public void afterTextChanged(Editable s) {}
        });
        dlg.show();
    }

    static void showKickedFile(final Context ctx, final java.io.File file) {
        String content;
        try { content = new String(Diag.readBytes(file), "UTF-8"); }
        catch (Throwable t) { content = "读取失败: " + t; }
        String title = file.getName();
        final StringBuilder share = new StringBuilder();

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(ctx, 10);
        box.setPadding(pad, pad, pad, pad);

        for (String line : content.split("\n")) {
            if (line.startsWith("群: ")) { title = line.substring(3).trim(); continue; }
            String[] p = line.split("\t", 3);
            if (p.length < 3) continue;
            String time = p[0], name = p[1], text = p[2];
            share.append(name).append(" (").append(time).append("): ").append(text).append("\n");
            // 系统消息: 小灰字居中(native 已拼成"cheky 邀请了 张三")
            if ("系统".equals(name)) {
                String st = text.replace("{from_user}", "某人").replace("{to_chatters}", "某成员");
                if (st.length() > 80) st = st.substring(0, 80);
                TextView sv2 = new TextView(ctx);
                sv2.setText("— " + st + " —");
                sv2.setTextSize(10); sv2.setTextColor(Color.parseColor("#9AA0A6"));
                sv2.setGravity(Gravity.CENTER);
                sv2.setPadding(0, dp(ctx, 4), 0, dp(ctx, 4));
                box.addView(sv2);
                continue;
            }
            // 头: 昵称 · 时间
            TextView h = new TextView(ctx);
            h.setText(name + "  ·  " + time);
            h.setTextSize(11); h.setTextColor(Color.parseColor("#9AA0A6"));
            h.setPadding(dp(ctx, 4), dp(ctx, 8), 0, dp(ctx, 2));
            box.addView(h);
            // 气泡
            TextView bub = new TextView(ctx);
            bub.setText(text);
            bub.setTextSize(15); bub.setTextColor(Color.parseColor("#111111"));
            bub.setTextIsSelectable(true);
            bub.setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 8));
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(Color.parseColor("#E8F0FE")); bg.setCornerRadius(dp(ctx, 14));
            bub.setBackground(bg);
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.addView(bub);
            box.addView(row);
        }
        if (box.getChildCount() == 0) {
            TextView e = new TextView(ctx); e.setText("(无可显示的消息)"); e.setTextColor(Color.parseColor("#DDDDDD"));
            box.addView(e);
        }
        ScrollView sv = new ScrollView(ctx); sv.addView(box);
        final String shareText = "群: " + title + "\n" + share.toString();
        new AlertDialog.Builder(ctx).setTitle("💬 " + title).setView(sv)
                .setPositiveButton("复制", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        try {
                            android.content.ClipboardManager cm = (android.content.ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("kicked", shareText));
                            android.widget.Toast.makeText(ctx, "已复制", android.widget.Toast.LENGTH_SHORT).show();
                        } catch (Throwable t) {}
                    }
                })
                .setNeutralButton("分享", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        try {
                            Intent s = new Intent(Intent.ACTION_SEND); s.setType("text/plain");
                            s.putExtra(Intent.EXTRA_TEXT, shareText);
                            ctx.startActivity(Intent.createChooser(s, "分享被踢群记录").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        } catch (Throwable t) {}
                    }
                })
                .setNegativeButton("关闭", null).show();
    }

    // 从模块 APK 的 assets/reward.png 读赞赏码位图(面板与飞书同进程, 用 AntiRecall.MODULE_PATH 读自身 apk)。
    static Bitmap loadReward() {
        String mp = AntiRecall.MODULE_PATH;
        try {
            if (mp == null) { Diag.w("reward: MODULE_PATH null"); return null; }
            ZipFile zf = new ZipFile(mp);
            try {
                ZipEntry e = zf.getEntry("assets/reward.png");
                if (e == null) { Diag.w("reward: entry 'assets/reward.png' 不在 " + mp); return null; }
                InputStream is = zf.getInputStream(e);
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[65536]; int r;
                while ((r = is.read(buf)) != -1) bo.write(buf, 0, r);
                is.close();
                byte[] b = bo.toByteArray();
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565;   // 二维码不需要 alpha, 省一半内存
                o.inSampleSize = 2;                            // 1190->595, 二维码仍清晰, 大幅降内存
                Bitmap bm = BitmapFactory.decodeByteArray(b, 0, b.length, o);
                Diag.w("reward: 读 " + b.length + " 字节, 解码=" + (bm != null));
                return bm;
            } finally { zf.close(); }
        } catch (Throwable t) { Diag.w("reward: 异常 " + t); return null; }
    }

    // 每次飞书启动只弹一次赞赏(内存标志, 参考锤锤 mDonatePromptDialogShown; 重启飞书才重置)。
    static volatile boolean donateShownThisSession = false;

    // 赞赏码弹窗(自带加载, 便于扫码/长按识别)。bmp 为空则从模块 assets 读。
    static void showReward(final Context ctx) {
        Bitmap bmp = loadReward();
        if (bmp == null) {
            new AlertDialog.Builder(ctx).setTitle("❤️ 赞赏作者")
                    .setMessage("赞赏码未打包(旧版本?)。可到讨论群找作者。")
                    .setPositiveButton("好", null).show();
            return;
        }
        showReward(ctx, bmp);
    }

    // 放大赞赏码(便于扫码/长按识别)。
    static void showReward(final Context ctx, Bitmap bmp) {
        ImageView iv = new ImageView(ctx);
        int sz = dp(ctx, 300);
        iv.setLayoutParams(new LinearLayout.LayoutParams(sz, sz));
        iv.setImageBitmap(bmp);
        LinearLayout wrap = new LinearLayout(ctx);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setGravity(Gravity.CENTER);
        int p = dp(ctx, 16);
        wrap.setPadding(p, p, p, p);
        wrap.addView(iv);
        new AlertDialog.Builder(ctx)
                .setTitle("❤️ 赞赏作者")
                .setMessage("感谢支持！微信「扫一扫 → 相册」或长按识别下方二维码")
                .setView(wrap)
                .setPositiveButton("关闭", null)
                .show();
    }

    // 富列表: 离职名单 join 资料档案, 每行点击开聊天
    static void showResignedList(final Context ctx) {
        try {
            java.io.File rf = new java.io.File("/data/data/" + PKG + "/files/resign_tracker/resigned_all.json");
            java.io.File pf = new java.io.File("/data/data/" + PKG + "/files/resign_tracker/profiles.json");
            final JSONObject resigned = rf.exists() ? new JSONObject(Config.read(rf)) : new JSONObject();
            final JSONObject profiles = pf.exists() ? new JSONObject(Config.read(pf)) : new JSONObject();

            List<String> ids = new ArrayList<String>();
            Iterator<String> it = resigned.keys();
            while (it.hasNext()) ids.add(it.next());
            // 按飞书 update_time(该 chatter 行离职/冻结变更时刻)降序 -> 真正最近离职在最上面。
            // 注意: first_seen 只是"模块首次把这行读进缓存的时刻", 会因你搜索/打开某人而变成今天,
            //       不代表其今天离职(如龙科宇 update_time=4月却 first_seen=今天=旧记录刚被缓存)。
            Collections.sort(ids, new Comparator<String>() {
                @Override public int compare(String a, String b) {
                    long ua = optLong(resigned.optJSONObject(a), "update_time");
                    long ub = optLong(resigned.optJSONObject(b), "update_time");
                    return Long.compare(ub, ua);
                }
            });
            long nowSec = System.currentTimeMillis() / 1000L;

            final LinearLayout box = new LinearLayout(ctx);
            box.setOrientation(LinearLayout.VERTICAL);
            int p = dp(ctx, 16);
            box.setPadding(p, p, p, p);

            final List<String> idsF = ids;
            final JSONObject resignedF = resigned, profilesF = profiles;
            final long nowSecF = nowSec;
            populateResigned(ctx, box, idsF, resignedF, profilesF, nowSecF, "");

            // 顶部搜索框: 按姓名/部门/邮箱/工号/职务 实时过滤
            final EditText search = new EditText(ctx);
            search.setHint("🔍 搜索 姓名/部门/邮箱/工号/职务");
            search.setTextSize(15);
            search.setSingleLine(true);
            search.setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10));
            search.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(Editable e) {
                    populateResigned(ctx, box, idsF, resignedF, profilesF, nowSecF, e.toString());
                }
            });

            ScrollView sv = new ScrollView(ctx);
            sv.addView(box);
            LinearLayout rootv = new LinearLayout(ctx);
            rootv.setOrientation(LinearLayout.VERTICAL);
            rootv.addView(search, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            rootv.addView(sv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

            new AlertDialog.Builder(ctx)
                    .setTitle("离职名单（" + ids.size() + " 人）· 按记录更新排序（非离职时间）")
                    .setView(rootv)
                    .setPositiveButton("关闭", null)
                    .show();
        } catch (Throwable t) {
            XposedBridge.log("[fucklark] showResignedList err " + t);
        }
    }

    // 按 query 过滤并重建离职行(空 query = 全部)。query 匹配 姓名/英文名/uid/部门/邮箱/工号/职务/上级。
    static void populateResigned(final Context ctx, LinearLayout box, List<String> ids,
                                 JSONObject resigned, JSONObject profiles, long nowSec, String query) {
        box.removeAllViews();
        String q = query == null ? "" : query.trim().toLowerCase();
        int shown = 0;
        for (final String uid : ids) {
            JSONObject r = resigned.optJSONObject(uid);
            JSONObject pr = profiles.optJSONObject(uid);
            String name = r != null ? r.optString("name", uid) : uid;

            if (!q.isEmpty()) {
                StringBuilder hay = new StringBuilder(name).append(' ').append(uid);
                if (r != null) hay.append(' ').append(r.optString("en_us_name", ""));
                if (pr != null) hay.append(' ').append(pr.optString("department", ""))
                        .append(' ').append(pr.optString("email", ""))
                        .append(' ').append(pr.optString("employee_id", ""))
                        .append(' ').append(pr.optString("position", ""))
                        .append(' ').append(pr.optString("leader", ""));
                if (!hay.toString().toLowerCase().contains(q)) continue;
            }

            // 注: 飞书本地无真实离职时间列; update_time 只是"该记录本地最后刷新时刻", 仅供参考排序。
            long rt = optLong(r, "update_time");
            StringBuilder sb = new StringBuilder();
            sb.append("👤 ").append(name);
            if (rt > 0) sb.append("   · 记录更新 ").append(fmtDate(rt));
            if (pr != null) {
                add(sb, "部门", pr.optString("department", ""));
                add(sb, "邮箱", pr.optString("email", ""));
                add(sb, "工号", pr.optString("employee_id", ""));
                add(sb, "职务", pr.optString("position", ""));
                add(sb, "手机", pr.optString("phone", ""));
                add(sb, "上级", pr.optString("leader", ""));
            } else {
                sb.append("\n  （详情未存档——在职时打开过其资料页才会有）");
            }

            TextView row = new TextView(ctx);
            row.setText(sb.toString());
            row.setTextSize(14);
            row.setTextColor(Color.parseColor("#DDDDDD"));
            row.setPadding(0, dp(ctx, 12), 0, dp(ctx, 12));
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { openChat(ctx, uid); }
            });
            box.addView(row);
            View div = new View(ctx);
            div.setBackgroundColor(Color.parseColor("#33FFFFFF"));
            box.addView(div, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
            shown++;
        }
        if (shown == 0) {
            TextView empty = new TextView(ctx);
            empty.setText("无匹配结果");
            empty.setTextColor(Color.parseColor("#888888"));
            empty.setPadding(0, dp(ctx, 16), 0, dp(ctx, 16));
            box.addView(empty);
        }
    }

    static void add(StringBuilder sb, String label, String v) {
        if (v != null && !v.isEmpty()) sb.append("\n  ").append(label).append("：").append(v);
    }
    static long optLong(JSONObject o, String k) {
        if (o == null) return 0;
        try { return Long.parseLong(o.optString(k, "0")); } catch (Throwable t) { return o.optLong(k, 0); }
    }
    // first_seen 归一到"秒"(历史数据里它是 System.currentTimeMillis() 毫秒)
    static long firstSeenSec(JSONObject o) {
        long t = optLong(o, "first_seen");
        return t > 100000000000L ? t / 1000L : t;   // >~1e11 视为毫秒
    }
    static String fmtDate(long sec) {
        if (sec <= 0) return "?";
        try {
            return new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                    .format(new java.util.Date(sec * 1000L));
        } catch (Throwable t) { return "?"; }
    }

    // 按 uid 打开该同事资料页(app 内同 uid 可启动非导出 Activity; 页面带"消息"按钮一点进聊天,
    // 且会触发 ProfileCapture 再归档一次)。intent key = param_key_user_id(逆向所得)。
    static void openChat(Context ctx, String uid) {
        try {
            Intent i = new Intent();
            i.setClassName(PKG, "com.ss.android.lark.profile.func.v3.userprofile.UserProfileActivityV3");
            i.putExtra("param_key_user_id", uid);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            XposedBridge.log("[fucklark] 打开资料页 uid=" + uid);
        } catch (Throwable t) {
            XposedBridge.log("[fucklark] 打开资料页失败 " + t);
        }
    }

    // 打开外链(优先 Telegram app, 没装则浏览器)
    static void openUrl(Context ctx, String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Throwable t) {
            XposedBridge.log("[fucklark] 打开链接失败 " + t);
        }
    }

    interface OnToggle { void on(boolean b); }

    static View switchRow(Context ctx, String label, boolean checked, final OnToggle cb) {
        return switchRow(ctx, label, null, checked, cb);
    }

    // 深/浅色自适应: 飞书弹窗常为深色, 标题必须比副标题亮, 否则层级反转看着糊
    static boolean isNight(Context ctx) {
        try {
            int m = ctx.getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            return m == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) { return true; }  // 拿不到就按深色, 保证标题够亮
    }

    // 带副标题(灰字说明)的开关行: 标题 + 下方灰字说明 | 右侧 Switch
    static View switchRow(Context ctx, String label, String sub, boolean checked, final OnToggle cb) {
        final boolean night = isNight(ctx);
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(ctx, 10), 0, dp(ctx, 10));

        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextSize(16);
        tv.setTextColor(night ? 0xFFF0F0F0 : 0xFF1A1A1A);   // 标题: 深色→近白 / 浅色→近黑
        texts.addView(tv);
        if (sub != null && sub.length() > 0) {
            TextView st = new TextView(ctx);
            st.setText(sub);
            st.setTextSize(12);
            st.setTextColor(night ? 0xFF9AA0A6 : 0xFF888888); // 副标题: 恒比标题暗
            st.setPadding(0, dp(ctx, 2), 0, 0);
            texts.addView(st);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = dp(ctx, 12);
        row.addView(texts, lp);

        Switch sw = new Switch(ctx);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean isChecked) { cb.on(isChecked); }
        });
        row.addView(sw);
        return row;
    }

    // 分组标题(小灰字, 上方留白分隔)
    static View groupHeader(Context ctx, String text) {
        TextView h = new TextView(ctx);
        h.setText(text);
        h.setTextSize(12);
        h.setTextColor(Color.parseColor("#3B9EFF"));
        h.setPadding(0, dp(ctx, 16), 0, dp(ctx, 2));
        return h;
    }

    static int profilesCount() {
        try {
            File f = new File("/data/data/" + PKG + "/files/resign_tracker/profiles.json");
            if (!f.exists()) return 0;
            return new org.json.JSONObject(Config.read(f)).length();
        } catch (Throwable t) { return -1; }
    }

    // 富列表: 全员档案(profiles.json) —— 本公司+外部客户, 搜索/分类, 点行开资料页
    static void showAllProfiles(final Context ctx) {
        try {
            File pf = new File("/data/data/" + PKG + "/files/resign_tracker/profiles.json");
            final JSONObject profiles = pf.exists() ? new JSONObject(Config.read(pf)) : new JSONObject();

            final List<String> ids = new ArrayList<String>();
            Iterator<String> it = profiles.keys();
            while (it.hasNext()) ids.add(it.next());
            // 排序: 本公司在前; 组内按部门, 再按姓名
            Collections.sort(ids, new Comparator<String>() {
                @Override public int compare(String a, String b) {
                    JSONObject pa = profiles.optJSONObject(a), pb = profiles.optJSONObject(b);
                    boolean ha = pa != null && pa.optBoolean("is_home", false);
                    boolean hb = pb != null && pb.optBoolean("is_home", false);
                    if (ha != hb) return ha ? -1 : 1;
                    String da = pa != null ? pa.optString("department", "") : "";
                    String db = pb != null ? pb.optString("department", "") : "";
                    int c = da.compareTo(db);
                    if (c != 0) return c;
                    String na = pa != null ? pa.optString("name", a) : a;
                    String nb = pb != null ? pb.optString("name", b) : b;
                    return na.compareTo(nb);
                }
            });

            final LinearLayout box = new LinearLayout(ctx);
            box.setOrientation(LinearLayout.VERTICAL);
            int p = dp(ctx, 16);
            box.setPadding(p, p, p, p);
            populateProfiles(ctx, box, ids, profiles, "");

            final EditText search = new EditText(ctx);
            search.setHint("🔍 搜索 姓名/部门/邮箱/工号/职务/公司");
            search.setTextSize(15);
            search.setSingleLine(true);
            search.setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10));
            search.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(Editable e) { populateProfiles(ctx, box, ids, profiles, e.toString()); }
            });

            // 统计: 本公司 / 完整档案 / 外部, 用于顶部说明
            int homeN = 0, homeFull = 0, extN = 0;
            for (String uid : ids) {
                JSONObject pr = profiles.optJSONObject(uid);
                if (pr == null) continue;
                if (pr.optBoolean("is_home", false)) {
                    homeN++;
                    // 有明细 = 部门/邮箱/职务 任一(不强求工号: 化名同事本就无工号)
                    if (pr.optString("department", "").length() > 0
                            || pr.optString("email", "").length() > 0
                            || pr.optString("position", "").length() > 0) homeFull++;
                } else extN++;
            }
            final boolean night = isNight(ctx);
            TextView hint = new TextView(ctx);
            hint.setText("🏢 本公司 " + homeN + "（有明细 " + homeFull + "）· 🌐 外部 " + extN + "\n"
                    + "自动归档，无需逐个点击。想收录更多人 / 补全资料：进飞书【通讯录 → 组织架构】，把各部门展开、上下滑到底，"
                    + "让飞书加载他们的资料，模块随即自动存档。\n"
                    + "离职后仍保留在职时抓到的部门/工号/职务等（只增不删）。点任意一行可打开其资料页。");
            hint.setTextSize(12);
            hint.setTextColor(night ? 0xFFAAB4C0 : 0xFF666666);
            hint.setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10));

            ScrollView sv = new ScrollView(ctx);
            sv.addView(box);
            LinearLayout rootv = new LinearLayout(ctx);
            rootv.setOrientation(LinearLayout.VERTICAL);
            rootv.addView(hint, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            rootv.addView(search, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            rootv.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

            new AlertDialog.Builder(ctx)
                    .setTitle("全员档案（" + ids.size() + " 人）")
                    .setView(rootv)
                    .setPositiveButton("关闭", null)
                    .show();
        } catch (Throwable t) {
            XposedBridge.log("[fucklark] showAllProfiles err " + t);
        }
    }

    static void populateProfiles(final Context ctx, LinearLayout box, List<String> ids,
                                 JSONObject profiles, String query) {
        box.removeAllViews();
        String q = query == null ? "" : query.trim().toLowerCase();
        int shown = 0;
        for (final String uid : ids) {
            JSONObject pr = profiles.optJSONObject(uid);
            if (pr == null) continue;
            String name = pr.optString("name", uid);
            boolean home = pr.optBoolean("is_home", false);
            String company = pr.optString("company", "");

            if (!q.isEmpty()) {
                StringBuilder hay = new StringBuilder(name).append(' ').append(uid)
                        .append(' ').append(pr.optString("department", ""))
                        .append(' ').append(pr.optString("email", ""))
                        .append(' ').append(pr.optString("employee_id", ""))
                        .append(' ').append(pr.optString("position", ""))
                        .append(' ').append(pr.optString("leader", ""))
                        .append(' ').append(pr.optString("phone", ""))
                        .append(' ').append(company);
                if (!hay.toString().toLowerCase().contains(q)) continue;
            }

            StringBuilder sb = new StringBuilder();
            sb.append(home ? "🏢 " : "🌐 ").append(name);
            if (pr.optBoolean("is_resigned", false)) sb.append("  · 已离职");
            if (!home) add(sb, "公司", company);
            add(sb, "部门", pr.optString("department", ""));
            add(sb, "职务", pr.optString("position", ""));
            add(sb, "工号", pr.optString("employee_id", ""));
            add(sb, "邮箱", pr.optString("email", ""));
            add(sb, "手机", pr.optString("phone", ""));
            add(sb, "上级", pr.optString("leader", ""));
            add(sb, "群昵称", pr.optString("nickname", ""));

            TextView row = new TextView(ctx);
            row.setText(sb.toString());
            row.setTextSize(14);
            row.setTextColor(Color.parseColor(home ? "#DDDDDD" : "#B0C4DE"));
            row.setPadding(0, dp(ctx, 12), 0, dp(ctx, 12));
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { openChat(ctx, uid); }
            });
            box.addView(row);
            View div = new View(ctx);
            div.setBackgroundColor(Color.parseColor("#33FFFFFF"));
            box.addView(div, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
            shown++;
        }
        if (shown == 0) {
            TextView empty = new TextView(ctx);
            empty.setText("无匹配结果");
            empty.setTextColor(Color.parseColor("#888888"));
            empty.setPadding(0, dp(ctx, 16), 0, dp(ctx, 16));
            box.addView(empty);
        }
    }

    static int resignCount() {
        try {
            File f = new File("/data/data/" + PKG + "/files/resign_tracker/resigned_all.json");
            if (!f.exists()) return 0;
            String s = Config.read(f);
            // 顶层 JSON 对象, 键数 = 人数
            int n = 0, i = 0;
            org.json.JSONObject o = new org.json.JSONObject(s);
            return o.length();
        } catch (Throwable t) { return -1; }
    }

    static int dp(Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density);
    }
}
