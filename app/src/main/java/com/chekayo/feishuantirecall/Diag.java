package com.chekayo.feishuantirecall;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * fuck lark 诊断日志：把关键事件(环境/安装/撤回拦截/反篡改)持久化到飞书私有目录的
 * 一个带上限的文本文件，供终端用户在设置面板一键复制发给作者排查“防撤回不生效”。
 *
 * native(libantirecall.so) 写同一个文件(见 antirecall.cpp flog); Java 侧走 {@link #w}。
 * 追加写 + 超过 CAP 截断为后半段，避免无限增长。
 */
public class Diag {
    // 由 AntiRecall.startNative 调 setFilesDir 初始化(按当前目标包, 国内/国际版自适应)。
    static volatile File FILE;

    public static void setFilesDir(File filesDir) {
        if (filesDir == null) return;
        FILE = new File(filesDir, "fucklark_log.txt");
    }
    static final int CAP = 256 * 1024;   // 256KB 上限
    static final SimpleDateFormat TS = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);

    public static synchronized void w(String msg) {
        if (!Config.diaglog) return;   // 开关关闭 = 完全不写文件
        if (FILE == null) return;       // setFilesDir 未调(非主进程/未到 startNative)
        try {
            File dir = FILE.getParentFile();
            if (dir != null && !dir.isDirectory()) dir.mkdirs();
            long len = FILE.exists() ? FILE.length() : 0;
            if (len > CAP) truncate();
            FileOutputStream os = new FileOutputStream(FILE, true);
            os.write((TS.format(new Date()) + " " + msg + "\n").getBytes("UTF-8"));
            os.flush();
            os.close();
            FILE.setReadable(true, false);
        } catch (Throwable ignored) {}
    }

    /** 超过上限时保留后半段(最近的日志更有价值)。 */
    static void truncate() {
        try {
            byte[] all = readBytes(FILE);
            int keep = all.length / 2;
            byte[] tail = new byte[keep];
            System.arraycopy(all, all.length - keep, tail, 0, keep);
            FileOutputStream os = new FileOutputStream(FILE, false);
            os.write("...(旧日志已截断)...\n".getBytes("UTF-8"));
            os.write(tail);
            os.flush();
            os.close();
        } catch (Throwable ignored) {}
    }

    public static synchronized String read() {
        try {
            if (FILE == null || !FILE.exists()) return "(暂无日志)";
            return new String(readBytes(FILE), "UTF-8");
        } catch (Throwable t) {
            return "(读取日志失败: " + t + ")";
        }
    }

    public static synchronized void clear() {
        try { if (FILE != null && FILE.exists()) FILE.delete(); } catch (Throwable ignored) {}
    }

    static byte[] readBytes(File f) throws Exception {
        FileInputStream is = new FileInputStream(f);
        byte[] b = new byte[(int) f.length()];
        int off = 0, r;
        while (off < b.length && (r = is.read(b, off, b.length - off)) > 0) off += r;
        is.close();
        return b;
    }
}
