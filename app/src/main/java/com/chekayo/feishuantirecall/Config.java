package com.chekayo.feishuantirecall;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

import org.json.JSONObject;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/**
 * FeishuKit 统一配置：进程内共享开关（设置面板与各 hook 同在飞书进程，直接读写内存 flag + 持久化 JSON）。
 * 改开关即时生效，无需重装/重启。用固定路径, 三处(AntiRecall/ResignTracker/设置面板)都能直接调。
 *
 * 存储布局（双副本，同一 schema）：
 *   - 模块进程：  /data/data/com.chekayo.feishuantirecall/files/fucklark_cfg.json
 *   - 飞书进程：  /data/data/&lt;lark&gt;/files/fucklark_cfg.json
 *
 * 同步协议（防死循环/掉帧）：
 * 1. 仅用户改动（set/setStr/setDismissed）才 broadcast；
 * 2. 接收端只 apply，绝不回推（回推曾导致广播风暴）；
 * 3. apply 保留对端 updatedAt，不抬到 now（否则双方永远"我更新"）；
 * 4. 不向本包名广播；
 * 5. 飞书启动经 ContentProvider 拉模块权威配置一次；
 * 6. 广播带最小间隔防抖，避免多进程启动连环发送。
 */
public class Config {
    public static final String ACTION_SYNC = "com.chekayo.feishuantirecall.CONFIG_SYNC";
    private static volatile Context appContext;
    private static volatile long updatedAt;
    public static volatile boolean antirecall = true;   // 防撤回
    public static volatile boolean resign = true;       // 离职统计
    public static volatile boolean antiread = false;    // 防对方已读 (清空 UPDATE_MESSAGES_ME_READ 的 message_ids/fold_ids)
    public static volatile boolean diaglog = false;     // 诊断日志 (关=完全不写文件)
    public static volatile boolean keepkicked = false;  // 保留被踢群聊天记录
    public static volatile boolean leavenotify = false; // 退群/被移除提醒 (Toast + 记录)
    public static volatile boolean orgwalker = true;   // 组织架构自动巡游 (AccessibilityService, 需在系统无障碍里开启)
    public static volatile boolean notifarchive = false; // 后台消息存档 (hook 通知抓正文, 救后台被撤回的消息)
    public static volatile boolean dewatermark = false; // 去除聊天水印 (hook View.setForeground 丢弃 watermark 前景)
    public static volatile boolean downloadunlock = false; // 解除文件/图片下载限制 (加密聊天禁另存 -> 强制放行)
    public static volatile boolean restrictunlock = false; // 解除保密模式复制/转发限制 (RestrictedMode 门禁拦截器 -> 全放行)
    public static volatile boolean pubdownload = false;    // 下载的文件同时另存到系统「下载」(MediaStore, 符合安卓规范)
    public static volatile boolean screenshotnoaudit = false; // 截图不上报 (no-op 截图审计检测器 ActivityObserver.onActivityResumed)
    public static volatile boolean forcescreenshot = false;   // 强制截图 (剥离 FLAG_SECURE / SurfaceView.setSecure, 仅飞书进程内)
    public static volatile boolean noauditall = false;        // 全审计无痕总闸 (no-op AuditEventStorage.writeData(Event), 一处掐全部上报)
    public static volatile String pubdownloadSubdir = "Lark";  // 公共下载子目录名 (空=直接 Download/; 默认 Download/Lark)
    public static volatile boolean updatebanner = true;  // 主页顶部更新横幅(有新版时提示)
    public static volatile int dismissedUpc = 0;         // 已忽略的更新 versionCode(× 关闭后记住, 不再唠叨)
    public static volatile boolean blockaipeek = true;   // 屏蔽 AI 总结「消息速览」浮层（整条）
    // ── 防撤回展示选项 ──
    public static volatile boolean showRecallHint = true;      // 是否展示「xxx撤回了一条消息」提示
    public static volatile boolean recallHintOriginal = true;  // 撤回提示是否附带原文
    public static volatile String recallHintText = "撤回了一条消息"; // 可自定义提示文案（可用 {name} 代表发送人）

