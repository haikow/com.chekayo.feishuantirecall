package com.chekayo.feishuantirecall;

import android.app.Notification;
import android.os.Bundle;
import android.os.Parcelable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 后台消息存档 —— 只留【被撤回】的消息原文。
 *
 * 为什么需要:客户端离线/后台期间对方撤回,撤回发生在服务器;等你上线同步,服务器只下发"已撤回"空壳,
 * 原文正文根本不发到本地库 -> SQL 层防撤回无能为力(见"防撤回场景 B"取证)。但原文其实到过设备 ——
 * 飞书用它弹了条通知。故在通知层 hook NotificationManager.notify:普通消息先进【内存缓冲(不落盘)】,
 * 当"X 撤回了一条消息"的撤回通知到来时,按发送人从缓冲回捞那条原文、只把【被撤回的】落盘。
 *
 * 前提:飞书通知开着"消息预览"(否则通知只有"你收到一条新消息",正文本就没送到设备)。仅文本可靠。
 * 局限:靠 发送人+时间 关联,群里同人连发多条时只能捞最近一条;消息通知与撤回通知须由同一进程弹
 * (缓冲在内存,不跨进程);撤回若无通知(同步时静默撤回)则捞不到。
 *
 * 跨进程:本类在【每个飞书进程】各自 hook + 各自读同一份配置(飞书私有目录,同 UID 可读);故设
 * filesDir 时也重载一次 Config,并对开关做节流热读(用户在设置里改开关后,弹通知的进程也能几秒内跟上)。
 */
