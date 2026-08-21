package com.chekayo.feishuantirecall;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

import org.json.JSONObject;

/**
 * fuck lark 统一配置：进程内共享开关（设置面板与各 hook 同在飞书进程，直接读写内存 flag + 持久化 JSON）。
 * 改开关即时生效，无需重装/重启。用固定路径, 三处(AntiRecall/ResignTracker/设置面板)都能直接调。
 */
public class Config {
    public static volatile boolean antirecall = true;   // 防撤回
    public static volatile boolean resign = true;       // 离职统计
    public static volatile boolean antiread = false;    // 防对方已读 (清空 UPDATE_MESSAGES_ME_READ 的 message_ids/fold_ids)
    public static volatile boolean diaglog = false;     // 诊断日志 (关=完全不写文件)
    public static volatile boolean keepkicked = false;  // 保留被踢群聊天记录
    public static volatile boolean leavenotify = false; // 退群/被移除提醒 (Toast + 记录)
    public static volatile boolean orgwalker = true;   // 组织架构自动巡游 (AccessibilityService, 需在系统无障碍里开启)
    public static volatile boolean notifarchive = false; // 后台消息存档 (hook 通知抓正文, 救后台被撤回的消息)
    public static volatile boolean dewatermark = false; // 去除聊天水印 (hook View.setForeground 丢弃 watermark 前景)
    public static volatile boolean downloadunlock = false; // 解除文件/图片下载限制 (加密聊天禁另存 -> 强制放行)
    public static volatile boolean restrictunlock = false; // 解除保密模式复制/转发限制 (RestrictedMode 门禁拦截器 -> 全放行)
    public static volatile boolean pubdownload = false;    // 下载的文件同时另存到系统「下载」(MediaStore, 符合安卓规范)
    public static volatile boolean screenshotnoaudit = false; // 截图不上报 (no-op 截图审计检测器 ActivityObserver.onActivityResumed)
    public static volatile boolean forcescreenshot = false;   // 强制截图 (剥离 FLAG_SECURE / SurfaceView.setSecure, 仅飞书进程内)
    public static volatile boolean noauditall = false;        // 全审计无痕总闸 (no-op AuditEventStorage.writeData(Event), 一处掐全部上报)
    public static volatile String pubdownloadSubdir = "Lark";  // 公共下载子目录名 (空=直接 Download/; 默认 Download/Lark)
    public static volatile boolean updatebanner = true;  // 主页顶部更新横幅(有新版时提示)
    public static volatile int dismissedUpc = 0;         // 已忽略的更新 versionCode(× 关闭后记住, 不再唠叨)

    // 由 AntiRecall.startNative 调 setFilesDir 初始化(按当前目标包, 国内/国际版自适应)。
    static volatile File cfgFile;

    public static void setFilesDir(File filesDir) {
        if (filesDir == null) return;
        cfgFile = new File(filesDir, "fucklark_cfg.json");
    }

    // profiles.json 由 ProfileCapture(扒UI) 与 ProfileBulk(批量解blob) 两处读改写, 共用此锁互斥, 防并发写坏
    public static final Object PROFILES_LOCK = new Object();

    public static synchronized void load() {
        try {
            if (cfgFile != null && cfgFile.exists()) {
                JSONObject o = new JSONObject(read(cfgFile));
                antirecall = o.optBoolean("antirecall", true);
                resign = o.optBoolean("resign", true);
                antiread = o.optBoolean("antiread", false);
                diaglog = o.optBoolean("diaglog", false);
                keepkicked = o.optBoolean("keepkicked", false);
                leavenotify = o.optBoolean("leavenotify", false);
                orgwalker = o.optBoolean("orgwalker", true);
                notifarchive = o.optBoolean("notifarchive", false);
                dewatermark = o.optBoolean("dewatermark", false);
                downloadunlock = o.optBoolean("downloadunlock", false);
                restrictunlock = o.optBoolean("restrictunlock", false);
                pubdownload = o.optBoolean("pubdownload", false);
                screenshotnoaudit = o.optBoolean("screenshotnoaudit", false);
                forcescreenshot = o.optBoolean("forcescreenshot", false);
                noauditall = o.optBoolean("noauditall", false);
                pubdownloadSubdir = o.optString("pubdownloadSubdir", "Lark");
                updatebanner = o.optBoolean("updatebanner", true);
                dismissedUpc = o.optInt("dismissedUpc", 0);
            } else {
                save();
            }
        } catch (Throwable ignored) {}
    }