    // 由 AntiRecall.startNative / LauncherActivity / SettingsPanel 调 setFilesDir 初始化(按当前目标包, 国内/国际版自适应)。
    static volatile File cfgFile;
    /** 防抖：避免 onResume / 多进程启动时连环 broadcast。 */
    private static volatile long lastBroadcastAt;
    private static final long BROADCAST_MIN_INTERVAL_MS = 400;

    /** 保存应用级 Context，用于向飞书进程发送配置同步广播。 */
    public static void setContext(Context c) { if (c != null) appContext = c.getApplicationContext(); }

    /** 供 ArchiveSync 等取 Context（可能尚未 setContext，则回落 AndroidAppHelper）。 */
    static Context appContextForSync() {
        Context c = appContext;
        if (c != null) return c;
        try {
            Object app = Class.forName("android.app.AndroidAppHelper")
                    .getMethod("currentApplication").invoke(null);
            if (app instanceof Context) {
                setContext((Context) app);
                return appContext;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** 读文件全部字节；失败返回 null。 */
    static byte[] readBytes(File f) {
        try {
            FileInputStream is = new FileInputStream(f);
            try {
                byte[] b = new byte[(int) f.length()];
                int off = 0, r;
                while (off < b.length && (r = is.read(b, off, b.length - off)) > 0) off += r;
                return b;
            } finally { is.close(); }
        } catch (Throwable t) { return null; }
    }

    /** 生成完整配置快照，供导出、剪贴板复制和跨进程同步使用。 */
    public static synchronized String snapshot() {
        try {
            JSONObject o = new JSONObject();
            o.put("schema", 1);
            o.put("updatedAt", updatedAt);
            o.put("antirecall",antirecall); o.put("resign",resign); o.put("antiread",antiread);
            o.put("diaglog",diaglog); o.put("keepkicked",keepkicked); o.put("leavenotify",leavenotify);
            o.put("orgwalker",orgwalker); o.put("notifarchive",notifarchive); o.put("dewatermark",dewatermark);
            o.put("downloadunlock",downloadunlock); o.put("restrictunlock",restrictunlock); o.put("pubdownload",pubdownload);
            o.put("screenshotnoaudit",screenshotnoaudit); o.put("forcescreenshot",forcescreenshot); o.put("noauditall",noauditall);
            o.put("pubdownloadSubdir",pubdownloadSubdir); o.put("updatebanner",updatebanner); o.put("dismissedUpc",dismissedUpc);
            o.put("showRecallHint", showRecallHint);
            o.put("recallHintOriginal", recallHintOriginal);
            o.put("recallHintText", recallHintText);
            o.put("blockaipeek", blockaipeek);
            return o.toString();
        } catch (Throwable t) { return "{}"; }
    }

    /**
     * 校验并应用配置快照。保留对端 updatedAt，不抬到 now。
     * @return true=已写入本地（incoming 更新或首次）；false=本地更新或解析失败，未覆盖。
     */
    public static synchronized boolean applySnapshot(String s) {
        try {
            if (s == null || s.isEmpty()) return false;
            JSONObject o = new JSONObject(s);
            if (o.length() == 0) return false;
            long incoming = o.optLong("updatedAt", 0);
            // 本地更新 → 不覆盖（接收端禁止回推，否则广播风暴）
            if (incoming > 0 && incoming < updatedAt) return false;
            if (incoming > 0) updatedAt = incoming;
            antirecall=o.optBoolean("antirecall",true); resign=o.optBoolean("resign",true); antiread=o.optBoolean("antiread",false);
            diaglog=o.optBoolean("diaglog",false); keepkicked=o.optBoolean("keepkicked",false); leavenotify=o.optBoolean("leavenotify",false);
            orgwalker=o.optBoolean("orgwalker",true); notifarchive=o.optBoolean("notifarchive",false); dewatermark=o.optBoolean("dewatermark",false);
            downloadunlock=o.optBoolean("downloadunlock",false); restrictunlock=o.optBoolean("restrictunlock",false); pubdownload=o.optBoolean("pubdownload",false);
            screenshotnoaudit=o.optBoolean("screenshotnoaudit",false); forcescreenshot=o.optBoolean("forcescreenshot",false); noauditall=o.optBoolean("noauditall",false);
            pubdownloadSubdir=o.optString("pubdownloadSubdir","Lark"); updatebanner=o.optBoolean("updatebanner",true); dismissedUpc=o.optInt("dismissedUpc",0);
            showRecallHint = o.optBoolean("showRecallHint", true);
            recallHintOriginal = o.optBoolean("recallHintOriginal", true);
            recallHintText = o.optString("recallHintText", "撤回了一条消息");
            blockaipeek = o.optBoolean("blockaipeek", true);
            save();
            return true;
        } catch (Throwable t) { return false; }
    }

    /** 本地 updatedAt（供同步协议判断谁更新）。 */
    public static synchronized long updatedAt() { return updatedAt; }

    public static void setFilesDir(File filesDir) {
        if (filesDir == null) return;
        cfgFile = new File(filesDir, "fucklark_cfg.json");
    }

    // profiles.json 由 ProfileCapture(扒UI) 与 ProfileBulk(批量解blob) 两处读改写, 共用此锁互斥, 防并发写坏
    public static final Object PROFILES_LOCK = new Object();

    public static synchronized void load() {
        try {
            if (cfgFile != null && cfgFile.exists()) {
                JSONObject o = new JSONObject(read(cfgFile));
                updatedAt = o.optLong("updatedAt", 0);
                // 旧版文件无 updatedAt：用文件 mtime 兜底，避免被模块侧"更新时间戳"误杀
                if (updatedAt <= 0) {
                    long m = cfgFile.lastModified();
                    if (m > 0) updatedAt = m;
                }
                antirecall = o.optBoolean("antirecall", true);
                resign = o.optBoolean("resign", true);
                antiread = o.optBoolean("antiread", false);
                diaglog = o.optBoolean("diaglog", false);
                keepkicked = o.optBoolean("keepkicked", false);
                leavenotify = o.optBoolean("leavenotify", false);
                orgwalker = o.optBoolean("orgwalker", true);
                notifarchive = o.optBoolean("notifarchive", false);
                dewatermark = o.optBoolean("dewatermark", false);
                downloadunlock = o.optBoolean("downloadunlock", false);
                restrictunlock = o.optBoolean("restrictunlock", false);
                pubdownload = o.optBoolean("pubdownload", false);
                screenshotnoaudit = o.optBoolean("screenshotnoaudit", false);
                forcescreenshot = o.optBoolean("forcescreenshot", false);
                noauditall = o.optBoolean("noauditall", false);
                pubdownloadSubdir = o.optString("pubdownloadSubdir", "Lark");
                updatebanner = o.optBoolean("updatebanner", true);
                dismissedUpc = o.optInt("dismissedUpc", 0);
                showRecallHint = o.optBoolean("showRecallHint", true);
                recallHintOriginal = o.optBoolean("recallHintOriginal", true);
                recallHintText = o.optString("recallHintText", "撤回了一条消息");
                blockaipeek = o.optBoolean("blockaipeek", true);
            } else {
                // 首次无本地文件：不立刻 save（多进程会互相覆盖成默认值）。由 sync 对齐后再落盘。
            }
        } catch (Throwable ignored) {}
    }

    /** 修改单个布尔配置并立即持久化、广播同步。值未变则不写不广播。 */
    public static synchronized void set(String key, boolean v) {
        boolean changed = false;
        if ("antirecall".equals(key)) { changed = antirecall != v; antirecall = v; }
        else if ("resign".equals(key)) { changed = resign != v; resign = v; }
        else if ("antiread".equals(key)) { changed = antiread != v; antiread = v; }
        else if ("diaglog".equals(key)) { changed = diaglog != v; diaglog = v; }
        else if ("keepkicked".equals(key)) { changed = keepkicked != v; keepkicked = v; }
        else if ("leavenotify".equals(key)) { changed = leavenotify != v; leavenotify = v; }
        else if ("orgwalker".equals(key)) { changed = orgwalker != v; orgwalker = v; }
        else if ("notifarchive".equals(key)) { changed = notifarchive != v; notifarchive = v; }
        else if ("dewatermark".equals(key)) { changed = dewatermark != v; dewatermark = v; }
        else if ("downloadunlock".equals(key)) { changed = downloadunlock != v; downloadunlock = v; }
        else if ("restrictunlock".equals(key)) { changed = restrictunlock != v; restrictunlock = v; }
        else if ("pubdownload".equals(key)) { changed = pubdownload != v; pubdownload = v; }
        else if ("screenshotnoaudit".equals(key)) { changed = screenshotnoaudit != v; screenshotnoaudit = v; }
        else if ("forcescreenshot".equals(key)) { changed = forcescreenshot != v; forcescreenshot = v; }
        else if ("noauditall".equals(key)) { changed = noauditall != v; noauditall = v; }
        else if ("updatebanner".equals(key)) { changed = updatebanner != v; updatebanner = v; }
        else if ("showRecallHint".equals(key)) { changed = showRecallHint != v; showRecallHint = v; }
        else if ("recallHintOriginal".equals(key)) { changed = recallHintOriginal != v; recallHintOriginal = v; }
        else if ("blockaipeek".equals(key)) { changed = blockaipeek != v; blockaipeek = v; }
        if (!changed) return;
        updatedAt = System.currentTimeMillis();
        save();
        broadcast();
    }

    public static synchronized void setDismissed(int vc) {
        if (dismissedUpc == vc) return;
        dismissedUpc = vc;
        updatedAt = System.currentTimeMillis();
        save();
        broadcast();
    }

    // 字符串配置(目前仅 pubdownloadSubdir / recallHintText)。子目录名做基本清洗。
    public static synchronized void setStr(String key, String v) {
        if ("pubdownloadSubdir".equals(key)) {
            String nv = sanitizeSubdir(v);
            if (nv.equals(pubdownloadSubdir)) return;
            pubdownloadSubdir = nv;
        } else if ("recallHintText".equals(key)) {
            String nv = sanitizeRecallHint(v);
            if (nv.equals(recallHintText)) return;
            recallHintText = nv;
        } else {
            return;
        }
        updatedAt = System.currentTimeMillis();
        save();
        broadcast();
    }

    /** 撤回提示文案清洗：去空白，默认「撤回了一条消息」；保留 {name}/{sender} 占位。 */
    static String sanitizeRecallHint(String s) {
        if (s == null) return "撤回了一条消息";
        s = s.trim().replace('\n', ' ').replace('\r', ' ');
        if (s.isEmpty()) return "撤回了一条消息";
        if (s.length() > 40) s = s.substring(0, 40);
        return s;
    }

    /** 向国内版、国际版飞书和模块自身发送最新配置（跨应用，接收端须 EXPORTED）。不发本包。 */
    public static void broadcast() {
        try {
            Context c = appContext;
            if (c == null) return;
            long now = System.currentTimeMillis();
            if (now - lastBroadcastAt < BROADCAST_MIN_INTERVAL_MS) return;
            lastBroadcastAt = now;
            String json = snapshot();
            String self = c.getPackageName();
            if (!"com.ss.android.lark".equals(self)) sendTo(c, "com.ss.android.lark", json);
            if (!"com.larksuite.suite".equals(self)) sendTo(c, "com.larksuite.suite", json);
            if (!"com.chekayo.feishuantirecall".equals(self)) sendTo(c, "com.chekayo.feishuantirecall", json);
        } catch (Throwable ignored) {}
    }

    private static void sendTo(Context c, String pkg, String json) {
        try {
            Intent i = new Intent(ACTION_SYNC);
            i.setPackage(pkg);
            i.putExtra("json", json);
            c.sendBroadcast(i);
        } catch (Throwable ignored) {}
    }

    /**
     * 同步接收回调：应用对端快照。只 apply，不回推（回推曾导致模块卡顿/掉帧）。
     * @return true=内存/磁盘已刷新。
     */
    public static boolean onSyncReceive(String json) {
        return applySnapshot(json);
    }

    /** 打开面板时：读本地 + 与模块权威源对齐。不在此 broadcast（用户改开关才广播）。 */
    public static synchronized void loadAndAnnounce() {
        load();
        syncWithProvider();
        // 对齐后仍无有效时间戳则落盘默认值（仅一次）
        if (updatedAt <= 0 && cfgFile != null && !cfgFile.exists()) {
            updatedAt = System.currentTimeMillis();
            save();
        }
    }

    /**
     * 飞书进程：经 ContentProvider 与模块进程对齐（新者胜）。
     * 模块进程：no-op（自己就是权威源）。
     */
    public static synchronized void syncWithProvider() {
        try {
            Context c = appContext;
            if (c == null) return;
            if ("com.chekayo.feishuantirecall".equals(c.getPackageName())) return;
            Bundle b = c.getContentResolver().call(ConfigProvider.URI, "get", null, null);
            if (b == null) return;
            String remote = b.getString("json");
            if (remote == null || remote.isEmpty()) return;
            long remoteTs = 0;
            try { remoteTs = new JSONObject(remote).optLong("updatedAt", 0); } catch (Throwable ignored) {}
            // 本地无有效时间戳(0) 或对端更新 → 采用对端（模块为权威源）
            if (remoteTs > updatedAt || (updatedAt <= 0 && remoteTs > 0)) {
                boolean ok = applySnapshot(remote);
                try { android.util.Log.i("fucklark", "sync pull ok=" + ok + " remoteTs=" + remoteTs + " localWas=" + updatedAt); } catch (Throwable ignored) {}
            } else if (updatedAt > 0 && remoteTs < updatedAt) {
                // 本地更新 → 写回模块权威源
                Bundle put = new Bundle();
                put.putString("json", snapshot());
                c.getContentResolver().call(ConfigProvider.URI, "put", null, put);
            }
        } catch (Throwable ignored) {}
    }

    /** @deprecated 旧名，保留兼容 */
    public static void pullFromProvider() { syncWithProvider(); }

    static String sanitizeSubdir(String s) {
        if (s == null) return "";
        s = s.trim();
        // 去掉首尾斜杠, 过滤路径穿越与非法文件名字符
        while (s.startsWith("/")) s = s.substring(1);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        s = s.replace("..", "").replaceAll("[\\\\:*?\"<>|]", "");
        return s.trim();
    }

    static synchronized void save() {
        try {
            if (cfgFile == null) return;
            JSONObject o = new JSONObject();
            o.put("updatedAt", updatedAt);
            o.put("antirecall", antirecall);
            o.put("resign", resign);
            o.put("antiread", antiread);
            o.put("diaglog", diaglog);
            o.put("keepkicked", keepkicked);
            o.put("leavenotify", leavenotify);
            o.put("orgwalker", orgwalker);
            o.put("notifarchive", notifarchive);
            o.put("dewatermark", dewatermark);
            o.put("downloadunlock", downloadunlock);
            o.put("restrictunlock", restrictunlock);
            o.put("pubdownload", pubdownload);
            o.put("screenshotnoaudit", screenshotnoaudit);
            o.put("forcescreenshot", forcescreenshot);
            o.put("noauditall", noauditall);
            o.put("pubdownloadSubdir", pubdownloadSubdir);
            o.put("updatebanner", updatebanner);
            o.put("dismissedUpc", dismissedUpc);
            o.put("showRecallHint", showRecallHint);
            o.put("recallHintOriginal", recallHintOriginal);
            o.put("recallHintText", recallHintText);
            o.put("blockaipeek", blockaipeek);
            File dir = cfgFile.getParentFile();
            if (dir != null && !dir.isDirectory()) dir.mkdirs();   // 定制飞书 files 目录可能尚未创建
            write(cfgFile, o.toString());
        } catch (Throwable t) {
            // 不再静默: 定制/白标飞书写盘失败时(路径/权限/沙箱), 打日志便于用户反馈定位「重启掉配置」。
            try { android.util.Log.w("fucklark", "Config.save 失败 path=" + cfgFile + " err=" + t); } catch (Throwable ignored) {}
        }
    }

    static String read(File f) throws Exception {
        FileInputStream is = new FileInputStream(f);
        byte[] b = new byte[(int) f.length()];
        int off = 0, r; while (off < b.length && (r = is.read(b, off, b.length - off)) > 0) off += r;
        is.close(); return new String(b, 0, off, "UTF-8");
    }
    static void write(File f, String s) throws Exception {
        FileOutputStream os = new FileOutputStream(f);
        os.write(s.getBytes("UTF-8")); os.flush(); os.close();
        f.setReadable(true, false);
    }
}
