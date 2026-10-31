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
            if (c == null) { Diag.w("档案同步: Application 未就绪 " + name); return; }
            String uid = AccountPaths.currentUid;
            File f = findLarkFile(c, uid, name);
            if (f == null || !f.exists() || f.length() == 0) {
                Diag.w("档案同步: 找不到当前账号文件 " + name + " root=" + c.getFilesDir());
                return;
            }
            String relative = name.endsWith(".json") ? "resign_tracker/" + name : name;
            File scoped = new File(c.getFilesDir(), "accounts/" + AccountPaths.safeUid(uid) + "/" + relative);
            if (!f.equals(scoped)) uid = null; // 旧全局数据没有可靠账号归属。
            if (f.length() > 8L * 1024 * 1024) return;
            byte[] data = Config.readBytes(f);
            if (data == null || data.length == 0) {
                Diag.w("档案同步: 读取源文件失败 " + name + " length=" + f.length());
                return;
            }
            try {
                Bundle extras = new Bundle();
                extras.putByteArray("data", data);
                extras.putString("uid", uid);
                Bundle result = c.getContentResolver().call(ConfigProvider.URI, "putFile", name, extras);
                if (result != null && result.getBoolean("ok")) {
                    Diag.w("档案同步: 已确认 " + name + " bytes=" + data.length + " scoped=" + (uid != null));
                    return;
                }
            } catch (Throwable t) { android.util.Log.w("fucklark", "档案 Provider 推送失败，回落广播", t); }
            Intent i = new Intent(ACTION_PUSH);
            i.setComponent(new android.content.ComponentName(Config.MODULE_PKG,
                    "com.chekayo.feishuantirecall.ArchiveSync$Receiver"));
            i.putExtra("name", name);
            i.putExtra("data", data);
            i.putExtra("uid", uid);
            c.sendBroadcast(i);
            Diag.w("档案同步: Provider 未确认，已发送显式副本广播 " + name);
        } catch (Throwable error) { Diag.w("档案同步失败 " + name + " " + error); }
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

    private static File findLarkFile(Context context, String uid, String name) {
        String relative = name.endsWith(".json") ? "resign_tracker/" + name : name;
        File scoped = new File(context.getFilesDir(), "accounts/" + AccountPaths.safeUid(uid) + "/" + relative);
        if (scoped.isFile()) return scoped;
        // 旧安装仅有全局文件时沿用兼容副本，不读取其他宿主或账号的目录。
        File legacy = new File(context.getFilesDir(), relative);
        return legacy.isFile() ? legacy : null;
    }

    /** 模块进程接收档案推送并落盘（manifest 注册）。 */
    public static class Receiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            try {
                if (context == null || intent == null) return;
                if (!ACTION_PUSH.equals(intent.getAction())) return;
                String name = intent.getStringExtra("name");
                byte[] data = intent.getByteArrayExtra("data");
                String uid = intent.getStringExtra("uid");
                if (name == null || data == null || data.length == 0) return;
                name = name.replace("..", "").replace("/", "_").replace("\\", "_");
                if (!name.endsWith(".json") && !name.endsWith(".txt")) return;
                ArchiveReplicaStore.save(context.getFilesDir(), uid, name, data);
                android.util.Log.i("fucklark", "ArchiveSync.recv " + name + " bytes=" + data.length);
            } catch (Throwable t) {
                android.util.Log.w("fucklark", "ArchiveSync.recv err " + t);
            }
        }
    }
}
