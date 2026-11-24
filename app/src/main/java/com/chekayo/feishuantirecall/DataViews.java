package com.chekayo.feishuantirecall;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 数据查看器与工具方法：不依赖 Xposed / 飞书类，桌面入口与飞书进程共用。
 * 档案读取：飞书路径优先，桌面回落模块 resign_tracker 副本（ArchiveSync 推送）。
 */
final class DataViews {
    private DataViews() { }

    private static final long SEARCH_DEBOUNCE_MS = 200L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO = Executors.newFixedThreadPool(2, new ThreadFactory() {
        private int seq = 0;
        @Override public synchronized Thread newThread(Runnable r) {
            Thread t = new Thread(r, "FeishuKit-io-" + (++seq));
            t.setDaemon(true);
            return t;
        }
    });

    /** 目标飞书包名（飞书进程由入口锁定）。 */
    static volatile String PKG = "com.ss.android.lark";

    static volatile boolean updateCheckedThisSession = false;

    static final String[] UPDATE_MIRRORS = {
        "https://ghproxy.net/https://raw.githubusercontent.com/haikow/com.chekayo.feishuantirecall/main/version.json",
        "https://gh-proxy.com/raw.githubusercontent.com/haikow/com.chekayo.feishuantirecall/main/version.json",
        "https://cdn.jsdelivr.net/gh/haikow/com.chekayo.feishuantirecall@main/version.json",
        "https://fastly.jsdelivr.net/gh/haikow/com.chekayo.feishuantirecall@main/version.json",
        "https://raw.githubusercontent.com/haikow/com.chekayo.feishuantirecall/main/version.json"
    };

    private static int dialogListHeight(Context ctx) {
        int screenH = ctx.getResources().getDisplayMetrics().heightPixels;
        int target = (int) (screenH * 0.55f);
        int min = Ui.dp(ctx, 300);
        int max = Ui.dp(ctx, 520);
        if (target < min) target = min;
        if (target > max) target = max;
        return target;
    }

