package com.chekayo.feishuantirecall;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import android.view.View;
import android.view.MotionEvent;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 已撤回原文只是本地历史，不能继续作为服务端动作的目标。
 * 菜单、话题表情、滑动快捷操作都有独立入口：先按当前账号查询撤回标记，
 * 再在宿主副作用发生前拦截；普通消息和允许的内容查看操作保持宿主原流程。
 */
final class RecallActionGuard {
    private static final String MESSAGE = "com.ss.android.lark.chat.entity.message.Message";
    private static long lastNotice;
    private static final Map<Object, WeakReference<Object>> actionContexts =
            Collections.synchronizedMap(new WeakHashMap<Object, WeakReference<Object>>());
    private static final Map<Object, WeakReference<Object>> swipeMessages =
            Collections.synchronizedMap(new WeakHashMap<Object, WeakReference<Object>>());
    private static final Map<Object, Object> captureModels =
            Collections.synchronizedMap(new WeakHashMap<Object, Object>());

    // 白名单仅覆盖读取本地内容的动作；新版新增菜单类型默认不对已撤回目标开放。
    static boolean allowedAction(String type) {
        return "COPY".equals(type) || "COPY_IMAGE_AND_MEDIA".equals(type)
                || "COPY_URL_LINK".equals(type) || "ZONE_COPY".equals(type)
                || "SELECT_TEXT".equals(type) || "SAVE_TO".equals(type)
                || "MUTE_PLAY_VIDEO".equals(type) || "SELECT_AUDIO_PLAY_MODE".equals(type);
    }
    static boolean blockedAction(Object action, Object message) {
        if (!blockedMessage(message)) return false;
        try {
            Object type = actionType(action);
            return !(type instanceof Enum) || !allowedAction(((Enum<?>) type).name());
        } catch (Throwable ignored) { return true; }
    }

    private static Object actionType(Object action) throws Throwable {
        try { return XposedHelpers.callMethod(action, "getType"); }
        catch (Throwable ignored) {}
        Method found = null;
        for (Method method : action.getClass().getMethods()) {
            if (Modifier.isStatic(method.getModifiers()) || method.getParameterTypes().length != 0
                    || !method.getReturnType().isEnum()) continue;
            if (found != null) throw new NoSuchMethodException("ambiguous action type getter");
            found = method;
        }
        if (found == null) throw new NoSuchMethodException("action type getter");
        return found.invoke(action);
    }

    static boolean blockedMessage(Object message) {
        // 优先使用模型里的 chatId，避免不同会话里相同 ID 或切号后误判。
        if (message == null || AntiRecall.TAMPER == 2) return false;
        try {
            return RecallMessages.marked(RecallMessages.account(),
                    RecallMessages.messageText(message, "getChatId", ""),
                    RecallMessages.messageText(message, "getId", ""));
        } catch (Throwable ignored) { return false; }
    }
    static boolean blockedId(String id, String chat) {
        // 仅当宿主没有提供模型/会话时才使用消息 ID 的账号级索引兜底。
        if (AntiRecall.TAMPER == 2) return false;
        String uid = RecallMessages.account();
        return RecallMessages.marked(uid, chat, id) || RecallMessages.markedMessage(uid, id);
    }
    static Method findMessageGetter(Class<?> context, Class<?> message) throws NoSuchMethodException {
        Method found = null;
        for (Method m : context.getMethods()) {
            if (Modifier.isStatic(m.getModifiers()) || m.getParameterTypes().length != 0
                    || m.getReturnType() != message || m.isBridge()) continue;
            if (found != null) throw new NoSuchMethodException("ambiguous action message");
            found = m;
        }
        if (found == null) throw new NoSuchMethodException("action message");
        return found;
    }
    interface Target { boolean blocked(Object receiver, java.util.List<Object> args) throws Throwable; }
    static final class Guard extends XC_MethodHook {
        final Target target;
        final boolean predicate;
        Guard(Target target, boolean predicate) { this.target = target; this.predicate = predicate; }
        @Override protected void beforeHookedMethod(MethodHookParam param) {
            // 谓词方法返回 false；void 动作短路为 null，不执行回复/表情等宿主副作用。
            boolean blocked = false;
            try { blocked = target.blocked(param.thisObject, java.util.Arrays.asList(param.args)); }
            catch (Throwable ignored) {}
            if (!blocked) return;
            if (predicate) param.setResult(false);
            else { notice(); param.setResult(null); }
        }
    }
    private static void notice() {
        try {
            long now = System.currentTimeMillis();
            if (now - lastNotice < 1500) return;
            lastNotice = now;
            android.content.Context context = Config.appContextForSync();
            if (context != null) new android.os.Handler(android.os.Looper.getMainLooper()).post(
                    new AntiRecall.ToastRunnable(context, "这条消息已撤回，仅支持查看、复制和下载"));
        } catch (Throwable ignored) {}
    }
    /** 各入口独立尝试安装：版本混淆导致一个入口缺失时，其余保护仍可工作。 */
    static void install(ClassLoader cl) {
        try { installMenuPolicy(cl); }
        catch (Throwable t) { Diag.once("[fucklark] recall menu policy unavailable: " + t); }
        try { installSwipePolicy(cl); }
        catch (Throwable t) { Diag.once("[fucklark] recall swipe policy unavailable: " + t); }
        try { installThreadReactions(cl.loadClass("com.ss.android.lark.replyinthread.pages.normal.ReplyInThreadViewModel"), cl.loadClass(MESSAGE)); }
        catch (Throwable t) { Diag.once("[fucklark] recall thread reaction guard unavailable: " + t); }
    }

