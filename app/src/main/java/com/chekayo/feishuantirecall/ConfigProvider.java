package com.chekayo.feishuantirecall;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 模块侧权威源：配置 + 同事档案副本。
 * 飞书进程启动时 call get 拉配置；归档写入后 call putFile 推送 profiles/resigned 到模块目录，
 * 桌面入口才能读到「同事记录」。
 */
public class ConfigProvider extends ContentProvider {
    public static final String AUTHORITY = "com.chekayo.feishuantirecall.config";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY);

    @Override public boolean onCreate() { return true; }

    @Override public synchronized Bundle call(String method, String arg, Bundle extras) {
        try {
            if (getContext() != null) {
                Config.setContext(getContext());
                if (Config.cfgFile == null) Config.setFilesDir(getContext().getFilesDir());
            }
            if ("get".equals(method)) {
                Config.load();
                RuntimeStatus.report(extras);
                Bundle b = new Bundle();
                b.putString("json", Config.snapshot());
                return b;
            }
            if ("status".equals(method)) {
                Config.load();
                Bundle b = new Bundle();
                b.putString("text", RuntimeStatus.describe());
                return b;
            }
            if ("blockLogAdd".equals(method)) {
                if (extras != null) BlockLog.appendLocal(getContext(), extras.getString("json"));
                Bundle b = new Bundle(); b.putBoolean("ok", true); return b;
            }
            if ("blockLogGet".equals(method)) {
                Bundle b = new Bundle(); b.putString("text", BlockLog.readLocal(getContext())); return b;
            }
            if ("patch".equals(method)) {
                Bundle b = new Bundle();
                b.putString("json", Config.applyPatch(extras != null && extras.getString("json") != null ? extras.getString("json") : arg));
                return b;
            }
            if ("put".equals(method)) {
                String json = extras != null ? extras.getString("json") : null;
                Config.onSyncReceive(json);
                Config.broadcast();
                Bundle b = new Bundle();
                b.putString("json", Config.snapshot());
                return b;
            }
            // arg = 文件名（profiles.json / resigned_all.json），extras.data = 内容
            if ("putFile".equals(method) && arg != null && extras != null) {
                String name = arg.replace("..", "").replace("/", "_").replace("\\", "_");
                if (!(name.endsWith(".json") || name.endsWith(".txt"))) return null;
                byte[] data = extras.getByteArray("data");
                if (data == null) return null;
                // json → resign_tracker/，txt → files/ 根目录（如 notif_archive.txt）
                File out;
                if (name.endsWith(".txt")) {
                    out = new File(getContext().getFilesDir(), name);
                } else {
                    File dir = new File(getContext().getFilesDir(), "resign_tracker");
                    if (!dir.isDirectory()) dir.mkdirs();
                    out = new File(dir, name);
                }
                Config.write(out, new String(data, "UTF-8"));
                out.setReadable(true, false);
                Bundle b = new Bundle();
                b.putBoolean("ok", true);
                return b;
            }
        } catch (Throwable t) { android.util.Log.w("fucklark", "配置源调用失败 method=" + method, t); }
        return null;
    }

    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
