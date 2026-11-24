package com.chekayo.feishuantirecall;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** Exports and restores module-owned configuration and persistent archives through Android SAF. */
public final class DataMigration {
    private static final int REQ_EXPORT = 0x6F31;
    private static final int REQ_IMPORT = 0x6F32;
    private static final long MAX_BACKUP_BYTES = 32L * 1024L * 1024L;
    private static final int BUF_SIZE = 32 * 1024;
    private static final Pattern UID_PATTERN = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Pattern LEGACY_KICKED_PATTERN = Pattern.compile("kicked_[A-Za-z0-9_-]+\\.txt");
    private static final Pattern ACCOUNT_KICKED_PATTERN = Pattern.compile("kicked_[0-9]+\\.txt");
    private static final Pattern RECALL_MARKS_FILE_PATTERN = Pattern.compile("[0-9]+\\.marks");
    private static final Pattern RECALL_MARKS_PATH_PATTERN = Pattern.compile("recall_marks/[0-9]+\\.marks");
    private static final Pattern ACCOUNT_PATH_PATTERN = Pattern.compile(
            "accounts/[A-Za-z0-9_-]+/(resign_tracker/(profiles|resigned_all|resigned_latest)\\.json|notif_archive\\.txt|leave_log\\.txt|kicked_[0-9]+\\.txt)");

    private static volatile boolean installed;
    private static final AtomicInteger pendingRequest = new AtomicInteger(0);

    private DataMigration() {}

