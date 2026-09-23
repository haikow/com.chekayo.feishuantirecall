package com.chekayo.feishuantirecall;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 数据查看器与工具方法：不依赖 Xposed / 飞书类，桌面入口与飞书进程共用。
 * 档案读取：飞书路径优先，桌面回落模块 resign_tracker 副本（ArchiveSync 推送）。
 */
final class DataViews {
    private DataViews() { }

    /** 目标飞书包名（飞书进程由入口锁定）。 */
    static volatile String PKG = "com.ss.android.lark";

    static volatile boolean updateCheckedThisSession = false;
    static volatile boolean donateShownThisSession = false;

    static final String[] UPDATE_MIRRORS = {
        "https://ghproxy.net/https://raw.githubusercontent.com/haikow/com.chekayo.feishuantirecall/main/version.json",
        "https://gh-proxy.com/raw.githubusercontent.com/haikow/com.chekayo.feishuantirecall/main/version.json",
        "https://cdn.jsdelivr.net/gh/haikow/com.chekayo.feishuantirecall@main/version.json",
        "https://fastly.jsdelivr.net/gh/haikow/com.chekayo.feishuantirecall@main/version.json",
        "https://raw.githubusercontent.com/haikow/com.chekayo.feishuantirecall/main/version.json"
    };

    /**
     * 解析档案文件：按登录账号隔离。
     * 优先飞书 files/accounts/&lt;uid&gt;/resign_tracker/，再回落模块副本与旧全局路径。
     */
    static File archiveFile(String name) {
        try {
            File acc = AccountPaths.resolveArchive(null, PKG, AccountPaths.currentUid, name);
            if (acc != null && acc.exists()) return acc;
        } catch (Throwable ignored) {}
        File f = new File("/data/data/" + PKG + "/files/resign_tracker/" + name);
        if (f.exists()) return f;
        f = new File("/data/user/0/" + PKG + "/files/resign_tracker/" + name);
        if (f.exists()) return f;
        try {
            Class<?> c = Class.forName("android.app.ActivityThread");
            Object app = c.getMethod("currentApplication").invoke(null);
            if (app instanceof Context) {
                File m = new File(((Context) app).getFilesDir(), "resign_tracker/" + name);
                if (m.exists()) return m;
            }
        } catch (Throwable ignored) { }
        return new File("/data/data/" + PKG + "/files/resign_tracker/" + name);
    }