    private static EditText createSearchInput(Context ctx, String hint) {
        EditText search = new EditText(ctx);
        search.setHint(hint);
        search.setTextSize(14);
        search.setSingleLine(true);
        search.setTextColor(Ui.titleColor(ctx));
        search.setHintTextColor(Ui.muteColor(ctx));
        search.setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 8), Ui.dp(ctx, 10), Ui.dp(ctx, 8));
        return search;
    }

    private static TextView createStatusView(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setTextColor(Ui.subColor(ctx));
        tv.setGravity(Gravity.CENTER);
        int p = Ui.dp(ctx, 20);
        tv.setPadding(p, p, p, p);
        return tv;
    }

    private static ListView createDialogListView(Context ctx, boolean withDivider) {
        ListView lv = new ListView(ctx);
        lv.setCacheColorHint(0);
        if (withDivider) {
            lv.setDivider(new ColorDrawable(Ui.divider(ctx)));
            lv.setDividerHeight(1);
        } else {
            lv.setDivider(null);
            lv.setDividerHeight(0);
            lv.setSelector(android.R.color.transparent);
        }
        return lv;
    }

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
        if (!silent) Toast.makeText(ctx, "检查更新中…", Toast.LENGTH_SHORT).show();
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                String json = null;
                for (String u : UPDATE_MIRRORS) {
                    try {
                        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(u).openConnection();
                        c.setConnectTimeout(5000); c.setReadTimeout(8000);
                        c.setRequestProperty("User-Agent", "FeishuKit");
                        if (c.getResponseCode() != 200) { c.disconnect(); continue; }
                        InputStream is = c.getInputStream();
                        ByteArrayOutputStream bo = new ByteArrayOutputStream();
                        byte[] buf = new byte[8192]; int n;
                        while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
                        is.close(); c.disconnect();
                        json = new String(bo.toByteArray(), "UTF-8");
                        break;
                    } catch (Throwable ignore) { }
                }
                final String result = json;
                MAIN.post(new Runnable() {
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
                if (!silent) Toast.makeText(ctx, "检查更新失败（网络不可达）", Toast.LENGTH_LONG).show();
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
                if (!silent) Toast.makeText(ctx, "已是最新（v" + moduleVersion() + "）", Toast.LENGTH_SHORT).show();
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
                b.setPositiveButton("去下载", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { openUrl(ctx, durl); }
                });
            if (!channel.isEmpty())
                b.setNeutralButton("讨论群", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { openUrl(ctx, churl); }
                });
            b.setNegativeButton("关闭", null);
            Ui.showDialog(b);
        } catch (Throwable t) {
            if (!silent) Toast.makeText(ctx, "更新信息解析失败", Toast.LENGTH_SHORT).show();
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
        tv.setTextColor(Ui.titleColor(ctx));
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        int p = Ui.dp(ctx, 12);
        tv.setPadding(p, p, p, p);
        tv.setTextIsSelectable(true);
        ScrollView sv = new ScrollView(ctx);
        sv.addView(tv);
        Ui.showDialog(new AlertDialog.Builder(ctx)
                .setTitle("诊断日志")
                .setView(sv)
                .setPositiveButton("复制到剪贴板", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        try {
                            String header = "【FeishuKit 诊断日志】\n";
                            android.content.ClipboardManager cm =
                                    (android.content.ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("FeishuKit_log", header + log));
                            Toast.makeText(ctx, "已复制，粘贴到聊天发给作者即可", Toast.LENGTH_LONG).show();
                        } catch (Throwable t) {
                            Toast.makeText(ctx, "复制失败: " + t, Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNeutralButton("清空", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        Diag.clear();
                        Toast.makeText(ctx, "日志已清空", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("关闭", null));
    }

    /**
     * 后台消息存档: 读 notif_archive.txt(每行 "时间\\t发送人\\t正文"), 倒序显示 + 复制 + 清空。
     * 救「后台/离线被撤回」的消息 —— 撤回后服务器不下发原文, 但原文弹过通知, 在这里能翻到。
     */
    static void showNotifArchive(final Context ctx) {
        final String content = NotifArchive.read();
        if (content.trim().isEmpty()) {
            Ui.showDialog(new AlertDialog.Builder(ctx).setTitle("后台消息存档")
                    .setMessage("暂无记录。\n\n开启「后台消息存档」后，后台/离线时被撤回的消息会在这里留底。\n前提：飞书通知开启「消息预览」。")
                    .setPositiveButton("关闭", null));
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
        tv.setTextColor(Ui.titleColor(ctx));
        int p = Ui.dp(ctx, 12);
        tv.setPadding(p, p, p, p);
        tv.setTextIsSelectable(true);
        ScrollView sv = new ScrollView(ctx);
        sv.addView(tv);
        Ui.showDialog(new AlertDialog.Builder(ctx)
                .setTitle("后台消息存档")
                .setView(sv)
                .setPositiveButton("复制全部", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        try {
                            android.content.ClipboardManager cm =
                                    (android.content.ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("notif_archive", content));
                            Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show();
                        } catch (Throwable t) { }
                    }
                })
                .setNeutralButton("清空", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        NotifArchive.clear();
                        Toast.makeText(ctx, "存档已清空", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("关闭", null));
    }

    /** 退群/被移除记录: 读 leave_log.txt(每行 "时间\\t文案"), 倒序显示 + 复制 + 清空。 */
    static void showLeaveLog(final Context ctx) {
        final File f = AccountPaths.resolveMessageFile(ctx, PKG, AccountPaths.currentUid, "leave_log.txt");
        String content = "";
        try { if (f.exists()) content = new String(Diag.readBytes(f), "UTF-8"); } catch (Throwable ignored) {}
        if (content.trim().isEmpty()) {
            Ui.showDialog(new AlertDialog.Builder(ctx).setTitle("退群/移除记录")
                    .setMessage("暂无记录。开启「退群提醒」后会记到这里。")
                    .setPositiveButton("关闭", null));
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
        tv.setTextColor(Ui.titleColor(ctx));
        int p = Ui.dp(ctx, 12);
        tv.setPadding(p, p, p, p);
        tv.setTextIsSelectable(true);
        ScrollView sv = new ScrollView(ctx);
        sv.addView(tv);
        Ui.showDialog(new AlertDialog.Builder(ctx)
                .setTitle("退群/移除记录（" + cnt + "）")
                .setView(sv)
                .setPositiveButton("复制", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        try {
                            android.content.ClipboardManager cm =
                                    (android.content.ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("FeishuKit_leave", full));
                            Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show();
                        } catch (Throwable t) { }
                    }
                })
                .setNeutralButton("清空", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        try { f.delete(); } catch (Throwable t) { }
                        Toast.makeText(ctx, "已清空", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("关闭", null));
    }

    // ── 被踢群聊天记录（异步加载 + ListView ViewHolder + 200ms 防抖） ──

    private static final class KickedExportItem {
        final File file;
        final String display;
        final String haystack;
        KickedExportItem(File file, String display, String haystack) {
            this.file = file;
            this.display = display;
            this.haystack = haystack;
        }
    }

    private static final class KickedExportsAdapter extends BaseAdapter {
        private final Context ctx;
        private List<KickedExportItem> items = Collections.emptyList();

        KickedExportsAdapter(Context ctx) { this.ctx = ctx; }

        void setItems(List<KickedExportItem> next) {
            this.items = next != null ? next : Collections.<KickedExportItem>emptyList();
            notifyDataSetChanged();
        }

        @Override public int getCount() { return items.size(); }
        @Override public KickedExportItem getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }

        private static final class Holder {
            final TextView title;
            Holder(TextView title) { this.title = title; }
        }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            Holder h;
            if (convertView == null) {
                TextView row = new TextView(ctx);
                row.setTextSize(15);
                row.setTextColor(Ui.ACCENT);
                row.setPadding(Ui.dp(ctx, 4), Ui.dp(ctx, 12), Ui.dp(ctx, 4), Ui.dp(ctx, 12));
                h = new Holder(row);
                row.setTag(h);
                convertView = row;
            } else {
                h = (Holder) convertView.getTag();
            }
            h.title.setText(items.get(position).display);
            return convertView;
        }
    }

    private static File[] findKickedFiles(Context ctx) {
        java.io.FilenameFilter filter = new java.io.FilenameFilter() {
            @Override public boolean accept(File d, String n) {
                return n.startsWith("kicked_") && n.endsWith(".txt");
            }
        };
        File dir = AccountPaths.accountRoot(ctx, PKG, AccountPaths.currentUid);
        File[] found = dir.listFiles(filter);
        if (found == null || found.length == 0) {
            found = new File("/data/data/" + PKG + "/files").listFiles(filter);
        }
        if ((found == null || found.length == 0) && ctx != null) {
            File local = ctx.getFilesDir();
            if (local != null) found = local.listFiles(filter);
        }
        return found;
    }

    /** 被踢群聊天记录: 列出 files/kicked_*.txt, 异步加载 + ListView 复用 + 防抖搜索。 */
    static void showKickedExports(final Context ctx) {
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(ctx, 14);
        root.setPadding(p, p, p, Ui.dp(ctx, 6));

        final EditText search = createSearchInput(ctx, "搜索群名 / 消息内容");
        root.addView(search, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final TextView status = createStatusView(ctx, "正在读取…");
        root.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final ListView listView = createDialogListView(ctx, true);
        listView.setVisibility(View.GONE);
        root.addView(listView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dialogListHeight(ctx)));

        final KickedExportsAdapter adapter = new KickedExportsAdapter(ctx);
        listView.setAdapter(adapter);

        final AlertDialog dlg = Ui.showDialog(new AlertDialog.Builder(ctx)
                .setTitle("被踢群聊天记录")
                .setView(root)
                .setNegativeButton("关闭", null));

        final List<KickedExportItem> allItems = new ArrayList<KickedExportItem>();
        final boolean[] loaded = { false };

        final Runnable applyFilter = new Runnable() {
            @Override public void run() {
                if (!dlg.isShowing() || !loaded[0]) return;
                if (allItems.isEmpty()) {
                    status.setText("暂无记录。开启「保留被踢群聊天记录」后会自动导出。");
                    status.setVisibility(View.VISIBLE);
                    listView.setVisibility(View.GONE);
                    return;
                }
                String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
                List<KickedExportItem> filtered;
                if (q.isEmpty()) {
                    filtered = allItems;
                } else {
                    filtered = new ArrayList<KickedExportItem>();
                    for (int i = 0; i < allItems.size(); i++) {
                        KickedExportItem item = allItems.get(i);
                        if (item.haystack.indexOf(q) >= 0) filtered.add(item);
                    }
                }
                adapter.setItems(filtered);
                if (filtered.isEmpty()) {
                    status.setText("无匹配");
                    status.setVisibility(View.VISIBLE);
                    listView.setVisibility(View.GONE);
                } else {
                    status.setVisibility(View.GONE);
                    listView.setVisibility(View.VISIBLE);
                }
            }
        };

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                MAIN.removeCallbacks(applyFilter);
                MAIN.postDelayed(applyFilter, SEARCH_DEBOUNCE_MS);
            }
        });

        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                KickedExportItem item = adapter.getItem(position);
                if (item != null) {
                    dlg.dismiss();
                    showKickedFile(ctx, item.file);
                }
            }
        });

        dlg.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface d) {
                MAIN.removeCallbacks(applyFilter);
            }
        });

        IO.execute(new Runnable() {
            @Override public void run() {
                final List<KickedExportItem> built = new ArrayList<KickedExportItem>();
                try {
                    File[] files = findKickedFiles(ctx);
                    if (files != null && files.length > 0) {
                        java.util.Arrays.sort(files, new Comparator<File>() {
                            @Override public int compare(File a, File b) {
                                return Long.compare(b.lastModified(), a.lastModified());
                            }
                        });
                        for (int i = 0; i < files.length; i++) {
                            File f = files[i];
                            String label = f.getName();
                            String content = "";
                            try {
                                content = new String(Diag.readBytes(f), "UTF-8");
                                int nl = content.indexOf('\n');
                                String first = nl > 0 ? content.substring(0, nl) : content;
                                if (first.startsWith("群: ")) label = first.substring(3).trim();
                            } catch (Throwable ignored) { }
                            String display = label + "   (" + (f.length() / 1024 + 1) + "KB)";
                            String haystack = (label + " " + content).toLowerCase(Locale.ROOT);
                            built.add(new KickedExportItem(f, display, haystack));
                        }
                    }
                } catch (Throwable t) {
                    android.util.Log.w("FeishuKit", "showKickedExports load err " + t);
                }
                MAIN.post(new Runnable() {
                    @Override public void run() {
                        if (!dlg.isShowing()) return;
                        allItems.clear();
                        allItems.addAll(built);
                        loaded[0] = true;
                        dlg.setTitle("被踢群聊天记录（" + allItems.size() + "）");
                        applyFilter.run();
                    }
                });
            }
        });
    }

    // ── 单个被踢群导出文件（异步加载 + ListView 双类型 ViewHolder） ──

    private static final class KickedMsgItem {
        final boolean isSystem;
        final String header;
        final String body;
        KickedMsgItem(boolean isSystem, String header, String body) {
            this.isSystem = isSystem;
            this.header = header;
            this.body = body;
        }
    }

    private static final class KickedMsgAdapter extends BaseAdapter {
        private static final int TYPE_SYSTEM = 0;
        private static final int TYPE_BUBBLE = 1;

        private final Context ctx;
        private List<KickedMsgItem> items = Collections.emptyList();

        KickedMsgAdapter(Context ctx) { this.ctx = ctx; }

        void setItems(List<KickedMsgItem> next) {
            this.items = next != null ? next : Collections.<KickedMsgItem>emptyList();
            notifyDataSetChanged();
        }

        @Override public int getCount() { return items.size(); }
        @Override public KickedMsgItem getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int position) {
            return items.get(position).isSystem ? TYPE_SYSTEM : TYPE_BUBBLE;
        }
        @Override public boolean areAllItemsEnabled() { return false; }
        @Override public boolean isEnabled(int position) { return false; }

        private static final class SystemHolder {
            final TextView text;
            SystemHolder(TextView text) { this.text = text; }
        }

        private static final class BubbleHolder {
            final TextView header;
            final TextView body;
            BubbleHolder(TextView header, TextView body) {
                this.header = header;
                this.body = body;
            }
        }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            KickedMsgItem item = items.get(position);
            if (item.isSystem) {
                SystemHolder sh;
                if (convertView == null) {
                    TextView sv = new TextView(ctx);
                    sv.setTextSize(10);
                    sv.setTextColor(Ui.subColor(ctx));
                    sv.setGravity(Gravity.CENTER);
                    sv.setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 4), Ui.dp(ctx, 8), Ui.dp(ctx, 4));
                    sh = new SystemHolder(sv);
                    sv.setTag(sh);
                    convertView = sv;
                } else {
                    sh = (SystemHolder) convertView.getTag();
                }
                sh.text.setText(item.body);
                return convertView;
            }
            BubbleHolder bh;
            if (convertView == null) {
                LinearLayout wrap = new LinearLayout(ctx);
                wrap.setOrientation(LinearLayout.VERTICAL);
                wrap.setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 4), Ui.dp(ctx, 10), Ui.dp(ctx, 4));

                TextView h = new TextView(ctx);
                h.setTextSize(11);
                h.setTextColor(Ui.subColor(ctx));
                h.setPadding(Ui.dp(ctx, 4), Ui.dp(ctx, 4), 0, Ui.dp(ctx, 2));
                wrap.addView(h);

                TextView bub = new TextView(ctx);
                bub.setTextSize(15);
                bub.setTextColor(Ui.titleColor(ctx));
                bub.setTextIsSelectable(true);
                bub.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 8), Ui.dp(ctx, 12), Ui.dp(ctx, 8));
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(Ui.isNight(ctx) ? 0xFF2B3A55 : 0xFFE8F0FE);
                bg.setCornerRadius(Ui.dp(ctx, 14));
                bub.setBackground(bg);

                LinearLayout row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.addView(bub);
                wrap.addView(row);

                bh = new BubbleHolder(h, bub);
                wrap.setTag(bh);
                convertView = wrap;
            } else {
                bh = (BubbleHolder) convertView.getTag();
            }
            bh.header.setText(item.header);
            bh.body.setText(item.body);
            return convertView;
        }
    }

    /** 单个被踢群导出文件：异步解析 + ListView 气泡渲染 + 复制/分享全文。 */
    static void showKickedFile(final Context ctx, final File file) {
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);

        final TextView status = createStatusView(ctx, "正在读取…");
        root.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final ListView listView = createDialogListView(ctx, false);
        listView.setVisibility(View.GONE);
        root.addView(listView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dialogListHeight(ctx)));

        final KickedMsgAdapter adapter = new KickedMsgAdapter(ctx);
        listView.setAdapter(adapter);

        final String[] shareHolder = { null };
        final AlertDialog dlg = Ui.showDialog(new AlertDialog.Builder(ctx)
                .setTitle(file.getName())
                .setView(root)
                .setPositiveButton("复制", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        if (shareHolder[0] == null) {
                            Toast.makeText(ctx, "正在读取中…", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        try {
                            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                                    ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("kicked", shareHolder[0]));
                            Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show();
                        } catch (Throwable t) { }
                    }
                })
                .setNeutralButton("分享", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        if (shareHolder[0] == null) {
                            Toast.makeText(ctx, "正在读取中…", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        try {
                            Intent s = new Intent(Intent.ACTION_SEND);
                            s.setType("text/plain");
                            s.putExtra(Intent.EXTRA_TEXT, shareHolder[0]);
                            ctx.startActivity(Intent.createChooser(s, "分享被踢群记录")
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        } catch (Throwable t) { }
                    }
                })
                .setNegativeButton("关闭", null));

        IO.execute(new Runnable() {
            @Override public void run() {
                String content;
                try { content = new String(Diag.readBytes(file), "UTF-8"); }
                catch (Throwable t) { content = "读取失败: " + t; }
                String title = file.getName();
                StringBuilder share = new StringBuilder();
                final List<KickedMsgItem> items = new ArrayList<KickedMsgItem>();
                for (String line : content.split("\n")) {
                    if (line.startsWith("群: ")) { title = line.substring(3).trim(); continue; }
                    String[] p = line.split("\t", 3);
                    if (p.length < 3) continue;
                    String time = p[0], name = p[1], text = p[2];
                    share.append(name).append(" (").append(time).append("): ").append(text).append("\n");
                    if ("系统".equals(name)) {
                        String st = text.replace("{from_user}", "某人").replace("{to_chatters}", "某成员");
                        if (st.length() > 80) st = st.substring(0, 80);
                        items.add(new KickedMsgItem(true, "", "— " + st + " —"));
                    } else {
                        items.add(new KickedMsgItem(false, name + "  ·  " + time, text));
                    }
                }
                final String finalTitle = title;
                final String finalShare = "群: " + title + "\n" + share.toString();
                MAIN.post(new Runnable() {
                    @Override public void run() {
                        if (!dlg.isShowing()) return;
                        dlg.setTitle(finalTitle);
                        shareHolder[0] = finalShare;
                        if (items.isEmpty()) {
                            status.setText("(无可显示的消息)");
                            status.setVisibility(View.VISIBLE);
                            listView.setVisibility(View.GONE);
                        } else {
                            adapter.setItems(items);
                            status.setVisibility(View.GONE);
                            listView.setVisibility(View.VISIBLE);
                        }
                    }
                });
            }
        });
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
            Ui.showDialog(new AlertDialog.Builder(ctx).setTitle("赞赏 FeishuKit")
                    .setMessage("赞赏码资源缺失。可打开讨论群或项目主页支持作者。\n\n赞助纯属鼓励，与功能无关，所有功能开源免费。")
                    .setPositiveButton("关闭", null));
            return;
        }
        showReward(ctx, bmp);
    }

    /** 放大赞赏码(便于扫码/长按识别)。 */
    static void showReward(final Context ctx, Bitmap bmp) {
        ImageView iv = new ImageView(ctx);
        int sz = Ui.dp(ctx, 300);
        iv.setLayoutParams(new LinearLayout.LayoutParams(sz, sz));
        iv.setImageBitmap(bmp);
        LinearLayout wrap = new LinearLayout(ctx);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setGravity(Gravity.CENTER);
        int p = Ui.dp(ctx, 16);
        wrap.setPadding(p, p, p, p);
        wrap.addView(iv);
        Ui.showDialog(new AlertDialog.Builder(ctx)
                .setTitle("赞赏 FeishuKit")
                .setMessage("感谢支持维护与版本适配。\n微信「扫一扫 → 相册」或长按识别二维码。\n赞助纯属鼓励，与功能解锁无关。")
                .setView(wrap)
                .setPositiveButton("关闭", null));
    }

    // ── 离职名单（异步加载 + ListView ViewHolder + 200ms 防抖） ──

    private static final class ResignedRow {
        final String uid;
        final String displayText;
        final String haystack;
        final JSONObject profile;
        final JSONObject resignedEntry;

        ResignedRow(String uid, String displayText, String haystack,
                    JSONObject profile, JSONObject resignedEntry) {
            this.uid = uid;
            this.displayText = displayText;
            this.haystack = haystack;
            this.profile = profile;
            this.resignedEntry = resignedEntry;
        }
    }

    private static final class ResignedAdapter extends BaseAdapter {
        private final Context ctx;
        private List<ResignedRow> items = Collections.emptyList();

        ResignedAdapter(Context ctx) { this.ctx = ctx; }

        void setItems(List<ResignedRow> next) {
            this.items = next != null ? next : Collections.<ResignedRow>emptyList();
            notifyDataSetChanged();
        }

        @Override public int getCount() { return items.size(); }
        @Override public ResignedRow getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }

        private static final class Holder {
            final TextView text;
            Holder(TextView text) { this.text = text; }
        }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            Holder h;
            if (convertView == null) {
                TextView row = new TextView(ctx);
                row.setTextSize(14);
                row.setTextColor(Ui.titleColor(ctx));
                row.setPadding(Ui.dp(ctx, 4), Ui.dp(ctx, 12), Ui.dp(ctx, 4), Ui.dp(ctx, 12));
                h = new Holder(row);
                row.setTag(h);
                convertView = row;
            } else {
                h = (Holder) convertView.getTag();
            }
            h.text.setText(items.get(position).displayText);
            return convertView;
        }
    }

    private static final class ResignedLoadResult {
        final String revision;
        final List<ResignedRow> rows;
        ResignedLoadResult(String revision, List<ResignedRow> rows) {
            this.revision = revision;
            this.rows = rows;
        }
    }

    private static ResignedLoadResult loadResignedInBackground(String previousRevision) throws Exception {
        File rf = archiveFile("resigned_all.json");
        File pf = archiveFile("profiles.json");
        String nextRevision = archiveRevision(rf) + "|" + archiveRevision(pf);
        if (previousRevision != null && previousRevision.equals(nextRevision)) {
            return null;
        }
        JSONObject profiles = pf.exists() ? new JSONObject(Config.read(pf)) : new JSONObject();
        final JSONObject resigned = ResignedArchive.combine(
                rf.exists() ? new JSONObject(Config.read(rf)) : new JSONObject(),
                profiles, System.currentTimeMillis());
        List<String> ids = new ArrayList<String>();
        Iterator<String> it = resigned.keys();
        while (it.hasNext()) ids.add(it.next());
        Collections.sort(ids, new Comparator<String>() {
            @Override public int compare(String a, String b) {
                long ua = optLong(resigned.optJSONObject(a), "update_time");
                long ub = optLong(resigned.optJSONObject(b), "update_time");
                return Long.compare(ub, ua);
            }
        });
        List<ResignedRow> rows = new ArrayList<ResignedRow>(ids.size());
        for (int i = 0; i < ids.size(); i++) {
            String uid = ids.get(i);
            JSONObject r = resigned.optJSONObject(uid);
            JSONObject pr = profiles.optJSONObject(uid);
            String name = r != null ? r.optString("name", uid) : uid;
            StringBuilder hay = new StringBuilder(name).append(' ').append(uid);
            if (r != null) hay.append(' ').append(r.optString("en_us_name", ""));
            if (pr != null) {
                hay.append(' ').append(pr.optString("department", ""))
                        .append(' ').append(pr.optString("email", ""))
                        .append(' ').append(pr.optString("employee_id", ""))
                        .append(' ').append(pr.optString("position", ""))
                        .append(' ').append(pr.optString("leader", ""));
            }
            long rt = normalizeEpochSec(optLong(r, "update_time"));
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
            rows.add(new ResignedRow(uid, sb.toString(),
                    hay.toString().toLowerCase(Locale.ROOT), pr, r));
        }
        return new ResignedLoadResult(nextRevision, rows);
    }

    /** 富列表: 离职名单 join 资料档案, 异步加载 + ListView 复用 + 防抖搜索；点击看详情。 */
    static void showResignedList(final Context ctx) {
        try {
            ArchiveSync.requestRefresh(ctx);

            LinearLayout rootv = new LinearLayout(ctx);
            rootv.setOrientation(LinearLayout.VERTICAL);
            int p = Ui.dp(ctx, 12);
            rootv.setPadding(p, p, p, Ui.dp(ctx, 6));

            final EditText search = createSearchInput(ctx, "搜索 姓名/部门/邮箱/工号/职务");
            rootv.addView(search, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            final TextView status = createStatusView(ctx, "正在读取…");
            rootv.addView(status, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            final ListView listView = createDialogListView(ctx, true);
            listView.setVisibility(View.GONE);
            rootv.addView(listView, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dialogListHeight(ctx)));

            final ResignedAdapter adapter = new ResignedAdapter(ctx);
            listView.setAdapter(adapter);

            final AlertDialog dialog = Ui.showDialog(new AlertDialog.Builder(ctx)
                    .setTitle("离职名单")
                    .setView(rootv)
                    .setNeutralButton("刷新", null)
                    .setPositiveButton("关闭", null));

            final List<ResignedRow> allRows = new ArrayList<ResignedRow>();
            final boolean[] loaded = { false };
            final String[] revision = { null };
            final AtomicBoolean busy = new AtomicBoolean(false);

            final Runnable applyFilter = new Runnable() {
                @Override public void run() {
                    if (!dialog.isShowing() || !loaded[0]) return;
                    String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
                    List<ResignedRow> filtered;
                    if (q.isEmpty()) {
                        filtered = allRows;
                    } else {
                        filtered = new ArrayList<ResignedRow>();
                        for (int i = 0; i < allRows.size(); i++) {
                            ResignedRow r = allRows.get(i);
                            if (r.haystack.indexOf(q) >= 0) filtered.add(r);
                        }
                    }
                    adapter.setItems(filtered);
                    if (filtered.isEmpty()) {
                        status.setText(allRows.isEmpty() ? "暂无离职记录" : "无匹配结果");
                        status.setVisibility(View.VISIBLE);
                        listView.setVisibility(View.GONE);
                    } else {
                        status.setVisibility(View.GONE);
                        listView.setVisibility(View.VISIBLE);
                    }
                }
            };

            final Runnable triggerLoad = new Runnable() {
                @Override public void run() {
                    if (!dialog.isShowing()) return;
                    if (!busy.compareAndSet(false, true)) return;
                    final String prevRev = revision[0];
                    IO.execute(new Runnable() {
                        @Override public void run() {
                            try {
                                final ResignedLoadResult res = loadResignedInBackground(prevRev);
                                if (res != null) {
                                    MAIN.post(new Runnable() {
                                        @Override public void run() {
                                            if (!dialog.isShowing()) return;
                                            revision[0] = res.revision;
                                            allRows.clear();
                                            allRows.addAll(res.rows);
                                            loaded[0] = true;
                                            dialog.setTitle("离职名单（" + allRows.size() + " 人）");
                                            applyFilter.run();
                                        }
                                    });
                                }
                            } catch (Throwable t) {
                                android.util.Log.w("fucklark", "离职名单加载失败", t);
                                MAIN.post(new Runnable() {
                                    @Override public void run() {
                                        if (!dialog.isShowing() || loaded[0]) return;
                                        status.setText("读取失败，请点击刷新重试");
                                    }
                                });
                            } finally {
                                busy.set(false);
                            }
                        }
                    });
                }
            };

            search.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(Editable e) {
                    MAIN.removeCallbacks(applyFilter);
                    MAIN.postDelayed(applyFilter, SEARCH_DEBOUNCE_MS);
                }
            });

            listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                @Override public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                    ResignedRow item = adapter.getItem(position);
                    if (item != null) {
                        showProfileDetail(ctx, item.uid, item.profile, item.resignedEntry);
                    }
                }
            });

            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    ArchiveSync.requestRefresh(ctx);
                    triggerLoad.run();
                }
            });

            final Runnable poll = new Runnable() {
                @Override public void run() {
                    if (!dialog.isShowing()) return;
                    triggerLoad.run();
                    MAIN.postDelayed(this, 2000);
                }
            };

            dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override public void onDismiss(DialogInterface d) {
                    MAIN.removeCallbacks(poll);
                    MAIN.removeCallbacks(applyFilter);
                }
            });

            triggerLoad.run();
            MAIN.postDelayed(poll, 2000);
        } catch (Throwable t) {
            android.util.Log.w("FeishuKit", "showResignedList err " + t);
        }
    }

    private static String archiveRevision(File file) {
        return file.getAbsolutePath() + ":" + file.lastModified() + ":" + file.length();
    }

    static void add(StringBuilder sb, String label, String v) {
        if (v != null && !v.isEmpty()) sb.append("\n  ").append(label).append("：").append(v);
    }

    static long optLong(JSONObject o, String k) {
        if (o == null) return 0;
        try { return Long.parseLong(o.optString(k, "0")); } catch (Throwable t) { return o.optLong(k, 0); }
    }

    private static long normalizeEpochSec(long t) {
        return t > 100000000000L ? t / 1000L : t;
    }

    static String fmtDate(long sec) {
        if (sec <= 0) return "?";
        try {
            return new java.text.SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                    .format(new java.util.Date(sec * 1000L));
        } catch (Throwable t) { return "?"; }
    }

    /** 同事详情弹窗：展示所有已归档字段 + 复制全部；飞书进程内额外提供「打开飞书资料页」。 */
    static void showProfileDetail(final Context ctx, final String uid,
                                  JSONObject pr, JSONObject resignedEntry) {
        String name = "";
        if (pr != null) name = pr.optString("name", "");
        if (isEmpty(name) && resignedEntry != null) name = resignedEntry.optString("name", "");
        if (isEmpty(name)) name = uid != null ? uid : "";

        String enName = "";
        if (pr != null) enName = pr.optString("en_us_name", "");
        if (isEmpty(enName) && resignedEntry != null) enName = resignedEntry.optString("en_us_name", "");

        boolean isResigned = resignedEntry != null || (pr != null && pr.optBoolean("is_resigned", false));
        boolean isFrozen = (resignedEntry != null && resignedEntry.optBoolean("is_frozen", false))
                || (pr != null && pr.optBoolean("is_frozen", false));
        String status;
        if (isResigned) {
            status = isFrozen ? "已离职 · 已冻结" : "已离职";
        } else if (pr != null) {
            status = kindLabel(classifyProfile(pr));
        } else {
            status = "未知";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("姓名：").append(name);
        if (!isEmpty(enName) && !enName.equals(name)) sb.append(" (").append(enName).append(")");
        sb.append("\n状态：").append(status);

        if (pr != null) {
            appendDetailField(sb, "公司", pr.optString("company", ""));
            appendDetailField(sb, "部门", pr.optString("department", ""));
            appendDetailField(sb, "职务", pr.optString("position", ""));
            appendDetailField(sb, "工号", pr.optString("employee_id", ""));
            appendDetailField(sb, "邮箱", pr.optString("email", ""));
            appendDetailField(sb, "手机", pr.optString("phone", ""));
            appendDetailField(sb, "上级", pr.optString("leader", ""));
            appendDetailField(sb, "群昵称", pr.optString("nickname", ""));
        }
        appendDetailField(sb, "UID", uid);

        long ut = 0;
        if (resignedEntry != null) ut = optLong(resignedEntry, "update_time");
        if (ut <= 0 && pr != null) ut = optLong(pr, "update_time");
        ut = normalizeEpochSec(ut);
        if (ut > 0) sb.append("\n记录更新：").append(fmtDate(ut));
        if (pr == null) sb.append("\n\n（详细资料未存档——在职时打开过其资料页才会有）");

        final String detailText = sb.toString();
        TextView tv = new TextView(ctx);
        tv.setText(detailText);
        tv.setTextSize(14);
        tv.setLineSpacing(0f, 1.25f);
        tv.setTextColor(Ui.titleColor(ctx));
        tv.setTextIsSelectable(true);
        int p = Ui.dp(ctx, 16);
        tv.setPadding(p, p, p, p);
        ScrollView sv = new ScrollView(ctx);
        sv.addView(tv);

        AlertDialog.Builder builder = new AlertDialog.Builder(ctx)
                .setTitle(name)
                .setView(sv)
                .setPositiveButton("复制全部", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        try {
                            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                                    ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("FeishuKit_profile", detailText));
                            Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show();
                        } catch (Throwable t) {
                            Toast.makeText(ctx, "复制失败: " + t, Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("关闭", null);
        if (!Config.MODULE_PKG.equals(ctx.getPackageName()) && !isEmpty(uid)) {
            builder.setNeutralButton("打开飞书资料页", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    openChat(ctx, uid);
                }
            });
        }
        Ui.showDialog(builder);
    }

    private static void appendDetailField(StringBuilder sb, String label, String value) {
        if (!isEmpty(value)) sb.append("\n").append(label).append("：").append(value);
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

    // ── 全员档案分类与列表 ──────────────────────────────────────────
    // 优先级：
    //   1) 飞书真实成员类型 chatter_type == 2 (BOT) → 机器人
    //   2) 已离职 → 离职
    //   3) 非本公司租户 (is_home == false) → 外部
    //   4) 飞书真实成员类型 chatter_type == 1 (USER) → 同事（即使尚未缓存 V3 部门/邮箱）
    //   5) 兼容历史无 chatter_type 记录：有任意资料明细 → 同事；否则暂归机器人（下次花名册刷新后自动归位）
    static final String KIND_EMPLOYEE = "employee";
    static final String KIND_RESIGNED = "resigned";
    static final String KIND_EXTERNAL = "external";
    static final String KIND_BOT = "bot";
    static final int CHATTER_TYPE_UNKNOWN = 0;
    static final int CHATTER_TYPE_USER = 1;
    static final int CHATTER_TYPE_BOT = 2;

    /** 按档案字段推断类别（展示时计算，兼容旧 profiles.json 无 chatter_type 字段）。 */
    static String classifyProfile(JSONObject pr) {
        if (pr == null) return KIND_EMPLOYEE;
        int chatterType = pr.optInt("chatter_type", CHATTER_TYPE_UNKNOWN);
        if (chatterType == CHATTER_TYPE_BOT) return KIND_BOT;
        if (pr.optBoolean("is_resigned", false)) return KIND_RESIGNED;
        if (!pr.optBoolean("is_home", false)) return KIND_EXTERNAL;
        if (chatterType == CHATTER_TYPE_USER) return KIND_EMPLOYEE;
        if (!isEmpty(pr.optString("department", ""))
                || !isEmpty(pr.optString("email", ""))
                || !isEmpty(pr.optString("employee_id", ""))
                || !isEmpty(pr.optString("position", ""))
                || !isEmpty(pr.optString("leader", ""))
                || !isEmpty(pr.optString("phone", ""))) {
            return KIND_EMPLOYEE;
        }
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

    /** 全员档案人数（仅统计合法 UID 条目，排除历史并发脏键）。 */
    static int profilesCount() {
        try {
            File f = archiveFile("profiles.json");
            if (!f.exists()) return 0;
            JSONObject all = new JSONObject(Config.read(f));
            int count = 0;
            Iterator<String> it = all.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (ProfileBulk.isValidStoredKey(k) && all.optJSONObject(k) != null) count++;
            }
            return count;
        } catch (Throwable t) { return -1; }
    }

    private static final class ProfileRow {
        final String uid;
        final String kind;
        final String displayText;
        final String haystack;
        final JSONObject profile;

        ProfileRow(String uid, String kind, String displayText, String haystack, JSONObject profile) {
            this.uid = uid;
            this.kind = kind;
            this.displayText = displayText;
            this.haystack = haystack;
            this.profile = profile;
        }
    }

    private static final class ProfileAdapter extends BaseAdapter {
        private final Context ctx;
        private List<ProfileRow> items = Collections.emptyList();

        ProfileAdapter(Context ctx) { this.ctx = ctx; }

        void setItems(List<ProfileRow> next) {
            this.items = next != null ? next : Collections.<ProfileRow>emptyList();
            notifyDataSetChanged();
        }

        @Override public int getCount() { return items.size(); }
        @Override public ProfileRow getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }

        private static final class Holder {
            final TextView text;
            Holder(TextView text) { this.text = text; }
        }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            Holder h;
            if (convertView == null) {
                TextView row = new TextView(ctx);
                row.setTextSize(14);
                row.setPadding(Ui.dp(ctx, 4), Ui.dp(ctx, 12), Ui.dp(ctx, 4), Ui.dp(ctx, 12));
                h = new Holder(row);
                row.setTag(h);
                convertView = row;
            } else {
                h = (Holder) convertView.getTag();
            }
            ProfileRow item = items.get(position);
            h.text.setText(item.displayText);
            boolean night = Ui.isNight(ctx);
            int color;
            if (KIND_BOT.equals(item.kind)) color = night ? 0xFFE6C56A : 0xFFB8860B;
            else if (KIND_EXTERNAL.equals(item.kind)) color = night ? 0xFF9BB8D4 : 0xFF5B7C99;
            else if (KIND_RESIGNED.equals(item.kind)) color = night ? 0xFFE8A0A0 : 0xFFC06060;
            else color = Ui.titleColor(ctx);
            h.text.setTextColor(color);
            return convertView;
        }
    }

    private static final class ProfilesLoadResult {
        final List<ProfileRow> rows;
        final int nEmp, nRes, nExt, nBot;
        ProfilesLoadResult(List<ProfileRow> rows, int nEmp, int nRes, int nExt, int nBot) {
            this.rows = rows;
            this.nEmp = nEmp;
            this.nRes = nRes;
            this.nExt = nExt;
            this.nBot = nBot;
        }
    }

    private static ProfilesLoadResult loadProfilesInBackground() throws Exception {
        File pf = archiveFile("profiles.json");
        final JSONObject profiles = pf.exists() ? new JSONObject(Config.read(pf)) : new JSONObject();
        // 自动自愈清洗历史脏键（如旧版并发写盘拼串导致的超长 hex 伪 UID）
        int cleaned = ProfileBulk.pruneCorruptedEntries(profiles);
        if (cleaned > 0 && pf.isFile()) {
            try {
                synchronized (Config.PROFILES_LOCK) {
                    JSONObject disk = pf.length() > 0 ? new JSONObject(Config.read(pf)) : new JSONObject();
                    if (ProfileBulk.pruneCorruptedEntries(disk) > 0) {
                        ProfileBulk.writeAtomic(pf, disk.toString(1));
                    }
                }
            } catch (Throwable ignored) { }
        }
        List<String> ids = new ArrayList<String>();
        Iterator<String> it = profiles.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (ProfileBulk.isValidStoredKey(k)) ids.add(k);
        }
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
        List<ProfileRow> rows = new ArrayList<ProfileRow>(ids.size());
        for (int i = 0; i < ids.size(); i++) {
            String uid = ids.get(i);
            JSONObject pr = profiles.optJSONObject(uid);
            if (pr == null) continue;
            String kind = classifyProfile(pr);
            if (KIND_RESIGNED.equals(kind)) nRes++;
            else if (KIND_EXTERNAL.equals(kind)) nExt++;
            else if (KIND_BOT.equals(kind)) nBot++;
            else nEmp++;

            String name = pr.optString("name", uid);
            String company = pr.optString("company", "");
            StringBuilder hay = new StringBuilder(name).append(' ').append(uid)
                    .append(' ').append(pr.optString("department", ""))
                    .append(' ').append(pr.optString("email", ""))
                    .append(' ').append(pr.optString("employee_id", ""))
                    .append(' ').append(pr.optString("position", ""))
                    .append(' ').append(pr.optString("leader", ""))
                    .append(' ').append(pr.optString("phone", ""))
                    .append(' ').append(company);

            StringBuilder sb = new StringBuilder();
            sb.append(kindIcon(kind)).append(' ').append(name);
            sb.append("  · ").append(kindLabel(kind));
            if (KIND_RESIGNED.equals(kind)) {
                if (pr.optBoolean("is_frozen", false)) sb.append(" · 已冻结");
                add(sb, "部门", pr.optString("department", ""));
                add(sb, "职务", pr.optString("position", ""));
                add(sb, "邮箱", pr.optString("email", ""));
                add(sb, "工号", pr.optString("employee_id", ""));
                long ut = normalizeEpochSec(optLong(pr, "update_time"));
                if (ut > 0) sb.append("\n  记录更新 ").append(fmtDate(ut));
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
            rows.add(new ProfileRow(uid, kind, sb.toString(),
                    hay.toString().toLowerCase(Locale.ROOT), pr));
        }
        return new ProfilesLoadResult(rows, nEmp, nRes, nExt, nBot);
    }

    /** 全员档案：顶部页签 + 异步加载 + ListView 复用 + 防抖搜索。 */
    static void showAllProfiles(final Context ctx) {
        try {
            final LinearLayout root = new LinearLayout(ctx);
            root.setOrientation(LinearLayout.VERTICAL);
            int p = Ui.dp(ctx, 10);
            root.setPadding(p, p, p, p);

            final String[] tabKinds = {
                    KIND_EMPLOYEE, KIND_RESIGNED, KIND_EXTERNAL, KIND_BOT, ""
            };
            final String[] initialTitles = {
                    "同事", "离职", "外部", "机器人", "全部"
            };
            final int[] tabIdx = { 0 };

            LinearLayout topBar = new LinearLayout(ctx);
            topBar.setOrientation(LinearLayout.HORIZONTAL);
            topBar.setGravity(Gravity.CENTER_VERTICAL);
            TextView prev = new TextView(ctx);
            prev.setText("‹");
            prev.setTextSize(22);
            prev.setTextColor(Ui.subColor(ctx));
            prev.setPadding(Ui.dp(ctx, 6), Ui.dp(ctx, 10), Ui.dp(ctx, 6), Ui.dp(ctx, 10));
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
            next.setPadding(Ui.dp(ctx, 6), Ui.dp(ctx, 10), Ui.dp(ctx, 6), Ui.dp(ctx, 10));
            topBar.addView(next);
            root.addView(topBar, new LinearLayout.LayoutParams(-1, -2));

            TextView hint = new TextView(ctx);
            hint.setText(AccountPaths.label(ctx)
                    + "\n归类：优先按飞书真实成员类型区分同事与机器人；离职/外部单独页签。\n"
                    + "档案与消息按飞书账号隔离，切换账号后各看各的。\n"
                    + "点击顶部页签或 ‹ › 切换分类，上下滑动列表。");
            hint.setTextSize(11);
            hint.setTextColor(Ui.subColor(ctx));
            hint.setPadding(Ui.dp(ctx, 4), Ui.dp(ctx, 2), Ui.dp(ctx, 4), Ui.dp(ctx, 6));
            root.addView(hint);

            final EditText search = createSearchInput(ctx, "搜索 姓名/部门/邮箱/工号/职务");
            root.addView(search, new LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT));

            final TextView status = createStatusView(ctx, "正在读取…");
            root.addView(status, new LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT));

            final ListView listView = createDialogListView(ctx, true);
            listView.setVisibility(View.GONE);
            root.addView(listView, new LinearLayout.LayoutParams(-1, dialogListHeight(ctx)));

            final ProfileAdapter adapter = new ProfileAdapter(ctx);
            listView.setAdapter(adapter);

            final List<ProfileRow> allRows = new ArrayList<ProfileRow>();
            final boolean[] loaded = { false };

            final AlertDialog dialog = Ui.showDialog(new AlertDialog.Builder(ctx)
                    .setTitle("全员档案")
                    .setView(root)
                    .setPositiveButton("关闭", null));

            final Runnable applyFilter = new Runnable() {
                @Override public void run() {
                    if (!dialog.isShowing() || !loaded[0]) return;
                    String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
                    String kindFilter = tabKinds[tabIdx[0]];
                    Set<String> allow = null;
                    if (kindFilter != null && kindFilter.length() > 0) {
                        allow = new HashSet<String>();
                        for (String k : kindFilter.split(",")) if (k.length() > 0) allow.add(k);
                    }
                    List<ProfileRow> filtered = new ArrayList<ProfileRow>();
                    for (int i = 0; i < allRows.size(); i++) {
                        ProfileRow row = allRows.get(i);
                        if (allow != null && !allow.contains(row.kind)) continue;
                        if (!q.isEmpty() && row.haystack.indexOf(q) < 0) continue;
                        filtered.add(row);
                    }
                    adapter.setItems(filtered);
                    if (filtered.isEmpty()) {
                        status.setText(allRows.isEmpty() ? "暂无档案数据" : "无匹配结果");
                        status.setVisibility(View.VISIBLE);
                        listView.setVisibility(View.GONE);
                    } else {
                        status.setVisibility(View.GONE);
                        listView.setVisibility(View.VISIBLE);
                    }
                }
            };

            final TextView[] tabViews = new TextView[initialTitles.length];
            for (int i = 0; i < initialTitles.length; i++) {
                final int idx = i;
                TextView tab = new TextView(ctx);
                tabViews[i] = tab;
                tab.setText(initialTitles[i]);
                tab.setTextSize(13);
                tab.setGravity(Gravity.CENTER);
                tab.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 8), Ui.dp(ctx, 12), Ui.dp(ctx, 8));
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                tlp.leftMargin = Ui.dp(ctx, 4);
                tlp.rightMargin = Ui.dp(ctx, 4);
                tabs.addView(tab, tlp);
                tab.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        tabIdx[0] = idx;
                        refreshTabUi(ctx, tabViews, tabIdx[0], hs);
                        MAIN.removeCallbacks(applyFilter);
                        applyFilter.run();
                    }
                });
            }

            prev.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    int i = tabIdx[0] - 1;
                    if (i < 0) i = tabViews.length - 1;
                    tabIdx[0] = i;
                    refreshTabUi(ctx, tabViews, i, hs);
                    MAIN.removeCallbacks(applyFilter);
                    applyFilter.run();
                }
            });
            next.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    int i = (tabIdx[0] + 1) % tabViews.length;
                    tabIdx[0] = i;
                    refreshTabUi(ctx, tabViews, i, hs);
                    MAIN.removeCallbacks(applyFilter);
                    applyFilter.run();
                }
            });

            search.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(Editable e) {
                    MAIN.removeCallbacks(applyFilter);
                    MAIN.postDelayed(applyFilter, SEARCH_DEBOUNCE_MS);
                }
            });

            listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                @Override public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                    ProfileRow item = adapter.getItem(position);
                    if (item != null) {
                        showProfileDetail(ctx, item.uid, item.profile, null);
                    }
                }
            });

            dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override public void onDismiss(DialogInterface d) {
                    MAIN.removeCallbacks(applyFilter);
                }
            });

            refreshTabUi(ctx, tabViews, 0, hs);

            IO.execute(new Runnable() {
                @Override public void run() {
                    try {
                        final ProfilesLoadResult res = loadProfilesInBackground();
                        MAIN.post(new Runnable() {
                            @Override public void run() {
                                if (!dialog.isShowing()) return;
                                allRows.clear();
                                allRows.addAll(res.rows);
                                loaded[0] = true;
                                dialog.setTitle("全员档案（" + allRows.size() + "）");
                                tabViews[0].setText("同事 " + res.nEmp);
                                tabViews[1].setText("离职 " + res.nRes);
                                tabViews[2].setText("外部 " + res.nExt);
                                tabViews[3].setText("机器人 " + res.nBot);
                                tabViews[4].setText("全部");
                                applyFilter.run();
                            }
                        });
                    } catch (Throwable t) {
                        android.util.Log.w("FeishuKit", "showAllProfiles load err " + t);
                        MAIN.post(new Runnable() {
                            @Override public void run() {
                                if (!dialog.isShowing()) return;
                                status.setText("读取失败，请重试");
                            }
                        });
                    }
                }
            });
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

    /** 记录页后台刷新人数文本；不阻塞 UI 线程，不重建页签、列表和已打开的对话框。 */
    static View archiveCountRow(final Context ctx, final boolean resigned, View.OnClickListener click) {
        final View row = Ui.navRow(ctx, resigned ? "离职名单" : "全员档案", "正在读取…", Ui.ACCENT, click);
        final TextView subtitle = (TextView) ((ViewGroup) ((ViewGroup) row).getChildAt(0)).getChildAt(1);
        final String[] revision = { null };
        final AtomicBoolean busy = new AtomicBoolean(false);
        final Runnable reload = new Runnable() {
            @Override public void run() {
                if (!row.isAttachedToWindow()) return;
                if (busy.compareAndSet(false, true)) {
                    final String prevRev = revision[0];
                    IO.execute(new Runnable() {
                        @Override public void run() {
                            try {
                                final String next = archiveRevision(archiveFile("profiles.json"))
                                        + (resigned ? "|" + archiveRevision(archiveFile("resigned_all.json")) : "");
                                if (!next.equals(prevRev)) {
                                    final int count = resigned ? resignCount() : profilesCount();
                                    revision[0] = next;
                                    MAIN.post(new Runnable() {
                                        @Override public void run() {
                                            if (row.isAttachedToWindow()) {
                                                subtitle.setText(count >= 0 ? count + " 人" : "读取失败，请刷新重试");
                                            }
                                        }
                                    });
                                }
                            } finally {
                                busy.set(false);
                            }
                        }
                    });
                }
                MAIN.postDelayed(this, 2000);
            }
        };
        row.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) { MAIN.removeCallbacks(reload); MAIN.post(reload); }
            @Override public void onViewDetachedFromWindow(View view) { MAIN.removeCallbacks(reload); }
        });
        return row;
    }

    /** 离职名单人数（读 resigned_all.json 顶层键数）。 */
    static int resignCount() {
        try {
            File f = archiveFile("resigned_all.json");
            File profiles = archiveFile("profiles.json");
            JSONObject o = f.isFile() ? new JSONObject(Config.read(f)) : new JSONObject();
            JSONObject p = profiles.isFile() ? new JSONObject(Config.read(profiles)) : new JSONObject();
            return ResignedArchive.combine(o, p, System.currentTimeMillis()).length();
        } catch (Throwable t) { return -1; }
    }

    static String moduleApkPath() {
        try {
            Class<?> c = Class.forName("com.chekayo.feishuantirecall.AntiRecall");
            Object v = c.getField("MODULE_PATH").get(null);
            return v == null ? null : String.valueOf(v);
        } catch (Throwable t) { return null; }
    }

    static String moduleVersion() { return ModuleBuild.VERSION; }
    static int moduleVersionCode() { return ModuleBuild.VERSION_CODE; }
}
