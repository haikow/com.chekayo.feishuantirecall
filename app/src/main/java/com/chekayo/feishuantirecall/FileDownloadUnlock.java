package com.chekayo.feishuantirecall;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 解除文件/图片下载限制 —— 飞书对加密聊天(外部/密聊)禁止另存(CipherManager.e()==1 -> download forbidden)。
 * 两处判定, 判定为"不加密/ALLOW"即解锁。纯客户端、不改数据、不影响对方。
 *
 * 抗混淆: 不写死混淆方法名(逐版本会漂: 文件判定 7.69.6 是 h()、7.71.8 是 f())。改为按【签名】定位:
 *   ① FileOpenUtils 里唯一的 static、无参、返回 boolean 的方法 = return CipherManager.e()==1  -> 强制 false
 *   ② DownloadCheckUtil 里 static、(PhotoItem)->DownloadCheckResult 的方法(doDownloadCheck) -> 强制 ALLOW
 * 类名/包名/枚举常量名(ALLOW)稳定不混淆, 只有方法字母漂, 故按签名找最稳。开关 Config.downloadunlock(运行时判)。
 */
public class FileDownloadUnlock {

    static volatile boolean INSTALLED = false;

    public static void install(ClassLoader cl) {
        if (INSTALLED) return;
        INSTALLED = true;

        // ① 文件下载判定: FileOpenUtils 里唯一的"无参 static boolean" -> return CipherManager.e()==1
        try {
            Class<?> foc = cl.loadClass("com.ss.android.lark.filedetail.impl.open.FileOpenUtils");
            Method m = uniqueStaticNoArgBoolean(foc);
            if (m != null) {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (Config.downloadunlock) p.setResult(false);
                    }
                });
                XposedBridge.log("[fucklark] 解除下载限制: FileOpenUtils." + m.getName() + " 已 hook (进程 " + AntiRecall.currentProcessName() + ")");
            } else {
                XposedBridge.log("[fucklark] 解除下载限制: FileOpenUtils 无唯一无参 boolean 方法, 跳过(版本变动?)");
            }
        } catch (Throwable t) {
            XposedBridge.log("[fucklark] download unlock(FileOpenUtils) install failed: " + t);
        }

        // ② 图片下载判定: DownloadCheckUtil 里 (PhotoItem)->DownloadCheckResult 的静态方法 -> 强制 ALLOW
        try {
            Class<?> dc = cl.loadClass("com.ss.android.lark.widget.photo.preview.utils.DownloadCheckUtil");
            Class<?> res = cl.loadClass("com.ss.android.lark.widget.photo.preview.utils.DownloadCheckUtil$DownloadCheckResult");
            Class<?> photo = cl.loadClass("com.ss.android.lark.widget.photopicker.entity.PhotoItem");
            final Object allow = XposedHelpers.getStaticObjectField(res, "ALLOW");
            Method m = staticMethodBySig(dc, res, photo);
            if (m != null) {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (Config.downloadunlock) p.setResult(allow);
                    }
                });
                XposedBridge.log("[fucklark] 解除下载限制: DownloadCheckUtil." + m.getName() + " 已 hook (进程 " + AntiRecall.currentProcessName() + ")");
            } else {
                XposedBridge.log("[fucklark] 解除下载限制: DownloadCheckUtil 无 (PhotoItem)->Result 方法, 跳过");
            }
        } catch (Throwable t) {
            XposedBridge.log("[fucklark] download unlock(DownloadCheckUtil) install failed: " + t);
        }

        // ③ 屏蔽下载/预览/存云盘/存图审计 -> 下载无痕(跟随 Config.downloadunlock)。
        installDownloadAuditSuppress(cl);
    }

    static final java.util.Set<String> auditImplHooked =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    /**
     * 屏蔽文件/图片操作审计 —— 打开/预览/下载/存云盘会调 IAuditDependency 的 (String,String,String,String)->void 上报
     * (报 会话+文件名+mime+key 到企业审计后台); 存图走 auditImageDownload。全部空转 = 下载无痕。授权校验(带 Context/回调
     * 的方法)不碰, 否则会拦下载。仅 7.70 定位: 类名 FileDetailModuleDependency/PhotoPickerModuleDependencyImpl 未混淆,
     * 审计实现类在运行时发现(hook getAuditDependency 拿返回对象的类), 只按 4×String 签名空转。
     */
    static void installDownloadAuditSuppress(ClassLoader cl) {
        // 文件: hook FileDetailModuleDependency.getAuditDependency() -> 发现实现类 -> 空转其 (String×4)->void 方法
        try {
            Class<?> dep = cl.loadClass("com.ss.android.lark.filedetail.FileDetailModuleDependency");
            XposedBridge.hookAllMethods(dep, "getAuditDependency", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Object impl = p.getResult();
                    if (impl == null) return;
                    Class<?> c = impl.getClass();
                    if (!auditImplHooked.add(c.getName())) return;   // 每个实现类只挂一次
                    int n = 0;
                    for (Method m : c.getDeclaredMethods()) {
                        if (m.isSynthetic() || m.isBridge()) continue;
                        if (m.getReturnType() != void.class) continue;
                        Class<?>[] ps = m.getParameterTypes();
                        if (ps.length != 4) continue;
                        if (ps[0] != String.class || ps[1] != String.class || ps[2] != String.class || ps[3] != String.class) continue;
                        final String mn = m.getName();
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override protected void beforeHookedMethod(MethodHookParam q) {
                                if (Config.downloadunlock) {
                                    q.setResult(null);   // 跳过审计上报
                                    if (Config.diaglog) XposedBridge.log("[fucklark] 已拦下文件审计上报: " + mn
                                            + "(" + java.util.Arrays.toString(q.args) + ")");
                                }
                            }
                        });
                        n++;
                    }
                    XposedBridge.log("[fucklark] 屏蔽下载审计: " + c.getName() + " 空转 " + n + " 个上报方法");
                }
            });
            XposedBridge.log("[fucklark] 屏蔽下载审计: 已挂 FileDetailModuleDependency.getAuditDependency (进程 " + AntiRecall.currentProcessName() + ")");
        } catch (Throwable t) {
            XposedBridge.log("[fucklark] download-audit suppress(file) install failed: " + t);
        }
        // 图片: hook PhotoPickerModuleDependencyImpl.auditImageDownload(String) -> 空转
        try {
            Class<?> ppd = cl.loadClass("com.ss.android.lark.framework.assembly.photopicker.PhotoPickerModuleDependencyImpl");
            int n = XposedBridge.hookAllMethods(ppd, "auditImageDownload", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (Config.downloadunlock) {
                        p.setResult(null);
                        if (Config.diaglog) XposedBridge.log("[fucklark] 已拦下图片审计上报: auditImageDownload("
                                + java.util.Arrays.toString(p.args) + ")");
                    }
                }
            }).size();
            XposedBridge.log("[fucklark] 屏蔽下载审计: PhotoPickerModuleDependencyImpl.auditImageDownload 已 hook " + n + " 个");
        } catch (Throwable t) {
            XposedBridge.log("[fucklark] download-audit suppress(image) install failed: " + t);
        }
        // 总出口(双保险): 存图/存视频的所有路径(PhotoPicker / ChatAuditDependency 等)最终都汇到审计服务
        // y33.a.a().auditImageDownload / auditMediaDownload。hook 访问器 y33.a.a() 发现服务实例, 按未混淆名空转其
        // auditImageDownload / auditMediaDownload -> 一网打尽。仅 7.70 定位(y33.a 混淆名; 方法名未混淆)。
        try {
            Class<?> y33a = cl.loadClass("y33.a");
            XposedBridge.hookAllMethods(y33a, "a", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Object svc = p.getResult();
                    if (svc == null) return;
                    Class<?> c = svc.getClass();
                    if (!auditImplHooked.add("svc:" + c.getName())) return;   // 服务实现类只挂一次
                    int n = hookNamedVoidMethods(c, "auditImageDownload") + hookNamedVoidMethods(c, "auditMediaDownload");
                    XposedBridge.log("[fucklark] 屏蔽下载审计(总出口): " + c.getName() + " 空转 " + n + " 个存图/存视频上报");
                }
            });
            XposedBridge.log("[fucklark] 屏蔽下载审计: 已挂审计服务访问器 y33.a.a (进程 " + AntiRecall.currentProcessName() + ")");
        } catch (Throwable t) {
            XposedBridge.log("[fucklark] download-audit suppress(sink) install failed: " + t);
        }
    }

    // 空转某类中所有指定名字的方法(存图/存视频审计), 返回挂上的数量。
    static int hookNamedVoidMethods(Class<?> c, final String name) {
        int n = 0;
        for (Method m : c.getDeclaredMethods()) {
            if (m.isSynthetic() || m.isBridge()) continue;
            if (!m.getName().equals(name)) continue;
            final String mn = m.getName();
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (Config.downloadunlock) {
                        p.setResult(null);
                        if (Config.diaglog) XposedBridge.log("[fucklark] 已拦下存图/存视频审计上报: " + mn
                                + "(" + java.util.Arrays.toString(p.args) + ")");
                    }
                }
            });
            n++;
        }
        return n;
    }

    // FileOpenUtils 里唯一的 static、无参、返回 boolean 的方法(不唯一则返回 null, 宁可不 hook 也不 hook 错)
    static Method uniqueStaticNoArgBoolean(Class<?> c) {
        Method hit = null;
        for (Method m : c.getDeclaredMethods()) {
            if (m.isSynthetic() || m.isBridge()) continue;
            if (Modifier.isStatic(m.getModifiers())
                    && m.getParameterTypes().length == 0
                    && (m.getReturnType() == boolean.class || m.getReturnType() == Boolean.class)) {
                if (hit != null) return null;   // 多个 -> 放弃
                hit = m;
            }
        }
        return hit;
    }

    // static、单参=param、返回类型=ret 的方法(唯一命中: doDownloadCheck(PhotoItem)->DownloadCheckResult)
    static Method staticMethodBySig(Class<?> c, Class<?> ret, Class<?> param) {
        for (Method m : c.getDeclaredMethods()) {
            if (m.isSynthetic() || m.isBridge()) continue;
            if (Modifier.isStatic(m.getModifiers())
                    && m.getReturnType() == ret
                    && m.getParameterTypes().length == 1
                    && m.getParameterTypes()[0] == param) {
                return m;
            }
        }
        return null;
    }
}
