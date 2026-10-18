package com.chekayo.feishuantirecall;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;

/**
 * 飞书账号隔离路径：
 * 全员档案 / 离职名单 / 通知存档 / 退群日志 等按登录账号分子目录，避免多账号混在一起。
 *
 * 目录约定（飞书私有目录下）：
 *   files/accounts/&lt;uid&gt;/resign_tracker/profiles.json
 *   files/accounts/&lt;uid&gt;/resign_tracker/resigned_all.json
 *   files/accounts/&lt;uid&gt;/notif_archive.txt
 *   files/accounts/&lt;uid&gt;/leave_log.txt
 *   files/accounts/&lt;uid&gt;/kicked_*.txt
 *
 * 当前账号：优先用 contact.db 路径里的 sdk_storage/&lt;uid&gt;；再尝试最近修改的 sdk_storage 子目录。
 * 认不出时用 "unknown"，行为退回旧全局路径，避免完全不可用。
 */
public final class AccountPaths {
    public static final String FALLBACK_UID = "unknown";

    /** 进程内当前登录账号 uid；空表示尚未识别。 */
    public static volatile String currentUid = "";
    public static volatile String currentPkg = "com.ss.android.lark";

    private AccountPaths() { }

    /** 在飞书进程里探测当前账号并缓存。 */
    public static synchronized void bind(Context ctx, String pkg) {
        if (pkg != null && !pkg.isEmpty()) currentPkg = pkg;
        currentUid = detectUid(ctx);
        android.util.Log.i("fucklark", "AccountPaths uid=" + currentUid + " pkg=" + currentPkg);
    }

    /** 探测当前飞书账号 uid。 */
    public static String detectUid(Context ctx) {
        // 1) contact.db 路径：databases/sdk_storage/<uid>/contact.db
        try {
            File data = null;
            if (ctx != null) data = ctx.getFilesDir().getParentFile();
            if (data == null) {
                data = ProfilePaths.dataDir(currentPkg);
            }
            File sdk = new File(data, "databases/sdk_storage");
            File[] subs = sdk.listFiles();
            if (subs != null && subs.length > 0) {
                File best = null;
                long bestT = 0;
                for (File s : subs) {
                    if (!s.isDirectory()) continue;
                    // 仅认含 contact.db 的真账号目录: log/byteview 等杂项目录没有 contact.db,
                    // 若按目录 mtime 参与竞争会被误选为 uid, 导致数据写进伪账号桶
                    File db = new File(s, "contact.db");
                    if (!db.isFile()) continue;
                    long t = db.lastModified();
                    if (t >= bestT) { bestT = t; best = s; }
                }
                if (best != null) {
                    String n = best.getName();
                    if (n.length() > 0 && !".".equals(n) && !"..".equals(n)) return n;
                }
            }
        } catch (Throwable ignored) { }
        // 2) 已缓存
        if (currentUid != null && !currentUid.isEmpty()) return currentUid;
        return FALLBACK_UID;
    }

    /** 非法路径字符清洗。 */
    public static String safeUid(String uid) {
        if (uid == null || uid.trim().isEmpty()) return FALLBACK_UID;
        return uid.trim().replaceAll("[^A-Za-z0-9_\\-]", "_");
    }

    /** 账号数据根目录：files/accounts/&lt;uid&gt;。 */
    public static File accountRoot(Context ctx, String pkg, String uid) {
        File base = ctx != null ? ctx.getFilesDir() : null;
        if (base == null) {
            String p = (pkg == null || pkg.isEmpty()) ? currentPkg : pkg;
            base = ProfilePaths.filesDir(p);
        }
        return new File(base, "accounts/" + safeUid(uid));
    }

    /** 当前账号某文件。 */
    public static File accountFile(Context ctx, String pkg, String uid, String name) {
        return new File(accountRoot(ctx, pkg, uid), name);
    }

    /** 档案目录 resign_tracker（当前账号）。 */
    public static File resignDir(Context ctx, String pkg, String uid) {
        return new File(accountRoot(ctx, pkg, uid), "resign_tracker");
    }

