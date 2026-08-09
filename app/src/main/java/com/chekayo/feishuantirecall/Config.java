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
        save();
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
            write(cfgFile, o.toString());
        } catch (Throwable ignored) {}
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
