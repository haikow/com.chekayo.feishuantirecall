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
