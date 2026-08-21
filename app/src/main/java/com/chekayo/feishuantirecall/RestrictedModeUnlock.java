package com.chekayo.feishuantirecall;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 解除「保密模式」限制 —— 飞书企业「保密模式」(代码里叫 RestrictedMode)会禁止会话内复制/转发/下载/截屏/存表情,
 * 操作时中止并弹「保密模式已开启…」。纯客户端门禁, 放行即解。不改数据、不影响对方、服务端无感。
 *
 * 总闸: 判定工具类(混淆名 com.ss.android.lark.chat.utils.v0)有 5 个 boolean(Chat) 判定:
 *   canCopy / canForward / canDownload / canScreenshot / canSaveImageToSticker
 *   逻辑均为: 白名单 || !chat.getRestrictedModeSetting().getSwitch()(保密开关关) || 该权限==全员 -> true(允许); 否则 false。
 *   调用点如 ChatCopyActionProvider$CopyMessageAction.onClick: if(!canCopy(chat)){ showToast(); return; } 否则复制。
 * 工具类名 v0 逐版本会漂, 故不 hook 它; 改 hook 实体上未混淆的稳定 getter:
 *   com.ss.android.lark.chat.entity.chat.Chat$RestrictedModeSetting.getSwitch() -> 强制 false(保密开关=关)。
 *   一处放行, 5 个判定因 !getSwitch() 短路全部 true = 复制/转发/下载/截屏/存表情全解, 且不再显示保密状态。
 *
 * 兜底: com.ss.android.lark.chat.dlp.MessageRestrictedActionInterceptor (type=RESTRICTED_MESSAGE) 走的是
 *   服务端按消息下发的 getDisabledAction() 表(逐条置灰菜单)。其门禁方法签名 boolean (MessageActionType, ...),
 *   一并强制 false, 覆盖「按消息禁用」这条平行路径。
 *
 * 抗混淆: 类名/包名/参数类型(Chat / MessageActionType)均未混淆, 方法名逐版本漂, 故一律【按签名】定位。
 * 开关 Config.restrictunlock(运行时判)。
 */
public class RestrictedModeUnlock {

    static volatile boolean INSTALLED = false;

