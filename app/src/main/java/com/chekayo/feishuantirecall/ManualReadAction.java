package com.chekayo.feishuantirecall;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Toast;
import java.lang.reflect.Field;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 在宿主原生长按菜单中追加单条回执。
 * 菜单入口、消息 getter、item builder 和回调接口均按签名/返回类型发现；
 * 不消费自动已读缓存，也不调用回复触发链。
 */
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
            final Method messageGetter = entry.messageGetter;
            Diag.once("[fucklark] manual-read menu parse matched: " + parse.toGenericString()
                    + " contextArg=" + entry.contextIndex
                    + " messageGetter=" + messageGetter.toGenericString());
            XposedBridge.hookMethod(parse, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        if (!Config.antiread || AntiRecall.TAMPER == 2 || p.getResult() == null) return;
                        final Object ctx = p.args[entry.contextIndex];
                        final Object message = messageGetter.invoke(ctx);
                        final String account = RecallMessages.account();
                        if (!eligible(message, account)) return;
                        final String chat = text(message, "getChatId"), id = text(message, "getId");

                        Object result = p.getResult();
                        List<?> original = (List<?>) result.getClass().getMethod("getActionList").invoke(result);
                        if (original == null || original.isEmpty()) return; // 不绕过宿主禁止菜单的情形

                        // 动态查找网格项的结束位置
                        int gridEndPos = 0;
                        for (int i = 0; i < original.size(); i++) {
                            try {
                                if (isGridItem(original.get(i))) {
                                    // itemType=5 是网格类型
                                    gridEndPos = i + 1;
                                } else {
                                    // 遇到第一个非网格项，停止
                                    break;
                                }
                            } catch (Throwable ignored) {
                                // Unknown item shape: preserve the host's usual four-item grid prefix.
                                gridEndPos = Math.min(4, original.size());
                                break;
                            }
                        }

                        Context androidContext = findContext(ctx);
                        if (androidContext == null) androidContext = Config.appContextForSync();
                        if (androidContext == null) throw new IllegalStateException("菜单 Context 不可用");
                        Object item = createItem(cl, original.get(0).getClass(), androidContext, new Runnable() {
                            @Override public void run() {
                                try { XposedHelpers.callMethod(ctx, "dismiss"); } catch (Throwable ignored) {}
                                mark(message, account, chat, id);
                            }
                        });

                        List<Object> items = new ArrayList<Object>(original);
                        items.add(gridEndPos, item);
                        Field actionListField = findActionListField(result, original);
                        Diag.once("[fucklark] manual-read action list matched: "
                                + actionListField.getDeclaringClass().getName() + "#" + actionListField.getName());
                        actionListField.set(result, items);
                    } catch (Throwable t) { Diag.once("[fucklark] 单条已读菜单不可用: " + t); }
                }
            });
            Diag.once("[fucklark] 单条已读菜单已安装: manager=" + manager.getName());
        } catch (Throwable t) { Diag.once("[fucklark] 单条已读菜单当前版本未适配: " + t); }
    }

    private static Context findContext(Object owner) {
        if (owner == null) return null;
        Method fallback = null;
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.getParameterTypes().length != 0
                        || !Context.class.isAssignableFrom(method.getReturnType())) continue;
                if ("getContext".equals(method.getName())) {
                    fallback = method;
                    break;
                }
                if (fallback == null) fallback = method;
            }
            if (fallback != null && "getContext".equals(fallback.getName())) break;
        }
        if (fallback == null) return owner instanceof Context ? (Context) owner : null;
        try {
            fallback.setAccessible(true);
            Object value = fallback.invoke(owner);
            return value instanceof Context ? (Context) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Field findActionListField(Object result, List<?> actionList) throws Exception {
        for (Class<?> type = result.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!List.class.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                if (field.get(result) == actionList) return field;
            }
        }
        throw new NoSuchFieldException("menu action list field by identity");
    }

    private static boolean isGridItem(Object item) throws Exception {
        Method found = null;
        for (Method method : item.getClass().getMethods()) {
            if (method.getParameterTypes().length != 0 || method.getReturnType() != int.class) continue;
            Object value = method.invoke(item);
            if (!(value instanceof Integer) || ((Integer) value) != 5) continue;
            if (found != null) throw new IllegalStateException("ambiguous item type getter");
            found = method;
        }
        return found != null;
    }

    private static boolean eligible(Object message, String account) {
        if (message == null || account.isEmpty() || RecallActionGuard.blockedMessage(message)) return false;
        try {
            String chat = RecallMessages.messageText(message, "getChatId", "");
            String id = RecallMessages.messageText(message, "getId", "");
            if (RecallMessages.messageFromMe(message, account, chat, id)
                    || RecallMessages.messageRecalled(message)
                    || isPreMessage(message)) return false;
            // 普通会话消息支持；话题内的回执语义未验证，不能用整段话题位置代替单条。
            Object thread = XposedHelpers.callMethod(message, "getThreadId");
            if (thread != null && !thread.toString().isEmpty() && !"0".equals(thread.toString())) return false;
            return RecallMessages.positive(id) > 0 && RecallMessages.positive(chat) > 0;
        } catch (Throwable ignored) { return false; }
    }

    private static boolean isPreMessage(Object message) {
        try { return Boolean.TRUE.equals(XposedHelpers.callMethod(message, "isPreMessage")); }
        catch (Throwable ignored) { return false; }
    }

    private static Object createItem(ClassLoader cl, Class<?> itemClass, Context context, final Runnable click) throws Exception {
        Class<?> builderClass = findBuilderClass(itemClass);
        Method callbackSetter = null;
        Class<?> callback = null;
        for (Method method : builderClass.getDeclaredMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 1 || !params[0].isInterface()
                    || !(method.getReturnType() == builderClass || method.getReturnType() == void.class)) continue;
            if (!isActionCallback(params[0])) continue;
            if (callbackSetter != null) throw new NoSuchMethodException("ambiguous menu callback setter");
            callbackSetter = method;
            callback = params[0];
        }
        if (callbackSetter == null) throw new NoSuchMethodException("menu callback setter by signature");
        callbackSetter.setAccessible(true);
        Diag.once("[fucklark] manual-read callback matched: " + callback.getName()
                + " setter=" + callbackSetter.toGenericString());
        Object proxy = Proxy.newProxyInstance(cl, new Class<?>[]{callback}, new InvocationHandler() {
            @Override public Object invoke(Object p, Method m, Object[] args) {
                if (m.getDeclaringClass() == Object.class) return objectMethod(p, m, args);
                if (args != null && args.length == 2 && args[0] instanceof View && args[1] instanceof Boolean) click.run();
                return m.getReturnType() == boolean.class ? Boolean.FALSE : null;
            }
        });

        android.graphics.drawable.Drawable icon = context.getDrawable(android.R.drawable.ic_menu_view);
        Constructor<?> constructor = builderClass.getDeclaredConstructor(android.graphics.drawable.Drawable.class, String.class);
        constructor.setAccessible(true);
        Diag.once("[fucklark] manual-read builder matched: " + builderClass.getName()
                + " ctor=" + constructor.toGenericString());
        Object b = constructor.newInstance(icon, "标为已读");
        callbackSetter.invoke(b, proxy);
        findIntSetter(builderClass).invoke(b, 5);
        Method build = findBuildMethod(builderClass, itemClass);
        Diag.once("[fucklark] manual-read builder output matched: " + build.toGenericString());
        return build.invoke(b);
    }

    private static Class<?> findBuilderClass(Class<?> itemClass) throws NoSuchMethodException {
        for (Class<?> nested : itemClass.getDeclaredClasses()) {
            try {
                nested.getDeclaredConstructor(android.graphics.drawable.Drawable.class, String.class);
                return nested;
            } catch (NoSuchMethodException ignored) {}
        }
        throw new NoSuchMethodException("menu item builder (Drawable,String)");
    }

    private static boolean isActionCallback(Class<?> type) {
        boolean click = false, noArgVoid = false, noArgBoolean = false;
        boolean boolBoolean = false, otherVoidArg = false, hasActionType = false;
        boolean hasContextIconOrLabel = false;
        for (Method method : type.getMethods()) {
            Class<?>[] params = method.getParameterTypes();
            Class<?> result = method.getReturnType();
            if (params.length == 2 && params[0] == View.class
                    && (params[1] == boolean.class || params[1] == Boolean.class)
                    && result == void.class) click = true;
            if (params.length == 0 && result == void.class) noArgVoid = true;
            if (params.length == 0 && result == boolean.class) noArgBoolean = true;
            if (params.length == 1 && params[0] == boolean.class && result == boolean.class) boolBoolean = true;
            if (params.length == 1 && params[0] != View.class && result == void.class) otherVoidArg = true;
            if (params.length == 0 && result.isEnum()) hasActionType = true;
            if (params.length == 1 && params[0] == Context.class
                    && (android.graphics.drawable.Drawable.class.isAssignableFrom(result) || result == String.class))
                hasContextIconOrLabel = true;
        }
        return click && noArgVoid && noArgBoolean && boolBoolean && otherVoidArg
                && !hasActionType && !hasContextIconOrLabel;
    }

    private static Method findIntSetter(Class<?> builderClass) throws NoSuchMethodException {
        Method found = null;
        for (Method method : builderClass.getDeclaredMethods()) {
            if (method.getParameterTypes().length != 1 || method.getParameterTypes()[0] != int.class
                    || method.getReturnType() != builderClass) continue;
            if (found != null) throw new NoSuchMethodException("ambiguous menu item type setter");
            found = method;
        }
        if (found == null) throw new NoSuchMethodException("menu item type setter by signature");
        found.setAccessible(true);
        return found;
    }

    private static Method findBuildMethod(Class<?> builderClass, Class<?> itemClass) throws NoSuchMethodException {
        Method found = null;
        for (Method method : builderClass.getDeclaredMethods()) {
            if (method.getParameterTypes().length != 0 || method.getReturnType() != itemClass) continue;
            if (found != null) throw new NoSuchMethodException("ambiguous menu item build method");
            found = method;
        }
        if (found == null) throw new NoSuchMethodException("menu item build method by signature");
        found.setAccessible(true);
        return found;
    }

    private static void mark(final Object message, final String account, final String chat, final String id) {
        if (!Config.antiread || !account.equals(RecallMessages.account()) || !eligible(message, account)) {
            notice("消息状态或账号已变化，请重新长按消息"); return;
        }
        final Method send = AntiRecall.knMethod;
        final AntiRecall.KnState template;
        synchronized (AntiRecall.KN_STATE_LOCK) { template = AntiRecall.KN_STATE.get(chat); }
        if (send == null || template == null) { notice("尚未捕获此会话的已读请求，请重新进入聊天后重试"); return; }
        final String key = account + ":" + chat + ":" + id;
        if (!IN_FLIGHT.add(key)) return;
        new Thread(new Runnable() {
            @Override public void run() {
                Boolean previous = AntiRecall.KN_REPLAY.get();
                try {
                    if (!Config.antiread || AntiRecall.TAMPER == 2 || !account.equals(RecallMessages.account())
                            || !eligible(message, account)) throw new IllegalStateException("账号或消息状态已变化");
                    Object channel = channelOf(template.m);
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
                    // 仅本线程本次请求直通现有拦截；不设置 READ_WINDOWS，也不重放积压 IDs。
                    AntiRecall.KN_REPLAY.set(Boolean.TRUE);
                    send.invoke(template.impl, request, callback);
                    MAIN.postDelayed(new Runnable() {
                        @Override public void run() {
                            if (IN_FLIGHT.remove(key)) notice("未收到已读确认，请检查网络后重试");
                        }
                    }, 15000);
                } catch (Throwable t) {
                    IN_FLIGHT.remove(key); notice("当前版本无法提交单条已读，请稍后重试");
                    Diag.once("[fucklark] 单条已读失败: " + t);
                } finally {
                    if (previous == null) AntiRecall.KN_REPLAY.remove(); else AntiRecall.KN_REPLAY.set(previous);
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
        if ("getId".equals(getter) || "getChatId".equals(getter))
            return RecallMessages.messageText(message, getter, "");
        return String.valueOf(XposedHelpers.callMethod(message, getter));
    }
    private static Object channelOf(Object packet) throws Exception {
        for (java.lang.reflect.Field field : packet.getClass().getDeclaredFields()) {
            if (field.getType().getName().endsWith(".Channel")) {
                field.setAccessible(true);
                return field.get(packet);
            }
        }
        throw new NoSuchFieldException("read packet channel field");
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