    public static synchronized void install() {
        if (installed) return;
        installed = true;
        try {
            XposedBridge.hookAllMethods(Activity.class, "onActivityResult", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    int request = (Integer) p.args[0];
                    int result = (Integer) p.args[1];
                    Intent data = (Intent) p.args[2];
                    Activity activity = (Activity) p.thisObject;
                    handleActivityResult(activity, request, result, data);
                }
            });
        } catch (Throwable t) {
            installed = false;
            logWarn("migration result hook failed", t);
        }
    }

    /** Handles SAF export/import results from either LauncherActivity or the hooked host Activity. */
    public static boolean handleActivityResult(Activity activity, int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_EXPORT && requestCode != REQ_IMPORT) return false;
        if (!pendingRequest.compareAndSet(requestCode, 0)) return false;
        if (activity == null || resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return true;
        }
        if (requestCode == REQ_EXPORT) exportAsync(activity, data.getData());
        else importAsync(activity, data.getData());
        return true;
    }

    public static void chooseExport(Context context) {
        Activity activity = activityOf(context);
        if (activity == null) { toast(context, "无法取得当前页面，不能打开文件选择器"); return; }
        try {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/zip");
            String day = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            i.putExtra(Intent.EXTRA_TITLE, "FeishuKit-backup-" + day + ".zip");
            pendingRequest.set(REQ_EXPORT);
            activity.startActivityForResult(i, REQ_EXPORT);
        } catch (Throwable t) {
            pendingRequest.compareAndSet(REQ_EXPORT, 0);
            toast(context, "打开导出位置失败: " + safeMessage(t));
        }
    }

    public static void chooseImport(Context context) {
        Activity activity = activityOf(context);
        if (activity == null) { toast(context, "无法取得当前页面，不能打开文件选择器"); return; }
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/zip");
            pendingRequest.set(REQ_IMPORT);
            activity.startActivityForResult(i, REQ_IMPORT);
        } catch (Throwable t) {
            pendingRequest.compareAndSet(REQ_IMPORT, 0);
            toast(context, "打开备份文件失败: " + safeMessage(t));
        }
    }

    private static void exportAsync(final Activity activity, final Uri uri) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    List<FileItem> files = collect(activity.getFilesDir());
                    OutputStream raw = activity.getContentResolver().openOutputStream(uri, "w");
                    if (raw == null) throw new Exception("目标文件不可写");
                    ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(raw));
                    try {
                        JSONObject manifest = new JSONObject();
                        manifest.put("format", 2);
                        manifest.put("moduleVersion", ModuleBuild.VERSION);
                        manifest.put("createdAt", System.currentTimeMillis());
                        manifest.put("sourcePackage", activity.getPackageName());
                        putBytes(zip, "manifest.json", manifest.toString(2).getBytes("UTF-8"));
                        byte[] buf = new byte[BUF_SIZE];
                        for (FileItem item : files) putFile(zip, item.file, item.path, buf);
                    } finally { zip.close(); }
                    done(activity, "备份完成，共 " + files.size() + " 个数据文件");
                } catch (Throwable t) {
                    logWarn("export backup failed", t);
                    done(activity, "备份失败: " + safeMessage(t));
                }
            }
        }, "fucklark-backup-export").start();
    }

    private static void importAsync(final Activity activity, final Uri uri) {
        new Thread(new Runnable() {
            @Override public void run() {
                File stage = new File(activity.getCacheDir(), "fucklark_restore_" + System.currentTimeMillis());
                try {
                    if (!stage.mkdirs()) throw new Exception("无法创建导入临时目录");
                    InputStream raw = activity.getContentResolver().openInputStream(uri);
                    if (raw == null) throw new Exception("备份文件不可读");
                    Set<String> extracted = extractValidated(raw, stage);
                    if (!extracted.contains("manifest.json")) throw new Exception("不是有效的 FeishuKit 备份");
                    JSONObject manifest = new JSONObject(readText(new File(stage, "manifest.json")));
                    int format = manifest.optInt("format", 0);
                    if (format != 1 && format != 2) throw new Exception("不支持的备份格式");
                    validateJsonFiles(stage, extracted);
                    File root = activity.getFilesDir();
                    int restored = 0;
                    for (String path : extracted) {
                        if ("manifest.json".equals(path)) continue;
                        atomicCopy(new File(stage, path), new File(root, path));
                        restored++;
                    }
                    Config.setFilesDir(root);
                    Config.load();
                    Config.syncAfterRestore(activity);
                    if (!Config.MODULE_PKG.equals(activity.getPackageName())) {
                        try { ArchiveSync.pushAll(); } catch (Throwable ignored) {}
                    }
                    done(activity, "恢复完成，共 " + restored + " 个数据文件；建议重启飞书");
                } catch (Throwable t) {
                    logWarn("import backup failed", t);
                    done(activity, "恢复失败，未通过校验: " + safeMessage(t));
                } finally { deleteTree(stage); }
            }
        }, "fucklark-backup-import").start();
    }

    static List<FileItem> collect(File root) {
        List<FileItem> out = new ArrayList<FileItem>();
        if (root == null || !root.isDirectory()) return out;
        addIfFile(out, root, "fucklark_cfg.json");
        addIfFile(out, root, "block_log.json");
        addIfFile(out, root, "notif_archive.txt");
        addIfFile(out, root, "leave_log.txt");
        File[] top = root.listFiles();
        if (top != null) {
            for (File f : top) {
                if (f.isFile() && LEGACY_KICKED_PATTERN.matcher(f.getName()).matches()) {
                    out.add(new FileItem(f, f.getName()));
                }
            }
        }
        addIfFile(out, root, "resign_tracker/resigned_all.json");
        addIfFile(out, root, "resign_tracker/resigned_latest.json");
        addIfFile(out, root, "resign_tracker/profiles.json");

        File accountsDir = new File(root, "accounts");
        File[] accountDirs = accountsDir.isDirectory() ? accountsDir.listFiles() : null;
        if (accountDirs != null) {
            for (File accDir : accountDirs) {
                if (!accDir.isDirectory()) continue;
                String uid = accDir.getName();
                if (!UID_PATTERN.matcher(uid).matches()) continue;
                String prefix = "accounts/" + uid + "/";
                addIfFile(out, root, prefix + "resign_tracker/profiles.json");
                addIfFile(out, root, prefix + "resign_tracker/resigned_all.json");
                addIfFile(out, root, prefix + "resign_tracker/resigned_latest.json");
                addIfFile(out, root, prefix + "notif_archive.txt");
                addIfFile(out, root, prefix + "leave_log.txt");
                File[] accFiles = accDir.listFiles();
                if (accFiles != null) {
                    for (File f : accFiles) {
                        if (f.isFile() && ACCOUNT_KICKED_PATTERN.matcher(f.getName()).matches()) {
                            out.add(new FileItem(f, prefix + f.getName()));
                        }
                    }
                }
            }
        }

        File marksDir = new File(root, "recall_marks");
        File[] markFiles = marksDir.isDirectory() ? marksDir.listFiles() : null;
        if (markFiles != null) {
            for (File f : markFiles) {
                if (f.isFile() && RECALL_MARKS_FILE_PATTERN.matcher(f.getName()).matches()) {
                    out.add(new FileItem(f, "recall_marks/" + f.getName()));
                }
            }
        }
        return out;
    }

    private static void addIfFile(List<FileItem> out, File root, String path) {
        File f = new File(root, path);
        if (f.isFile()) out.add(new FileItem(f, path));
    }

    static boolean allowed(String path) {
        if (path == null || path.isEmpty() || path.startsWith("/")
                || path.indexOf('\\') >= 0 || path.contains("..")) {
            return false;
        }
        if ("manifest.json".equals(path)
                || "fucklark_cfg.json".equals(path)
                || "block_log.json".equals(path)
                || "notif_archive.txt".equals(path)
                || "leave_log.txt".equals(path)
                || "resign_tracker/resigned_all.json".equals(path)
                || "resign_tracker/resigned_latest.json".equals(path)
                || "resign_tracker/profiles.json".equals(path)) {
            return true;
        }
        if (LEGACY_KICKED_PATTERN.matcher(path).matches()) return true;
        if (RECALL_MARKS_PATH_PATTERN.matcher(path).matches()) return true;
        if (ACCOUNT_PATH_PATTERN.matcher(path).matches()) return true;
        return false;
    }

    static Set<String> extractValidated(InputStream raw, File stage) throws Exception {
        Set<String> names = new HashSet<String>();
        String stageCanonical = stage.getCanonicalPath();
        if (!stageCanonical.endsWith(File.separator)) stageCanonical += File.separator;
        ZipInputStream zip = new ZipInputStream(new BufferedInputStream(raw));
        long total = 0;
        byte[] buf = new byte[BUF_SIZE];
        try {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                String name = e.getName().replace('\\', '/');
                if (e.isDirectory()) continue;
                if (!allowed(name) || name.startsWith("/") || name.contains("..") || !names.add(name))
                    throw new Exception("备份包含非法文件: " + name);
                File dst = new File(stage, name);
                if (!dst.getCanonicalPath().startsWith(stageCanonical))
                    throw new Exception("备份包含非法路径: " + name);
                File parent = dst.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new Exception("无法创建临时目录");
                OutputStream out = new BufferedOutputStream(new FileOutputStream(dst));
                try {
                    int n;
                    while ((n = zip.read(buf)) != -1) {
                        total += n;
                        if (total > MAX_BACKUP_BYTES) throw new Exception("备份超过 32MB 限制");
                        out.write(buf, 0, n);
                    }
                } finally { out.close(); }
                zip.closeEntry();
            }
        } finally { zip.close(); }
        return names;
    }

    private static void validateJsonFiles(File stage, Set<String> names) throws Exception {
        for (String name : names) {
            if (!name.endsWith(".json")) continue;
            try {
                String json = readText(new File(stage, name)).trim();
                if (json.startsWith("{")) new JSONObject(json);
                else if (json.startsWith("[")) new JSONArray(json);
                else throw new Exception("JSON 顶层必须是对象或数组");
            }
            catch (Throwable t) { throw new Exception("JSON 数据损坏: " + name); }
        }
    }

    private static void atomicCopy(File src, File dst) throws Exception {
        File parent = dst.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new Exception("无法创建目标目录");
        File tmp = new File(parent, dst.getName() + ".restore.tmp");
        copy(src, tmp);
        if (dst.exists() && !dst.delete()) { tmp.delete(); throw new Exception("无法替换 " + dst.getName()); }
        if (!tmp.renameTo(dst)) { copy(tmp, dst); tmp.delete(); }
    }

    private static void copy(File src, File dst) throws Exception {
        InputStream in = new BufferedInputStream(new FileInputStream(src));
        OutputStream out = new BufferedOutputStream(new FileOutputStream(dst));
        byte[] buf = new byte[BUF_SIZE];
        try { int n; while ((n = in.read(buf)) != -1) out.write(buf, 0, n); }
        finally { try { in.close(); } finally { out.close(); } }
    }

    private static void putFile(ZipOutputStream zip, File file, String path, byte[] buf) throws Exception {
        zip.putNextEntry(new ZipEntry(path));
        InputStream in = new BufferedInputStream(new FileInputStream(file));
        try { int n; while ((n = in.read(buf)) != -1) zip.write(buf, 0, n); }
        finally { in.close(); zip.closeEntry(); }
    }

    private static void putBytes(ZipOutputStream zip, String path, byte[] bytes) throws Exception {
        zip.putNextEntry(new ZipEntry(path)); zip.write(bytes); zip.closeEntry();
    }

    private static String readText(File f) throws Exception {
        InputStream in = new FileInputStream(f);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        try { int n; while ((n = in.read(buf)) != -1) out.write(buf, 0, n); }
        finally { in.close(); }
        return new String(out.toByteArray(), "UTF-8");
    }

    private static Activity activityOf(Context context) {
        Context c = context;
        for (int i = 0; i < 10 && c != null; i++) {
            if (c instanceof Activity) return (Activity) c;
            if (!(c instanceof ContextWrapper)) break;
            Context next = ((ContextWrapper) c).getBaseContext();
            if (next == c) break;
            c = next;
        }
        return null;
    }

    private static void done(final Activity a, final String text) {
        a.runOnUiThread(new Runnable() { @Override public void run() { toast(a, text); } });
    }

    private static void logWarn(String msg, Throwable t) {
        try { android.util.Log.w("fucklark", msg, t); } catch (Throwable ignored) {}
        try { XposedBridge.log("[fucklark] " + msg + ": " + t); } catch (Throwable ignored) {}
    }

    private static void toast(Context c, String text) { Toast.makeText(c, text, Toast.LENGTH_LONG).show(); }
    private static String safeMessage(Throwable t) { return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage(); }
    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteTree(c);
        f.delete();
    }

    static final class FileItem {
        final File file; final String path;
        FileItem(File file, String path) { this.file = file; this.path = path; }
    }
}
