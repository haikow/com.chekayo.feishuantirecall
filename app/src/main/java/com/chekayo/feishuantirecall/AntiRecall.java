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
 *    撤回 = REPLACE INTO `messages` 且 is_recalled=1 + content 清空。自己的消息正常撤回，
 *    他人的原文保留并单独记录撤回状态。
 *    仅在【主进程】装载 (其它进程/沙箱进程加载/重定位会崩, 且没有消息库)。
 *    安装动作由 Java 线程轮询 native tryInstall() 完成 (避免 raw pthread 上 PLT 惰性解析崩溃)。
 *
 * 2) Java 消息模型兜底: 首次遇到模型时动态发现内容 getter，按账号/会话/消息缓存原文。
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
    static final String MODULE_VERSION = "1.8.13";
    static final int MODULE_VERSION_CODE = 35;   // 与 AndroidManifest versionCode 同步; 更新检查比对用

    // 签名自校验: 运行 APK 的证书 SHA-256(=SHA256(signature.toByteArray()))。重打包必须重签名 -> 证书变 -> 检测到篡改。
    static final String EXPECTED_SIG = "0cc1410f036279be41e112726687480a92e9f0a3bb5bfae09c9a23c4a764ccfd";
    // 0=未判定, 1=正版, 2=被篡改(重签名)。篡改则禁用核心功能(防撤回/防已读) + 面板告警。
    static volatile int TAMPER = 0;
    // 归档证书解析只做一次；PackageManager 在部分 ROM 上解析模块 APK 很慢或会抛错。
    private static volatile int SIGNATURE_STATE = 0; // 0=未检查，1=可信/读取失败放行，2=证书不匹配

    static volatile String MODULE_PATH = null;
    static volatile boolean NATIVE_STARTED = false;

    /** JNI: 尝试安装一次 sqlite3_step inline hook; true=已装好或库缺符号(停止), false=库未加载(继续重试). */
    public static native boolean tryInstall();

    /** JNI: 周期维护 —— 重打被 lark 反篡改还原的 liblark .text hook (防已读发送拦截). */
    public static native void nativeMaintain();

    /** JNI: 走专属 logcat tag "antiread-j" (避开被其它模块刷爆的 LSPosedFramework). */
    public static native void nativeLog(String s);
    /** JNI: native 撤回诊断与 Java 诊断共用 files/fucklark_log.txt；业务数据仍写账号目录。 */
    public static native void nativeSetLogDir(String dir);

    /** JNI: FeishuKit 设置面板实时开关防撤回中和. */
    public static native void nativeSetRecall(boolean on);
    /** JNI: 0=原生撤回提示，1=保留他人原文并用气泡颜色标识。 */

    /** JNI: UI 从已提交标记快照查询，不在主线程访问 SQLite 连接。 */
    public static native boolean nativeIsRecallMarked(String uid, long chat, long id);
    public static native void nativeBindRecallAccount(String uid, String storage);

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

    static volatile boolean CONFIG_BRIDGE_INSTALLED = false;

    /** 跨进程配置桥：Application 就绪后 setContext + 注册 ACTION_SYNC（EXPORTED）。 */
    static void installConfigBridge() {
        if (CONFIG_BRIDGE_INSTALLED) return;
        CONFIG_BRIDGE_INSTALLED = true;
        try {
            Object app = XposedHelpers.callStaticMethod(Class.forName("android.app.AndroidAppHelper"), "currentApplication");
            if (app instanceof android.content.Context) {
                bindConfigBridge((android.content.Context) app);
                RecallMessages.applicationReady = true;
                RecallMessages.refreshAccount();
                return;
            }
        } catch (Throwable ignored) {}
        try {
            XposedHelpers.findAndHookMethod(android.app.Instrumentation.class, "callApplicationOnCreate",
                    android.app.Application.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        if (p.args[0] instanceof android.content.Context) {
                            bindConfigBridge((android.content.Context) p.args[0]);
                            RecallMessages.applicationReady = true;
                            RecallMessages.refreshAccount();
                        }
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("[fucklark] config bridge defer failed: " + t);
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
                        RecallBubbleUi.schedule();
                        try {
                            nativeSetRecall(Config.antirecall);

                            nativeSetKeepKicked(Config.keepkicked);
                            nativeSetDiag(Config.diaglog);
                            nativeSetLeaveNotify(Config.leavenotify);
                        } catch (Throwable ignored) {}
                    } else if (ArchiveSync.ACTION_REFRESH.equals(act)) {
                        com.chekayo.larkresign.ResignTracker.requestRefresh("桌面刷新请求");
                    } else if (ArchiveSync.ACTION_PULL.equals(act)) {
                        // 桌面入口请求档案副本 → 推 profiles + 离职名单
                        ArchiveSync.pushAll();
                    }
                }
            };
            android.content.IntentFilter syncFilter = new android.content.IntentFilter();
            syncFilter.addAction(Config.ACTION_SYNC);
            syncFilter.addAction(ArchiveSync.ACTION_PULL);
            syncFilter.addAction(ArchiveSync.ACTION_REFRESH);
            // 必须 EXPORTED：发送方是模块桌面进程（另一 UID），NOT_EXPORTED 会收不到
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                c.registerReceiver(receiver, syncFilter, android.content.Context.RECEIVER_EXPORTED);
            } else {
                c.registerReceiver(receiver, syncFilter);
            }
            XposedBridge.log("[fucklark] config bridge bound pkg=" + c.getPackageName());
            // Context 就绪后再与模块权威源对齐一次（handleLoadPackage 早期可能 context 还是 null）
            try { Config.loadAndAnnounce(); Config.startPolling(); } catch (Throwable ignored) {}
        } catch (Throwable t) {
            XposedBridge.log("[fucklark] config bridge bind failed: " + t);
        }
    }

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (!isLarkFamily(lpparam.packageName) && !isLarkApp(lpparam.classLoader)) return;
        PKG = lpparam.packageName;
        installConfigBridge();   // 锁定当前目标(进程内唯一), 下游 dataDir/getPackageInfo 随之自适应

        // ---- 0) 后台消息存档: hook 通知抓正文(所有飞书进程都装) ----
        // 通知可能由主进程或 :wschannel 进程弹 -> 每个进程各自 hook + 各自读同一份配置(同 UID 私有目录)。
        // 救"后台被撤回"的消息: 撤回后服务器只下发已撤回空壳, 但原文到过设备(弹过通知), 在这里抓下来。
        try {
            File fdir = larkFilesDir();
            Diag.setFilesDir(fdir);
            Config.setFilesDir(fdir);        // 每进程都设: 让 Config.notifarchive 能从磁盘读到
            AccountPaths.currentPkg = PKG;   // 供 NotifArchive/AccountPaths 无 Context 探测 uid 用
            NotifArchive.setFilesDir(fdir);
            try { Config.loadAndAnnounce(); } catch (Throwable ignored) {}
            installNotifHook();
        } catch (Throwable t) { XposedBridge.log("[fucklark] notif archive init failed: " + t); }

        // ---- 0.5) 去除聊天水印: hook View.setForeground, 丢弃水印包的前景 drawable ----
        try { installWatermarkHook(); } catch (Throwable t) { XposedBridge.log("[fucklark] watermark init failed: " + t); }

        // ---- 0.55) 屏蔽输入框上方「消息速览」AI 总结小提示 ----
        try { AiPeekBlock.install(); } catch (Throwable t) { XposedBridge.log("[fucklark] ai peek init failed: " + t); }

        try { MessageTimeDisplay.install(lpparam.classLoader); } catch (Throwable t) { XposedBridge.log("[fucklark] message time init failed: " + t); }

        // ---- 0.56) 屏蔽消息中的「相关知识」AI 推荐卡片 ----
        try { RelatedKnowledgeBlock.install(); } catch (Throwable t) { XposedBridge.log("[fucklark] related knowledge init failed: " + t); }

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

        // ---- 1) 防撤回三层独立安装：身份/Java 兜底、native SQL 拦截、原气泡标识 ----
        if (PKG.equals(currentProcessName())) installRecall(lpparam.classLoader);

        // ---- 1.5) 防对方已读 (v2, Java 层): hook UpdateMessagesMeReadRequest 构造, 清空 message_ids/fold_ids ----
        // 桌面版做法的忠实移植: 已读上报命令 UPDATE_MESSAGES_ME_READ 携带 message_ids, 清空即"标零条已读".
        // 主路径 com.ss.android.lark.im.sdk.service.ImSdkMessageServiceImplV2.readMessageForChannel ->
        //   rustclient jk(UPDATE_MESSAGES_ME_READ, UpdateMessagesMeReadRequest{message_ids,max_position,...}).
        // pb 类名 com.bytedance.lark.pb.im.v1.UpdateMessagesMeReadRequest 稳定未混淆.
        try { installAntiRead2(lpparam.classLoader); } catch (Throwable t) { alog("antiread2 install failed: " + t); }
        if (PKG.equals(currentProcessName())) ManualReadAction.install(lpparam.classLoader);

        // 7.x and 8.x share the runtime-discovered getter hook; no version-specific mapper.
        RecallMessages.enableDynamicContentRestore();
    }

    /**
     * 校验当前运行的模块 APK 签名证书是否为作者本人。
     * 直接读 MODULE_PATH 的 APK 归档签名(与包名无关, 重命名也拦得到)。
     * 判定原则: 只有【确认证书不同】才判篡改; 读不到/出错一律放行(fail-open), 不误伤正版。
     */
    static synchronized boolean checkSignature(android.content.Context ctx) {
        if (SIGNATURE_STATE == 1) return true;
        if (SIGNATURE_STATE == 2) return false;
        if (MODULE_PATH == null || ctx == null) return true;
        try {
            android.content.pm.PackageManager pm = ctx.getPackageManager();
            android.content.pm.PackageInfo pi = pm.getPackageArchiveInfo(
                    MODULE_PATH, android.content.pm.PackageManager.GET_SIGNATURES);
            if (pi == null || pi.signatures == null || pi.signatures.length == 0) {
                SIGNATURE_STATE = 1;
                return true;
            }
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(pi.signatures[0].toByteArray());
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            boolean trusted = EXPECTED_SIG.equals(sb.toString());
            SIGNATURE_STATE = trusted ? 1 : 2;
            return trusted;
        } catch (Throwable t) {
            SIGNATURE_STATE = 1;
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
        return ProfilePaths.filesDir(PKG);
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
        // 同时挂 UI 还原: 撤回系统提示渲染时把存档原文拼回去
        installRecallUiRestore();
    }

    /**
     * 后台撤回 UI 策略（三分，互不混用）：
     * 撤回提示只负责文案展示；原文保留由账号、会话和消息 ID 精确匹配。
     * 通知存档只用于统计，不能按发送人替换历史消息，防止多条消息串文。
     */
    static final java.util.Set<android.widget.TextView> RECALL_UI_BUSY =
            java.util.Collections.synchronizedSet(
                    java.util.Collections.newSetFromMap(new java.util.WeakHashMap<android.widget.TextView, Boolean>()));

    static void installRecallUiRestore() {
        try {
            XC_MethodHook h = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        CharSequence src = null;
                        if (p.args != null && p.args.length > 0 && p.args[0] instanceof CharSequence) {
                            src = (CharSequence) p.args[0];
                        }
                        if (src == null) return;
                        String s = src.toString();
                        if (s == null || s.length() == 0) return;
                        if (!Config.antirecall || Config.recallDisplayMode != Config.RECALL_MODE_HINT
                                || !isRecallSystemText(s) || isOwnRecallNotice(s)) return;

                        android.widget.TextView tv = (android.widget.TextView) p.thisObject;
                        if (RECALL_UI_BUSY.contains(tv)) return;
                        try { Config.load(); } catch (Throwable ignored) {}

                        String sender = recallSenderFromUi(s);
                        RECALL_UI_BUSY.add(tv);
                        try {
                            // 提示模式只显示提示，不用按发送人索引的旧通知内容覆盖历史撤回。
                            if (!Config.showRecallHint) {
                                tv.setVisibility(android.view.View.GONE);
                                tv.setHeight(0);
                                return;
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
                }
            };
            try {
                XposedHelpers.findAndHookMethod(android.widget.TextView.class, "setText",
                        CharSequence.class, android.widget.TextView.BufferType.class, h);
            } catch (Throwable ignored) { }
            try {
                XposedHelpers.findAndHookMethod(android.widget.TextView.class, "setText",
                        CharSequence.class, h);
            } catch (Throwable ignored) { }
            XposedBridge.log("[fucklark] 后台撤回 统计/还原/提示 已 hook (进程 " + currentProcessName() + ")");
        } catch (Throwable t) {
            XposedBridge.log("[fucklark] recall UI restore hook failed: " + t);
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

    static boolean isOwnRecallNotice(String text) {
        if (text == null) return false;
        String s = text.trim().toLowerCase(java.util.Locale.US);
        return s.startsWith("你撤回") || s.startsWith("您撤回")
                || s.startsWith("you recalled ") || s.startsWith("you unsent ");
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

    static void installRecall(ClassLoader cl) {
        try { RecallMessages.install(cl); }
        catch (Throwable t) { Diag.once("[fucklark] recall message hook unavailable: " + t); XposedBridge.log("[fucklark] recall message hook unavailable: " + t); }
        try { startNative(); }
        catch (Throwable t) { Diag.once("[antirecall] native start failed: " + t); XposedBridge.log("[antirecall] native start failed: " + t); }
        try { RecallBubbleUi.install(cl); }
        catch (Throwable t) { Diag.once("[fucklark] recall bubble hook unavailable: " + t); XposedBridge.log("[fucklark] recall bubble hook unavailable: " + t); }
        RecallActionGuard.install(cl);
        try { RecallTranslationUi.install(cl); }
        catch (Throwable t) { Diag.once("[fucklark] recall translation hook unavailable: " + t); XposedBridge.log("[fucklark] recall translation hook unavailable: " + t); }
    }

    static synchronized void startNative() throws Exception {
        if (NATIVE_STARTED) return;
        if (MODULE_PATH == null) throw new IllegalStateException("module path null (initZygote not called)");

        File dataDir = ProfilePaths.dataDir(PKG);
        File outDir = new File(dataDir, "antirecall");
        outDir.mkdirs();
        ZipFile zf = new ZipFile(MODULE_PATH);
        ZipEntry library = zf.getEntry("lib/arm64-v8a/libantirecall.so");
        if (library == null) { zf.close(); throw new IllegalStateException("native library missing"); }
        // CRC 随库内容变化，即使版本号相同也不会继续加载旧库文件。
        File out = new File(outDir, "libantirecall-" + Long.toHexString(library.getCrc()) + ".so");
        try {
            ZipEntry e = zf.getEntry("lib/arm64-v8a/libantirecall.so");
            if (e == null) throw new IllegalStateException("lib/arm64-v8a/libantirecall.so not in module apk");
            InputStream is = zf.getInputStream(e);
            File tmp = new File(outDir, "libantirecall.so.tmp");
            FileOutputStream os = new FileOutputStream(tmp);
            byte[] buf = new byte[65536];
            int r;
            while ((r = is.read(buf)) > 0) os.write(buf, 0, r);
            os.flush();
            os.getFD().sync();
            os.close();
            is.close();
            if (out.exists() && !out.delete()) throw new IllegalStateException("cannot replace old native library");
            if (!tmp.renameTo(out)) throw new IllegalStateException("cannot publish native library");
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
        try {
            Config.setFilesDir(filesDir);
            Diag.setFilesDir(filesDir);
            nativeSetLogDir(filesDir.getAbsolutePath());
        } catch (Throwable t) { XposedBridge.log("[antirecall] setFilesDir err " + t); }
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
            nativeSetRecall(Config.antirecall);
            nativeSetDiag(Config.diaglog);
            nativeSetKeepKicked(Config.keepkicked); nativeSetLeaveNotify(Config.leavenotify);
            XposedBridge.log("[fucklark] antirecall=" + Config.antirecall + " resign=" + Config.resign
                    + " antiread=" + Config.antiread + " diaglog=" + Config.diaglog
                    + " keepkicked=" + Config.keepkicked + " leavenotify=" + Config.leavenotify
                    + " updatedAt=" + Config.updatedAt());
        } catch (Throwable t) { XposedBridge.log("[fucklark] config init err " + t); }

        // 诊断日志: 环境信息(每次冷启动一条, 仅在诊断开关开启时写)。飞书 versionName 在 Installer 线程解析。
        Diag.w("==== 模块启动 v" + MODULE_VERSION
                + " | 进程 " + currentProcessName() + " | abi " + android.os.Build.CPU_ABI + " ====");
        Diag.w("动态锚点策略: 消息模型/菜单/已读请求按签名与类型发现；协议类名仅作候选锚点；歧义时降级");

        RecallMessages.restoreAccounts();

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
                    RecallMessages.refreshAccount();
                    if (tryInstall()) {
                        XposedBridge.log("[antirecall] SQLite hook 安装成功：第 " + (i + 1) + " 次尝试，重试 " + i + " 次");
                        Diag.w("防撤回进度 1/3：SQLite 拦截钩子已安装；正在观察消息库，尚未证明已命中撤回写入");
                        // 安装成功后转入维护循环: 周期重打被反篡改还原的 liblark hook + 轮询退群提醒.
                        android.os.Handler mh = new android.os.Handler(android.os.Looper.getMainLooper());
                        while (true) {
                            try { Thread.sleep(150); } catch (InterruptedException e) { return; }
                            try { nativeMaintain(); } catch (Throwable e) { /* ignore */ }
                            try { RecallMessages.refreshAccount(); } catch (Throwable ignored) {}
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
            XposedBridge.log("[antirecall] tryInstall gave up (no supported SQLite provider seen in time)");
            Diag.w("防撤回失败：120 秒内未发现可用 SQLite provider（libwcdb2/libsqlcipher），请检查飞书版本与模块作用域");
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
    static final java.util.Map<String, Long> READ_WINDOWS =
            new java.util.concurrent.ConcurrentHashMap<String, Long>();   // 按会话的"刚发送"窗口截止(ms)
    static final long READ_WINDOW_MS = 2500;
    // 安卓已读模型: 浏览时用 message_ids 上报(被抑制), 回复时飞书只推 max_position 不带 ids。
    // 故按会话暂存"浏览时抑制掉的 message_ids", 回复窗口内的读请求把它们补回去一起放行。
    //   局限: 若你一直停在页面很久再回复, 飞书可能不再发读请求 -> 无载体回填 -> 仍未读(退出再进即可)。
    // v2.5(legacy 移植, 与 api102 线功能一致): 8.1.12 实测模块抑制浏览后飞书不再发回复载体,
    //   积压 ids 永远无车可搭 -> 对方看到回复但消息仍未读。架构 = Kn 截获重放:
    //   ImSdkMessageServiceImplV2.Kn(m数据包, callback) 是已读上报的真实组装点 —— 浏览时在 Kn 入口
    //   按会话截获 {impl, m, callback}(m 内含真实 ids, 本模块只清空构造产物不动 m 本体), 回复瞬间把
    //   缓存原样重放给 Kn → 走飞书自身 rustclient 管线补报全部已读(报文与原生逐字节同构)。
    //   多版本自适应: Kn 按签名形状发现(不维护混淆名对照表), 多候选全挂截获+栈定夺; 发现失败自动
    //   降级为纯载体模式(7.x 载体本就存在, 老版本行为不变)。
    static final java.util.Map<String, java.util.LinkedHashSet<String>> PENDING_READ =
            new java.util.concurrent.ConcurrentHashMap<String, java.util.LinkedHashSet<String>>();
    static final Object PENDING_LOCK = new Object();
    /** 单会话重放缓存: 模板(最新截获包) + 待重放累加集; 认领=整条 remove 原子。 */
    static final class KnState {
        final Object impl, m, cb, idsSample;
        final java.util.LinkedHashSet<String> pendingIds = new java.util.LinkedHashSet<String>();
        KnState(Object impl, Object m, Object cb, Object idsSample) {
            this.impl = impl; this.m = m; this.cb = cb; this.idsSample = idsSample;
        }
    }
    static final java.util.Map<String, KnState> KN_STATE =
            new java.util.concurrent.ConcurrentHashMap<String, KnState>();
    static final Object KN_STATE_LOCK = new Object();
    /** 消息 id → 会话 id 映射(浏览落账时记录, 表情回复按 message_id 反查会话); 超限整体清空。 */
    static final java.util.Map<String, String> MSG2CH =
            new java.util.concurrent.ConcurrentHashMap<String, String>();
    /** Kn 入口截获的线程槽 {impl, m, cb}: 由同线程紧随其后的读请求构造落账并消费。 */
    static final ThreadLocal<Object[]> CURRENT_KN = new ThreadLocal<Object[]>();
    /** 重放期间置位: ReadReqHook 原样直通(真实 ids 不得再被清空)。 */
    static final ThreadLocal<Boolean> KN_REPLAY = new ThreadLocal<Boolean>();
    static volatile boolean KN_READY = false;
    static volatile java.lang.reflect.Method knMethod;
    static volatile boolean knLearnPending = false;
    static volatile java.util.List<java.lang.reflect.Method> knCandidates;
    static volatile Class<?> knImplClass;
    static final Object KN_ACC_LOCK = new Object();
    static volatile boolean knAccResolved = false;
    static volatile java.lang.reflect.Method knAccIds;
    static final Object MSG2CH_LOCK = new Object();
    static final ThreadLocal<Object[]> KN_SLOT_BACKUP = new ThreadLocal<Object[]>();
    /** 稳定锚点(未混淆): 服务实现类名与框架回调接口; 混淆的 Kn/m 名一律动态发现。 */
    static final String IM_MSG_SERVICE_V2 = "com.ss.android.lark.im.sdk.service.ImSdkMessageServiceImplV2";
    static final String KN_CB_CLASS = "com.larksuite.framework.callback.IGetDataCallback";

    /** 在 impl 类按签名形状发现 Kn 候选: void 实例方法, 参数=(同包类型, cb); 返回全部命中(空=零命中)。
        真机 8.1.12 实测多义(同形状方法不止一个) —— 多候选全挂截获, 重放方法由调用栈定夺。 */
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

    /**
     * 按“同包请求对象 + 框架回调 + void 返回”发现已读发送入口。
     * 多候选全部截获，首次真实读请求再用调用栈确认；零候选时只保留构造器兜底。
     */
    static void installKnReplay(ClassLoader cl) {
        try {
            Class<?> impl = cl.loadClass(IM_MSG_SERVICE_V2);
            Class<?> cbCls = cl.loadClass(KN_CB_CLASS);
            java.util.List<java.lang.reflect.Method> cands = discoverKn(impl, cbCls);
            if (cands.isEmpty()) throw new IllegalStateException("Kn 签名形状零命中");
            StringBuilder matched = new StringBuilder();
            for (java.lang.reflect.Method c : cands) {
                XposedBridge.hookMethod(c, new KnCaptureHook());
                if (matched.length() > 0) matched.append(" | ");
                matched.append(c.toGenericString());
            }
            Diag.once("[fucklark] read-send anchors matched: impl=" + impl.getName()
                    + " callback=" + cbCls.getName() + " candidates=" + matched);
            knImplClass = impl;
            knCandidates = cands;
            if (cands.size() == 1) {
                setKnMethod(cands.get(0));
            } else {
                knLearnPending = true;
                KN_READY = true;   // 截获已全挂 —— 重放方法等栈定夺
                alog("Kn 签名形状多义(" + cands.size() + "个): 截获已全挂, 重放方法待首次读上报按调用栈定夺");
            }
        } catch (Throwable t) {
            KN_READY = false;
            alog("Kn 重放通道不可用(仅载体兜底, 老版本行为不变): " + describe(t));
            Diag.once("[fucklark] read-send dynamic anchor unavailable: " + describe(t));
        }
    }

    private static void setKnMethod(java.lang.reflect.Method knM) {
        knM.setAccessible(true);
        knMethod = knM;
        knLearnPending = false;
        knCandidates = null;
        KN_READY = true;
        alog("已读补报通道就绪: Kn 重放 (" + knM.getDeclaringClass().getSimpleName() + "#" + knM.getName() + ")");
        Diag.once("[fucklark] read-send replay selected: " + knM.toGenericString());
    }

    /** 从读请求构造的调用栈上认领重放方法: 真实方法帧必然在栈上(类名+方法名对号入座)。
        认领成功收摊; 本栈无候选帧则保留挂起等下一次读上报。 */
    private static void learnKnMethodFromStack() {
        try {
            Class<?> impl = knImplClass;
            java.util.List<java.lang.reflect.Method> cands = knCandidates;
            if (impl == null || cands == null) { knLearnPending = false; return; }
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
            alog("Kn 重放方法栈定位失败(截获照常, 重放降级): " + describe(t));
        }
    }

    /** Kn 入口截获: before 落线程槽(保存旧槽), after 恢复(不越过调用生命周期污染后续构造)。 */
    static class KnCaptureHook extends XC_MethodHook {
        @Override protected void beforeHookedMethod(MethodHookParam param) {
            if (Boolean.TRUE.equals(KN_REPLAY.get())) return;
            Object[] prev = CURRENT_KN.get();
            Object[] a = param.args;
            CURRENT_KN.set(new Object[]{ param.thisObject,
                    a != null && a.length > 0 ? a[0] : null,
                    a != null && a.length > 1 ? a[1] : null });
            KN_SLOT_BACKUP.set(prev);
        }
        @Override protected void afterHookedMethod(MethodHookParam param) {
            CURRENT_KN.set(KN_SLOT_BACKUP.get());
            KN_SLOT_BACKUP.remove();
        }
    }
    /** 认领并后台重放指定会话的累加缓存(发送回复与表情回复两个触发源共用);
        内含全部门控: 通道就绪/重放方法已定夺/卫兵/开关。 */
    static void tryClaimAndReplay(String ch) {
        if (!chatKnown(ch)) return;
        if (!KN_READY || knMethod == null || Boolean.TRUE.equals(KN_REPLAY.get())) return;
        try {
            if (!Config.antiread || TAMPER == 2) return;
            // 原子认领: 统一会话状态整条 remove —— 并发回复不会双取旧缓存, 也不会误删新缓存。
            KnState st = KN_STATE.remove(ch);
            if (st != null && !st.pendingIds.isEmpty()) {
                PENDING_READ.remove(ch);   // 重放将覆盖同一批 ids, 兜底缓存一并领走防重复
                new Thread(new ReplayTask(ch, st), "feishukit-read-replay").start();
            } else if (st != null) {
                synchronized (KN_STATE_LOCK) { KN_STATE.putIfAbsent(ch, st); }   // 空批次放回
            }
        } catch (Throwable t) {
            alog("已读补报处理异常: " + describe(t));
        }
    }

    /** 触发源公共入口: 按会话打开载体兜底窗口 + 原子认领重放 —— 发送回复与表情回复经此完全等价
        (Kn 通道未就绪/调度失败/重放失败时, 表情触发的窗口同样能放行 PENDING_READ 载体兜底)。 */
    static void triggerReply(String ch) {
        if (!chatKnown(ch)) return;
        long now = System.currentTimeMillis();
        if (Config.antiread && TAMPER != 2) {
            READ_WINDOWS.put(ch, Long.valueOf(now + READ_WINDOW_MS));
            for (java.util.Map.Entry<String, Long> e : READ_WINDOWS.entrySet()) {
                if (e.getValue().longValue() < now - READ_WINDOW_MS) READ_WINDOWS.remove(e.getKey());
            }
        }
        tryClaimAndReplay(ch);
    }

    static boolean chatKnown(String ch) { return ch != null && !"?".equals(ch); }

    /** 表情回复触发源: CreateReactionRequest(message_id, type) 构造即用户贴了表情 ——
        经 MSG2CH 反查会话后走与回复完全相同的 triggerReply(开窗+认领重放)。
        消息 id 反查不到(过期映射)时不触发 —— 该条已由表情 RPC 自行标读, 其余消息维持未读语义。 */
    static class ReactionHook extends XC_MethodHook {
        @Override protected void beforeHookedMethod(MethodHookParam param) {
            try {
                Object[] a = param.args;
                if (a.length >= 1 && a[0] instanceof String && !Boolean.TRUE.equals(KN_REPLAY.get())) {
                    String ch = MSG2CH.get(a[0]);
                    if (chatKnown(ch) && !RecallActionGuard.blockedId((String) a[0], ch)) triggerReply(ch);
                }
            } catch (Throwable ignore) {
            }
        }
    }

    /** 一次重放任务(后台线程): 模板 ids 原地替换为累加全集 → Kn 重放。
        降级(访问器不可用/列表不可变)时按最后一次包原样重放, 未补齐部分恢复给兜底/下次重试。 */
    static final class ReplayTask implements Runnable {
        final String ch;
        final KnState st;
        ReplayTask(String ch, KnState st) { this.ch = ch; this.st = st; }
        @Override public void run() {
            KN_REPLAY.set(Boolean.TRUE);
            try {
                resolveIdsAccessor(st.m, st.idsSample);
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
                knMethod.invoke(st.impl, st.m, st.cb);
                int c = READ_LOG_COUNT.incrementAndGet();
                if (c <= 200) alog("READ_REQ 已读补报(重放," + (mergedIn ? st.pendingIds.size() : -1) + "条) ch=" + ch);
                if (!mergedIn) restoreReplayCache(ch, st, st.pendingIds);   // 降级未补齐: 保留给兜底/下次重试
            } catch (Throwable t) {
                restoreReplayCache(ch, st, st.pendingIds);
                alog("已读补报失败(缓存恢复,走载体兜底): " + describe(t));
            } finally {
                KN_REPLAY.set(Boolean.FALSE);
            }
        }
    }

    /** 重放失败/降级后的缓存恢复: 模板放回(若期间无更新截获); 失败批次 ids 无条件并入
        最新状态的待重放集合与兜底暂存 —— 有新截获时旧批次也不得丢失。 */
    private static void restoreReplayCache(String ch, KnState st, java.util.LinkedHashSet<String> batch) {
        try {
            synchronized (KN_STATE_LOCK) {
                KnState cur = KN_STATE.get(ch);
                if (cur == null) {
                    KnState back = new KnState(st.impl, st.m, st.cb, st.idsSample);
                    back.pendingIds.addAll(batch);
                    trimIds(back.pendingIds);
                    KN_STATE.put(ch, back);
                } else {
                    cur.pendingIds.addAll(batch);
                    trimIds(cur.pendingIds);
                }
            }
            synchronized (PENDING_LOCK) {
                java.util.LinkedHashSet<String> pr = PENDING_READ.get(ch);
                if (pr == null) { pr = new java.util.LinkedHashSet<String>(); PENDING_READ.put(ch, pr); }
                pr.addAll(batch);
                trimIds(pr);
            }
        } catch (Throwable ignore) {
        }
    }

    private static void trimIds(java.util.LinkedHashSet<String> set) {
        java.util.Iterator<String> it = set.iterator();
        while (set.size() > 500 && it.hasNext()) { it.next(); it.remove(); }
    }

    /** 定位模板 m 的 ids 访问器: m 的无参实例 List 方法中, 返回列表与样本同引用或同内容者;
        多义/零命中都算失败(永久降级原样重放)。 */
    private static void resolveIdsAccessor(Object mPkt, Object sample) {
        synchronized (KN_ACC_LOCK) {
            if (knAccResolved) return;
            knAccResolved = true;
            if (mPkt == null || !(sample instanceof java.util.List)) return;
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
            } else {
                alog("重放模板 ids 访问器无法唯一定位, 重放降级为最后一次包原样发送");
            }
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
        XposedBridge.hookAllConstructors(reqc, new ReadReqHook());
        Diag.once("[fucklark] read-request anchor matched: " + reqc.getName()
                + " constructors=" + reqc.getDeclaredConstructors().length);
        // 发送开窗: 复刻桌面吾乐吧/Linux —— 纯浏览未读, 回复后才把可视消息标已读。
        for (String sc : SEND_REQ_CLASSES) {
            try { XposedBridge.hookAllConstructors(cl.loadClass(sc), new SendReqHook());
                  alog("antiread2: 发送开窗 hook " + sc);
                  Diag.once("[fucklark] read-window request matched: " + sc); }
            catch (Throwable t) { alog("antiread2: 发送类 " + sc + " 不存在: " + t); }
        }
        // 表情回复触发: CreateReactionRequest(message_id, type) —— 贴表情视同回复, 触发该会话重放。
        try {
            XposedBridge.hookAllConstructors(cl.loadClass("com.bytedance.lark.pb.im.v1.CreateReactionRequest"),
                    new ReactionHook());
            alog("antiread2: 表情回复触发 hook CreateReactionRequest");
            Diag.once("[fucklark] reaction request anchor matched: com.bytedance.lark.pb.im.v1.CreateReactionRequest");
        } catch (Throwable t) {
            alog("antiread2: 表情回复类不存在(该版本无贴表情?): " + t);
        }
        installKnReplay(cl);   // Kn 截获重放通道; 失败自动降级纯载体模式(老版本行为不变)
        ANTIREAD2_INSTALLED = true;
        alog("antiread2 installed: hook " + READ_REQ_CLASS + " ctor (antiread=" + Config.antiread
                + ", 回复才已读" + (KN_READY ? "+Kn重放" : "") + ")");
    }

    // 你发消息时开 2.5s 已读窗口(与已读请求同进程/同层, 时间窗区分"被动浏览 vs 回复")。
    static class SendReqHook extends XC_MethodHook {
        @Override protected void beforeHookedMethod(MethodHookParam param) {
            String ch = replyChannelId(param.args);
            if (chatKnown(ch)) triggerReply(ch);
        }
    }

    static class ReadReqHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            // KN_REPLAY 置位 = 本模块正在重放浏览上报, 原样直通(真实 ids 不得再被清空)
            if (Boolean.TRUE.equals(KN_REPLAY.get())) return;
            try {
                Object[] a = param.args;
                if (a == null || a.length < 8) return;
                String ch = channelId(a.length > 1 ? a[1] : null);
                // 审计 P1 修复: 窗口按会话记录 —— 回复 B 开的窗口不再放行 A 的浏览请求
                Long windowEnd = READ_WINDOWS.get(ch);
                boolean inSendWindow = windowEnd != null
                        && System.currentTimeMillis() < windowEnd.longValue();
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
                            java.util.List origIds = (java.util.List) a[0];   // 原始 ids 引用(Kn 重放缓存样本)
                            synchronized (PENDING_LOCK) {
                                java.util.LinkedHashSet<String> buf = PENDING_READ.get(ch);
                                if (buf == null) { buf = new java.util.LinkedHashSet<String>(); PENDING_READ.put(ch, buf); }
                                for (Object o : origIds) if (o instanceof String) buf.add((String) o);
                                java.util.Iterator<String> it = buf.iterator();
                                while (buf.size() > 500 && it.hasNext()) { it.next(); it.remove(); }
                            }
                            a[0] = new java.util.ArrayList<String>();
                            // v2.5 Kn 落账: 线程槽里的 {impl,m,cb} 按本构造的会话 id 落账并消费。
                            if (KN_READY && !Boolean.TRUE.equals(KN_REPLAY.get())) {
                                Object[] kn = CURRENT_KN.get();
                                if (kn != null) {
                                    synchronized (KN_STATE_LOCK) {
                                        KnState prev = KN_STATE.get(ch);
                                        KnState st = new KnState(kn[0], kn[1], kn[2], origIds);
                                        if (prev != null) st.pendingIds.addAll(prev.pendingIds);
                                        for (Object o : origIds) if (o instanceof String) st.pendingIds.add((String) o);
                                        java.util.Iterator<String> it2 = st.pendingIds.iterator();
                                        while (st.pendingIds.size() > 500 && it2.hasNext()) { it2.next(); it2.remove(); }
                                        KN_STATE.put(ch, st);
                                    }
                                    synchronized (MSG2CH_LOCK) {
                                        for (Object o : origIds) {
                                            if (o instanceof String) MSG2CH.put((String) o, ch);
                                        }
                                        if (MSG2CH.size() > 4000) MSG2CH.clear();
                                    }
                                    CURRENT_KN.remove();
                                    if (knLearnPending) learnKnMethodFromStack();   // 栈上认领重放方法
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

}