    public static synchronized void set(String key, boolean v) {
        if ("antirecall".equals(key)) antirecall = v;
        else if ("resign".equals(key)) resign = v;
        else if ("antiread".equals(key)) antiread = v;
        else if ("diaglog".equals(key)) diaglog = v;
        else if ("keepkicked".equals(key)) keepkicked = v;
        else if ("leavenotify".equals(key)) leavenotify = v;
        else if ("orgwalker".equals(key)) orgwalker = v;
        else if ("notifarchive".equals(key)) notifarchive = v;
        else if ("dewatermark".equals(key)) dewatermark = v;
        else if ("downloadunlock".equals(key)) downloadunlock = v;
        else if ("restrictunlock".equals(key)) restrictunlock = v;
        else if ("pubdownload".equals(key)) pubdownload = v;
        else if ("screenshotnoaudit".equals(key)) screenshotnoaudit = v;
        else if ("forcescreenshot".equals(key)) forcescreenshot = v;
        else if ("noauditall".equals(key)) noauditall = v;
        else if ("updatebanner".equals(key)) updatebanner = v;
        save();
    }

    public static synchronized void setDismissed(int vc) { dismissedUpc = vc; save(); }

    // 字符串配置(目前仅 pubdownloadSubdir)。子目录名做基本清洗: 去首尾空白与斜杠, 拦非法字符。
    public static synchronized void setStr(String key, String v) {
        if ("pubdownloadSubdir".equals(key)) pubdownloadSubdir = sanitizeSubdir(v);
        save();
    }

    static String sanitizeSubdir(String s) {
        if (s == null) return "";
        s = s.trim();
        // 去掉首尾斜杠, 过滤路径穿越与非法文件名字符
        while (s.startsWith("/")) s = s.substring(1);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        s = s.replace("..", "").replaceAll("[\\\\:*?\"<>|]", "");
        return s.trim();
    }

    static synchronized void save() {
        try {
            if (cfgFile == null) return;
            JSONObject o = new JSONObject();
            o.put("antirecall", antirecall);
            o.put("resign", resign);
            o.put("antiread", antiread);
            o.put("diaglog", diaglog);
            o.put("keepkicked", keepkicked);
            o.put("leavenotify", leavenotify);
            o.put("orgwalker", orgwalker);
            o.put("notifarchive", notifarchive);
            o.put("dewatermark", dewatermark);
            o.put("downloadunlock", downloadunlock);
            o.put("restrictunlock", restrictunlock);
            o.put("pubdownload", pubdownload);
            o.put("screenshotnoaudit", screenshotnoaudit);
            o.put("forcescreenshot", forcescreenshot);
            o.put("noauditall", noauditall);
            o.put("pubdownloadSubdir", pubdownloadSubdir);
            o.put("updatebanner", updatebanner);
            o.put("dismissedUpc", dismissedUpc);
            File dir = cfgFile.getParentFile();
            if (dir != null && !dir.isDirectory()) dir.mkdirs();   // 定制飞书 files 目录可能尚未创建
            write(cfgFile, o.toString());
        } catch (Throwable t) {
            // 不再静默: 定制/白标飞书写盘失败时(路径/权限/沙箱), 打日志便于用户反馈定位「重启掉配置」。
            try { de.robv.android.xposed.XposedBridge.log("[fucklark] Config.save 失败 path=" + cfgFile + " err=" + t); } catch (Throwable ignored) {}
        }
    }

    static String read(File f) throws Exception {
        FileInputStream is = new FileInputStream(f);
        byte[] b = new byte[(int) f.length()];
        int off = 0, r; while (off < b.length && (r = is.read(b, off, b.length - off)) > 0) off += r;
        is.close(); return new String(b, 0, off, "UTF-8");
    }
    static void write(File f, String s) throws Exception {
        FileOutputStream os = new FileOutputStream(f);
        os.write(s.getBytes("UTF-8")); os.flush(); os.close();
        f.setReadable(true, false);
    }
}
