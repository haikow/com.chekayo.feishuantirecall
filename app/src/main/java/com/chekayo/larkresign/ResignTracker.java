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

        // 抽 native .so 到私有目录并加载
        File dataDir = ctx.getFilesDir().getParentFile();
        File outDir = new File(ctx.getFilesDir(), "resign_tracker");
        outDir.mkdirs();
        File so = new File(new File(dataDir, "resign_tracker_lib"), "libresign.so");
        so.getParentFile().mkdirs();
        extractSo(so);
        System.load(so.getAbsolutePath());
        XposedBridge.log(TAG + ": native loaded " + so.getAbsolutePath());

        final File allFile = new File(outDir, "resigned_all.json");
        final File snapFile = new File(outDir, "resigned_latest.json");
        final File profJsonl = new File(outDir, "v3_bulk.jsonl");   // native 落 blob(临时)
        final File profJson  = new File(outDir, "profiles.json");   // append-only 富资料档案
        final File rosterJsonl = new File(outDir, "roster.jsonl"); // native 落全量花名册(临时)
        final File rosterJson  = profJson;                         // 全员并入同一 profiles.json(补齐姓名)

        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                // 1) 等 libsqlcipher 加载 + 装 hook
                for (int i = 0; i < 600; i++) {           // ~90s
                    try { if (nativeInit()) break; } catch (Throwable e) { XposedBridge.log(TAG + ": nativeInit err " + e); return; }
                    sleep(150);
                }
                XposedBridge.log(TAG + ": nativeInit done, 等待 contact.db 句柄...");
                // 2) 周期 dump + 并入累计记录 (先密后疏)
                long[] delays = {8000, 15000, 30000, 60000, 120000, 300000};
                int idx = 0;
                int profTick = 0;   // V3 富资料 dump 较重(全表 blob), 隔几轮才抓一次
                int rosterTick = 0; // 全量花名册 dump(全员), 比 V3 更重, 隔更多轮抓一次
                while (true) {
                    try {
                        Config.load();
                        if (!Config.resign) { sleep(30000); continue; }   // fuck lark 开关: 关了就不采集
                        nativeArmDump(snapFile.getAbsolutePath());
                        int rc = -999;
                        for (int k = 0; k < 60; k++) {       // 等下一次 contact.db prepare 完成 dump, 最多 ~30s
                            rc = nativePoll();
                            if (rc >= 0) break;
                            sleep(500);
                        }
                        if (rc >= 0) {
                            int merged = mergeInto(allFile, snapFile);
                            XposedBridge.log(TAG + ": 离职快照=" + rc + " 累计存档=" + merged
                                    + " -> " + allFile.getAbsolutePath());
                        }
                        // rc==-1: contact.db 句柄还没抓到 (还没查过联系人), 下轮继续

                        // 每 3 轮抓一次 V3 富资料(部门/邮箱/工号/职务/上级 + 外部公司名), append-only 并入 profiles.json
                        if (profTick++ % 3 == 0) {
                            try {
                                nativeArmProfiles(profJsonl.getAbsolutePath());
                                int prc = -999;
                                for (int k = 0; k < 60; k++) { prc = nativeProfileResult(); if (prc >= 0) break; sleep(500); }
                                if (prc >= 0) {
                                    int n = ProfileBulk.merge(profJsonl, profJson);
                                    XposedBridge.log(TAG + ": V3富资料 dump=" + prc + " 并入档案=" + n + " -> " + profJson.getAbsolutePath());
                                    profJsonl.delete();   // 临时文件用完即删(可含大量 hex)
                                }
                            } catch (Throwable pe) { XposedBridge.log(TAG + ": profile dump err " + pe); }
                        }

                        // 每 5 轮抓一次全量花名册(不限 is_resigned): 配合组织架构巡游补齐全员姓名,
                        // 无 V3 富资料的人也落 name/tenant_id -> profiles.json 里"全员档案"更全。
                        if (rosterTick++ % 5 == 0) {
                            try {
                                nativeArmRoster(rosterJsonl.getAbsolutePath());
                                int rrc = -999;
                                for (int k = 0; k < 60; k++) { rrc = nativeRosterResult(); if (rrc >= 0) break; sleep(500); }
                                if (rrc >= 0) {
                                    int n = ProfileBulk.mergeRoster(rosterJsonl, rosterJson);
                                    XposedBridge.log(TAG + ": 全量花名册 dump=" + rrc + " 并入档案=" + n + " -> " + rosterJson.getAbsolutePath());
                                    rosterJsonl.delete();   // 临时文件用完即删(可含大量 hex)
                                }
                            } catch (Throwable re) { XposedBridge.log(TAG + ": roster dump err " + re); }
                        }
                    } catch (Throwable e) { XposedBridge.log(TAG + ": dump err " + e); }
                    sleep(delays[Math.min(idx++, delays.length - 1)]);
                }
            }
        }, "lark-resign-tracker");
        t.setDaemon(true);
        t.start();
    }

    /** 把本次快照 union 进累计文件 (按 id 去重; 新 id 记 first_seen; 每次刷新 name/last_seen)。返回累计人数。 */
    static int mergeInto(File allFile, File snapFile) {
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
        FileOutputStream os = new FileOutputStream(f);
        os.write(s.getBytes("UTF-8")); os.flush(); os.close();
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
