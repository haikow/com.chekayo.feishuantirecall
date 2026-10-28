package com.chekayo.feishuantirecall;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Toast;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import io.github.libxposed.api.XposedInterface;

/** 在原生长按菜单末尾追加单条回执。不调用 triggerReply，不消费任何自动回执缓存。 */
final class ManualReadAction {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final java.util.Set<String> IN_FLIGHT = java.util.Collections.newSetFromMap(
            new ConcurrentHashMap<String, Boolean>());

    static void install(final ClassLoader cl) {
        try {
            final Class<?> messageClass = cl.loadClass("com.ss.android.lark.chat.entity.message.Message");
            Class<?> manager = cl.loadClass("com.ss.android.lark.chat.message.menu.MessageActionManager");
            final ManualReadMenuDiscovery.Entry entry = ManualReadMenuDiscovery.find(manager, messageClass);
            final Method parse = entry.parser;
            final Method selectedMessageGetter = entry.messageGetter;
            Diag.once("[fucklark] 单条已读菜单入口命中: " + parse.toGenericString()
                    + " contextArg=" + entry.contextIndex
                    + " messageGetter=" + selectedMessageGetter.toGenericString());

            // Use the API 102 hook registry with the exact runtime-discovered signature.
            HookRuntime.findAndHookMethod(manager.getName(), cl, parse.getName(),
                    parse.getParameterTypes(),
                    "manual-read-menu", new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    try {
                        if (!Config.antiread || AntiRecall.TAMPER == 2 || result == null) return result;
                        final Object ctx = chain.getArgs().get(entry.contextIndex);
                        final Object message = selectedMessageGetter.invoke(ctx);
                        final String account = RecallMessages.account();
                        if (!eligible(message, account)) return result;
                        final String chat = RecallMessages.messageText(message, "getChatId", "");
                        final String id = RecallMessages.messageText(message, "getId", "");

                        List<?> original = (List<?>) result.getClass().getMethod("getActionList").invoke(result);
                        if (original == null || original.isEmpty()) return result;

                        // 动态查找网格项的结束位置
                        int gridEndPos = 0;
                        for (int i = 0; i < original.size(); i++) {
                            try {
                                Object item = original.get(i);
                                if (isGridItem(original.get(i))) {
                                    gridEndPos = i + 1;
                                } else {
                                    break;
                                }
                            } catch (Throwable ignored) {
                                gridEndPos = Math.min(4, original.size());
                                break;
                            }
                        }

                        Context androidContext = findContext(ctx);
                        if (androidContext == null) androidContext = Config.appContextForSync();
                        if (androidContext == null) return result;

                        Object item = createItem(cl, original.get(0).getClass(), androidContext, new Runnable() {
                            @Override public void run() {
                                try { Reflect.callMethod(ctx, "dismiss"); } catch (Throwable ignored) {}
                                mark(message, account, chat, id);
                            }
                        });

                        List<Object> items = new ArrayList<Object>(original);
                        items.add(gridEndPos, item);
                        findActionListField(result, original).set(result, items);
                    } catch (Throwable t) {
                        Diag.once("[fucklark] 单条已读菜单不可用: " + t);
                    }
                    return result;
                }
            });
            Diag.once("[fucklark] 单条已读菜单已安装: " + manager.getName());
        } catch (Throwable t) {
            Diag.once("[fucklark] 单条已读菜单当前版本未适配: " + t);
        }
    }

    private static boolean eligible(Object message, String account) {
        try {
            if (message == null || account == null || account.isEmpty()) return false;
            String chat = RecallMessages.messageText(message, "getChatId", "");
            String id = RecallMessages.messageText(message, "getId", "");
            if (RecallMessages.messageFromMe(message, account, chat, id)
                    || RecallMessages.messageRecalled(message)
                    || isPreMessage(message)) return false;
            Object thread = Reflect.callMethod(message, "getThreadId");
            if (thread != null && !thread.toString().isEmpty() && !"0".equals(thread.toString())) return false;
            return RecallMessages.positive(id) > 0 && RecallMessages.positive(chat) > 0;
        } catch (Throwable ignored) { return false; }
    }

    private static boolean isPreMessage(Object message) {
        try { return Boolean.TRUE.equals(Reflect.callMethod(message, "isPreMessage")); }
        catch (Throwable ignored) { return false; }
    }

    private static Context findContext(Object owner) {
        if (owner instanceof Context) return (Context) owner;
        if (owner == null) return null;
        Method found = null;
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass())
            for (Method m : type.getDeclaredMethods()) {
                if (m.getParameterTypes().length != 0 || !Context.class.isAssignableFrom(m.getReturnType())) continue;
                if ("getContext".equals(m.getName())) { found = m; break; }
                if (found == null) found = m;
            }
        if (found == null) return null;
        try { found.setAccessible(true); Object v = found.invoke(owner); return v instanceof Context ? (Context) v : null; }
        catch (Throwable ignored) { return null; }
    }

    private static Field findActionListField(Object result, List<?> actions) throws Exception {
        for (Class<?> type = result.getClass(); type != null; type = type.getSuperclass())
            for (Field field : type.getDeclaredFields()) {
                if (!List.class.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                if (field.get(result) == actions) {
                    Diag.once("[fucklark] 菜单列表字段已定位: "
                            + field.getDeclaringClass().getName() + "#" + field.getName());
                    return field;
                }
            }
        throw new NoSuchFieldException("菜单 action list identity field");
    }

    private static boolean isGridItem(Object item) throws Exception {
        Method found = null;
        for (Method method : item.getClass().getMethods()) {
            if (method.getParameterTypes().length != 0 || method.getReturnType() != int.class) continue;
            Object value = method.invoke(item);
            if (!(value instanceof Integer) || ((Integer) value) != 5) continue;
            if (found != null) throw new NoSuchMethodException("ambiguous item type getter");
            found = method;
        }
        return found != null;
    }

    private static Class<?> findBuilderClass(Class<?> itemClass) throws NoSuchMethodException {
        Class<?> found = null;
        for (Class<?> nested : itemClass.getDeclaredClasses()) try {
            nested.getDeclaredConstructor(android.graphics.drawable.Drawable.class, String.class);
            if (found != null) throw new NoSuchMethodException("ambiguous menu builder class");
            found = nested;
        } catch (NoSuchMethodException ignored) {}
        if (found == null) throw new NoSuchMethodException("menu builder by constructor shape");
        Diag.once("[fucklark] 单条已读菜单 builder 命中: " + found.getName());
        return found;
    }

    private static boolean isCallback(Class<?> type) {
        boolean click = false, noArgVoid = false, noArgBoolean = false;
        boolean boolBoolean = false, otherVoidArg = false, hasActionType = false;
        boolean hasContextIconOrLabel = false;
        for (Method m : type.getMethods()) {
            Class<?>[] p = m.getParameterTypes();
            Class<?> result = m.getReturnType();
            if (p.length == 2 && p[0] == View.class
                    && (p[1] == boolean.class || p[1] == Boolean.class) && result == void.class) click = true;
            if (p.length == 0 && result == void.class) noArgVoid = true;
            if (p.length == 0 && result == boolean.class) noArgBoolean = true;
            if (p.length == 1 && p[0] == boolean.class && result == boolean.class) boolBoolean = true;
            if (p.length == 1 && p[0] != View.class && p[0] != boolean.class && result == void.class)
                otherVoidArg = true;
            if (p.length == 0 && result.isEnum()) hasActionType = true;
            if (p.length == 1 && p[0] == Context.class
                    && (android.graphics.drawable.Drawable.class.isAssignableFrom(result) || result == String.class))
                hasContextIconOrLabel = true;
        }
        return click && noArgVoid && noArgBoolean && boolBoolean && otherVoidArg
                && !hasActionType && !hasContextIconOrLabel;
    }

    private static Object createItem(ClassLoader cl, Class<?> itemClass, final Context androidContext, final Runnable action) throws Exception {
        Class<?> builder = findBuilderClass(itemClass);
        Method callbackSetter = null;
        Class<?> callbackClass = null;
        for (Method m : builder.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length != 1 || !p[0].isInterface() || !isCallback(p[0])
                    || !(m.getReturnType() == builder || m.getReturnType() == void.class)) continue;
            if (callbackSetter != null) throw new NoSuchMethodException("ambiguous callback setter");
            callbackSetter = m; callbackClass = p[0];
        }
        if (callbackSetter == null) throw new NoSuchMethodException("callback setter by interface shape");
        callbackSetter.setAccessible(true);
        Diag.once("[fucklark] 单条已读菜单 callback 命中: " + callbackClass.getName()
                + " setter=" + callbackSetter.toGenericString());
        final Class<?> callback = callbackClass;
        Object listener = Proxy.newProxyInstance(cl, new Class<?>[]{callback}, new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
                if (args != null && args.length == 2 && args[0] instanceof View && args[1] instanceof Boolean) {
                    MAIN.post(action);
                    return null;
                }
                return method.getReturnType() == boolean.class ? Boolean.FALSE : null;
            }
        });
        Constructor<?> ctor = builder.getDeclaredConstructor(android.graphics.drawable.Drawable.class, String.class);
        ctor.setAccessible(true);
        Object b = ctor.newInstance(androidContext.getDrawable(android.R.drawable.ic_menu_view), "标为已读");
        callbackSetter.invoke(b, listener);
        Method typeSetter = null, build = null;
        for (Method m : builder.getDeclaredMethods()) {
            if (m.getParameterTypes().length == 1 && m.getParameterTypes()[0] == int.class
                    && m.getReturnType() == builder) {
                if (typeSetter != null) throw new NoSuchMethodException("ambiguous item type setter");
                typeSetter = m;
            }
            if (m.getParameterTypes().length == 0 && m.getReturnType() == itemClass) {
                if (build != null) throw new NoSuchMethodException("ambiguous item build method");
                build = m;
            }
        }
        if (typeSetter == null || build == null) throw new NoSuchMethodException("menu builder terminal methods");
        typeSetter.setAccessible(true); build.setAccessible(true);
        typeSetter.invoke(b, 5);
        return build.invoke(b);
    }

    private static void mark(final Object message, final String account, final String chat, final String id) {
        if (!Config.antiread || !account.equals(RecallMessages.account()) || !eligible(message, account)) {
            notice("消息状态或账号已变化，请重新长按消息"); return;
        }
        final Method send = AntiRecall.knMethod;
        final AntiRecall.KnState template = AntiRecall.KN_STATE.get(chat);
        if (send == null || template == null) {
            notice("尚未捕获此会话的已读请求，请重新进入聊天后重试"); return;
        }
        final String key = account + ":" + chat + ":" + id;
        if (!IN_FLIGHT.add(key)) return;

        new Thread(new Runnable() {
            @Override public void run() {
                Boolean previous = AntiRecall.KN_REPLAY.get();
                try {
                    if (!Config.antiread || AntiRecall.TAMPER == 2 || !account.equals(RecallMessages.account())
                            || !eligible(message, account)) throw new IllegalStateException("账号或消息状态已变化");

                    Object channel = channelField(template.m);
                    if (!chat.equals(AntiRecall.channelId(channel))) throw new IllegalStateException("会话模板不匹配");

                    Object request = ManualReadRequest.create(template.m, id);
                    Class<?> cb = send.getParameterTypes()[1];
                    Object callback = Proxy.newProxyInstance(cb.getClassLoader(), new Class<?>[]{cb}, new InvocationHandler() {
                        @Override public Object invoke(Object proxy, Method method, Object[] args) {
                            if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
                            if (method.getName().equals("onSuccess")) {
                                IN_FLIGHT.remove(key); notice("此条消息已标为已读");
                            } else if (method.getName().equals("onError")) {
                                IN_FLIGHT.remove(key); notice("标为已读失败，请稍后重试");
                            }
                            return null;
                        }
                    });
                    AntiRecall.KN_REPLAY.set(Boolean.TRUE);
                    send.invoke(template.impl, request, callback);
                    MAIN.postDelayed(new Runnable() {
                        @Override public void run() {
                            if (IN_FLIGHT.remove(key)) notice("未收到已读确认，请检查网络后重试");
                        }
                    }, 15000);
                } catch (Throwable t) {
                    IN_FLIGHT.remove(key);
                    notice("标记失败: " + t.getMessage());
                    Diag.once("[fucklark] 单条已读标记失败: " + t);
                } finally {
                    if (previous != null) AntiRecall.KN_REPLAY.set(previous);
                    else AntiRecall.KN_REPLAY.remove();
                }
            }
        }, "feishukit-manual-read").start();
    }

    private static Object objectMethod(Object proxy, Method method, Object[] args) {
        if (method.getName().equals("equals")) return args != null && proxy == args[0];
        if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
        return "FeishuKitManualRead";
    }

    private static String text(Object message, String getter) {
        return String.valueOf(Reflect.callMethod(message, getter));
    }

    private static Object channelField(Object request) throws Exception {
        for (Class<?> type = request.getClass(); type != null; type = type.getSuperclass())
            for (Field field : type.getDeclaredFields()) if (field.getType().getName().endsWith(".Channel")) {
                field.setAccessible(true);
                return field.get(request);
            }
        throw new NoSuchFieldException("channel field by runtime type");
    }

    private static void notice(final String text) {
        MAIN.post(new Runnable() {
            @Override public void run() {
                Context c = Config.appContextForSync();
                if (c != null) Toast.makeText(c, text, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private ManualReadAction() {}
}
