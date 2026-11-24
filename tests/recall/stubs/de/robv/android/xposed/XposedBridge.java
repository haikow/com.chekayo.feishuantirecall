package de.robv.android.xposed;

import java.lang.reflect.Constructor;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class XposedBridge {
    public static final Map<Member, XC_MethodHook> HOOKS = new ConcurrentHashMap<Member, XC_MethodHook>();

    public static void clearHooks() {
        HOOKS.clear();
    }

    public static void log(String text) {}
    public static void log(Throwable t) {}

    public static Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> hookClass, String methodName, XC_MethodHook callback) {
        Set<XC_MethodHook.Unhook> unhooks = new HashSet<XC_MethodHook.Unhook>();
        if (hookClass != null && methodName != null) {
            for (Method m : hookClass.getDeclaredMethods()) {
                if (methodName.equals(m.getName())) {
                    unhooks.add(hookMethod(m, callback));
                }
            }
        }
        return unhooks;
    }

    public static XC_MethodHook.Unhook hookMethod(Member method, XC_MethodHook callback) {
        if (method != null && callback != null) {
            HOOKS.put(method, callback);
        }
        return new XC_MethodHook.Unhook();
    }

    public static Set<XC_MethodHook.Unhook> hookAllConstructors(Class<?> hookClass, XC_MethodHook callback) {
        Set<XC_MethodHook.Unhook> unhooks = new HashSet<XC_MethodHook.Unhook>();
        if (hookClass != null) {
            for (Constructor<?> c : hookClass.getDeclaredConstructors()) {
                unhooks.add(hookMethod(c, callback));
            }
        }
        return unhooks;
    }
}
