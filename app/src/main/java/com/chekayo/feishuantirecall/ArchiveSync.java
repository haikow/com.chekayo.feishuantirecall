package com.chekayo.feishuantirecall;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

/**
 * 同事档案副本同步：飞书进程归档写完后，把 profiles.json / resigned_all.json / notif_archive.txt
 * 优先经 ContentProvider FD 流式（gzip 压缩 + commitFile）推送到模块目录（规避 Binder 1MB 上限）；
 * 仅对 <= 200KB 的小文件保留 putFile / 显式包名广播兜底。
 */
public final class ArchiveSync {
    public static final String ACTION_PUSH = "com.chekayo.feishuantirecall.ARCHIVE_PUSH";
    /** 模块 → 飞书：请把档案/离职名单推过来（模块进程活着时才收得到 PUSH）。 */
    public static final String ACTION_PULL = "com.chekayo.feishuantirecall.ARCHIVE_PULL";
    public static final String ACTION_REFRESH = "com.chekayo.feishuantirecall.ARCHIVE_REFRESH";

    private static final long MAX_ARCHIVE_BYTES = 16L * 1024L * 1024L;
    private static final int SMALL_FALLBACK_LIMIT = 200 * 1024;
    private static final int MAX_BROADCAST_RECV_BYTES = 256 * 1024;
    private static final int STREAM_BUF_SIZE = 32 * 1024;

    private ArchiveSync() { }

    public static void requestRefresh(Context ctx) {
        if (ctx == null) return;
        if (!Config.MODULE_PKG.equals(ctx.getPackageName())) {
            com.chekayo.larkresign.ResignTracker.requestRefresh("打开离职名单");
        } else {
            for (String pkg : Config.knownHostPackages()) {
                try {
                    Intent i = new Intent(ACTION_REFRESH);
                    i.setPackage(pkg);
                    ctx.sendBroadcast(i);
                } catch (Throwable ignored) { }
            }
        }
        requestPull(ctx);
    }

    /** 模块进程打开记录页时：请求飞书侧推送档案副本（需飞书在运行）。 */
    public static void requestPull(Context ctx) {
        try {
            Context c = ctx != null ? ctx.getApplicationContext() : Config.appContextForSync();
            if (c == null) return;
            for (String pkg : Config.knownHostPackages()) {
                try {
                    Intent i = new Intent(ACTION_PULL);
                    i.setPackage(pkg);
                    c.sendBroadcast(i);
                } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) { }
    }

    /** 将飞书侧档案文件推送到模块副本目录。name 仅接受 *.json / *.txt。 */
    public static void pushToModule(String name) {
        try {
            if (name == null || !name.matches("[A-Za-z0-9_]+\\.(json|txt)")) return;
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
            long fileLen = f.length();
            if (fileLen > MAX_ARCHIVE_BYTES) {
                Diag.w("档案同步: 文件超过 16MB 上限，跳过 " + name + " bytes=" + fileLen);
                return;
            }

            // 1) 主路径：FD 流式（gzip 压缩写入 staging 文件，再 commitFile 原子落盘），不走 Binder 字节数组
            if (pushViaProviderStream(c, f, uid, name)) {
                return;
            }

            // 2) 回落路径：仅 <= 200KB 小文件尝试 putFile 或显式广播，避免 TransactionTooLargeException
            if (fileLen > SMALL_FALLBACK_LIMIT) {
                Diag.w("档案同步: Provider 流式通道不可用且文件超过 200KB，跳过广播 " + name + " bytes=" + fileLen);
                return;
            }
            byte[] data = Config.readBytes(f);
            if (data == null || data.length == 0 || data.length > SMALL_FALLBACK_LIMIT) {
                Diag.w("档案同步: 读取源文件失败或超限 " + name + " length=" + fileLen);
                return;
            }
            try {
                Bundle extras = new Bundle();
                extras.putByteArray("data", data);
                extras.putString("uid", uid);
                Bundle result = c.getContentResolver().call(ConfigProvider.URI, "putFile", name, extras);
                if (result != null && result.getBoolean("ok")) {
                    Diag.w("档案同步: putFile 已确认 " + name + " bytes=" + data.length + " scoped=" + (uid != null));
                    return;
                }
            } catch (Throwable t) {
                android.util.Log.w("fucklark", "档案 Provider putFile 失败，回落广播", t);
            }
            Intent i = new Intent(ACTION_PUSH);
            i.setComponent(new android.content.ComponentName(Config.MODULE_PKG,
                    "com.chekayo.feishuantirecall.ArchiveSync$Receiver"));
            i.putExtra("name", name);
            i.putExtra("data", data);
            i.putExtra("uid", uid);
            c.sendBroadcast(i);
            Diag.w("档案同步: Provider 未确认，已发送显式副本广播 " + name + " bytes=" + data.length);
        } catch (Throwable error) { Diag.w("档案同步失败 " + name + " " + error); }
    }

    private static boolean pushViaProviderStream(Context c, File src, String uid, String name) {
        try {
            String token = UUID.randomUUID().toString().replace("-", "");
            Uri stageUri = Uri.parse("content://" + ConfigProvider.AUTHORITY + "/archive_stage/" + token);
            OutputStream raw = c.getContentResolver().openOutputStream(stageUri, "w");
            if (raw == null) return false;
            long rawBytes = 0;
            try {
                GZIPOutputStream gz = new GZIPOutputStream(new BufferedOutputStream(raw));
                try {
                    InputStream in = new BufferedInputStream(new FileInputStream(src));
                    try {
                        byte[] buf = new byte[STREAM_BUF_SIZE];
                        int n;
                        while ((n = in.read(buf)) != -1) {
                            rawBytes += n;
                            if (rawBytes > MAX_ARCHIVE_BYTES) {
                                throw new java.io.IOException("archive grew beyond 16MB during stream");
                            }
                            gz.write(buf, 0, n);
                        }
                    } finally { in.close(); }
                } finally { gz.close(); }
            } finally {
                try { raw.close(); } catch (Throwable ignored) {}
            }
            if (rawBytes == 0) return false;
            Bundle extras = new Bundle();
            extras.putString("token", token);
            extras.putString("uid", uid);
            extras.putBoolean("gzip", true);
            Bundle result = c.getContentResolver().call(ConfigProvider.URI, "commitFile", name, extras);
            if (result != null && result.getBoolean("ok")) {
                Diag.w("档案同步: FD流式已确认 " + name + " bytes=" + rawBytes + " scoped=" + (uid != null));
                return true;
            }
        } catch (Throwable t) {
            android.util.Log.w("fucklark", "档案 FD 流式推送失败 name=" + name, t);
        }
        return false;
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

    /** 模块进程接收小文件档案推送并落盘（manifest 注册）。 */
    public static class Receiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            try {
                if (context == null || intent == null) return;
                if (!ACTION_PUSH.equals(intent.getAction())) return;
                String name = intent.getStringExtra("name");
                byte[] data = intent.getByteArrayExtra("data");
                String uid = intent.getStringExtra("uid");
                if (name == null || !name.matches("[A-Za-z0-9_]+\\.(json|txt)")) return;
                if (data == null || data.length == 0 || data.length > MAX_BROADCAST_RECV_BYTES) return;
                ArchiveReplicaStore.save(context.getFilesDir(), uid, name, data);
                android.util.Log.i("fucklark", "ArchiveSync.recv " + name + " bytes=" + data.length);
            } catch (Throwable t) {
                android.util.Log.w("fucklark", "ArchiveSync.recv err " + t);
            }
        }
    }
}
