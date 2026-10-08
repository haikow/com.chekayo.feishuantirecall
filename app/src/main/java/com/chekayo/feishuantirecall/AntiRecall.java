package com.chekayo.feishuantirecall;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import io.github.libxposed.api.XposedInterface;

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
 *
 * 生命周期: 不再实现 legacy 入口接口, 由唯一 modern 入口 FeishuKitModule 在
 * onPackageReady 调 {@link #install} 完成完整分发(幂等在入口层)。
 */
public class AntiRecall {

    // 支持的目标包: 国内版飞书 + 国际版 Lark。LSPosed 按 scope.list 注入对应进程。
    static final String PKG_FEISHU = "com.ss.android.lark";
    static final String PKG_LARK = "com.larksuite.suite";   // 国际版(Google Play), v7.72.10 真机确认
    // 运行时当前注入的目标包名 (FeishuKitModule 分发 install 时锁定, 进程内唯一)。
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
    static final String MODULE_VERSION = "1.8.9-api102";
    static final int MODULE_VERSION_CODE = 1031;   // 与 AndroidManifest versionCode 同步; 更新检查比对用
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

    /** JNI: FeishuKit 设置面板实时开关防撤回中和. */
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

    /** 供 ModulePath 在 onModuleLoaded 阶段回写模块 APK 路径(legacy 由 initZygote 设置)。 */
    public static void setModulePath(String path) { MODULE_PATH = path; }

    static volatile boolean CONFIG_BRIDGE_INSTALLED = false;

    /** 跨进程配置桥：Application 就绪后 setContext + 注册 ACTION_SYNC（EXPORTED）。 */
    static void installConfigBridge() {
        if (CONFIG_BRIDGE_INSTALLED) return;
        CONFIG_BRIDGE_INSTALLED = true;
        try {
            Object app = Reflect.callStaticMethod(Class.forName("android.app.AndroidAppHelper"), "currentApplication");
            if (app instanceof android.content.Context) {
                bindConfigBridge((android.content.Context) app);
                return;
            }
        } catch (Throwable ignored) {}
        try {
            HookRuntime.hookMethod(android.app.Instrumentation.class, "callApplicationOnCreate",
                    new Class<?>[]{android.app.Application.class}, "configbridge.callApplicationOnCreate",
                    new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    // legacy after-hook：Application 创建完成后再绑定配置桥
                    Object result = chain.proceed();
                    try {
                        if (chain.getArgs().get(0) instanceof android.content.Context) {
                            bindConfigBridge((android.content.Context) chain.getArgs().get(0));
                        }
                    } catch (Throwable ignored) {}
                    return result;
                }
            });
        } catch (Throwable t) {
            ModuleLog.log("[fucklark] config bridge defer failed: " + t);
        }
    }

    static void bindConfigBridge(final android.content.Context c) {
        try {
            Config.setContext(c);
            android.content.BroadcastReceiver receiver = new android.content.BroadcastReceiver() {
                @Override public void onReceive(android.content.Context x, android.content.Intent i) {
                    String act = i.getAction();
                    if (Config.ACTION_SYNC.equals(act)) {
                        Config.onSyncReceive(i.getStringExtra("json"));
                        try {
                            nativeSetRecall(Config.antirecall);
                            nativeSetKeepKicked(Config.keepkicked);
                            nativeSetDiag(Config.diaglog);
                            nativeSetLeaveNotify(Config.leavenotify);
                        } catch (Throwable ignored) {}
                    } else if (ArchiveSync.ACTION_PULL.equals(act)) {
                        // 桌面入口请求档案副本 → 推 profiles + 离职名单
                        ArchiveSync.pushAll();
                    }
                }
            };
            android.content.IntentFilter syncFilter = new android.content.IntentFilter();
            syncFilter.addAction(Config.ACTION_SYNC);
            syncFilter.addAction(ArchiveSync.ACTION_PULL);
            // 必须 EXPORTED：发送方是模块桌面进程（另一 UID），NOT_EXPORTED 会收不到
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                c.registerReceiver(receiver, syncFilter, android.content.Context.RECEIVER_EXPORTED);
            } else {
                c.registerReceiver(receiver, syncFilter);
            }
            // 注册成功即登记外部回调（同一接收器承载 ACTION_SYNC + 档案 ACTION_PULL 两个 action）：
            // 无 unregister 生命周期，是 hot reload 门控的拒绝事实之一（06 文档 §3.3）
            HotReloadSafety.markExternalCallback("config-bridge.receiver(sync,pull)");
            ModuleLog.log("[fucklark] config bridge bound pkg=" + c.getPackageName());
            // Context 就绪后再与模块权威源对齐一次（install 分发早期可能 context 还是 null）
            try { Config.loadAndAnnounce(); } catch (Throwable ignored) {}
        } catch (Throwable t) {
            ModuleLog.log("[fucklark] config bridge bind failed: " + t);
        }
    }

    /**
     * 完整功能安装。唯一分发点: FeishuKitModule.onPackageReady(已按国内/国际/白标过滤并
     * 保证幂等)。跨进程配置桥不在此装——它要对任意注入进程生效(legacy 先于包名过滤),
     * 由入口在过滤前调用 {@link #installConfigBridge}。
     */
    public static void install(String packageName, ClassLoader classLoader) {
        if (!isLarkFamily(packageName) && !isLarkApp(classLoader)) return;
        PKG = packageName;   // 锁定当前目标(进程内唯一), 下游 dataDir/getPackageInfo 随之自适应

        // ---- 0) 后台消息存档: hook 通知抓正文(所有飞书进程都装) ----
        // 通知可能由主进程或 :wschannel 进程弹 -> 每个进程各自 hook + 各自读同一份配置(同 UID 私有目录)。
        // 救"后台被撤回"的消息: 撤回后服务器只下发已撤回空壳, 但原文到过设备(弹过通知), 在这里抓下来。
        try {
            File fdir = larkFilesDir();
            Config.setFilesDir(fdir);        // 每进程都设: 让 Config.notifarchive 能从磁盘读到
            AccountPaths.currentPkg = PKG;   // 供 NotifArchive/AccountPaths 无 Context 探测 uid 用
            NotifArchive.setFilesDir(fdir);
            try { Config.loadAndAnnounce(); } catch (Throwable ignored) {}
            installNotifHook();
        } catch (Throwable t) { ModuleLog.log("[fucklark] notif archive init failed: " + t); }

        // ---- 0.5) 去除聊天水印: hook View.setForeground, 丢弃水印包的前景 drawable ----
        try { installWatermarkHook(); } catch (Throwable t) { ModuleLog.log("[fucklark] watermark init failed: " + t); }

        // ---- 0.55) 屏蔽输入框上方「消息速览」AI 总结小提示 ----
        try { AiPeekBlock.install(); } catch (Throwable t) { ModuleLog.log("[fucklark] ai peek init failed: " + t); }

        // ---- 0.6) 解除文件/图片下载限制: 加密聊天禁另存 -> 强制放行(按签名定位, 抗混淆) ----
        try { FileDownloadUnlock.install(classLoader); } catch (Throwable t) { ModuleLog.log("[fucklark] download unlock init failed: " + t); }

        // ---- 0.65) 解除「保密模式」复制/转发限制: RestrictedMode 门禁拦截器 -> 全放行(按签名定位, 抗混淆) ----
        try { RestrictedModeUnlock.install(classLoader); } catch (Throwable t) { ModuleLog.log("[fucklark] restricted-mode unlock init failed: " + t); }

        // ---- 0.7) 主页顶部更新横幅: hook MainActivity.onResume, 有新版时注入横幅 ----
        try { UpdateBanner.install(classLoader); } catch (Throwable t) { ModuleLog.log("[fucklark] update banner init failed: " + t); }

        // ---- 0.8) 下载文件另存到系统「下载」: 主进程监听 Lark/download, 写完即 MediaStore 复制到公共 Download ----
        try {
            if (PKG.equals(currentProcessName())) installDownloadMirror();
        } catch (Throwable t) { ModuleLog.log("[fucklark] download mirror init failed: " + t); }

        // ---- 1) 原生 SQL 层: 仅主进程 ----
        try {
            if (PKG.equals(currentProcessName())) {
                startNative();
            }
        } catch (Throwable t) {
            ModuleLog.log("[antirecall] native start failed: " + t);
        }

        // ---- 1.5) 防对方已读 (v2, Java 层): hook UpdateMessagesMeReadRequest 构造, 清空 message_ids/fold_ids ----
        // 桌面版做法的忠实移植: 已读上报命令 UPDATE_MESSAGES_ME_READ 携带 message_ids, 清空即"标零条已读".
        // 主路径 com.ss.android.lark.im.sdk.service.ImSdkMessageServiceImplV2.readMessageForChannel ->
        //   rustclient jk(UPDATE_MESSAGES_ME_READ, UpdateMessagesMeReadRequest{message_ids,max_position,...}).
        // pb 类名 com.bytedance.lark.pb.im.v1.UpdateMessagesMeReadRequest 稳定未混淆.
        try { installAntiRead2(classLoader); } catch (Throwable t) { alog("antiread2 install failed: " + t); }

        // ---- 2) Java 映射器兜底 (版本自适应) ----
        // 旧版飞书(≤7.70): 映射器 ax2.b.a(Object,int) 存在 -> 沿用【之前的方法】hook 它,
        //   打开聊天时把撤回内容实时顶回 UI + 还原状态(无痕)。
        // 新版飞书(≥7.71): 混淆器把短名 ax2.b 重排成无关类(Glide Headers), 该方法不存在
        //   -> 走【新方式】: 仅依赖 native SQL 层(已在 7.71.8 真机验证撤回原文保留)。
        // findMethodExactIfExists 只探测不抛异常, 据此路由, 不再对新版误报 NoSuchMethodError。
        ClassLoader cl = classLoader;
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
                normal = Reflect.getStaticObjectField(Reflect.findClass(STATUS, cl), "NORMAL");
            } catch (Throwable t) {
                ModuleLog.log("[antirecall] get NORMAL failed: " + t);
            }
            try {
                HookRuntime.findAndHookMethod(MAPPER, cl, "a", new Class<?>[]{Object.class, int.class},
                        "antirecall.mapper", new MapperHook(normal));
                ModuleLog.log("[antirecall] 旧版飞书: Java 映射器 " + MAPPER + ".a 已挂 (legacy mode)");
            } catch (Throwable t) {
                ModuleLog.log("[antirecall] hook " + MAPPER + ".a failed: " + t);
            }
        } else {
            ModuleLog.log("[antirecall] 新版飞书: " + MAPPER + ".a(Object,int) 不存在, 仅走 native SQL 层 (new mode)");
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
            Object app = Reflect.callStaticMethod(Class.forName("android.app.AndroidAppHelper"), "currentApplication");
            android.content.Context ctx = (android.content.Context) app;
            android.content.pm.PackageInfo pi = ctx.getPackageManager().getPackageInfo(PKG, 0);
            return pi.versionName + " (" + pi.versionCode + ")";
        } catch (Throwable t) { return "?"; }
    }

    /** 下载另存监听: 取 Application context 装 DownloadMirror; context 尚未就绪则 hook Instrumentation.callApplicationOnCreate 兜底。 */
    static void installDownloadMirror() {
        try {
            Object app = Reflect.callStaticMethod(Class.forName("android.app.AndroidAppHelper"), "currentApplication");
            if (app instanceof android.content.Context) {
                DownloadMirror.install((android.content.Context) app);
                return;
            }
        } catch (Throwable ignored) {}
        try {
            HookRuntime.hookMethod(android.app.Instrumentation.class, "callApplicationOnCreate",
                    new Class<?>[]{android.app.Application.class}, "dlmirror.deferAppCreate",
                    new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    // legacy after-hook：Application 就绪后装 DownloadMirror（无内部 try，靠 protective 兜底）
                    Object result = chain.proceed();
                    if (chain.getArgs().get(0) instanceof android.content.Context) {
                        DownloadMirror.install((android.content.Context) chain.getArgs().get(0));
                    }
                    return result;
                }
            });
        } catch (Throwable t) { ModuleLog.log("[fucklark][dl] defer install failed: " + t); }
    }

    /** 当前目标飞书包的 files 目录(国内/国际版 + /data/user/0 兼容)。 */
    static File larkFilesDir() {
        // 首选: App 真实 files 目录(getFilesDir), 任何包名/沙箱/工作资料/虚拟化都对; currentApplication 早期可能为 null -> 回退猜路径。
        try {
            Object app = Reflect.callStaticMethod(Class.forName("android.app.AndroidAppHelper"), "currentApplication");
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
            HookRuntime.hookMethod(android.app.NotificationManager.class, "notify",
                    new Class<?>[]{String.class, int.class, android.app.Notification.class},
                    "notifarchive.notify", new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    // legacy before-hook：通知先抓存档再放行
                    try { NotifArchive.capture((android.app.Notification) chain.getArgs().get(2)); } catch (Throwable ignored) {}
                    // 开关判定放进 capture()(内部节流热读磁盘配置), 以覆盖 :wschannel 进程 Config 内存不同步。
                    return chain.proceed();
                }
            });
            ModuleLog.log("[fucklark] 后台消息存档: NotificationManager.notify 已 hook (进程 " + currentProcessName() + ")");
        } catch (Throwable t) {
            ModuleLog.log("[fucklark] notif hook install failed: " + t);
        }
        // 同时挂 UI 还原: 撤回系统提示渲染时把存档原文拼回去
        installRecallUiRestore();
    }

    /**
     * 后台撤回 UI 策略（三分，互不混用）：
     * 1) 统计存档：由 NotifArchive.capture 写 notif_archive.txt + 还原表（发送人严格匹配）；
     * 2) 聊天还原：仅当【能对上发送人】且存档有该条原文时，把系统提示整段替换为原文；
     * 3) 撤回提示：还原失败/无存档时，提示文案只用 Config.recallHintText（默认「撤回了一条消息」），
     *    绝不把存档里最近一条消息当成提示文案。
     */
    static final java.util.Set<android.widget.TextView> RECALL_UI_BUSY =
            java.util.Collections.synchronizedSet(
                    java.util.Collections.newSetFromMap(new java.util.WeakHashMap<android.widget.TextView, Boolean>()));

    static void installRecallUiRestore() {
        try {
            XposedInterface.Hooker h = new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    // legacy after-hook：原方法先执行，回调异常不影响原返回值
                    Object result = chain.proceed();
                    try {
                        Object[] args = chain.getArgs().toArray();
                        CharSequence src = null;
                        if (args.length > 0 && args[0] instanceof CharSequence) {
                            src = (CharSequence) args[0];
                        }
                        if (src == null) return result;
                        String s = src.toString();
                        if (s == null || s.length() == 0) return result;
                        if (!isRecallSystemText(s)) return result;

                        android.widget.TextView tv = (android.widget.TextView) chain.getThisObject();
                        if (RECALL_UI_BUSY.contains(tv)) return result;
                        try { Config.load(); } catch (Throwable ignored) {}

                        String sender = recallSenderFromUi(s);
                        // 严格匹配：发送人空或对不上 → 不还原，避免用存档里最新一条乱顶
                        String orig = null;
                        if (sender != null && sender.trim().length() > 0) {
                            orig = NotifArchive.findRestore(sender.trim());
                        }

                        RECALL_UI_BUSY.add(tv);
                        try {
                            // ── 路径 A：统计里能对上的存档 → 聊天直接还原为原文 ──
                            if (orig != null && orig.trim().length() > 0 && !orig.trim().equals(s)) {
                                tv.setVisibility(android.view.View.VISIBLE);
                                fixRecallRowHeight(tv);
                                tv.setText(orig.trim());
                                return result;
                            }
                            // ── 路径 B：无存档 → 撤回提示只用配置文案，不用存档内容 ──
                            if (!Config.showRecallHint) {
                                tv.setVisibility(android.view.View.GONE);
                                tv.setHeight(0);
                                return result;
                            }
                            tv.setVisibility(android.view.View.VISIBLE);
                            fixRecallRowHeight(tv);
                            String hint = Config.recallHintText;
                            if (hint == null || hint.trim().isEmpty()) hint = "撤回了一条消息";
                            String name = sender == null ? "" : sender.trim();
                            if (hint.contains("{name}") || hint.contains("{sender}")) {
                                hint = hint.replace("{name}", name).replace("{sender}", name);
                            } else if (!name.isEmpty() && !hint.startsWith(name)) {
                                hint = name + hint;
                            }
                            if (!hint.equals(s)) tv.setText(hint);
                        } finally {
                            RECALL_UI_BUSY.remove(tv);
                        }
                    } catch (Throwable ignored) {}
                    return result;
                }
            };
            try {
                HookRuntime.hookMethod(android.widget.TextView.class, "setText",
                        new Class<?>[]{CharSequence.class, android.widget.TextView.BufferType.class},
                        "recallui.setText.2args", h);
            } catch (Throwable ignored) { }
            try {
                HookRuntime.hookMethod(android.widget.TextView.class, "setText",
                        new Class<?>[]{CharSequence.class}, "recallui.setText.1arg", h);
            } catch (Throwable ignored) { }
            ModuleLog.log("[fucklark] 后台撤回 统计/还原/提示 已 hook (进程 " + currentProcessName() + ")");
        } catch (Throwable t) {
            ModuleLog.log("[fucklark] recall UI restore hook failed: " + t);
        }
    }

    static void fixRecallRowHeight(android.widget.TextView tv) {
        try {
            if (tv.getLayoutParams() != null
                    && tv.getLayoutParams().height != android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                    && tv.getLayoutParams().height > 0) {
                tv.getLayoutParams().height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
            }
        } catch (Throwable ignored) {}
    }

    /** 聊天系统提示文案里识别撤回（收窄匹配，避免普通消息误伤）。 */
    static boolean isRecallSystemText(String s) {
        if (s == null) return false;
        String l = s.toLowerCase(java.util.Locale.US);
        return s.contains("撤回了一条消息")
                || s.contains("撤回了这条消息")
                || s.contains("撤回了此消息")
                || s.contains("消息已撤回")
                || l.contains("recalled a message")
                || l.contains("recalled this message")
                || l.contains("unsent a message");
    }

    /** 从「xxx撤回了一条消息」提取发送人; 认不出返回空串(还原表可兜底取最新)。 */
    static String recallSenderFromUi(String s) {
        if (s == null) return "";
        int i = s.indexOf("撤回了一条消息");
        if (i > 0) return s.substring(0, i).replace("【", "").replace("】", "").trim();
        String l = s.toLowerCase(java.util.Locale.US);
        int j = l.indexOf(" recalled a message");
        if (j < 0) j = l.indexOf(" unsent a message");
        if (j > 0) return s.substring(0, j).trim();
        return "";
    }

    // 去除聊天水印: 飞书对外部联系人聊天把 WatermarkDrawable(com.ss.android.lark.watermark.*)
    // setForeground 到 DecorView 上, 平铺画你自己的名字+手机尾号(防泄密水印)。所有挂载方式(Activity/
    // Dialog/Fragment/FrameLayout/MIUI)都走同一个 View.setForeground, 故拦这一处、前景是水印包的就丢弃。
    // 纯客户端渲染, 去掉不影响对方、不改数据。按包名前缀判定(类名被混淆成单字母, 但包名 watermark 未混淆, 跨版本稳)。
    static void installWatermarkHook() {
        try {
            HookRuntime.hookMethod(android.view.View.class, "setForeground",
                    new Class<?>[]{android.graphics.drawable.Drawable.class}, "dewatermark.setForeground",
                    new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    // legacy before-hook：命中水印 drawable 时置 args[0]=null，原方法继续(proceed 改参)
                    Object[] args = chain.getArgs().toArray();
                    try {
                        Object d = args[0];
                        if (Config.dewatermark && d != null
                                && d.getClass().getName().startsWith("com.ss.android.lark.watermark.")) {
                            args[0] = null;   // 不设水印前景 -> 聊天页/弹窗/文件预览覆盖层全不画
                        }
                    } catch (Throwable ignored) {}
                    return chain.proceed(args);
                }
            });
            ModuleLog.log("[fucklark] 去水印: View.setForeground 已 hook (进程 " + currentProcessName() + ")");
        } catch (Throwable t) {
            ModuleLog.log("[fucklark] watermark hook install failed: " + t);
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
        if (MODULE_PATH == null) throw new IllegalStateException("module path null (ModulePath not applied)");

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
        // native inline hook（sqlite3_step + liblark .text 维护重打）无 unhook/dlclose 生命周期：
        // load 返回即登记为 hot reload 拒绝事实（06 文档 §3.1/§3.2）
        HotReloadSafety.markNativeHook("antirecall.native-inline");
        ModuleLog.log("[antirecall] native lib loaded: " + out.getAbsolutePath());

        // 当前目标包的 files dir → 配置/日志/native 日志都按它走(国内/国际版自适应)。
        // 必须在 Config.load()/Diag.w() 之前, 否则它们用的还是 null 路径。
        File filesDir = new File(dataDir, "files");
        try { Config.setFilesDir(filesDir); Diag.setFilesDir(filesDir); } catch (Throwable t) { ModuleLog.log("[antirecall] setFilesDir err " + t); }
        // native 写退群/被踢日志时也进当前账号子目录
        try {
            AccountPaths.bind(null, PKG);
            File acc = AccountPaths.accountRoot(null, PKG, AccountPaths.currentUid);
            acc.mkdirs();
            // ≤1.8.1 的全局路径旧数据(全员档案/离职名单/消息存档)并入账号目录, 否则被新账号文件遮蔽读不到
            AccountPaths.migrateLegacy(acc);
            nativeSetDataDir(acc.getAbsolutePath());
            // bind 后 uid 已确定: 重指 NotifArchive FILE 到正确账号桶(早期 setFilesDir 可能落在 unknown 桶)
            NotifArchive.setFilesDir(filesDir);
        } catch (Throwable t) {
            try { nativeSetDataDir(filesDir.getAbsolutePath()); } catch (Throwable ignored) {}
        }
        try { nativeSetDataDir(AccountPaths.accountRoot(null, PKG, AccountPaths.currentUid).getAbsolutePath()); } catch (Throwable ignored) {}

        // FeishuKit: 读配置 + 与模块权威源对齐 + 按开关设防撤回/诊断状态
        // 注意: 不能只 load() 本地旧文件——会把刚同步到的配置冲掉
        try {
            Config.loadAndAnnounce();
            nativeSetRecall(Config.antirecall); nativeSetDiag(Config.diaglog);
            nativeSetKeepKicked(Config.keepkicked); nativeSetLeaveNotify(Config.leavenotify);
            ModuleLog.log("[fucklark] antirecall=" + Config.antirecall + " resign=" + Config.resign
                    + " antiread=" + Config.antiread + " diaglog=" + Config.diaglog
                    + " keepkicked=" + Config.keepkicked + " leavenotify=" + Config.leavenotify
                    + " updatedAt=" + Config.updatedAt());
        } catch (Throwable t) { ModuleLog.log("[fucklark] config init err " + t); }

        // 诊断日志: 环境信息(每次冷启动一条, 仅在诊断开关开启时写)。飞书 versionName 在 Installer 线程解析。
        Diag.w("==== 模块启动 v" + MODULE_VERSION
                + " | 进程 " + currentProcessName() + " | abi " + android.os.Build.CPU_ABI + " ====");

        Thread t = new Thread(new Installer(), "antirecall-installer");
        t.setDaemon(true);
        // 安装轮询 + 安装成功后转入 nativeMaintain 常驻维护循环（06 文档 §3.2），无停止信号
        HotReloadSafety.markThread("antirecall.installer-thread");
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
                    Object app = Reflect.callStaticMethod(Class.forName("android.app.AndroidAppHelper"), "currentApplication");
                    ctx = (android.content.Context) app;
                } catch (Throwable ignored) {}
                if (ctx != null) break;
                try { Thread.sleep(250); } catch (InterruptedException e) { break; }
            }
            if (ctx != null) Diag.w("飞书版本 = " + feishuVersion());

            // ★ 签名自校验: 被重打包(重签名)则禁用核心功能, 不装 native hook。
            TAMPER = checkSignature(ctx) ? 1 : 2;
            if (TAMPER == 2) {
                ModuleLog.log("[fucklark] 签名不匹配, 疑似被篡改/重打包 -> 禁用防撤回/防已读");
                Diag.w("⚠️ 签名校验失败: 本模块被篡改/重打包, 已禁用核心功能。请从官方渠道重新下载。");
                return;   // 不进入 native 安装循环 -> 无防撤回
            }

            for (int i = 0; i < 800; i++) {       // ~120s @150ms
                try {
                    if (tryInstall()) {
                        ModuleLog.log("[antirecall] native hook installed (after " + i + " tries)");
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
                    ModuleLog.log("[antirecall] tryInstall error: " + e);
                    return;
                }
                try { Thread.sleep(150); } catch (InterruptedException e) { return; }
            }
            ModuleLog.log("[antirecall] tryInstall gave up (libsqlcipher not seen in time)");
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
        try { nativeLog(m); } catch (Throwable t) { ModuleLog.log("[antiread] " + m); }
    }

    static void installAntiRead(ClassLoader cl) {
        if (ANTIREAD_INSTALLED) return;
        Class<?> sdk = Reflect.findClass(SDK_CLASS, cl);
        InvokeHook h = new InvokeHook();
        // 钩所有 invoke* 重载 (含带 Command 对象的高层方法). native _invoke 的 int 恒为 10000(噪音).
        String[] names = {"invoke", "invokeV2", "invokeOpt", "invokeAsync", "invokeAsyncV2", "invokeAsyncOpt"};
        for (String m : names) {
            try { HookRuntime.hookAllMethods(sdk, m, "antiread.invoke." + m, h); } catch (Throwable t) { alog(m + " hookAll miss: " + t); }
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
    static final java.util.concurrent.ConcurrentHashMap<String, Long> READ_WINDOWS =
            new java.util.concurrent.ConcurrentHashMap<String, Long>();   // 按会话的"刚发送"窗口截止(ms)
    static final long READ_WINDOW_MS = 2500;
    // 安卓已读模型: 浏览时用 message_ids 上报(被本模块抑制), 回复时飞书只推 max_position。
    // 7.70 时代回复窗口内尚有"只带 max_position 的载体"可把暂存 ids 补回去放行; 8.1.12 真机
    // 实测(2026-10-06 phase-7 基线对照): 浏览被抑制后飞书连回复载体也不发了(原生每条回复都发),
    // 积压 ids 永远无车可搭 -> 对方看到你回复但消息仍未读。
    // v2.1(已废弃): 自己构造请求经 Sdk._invokeAsync 直发 —— 8.1.12 该静态 native 门面无 JNI
    //   实现(UnsatisfiedLinkError 真机实测), 且业务层不调用这组入口, 死路。
    // v2.2(已废弃): 只缓存最后一次 Kn 包重放 —— 真机实测浏览上报拆多条内部调用(1/1/2条),
    //   只取最后一条会丢前面包里的 ids(77/88 在前两条包里被覆盖, 最后一条恰带 99, 已读颠倒)。
    // v2.3: 累加合并修复多包拆分丢 ids(真机复现已读颠倒场景并确认修复)。
    // v2.4(现行) 签名特征发现(第2层版本适配): 不维护"版本→混淆名"对照表 ——
    //   锚点用未混淆的 ImSdkMessageServiceImplV2(服务实现类名) + IGetDataCallback(框架回调接口),
    //   Kn 按签名形状发现(void 实例方法, 参数=(同包类型, cb), 唯一命中), 混淆名自动得出;
    //   m 的 ids 访问器不做名字/形状假设, 首次截获时用构造参数里的原始 ids 列表做同引用/
    //   同内容关联定位(唯命中缓存); Kn 截获只缓存三元组不调用 m 的任何混淆访问器 ——
    //   会话 id(a[1])与真实 ids(a[0])在构造参数里有稳定来源。发现失败 KN_READY=false 纯载体降级。
    static final java.util.Map<String, java.util.LinkedHashSet<String>> PENDING_READ =
            new java.util.concurrent.ConcurrentHashMap<String, java.util.LinkedHashSet<String>>();

    /** v2.4 稳定锚点: 服务实现类名(未混淆)与框架回调接口(未混淆); 混淆的 Kn/m 名一律动态发现。 */
    static final String IM_MSG_SERVICE_V2 = "com.ss.android.lark.im.sdk.service.ImSdkMessageServiceImplV2";
    static final String KN_CB_CLASS = "com.larksuite.framework.callback.IGetDataCallback";
    /** 按会话累加的截获 ids(上次回复以来的并集, 上限500); ctx 按会话存重放模板 {impl, m, cb, ids样本}。 */
    /** 审计 P2-1 修复: 单会话重放缓存统一为 KnState —— 认领=整条 map.remove 原子完成, 并发回复
        不会双取旧缓存, 也不会用旧引用误删认领后产生的新缓存。 */
    static final class KnState {
        final Object impl, m, cb, idsSample;   // 重放模板(最新截获包)
        final java.util.LinkedHashSet<String> pendingIds = new java.util.LinkedHashSet<String>(); // 待重放累加(上限500)
        KnState(Object impl, Object m, Object cb, Object idsSample) {
            this.impl = impl; this.m = m; this.cb = cb; this.idsSample = idsSample;
        }
    }
    static final java.util.concurrent.ConcurrentHashMap<String, KnState> KN_STATE =
            new java.util.concurrent.ConcurrentHashMap<String, KnState>();
    /** 消息 id → 会话 id 映射(浏览落账时记录, 表情回复按 message_id 反查会话触发重放);
        超限整体清空(表情回复的对象几乎总是近期消息)。 */
    static final java.util.concurrent.ConcurrentHashMap<String, String> MSG2CH =
            new java.util.concurrent.ConcurrentHashMap<String, String>();
    /** Kn 入口的线程槽 {impl, m, cb}: 截获暂存, 由同线程紧随其后的读请求构造按会话落账并消费。 */
    static final ThreadLocal<Object[]> CURRENT_KN = new ThreadLocal<Object[]>();
    /** 重放期间置位: 本模块的 readreq ctor hook 与 Kn 截获 hook 原样直通, 防重放请求再被清空/自捕获。 */
    static final ThreadLocal<Boolean> KN_REPLAY = new ThreadLocal<Boolean>();
    static volatile boolean KN_READY = false;
    static volatile java.lang.reflect.Method knMethod;

    /** Kn 重放出口: 生产=反射 Kn.invoke(impl, m, callback); 测试注入 fake 捕获。 */
    interface KnReplayer { void replay(Object impl, Object m, Object cb) throws Throwable; }
    static volatile KnReplayer sReplayer = new DefaultKnReplayer();

    /** 重放调度器: 生产=独立后台线程(重放含 Kn 调用+网络链, 绝不占用发送主线程); 测试注入同步执行。 */
    interface ReplayScheduler { void schedule(Runnable r); }
    static volatile ReplayScheduler sScheduler = new DefaultReplayScheduler();

    static final class DefaultReplayScheduler implements ReplayScheduler {
        @Override public void schedule(Runnable r) { new Thread(r, "feishukit-read-replay").start(); }
    }

    static final class DefaultKnReplayer implements KnReplayer {
        @Override public void replay(Object impl, Object m, Object cb) throws Throwable {
            try { knMethod.invoke(impl, m, cb); }
            catch (Throwable t) { throw new RuntimeException("[补报.invoke] " + describe(t), t); }
        }
    }

    /** 兜底暂存追加合并函数(具名类, 审计 P2 补充): 追加必须在 compute 内会话级原子完成 ——
        map 外修改共享 LinkedHashSet 会与原子 remove 消费交错导致新 ids 永久丢失; 顺手执行 500 上限。
        复审 P2: 接收 Iterable<?> 并逐项 instanceof String 过滤 —— 泛型标注 Iterable<String> 会让
        编译器插入逐元素 checkcast, 单个异类元素抛 CCE 会使整批绕过清空(旧实现的防御性被丢失)。 */
    private static final class PendingMerge implements java.util.function.BiFunction<String, java.util.LinkedHashSet<String>, java.util.LinkedHashSet<String>> {
        final java.lang.Iterable<?> newIds;
        PendingMerge(java.lang.Iterable<?> newIds) { this.newIds = newIds; }
        @Override public java.util.LinkedHashSet<String> apply(String k, java.util.LinkedHashSet<String> prev) {
            java.util.LinkedHashSet<String> set = (prev == null)
                    ? new java.util.LinkedHashSet<String>() : prev;
            for (Object o : newIds) if (o instanceof String) set.add((String) o);
            java.util.Iterator<String> it = set.iterator();
            while (set.size() > 500 && it.hasNext()) { it.next(); it.remove(); }
            return set;
        }
    }

    /** 一次重放任务(后台线程执行): 模板 ids 原地替换为合并全集 → Kn 重放。
        审计 P2-4: 访问器不可用/列表不可变时按最后一次包原样重放, 未补齐的部分必须恢复给兜底/下次重试。 */
    static final class ReplayTask implements Runnable {
        final String ch;
        final KnState st;
        ReplayTask(String ch, KnState st) { this.ch = ch; this.st = st; }
        @Override public void run() {
            KN_REPLAY.set(Boolean.TRUE);
            try {
                if (!resolveIdsAccessor(st.m, st.idsSample)) {
                    alog("重放模板 ids 访问器无法唯一定位, 按最后一次包原样重放");
                }
                boolean mergedIn = false;
                java.util.List sent = null;
                if (knAccIds != null) {
                    try {
                        java.util.List slot = (java.util.List) knAccIds.invoke(st.m);
                        if (slot != null) {
                            sent = new java.util.ArrayList(slot);
                            slot.clear();
                            slot.addAll(st.pendingIds);
                            mergedIn = true;
                        }
                    } catch (Throwable t) {
                        alog("重放模板 ids 列表处理失败(按最后一次包原样重放): " + describe(t));
                    }
                }
                sReplayer.replay(st.impl, st.m, st.cb);
                int c = READ_LOG_COUNT.incrementAndGet();
                if (c <= 200) alog("READ_REQ 已读补报(重放," + (mergedIn ? st.pendingIds.size() : -1) + "条) ch=" + ch);
                if (!mergedIn) {
                    restoreReplayCache(ch, st, st.pendingIds);   // 降级未补齐: 全批保留给兜底/下次重试
                }
            } catch (Throwable t) {
                restoreReplayCache(ch, st, st.pendingIds);
                alog("已读补报失败(缓存恢复,走载体兜底): " + describe(t));
            } finally {
                KN_REPLAY.set(Boolean.FALSE);
            }
        }
    }

    /** 重放失败/降级后的缓存恢复(审计 P2-2): 模板放回(若期间无更新截获); 失败批次 ids **无条件**并入
        最新状态的待重放集合与兜底暂存 —— 有新截获时旧批次也不得丢失。 */
    private static void restoreReplayCache(String ch, KnState st, java.util.LinkedHashSet<String> batch) {
        try {
            KN_STATE.compute(ch, new KnRestoreMerge(st, batch));
            PENDING_READ.compute(ch, new PendingMerge(batch));   // 兜底暂存原子回填(含 500 上限)
        } catch (Throwable ignore) {
        }
    }

    /** 落账合并函数(具名类, -source 8 无 LambdaMetafactory): 模板取最新包, ids 累加携带旧批次。 */
    private static final class KnCaptureMerge implements java.util.function.BiFunction<String, KnState, KnState> {
        final Object[] kn;
        final java.util.List origIds;
        KnCaptureMerge(Object[] kn, java.util.List origIds) { this.kn = kn; this.origIds = origIds; }
        @Override public KnState apply(String k, KnState prev) {
            KnState st = new KnState(kn[0], kn[1], kn[2], origIds);
            if (prev != null) st.pendingIds.addAll(prev.pendingIds);
            for (Object o : origIds) if (o instanceof String) st.pendingIds.add((String) o);
            java.util.Iterator<String> it2 = st.pendingIds.iterator();
            while (st.pendingIds.size() > 500 && it2.hasNext()) { it2.next(); it2.remove(); }
            return st;
        }
    }

    /** 恢复合并函数(具名类): 状态不存在则整体放回, 存在则失败批次并入其待重放集(含 500 上限)。 */
    private static final class KnRestoreMerge implements java.util.function.BiFunction<String, KnState, KnState> {
        final KnState back;
        final java.util.LinkedHashSet<String> batch;
        KnRestoreMerge(KnState back, java.util.LinkedHashSet<String> batch) { this.back = back; this.batch = batch; }
        @Override public KnState apply(String k, KnState cur) {
            if (cur == null) {
                KnState fresh = new KnState(back.impl, back.m, back.cb, back.idsSample);
                fresh.pendingIds.addAll(batch);
                return fresh;
            }
            cur.pendingIds.addAll(batch);   // 已有更新截获: 失败批次并入其待重放集, 绝不丢弃
            java.util.Iterator<String> it = cur.pendingIds.iterator();
            while (cur.pendingIds.size() > 500 && it.hasNext()) { it.next(); it.remove(); }
            return cur;
        }
    }

    /** Kn 入口截获(v2.4 最简形态): 只缓存 {impl, m, cb} 到线程槽; 会话 id 与真实 ids 都在紧随其后的
        读请求构造参数里, 由 ReadReqHook 按会话落账并消费 —— 不调用 m 的任何混淆访问器。
        审计 P2-5: 保存旧槽并在 finally 恢复 —— 候选未构造读请求就返回时, 线程槽不被过期模板污染(兼顾嵌套)。 */
    static class KnCaptureHook implements XposedInterface.Hooker {
        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
            Object[] a = chain.getArgs().toArray();
            if (a.length >= 2 && !Boolean.TRUE.equals(KN_REPLAY.get())) {
                Object[] prev = CURRENT_KN.get();
                CURRENT_KN.set(new Object[]{ chain.getThisObject(), a[0], a.length > 1 ? a[1] : null });
                try {
                    return chain.proceed(a);
                } finally {
                    CURRENT_KN.set(prev);
                }
            }
            return chain.proceed(a);
        }
    }

    /** 在 impl 类按签名形状发现 Kn 候选: void 实例方法, 参数=(同包类型, cb); 返回全部命中(空=零命中)。
        真机 8.1.12 实测多义(同形状方法不止一个) —— 多义时交由运行时栈自学习定夺, 不猜。 */
    static java.util.List<java.lang.reflect.Method> discoverKn(Class<?> impl, Class<?> cb) {
        String pkg = impl.getName().substring(0, impl.getName().lastIndexOf('.'));
        java.util.List<java.lang.reflect.Method> hits = new java.util.ArrayList<java.lang.reflect.Method>();
        for (java.lang.reflect.Method mm : impl.getDeclaredMethods()) {
            if (!void.class.equals(mm.getReturnType())
                    || java.lang.reflect.Modifier.isStatic(mm.getModifiers())) continue;
            Class<?>[] ps = mm.getParameterTypes();
            if (ps.length != 2 || !cb.equals(ps[1])) continue;
            if (ps[0].isPrimitive() || !ps[0].getName().startsWith(pkg + ".")) continue;
            hits.add(mm);
        }
        return hits;
    }

    /** 解析 Kn 重放通道(进会话安装时一次): 稳定锚点 + 签名发现; 全部候选都挂截获 hook(浏览上报
        可能经由多个同形状方法中的任意一条, 全挂才不丢), knLearnPending=true 表示重放方法未定夺 ——
        首次落账时从读请求构造的调用栈上精确认领真身(栈上必然有真实方法帧)。零命中纯载体降级。 */
    static void installKnReplay(ClassLoader cl) {
        try {
            Class<?> impl = Reflect.findClass(IM_MSG_SERVICE_V2, cl);
            Class<?> cbCls = cl.loadClass(KN_CB_CLASS);
            java.util.List<java.lang.reflect.Method> cands = discoverKn(impl, cbCls);
            if (cands.isEmpty()) throw new IllegalStateException("Kn 签名形状零命中");
            for (java.lang.reflect.Method c : cands) {
                HookRuntime.hookMethod(impl, c.getName(), c.getParameterTypes(),
                        "antiread2.kncapture." + c.getName(), new KnCaptureHook());
            }
            if (cands.size() == 1) {
                setKnMethod(cands.get(0));
            } else {
                knCandidates = cands;
                knLearnPending = true;   // 重放方法未定夺; 截获/落账照常工作
                KN_READY = true;         // 截获已全挂 —— 重放方法等栈定夺
                alog("Kn 签名形状多义(" + cands.size() + "个): 截获已全挂, 重放方法待首次读上报按调用栈定夺");
            }
        } catch (Throwable t) {
            KN_READY = false;
            alog("已读补报通道不可用(仅载体兜底): " + describe(t));
        }
    }

    private static void setKnMethod(java.lang.reflect.Method knM) {
        knM.setAccessible(true);
        knMethod = knM;
        knLearnPending = false;
        knCandidates = null;
        KN_READY = true;
        alog("已读补报通道就绪: Kn 重放 (" + knM.getDeclaringClass().getSimpleName() + "#" + knM.getName() + ")");
    }

    static volatile boolean knLearnPending = false;
    static volatile java.util.List<java.lang.reflect.Method> knCandidates;

    /** 从读请求构造的调用栈上认领重放方法: 真实方法帧必然在栈上(类名+方法名对号入座)。
        认领成功收摊; 本栈无候选帧则保留挂起等下一次读上报。 */
    private static void learnKnMethodFromStack() {
        try {
            java.util.List<java.lang.reflect.Method> cands = knCandidates;
            if (cands == null) { knLearnPending = false; return; }
            for (StackTraceElement f : new Throwable().getStackTrace()) {
                for (java.lang.reflect.Method c : cands) {
                    if (c.getName().equals(f.getMethodName())
                            && c.getDeclaringClass().getName().equals(f.getClassName())) {
                        setKnMethod(c);
                        return;
                    }
                }
            }
            // 本栈没有候选帧(异常路径), 保留挂起等下一次读上报
        } catch (Throwable t) {
            knLearnPending = false;
            knCandidates = null;
            alog("Kn 重放方法栈定位失败(截获照常, 重放降级原样包): " + describe(t));
        }
    }

    /** 定位模板 m 的 ids 访问器(审计 P2-3): 扫描与结果发布在同一把锁内原子完成 —— 并发重放线程
        等待初始化结束后取结果, 不存在"已尝试但未发布"的中间态; 定位失败为永久降级(原样重放)。 */
    private static final Object KN_ACC_LOCK = new Object();
    static volatile boolean knAccResolved = false;
    static volatile java.lang.reflect.Method knAccIds;

    private static boolean resolveIdsAccessor(Object mPkt, Object sample) {
        synchronized (KN_ACC_LOCK) {
            if (!knAccResolved) {
                knAccResolved = true;   // 只扫一次; 成败在本锁内原子发布
                if (mPkt != null && sample instanceof java.util.List) {
                    java.util.List sampleList = (java.util.List) sample;
                    java.lang.reflect.Method hit = null;
                    for (java.lang.reflect.Method mm : mPkt.getClass().getMethods()) {
                        if (mm.getParameterTypes().length != 0
                                || java.lang.reflect.Modifier.isStatic(mm.getModifiers())
                                || !java.util.List.class.equals(mm.getReturnType())) continue;
                        try {
                            Object v = mm.invoke(mPkt);
                            if (v == sampleList || sampleList.equals(v)) {
                                if (hit != null) { hit = null; break; }   // 多义 → 保守失败
                                hit = mm;
                            }
                        } catch (Throwable ignore) {
                        }
                    }
                    if (hit != null) {
                        hit.setAccessible(true);
                        knAccIds = hit;
                    }
                }
                if (knAccIds == null) alog("重放模板 ids 访问器无法唯一定位, 重放降级为最后一次包原样发送");
            }
            return knAccIds != null;
        }
    }

    /** 展开调用链到根因, 附栈顶 3 帧 —— Method.invoke 的 ITE 不展开根因等于白记。 */
    static String describe(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        StringBuilder sb = new StringBuilder(String.valueOf(c));
        StackTraceElement[] fs = c.getStackTrace();
        for (int i = 0; i < fs.length && i < 3; i++) sb.append(" @ ").append(fs[i]);
        return sb.toString();
    }

    /** 从回复请求参数定位会话 id: 优先 PB Channel 参数(toString 含 id=), 退而求其次裸数字串。 */
    static String replyChannelId(Object[] ra) {
        if (ra != null) {
            for (Object o : ra)
                if (o != null && "com.bytedance.lark.pb.basic.v1.Channel".equals(o.getClass().getName())) {
                    String c = channelId(o); if (!"?".equals(c)) return c;
                }
            for (Object o : ra) if (o instanceof String && ((String) o).matches("\\d{8,}")) return (String) o;
        }
        return "?";
    }
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
        HookRuntime.hookAllConstructors(reqc, "antiread2.readreq", new ReadReqHook());
        // 发送开窗: 复刻桌面吾乐吧/Linux —— 纯浏览未读, 回复后才把可视消息标已读。
        for (String sc : SEND_REQ_CLASSES) {
            try { HookRuntime.hookAllConstructors(cl.loadClass(sc), "antiread2.sendreq." + sc, new SendReqHook());
                  alog("antiread2: 发送开窗 hook " + sc); }
            catch (Throwable t) { alog("antiread2: 发送类 " + sc + " 不存在: " + t); }
        }
        // 表情回复触发: CreateReactionRequest(message_id, type) —— 贴表情视同回复, 触发该会话重放。
        try {
            HookRuntime.hookAllConstructors(cl.loadClass("com.bytedance.lark.pb.im.v1.CreateReactionRequest"),
                    "antiread2.reaction", new ReactionHook());
            alog("antiread2: 表情回复触发 hook CreateReactionRequest");
        } catch (Throwable t) {
            alog("antiread2: 表情回复类不存在(该版本无贴表情?): " + t);
        }
        installKnReplay(cl);   // 失败只禁用主动补报, 载体兜底不受影响
        ANTIREAD2_INSTALLED = true;
        alog("antiread2 installed: hook " + READ_REQ_CLASS + " ctor (antiread=" + Config.antiread
                + ", 回复才已读" + (KN_READY ? "+回复即Kn重放" : "") + ")");
    }

    // 你发消息时开 2.5s 已读窗口(与已读请求同进程/同层, 时间窗区分"被动浏览 vs 回复"),
    // 并在回复瞬间认领该会话的累加缓存后把重放交给后台线程(v2.4, 见 PENDING_READ 处注释)。
    // CreateQuasiMessageRequest(乐观回显)先于实际发送触发, 认领后 SendMessageRequest 二次触发
    // 查不到缓存自然空转, 保证每回复每会话至多重放一次; 重放绝不阻塞发送主线程(首放预热/
    // 反射/网络链都在后台)。
    static class SendReqHook implements XposedInterface.Hooker {
        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
            String ch = replyChannelId(chain.getArgs().toArray());
            if (chatKnown(ch)) triggerReply(ch);
            return chain.proceed();
        }
    }

    /** 触发源公共入口(复审 P2 修复抽取): 按会话打开载体兜底窗口 + 原子认领重放 ——
        发送回复与表情回复经此完全等价: 即使 Kn 通道未就绪/调度失败/重放失败,
        表情触发的窗口同样能放行 PENDING_READ 载体兜底, 不再只标被贴的那条。 */
    static void triggerReply(String ch) {
        if (!chatKnown(ch)) return;
        long now = System.currentTimeMillis();
        if (Config.antiread && TAMPER != 2) {
            // 审计 P1 修复: 窗口按会话记录 —— 回复 B 只开 B 的窗口, 不再放行 A 的浏览请求
            READ_WINDOWS.put(ch, Long.valueOf(now + READ_WINDOW_MS));
            for (java.util.Map.Entry<String, Long> e : READ_WINDOWS.entrySet()) {
                if (e.getValue().longValue() < now - READ_WINDOW_MS) READ_WINDOWS.remove(e.getKey());
            }
        }
        tryClaimAndReplay(ch);
    }

    static boolean chatKnown(String ch) { return ch != null && !"?".equals(ch); }

    /** 认领并后台重放指定会话的累加缓存(发送回复与表情回复两个触发源共用);
        内含全部门控: 通道就绪/重放方法已定夺/卫兵/开关。 */
    static void tryClaimAndReplay(String ch) {
        if (!KN_READY || knMethod == null || Boolean.TRUE.equals(KN_REPLAY.get())) return;
        try {
            if (!Config.antiread || TAMPER == 2) return;
            // 审计 P2-1 修复: 原子认领 —— 统一会话状态整条 remove, 并发回复不会双取旧缓存,
            // 也不会用旧引用误删认领后产生的新缓存。
            KnState st = KN_STATE.remove(ch);
            if (st != null && !st.pendingIds.isEmpty()) {
                PENDING_READ.remove(ch);   // 重放将覆盖同一批 ids, 兜底缓存一并领走防重复
                try {
                    sScheduler.schedule(new ReplayTask(ch, st));
                } catch (Throwable schedFail) {
                    // 审计 P2-6 修复: 调度失败必须归还被认领的缓存
                    restoreReplayCache(ch, st, st.pendingIds);
                    alog("重放调度失败(缓存已恢复,走载体兜底): " + describe(schedFail));
                }
            } else if (st != null) {
                KN_STATE.putIfAbsent(ch, st);   // 空批次放回(不覆盖更新的截获)
            }
        } catch (Throwable t) {
            alog("已读补报处理异常: " + describe(t));
        }
    }

    /** 表情回复触发源: CreateReactionRequest(message_id, type) 构造即用户贴了表情 ——
        经 MSG2CH 反查会话后走与回复完全相同的 triggerReply(开窗+认领重放)。
        消息 id 反查不到(过期映射)时不触发 —— 该条已由表情 RPC 自行标读, 其余消息维持未读语义。 */
    static class ReactionHook implements XposedInterface.Hooker {
        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
            Object[] a = chain.getArgs().toArray();
            try {
                if (a.length >= 1 && a[0] instanceof String && !Boolean.TRUE.equals(KN_REPLAY.get())) {
                    String ch = MSG2CH.get(a[0]);
                    if (chatKnown(ch)) triggerReply(ch);
                }
            } catch (Throwable ignore) {
            }
            return chain.proceed(a);
        }
    }

    static class ReadReqHook implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            // legacy before-hook 只改参不短路: 先取可变副本, 回调异常也不阻断原构造(与
            // legacy protective 下一致: 已做的修改随 proceed 生效)。模块预处理(改参/日志)
            // 包在吞异常 try 内; 原构造放行固定在 try 末尾之外恰好一次——短参数路径同样
            // 落到这一次 proceed 上, 不得出现「proceed 在 catch 内被吞后再放行一次」
            // (审计 F1: 同一次构造会被执行两遍)。
            Object[] a = chain.getArgs().toArray();
            try {
                // KN_REPLAY 置位 = 本模块正在重放浏览上报, 原样直通(真实 ids 不得再被清空)
                if (a != null && a.length >= 8 && !Boolean.TRUE.equals(KN_REPLAY.get())) {
                    String ch = channelId(a.length > 1 ? a[1] : null);
                    // 审计 P1 修复: 窗口按会话记录 —— 回复 B 开的窗口不再放行 A 的浏览请求
                    boolean inSendWindow = System.currentTimeMillis()
                            < READ_WINDOWS.getOrDefault(ch, Long.valueOf(0L)).longValue();
                    int midsBefore = (a[0] instanceof java.util.List) ? ((java.util.List) a[0]).size() : -1;

                    if (Config.antiread && TAMPER != 2) {
                        if (inSendWindow) {
                            // 回复窗口内(载体兜底路径): 放行, 并把该会话浏览时暂存的 message_ids 补回去
                            //   (安卓回复只带 max_position 不带 ids, 不补则对方仍未读)。
                            //   v2.2 重放已清 ctx 时此合并不了任何 ids, 两路径天然幂等。
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
                                java.util.List origIds = (java.util.List) a[0];   // v2.4: 原始 ids 引用(Kn 重放缓存样本)
                                // 审计 P2 补充: 追加必须在 compute 内会话级原子完成 —— get 后写与原子 remove
                                // 消费交错时, 新 ids 会写进已脱离 map 的旧集合而永久丢失。
                                PENDING_READ.compute(ch, new PendingMerge(origIds));
                                a[0] = new java.util.ArrayList<String>();
                                // v2.4 Kn 落账: 线程槽里的 {impl,m,cb} 按本构造的会话 id 落账并消费。
                                // 会话 id(a[1])与 ids(a[0] 原始引用)都来自构造参数 —— 稳定 pb 类, 无混淆依赖。
                                if (KN_READY && !Boolean.TRUE.equals(KN_REPLAY.get())) {
                                    Object[] kn = CURRENT_KN.get();
                                    if (kn != null) {
                                        // 审计 P2-1: 单会话状态 compute 原子更新 —— 模板取最新包, ids 累加携带旧批次
                                        KN_STATE.compute(ch, new KnCaptureMerge(kn, origIds));
                                        CURRENT_KN.remove();
                                        // 表情回复反查映射: 本批浏览的 message_id → 会话
                                        for (Object o : origIds) {
                                            if (o instanceof String) MSG2CH.put((String) o, ch);
                                        }
                                        if (MSG2CH.size() > 4000) MSG2CH.clear();   // 近期消息才是表情回复对象
                                        if (knLearnPending) learnKnMethodFromStack();   // 栈上认领重放方法(与本落账同调用)
                                        int ck = READ_LOG_COUNT.incrementAndGet();
                                        if (ck <= 200) alog("Kn截获 ch=" + ch + " +" + origIds.size()
                                                + " (累计" + KN_STATE.get(ch).pendingIds.size() + ")");
                                    }
                                }
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
                }
            } catch (Throwable ignore) {
            }
            return chain.proceed(a);
        }
    }

    static boolean isWatched(int cmd) {
        for (int c : READ_WATCH) if (c == cmd) return true;
        return false;
    }

    static class InvokeHook implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            // 模块预处理(读参/诊断/判定是否丢包)包在吞异常 try 内; 原调用放行固定在 try 外
            // 恰好一次(审计 F1: 各提前放行分支的 proceed 曾处于 catch 内, 异常被吞后原调用
            // 会被再执行一次)。唯一短路是 legacy setResult(null) 等价的丢包分支。
            boolean drop = false;
            try {
                Object[] args = chain.getArgs().toArray();
                if (args != null && args.length > 0) {
                    Object a0 = args[0];
                    Integer cmd = null;
                    if (a0 instanceof Integer) {
                        cmd = (Integer) a0;
                    } else if (a0 != null) {
                        // Command 对象 -> getValue(); 解析失败视为非 Command, 跳过处理
                        try { cmd = (Integer) Reflect.callMethod(a0, "getValue"); } catch (Throwable t) { cmd = null; }
                    }
                    if (cmd != null && cmd != 10000) {                        // native 包装哨兵噪音, 忽略
                        // 诊断: 记录命令(限量), 找开聊天触发的读命令
                        int c = INVOKE_LOG_COUNT.incrementAndGet();
                        if (c <= 4000) {
                            alog("invoke cmd=" + cmd
                                    + (isWatched(cmd) ? " *READ*" : "")
                                    + (cmd == CMD_UPDATE_MESSAGES_ME_READ ? " <UPDATE_MESSAGES_ME_READ>" : ""));
                        }
                        if (ANTIREAD_DROP && cmd == CMD_UPDATE_MESSAGES_ME_READ) {
                            alog("DROPPED UPDATE_MESSAGES_ME_READ");
                            drop = true;   // legacy setResult(null) 短路
                        }
                    }
                }
            } catch (Throwable ignore) {
            }
            return drop ? null : chain.proceed();
        }
    }

    static String textOf(Object content) {
        if (content == null) return null;
        try {
            Object s = Reflect.callMethod(content, "getText");
            return s == null ? null : s.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    static class MapperHook implements XposedInterface.Hooker {
        final Object normal;
        MapperHook(Object normal) { this.normal = normal; }

        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            // before 反射改对象, 不短路: 原方法必须执行恰好一次。预处理包在吞异常 try 内,
            // proceed 固定在 try 外一次(审计 F1: 被篡改/空参等提前返回分支的 proceed 曾
            // 处于 catch 内, 异常被吞后原方法会被再执行一次)。
            try {
                if (TAMPER != 2) {   // 被篡改则不还原撤回内容, 仅放行
                    Object mi = chain.getArgs().get(0);
                    if (mi != null) {
                        Object m = Reflect.callMethod(mi, "getMessage");
                        if (m != null) {
                            String id = String.valueOf(Reflect.callMethod(m, "getId"));
                            Object c = Reflect.callMethod(m, "getContent");
                            String t = textOf(c);
                            if (t != null && t.length() > 0) {
                                CACHE.put(id, c);
                            } else if (CACHE.containsKey(id)) {
                                Object cached = CACHE.get(id);
                                Reflect.callMethod(m, "setMessageContent", cached);
                                if (normal != null) {
                                    try { Reflect.callMethod(m, "setStatus", normal); } catch (Throwable ignore) {}
                                }
                            }
                        }
                    }
                }
            } catch (Throwable ignore) {
            }
            return chain.proceed();
        }
    }
}