    public static void install(ClassLoader cl) {
        if (INSTALLED) return;
        INSTALLED = true;

        // ① 总闸: 会话保密开关 Chat$RestrictedModeSetting.getSwitch() -> 强制 false(=保密模式关)
        //    5 个判定(canCopy/Forward/Download/Screenshot/Sticker)因 !getSwitch() 短路全部放行。
        try {
            Class<?> setting = cl.loadClass("com.ss.android.lark.chat.entity.chat.Chat$RestrictedModeSetting");
            Method m = setting.getDeclaredMethod("getSwitch");
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (Config.restrictunlock) p.setResult(false);   // 保密开关=关 -> 复制/转发/下载等全放行
                }
            });
            XposedBridge.log("[fucklark] 解除保密模式限制: Chat$RestrictedModeSetting.getSwitch 已 hook (进程 "
                    + AntiRecall.currentProcessName() + ")");
        } catch (Throwable t) {
            XposedBridge.log("[fucklark] restricted-mode unlock(getSwitch) install failed: " + t);
        }

        // ② 兜底: 逐消息 disabledAction 门禁拦截器 -> 强制 false(不拦)
        try {
            Class<?> interceptor = cl.loadClass("com.ss.android.lark.chat.dlp.MessageRestrictedActionInterceptor");
            Class<?> actionType = cl.loadClass("com.ss.android.lark.biz.im.extension.message_action.MessageActionType");
            int hooked = 0;
            for (Method m : interceptor.getDeclaredMethods()) {
                if (m.isSynthetic() || m.isBridge()) continue;
                if (m.getReturnType() != boolean.class) continue;
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length == 0 || ps[0] != actionType) continue;   // boolean (MessageActionType, ...)
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (Config.restrictunlock) p.setResult(false);   // 不拦截 -> 菜单项恢复可用、不弹 toast
                    }
                });
                hooked++;
            }
            XposedBridge.log("[fucklark] 解除保密模式限制: MessageRestrictedActionInterceptor 已 hook " + hooked
                    + " 个门禁方法 (进程 " + AntiRecall.currentProcessName() + ")");
        } catch (Throwable t) {
            XposedBridge.log("[fucklark] restricted-mode unlock(interceptor) install failed: " + t);
        }

        // ③ 屏蔽复制审计 -> 真正无痕(仅 7.70 定位):
        //    正常复制会静默上报 CopyActionAuditUtil.a(ctx,type) -> auditDependency.e(chatId,type) 到企业审计后台
        //    (只报「会话+内容类型」, 不含正文)。保密模式复制本应被拦、不上报; 绕过后会触发这条 -> 一并掐掉。
        //    审计工具类运行时名混淆为 com.ss.android.lark.chat.utils.q(7.70), 方法 a(ActionContext, Message$Type)->void。
        //    按签名(void, 2 参, 第 2 参=Message$Type)定位并空转。跟随 Config.restrictunlock 生效。
        try {
            Class<?> audit = cl.loadClass("com.ss.android.lark.chat.utils.q");
            Class<?> msgType = cl.loadClass("com.ss.android.lark.chat.entity.message.Message$Type");
            int hooked = 0;
            for (Method m : audit.getDeclaredMethods()) {
                if (m.isSynthetic() || m.isBridge()) continue;
                if (m.getReturnType() != void.class) continue;
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length != 2 || ps[1] != msgType) continue;   // void a(ActionContext, Message$Type)
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (Config.restrictunlock) p.setResult(null);   // 跳过审计上报
                    }
                });
                hooked++;
            }
            XposedBridge.log("[fucklark] 屏蔽复制审计: CopyActionAuditUtil(q) 已 hook " + hooked
                    + " 个方法 (进程 " + AntiRecall.currentProcessName() + ")");
        } catch (Throwable t) {
            XposedBridge.log("[fucklark] copy-audit suppress install failed: " + t);
        }

        // ④ 截图不上报 -> 让企业「设备审计」的截图检测器永不启动(仅 7.70 定位):
        //    截图审计子系统(modules/biz/ka/screen-audit-api)由 gc6.a(ActivityObserver, 注册在全局 Activity 生命周期)驱动:
        //    onActivityResumed 里 登录 + FG("core.custom_mobile.enable_audit_device") 通过后 detector.start(activity) 起检测。
        //    直接 no-op onActivityResumed -> 检测器永不启动 -> 截图不被检测、更不上报。跟 FLAG_SECURE 无关, 与强制截图模块互补。
        //    gc6.a / onActivityResumed 均为真实运行时名(Java 类无 Kotlin 元数据)。跟随 Config.screenshotnoaudit。
        try {
            XposedHelpers.findAndHookMethod("gc6.a", cl, "onActivityResumed", android.app.Activity.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (Config.screenshotnoaudit) p.setResult(null);   // 不启动截图检测器
                }
            });
            XposedBridge.log("[fucklark] 截图不上报: gc6.a.onActivityResumed 已 hook (进程 " + AntiRecall.currentProcessName() + ")");
        } catch (Throwable t) {
            XposedBridge.log("[fucklark] screenshot-noaudit install failed: " + t);
        }

        // ⑤ 强制截图 -> 在飞书进程内剥离 FLAG_SECURE(窗口防截图)+ SurfaceView.setSecure(安全画面), 让系统允许截图。
        //    只 hook 飞书自己的 Window/SurfaceView 调用, 不动系统进程(区别于 DisableFlagSecure 的全局做法), 只影响飞书。
        //    与「截图不上报」互补: 前者让你截得了, 后者让截图不被检测上报。跟随 Config.forcescreenshot。
        installForceScreenshot();

        // ⑥ 全审计无痕总闸 -> no-op 审计事件入库。所有 audit*Event(复制/下载/存图/截图/录屏/复制号码/拨号/OCR/
        //    链接/小程序…共 20 种)最终都: 构造 Event -> AuditEventStorage.writeData(Event) 入本地审计库 -> 批量上传。
        //    掐掉入库这一步 = 任何审计事件都进不了库、发不出去, 一处覆盖全部(含以后新增), 且在构造之后、不影响操作本身。
        //    AuditEventStorage 运行时名混淆为 com.ss.android.lark.audit.userscope.audit.a; 按签名 void(Event) 定位
        //    (Event=com.ss.android.lark.pb.security_event.Event, pb 未混淆)。仅 7.70 定位。跟随 Config.noauditall。
        try {
            Class<?> storage = cl.loadClass("com.ss.android.lark.audit.userscope.audit.a");
            Class<?> eventCls = cl.loadClass("com.ss.android.lark.pb.security_event.Event");
            int n = 0;
            for (Method m : storage.getDeclaredMethods()) {
                if (m.isSynthetic() || m.isBridge()) continue;
                if (m.getReturnType() != void.class) continue;
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length != 1 || ps[0] != eventCls) continue;   // writeData(Event)
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (Config.noauditall) {
                            p.setResult(null);   // 不入库 -> 不上传
                            if (Config.diaglog) XposedBridge.log("[fucklark] 全审计无痕: 已拦下审计事件入库 writeData");
                        }
                    }
                });
                n++;
            }
            XposedBridge.log("[fucklark] 全审计无痕总闸: AuditEventStorage.writeData 已 hook " + n
                    + " 个 (进程 " + AntiRecall.currentProcessName() + ")");
        } catch (Throwable t) {
            XposedBridge.log("[fucklark] noaudit-all install failed: " + t);
        }

        // ⑦ 双重保险(第二道全量闸): 派发链 AuditService.audit*Event -> wrapper.b.g -> AuditManager.auditSecurityEvent(Event)
        //    -> AuditEventStorage.writeData。中枢 AuditManager.auditSecurityEvent 是所有事件必经、且【类名+方法名全未混淆】,
        //    比 writeData(混淆类)更稳。hook 它 no-op = 每个审核点在入库前再被独立拦一道。跟随 Config.noauditall。
        try {
            Class<?> am = cl.loadClass("com.ss.android.lark.audit.AuditManager");
            int n = XposedBridge.hookAllMethods(am, "auditSecurityEvent", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (Config.noauditall) {
                        p.setResult(null);
                        if (Config.diaglog) XposedBridge.log("[fucklark] 全审计无痕(双保险): 已拦下审计中枢 auditSecurityEvent");
                    }
                }
            }).size();
            XposedBridge.log("[fucklark] 全审计无痕(双保险): AuditManager.auditSecurityEvent 已 hook " + n
                    + " 个 (进程 " + AntiRecall.currentProcessName() + ")");
        } catch (Throwable t) {
            XposedBridge.log("[fucklark] noaudit-all(AuditManager) install failed: " + t);
        }
    }

    static final int FLAG_SECURE = android.view.WindowManager.LayoutParams.FLAG_SECURE;  // 0x2000

    static void installForceScreenshot() {
        // Window.setFlags(flags, mask): 把 flags 里的 FLAG_SECURE 位清掉(mask 保留 -> 等于把该位置 0)
        try {
            XposedHelpers.findAndHookMethod(android.view.Window.class, "setFlags", int.class, int.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (Config.forcescreenshot) p.args[0] = ((Integer) p.args[0]) & ~FLAG_SECURE;
                }
            });
        } catch (Throwable t) { XposedBridge.log("[fucklark] force-screenshot(setFlags) failed: " + t); }
        // Window.addFlags(flags): 同上, 去掉 FLAG_SECURE
        try {
            XposedHelpers.findAndHookMethod(android.view.Window.class, "addFlags", int.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (Config.forcescreenshot) p.args[0] = ((Integer) p.args[0]) & ~FLAG_SECURE;
                }
            });
        } catch (Throwable t) { XposedBridge.log("[fucklark] force-screenshot(addFlags) failed: " + t); }
        // Window.setAttributes(LayoutParams): 直接改 flags 位的路径
        try {
            XposedHelpers.findAndHookMethod(android.view.Window.class, "setAttributes",
                    android.view.WindowManager.LayoutParams.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!Config.forcescreenshot) return;
                    android.view.WindowManager.LayoutParams lp = (android.view.WindowManager.LayoutParams) p.args[0];
                    if (lp != null) lp.flags &= ~FLAG_SECURE;
                }
            });
        } catch (Throwable t) { XposedBridge.log("[fucklark] force-screenshot(setAttributes) failed: " + t); }
        // SurfaceView.setSecure(boolean): 安全画面(视频/部分预览)强制 false
        try {
            XposedHelpers.findAndHookMethod(android.view.SurfaceView.class, "setSecure", boolean.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (Config.forcescreenshot) p.args[0] = false;
                }
            });
        } catch (Throwable t) { XposedBridge.log("[fucklark] force-screenshot(setSecure) failed: " + t); }
        XposedBridge.log("[fucklark] 强制截图: Window.setFlags/addFlags/setAttributes + SurfaceView.setSecure 已 hook (进程 " + AntiRecall.currentProcessName() + ")");
    }
}
