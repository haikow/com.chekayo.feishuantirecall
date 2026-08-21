package com.chekayo.feishuantirecall;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.FileObserver;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.HashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 下载文件另存到系统「下载」—— 飞书下载的文件只落到应用私有/内部目录(文件管理器不可见、其它 App 打不开)。
 * 本功能把最终【可打开】的文件复制一份到公共 Download/[子目录](MediaStore, 符合 scoped storage, 无需额外权限)。
 *
 * 两条互补路径, 都只复制"能打开"的最终文件, 不碰加密中间产物:
 *   ① 云文档/加密文件(drive/security space): 解密后落到内部 …/lark_security_space/cache/…, 飞书弹「已下载至{path}」。
 *      hook com.ss.android.lark.utils.UIHelper.mustacheFormat(int,"path",filePath) 认出该 toast -> 复制 filePath, 并把
 *      超长内部路径 toast 改写成「已保存到 Download/…/文件名」(参考微信类模块的保存交互, 体验更清爽)。
 *   ② 普通聊天文件「另存为」: 落到外部 files/Lark/download/, FileObserver 监听 CLOSE_WRITE -> 复制。
 *
 * 抗版本: 类名/方法名(UIHelper.mustacheFormat)未混淆; 目标 toast 用资源名 Lark_IM_AndroidDownloadedToPathway_Toast
 * 运行时解析 id(不写死数字); FileObserver/MediaStore 均标准 API。开关 Config.pubdownload; 子目录 Config.pubdownloadSubdir。
 */
public class DownloadMirror {

    static final String DL_TOAST_RES = "Lark_IM_AndroidDownloadedToPathway_Toast";

    static volatile boolean INSTALLED = false;
    static volatile FileObserver observer;                        // 强引用防 GC
    static final HashMap<String, Long> recent = new HashMap<>();  // 去重: 文件名 -> 上次处理时间

    public static void install(Context ctx) {
        if (INSTALLED) return;
        INSTALLED = true;
        startObserver(ctx);          // ② 外部 Lark/download 监听
        installDownloadToastHook(ctx); // ① 云文档/加密文件: hook 完成 toast
    }

    // ① hook「已下载至{path}」toast: 复制最终可打开文件 + 改写文案。
    static void installDownloadToastHook(final Context ctx) {
        try {
            Class<?> uih = ctx.getClassLoader().loadClass("com.ss.android.lark.utils.UIHelper");
            Method m = uih.getDeclaredMethod("mustacheFormat", int.class, String.class, String.class);
            final int wantId = ctx.getResources().getIdentifier(DL_TOAST_RES, "string", ctx.getPackageName());
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!Config.pubdownload) return;
                    if (!"path".equals(p.args[1])) return;               // 只认 {{path}} 占位的串
                    int resId = (Integer) p.args[0];
                    if (!isDownloadToast(ctx, resId, wantId)) return;
                    String path = (String) p.args[2];
                    if (path == null) return;
                    File src = new File(path);
                    if (!src.isFile() || src.length() == 0) return;
                    mirrorAsync(ctx, src);
                    // 改写 toast: 超长内部路径 -> 友好公共路径
                    String sub = Config.pubdownloadSubdir;
                    String rel = "Download" + (sub == null || sub.isEmpty() ? "" : "/" + sub);
                    p.setResult("已保存到 " + rel + "/" + src.getName());
                }
            });
            XposedBridge.log("[fucklark][dl] 下载完成 toast 已 hook (UIHelper.mustacheFormat, resId=" + wantId + ")");
        } catch (Throwable t) {
            XposedBridge.log("[fucklark][dl] toast hook install failed: " + t);
        }
    }

    static boolean isDownloadToast(Context ctx, int resId, int wantId) {
        if (wantId != 0) return resId == wantId;
        try { return DL_TOAST_RES.equals(ctx.getResources().getResourceEntryName(resId)); }
        catch (Throwable t) { return false; }
    }

    // ② 监听外部 Lark/download 目录, 文件写完即复制。
    static void startObserver(final Context ctx) {
        try {
            File ext = ctx.getExternalFilesDir(null);
            if (ext == null) { XposedBridge.log("[fucklark][dl] getExternalFilesDir null, 跳过 observer"); return; }
            final File dir = new File(ext, "Lark/download");
            if (!dir.exists()) dir.mkdirs();
            observer = new FileObserver(dir.getAbsolutePath(), FileObserver.CLOSE_WRITE) {
                @Override public void onEvent(int event, String name) {
                    if (!Config.pubdownload) return;
                    if (name == null || name.startsWith(".")) return;
                    if (name.endsWith(".tmp") || name.endsWith(".download") || name.endsWith(".part")) return;
                    File src = new File(dir, name);
                    if (!src.isFile() || src.length() == 0) return;
                    mirrorAsync(ctx, src);
                }
            };
            observer.startWatching();
            XposedBridge.log("[fucklark][dl] 下载另存 已监听: " + dir.getAbsolutePath());
        } catch (Throwable t) {
            XposedBridge.log("[fucklark][dl] observer install failed: " + t);
        }
    }

    // 后台线程复制(勿在 UI 线程 / toast 格式化里同步拷大文件), 带短时去重。
    static void mirrorAsync(final Context ctx, final File src) {
        final String name = src.getName();
        if (isDup(name)) return;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    copyToPublicDownload(ctx.getContentResolver(), src, name);
                    XposedBridge.log("[fucklark][dl] 已另存到系统下载: " + name);
                } catch (Throwable t) {
                    XposedBridge.log("[fucklark][dl] 另存失败(" + name + "): " + t);
                }
            }
        }, "fucklark-dl-mirror").start();
    }

    static synchronized boolean isDup(String name) {
        long now = System.currentTimeMillis();
        Long last = recent.get(name);
        recent.put(name, now);
        if (recent.size() > 256) recent.clear();
        return last != null && now - last < 8000;   // 8s 内同名视为重复
    }

    // 复制到公共 Download/[子目录]。Android 10+ 走 MediaStore.Downloads(无需权限); 9 及以下直接写公共目录。
    static void copyToPublicDownload(ContentResolver resolver, File src, String name) throws Exception {
        String subdir = Config.pubdownloadSubdir;   // 已在 Config.setStr 清洗
        if (Build.VERSION.SDK_INT >= 29) {
            String relPath = Environment.DIRECTORY_DOWNLOADS + (subdir == null || subdir.isEmpty() ? "" : "/" + subdir);
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, name);
            v.put(MediaStore.Downloads.RELATIVE_PATH, relPath);
            v.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri item = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (item == null) throw new Exception("MediaStore.insert 返回 null");
            try (InputStream in = new FileInputStream(src); OutputStream out = resolver.openOutputStream(item)) {
                if (out == null) throw new Exception("openOutputStream null");
                pump(in, out);
            }
            v.clear();
            v.put(MediaStore.Downloads.IS_PENDING, 0);
            resolver.update(item, v, null, null);
        } else {
            File base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File dstDir = (subdir == null || subdir.isEmpty()) ? base : new File(base, subdir);
            if (!dstDir.exists()) dstDir.mkdirs();
            File dst = uniqueFile(dstDir, name);
            try (InputStream in = new FileInputStream(src); OutputStream out = new java.io.FileOutputStream(dst)) {
                pump(in, out);
            }
        }
    }

    static File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            File c = new File(dir, stem + "(" + i + ")" + ext);
            if (!c.exists()) return c;
        }
        return f;
    }

    static void pump(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[64 * 1024];
        int r;
        while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
        out.flush();
    }
}