public class NotifArchive {
    static volatile File FILE;
    static final int CAP = 256 * 1024;   // 256KB 上限
    static final SimpleDateFormat TS = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);

    // 近期消息缓冲(内存, 不落盘): 撤回通知到达时按发送人回捞对应原文, 只把【被撤回的】那条落盘。
    static final int BUF = 80;
    static final long BUF_WINDOW = 10 * 60 * 1000L;   // 10 分钟内的消息才算撤回候选
    static final java.util.ArrayList<String[]> recent = new java.util.ArrayList<String[]>();  // {timeMs, sender, body}
    static volatile String lastSaved = "";
    static volatile long   lastSavedTime = 0;

    // 开关热读节流: 非主进程内存里的 Config 不会随设置面板即时更新,故每 ~3s 从磁盘重载一次。
    static volatile long lastCfgReload = 0;

    public static void setFilesDir(File filesDir) {
        if (filesDir == null) return;
        FILE = new File(filesDir, "notif_archive.txt");
    }

    /** 由 NotificationManager.notify hook 调:普通消息进内存缓冲,撤回通知回捞原文落盘。 */
    public static void capture(Notification n) {
        try {
            if (n == null) return;
            // 开关: 节流热读磁盘配置(覆盖非主进程 Config 内存不同步的情况)
            long now = System.currentTimeMillis();
            if (now - lastCfgReload > 3000) { lastCfgReload = now; try { Config.load(); } catch (Throwable ignored) {} }
            if (!Config.notifarchive) return;
            if (FILE == null) return;

            // 跳过常驻/前台服务/分组汇总通知(不是真正的消息)
            int f = n.flags;
            if ((f & Notification.FLAG_ONGOING_EVENT) != 0) return;
            if ((f & Notification.FLAG_FOREGROUND_SERVICE) != 0) return;
            if ((f & Notification.FLAG_GROUP_SUMMARY) != 0) return;

            Bundle ex = n.extras;
            if (ex == null) return;
            String title = cs(ex.getCharSequence(Notification.EXTRA_TITLE));
            String text  = cs(ex.getCharSequence(Notification.EXTRA_BIG_TEXT));
            if (text.isEmpty()) text = cs(ex.getCharSequence(Notification.EXTRA_TEXT));

            // MessagingStyle: 正文在 EXTRA_MESSAGES(每条含 sender/text),取最后一条更准
            String msgSender = "";
            try {
                Parcelable[] msgs = ex.getParcelableArray(Notification.EXTRA_MESSAGES);
                if (msgs != null && msgs.length > 0 && msgs[msgs.length - 1] instanceof Bundle) {
                    Bundle mb = (Bundle) msgs[msgs.length - 1];
                    String mt = cs(mb.getCharSequence("text"));
                    String ms = cs(mb.getCharSequence("sender"));
                    if (!mt.isEmpty()) text = mt;
                    if (!ms.isEmpty()) msgSender = ms;
                }
            } catch (Throwable ignored) {}

            title = title.replace('\t', ' ').replace('\n', ' ').trim();
            String body = text.replace('\t', ' ').replace('\n', ' ').trim();
            if (body.isEmpty()) return;                 // 无正文(预览关) -> 没救

            // ① 撤回系统通知 -> 从内存缓冲回捞该发送人最近一条, 只把【被撤回的】原文落盘
            String rs = recallSender(body);
            if (rs != null) {
                String[] hit = takeRecent(rs, now);
                if (hit != null) {
                    String sig = hit[0] + "|" + hit[1];
                    if (sig.equals(lastSaved) && (now - lastSavedTime) < 8000) return;   // 同条撤回不重复落
                    lastSaved = sig; lastSavedTime = now;
                    write(TS.format(new Date(now)) + "\t" + hit[0] + "\t" + hit[1] + "\n");
                }
                return;   // 撤回通知本身不记
            }

            // ② 普通消息 -> 拆"发送人: 正文", 只进内存缓冲(不落盘); 被撤回时才捞出来
            String sender = msgSender, msg = body;
            if (sender.isEmpty()) {
                int i = sepIdx(body);
                if (i > 0) { sender = body.substring(0, i).trim(); msg = body.substring(i + 1).trim(); }
                else sender = title;
            }
            if (msg.isEmpty()) return;
            addRecent(now, sender, msg);
        } catch (Throwable ignored) {}
    }

    // 撤回系统通知的发送人(非撤回通知返回 null; 认不出发送人返回 "")。
    static String recallSender(String body) {
        if (body == null) return null;
        int i = body.indexOf("撤回了一条消息");
        if (i >= 0) return body.substring(0, i).trim();
        if (body.contains("已撤回")) return "";
        String l = body.toLowerCase(Locale.US);
        int j = l.indexOf(" recalled a message");
        if (j < 0) j = l.indexOf(" unsent a message");
        if (j > 0) return body.substring(0, j).trim();
        return null;
    }

    // 从内存缓冲(新->旧)取该发送人最近一条, 命中即移除。sender 空=不限, 取最新。
    static synchronized String[] takeRecent(String sender, long now) {
        for (int i = recent.size() - 1; i >= 0; i--) {
            String[] e = recent.get(i);
            if (now - Long.parseLong(e[0]) > BUF_WINDOW) continue;
            boolean match = sender == null || sender.isEmpty()
                    || e[1].equals(sender) || e[1].contains(sender) || sender.contains(e[1]);
            if (match) { recent.remove(i); return new String[]{ e[1], e[2] }; }
        }
        return null;
    }

    static synchronized void addRecent(long now, String sender, String msg) {
        for (int i = recent.size() - 1; i >= 0 && i >= recent.size() - 3; i--) {
            String[] e = recent.get(i);
            if (e[1].equals(sender) && e[2].equals(msg)) { e[0] = Long.toString(now); return; }   // 连发去重
        }
        recent.add(new String[]{ Long.toString(now), sender, msg });
        while (recent.size() > BUF) recent.remove(0);
    }

    // "发送人:正文" 的分隔冒号(全角/半角, 取最靠前的)
    static int sepIdx(String s) {
        int a = s.indexOf('：'); int b = s.indexOf(':');
        if (a < 0) return b; if (b < 0) return a; return Math.min(a, b);
    }

    static String cs(CharSequence c) { return c == null ? "" : c.toString(); }

    static synchronized void write(String line) {
        try {
            File dir = FILE.getParentFile();
            if (dir != null && !dir.isDirectory()) dir.mkdirs();
            long len = FILE.exists() ? FILE.length() : 0;
            if (len > CAP) truncate();
            FileOutputStream os = new FileOutputStream(FILE, true);
            os.write(line.getBytes("UTF-8"));
            os.flush(); os.close();
            FILE.setReadable(true, false);
        } catch (Throwable ignored) {}
    }

    /** 超上限保留后半段(最近的更有用)。 */
    static void truncate() {
        try {
            byte[] all = readBytes(FILE);
            int keep = all.length / 2;
            byte[] tail = new byte[keep];
            System.arraycopy(all, all.length - keep, tail, 0, keep);
            FileOutputStream os = new FileOutputStream(FILE, false);
            os.write("...(旧存档已截断)...\n".getBytes("UTF-8"));
            os.write(tail);
            os.flush(); os.close();
        } catch (Throwable ignored) {}
    }

    public static synchronized String read() {
        try {
            if (FILE == null || !FILE.exists()) return "";
            return new String(readBytes(FILE), "UTF-8");
        } catch (Throwable t) { return ""; }
    }

    public static synchronized int count() {
        try {
            String s = read();
            if (s.isEmpty()) return 0;
            int c = 0;
            for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '\n') c++;
            return c;
        } catch (Throwable t) { return 0; }
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