    /**
     * 解析档案文件（读优先级）：
     * 1) 飞书 files/accounts/&lt;uid&gt;/resign_tracker/&lt;name&gt;
     * 2) 模块 files/accounts/&lt;uid&gt;/...
     * 3) 旧全局路径（兼容历史数据）
     */
    public static File resolveArchive(Context ctx, String pkg, String uid, String name) {
        String[] pkgs = { pkg, "com.ss.android.lark", "com.larksuite.suite", "com.chekayo.feishuantirecall" };
        String[] uids = { uid, currentUid, FALLBACK_UID };
        for (String p : pkgs) {
            if (p == null || p.isEmpty()) continue;
            for (String u : uids) {
                if (u == null || u.isEmpty()) continue;
                File f = accountFile(null, p, u, "resign_tracker/" + name);
                if (f.exists()) return f;
            }
        }
        // 旧全局
        for (String p : pkgs) {
            if (p == null || p.isEmpty()) continue;
            File f = new File(ProfilePaths.filesDir(p), "resign_tracker/" + name);
            if (f.exists()) return f;
        }
        try {
            Class<?> c = Class.forName("android.app.ActivityThread");
            Object app = c.getMethod("currentApplication").invoke(null);
            if (app instanceof Context && uid != null) {
                return accountFile((Context) app, pkg, uid, "resign_tracker/" + name);
            }
        } catch (Throwable ignored) { }
        return new File(ProfilePaths.filesDir(pkg == null ? currentPkg : pkg), "resign_tracker/" + name);
    }

    /**
     * 旧数据迁移（一次性，幂等）：v1.8.2 起数据按账号目录隔离，但更早版本（≤1.8.1）的数据
     * 留在全局路径 files/ 与 files/resign_tracker/，升级后账号目录里的新文件会在读路径上
     * 「遮蔽」旧数据 —— 表现为全员档案缺部门/工号、离职名单与后台消息存档为空。
     * 这里把旧数据并入当前账号目录：JSON 按顶层 key 并集（保留账号目录新值），文本旧内容前置。
     * marker 文件保证只跑一次；账号目录已有数据不会丢。
     */
    public static void migrateLegacy(File base) {
        try {
            if (base == null || !base.isDirectory()) return;
            File marker = new File(base, ".legacy_migrated");
            if (marker.exists()) return;
            File files = base.getParentFile() != null ? base.getParentFile().getParentFile() : null; // files/
            if (files == null || !files.isDirectory()) return;
            File accResign = new File(base, "resign_tracker");
            File gResign = new File(files, "resign_tracker");
            mergeJsonOne(new File(gResign, "profiles.json"), new File(accResign, "profiles.json"));
            mergeJsonOne(new File(gResign, "resigned_all.json"), new File(accResign, "resigned_all.json"));
            copyIfMissing(new File(gResign, "resigned_latest.json"), new File(accResign, "resigned_latest.json"));
            prependOne(new File(files, "notif_archive.txt"), new File(base, "notif_archive.txt"));
            prependOne(new File(files, "leave_log.txt"), new File(base, "leave_log.txt"));
            File[] top = files.listFiles();
            if (top != null) for (File k : top) {
                String n = k.getName();
                if (k.isFile() && n.startsWith("kicked_") && n.endsWith(".txt")) copyIfMissing(k, new File(base, n));
            }
            // 救援 accounts/unknown/ 桶: 旧版本在 uid 未识别时把数据写进了 unknown, 并回正确账号目录
            File unk = new File(files, "accounts/" + FALLBACK_UID);
            if (unk.isDirectory() && !new File(unk, ".legacy_migrated").exists()) {
                File unkResign = new File(unk, "resign_tracker");
                mergeJsonOne(new File(unkResign, "profiles.json"), new File(accResign, "profiles.json"));
                mergeJsonOne(new File(unkResign, "resigned_all.json"), new File(accResign, "resigned_all.json"));
                prependOne(new File(unk, "notif_archive.txt"), new File(base, "notif_archive.txt"));
                prependOne(new File(unk, "leave_log.txt"), new File(base, "leave_log.txt"));
                File[] uk = unk.listFiles();
                if (uk != null) for (File k : uk) {
                    String n = k.getName();
                    if (k.isFile() && n.startsWith("kicked_") && n.endsWith(".txt")) copyIfMissing(k, new File(base, n));
                }
                try { new File(unk, ".legacy_migrated").createNewFile(); } catch (Throwable ignored) {}
            }
            marker.createNewFile();
            android.util.Log.i("fucklark", "AccountPaths.migrateLegacy done -> " + base);
        } catch (Throwable t) {
            android.util.Log.w("fucklark", "migrateLegacy err " + t);
        }
    }