    /** 应用内更新检查(多镜像只读拉 version.json; 不采集、不上传)。silent=true 仅在有新版/公告时弹。 */
    static void checkUpdate(final Context ctx, final boolean silent) {
        if (!silent) android.widget.Toast.makeText(ctx, "检查更新中…", android.widget.Toast.LENGTH_SHORT).show();
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                String json = null;
                for (String u : UPDATE_MIRRORS) {
                    try {
                        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(u).openConnection();
                        c.setConnectTimeout(5000); c.setReadTimeout(8000);
                        c.setRequestProperty("User-Agent", "FeishuKit");
                        if (c.getResponseCode() != 200) { c.disconnect(); continue; }
                        java.io.InputStream is = c.getInputStream();
                        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                        byte[] buf = new byte[8192]; int n;
                        while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
                        is.close(); c.disconnect();
                        json = new String(bo.toByteArray(), "UTF-8");
                        break;
                    } catch (Throwable ignore) { }
                }
                final String result = json;
                new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                    @Override public void run() { showUpdateResult(ctx, result, silent); }
                });
            }
        }, "FeishuKit-update");
        t.setDaemon(true); t.start();
    }

    /** 解析 version.json 并弹更新/公告对话框。 */
    static void showUpdateResult(final Context ctx, String json, boolean silent) {
        try {
            if (json == null) {
                if (!silent) android.widget.Toast.makeText(ctx, "检查更新失败（网络不可达）", android.widget.Toast.LENGTH_LONG).show();
                return;
            }
            JSONObject o = new JSONObject(json);
            int vc = o.optInt("versionCode", 0);
            String vn = o.optString("versionName", "");
            String notice = o.optString("notice", "").trim();
            String changelog = o.optString("changelog", "").trim();
            String channel = o.optString("channel", "").trim();
            String dl = "";
            org.json.JSONArray da = o.optJSONArray("downloads");
            if (da != null && da.length() > 0) dl = da.optString(0, "");
            if (dl.isEmpty()) dl = o.optString("download", "");
            boolean newer = vc > moduleVersionCode();
            boolean hasNotice = !notice.isEmpty();
            if (!newer && !hasNotice) {
                if (!silent) android.widget.Toast.makeText(ctx, "已是最新（v" + moduleVersion() + "）", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            StringBuilder msg = new StringBuilder();
            if (hasNotice) msg.append("📢 ").append(notice).append("\n\n");
            if (newer) {
                msg.append("发现新版 v").append(vn).append("（当前 v").append(moduleVersion()).append("）");
                if (!changelog.isEmpty()) msg.append("\n\n").append(changelog);
            }
            AlertDialog.Builder b = new AlertDialog.Builder(ctx)
                    .setTitle(newer ? "有新版本" : "公告")
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

    /**
     * 诊断日志弹窗: 显示 fucklark_log.txt 尾部 + 复制到剪贴板 + 清空。
     * 终端用户反馈「防撤回不生效」时, 点开→复制→发作者, 一看便知(版本/安装/拦截)。
     */
    static void showDiagLog(final Context ctx) {
        final String log = Diag.read();
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
                .setTitle("诊断日志")
                .setView(sv)
                .setPositiveButton("复制到剪贴板", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        try {
                            String header = "【FeishuKit 诊断日志】\n";
                            android.content.ClipboardManager cm =
                                    (android.content.ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("FeishuKit_log", header + log));
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

    /**
     * 后台消息存档: 读 notif_archive.txt(每行 "时间\\t发送人\\t正文"), 倒序显示 + 复制 + 清空。
     * 救「后台/离线被撤回」的消息 —— 撤回后服务器不下发原文, 但原文弹过通知, 在这里能翻到。
     */
    static void showNotifArchive(final Context ctx) {
        final String content = NotifArchive.read();
        if (content.trim().isEmpty()) {
            new AlertDialog.Builder(ctx).setTitle("后台消息存档")
                    .setMessage("暂无记录。\n\n开启「后台消息存档」后，后台/离线时被撤回的消息会在这里留底。\n前提：飞书通知开启「消息预览」。")
                    .setPositiveButton("关闭", null).show();
            return;
        }
        String[] lines = content.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = lines.length - 1; i >= 0; i--) {
            String ln = lines[i].trim();
            if (ln.isEmpty()) continue;
            String[] c = ln.split("\t", 3);
            if (c.length >= 3) sb.append(c[0]).append("  ·  ").append(c[1]).append("\n").append(c[2]).append("\n\n");
            else sb.append(ln.replace("\t", "  ·  ")).append("\n\n");
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
                .setTitle("后台消息存档")
                .setView(sv)
                .setPositiveButton("复制全部", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        try {
                            android.content.ClipboardManager cm =
                                    (android.content.ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("notif_archive", content));
                            android.widget.Toast.makeText(ctx, "已复制", android.widget.Toast.LENGTH_SHORT).show();
                        } catch (Throwable t) { }
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

    /** 退群/被移除记录: 读 leave_log.txt(每行 "时间\\t文案"), 倒序显示 + 复制 + 清空。 */
    static void showLeaveLog(final Context ctx) {
        final File f = AccountPaths.resolveMessageFile(ctx, PKG, AccountPaths.currentUid, "leave_log.txt");
        String content = "";
        try { if (f.exists()) content = new String(Diag.readBytes(f), "UTF-8"); } catch (Throwable ignored) {}
        if (content.trim().isEmpty()) {
            new AlertDialog.Builder(ctx).setTitle("退群/移除记录")
                    .setMessage("暂无记录。开启「退群提醒」后会记到这里。")
                    .setPositiveButton("关闭", null).show();
            return;
        }
        String[] lines = content.split("\n");
        StringBuilder sb = new StringBuilder();
        int cnt = 0;
        for (int i = lines.length - 1; i >= 0; i--) {
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
                .setTitle("退群/移除记录（" + cnt + "）")
                .setView(sv)
                .setPositiveButton("复制", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        try {
                            android.content.ClipboardManager cm =
                                    (android.content.ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("FeishuKit_leave", full));
                            android.widget.Toast.makeText(ctx, "已复制", android.widget.Toast.LENGTH_SHORT).show();
                        } catch (Throwable t) { }
                    }
                })
                .setNeutralButton("清空", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        try { f.delete(); } catch (Throwable t) { }
                        android.widget.Toast.makeText(ctx, "已清空", android.widget.Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    /** 被踢群聊天记录: 列出 files/kicked_*.txt, 支持搜索, 点开看内容 + 复制 + 分享。 */
    static void showKickedExports(final Context ctx) {
        File dir = AccountPaths.accountRoot(ctx, PKG, AccountPaths.currentUid);
        File[] found = dir.listFiles(new java.io.FilenameFilter() {
            @Override public boolean accept(java.io.File d, String n) { return n.startsWith("kicked_") && n.endsWith(".txt"); }
        });
        if (found == null || found.length == 0) {
            dir = new File("/data/data/" + PKG + "/files");
            found = dir.listFiles(new java.io.FilenameFilter() {
                @Override public boolean accept(java.io.File d, String n) { return n.startsWith("kicked_") && n.endsWith(".txt"); }
            });
        }
        final File[] files = found;
        if (files == null || files.length == 0) {
            new AlertDialog.Builder(ctx).setTitle("被踢群聊天记录")
                    .setMessage("暂无记录。开启「保留被踢群聊天记录」后会自动导出。")
                    .setPositiveButton("好", null).show();
            return;
        }
        java.util.Arrays.sort(files, new Comparator<File>() {
            @Override public int compare(File a, File b) { return Long.compare(b.lastModified(), a.lastModified()); }
        });
        final String[] gname = new String[files.length];
        final String[] lower = new String[files.length];
        for (int i = 0; i < files.length; i++) {
            String label = files[i].getName(), content = "";
            try {
                content = new String(Diag.readBytes(files[i]), "UTF-8");
                int nl = content.indexOf('\n');
                String first = nl > 0 ? content.substring(0, nl) : content;
                if (first.startsWith("群: ")) label = first.substring(3).trim();
            } catch (Throwable t) { }
            gname[i] = label;
            lower[i] = (label + " " + content).toLowerCase();
        }
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(ctx, 14);
        root.setPadding(p, p, p, dp(ctx, 6));
        final EditText search = new EditText(ctx);
        search.setHint("搜索群名 / 消息内容");
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
                .setTitle("被踢群聊天记录（" + files.length + "）")
                .setView(root).setNegativeButton("关闭", null).create();
        final Runnable[] repop = new Runnable[1];
        repop[0] = new Runnable() {
            @Override public void run() {
                String q = search.getText().toString().trim().toLowerCase();
                listBox.removeAllViews();
                int shown = 0;
                for (int i = 0; i < files.length; i++) {
                    if (q.length() > 0 && lower[i].indexOf(q) < 0) continue;
                    final File f = files[i];
                    TextView row = new TextView(ctx);
                    row.setText(gname[i] + "   (" + (f.length() / 1024 + 1) + "KB)");
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

    /** 单个被踢群导出文件：气泡式渲染 + 复制/分享全文。 */
    static void showKickedFile(final Context ctx, final File file) {
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
            TextView h = new TextView(ctx);
            h.setText(name + "  ·  " + time);
            h.setTextSize(11); h.setTextColor(Color.parseColor("#9AA0A6"));
            h.setPadding(dp(ctx, 4), dp(ctx, 8), 0, dp(ctx, 2));
            box.addView(h);
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
        new AlertDialog.Builder(ctx).setTitle(title).setView(sv)
                .setPositiveButton("复制", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        try {
                            android.content.ClipboardManager cm = (android.content.ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("kicked", shareText));
                            android.widget.Toast.makeText(ctx, "已复制", android.widget.Toast.LENGTH_SHORT).show();
                        } catch (Throwable t) { }
                    }
                })
                .setNeutralButton("分享", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        try {
                            Intent s = new Intent(Intent.ACTION_SEND); s.setType("text/plain");
                            s.putExtra(Intent.EXTRA_TEXT, shareText);
                            ctx.startActivity(Intent.createChooser(s, "分享被踢群记录").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        } catch (Throwable t) { }
                    }
                })
                .setNegativeButton("关闭", null).show();
    }

    private static volatile Bitmap rewardCache;

    /**
     * 加载 assets/reward.png：
     * - 模块进程：直接读本 APK assets（不依赖 MODULE_PATH）；
     * - 飞书进程：从 MODULE_PATH / 模块包 sourceDir 的 APK zip 内读 entry。
     */
    static Bitmap loadReward(Context ctx) {
        Bitmap hit = rewardCache;
        if (hit != null && !hit.isRecycled()) return hit;
        try {
            if (ctx != null && "com.chekayo.feishuantirecall".equals(ctx.getPackageName())) {
                InputStream is = ctx.getAssets().open("reward.png");
                try {
                    Bitmap bm = decodeRewardStream(is);
                    if (bm != null) { rewardCache = bm; return bm; }
                } finally { is.close(); }
            }
            String mp = moduleApkPath();
            if (mp == null && ctx != null) {
                try {
                    mp = ctx.getPackageManager().getApplicationInfo("com.chekayo.feishuantirecall", 0).sourceDir;
                } catch (Throwable ignored) { }
            }
            if (mp != null) {
                ZipFile zf = new ZipFile(mp);
                try {
                    ZipEntry e = zf.getEntry("assets/reward.png");
                    if (e == null) e = zf.getEntry("reward.png");
                    if (e != null) {
                        InputStream is = zf.getInputStream(e);
                        try {
                            Bitmap bm = decodeRewardStream(is);
                            if (bm != null) { rewardCache = bm; return bm; }
                        } finally { is.close(); }
                    }
                } finally { zf.close(); }
            }
        } catch (Throwable t) {
            android.util.Log.w("FeishuKit", "reward load err " + t);
        }
        return null;
    }

    private static Bitmap decodeRewardStream(InputStream is) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int r;
        while ((r = is.read(buf)) != -1) bo.write(buf, 0, r);
        byte[] b = bo.toByteArray();
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        o.inSampleSize = 2;
        return BitmapFactory.decodeByteArray(b, 0, b.length, o);
    }

    /** 赞赏码弹窗（工具页手动打开；加载失败则提示去讨论群）。 */
    static void showReward(final Context ctx) {
        Bitmap bmp = loadReward(ctx);
        if (bmp == null) {
            new AlertDialog.Builder(ctx).setTitle("赞赏 FeishuKit")
                    .setMessage("赞赏码资源缺失。可打开讨论群或项目主页支持作者。\n\n赞助纯属鼓励，与功能无关，所有功能开源免费。")
                    .setPositiveButton("关闭", null).show();
            return;
        }
        showReward(ctx, bmp);
    }

    /** 放大赞赏码(便于扫码/长按识别)。 */
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
                .setTitle("赞赏 FeishuKit")
                .setMessage("感谢支持维护与版本适配。\n微信「扫一扫 → 相册」或长按识别二维码。\n赞助纯属鼓励，与功能解锁无关。")
                .setView(wrap)
                .setPositiveButton("关闭", null)
                .show();
    }

    /** 富列表: 离职名单 join 资料档案, 每行点击打开资料页；顶部搜索实时过滤。 */
    static void showResignedList(final Context ctx) {
        try {
            File rf = archiveFile("resigned_all.json");
            File pf = archiveFile("profiles.json");
            final JSONObject resigned = rf.exists() ? new JSONObject(Config.read(rf)) : new JSONObject();
            final JSONObject profiles = pf.exists() ? new JSONObject(Config.read(pf)) : new JSONObject();
            final List<String> ids = new ArrayList<String>();
            Iterator<String> it = resigned.keys();
            while (it.hasNext()) ids.add(it.next());
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
            final EditText search = new EditText(ctx);
            search.setHint("搜索 姓名/部门/邮箱/工号/职务");
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
                    .setTitle("离职名单（" + ids.size() + " 人）")
                    .setView(rootv)
                    .setPositiveButton("关闭", null)
                    .show();
        } catch (Throwable t) {
            android.util.Log.w("FeishuKit", "showResignedList err " + t);
        }
    }

    /** 按 query 过滤并重建离职行（空 query = 全部）。匹配 姓名/英文名/uid/部门/邮箱/工号/职务/上级。 */
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
            long rt = optLong(r, "update_time");
            StringBuilder sb = new StringBuilder();
            sb.append(name);
            if (rt > 0) sb.append("   · 记录更新 ").append(fmtDate(rt));
            if (r != null && r.optBoolean("is_frozen", false)) sb.append(" · 已冻结");
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

    static String fmtDate(long sec) {
        if (sec <= 0) return "?";
        try {
            return new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                    .format(new java.util.Date(sec * 1000L));
        } catch (Throwable t) { return "?"; }
    }

    /**
     * 按 uid 打开该同事资料页(app 内同 uid 可启动非导出 Activity; 页面带「消息」按钮一点进聊天,
     * 且会触发 ProfileCapture 再归档一次)。intent key = param_key_user_id(逆向所得)。
     */
    static void openChat(Context ctx, String uid) {
        try {
            Intent i = new Intent();
            i.setClassName(PKG, "com.ss.android.lark.profile.func.v3.userprofile.UserProfileActivityV3");
            i.putExtra("param_key_user_id", uid);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Throwable t) {
            android.util.Log.w("FeishuKit", "open profile fail " + t);
        }
    }

    /** 打开外链(优先系统浏览器)。 */
    static void openUrl(Context ctx, String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Throwable ignored) { }
    }

    /** 深/浅色自适应: 飞书弹窗常为深色, 标题必须比副标题亮，否则层级反转看着糊。 */
    static boolean isNight(Context ctx) {
        try {
            int m = ctx.getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            return m == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) { return true; }
    }

    // ── 全员档案分类与列表 ──────────────────────────────────────────
    // 优先级：已离职 > 外部 > 部门 > 邮箱
    //   有部门 → 同事（哪怕没有邮箱）
    //   无部门但有邮箱 → 同事
    //   无部门且无邮箱 → 机器人
    static final String KIND_EMPLOYEE = "employee";
    static final String KIND_RESIGNED = "resigned";
    static final String KIND_EXTERNAL = "external";
    static final String KIND_BOT = "bot";

    /** 按档案字段推断类别（展示时计算，兼容旧 profiles.json 无 kind 字段）。 */
    static String classifyProfile(JSONObject pr) {
        if (pr == null) return KIND_EMPLOYEE;
        if (pr.optBoolean("is_resigned", false)) return KIND_RESIGNED;
        if (!pr.optBoolean("is_home", false)) return KIND_EXTERNAL;
        // 部门优先于邮箱
        if (!isEmpty(pr.optString("department", ""))) return KIND_EMPLOYEE;
        if (!isEmpty(pr.optString("email", ""))) return KIND_EMPLOYEE;
        return KIND_BOT;
    }

    static boolean isEmpty(String s) { return s == null || s.length() == 0; }

    static String kindLabel(String kind) {
        if (KIND_RESIGNED.equals(kind)) return "已离职";
        if (KIND_EXTERNAL.equals(kind)) return "外部";
        if (KIND_BOT.equals(kind)) return "机器人";
        return "同事";
    }

    static String kindIcon(String kind) {
        if (KIND_RESIGNED.equals(kind)) return "🚪";
        if (KIND_EXTERNAL.equals(kind)) return "🌐";
        if (KIND_BOT.equals(kind)) return "🤖";
        return "🏢";
    }

    static int kindRank(String kind) {
        if (KIND_EMPLOYEE.equals(kind)) return 0;
        if (KIND_RESIGNED.equals(kind)) return 1;
        if (KIND_EXTERNAL.equals(kind)) return 2;
        if (KIND_BOT.equals(kind)) return 3;
        return 4;
    }

    /** 全员档案人数（读 profiles.json 顶层键数）。 */
    static int profilesCount() {
        try {
            File f = archiveFile("profiles.json");
            if (!f.exists()) return 0;
            return new JSONObject(Config.read(f)).length();
        } catch (Throwable t) { return -1; }
    }

    /** 全员档案：左右切页签 + 上下滑列表。 */
    static void showAllProfiles(final Context ctx) {
        try {
            File pf = archiveFile("profiles.json");
            final JSONObject profiles = pf.exists() ? new JSONObject(Config.read(pf)) : new JSONObject();
            final List<String> ids = new ArrayList<String>();
            Iterator<String> it = profiles.keys();
            while (it.hasNext()) ids.add(it.next());
            Collections.sort(ids, new Comparator<String>() {
                @Override public int compare(String a, String b) {
                    JSONObject pa = profiles.optJSONObject(a), pb = profiles.optJSONObject(b);
                    int ra = kindRank(classifyProfile(pa)), rb = kindRank(classifyProfile(pb));
                    if (ra != rb) return ra - rb;
                    String da = pa != null ? pa.optString("department", "") : "";
                    String db = pb != null ? pb.optString("department", "") : "";
                    int c = da.compareTo(db);
                    if (c != 0) return c;
                    String na = pa != null ? pa.optString("name", a) : a;
                    String nb = pb != null ? pb.optString("name", b) : b;
                    return na.compareTo(nb);
                }
            });
            int nEmp = 0, nRes = 0, nExt = 0, nBot = 0;
            for (String uid : ids) {
                String k = classifyProfile(profiles.optJSONObject(uid));
                if (KIND_RESIGNED.equals(k)) nRes++;
                else if (KIND_EXTERNAL.equals(k)) nExt++;
                else if (KIND_BOT.equals(k)) nBot++;
                else nEmp++;
            }

            final LinearLayout root = new LinearLayout(ctx);
            root.setOrientation(LinearLayout.VERTICAL);
            int p = dp(ctx, 10);
            root.setPadding(p, p, p, p);

            final String[] tabKinds = {
                    KIND_EMPLOYEE, KIND_RESIGNED, KIND_EXTERNAL, KIND_BOT, ""
            };
            final String[] tabTitles = {
                    "同事 " + nEmp, "离职 " + nRes, "外部 " + nExt, "机器人 " + nBot, "全部"
            };
            final int[] tabIdx = { 0 };

            LinearLayout topBar = new LinearLayout(ctx);
            topBar.setOrientation(LinearLayout.HORIZONTAL);
            topBar.setGravity(Gravity.CENTER_VERTICAL);
            TextView prev = new TextView(ctx);
            prev.setText("‹");
            prev.setTextSize(22);
            prev.setTextColor(Ui.subColor(ctx));
            prev.setPadding(dp(ctx, 6), dp(ctx, 10), dp(ctx, 6), dp(ctx, 10));
            topBar.addView(prev);
            final android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(ctx);
            hs.setHorizontalScrollBarEnabled(false);
            final LinearLayout tabs = new LinearLayout(ctx);
            tabs.setOrientation(LinearLayout.HORIZONTAL);
            hs.addView(tabs);
            topBar.addView(hs, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView next = new TextView(ctx);
            next.setText("›");
            next.setTextSize(22);
            next.setTextColor(Ui.subColor(ctx));
            next.setPadding(dp(ctx, 6), dp(ctx, 10), dp(ctx, 6), dp(ctx, 10));
            topBar.addView(next);
            root.addView(topBar, new LinearLayout.LayoutParams(-1, -2));

            TextView hint = new TextView(ctx);
            hint.setText(AccountPaths.label(ctx)
                    + "\n归类：有部门或邮箱 → 同事；无部门且无邮箱 → 机器人；离职/外部单独页签。\n"
                    + "档案与消息按飞书账号隔离，切换账号后各看各的。\n"
                    + "左右切换页签，上下滑动列表。");
            hint.setTextSize(11);
            hint.setTextColor(Ui.subColor(ctx));
            hint.setPadding(dp(ctx, 4), dp(ctx, 2), dp(ctx, 4), dp(ctx, 6));
            root.addView(hint);

            final EditText search = new EditText(ctx);
            search.setHint("搜索 姓名/部门/邮箱/工号/职务");
            search.setTextSize(14);
            search.setSingleLine(true);
            search.setPadding(dp(ctx, 8), dp(ctx, 6), dp(ctx, 8), dp(ctx, 6));
            root.addView(search, new LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT));

            final ScrollView scroll = new ScrollView(ctx);
            scroll.setFillViewport(true);
            final LinearLayout list = new LinearLayout(ctx);
            list.setOrientation(LinearLayout.VERTICAL);
            list.setPadding(0, dp(ctx, 4), 0, dp(ctx, 8));
            scroll.addView(list);
            root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

            final TextView[] tabViews = new TextView[tabTitles.length];
            for (int i = 0; i < tabTitles.length; i++) {
                final int idx = i;
                TextView tab = new TextView(ctx);
                tabViews[i] = tab;
                tab.setText(tabTitles[i]);
                tab.setTextSize(13);
                tab.setGravity(Gravity.CENTER);
                tab.setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 8));
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                tlp.leftMargin = dp(ctx, 4);
                tlp.rightMargin = dp(ctx, 4);
                tabs.addView(tab, tlp);
                tab.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        tabIdx[0] = idx;
                        refreshTabUi(ctx, tabViews, tabIdx[0], hs);
                        fillProfileList(ctx, list, ids, profiles, search.getText().toString(), tabKinds[tabIdx[0]]);
                    }
                });
            }

            prev.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    int i = tabIdx[0] - 1;
                    if (i < 0) i = tabViews.length - 1;
                    tabIdx[0] = i;
                    refreshTabUi(ctx, tabViews, i, hs);
                    fillProfileList(ctx, list, ids, profiles, search.getText().toString(), tabKinds[i]);
                }
            });
            next.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    int i = (tabIdx[0] + 1) % tabViews.length;
                    tabIdx[0] = i;
                    refreshTabUi(ctx, tabViews, i, hs);
                    fillProfileList(ctx, list, ids, profiles, search.getText().toString(), tabKinds[i]);
                }
            });

            scroll.setOnTouchListener(new View.OnTouchListener() {
                float sx, sy;
                @Override public boolean onTouch(View v, android.view.MotionEvent ev) {
                    try {
                        int act = ev.getActionMasked();
                        if (act == android.view.MotionEvent.ACTION_DOWN) {
                            sx = ev.getX(); sy = ev.getY();
                        } else if (act == android.view.MotionEvent.ACTION_UP) {
                            float dx = ev.getX() - sx;
                            float dy = ev.getY() - sy;
                            if (Math.abs(dx) > dp(ctx, 80) && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                                int i;
                                if (dx < 0) i = (tabIdx[0] + 1) % tabViews.length;
                                else {
                                    i = tabIdx[0] - 1;
                                    if (i < 0) i = tabViews.length - 1;
                                }
                                tabIdx[0] = i;
                                refreshTabUi(ctx, tabViews, i, hs);
                                fillProfileList(ctx, list, ids, profiles, search.getText().toString(), tabKinds[i]);
                                return true;
                            }
                        }
                    } catch (Throwable ignored) { }
                    return false;
                }
            });

            search.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(Editable e) {
                    fillProfileList(ctx, list, ids, profiles, e.toString(), tabKinds[tabIdx[0]]);
                }
            });

            refreshTabUi(ctx, tabViews, 0, hs);
            fillProfileList(ctx, list, ids, profiles, "", tabKinds[0]);

            new AlertDialog.Builder(ctx)
                    .setTitle("全员档案（" + ids.size() + "）")
                    .setView(root)
                    .setPositiveButton("关闭", null)
                    .show();
        } catch (Throwable t) {
            android.util.Log.w("FeishuKit", "showAllProfiles err " + t);
        }
    }

    /** 页签高亮 + 横向滚到可见。 */
    static void refreshTabUi(Context ctx, TextView[] tabViews, int active,
                             android.widget.HorizontalScrollView hs) {
        for (int j = 0; j < tabViews.length; j++) {
            boolean on = j == active;
            tabViews[j].setTextColor(on ? Ui.ACCENT : Ui.subColor(ctx));
            tabViews[j].setTypeface(null, on ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
        final View cur = tabViews[active];
        hs.post(new Runnable() {
            @Override public void run() {
                int target = Math.max(0, cur.getLeft() - hs.getWidth() / 3);
                hs.scrollTo(target, 0);
            }
        });
    }

    /** 填充档案列表行（按 kindFilter + 搜索词过滤；每行点击打开资料页）。 */
    static void fillProfileList(Context ctx, LinearLayout box, List<String> ids,
                                JSONObject profiles, String query, String kindFilter) {
        box.removeAllViews();
        String q = query == null ? "" : query.trim().toLowerCase();
        java.util.Set<String> allow = null;
        if (kindFilter != null && kindFilter.length() > 0) {
            allow = new java.util.HashSet<String>();
            for (String k : kindFilter.split(",")) if (k.length() > 0) allow.add(k);
        }
        int shown = 0;
        for (final String uid : ids) {
            JSONObject pr = profiles.optJSONObject(uid);
            if (pr == null) continue;
            String kind = classifyProfile(pr);
            if (allow != null && !allow.contains(kind)) continue;
            String name = pr.optString("name", uid);
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
            sb.append(kindIcon(kind)).append(' ').append(name);
            sb.append("  · ").append(kindLabel(kind));
            if (KIND_RESIGNED.equals(kind)) {
                if (pr.optBoolean("is_frozen", false)) sb.append(" · 已冻结");
                add(sb, "部门", pr.optString("department", ""));
                add(sb, "职务", pr.optString("position", ""));
                add(sb, "邮箱", pr.optString("email", ""));
                add(sb, "工号", pr.optString("employee_id", ""));
                long ut = 0;
                try { ut = Long.parseLong(pr.optString("update_time", "0")); } catch (Throwable ignored) {}
                if (ut > 0) {
                    if (ut > 100000000000L) ut = ut / 1000L;
                    sb.append("\n  记录更新 ").append(fmtDate(ut));
                }
            } else {
                if (company.length() > 0) add(sb, "公司", company);
                add(sb, "部门", pr.optString("department", ""));
                add(sb, "职务", pr.optString("position", ""));
                add(sb, "工号", pr.optString("employee_id", ""));
                add(sb, "邮箱", pr.optString("email", ""));
                add(sb, "手机", pr.optString("phone", ""));
                add(sb, "上级", pr.optString("leader", ""));
                add(sb, "群昵称", pr.optString("nickname", ""));
            }
            TextView row = new TextView(ctx);
            row.setText(sb.toString());
            row.setTextSize(14);
            int color;
            if (KIND_BOT.equals(kind)) color = isNight(ctx) ? 0xFFE6C56A : 0xFFB8860B;
            else if (KIND_EXTERNAL.equals(kind)) color = isNight(ctx) ? 0xFF9BB8D4 : 0xFF5B7C99;
            else if (KIND_RESIGNED.equals(kind)) color = isNight(ctx) ? 0xFFE8A0A0 : 0xFFC06060;
            else color = Ui.titleColor(ctx);
            row.setTextColor(color);
            row.setPadding(0, dp(ctx, 12), 0, dp(ctx, 12));
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { openChat(ctx, uid); }
            });
            box.addView(row);
            View div = new View(ctx);
            div.setBackgroundColor(Ui.divider(ctx));
            box.addView(div, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
            shown++;
        }
        if (shown == 0) {
            TextView empty = new TextView(ctx);
            empty.setText("无匹配结果");
            empty.setTextColor(Ui.muteColor(ctx));
            empty.setPadding(0, dp(ctx, 16), 0, dp(ctx, 16));
            box.addView(empty);
        }
    }

    /** 离职名单人数（读 resigned_all.json 顶层键数）。 */
    static int resignCount() {
        try {
            File f = archiveFile("resigned_all.json");
            if (!f.exists()) return 0;
            JSONObject o = new JSONObject(Config.read(f));
            return o.length();
        } catch (Throwable t) { return -1; }
    }

    static int dp(Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density);
    }

    static String moduleApkPath() {
        try {
            Class<?> c = Class.forName("com.chekayo.feishuantirecall.AntiRecall");
            Object v = c.getField("MODULE_PATH").get(null);
            return v == null ? null : String.valueOf(v);
        } catch (Throwable t) { return null; }
    }

    static String moduleVersion() { return "1.8.3"; }

    static int moduleVersionCode() { return 24; }
}
