package com.chekayo.feishuantirecall;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/** RecyclerView 绑定实现会被改名；按 holder/int/void 契约定位，优先真实实现。 */
final class ConversationSwipeSettingsDiscovery {
    static List<Method> find(Class<?> activity, Class<?> adapter, Class<?> holder) throws NoSuchMethodException {
        List<Method> bindings = new ArrayList<Method>();
        for (Class<?> nested : activity.getDeclaredClasses()) {
            if (!adapter.isAssignableFrom(nested)) continue;
            Method implementation = null, bridge = null;
            int implementations = 0, bridges = 0;
            for (Method method : nested.getDeclaredMethods()) {
                Class<?>[] args = method.getParameterTypes();
                if (Modifier.isStatic(method.getModifiers()) || method.getReturnType() != void.class
                        || args.length != 2 || args[1] != int.class || !holder.isAssignableFrom(args[0])) continue;
                if (method.isBridge() || method.isSynthetic()) { bridge = method; bridges++; }
                else { implementation = method; implementations++; }
            }
            if (implementations > 1 || (implementations == 0 && bridges > 1))
                throw new NoSuchMethodException("会话滑动设置绑定实现不唯一: " + nested.getName());
            Method binding = implementations == 1 ? implementation : bridge;
            if (binding != null) { binding.setAccessible(true); bindings.add(binding); }
        }
        if (bindings.isEmpty()) throw new NoSuchMethodException("没有会话滑动设置绑定实现");
        return bindings;
    }
    private ConversationSwipeSettingsDiscovery() {}
}
