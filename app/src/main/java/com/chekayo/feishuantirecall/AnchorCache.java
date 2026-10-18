package com.chekayo.feishuantirecall;

import android.content.Context;
import java.io.*;
import java.lang.reflect.Method;
import java.util.Properties;

/** 每个宿主/Android 用户独立存储。缓存只保存方法描述，不保存 ClassLoader 或消息数据。 */
final class AnchorCache {
    static final String SCHEMA = "time-footer-2";
    static String identity(Context ctx) throws Exception {
        android.content.pm.PackageInfo info = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
        android.content.pm.ApplicationInfo app = ctx.getApplicationInfo();
        // updateTime 和 APK 路径可识别同版本替换安装；模块扫描规则变化也强制失效。
        return SCHEMA + ":" + ctx.getPackageName() + ":" + info.versionName + ":"
                + info.versionCode + ":" + info.lastUpdateTime + ":" + app.sourceDir;
    }
    static Method load(File file, String identity, ClassLoader loader) {
        if (!file.isFile()) { Diag.w("时间锚点缓存: 不存在，需要扫描"); return null; }
        try (FileInputStream in = new FileInputStream(file)) {
            Properties p = new Properties(); p.load(in);
            if (!identity.equals(p.getProperty("identity"))) {
                Diag.w("时间锚点缓存: 宿主版本、安装信息或扫描规则变化，旧缓存失效"); return null;
            }
            Method method = loader.loadClass(p.getProperty("owner")).getDeclaredMethod(p.getProperty("method"),
                    loader.loadClass(p.getProperty("holder")), loader.loadClass(p.getProperty("model")));
            int flags = method.getModifiers();
            if (method.getReturnType() != void.class || method.isBridge() || method.isSynthetic()
                    || java.lang.reflect.Modifier.isStatic(flags) || java.lang.reflect.Modifier.isPrivate(flags)) {
                Diag.w("时间锚点缓存: 方法签名校验失败"); return null;
            }
            method.setAccessible(true);
            Diag.w("时间锚点缓存: 命中 " + method.toGenericString());
            return method;
        } catch (Exception error) { Diag.w("时间锚点缓存: 读取失败，回退扫描 " + error); return null; }
        catch (LinkageError error) { Diag.w("时间锚点缓存: 宿主类加载失败，回退扫描 " + error); return null; }
    }
    static void save(File file, String identity, Method method) throws Exception {
        Properties p = new Properties();
        p.setProperty("identity", identity);
        p.setProperty("owner", method.getDeclaringClass().getName());
        p.setProperty("method", method.getName());
        p.setProperty("holder", method.getParameterTypes()[0].getName());
        p.setProperty("model", method.getParameterTypes()[1].getName());
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("cannot create anchor directory");
        File temp = File.createTempFile("time-anchor", ".tmp", parent);
        try {
            try (FileOutputStream out = new FileOutputStream(temp)) {
                p.store(out, "FeishuKit anchor cache"); out.getFD().sync();
            }
            if (!temp.renameTo(file)) throw new IOException("cannot publish anchor cache");
            Diag.w("时间锚点缓存: 已保存 " + method.toGenericString());
        } finally { temp.delete(); }
    }
    private AnchorCache() {}
}
