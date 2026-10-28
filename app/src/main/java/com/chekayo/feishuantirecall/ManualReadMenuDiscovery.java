package com.chekayo.feishuantirecall;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

/** Discovers the long-press menu parser without assuming obfuscated parameter order. */
final class ManualReadMenuDiscovery {
    static final class Entry {
        final Method parser;
        final Method messageGetter;
        final int contextIndex;

        Entry(Method parser, Method messageGetter, int contextIndex) {
            this.parser = parser;
            this.messageGetter = messageGetter;
            this.contextIndex = contextIndex;
        }
    }

    static Entry find(Class<?> manager, Class<?> messageClass) throws NoSuchMethodException {
        Entry found = null;
        for (Method method : manager.getDeclaredMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if (Modifier.isStatic(method.getModifiers()) || params.length != 3
                    || method.getReturnType() == void.class || method.getReturnType().isPrimitive()) continue;
            Method actionList;
            try { actionList = method.getReturnType().getMethod("getActionList"); }
            catch (NoSuchMethodException ignored) { continue; }
            if (!List.class.isAssignableFrom(actionList.getReturnType())) continue;
            int listIndex = -1;
            for (int i = 0; i < params.length; i++) if (List.class.isAssignableFrom(params[i])) {
                if (listIndex != -1) { listIndex = -2; break; }
                listIndex = i;
            }
            if (listIndex < 0) continue;
            Method getter = null;
            int contextIndex = -1;
            boolean ambiguousContext = false;
            for (int i = 0; i < params.length; i++) {
                if (i == listIndex) continue;
                Method candidate = messageGetter(params[i], messageClass);
                if (candidate == null) continue;
                if (contextIndex >= 0) { ambiguousContext = true; break; }
                contextIndex = i;
                getter = candidate;
            }
            if (ambiguousContext || contextIndex < 0) continue;
            if (found != null) throw new NoSuchMethodException("长按菜单解析入口候选不唯一");
            found = new Entry(method, getter, contextIndex);
        }
        if (found == null) throw new NoSuchMethodException("未找到符合结构的长按菜单解析入口");
        found.parser.setAccessible(true);
        found.messageGetter.setAccessible(true);
        return found;
    }

    private static Method messageGetter(Class<?> context, Class<?> messageClass) {
        Method found = null;
        for (Method method : context.getMethods()) {
            if (Modifier.isStatic(method.getModifiers()) || method.getParameterTypes().length != 0
                    || method.getReturnType() != messageClass || method.isBridge() || method.isSynthetic()) continue;
            if (found != null) return null;
            found = method;
        }
        return found;
    }

    private ManualReadMenuDiscovery() {}
}
