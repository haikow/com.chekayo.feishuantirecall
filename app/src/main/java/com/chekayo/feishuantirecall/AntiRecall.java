package com.chekayo.feishuantirecall;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * 飞书防撤回 (com.ss.android.lark) —— 双层方案
 *
 * 1) 原生 SQL 层 (libsqlcipher.so, libantirecall.so inline hook sqlite3_step):
 *    撤回 = REPLACE INTO `messages` 且 is_recalled=1 + content 清空。该写入即将执行时把
 *    id+chat_id 改绑成哨兵(1,1), 真正的原始行(收信时已入库)原封不动 -> 未打开聊天也能救。
 *    仅在【主进程】装载 (其它进程/沙箱进程加载/重定位会崩, 且没有消息库)。
 *    安装动作由 Java 线程轮询 native tryInstall() 完成 (避免 raw pthread 上 PLT 惰性解析崩溃)。
 *
 * 2) Java 映射器层 (ax2.b.a): 打开聊天时把被撤回的 Content 顶回 + status 还原, 兜底。
 */
public class AntiRecall implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    // 支持的目标包: 国内版飞书 + 国际版 Lark。LSPosed 按 xposedscope 列表注入对应进程。
    static final String PKG_FEISHU = "com.ss.android.lark";
    static final String PKG_LARK = "com.larksuite.suite";   // 国际版(Google Play), v7.72.10 真机确认
    // 运行时当前注入的目标包名 (handleLoadPackage 入口锁定, 进程内唯一)。
    // dataDir/getPackageInfo/主进程判断都用它, 国内/国际版同代码自适应, 无需为国际版另开分支。
    static volatile String PKG = PKG_FEISHU;
    static boolean isLarkFamily(String pkg) {
        return PKG_FEISHU.equals(pkg) || PKG_LARK.equals(pkg);
    }
    // 适配飞书二次开发/私有化版(白标 app: 应用包名换, 但内核类保留 com.bytedance.lark.*):
    // 探测飞书标志类, 不限包名。LSPosed 勾选目标 app 作用域后, 模块在该进程探测命中即注入。
    // 缓存 g_lark_mark 避免每 hook 重复 loadClass。
    static volatile Integer g_lark_mark;   // null=未测, 1=飞书系, 0=否
    public static boolean isLarkApp(ClassLoader cl) {
        if (g_lark_mark != null) return g_lark_mark == 1;
        String[] marks = {
            "com.bytedance.lark.sdk.Sdk",              // 飞书 Java→rust bridge 入口(稳定未混淆)
            "com.bytedance.lark.pb.basic.v1.Command"    // pb 命令枚举(稳定)
        };
        for (String m : marks) {
            try { cl.loadClass(m); g_lark_mark = 1; return true; } catch (Throwable t) {}
        }
        g_lark_mark = 0;
        return false;
    }
    static final String MODULE_VERSION = "1.7.2";
    static final int MODULE_VERSION_CODE = 20;   // 与 AndroidManifest versionCode 同步; 更新检查比对用
    static final String MAPPER = "ax2.b";

    // 签名自校验: 运行 APK 的证书 SHA-256(=SHA256(signature.toByteArray()))。重打包必须重签名 -> 证书变 -> 检测到篡改。
    static final String EXPECTED_SIG = "0cc1410f036279be41e112726687480a92e9f0a3bb5bfae09c9a23c4a764ccfd";
    // 0=未判定, 1=正版, 2=被篡改(重签名)。篡改则禁用核心功能(防撤回/防已读) + 面板告警。
    static volatile int TAMPER = 0;
    static final String STATUS = "com.ss.android.lark.chat.entity.message.Message$Status";

    static final Map<String, Object> CACHE = new ConcurrentHashMap<String, Object>();

    static volatile String MODULE_PATH = null;
    static volatile boolean NATIVE_STARTED = false;

    /** JNI: 尝试安装一次 sqlite3_step inline hook; true=已装好或库缺符号(停止), false=库未加载(继续重试). */
    public static native boolean tryInstall();

    /** JNI: 周期维护 —— 重打被 lark 反篡改还原的 liblark .text hook (防已读发送拦截). */
    public static native void nativeMaintain();

    /** JNI: 走专属 logcat tag "antiread-j" (避开被其它模块刷爆的 LSPosedFramework). */
    public static native void nativeLog(String s);

    /** JNI: fuck lark 设置面板实时开关防撤回中和. */
    public static native void nativeSetRecall(boolean on);

    /** JNI: 诊断日志开关 (关=native flog 完全不写文件). */
    public static native void nativeSetDiag(boolean on);

    /** JNI: 保留被踢群聊天记录开关 (拦清群 DELETE + 窗口内拦删消息). */
    public static native void nativeSetKeepKicked(boolean on);

    /** JNI: 退群/被移除提醒开关 (检测成员变动系统消息 -> 入队). */
    public static native void nativeSetLeaveNotify(boolean on);

    /** JNI: 取一条待弹的退群/被移除提醒 (无则返回 null). */
    public static native String nativePollLeaveEvent();

    /** JNI: 设当前目标包的 files 目录(国内/国际版自适应), native 据此拼日志/kicked/leave 路径。 */
    public static native void nativeSetDataDir(String dir);

    @Override
    public void initZygote(IXposedHookZygoteInit.StartupParam sp) {
        MODULE_PATH = sp.modulePath;
    }

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (!isLarkFamily(lpparam.packageName) && !isLarkApp(lpparam.classLoader)) return;
        PKG = lpparam.packageName;   // 锁定当前目标(进程内唯一), 下游 dataDir/getPackageInfo 随之自适应

        // ---- 0) 后台消息存档: hook 通知抓正文(所有飞书进程都装) ----
        // 通知可能由主进程或 :wschannel 进程弹 -> 每个进程各自 hook + 各自读同一份配置(同 UID 私有目录)。
        // 救"后台被撤回"的消息: 撤回后服务器只下发已撤回空壳, 但原文到过设备(弹过通知), 在这里抓下来。
        try {
            File fdir = larkFilesDir();
            Config.setFilesDir(fdir);        // 每进程都设: 让 Config.notifarchive 能从磁盘读到
            NotifArchive.setFilesDir(fdir);
            try { Config.load(); } catch (Throwable ignored) {}
            installNotifHook();
        } catch (Throwable t) { XposedBridge.log("[fucklark] notif archive init failed: " + t); }

        // ---- 0.5) 去除聊天水印: hook View.setForeground, 丢弃水印包的前景 drawable ----
        try { installWatermarkHook(); } catch (Throwable t) { XposedBridge.log("[fucklark] watermark init failed: " + t); }

        // ---- 0.6) 解除文件/图片下载限制: 加密聊天禁另存 -> 强制放行(按签名定位, 抗混淆) ----
        try { FileDownloadUnlock.install(lpparam.classLoader); } catch (Throwable t) { XposedBridge.log("[fucklark] download unlock init failed: " + t); }

        // ---- 0.65) 解除「保密模式」复制/转发限制: RestrictedMode 门禁拦截器 -> 全放行(按签名定位, 抗混淆) ----
        try { RestrictedModeUnlock.install(lpparam.classLoader); } catch (Throwable t) { XposedBridge.log("[fucklark] restricted-mode unlock init failed: " + t); }

        // ---- 0.7) 主页顶部更新横幅: hook MainActivity.onResume, 有新版时注入横幅 ----
        try { UpdateBanner.install(lpparam.classLoader); } catch (Throwable t) { XposedBridge.log("[fucklark] update banner init failed: " + t); }

        // ---- 0.8) 下载文件另存到系统「下载」: 主进程监听 Lark/download, 写完即 MediaStore 复制到公共 Download ----
        try {
            if (PKG.equals(currentProcessName())) installDownloadMirror();
        } catch (Throwable t) { XposedBridge.log("[fucklark] download mirror init failed: " + t); }

        // ---- 1) 原生 SQL 层: 仅主进程 ----
        try {
            if (PKG.equals(currentProcessName())) {
                startNative();
            }
        } catch (Throwable t) {
            XposedBridge.log("[antirecall] native start failed: " + t);
        }

        // ---- 1.5) 防对方已读 (v2, Java 层): hook UpdateMessagesMeReadRequest 构造, 清空 message_ids/fold_ids ----
        // 桌面版做法的忠实移植: 已读上报命令 UPDATE_MESSAGES_ME_READ 携带 message_ids, 清空即"标零条已读".
        // 主路径 com.ss.android.lark.im.sdk.service.ImSdkMessageServiceImplV2.readMessageForChannel ->
        //   rustclient jk(UPDATE_MESSAGES_ME_READ, UpdateMessagesMeReadRequest{message_ids,max_position,...}).
        // pb 类名 com.bytedance.lark.pb.im.v1.UpdateMessagesMeReadRequest 稳定未混淆.
        try { installAntiRead2(lpparam.classLoader); } catch (Throwable t) { alog("antiread2 install failed: " + t); }

        // ---- 2) Java 映射器兜底 (版本自适应) ----
        // 旧版飞书(≤7.70): 映射器 ax2.b.a(Object,int) 存在 -> 沿用【之前的方法】hook 它,
        //   打开聊天时把撤回内容实时顶回 UI + 还原状态(无痕)。
        // 新版飞书(≥7.71): 混淆器把短名 ax2.b 重排成无关类(Glide Headers), 该方法不存在
        //   -> 走【新方式】: 仅依赖 native SQL 层(已在 7.71.8 真机验证撤回原文保留)。
        // findMethodExactIfExists 只探测不抛异常, 据此路由, 不再对新版误报 NoSuchMethodError。
        ClassLoader cl = lpparam.classLoader;
        boolean legacyMapper = false;
        try {
            // 标准反射探测: 旧版存在 ax2.b.a(Object,int); 新版该短名被重排, 探测失败.
            Class<?> mc = cl.loadClass(MAPPER);
            mc.getDeclaredMethod("a", Object.class, int.class);
            legacyMapper = true;
        } catch (Throwable t) {
            legacyMapper = false;
        }
        if (legacyMapper) {
            Object normal = null;
            try {
                normal = XposedHelpers.getStaticObjectField(XposedHelpers.findClass(STATUS, cl), "NORMAL");
            } catch (Throwable t) {
                XposedBridge.log("[antirecall] get NORMAL failed: " + t);
            }
            try {
                XposedHelpers.findAndHookMethod(MAPPER, cl, "a", Object.class, int.class, new MapperHook(normal));
                XposedBridge.log("[antirecall] 旧版飞书: Java 映射器 " + MAPPER + ".a 已挂 (legacy mode)");
            } catch (Throwable t) {
                XposedBridge.log("[antirecall] hook " + MAPPER + ".a failed: " + t);
            }
        } else {
            XposedBridge.log("[antirecall] 新版飞书: " + MAPPER + ".a(Object,int) 不存在, 仅走 native SQL 层 (new mode)");
        }
    }

    /**
     * 校验当前运行的模块 APK 签名证书是否为作者本人。
     * 直接读 MODULE_PATH 的 APK 归档签名(与包名无关, 重命名也拦得到)。
     * 判定原则: 只有【确认证书不同】才判篡改; 读不到/出错一律放行(fail-open), 不误伤正版。
     */
    static boolean checkSignature(android.content.Context ctx) {
        try {
            if (MODULE_PATH == null || ctx == null) return true;
            android.content.pm.PackageManager pm = ctx.getPackageManager();
            android.content.pm.PackageInfo pi = pm.getPackageArchiveInfo(
                    MODULE_PATH, android.content.pm.PackageManager.GET_SIGNATURES);
            if (pi == null || pi.signatures == null || pi.signatures.length == 0) return true;
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(pi.signatures[0].toByteArray());
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return EXPECTED_SIG.equals(sb.toString());
        } catch (Throwable t) {
            return true;   // 校验异常放行, 避免个别机型/系统误伤正版
        }
    }

    static String feishuVersion() {
        try {
            Object app = XposedHelpers.callStaticMethod(Class.forName("android.app.AndroidAppHelper"), "currentApplication");
            android.content.Context ctx = (android.content.Context) app;
            android.content.pm.PackageInfo pi = ctx.getPackageManager().getPackageInfo(PKG, 0);
            return pi.versionName + " (" + pi.versionCode + ")";
        } catch (Throwable t) { return "?"; }
    }

    /** 下载另存监听: 取 Application context 装 DownloadMirror; context 尚未就绪则 hook Instrumentation.callApplicationOnCreate 兜底。 */
    static void installDownloadMirror() {
        try {
            Object app = XposedHelpers.callStaticMethod(Class.forName("android.app.AndroidAppHelper"), "currentApplication");
            if (app instanceof android.content.Context) {
                DownloadMirror.install((android.content.Context) app);
                return;
            }
        } catch (Throwable ignored) {}
        try {
            XposedHelpers.findAndHookMethod(android.app.Instrumentation.class, "callApplicationOnCreate",
                    android.app.Application.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.args[0] instanceof android.content.Context) DownloadMirror.install((android.content.Context) p.args[0]);
                }
            });
        } catch (Throwable t) { XposedBridge.log("[fucklark][dl] defer install failed: " + t); }
    }

    /** 当前目标飞书包的 files 目录(国内/国际版 + /data/user/0 兼容)。 */
    static File larkFilesDir() {
        // 首选: App 真实 files 目录(getFilesDir), 任何包名/沙箱/工作资料/虚拟化都对; currentApplication 早期可能为 null -> 回退猜路径。
        try {
            Object app = XposedHelpers.callStaticMethod(Class.forName("android.app.AndroidAppHelper"), "currentApplication");
            if (app instanceof android.content.Context) {
                File f = ((android.content.Context) app).getFilesDir();
                if (f != null) return f;
            }
        } catch (Throwable ignored) {}
        File dataDir = null;
        for (String d : new String[]{"/data/data/" + PKG, "/data/user/0/" + PKG}) {
            File f = new File(d);
            if (f.isDirectory()) { dataDir = f; break; }
        }
        if (dataDir == null) dataDir = new File("/data/data/" + PKG);
        return new File(dataDir, "files");
    }

    /** hook NotificationManager.notify(String,int,Notification): 抓消息通知正文存档(后台防撤回场景 B)。
     *  notify(int,Notification) 内部转调本 3 参重载, 故只 hook 这一处即可覆盖两种调用。 */
    static void installNotifHook() {
        try {
            XposedHelpers.findAndHookMethod(android.app.NotificationManager.class, "notify",
                    String.class, int.class, android.app.Notification.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    // 开关判定放进 capture()(内部节流热读磁盘配置), 以覆盖 :wschannel 进程 Config 内存不同步。
                    try { NotifArchive.capture((android.app.Notification) p.args[2]); } catch (Throwable ignored) {}
                }
            });
            XposedBridge.log("[fucklark] 后台消息存档: NotificationManager.notify 已 hook (进程 " + currentProcessName() + ")");
        } catch (Throwable t) {
            XposedBridge.log("[fucklark] notif hook install failed: " + t);
        }
    }

    // 去除聊天水印: 飞书对外部联系人聊天把 WatermarkDrawable(com.ss.android.lark.watermark.*)
    // setForeground 到 DecorView 上, 平铺画你自己的名字+手机尾号(防泄密水印)。所有挂载方式(Activity/
    // Dialog/Fragment/FrameLayout/MIUI)都走同一个 View.setForeground, 故拦这一处、前景是水印包的就丢弃。
    // 纯客户端渲染, 去掉不影响对方、不改数据。按包名前缀判定(类名被混淆成单字母, 但包名 watermark 未混淆, 跨版本稳)。
    static void installWatermarkHook() {
        try {
            XposedHelpers.findAndHookMethod(android.view.View.class, "setForeground",
                    android.graphics.drawable.Drawable.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        Object d = p.args[0];
                        if (Config.dewatermark && d != null
                                && d.getClass().getName().startsWith("com.ss.android.lark.watermark.")) {
                            p.args[0] = null;   // 不设水印前景 -> 聊天页/弹窗/文件预览覆盖层全不画
                        }
                    } catch (Throwable ignored) {}
                }
            });
            XposedBridge.log("[fucklark] 去水印: View.setForeground 已 hook (进程 " + currentProcessName() + ")");
        } catch (Throwable t) {
            XposedBridge.log("[fucklark] watermark hook install failed: " + t);
        }
    }

    static String currentProcessName() {
        try {
            FileInputStream fis = new FileInputStream("/proc/self/cmdline");
            byte[] buf = new byte[256];
            int n = fis.read(buf);
            fis.close();
            if (n <= 0) return "";
            int end = 0;
            while (end < n && buf[end] != 0) end++;
            return new String(buf, 0, end).trim();
        } catch (Throwable t) {
            return "";
        }
    }

    static synchronized void startNative() throws Exception {
        if (NATIVE_STARTED) return;
        if (MODULE_PATH == null) throw new IllegalStateException("module path null (initZygote not called)");

        File dataDir = null;
        for (String d : new String[]{"/data/data/" + PKG, "/data/user/0/" + PKG}) {
            File f = new File(d);
            if (f.isDirectory()) { dataDir = f; break; }
        }
        if (dataDir == null) dataDir = new File("/data/data/" + PKG);
        File outDir = new File(dataDir, "antirecall");
        outDir.mkdirs();
        File out = new File(outDir, "libantirecall.so");

        ZipFile zf = new ZipFile(MODULE_PATH);
        try {
            ZipEntry e = zf.getEntry("lib/arm64-v8a/libantirecall.so");
            if (e == null) throw new IllegalStateException("lib/arm64-v8a/libantirecall.so not in module apk");
            InputStream is = zf.getInputStream(e);
            FileOutputStream os = new FileOutputStream(out);
            byte[] buf = new byte[65536];
            int r;
            while ((r = is.read(buf)) > 0) os.write(buf, 0, r);
            os.flush();
            os.close();
            is.close();
        } finally {
            zf.close();
        }
        out.setReadable(true, false);
        out.setExecutable(true, false);

        System.load(out.getAbsolutePath());
        NATIVE_STARTED = true;
        XposedBridge.log("[antirecall] native lib loaded: " + out.getAbsolutePath());

        // 当前目标包的 files dir → 配置/日志/native 日志都按它走(国内/国际版自适应)。
        // 必须在 Config.load()/Diag.w() 之前, 否则它们用的还是 null 路径。
        File filesDir = new File(dataDir, "files");
        try { Config.setFilesDir(filesDir); Diag.setFilesDir(filesDir); } catch (Throwable t) { XposedBridge.log("[antirecall] setFilesDir err " + t); }
        try { nativeSetDataDir(filesDir.getAbsolutePath()); } catch (Throwable t) { XposedBridge.log("[antirecall] nativeSetDataDir err " + t); }

        // fuck lark: 读配置 + 按开关设防撤回/诊断状态 (即时生效; 须在写日志前, 否则 diaglog 还是默认值)
        try { Config.load(); nativeSetRecall(Config.antirecall); nativeSetDiag(Config.diaglog); nativeSetKeepKicked(Config.keepkicked); nativeSetLeaveNotify(Config.leavenotify);
              XposedBridge.log("[fucklark] antirecall=" + Config.antirecall + " resign=" + Config.resign + " diaglog=" + Config.diaglog + " keepkicked=" + Config.keepkicked + " leavenotify=" + Config.leavenotify); }
        catch (Throwable t) { XposedBridge.log("[fucklark] config init err " + t); }

        // 诊断日志: 环境信息(每次冷启动一条, 仅在诊断开关开启时写)。飞书 versionName 在 Installer 线程解析。
        Diag.w("==== 模块启动 v" + MODULE_VERSION
                + " | 进程 " + currentProcessName() + " | abi " + android.os.Build.CPU_ABI + " ====");

        Thread t = new Thread(new Installer(), "antirecall-installer");
        t.setDaemon(true);
        t.start();
    }

    // 具名静态类 (d8 对匿名内部类会 NPE)
    static class Installer implements Runnable {
        @Override
        public void run() {
            // 解析飞书版本 + 签名自校验(Application 就绪前 currentApplication()=null, 重试几次).
            android.content.Context ctx = null;
            for (int k = 0; k < 40; k++) {
                try {
                    Object app = XposedHelpers.callStaticMethod(Class.forName("android.app.AndroidAppHelper"), "currentApplication");
                    ctx = (android.content.Context) app;
                } catch (Throwable ignored) {}
                if (ctx != null) break;
                try { Thread.sleep(250); } catch (InterruptedException e) { break; }
            }
            if (ctx != null) Diag.w("飞书版本 = " + feishuVersion());

            // ★ 签名自校验: 被重打包(重签名)则禁用核心功能, 不装 native hook。
            TAMPER = checkSignature(ctx) ? 1 : 2;
            if (TAMPER == 2) {
                XposedBridge.log("[fucklark] 签名不匹配, 疑似被篡改/重打包 -> 禁用防撤回/防已读");
                Diag.w("⚠️ 签名校验失败: 本模块被篡改/重打包, 已禁用核心功能。请从官方渠道重新下载。");
                return;   // 不进入 native 安装循环 -> 无防撤回
            }

            for (int i = 0; i < 800; i++) {       // ~120s @150ms
                try {
                    if (tryInstall()) {
                        XposedBridge.log("[antirecall] native hook installed (after " + i + " tries)");
                        Diag.w("防撤回: sqlite3_step hook 已安装 (第 " + i + " 次尝试命中 libsqlcipher) ✓");
                        // 安装成功后转入维护循环: 周期重打被反篡改还原的 liblark hook + 轮询退群提醒.
                        android.os.Handler mh = new android.os.Handler(android.os.Looper.getMainLooper());
                        while (true) {
                            try { Thread.sleep(150); } catch (InterruptedException e) { return; }
                            try { nativeMaintain(); } catch (Throwable e) { /* ignore */ }
                            // 退群/被移除提醒: 取队列 -> 主线程弹 Toast (ctx = 飞书 Application)
                            if (Config.leavenotify && ctx != null) {
                                try {
                                    String ev = nativePollLeaveEvent();
                                    if (ev != null) mh.post(new ToastRunnable(ctx, ev));
                                } catch (Throwable e) { /* ignore */ }
                            }
                        }
                    }
                } catch (Throwable e) {
                    XposedBridge.log("[antirecall] tryInstall error: " + e);
                    return;
                }
                try { Thread.sleep(150); } catch (InterruptedException e) { return; }
            }
            XposedBridge.log("[antirecall] tryInstall gave up (libsqlcipher not seen in time)");
            Diag.w("防撤回: ✗ 安装失败 —— 120s 内未见 libsqlcipher.so (飞书可能改了加密库/该机型未加载/版本不兼容)");
        }
    }

    // 具名 Runnable (d8 对匿名内部类会 NPE): 主线程弹退群/被移除 Toast。
    static class ToastRunnable implements Runnable {
        final android.content.Context c; final String m;
        ToastRunnable(android.content.Context c, String m) { this.c = c; this.m = m; }
        @Override public void run() {
            try { android.widget.Toast.makeText(c, m, android.widget.Toast.LENGTH_LONG).show(); } catch (Throwable t) { /* ignore */ }
        }
    }

    // ── 防已读 ──────────────────────────────────────────────────────────
    // 飞书所有 Java→rust 调用走 com.bytedance.lark.sdk.Sdk 的 _invoke* (int command, byte[]).
    // command = com.bytedance.lark.pb.basic.v1.Command 枚举值.
    // 已读上报命令: UPDATE_MESSAGES_ME_READ=1021 (对方看到"已读"的元凶).
    static final int CMD_UPDATE_MESSAGES_ME_READ = 1021;
    // 观察名单 (开聊天可能触发的读相关命令)
    static final int[] READ_WATCH = {18 /*ENTER_CHAT*/, 1021 /*UPDATE_MESSAGES_ME_READ*/,
            1067 /*CREATE_CHAT_LAST_READ_POSITION*/, 2224 /*UPDATE_THREADS_ME_READ*/,
            2234 /*UPDATE_DOC_ME_READ*/, 2263 /*UPDATE_CHAT_APPLICATION_ME_READ*/};
    // true=丢弃已读上报(防已读生效); false=仅记录(确认阶段)
    static final boolean ANTIREAD_DROP = false;

    static volatile boolean ANTIREAD_INSTALLED = false;
    static final java.util.concurrent.atomic.AtomicInteger INVOKE_LOG_COUNT = new java.util.concurrent.atomic.AtomicInteger(0);

    static final String SDK_CLASS = "com.bytedance.lark.sdk.Sdk";

    static void alog(String m) {            // 优先专属 tag antiread-j (非主进程 native 未加载时 fallback)
        try { nativeLog(m); } catch (Throwable t) { XposedBridge.log("[antiread] " + m); }
    }

    static void installAntiRead(ClassLoader cl) {
        if (ANTIREAD_INSTALLED) return;
        Class<?> sdk = XposedHelpers.findClass(SDK_CLASS, cl);
        InvokeHook h = new InvokeHook();
        // 钩所有 invoke* 重载 (含带 Command 对象的高层方法). native _invoke 的 int 恒为 10000(噪音).
        String[] names = {"invoke", "invokeV2", "invokeOpt", "invokeAsync", "invokeAsyncV2", "invokeAsyncOpt"};
        for (String m : names) {
            try { XposedBridge.hookAllMethods(sdk, m, h); } catch (Throwable t) { alog(m + " hookAll miss: " + t); }
        }
        ANTIREAD_INSTALLED = true;
        alog("installed hookAll on Sdk.invoke* (drop=" + ANTIREAD_DROP + ")");
    }

    // ── 防对方已读 v2 (Java 层, 清 message_ids) ────────────────────────────
    static final String READ_REQ_CLASS = "com.bytedance.lark.pb.im.v1.UpdateMessagesMeReadRequest";
    // 发送消息 pb 请求(与已读同层, 稳定未混淆): 构造时=你刚发消息 -> 开已读窗口。
    static final String[] SEND_REQ_CLASSES = {
        "com.bytedance.lark.pb.im.v1.SendMessageRequest",       // 实际发送
        "com.bytedance.lark.pb.im.v1.CreateQuasiMessageRequest" // 本地乐观回显(点发送即触发, 更早)
    };
    static volatile long READ_WINDOW = 0;   // "刚发送"窗口截止(ms); 窗口内的已读上报放行 -> 回复才已读
    static final long READ_WINDOW_MS = 2500;
    // 安卓已读模型: 浏览时用 message_ids 上报(被抑制), 回复时飞书只推 max_position 不带 ids。
    // 故按会话暂存"浏览时抑制掉的 message_ids", 回复窗口内的读请求把它们补回去一起放行。
    //   局限: 若你一直停在页面很久再回复, 飞书可能不再发读请求 -> 无载体回填 -> 仍未读(退出再进即可)。
    static final java.util.Map<String, java.util.LinkedHashSet<String>> PENDING_READ =
            new java.util.concurrent.ConcurrentHashMap<String, java.util.LinkedHashSet<String>>();
    static final java.util.regex.Pattern CH_ID = java.util.regex.Pattern.compile("id=(\\d{6,})");
    static String channelId(Object ch) {
        if (ch == null) return "?";
        try { java.util.regex.Matcher m = CH_ID.matcher(String.valueOf(ch)); if (m.find()) return m.group(1); }
        catch (Throwable t) {}
        // 兜底: 反射找一个"长数字串"的 String 字段(即 chat id; Wire pb 字段公开)
        try {
            for (java.lang.reflect.Field f : ch.getClass().getDeclaredFields()) {
                if (f.getType() == String.class) {
                    f.setAccessible(true);
                    Object v = f.get(ch);
                    if (v instanceof String && ((String) v).matches("\\d{8,}")) return (String) v;
                }
            }
        } catch (Throwable t) {}
        return "?";
    }
    static volatile boolean ANTIREAD2_INSTALLED = false;
    static final java.util.concurrent.atomic.AtomicInteger READ_LOG_COUNT = new java.util.concurrent.atomic.AtomicInteger(0);

    static void installAntiRead2(ClassLoader cl) {
        if (ANTIREAD2_INSTALLED) return;
        Class<?> reqc;
        try { reqc = cl.loadClass(READ_REQ_CLASS); }
        catch (Throwable t) { alog("antiread2: " + READ_REQ_CLASS + " 不存在(版本变了?): " + t); return; }
        // 构造函数参数序(Wire build()): (message_ids[0], channel[1], max_position[2], thread_id[3],
        //   _[4], max_position_badge_count[5], _[6], fold_ids[7], [unknownFields[8]]).
        XposedBridge.hookAllConstructors(reqc, new ReadReqHook());
        // 发送开窗: 复刻桌面吾乐吧/Linux —— 纯浏览未读, 回复后才把可视消息标已读。
        for (String sc : SEND_REQ_CLASSES) {
            try { XposedBridge.hookAllConstructors(cl.loadClass(sc), new SendReqHook());
                  alog("antiread2: 发送开窗 hook " + sc); }
            catch (Throwable t) { alog("antiread2: 发送类 " + sc + " 不存在: " + t); }
        }
        ANTIREAD2_INSTALLED = true;
        alog("antiread2 installed: hook " + READ_REQ_CLASS + " ctor (antiread=" + Config.antiread + ", 回复才已读)");
    }

    // 你发消息时开 2.5s 已读窗口(与已读请求同进程/同层, 时间窗区分"被动浏览 vs 回复")。
    static class SendReqHook extends XC_MethodHook {
        @Override protected void beforeHookedMethod(MethodHookParam param) {
            READ_WINDOW = System.currentTimeMillis() + READ_WINDOW_MS;
        }
    }

    static class ReadReqHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                Object[] a = param.args;
                if (a == null || a.length < 8) return;
                boolean inSendWindow = System.currentTimeMillis() < READ_WINDOW;
                String ch = channelId(a.length > 1 ? a[1] : null);
                int midsBefore = (a[0] instanceof java.util.List) ? ((java.util.List) a[0]).size() : -1;

                if (Config.antiread && TAMPER != 2) {
                    if (inSendWindow) {
                        // 回复窗口内: 放行, 并把该会话浏览时暂存的 message_ids 补回去(安卓回复只带
                        //   max_position 不带 ids, 不补则对方仍未读) -> 对方看到你已读可视消息。
                        java.util.LinkedHashSet<String> buf = PENDING_READ.remove(ch);
                        if (a[0] instanceof java.util.List) {
                            java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<String>();
                            if (buf != null) merged.addAll(buf);
                            for (Object o : (java.util.List) a[0]) if (o instanceof String) merged.add((String) o);
                            a[0] = new java.util.ArrayList<String>(merged);
                        }
                    } else {
                        // 纯浏览: 暂存被抑制的 message_ids(按会话, 上限500), 再清空本次上报。
                        if (a[0] instanceof java.util.List && !((java.util.List) a[0]).isEmpty()) {
                            java.util.LinkedHashSet<String> buf = PENDING_READ.get(ch);
                            if (buf == null) { buf = new java.util.LinkedHashSet<String>(); PENDING_READ.put(ch, buf); }
                            for (Object o : (java.util.List) a[0]) if (o instanceof String) buf.add((String) o);
                            java.util.Iterator<String> it = buf.iterator();
                            while (buf.size() > 500 && it.hasNext()) { it.next(); it.remove(); }
                            a[0] = new java.util.ArrayList<String>();
                        }
                        if (a[7] instanceof java.util.List) a[7] = new java.util.ArrayList<Long>();     // fold_ids
                    }
                }

                int c = READ_LOG_COUNT.incrementAndGet();
                if (c <= 200) {
                    int midsAfter = (a[0] instanceof java.util.List) ? ((java.util.List) a[0]).size() : -1;
                    alog("READ_REQ #" + c + " ch=" + ch + " ids:" + midsBefore + "->" + midsAfter
                            + " maxPos=" + a[2] + " sendWin=" + inSendWindow
                            + (Config.antiread ? (inSendWindow ? " 放行(回复,补" + midsAfter + "条)" : " 清空(浏览,暂存)") : " 仅记录"));
                }
            } catch (Throwable ignore) {
            }
        }
    }

    static boolean isWatched(int cmd) {
        for (int c : READ_WATCH) if (c == cmd) return true;
        return false;
    }

    static class InvokeHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                if (param.args == null || param.args.length == 0) return;
                Object a0 = param.args[0];
                int cmd;
                if (a0 instanceof Integer) {
                    cmd = (Integer) a0;
                } else if (a0 != null) {
                    // Command 对象 -> getValue()
                    try { cmd = (Integer) XposedHelpers.callMethod(a0, "getValue"); }
                    catch (Throwable t) { return; }   // 不是 Command, 跳过
                } else return;
                if (cmd == 10000) return;             // native 包装哨兵噪音, 忽略
                // 诊断: 记录命令(限量), 找开聊天触发的读命令
                int c = INVOKE_LOG_COUNT.incrementAndGet();
                if (c <= 4000) {
                    alog("invoke cmd=" + cmd
                            + (isWatched(cmd) ? " *READ*" : "")
                            + (cmd == CMD_UPDATE_MESSAGES_ME_READ ? " <UPDATE_MESSAGES_ME_READ>" : ""));
                }
                if (ANTIREAD_DROP && cmd == CMD_UPDATE_MESSAGES_ME_READ) {
                    param.setResult(null);
                    alog("DROPPED UPDATE_MESSAGES_ME_READ");
                }
            } catch (Throwable ignore) {
            }
        }
    }

    static String textOf(Object content) {
        if (content == null) return null;
        try {
            Object s = XposedHelpers.callMethod(content, "getText");
            return s == null ? null : s.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    static class MapperHook extends XC_MethodHook {
        final Object normal;
        MapperHook(Object normal) { this.normal = normal; }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                if (TAMPER == 2) return;   // 被篡改则不还原撤回内容
                Object mi = param.args[0];
                if (mi == null) return;
                Object m = XposedHelpers.callMethod(mi, "getMessage");
                if (m == null) return;
                String id = String.valueOf(XposedHelpers.callMethod(m, "getId"));
                Object c = XposedHelpers.callMethod(m, "getContent");
                String t = textOf(c);
                if (t != null && t.length() > 0) {
                    CACHE.put(id, c);
                } else if (CACHE.containsKey(id)) {
                    Object cached = CACHE.get(id);
                    XposedHelpers.callMethod(m, "setMessageContent", cached);
                    if (normal != null) {
                        try { XposedHelpers.callMethod(m, "setStatus", normal); } catch (Throwable ignore) {}
                    }
                }
            } catch (Throwable ignore) {
            }
        }
    }
}