    static void installThreadReactions(Class<?> type, Class<?> message) throws Throwable {
        // 话题页已有表情条也能直接触发 add/deleteReaction，不能只限制普通消息菜单。
        Method foundLookup = null;
        Method foundGetMessage = null;
        for (Method method : type.getMethods()) {
            if (Modifier.isStatic(method.getModifiers()) || method.getParameterTypes().length != 1
                    || method.getParameterTypes()[0] != String.class || method.getReturnType() == void.class) continue;
            Method getter;
            try { getter = findMessageGetter(method.getReturnType(), message); }
            catch (NoSuchMethodException ignored) { continue; }
            if (foundLookup != null) throw new NoSuchMethodException("ambiguous thread message lookup");
            foundLookup = method;
            foundGetMessage = getter;
        }
        if (foundLookup == null) throw new NoSuchMethodException("thread message lookup by shape");
        final Method lookup = foundLookup;
        final Method getMessage = foundGetMessage;
        Target target = new Target() {
            public boolean blocked(Object receiver, List<Object> args) throws Throwable {
                // 8.2.6 参数为 (messageId, reactionType)，第二个字符串不是消息 ID。
                String id = (String) args.get(0);
                if (blockedId(id, null)) return true;
                Object info = lookup.invoke(receiver, id);
                if (info != null && blockedMessage(getMessage.invoke(info))) return true;
                return blockedId(id, RecallMessages.messageText(receiver, "getChatId", ""));
            }
        };
        int installed = 0;
        for (Method method : type.getDeclaredMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if (Modifier.isStatic(method.getModifiers()) || method.getReturnType() != void.class
                    || params.length != 2 || params[0] != String.class || params[1] != String.class) continue;
            String id = "recall.thread.reaction." + method.getDeclaringClass().getName() + "." + method.toGenericString();
            RecallHooks.hook(method, id, new Guard(target, false));
            installed++;
        }
        if (installed == 0) throw new NoSuchMethodException("thread reaction methods by signature");
        Diag.once("[fucklark] thread reaction anchors matched: lookup=" + lookup.toGenericString()
                + " messageGetter=" + getMessage.toGenericString() + " actions=" + installed);
    }

