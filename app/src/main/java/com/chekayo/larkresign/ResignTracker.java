package com.chekayo.larkresign;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.json.JSONArray;
import org.json.JSONObject;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import com.chekayo.feishuantirecall.Config;
import com.chekayo.feishuantirecall.AntiRecall;
import com.chekayo.feishuantirecall.ProfileBulk;

/**
 * 飞书离职统计 (com.ss.android.lark)
 *
 * A(追溯+持续): 进程内 hook libsqlcipher.sqlite3_key_v2 抓 contact.db 句柄, 定时查询
 *   chatters WHERE is_resigned=1, 增量并入【只增长】的持久记录 —— 即便飞书日后清掉某离职行,
 *   我们也已存档 (这就覆盖了 B 向前捕获的核心价值)。
 * 输出: /data/data/com.ss.android.lark/files/resign_tracker/resigned_all.json (累计, 按 uid 去重)
 *        + resigned_latest.json (最近一次快照原样)
 */
public class ResignTracker implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    static final String PKG_FEISHU = "com.ss.android.lark";
    static final String PKG_LARK = "com.larksuite.suite";   // 国际版, v7.72.10 真机确认
    static volatile String PKG = PKG_FEISHU;   // 运行时锁定当前目标包
    static boolean isLarkFamily(String pkg) { return PKG_FEISHU.equals(pkg) || PKG_LARK.equals(pkg); }
    static final String TAG = "LarkResign";

    static volatile String MODULE_PATH = null;
    static volatile boolean STARTED = false;

    private static final Object REFRESH = new Object();
    private static volatile long refreshGeneration;
    private static long lastRefreshRequest;
    public static void requestRefresh(String reason) {
        synchronized (REFRESH) {
            long now = System.currentTimeMillis();
            if (now - lastRefreshRequest < 1500) return;
            lastRefreshRequest = now;
            refreshGeneration++;
            REFRESH.notifyAll();
        }
        XposedBridge.log(TAG + ": 请求刷新 " + reason);
    }

    private static void waitForRefresh(long delay, long generation) {
        synchronized (REFRESH) {
            if (refreshGeneration != generation) return;
            try { REFRESH.wait(delay); } catch (InterruptedException ignored) {}
        }
    }

    private static void installRefreshHooks() {
        XposedHelpers.findAndHookMethod(android.app.Instrumentation.class, "callActivityOnResume",
                android.app.Activity.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                android.app.Activity activity = (android.app.Activity) p.args[0];
                String name = activity.getClass().getName().toLowerCase(java.util.Locale.ROOT);
                if (name.contains("contact") || name.contains("department") || name.contains("organization"))
                    requestRefresh("进入通讯录 " + activity.getClass().getSimpleName());
            }
        });
        XposedHelpers.findAndHookMethod(android.widget.TextView.class, "setText",
                CharSequence.class, android.widget.TextView.BufferType.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (p.args[0] == null) return;
                String title = p.args[0].toString().trim();
                android.widget.TextView v = (android.widget.TextView) p.thisObject;
                String id = "";
                try { id = v.getResources().getResourceEntryName(v.getId()); } catch (Throwable ignored) {}
                if ("breadcrumb_label_tv".equals(id) || title.equals("组织架构")
                        || title.equals("组织内联系人") || title.equals("组织结构")) requestRefresh("组织架构加载");
            }
        });
    }

    public static native boolean nativeInit();
    public static native int nativeArmDump(String path);   // 请求一次 dump(下次 contact.db prepare 时就地完成)
    public static native int nativePoll();                 // -999 pending, >=0 行数, -1 句柄未就绪, 其它负=错误
    public static native int nativeArmProfiles(String path);// 请求一次 V3 富资料批量 dump -> JSONL
    public static native int nativeProfileResult();         // -999 pending, >=0 行数, -1 句柄未就绪

    // 全量花名册(不限 is_resigned): 配合组织架构巡游, 把全员落 JSONL, 补齐无 profile 的同事姓名。
    public static native int nativeArmRoster(String path);  // 请求一次全量花名册 dump -> JSONL
    public static native int nativeRosterResult();          // -999 pending, >=0 行数, -1 句柄未就绪

    @Override
    public void initZygote(IXposedHookZygoteInit.StartupParam sp) { MODULE_PATH = sp.modulePath; }

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (!isLarkFamily(lpparam.packageName) && !AntiRecall.isLarkApp(lpparam.classLoader)) return;
        PKG = lpparam.packageName;   // 锁定当前目标(主进程内唯一)
        // 只在主进程干活 (子进程无消息库/会重复)
        if (!PKG.equals(currentProcessName())) return;

        if (STARTED) return; STARTED = true;
        try { installRefreshHooks(); } catch (Throwable t) { XposedBridge.log(TAG + ": refresh hook err " + t); }
        // 起后台线程: 先等 Application context 就绪, 再抽 so + 装 hook + 周期 dump
        Thread boot = new Thread(new Runnable() {
            @Override public void run() {
                Context ctx = null;
                for (int i = 0; i < 400; i++) {           // 等 app 起来
                    ctx = currentAppContext();
                    if (ctx != null) break;
                    sleep(150);
                }
                if (ctx == null) { XposedBridge.log(TAG + ": 拿不到 app context, 放弃"); return; }
                try { start(ctx); } catch (Throwable t) { XposedBridge.log(TAG + ": start err " + t); }
            }
        }, "lark-resign-boot");
        boot.setDaemon(true);
        boot.start();
    }

    static Context currentAppContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = XposedHelpers.callStaticMethod(at, "currentApplication");
            return app instanceof Context ? (Context) app : null;
        } catch (Throwable t) { return null; }
    }

    static void start(final Context ctx) throws Exception {
        // 配置按当前目标包走(国内/国际版自适应; 与 AntiRecall 同主进程, 幂等)
        try { Config.setFilesDir(ctx.getFilesDir()); } catch (Throwable t) { XposedBridge.log(TAG + ": setFilesDir err " + t); }
        // 探测当前登录账号：档案/离职数据按账号隔离，避免多账号混写
        try {
            com.chekayo.feishuantirecall.AccountPaths.bind(ctx, PKG);
        } catch (Throwable t) { XposedBridge.log(TAG + ": AccountPaths.bind err " + t); }

        // 抽 native .so 到私有目录并加载
        File dataDir = ctx.getFilesDir().getParentFile();
        try {
            com.chekayo.feishuantirecall.AccountPaths.bind(ctx, PKG);
        } catch (Throwable t) { XposedBridge.log(TAG + ": AccountPaths.bind err " + t); }
        File so = new File(new File(dataDir, "resign_tracker_lib"), "libresign.so");
        so.getParentFile().mkdirs();
        extractSo(so);
        System.load(so.getAbsolutePath());
        XposedBridge.log(TAG + ": native loaded " + so.getAbsolutePath()
                + " uid=" + com.chekayo.feishuantirecall.AccountPaths.currentUid);

        final Context appCtx = ctx;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                for (int i = 0; i < 600; i++) {
                    try { if (nativeInit()) break; } catch (Throwable e) { XposedBridge.log(TAG + ": nativeInit err " + e); return; }
                    sleep(150);
                }
                XposedBridge.log(TAG + ": nativeInit done, 等待 contact.db 句柄...");
                long[] delays = {8000, 15000, 30000, 60000};
                int idx = 0;
                int profTick = 0;
                int rosterTick = 0;
                long processedGeneration = -1;
                while (true) {
                    final long generation = refreshGeneration;
                    boolean requested = generation != processedGeneration;
                    processedGeneration = generation;
                    try {
                        Config.load();
                        if (!Config.resign) { waitForRefresh(30000, generation); continue; }

                        // 1) 探测当前登录账号 → 输出目录 files/accounts/<uid>/resign_tracker
                        String uid = com.chekayo.feishuantirecall.AccountPaths.detectUid(appCtx);
                        if (uid != null && !uid.isEmpty()) {
                            com.chekayo.feishuantirecall.AccountPaths.currentUid = uid;
                        }
                        File outDir = com.chekayo.feishuantirecall.AccountPaths.resignDir(appCtx, PKG,
                                com.chekayo.feishuantirecall.AccountPaths.currentUid);
                        if (outDir != null && !outDir.isDirectory()) outDir.mkdirs();
                        // 切号后新账号目录首次使用时也迁移一次旧全局数据(幂等, 有 marker)
                        try { com.chekayo.feishuantirecall.AccountPaths.migrateLegacy(outDir.getParentFile()); }
                        catch (Throwable me) { XposedBridge.log(TAG + ": migrateLegacy err " + me); }

                        File accAll = new File(outDir, "resigned_all.json");
                        File accSnap = new File(outDir, "resigned_latest.json");
                        File accProfJl = new File(outDir, "v3_bulk.jsonl");
                        File accProf = new File(outDir, "profiles.json");
                        File accRosterJl = new File(outDir, "roster.jsonl");

                        boolean dumpProfiles = requested || profTick++ % 3 == 0;
                        boolean dumpRoster = requested || rosterTick++ % 5 == 0;
                        if (dumpProfiles) nativeArmProfiles(accProfJl.getAbsolutePath());
                        if (dumpRoster) nativeArmRoster(accRosterJl.getAbsolutePath());
                        // 2) 离职快照
                        nativeArmDump(accSnap.getAbsolutePath());
                        int rc = -999;
                        for (int k = 0; k < 60 && refreshGeneration == generation; k++) {
                            rc = nativePoll();
                            if (rc >= 0) break;
                            sleep(500);
                        }
                        if (rc >= 0) {
                            int merged = mergeInto(accAll, accSnap);
                            XposedBridge.log(TAG + ": 离职快照=" + rc + " 累计=" + merged
                                    + " uid=" + uid + " -> " + accAll.getAbsolutePath());
                            if (merged >= 0) {
                                try { com.chekayo.feishuantirecall.ArchiveSync.pushAll(); } catch (Throwable ignored) {}
                            }
                        }

                        // 3) V3 富资料 → 当前账号 profiles.json
                        if (dumpProfiles) {
                            try {
                                int prc = -999;
                                for (int k = 0; k < 60 && refreshGeneration == generation; k++) { prc = nativeProfileResult(); if (prc >= 0) break; sleep(500); }
                                if (prc >= 0) {
                                    int n = ProfileBulk.merge(accProfJl, accProf);
                                    XposedBridge.log(TAG + ": V3富资料 dump=" + prc + " 并入=" + n + " -> " + accProf.getAbsolutePath());
                                    try { com.chekayo.feishuantirecall.ArchiveSync.pushProfiles(); } catch (Throwable ignored) {}
                                    accProfJl.delete();
                                }
                            } catch (Throwable pe) { XposedBridge.log(TAG + ": profile dump err " + pe); }
                        }

                        // 4) 全量花名册 → 当前账号 profiles.json
                        if (dumpRoster) {
                            try {
                                int rrc = -999;
                                for (int k = 0; k < 60 && refreshGeneration == generation; k++) { rrc = nativeRosterResult(); if (rrc >= 0) break; sleep(500); }
                                if (rrc >= 0) {
                                    int n = ProfileBulk.mergeRoster(accRosterJl, accProf);
                                    XposedBridge.log(TAG + ": 全量花名册 dump=" + rrc + " 并入=" + n
                                            + " uid=" + uid + " -> " + accProf.getAbsolutePath());
                                    try { com.chekayo.feishuantirecall.ArchiveSync.pushProfiles(); } catch (Throwable ignored) {}
                                    accRosterJl.delete();
                                }
                            } catch (Throwable re) { XposedBridge.log(TAG + ": roster dump err " + re); }
                        }
                    } catch (Throwable e) { XposedBridge.log(TAG + ": dump err " + e); }
                    waitForRefresh(requested ? 1500 : delays[Math.min(idx++, delays.length - 1)], generation);
                }
            }
        }, "lark-resign-tracker");
        t.setDaemon(true);
        t.start();
        // 启动稍后推一次已有档案/离职名单副本到模块进程（等 Application/Context 就绪）
        Thread pushOnce = new Thread(new Runnable() {
            @Override public void run() {
                try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
                try { com.chekayo.feishuantirecall.ArchiveSync.pushAll(); }
                catch (Throwable t) { XposedBridge.log(TAG + ": startup archive push err " + t); }
            }
        }, "lark-archive-push");
        pushOnce.setDaemon(true);
        pushOnce.start();
    }

    /** 把本次快照 union 进累计文件 (按 id 去重; 新 id 记 first_seen; 每次刷新 name/last_seen)。返回累计人数。 */
    static int mergeInto(File allFile, File snapFile) {
        synchronized (Config.PROFILES_LOCK) {
            try {
                JSONArray snap = new JSONArray(readText(snapFile));
                JSONObject all = allFile.exists() ? new JSONObject(readText(allFile)) : new JSONObject();
                long now = System.currentTimeMillis();
                for (int i = 0; i < snap.length(); i++) {
                    JSONObject r = snap.getJSONObject(i);
                    String id = r.optString("id", "");
                    if (id.isEmpty()) continue;
                    JSONObject e = all.optJSONObject(id);
                    if (e == null) { e = new JSONObject(); e.put("first_seen", now); }
                    e.put("id", id);
                    e.put("name", r.optString("name"));
                    e.put("en_us_name", r.optString("en_us_name"));
                    e.put("update_time", r.optString("update_time"));
                    e.put("is_frozen", r.optString("is_frozen"));
                    e.put("last_seen", now);
                    all.put(id, e);
                }
                writeText(allFile, all.toString(1));
                return all.length();
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": merge err " + t);
                return -1;
            }
        }
    }

    static void extractSo(File out) throws Exception {
        ZipFile zf = new ZipFile(MODULE_PATH);
        try {
            ZipEntry e = zf.getEntry("lib/arm64-v8a/libresign.so");
            if (e == null) throw new IllegalStateException("libresign.so not in apk");
            InputStream is = zf.getInputStream(e);
            FileOutputStream os = new FileOutputStream(out);
            byte[] buf = new byte[65536]; int r;
            while ((r = is.read(buf)) > 0) os.write(buf, 0, r);
            os.flush(); os.close(); is.close();
        } finally { zf.close(); }
        out.setReadable(true, false);
        out.setExecutable(true, false);
    }

    static String readText(File f) throws Exception {
        FileInputStream is = new FileInputStream(f);
        byte[] b = new byte[(int) f.length()];
        int off = 0, r; while (off < b.length && (r = is.read(b, off, b.length - off)) > 0) off += r;
        is.close(); return new String(b, 0, off, "UTF-8");
    }
    static void writeText(File f, String s) throws Exception {
        File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
        FileOutputStream os = new FileOutputStream(tmp);
        try { os.write(s.getBytes("UTF-8")); os.flush(); } finally { os.close(); }
        if (!tmp.renameTo(f)) throw new java.io.IOException("档案写入失败 " + f);
        f.setReadable(true, false);
    }

    static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) {} }

    static String currentProcessName() {
        try {
            FileInputStream fis = new FileInputStream("/proc/self/cmdline");
            byte[] buf = new byte[256]; int n = fis.read(buf); fis.close();
            if (n <= 0) return "";
            int end = 0; while (end < n && buf[end] != 0) end++;
            return new String(buf, 0, end).trim();
        } catch (Throwable t) { return ""; }
    }
}