    /** 账号目录缺文件 → 复制；两边都有 → 按顶层 key 并集合并（保留账号目录已有值）。 */
    private static void mergeJsonOne(File legacy, File cur) {
        try {
            if (!legacy.isFile() || legacy.length() == 0) return;
            JSONObject lo = new JSONObject(new String(readAll(legacy), "UTF-8"));
            JSONObject co = (cur.isFile() && cur.length() > 0)
                    ? new JSONObject(new String(readAll(cur), "UTF-8")) : new JSONObject();
            boolean changed = false;
            java.util.Iterator<String> it = lo.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (!co.has(k)) { co.put(k, lo.get(k)); changed = true; }
            }
            if (!changed && cur.isFile() && cur.length() > 0) return;
            File p = cur.getParentFile();
            if (p != null && !p.isDirectory()) p.mkdirs();
            File tmp = new File(p, cur.getName() + ".mig.tmp");
            java.io.FileOutputStream out = new java.io.FileOutputStream(tmp);
            out.write(co.toString(1).getBytes("UTF-8"));
            out.close();
            if (cur.exists()) cur.delete();
            if (!tmp.renameTo(cur)) copy(tmp, cur);
            tmp.delete();
        } catch (Throwable ignored) { }
    }

    /** 账号目录缺文件 → 复制；两边都有 → 旧内容接在前面（仅迁移时跑一次）。 */
    private static void prependOne(File legacy, File cur) {
        try {
            if (!legacy.isFile() || legacy.length() == 0) return;
            if (!cur.isFile() || cur.length() == 0) { copyIfMissing(legacy, cur); return; }
            byte[] l = readAll(legacy), o = readAll(cur);
            java.io.FileOutputStream out = new java.io.FileOutputStream(cur);
            out.write(l);
            if (l.length > 0 && l[l.length - 1] != '\n') out.write('\n');
            out.write(o);
            out.close();
        } catch (Throwable ignored) { }
    }

    private static void copyIfMissing(File src, File dst) {
        try {
            if (!src.isFile() || dst.exists()) return;
            File p = dst.getParentFile();
            if (p != null && !p.isDirectory()) p.mkdirs();
            copy(src, dst);
        } catch (Throwable ignored) { }
    }

    private static void copy(File src, File dst) {
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(src);
            java.io.FileOutputStream out = new java.io.FileOutputStream(dst);
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            in.close();
            out.close();
        } catch (Throwable ignored) { }
    }

    private static byte[] readAll(File f) throws Exception {
        java.io.FileInputStream is = new java.io.FileInputStream(f);
        byte[] b = new byte[(int) f.length()];
        int off = 0, r;
        while (off < b.length && (r = is.read(b, off, b.length - off)) > 0) off += r;
        is.close();
        return b;
    }

    /** 消息类文件（通知存档/退群日志等）按账号路径。 */
    public static File resolveMessageFile(Context ctx, String pkg, String uid, String fileName) {
        String[] pkgs = { pkg, "com.ss.android.lark", "com.larksuite.suite", "com.chekayo.feishuantirecall" };
        String[] uids = { uid, currentUid, FALLBACK_UID };
        for (String p : pkgs) {
            if (p == null || p.isEmpty()) continue;
            for (String u : uids) {
                if (u == null || u.isEmpty()) continue;
                File f = accountFile(null, p, u, fileName);
                if (f.exists()) return f;
            }
        }
        for (String p : pkgs) {
            if (p == null || p.isEmpty()) continue;
            File f = new File(ProfilePaths.filesDir(p), fileName);
            if (f.exists()) return f;
        }
        String p = pkg == null ? currentPkg : pkg;
        String u = uid == null || uid.isEmpty() ? currentUid : uid;
        return accountFile(ctx, p, u, fileName);
    }

    /** 界面用：当前账号短标签。 */
    public static String label(Context ctx) {
        String u = currentUid;
        if (u == null || u.isEmpty()) u = detectUid(ctx);
        if (u == null || u.isEmpty() || FALLBACK_UID.equals(u)) return "未识别账号";
        return "账号 " + (u.length() > 12 ? u.substring(0, 12) + "…" : u);
    }
}