    /** 统一筛选消息菜单，新增的未知操作默认不可用于已撤回目标。 */
    private static void installMenuPolicy(ClassLoader cl) throws Throwable {
        Class<?> manager = cl.loadClass("com.ss.android.lark.chat.message.menu.MessageActionManager");
        Class<?> messageClass = cl.loadClass(MESSAGE);
        Method filter = null, messageGetter = null;
        for (Method method : manager.getDeclaredMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if (Modifier.isStatic(method.getModifiers()) || method.getReturnType() != boolean.class
                    || params.length != 3 || !List.class.isAssignableFrom(params[2])) continue;
            Method candidate;
            try { candidate = findMessageGetter(params[1], messageClass); }
            catch (NoSuchMethodException ignored) { continue; }
            if (filter != null) throw new NoSuchMethodException("ambiguous message-menu filter");
            filter = method;
            messageGetter = candidate;
        }
        if (filter == null) throw new NoSuchMethodException("message-menu filter by parameter shape");
        final Method message = messageGetter;
        filter.setAccessible(true);
        Diag.once("[fucklark] message-menu filter matched: " + filter.toGenericString()
                + " messageGetter=" + message.toGenericString());
        RecallHooks.hook(filter, "recall.menu.policy", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Object item = param.args[0], ctx = param.args[1];
                try {
                    actionContexts.put(item, new WeakReference<Object>(ctx));
                    // 已打开的菜单可能在撤回前创建：过滤显示还不够，点击时必须再次查标记。
                    // 正常消息及白名单动作仍沿用宿主点击回调。
                    Method click = findClickMethod(item.getClass());
                    String clickId = "recall.menu.click." + click.getDeclaringClass().getName();
                    if (click.getReturnType() == void.class && !RecallHooks.installed(clickId)) RecallHooks.hook(click,
                            clickId, new Guard(new Target() {
                        @Override public boolean blocked(Object receiver, List<Object> args) throws Throwable {
                            WeakReference<Object> ref = actionContexts.get(receiver);
                            Object ctx = ref == null ? null : ref.get();
                            return ctx != null && blockedAction(receiver, message.invoke(ctx));
                        }
                    }, false));
                } catch (Throwable t) { Diag.once("[fucklark] recall menu click guard unavailable: " + t.getClass().getSimpleName()); }
                try {
                    if (blockedAction(item, message.invoke(ctx))) param.setResult(true); // host: true = exclude
                } catch (Throwable ignored) {}
            }
        });
        Diag.once("[fucklark] recall menu policy: content access only");
    }

    private static Method findClickMethod(Class<?> type) throws NoSuchMethodException {
        Method found = null;
        for (Method method : type.getMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if (Modifier.isStatic(method.getModifiers()) || method.getReturnType() != void.class
                    || params.length != 2 || params[0] != View.class || params[1] != boolean.class) continue;
            if (found != null) throw new NoSuchMethodException("ambiguous menu action click method");
            found = method;
        }
        if (found == null) throw new NoSuchMethodException("menu action click method by shape");
        return found;
    }

    static Method findMethod(Class<?> type, Class<?> returns, Class<?>... params) throws NoSuchMethodException {
        Method found = null;
        for (Method method : type.getDeclaredMethods()) {
            if (Modifier.isStatic(method.getModifiers()) || method.isBridge()
                    || method.getReturnType() != returns
                    || !java.util.Arrays.equals(params, method.getParameterTypes())) continue;
            if (found != null) throw new NoSuchMethodException("ambiguous method in " + type.getName());
            found = method;
        }
        if (found == null) throw new NoSuchMethodException("method shape in " + type.getName());
        return found;
    }

    private static void installSwipePolicy(ClassLoader cl) throws Throwable {
        // 滑动动作先从 RecyclerView 命中消息，再把模型弱关联到宿主动作对象；
        // 动作真正执行时再查一次标记，覆盖捕获和点击之间发生的撤回。
        String prefix = "com.ss.android.lark.chat.chatwindow.chat.v2.component.message.swipe.";
        Class<?> captured = cl.loadClass(prefix + "SwipeActionHandler$CapturedItemInfo");
        Class<?> factory = cl.loadClass(prefix + "SwipeActionHandler$CapturedItemInfo$a");
        Class<?> recycler = cl.loadClass("androidx.recyclerview.widget.RecyclerView");
        Class<?> holder = cl.loadClass("androidx.recyclerview.widget.RecyclerView$ViewHolder");
        Class<?> source = cl.loadClass(prefix + "c");
        final Method childAt = recycler.getMethod("findChildViewUnder", float.class, float.class);
        final Method holderAt = recycler.getMethod("findContainingViewHolder", View.class);
        final Method position = holder.getMethod("getLayoutPosition");
        final Method itemAt = source.getMethod("getItem", int.class);
        final Method actionOf = findMethod(captured, cl.loadClass(prefix + "b"));
        Method capture = findMethod(factory, captured, recycler, MotionEvent.class,
                cl.loadClass(prefix + "g"), source, cl.loadClass(prefix + "b$a"));
        RecallHooks.hook(capture, "recall.swipe.capture", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Object model = null;
                try {
                    MotionEvent event = (MotionEvent) param.args[1];
                    Object child = childAt.invoke(param.args[0], event.getX(), event.getY());
                    Object holder = child == null ? null : holderAt.invoke(param.args[0], child);
                    if (holder != null) model = itemAt.invoke(param.args[3], position.invoke(holder));
                    if (blockedMessage(model)) { param.setResult(null); return; }
                    if (model != null) captureModels.put(param, model);
                } catch (Throwable ignored) {}
            }
            @Override protected void afterHookedMethod(MethodHookParam param) {
                Object model = captureModels.remove(param);
                Object result = param.getResult();
                if (result != null && model != null) try {
                    Object action = actionOf.invoke(result);
                    swipeMessages.put(action, new WeakReference<Object>(model));
                    Method invoke = action.getClass().getMethod("a");
                    String invokeId = "recall.swipe.invoke." + invoke.getDeclaringClass().getName();
                    if (invoke.getReturnType() == void.class && !RecallHooks.installed(invokeId)) RecallHooks.hook(invoke,
                            invokeId, new Guard(new Target() {
                        @Override public boolean blocked(Object receiver, List<Object> args) {
                            WeakReference<Object> ref = swipeMessages.get(receiver);
                            return ref != null && blockedMessage(ref.get());
                        }
                    }, false));
                } catch (Throwable t) { Diag.once("[fucklark] recall swipe invoke guard unavailable: " + t.getClass().getSimpleName()); }
            }
        });
        Diag.once("[fucklark] recall swipe policy: retained messages cannot be captured");
    }
}
