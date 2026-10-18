package com.chekayo.feishuantirecall;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 同事档案副本同步：飞书进程归档写完后，把 profiles.json / resigned_all.json
 * 经显式包名广播推到模块进程（Android 11+ 包可见性下 ContentResolver.call 会报 Unknown authority）。
 * 模块侧 manifest 注册接收器，写入 files/resign_tracker/，桌面入口读副本。
 */
public final class ArchiveSync {
    public static final String ACTION_PUSH = "com.chekayo.feishuantirecall.ARCHIVE_PUSH";
    /** 模块 → 飞书：请把档案/离职名单推过来（模块进程活着时才收得到 PUSH）。 */
    public static final String ACTION_PULL = "com.chekayo.feishuantirecall.ARCHIVE_PULL";
    public static final String ACTION_REFRESH = "com.chekayo.feishuantirecall.ARCHIVE_REFRESH";
    private ArchiveSync() { }

    public static void requestRefresh(Context ctx) {
        if (ctx == null) return;
        if (!Config.MODULE_PKG.equals(ctx.getPackageName())) {
            com.chekayo.larkresign.ResignTracker.requestRefresh("打开离职名单");
        } else {
            for (String pkg : new String[]{ "com.ss.android.lark", "com.larksuite.suite" }) {
                Intent i = new Intent(ACTION_REFRESH); i.setPackage(pkg); ctx.sendBroadcast(i);
            }
        }
        requestPull(ctx);
    }

    /** 模块进程打开记录页时：请求飞书侧推送档案副本（需飞书在运行）。 */
    public static void requestPull(Context ctx) {
        try {
            Context c = ctx != null ? ctx.getApplicationContext() : Config.appContextForSync();
            if (c == null) return;
            Intent i = new Intent(ACTION_PULL);
            i.setPackage("com.ss.android.lark");
            c.sendBroadcast(i);
            Intent i2 = new Intent(ACTION_PULL);
            i2.setPackage("com.larksuite.suite");
            c.sendBroadcast(i2);
        } catch (Throwable ignored) { }
    }

    /** 将飞书侧档案文件推送到模块副本目录。name 仅接受 *.json / *.txt。 */
    public static void pushToModule(String name) {
        try {
            if (name == null || !(name.endsWith(".json") || name.endsWith(".txt"))) return;
            Context c = Config.appContextForSync();
            if (c == null) return;
            File f = findLarkFile(name);
            if (f == null || !f.exists() || f.length() == 0) return;
            if (f.length() > 8L * 1024 * 1024) return;
            byte[] data = Config.readBytes(f);
            if (data == null || data.length == 0) return;
            try {
                Bundle extras = new Bundle(); extras.putByteArray("data", data);
                Bundle result = c.getContentResolver().call(ConfigProvider.URI, "putFile", name, extras);
                if (result != null && result.getBoolean("ok")) return;
            } catch (Throwable t) { android.util.Log.w("fucklark", "档案 Provider 推送失败，回落广播", t); }
            Intent i = new Intent(ACTION_PUSH);
            i.setPackage("com.chekayo.feishuantirecall");
            i.putExtra("name", name);
            i.putExtra("data", data);
            c.sendBroadcast(i);
        } catch (Throwable ignored) { }
    }

    public static void pushProfiles() { pushToModule("profiles.json"); }
    public static void pushResigned() { pushToModule("resigned_all.json"); }
    /** 后台消息存档（文本日志），供桌面统计条数。 */
    public static void pushNotifArchive() { pushToModule("notif_archive.txt"); }

    /** 一次把档案 + 离职名单 + 通知存档都推过去。 */
    public static void pushAll() {
        pushProfiles();
        pushResigned();
        pushNotifArchive();
    }

    private static File findLarkFile(String name) {
        String[] pkgs = {
                DataViews.PKG,
                "com.ss.android.lark",
                "com.larksuite.suite"
        };
        String[] uids = { AccountPaths.currentUid, AccountPaths.FALLBACK_UID };
        for (String pkg : pkgs) {
            if (pkg == null || pkg.isEmpty()) continue;
            // v1.8.2+ 按账号隔离: 档案在 accounts/<uid>/resign_tracker/, 存档文本直接在 accounts/<uid>/
            for (String u : uids) {
                if (u == null || u.isEmpty()) continue;
                File f = new File("/data/data/" + pkg + "/files/accounts/" + u + "/resign_tracker/" + name);
                if (f.exists()) return f;
                f = new File("/data/user/0/" + pkg + "/files/accounts/" + u + "/resign_tracker/" + name);
                if (f.exists()) return f;
                f = new File("/data/data/" + pkg + "/files/accounts/" + u + "/" + name);
                if (f.exists()) return f;
                f = new File("/data/user/0/" + pkg + "/files/accounts/" + u + "/" + name);
                if (f.exists()) return f;
            }
            // 旧全局路径兜底(迁移前/迁移失败时)
            File f = new File("/data/data/" + pkg + "/files/resign_tracker/" + name);
            if (f.exists()) return f;
            f = new File("/data/user/0/" + pkg + "/files/resign_tracker/" + name);
            if (f.exists()) return f;
            f = new File("/data/data/" + pkg + "/files/" + name);   // notif_archive.txt / leave_log.txt 全局根
            if (f.exists()) return f;
        }
        return null;
    }

    /** 模块进程接收档案推送并落盘（manifest 注册）。 */
    public static class Receiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            try {
                if (context == null || intent == null) return;
                if (!ACTION_PUSH.equals(intent.getAction())) return;
                String name = intent.getStringExtra("name");
                byte[] data = intent.getByteArrayExtra("data");
                if (name == null || data == null || data.length == 0) return;
                name = name.replace("..", "").replace("/", "_").replace("\\", "_");
                if (!name.endsWith(".json") && !name.endsWith(".txt")) return;
                File dir = new File(context.getFilesDir(), "resign_tracker");
                if (!dir.isDirectory()) dir.mkdirs();
                // 通知存档放 files 根目录，与其它档案目录区分
                File out = name.endsWith(".txt")
                        ? new File(context.getFilesDir(), name)
                        : new File(dir, name);
                Config.write(out, new String(data, "UTF-8"));
                out.setReadable(true, false);
                android.util.Log.i("fucklark", "ArchiveSync.recv " + name + " bytes=" + data.length);
            } catch (Throwable t) {
                android.util.Log.w("fucklark", "ArchiveSync.recv err " + t);
            }
        }
    }
}
