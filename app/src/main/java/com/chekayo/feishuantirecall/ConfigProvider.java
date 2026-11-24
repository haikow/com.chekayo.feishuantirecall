package com.chekayo.feishuantirecall;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipFile;

/**
 * 模块侧权威源：配置 + 同事档案副本。
 * 飞书进程启动时 call get 拉配置；归档写入后经 FD 流式 openFile + commitFile
 * （或小文件 putFile）推送 profiles/resigned/notif_archive 到模块目录，
 * 桌面入口才能读到「同事记录」。
 */
public class ConfigProvider extends ContentProvider {
    public static final String AUTHORITY = "com.chekayo.feishuantirecall.config";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY);

    private static final int MAX_PUT_FILE_BYTES = 256 * 1024;
    private static final int MAX_COMMIT_BYTES = 16 * 1024 * 1024;
    private static final long MAX_STAGE_FILE_BYTES = MAX_COMMIT_BYTES + 64L * 1024L;
    private static final long STALE_STAGE_MS = 5L * 60L * 1000L;
    private static final int MAX_STAGE_FILES = 64;
    private static final int BUF_SIZE = 32 * 1024;
    private static final Pattern STAGE_TOKEN = Pattern.compile("[A-Za-z0-9_-]{8,64}");
    private static final Pattern ARCHIVE_NAME = Pattern.compile("[A-Za-z0-9_]+\\.(json|txt)");
    private static final ConcurrentHashMap<String, Boolean> VERIFIED_HOSTS = new ConcurrentHashMap<String, Boolean>();

    @Override public boolean onCreate() { return true; }

    @Override public synchronized Bundle call(String method, String arg, Bundle extras) {
        Context ctx = getContext();
        if (ctx != null) {
            Config.setContext(ctx);
            if (Config.cfgFile == null) Config.setFilesDir(ctx.getFilesDir());
        }
        String callerPkg = resolveAuthorizedCaller(ctx, extras);
        if (callerPkg == null) {
            android.util.Log.w("fucklark", "拒绝未授权配置源调用 method=" + method + " uid=" + Binder.getCallingUid());
            throw new SecurityException("unauthorized caller");
        }
        if (!Config.MODULE_PKG.equals(callerPkg)) {
            Config.registerHostPackage(callerPkg);
        }
        try {
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
                String json = extras != null ? extras.getString("json") : null;
                if (BlockLog.isValidEventJson(json)) {
                    BlockLog.appendLocal(ctx, json);
                }
                Bundle b = new Bundle();
                b.putBoolean("ok", true);
                return b;
            }
            if ("blockLogGet".equals(method)) {
                Bundle b = new Bundle();
                b.putString("text", BlockLog.readLocal(ctx));
                return b;
            }
            if ("patch".equals(method)) {
                Bundle b = new Bundle();
                b.putString("json", Config.applyPatch(extras != null && extras.getString("json") != null ? extras.getString("json") : arg));
                return b;
            }
            if ("put".equals(method)) {
                Config.load();
                String json = extras != null ? extras.getString("json") : null;
                boolean force = extras != null && extras.getBoolean("force", false);
                if (force) Config.applyAuthorityRestore(json);
                else Config.onSyncReceive(json);
                Config.broadcast();
                Bundle b = new Bundle();
                b.putString("json", Config.snapshot());
                return b;
            }
            // 流式提交：openFile 写完 staging 临时文件后，由 commitFile 校验、解压并原子落盘。
            if ("commitFile".equals(method) && arg != null && extras != null && ctx != null) {
                if (!ARCHIVE_NAME.matcher(arg).matches()) return null;
                String token = extras.getString("token");
                if (token == null || !STAGE_TOKEN.matcher(token).matches()) return null;
                File stageDir = new File(ctx.getFilesDir(), "archive_stage");
                File stageFile = new File(stageDir, token + ".tmp");
                if (!stageFile.isFile()) {
                    pruneStagingDir(stageDir);
                    return null;
                }
                byte[] data;
                try {
                    data = readStagingFile(stageFile, extras.getBoolean("gzip", false));
                } finally {
                    stageFile.delete();
                    pruneStagingDir(stageDir);
                }
                if (data == null || data.length == 0) return null;
                ArchiveReplicaStore.save(ctx.getFilesDir(), extras.getString("uid"), arg, data);
                Bundle b = new Bundle();
                b.putBoolean("ok", true);
                return b;
            }
            // 小文件回落（<= 256KB）：arg = 文件名，extras.data = 内容
            if ("putFile".equals(method) && arg != null && extras != null && ctx != null) {
                if (!ARCHIVE_NAME.matcher(arg).matches()) return null;
                byte[] data = extras.getByteArray("data");
                if (data == null || data.length == 0 || data.length > MAX_PUT_FILE_BYTES) return null;
                ArchiveReplicaStore.save(ctx.getFilesDir(), extras.getString("uid"), arg, data);
                Bundle b = new Bundle();
                b.putBoolean("ok", true);
                return b;
            }
        } catch (Throwable t) { android.util.Log.w("fucklark", "配置源调用失败 method=" + method, t); }
        return null;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        Context ctx = getContext();
        if (ctx != null) {
            Config.setContext(ctx);
            if (Config.cfgFile == null) Config.setFilesDir(ctx.getFilesDir());
        }
        String callerPkg = resolveAuthorizedCaller(ctx, null);
        if (callerPkg == null) {
            android.util.Log.w("fucklark", "拒绝未授权 openFile uid=" + Binder.getCallingUid());
            throw new SecurityException("unauthorized caller");
        }
        if (!Config.MODULE_PKG.equals(callerPkg)) {
            Config.registerHostPackage(callerPkg);
        }
        if (!"w".equals(mode) && !"wt".equals(mode)) {
            throw new SecurityException("staging uri is write-only");
        }
        if (ctx == null || uri == null) throw new FileNotFoundException("invalid staging request");
        List<String> segments = uri.getPathSegments();
        if (segments == null || segments.size() != 2 || !"archive_stage".equals(segments.get(0))) {
            throw new FileNotFoundException("unsupported uri");
        }
        String token = segments.get(1);
        if (token == null || !STAGE_TOKEN.matcher(token).matches()) {
            throw new FileNotFoundException("invalid staging token");
        }
        File stageDir = new File(ctx.getFilesDir(), "archive_stage");
        if (!stageDir.isDirectory() && !stageDir.mkdirs()) {
            throw new FileNotFoundException("cannot create archive_stage dir");
        }
        pruneStagingDir(stageDir);
        File stageFile = new File(stageDir, token + ".tmp");
        return ParcelFileDescriptor.open(stageFile,
                ParcelFileDescriptor.MODE_CREATE
                        | ParcelFileDescriptor.MODE_TRUNCATE
                        | ParcelFileDescriptor.MODE_WRITE_ONLY);
    }

    boolean isAuthorizedCaller() {
        return resolveAuthorizedCaller(getContext(), null) != null;
    }

    /**
     * 校验 IPC 调用方身份：返回经系统 UID 核实的包名；未授权返回 null。
     * 放行：自身进程 / 模块包 / 国内与国际飞书 / 已登记白标宿主 / 含 liblark.so 或 assets/lark 特征的白标飞书。
     */
    private String resolveAuthorizedCaller(Context ctx, Bundle extras) {
        int callingUid = Binder.getCallingUid();
        if (callingUid == android.os.Process.myUid()) return Config.MODULE_PKG;
        if (ctx == null) return null;
        PackageManager pm = ctx.getPackageManager();
        if (pm == null) return null;

        List<String> uidPackages = new ArrayList<String>();
        String[] rawPkgs = null;
        try { rawPkgs = pm.getPackagesForUid(callingUid); } catch (Throwable ignored) {}
        if (rawPkgs != null) {
            for (String p : rawPkgs) {
                if (p != null && !p.isEmpty()) uidPackages.add(p);
            }
        }
        String callingPkg = null;
        try { callingPkg = getCallingPackage(); } catch (Throwable ignored) {}
        if (callingPkg != null && !callingPkg.isEmpty()) {
            if (uidPackages.isEmpty()) {
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(callingPkg, 0);
                    if (ai != null && ai.uid == callingUid) uidPackages.add(callingPkg);
                } catch (Throwable ignored) {}
            } else if (uidPackages.contains(callingPkg)) {
                uidPackages.remove(callingPkg);
                uidPackages.add(0, callingPkg);
            }
        }
        if (uidPackages.isEmpty()) return null;

        // 防止恶意方通过 extras.package 伪造其它宿主包名
        if (extras != null && extras.containsKey("package")) {
            String claimed = extras.getString("package");
            if (claimed != null && !claimed.isEmpty() && !uidPackages.contains(claimed)) {
                return null;
            }
        }

        List<String> knownHosts = Config.knownHostPackages();
        for (String pkg : uidPackages) {
            if (Config.MODULE_PKG.equals(pkg)) return pkg;
            if ("com.ss.android.lark".equals(pkg) || "com.larksuite.suite".equals(pkg)
                    || knownHosts.contains(pkg)) {
                return pkg;
            }
        }
        for (String pkg : uidPackages) {
            if (isVerifiedWhiteLabelLark(pm, pkg)) return pkg;
        }
        return null;
    }

    private static boolean isVerifiedWhiteLabelLark(PackageManager pm, String pkg) {
        Boolean cached = VERIFIED_HOSTS.get(pkg);
        if (cached != null) return cached.booleanValue();
        boolean verified = false;
        boolean inspected = false;
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            if (ai != null) {
                inspected = true;
                if (ai.nativeLibraryDir != null
                        && new File(ai.nativeLibraryDir, "liblark.so").exists()) {
                    verified = true;
                }
                if (!verified) {
                    List<String> apks = new ArrayList<String>();
                    if (ai.sourceDir != null) apks.add(ai.sourceDir);
                    if (ai.splitSourceDirs != null) {
                        for (String split : ai.splitSourceDirs) {
                            if (split != null) apks.add(split);
                        }
                    }
                    for (String apkPath : apks) {
                        if (apkHasLarkMarker(apkPath)) {
                            verified = true;
                            break;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        if (inspected && VERIFIED_HOSTS.size() < 256) {
            VERIFIED_HOSTS.put(pkg, Boolean.valueOf(verified));
        }
        return verified;
    }

    private static boolean apkHasLarkMarker(String apkPath) {
        ZipFile zf = null;
        try {
            zf = new ZipFile(apkPath);
            return zf.getEntry("lib/arm64-v8a/liblark.so") != null
                    || zf.getEntry("lib/armeabi-v7a/liblark.so") != null
                    || zf.getEntry("lib/x86_64/liblark.so") != null
                    || zf.getEntry("assets/lark/") != null
                    || zf.getEntry("assets/lark_config.json") != null;
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (zf != null) try { zf.close(); } catch (Throwable ignored) {}
        }
    }

    private static byte[] readStagingFile(File stageFile, boolean gzip) throws IOException {
        if (stageFile.length() <= 0 || stageFile.length() > MAX_STAGE_FILE_BYTES) {
            throw new IOException("invalid staging file size: " + stageFile.length());
        }
        InputStream in = new BufferedInputStream(new FileInputStream(stageFile));
        if (gzip) {
            try {
                in = new GZIPInputStream(in);
            } catch (IOException e) {
                try { in.close(); } catch (Throwable ignored) {}
                throw e;
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[BUF_SIZE];
        int total = 0;
        try {
            int n;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > MAX_COMMIT_BYTES) {
                    throw new IOException("archive exceeds 16MB limit");
                }
                out.write(buf, 0, n);
            }
        } finally {
            in.close();
        }
        return out.toByteArray();
    }

    private static void pruneStagingDir(File stageDir) {
        try {
            if (stageDir == null || !stageDir.isDirectory()) return;
            File[] files = stageDir.listFiles();
            if (files == null || files.length == 0) return;
            long cutoff = System.currentTimeMillis() - STALE_STAGE_MS;
            List<File> remaining = new ArrayList<File>();
            for (File f : files) {
                if (!f.isFile() || !f.getName().endsWith(".tmp")) continue;
                if (f.lastModified() < cutoff) f.delete();
                else remaining.add(f);
            }
            if (remaining.size() > MAX_STAGE_FILES) {
                java.util.Collections.sort(remaining, new java.util.Comparator<File>() {
                    @Override public int compare(File a, File b) {
                        return Long.compare(a.lastModified(), b.lastModified());
                    }
                });
                int excess = remaining.size() - MAX_STAGE_FILES;
                for (int i = 0; i < excess; i++) remaining.get(i).delete();
            }
        } catch (Throwable ignored) {}
    }

    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
