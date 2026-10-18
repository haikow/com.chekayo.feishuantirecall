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
import io.github.libxposed.api.XposedInterface;

/** 保留的原文只供查看/复制/下载，不再作为回复、转发或表情的服务端目标。 */
final class RecallActionGuard {
    private static final String MESSAGE = "com.ss.android.lark.chat.entity.message.Message";
    private static final String CONTEXT = "com.ss.android.lark.biz.im.extension.message_action.a";
    private static long lastNotice;
    private static final Map<Object, WeakReference<Object>> actionContexts =
            Collections.synchronizedMap(new WeakHashMap<Object, WeakReference<Object>>());
    private static final Map<Object, WeakReference<Object>> swipeMessages =
            Collections.synchronizedMap(new WeakHashMap<Object, WeakReference<Object>>());

    // Content access only. Unknown/new menu types are not safe for a recalled target.
    static boolean allowedAction(String type) {
        return "COPY".equals(type) || "COPY_IMAGE_AND_MEDIA".equals(type)
                || "COPY_URL_LINK".equals(type) || "ZONE_COPY".equals(type)
                || "SELECT_TEXT".equals(type) || "SAVE_TO".equals(type)
                || "MUTE_PLAY_VIDEO".equals(type) || "SELECT_AUDIO_PLAY_MODE".equals(type);
    }
    static boolean blockedAction(Object action, Object message) {
        if (!blockedMessage(message)) return false;
        try {
            Object type = Reflect.callMethod(action, "getType");
            return !(type instanceof Enum) || !allowedAction(((Enum<?>) type).name());
        } catch (Throwable ignored) { return true; }
    }

    static boolean blockedMessage(Object message) {
        if (message == null || AntiRecall.TAMPER == 2) return false;
        try {
            return RecallMessages.marked(RecallMessages.account(),
                    RecallMessages.value(message, "getChatId"), RecallMessages.value(message, "getId"));
        } catch (Throwable ignored) { return false; }
    }
    static boolean blockedId(String id, String chat) {
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
    static final class Guard implements XposedInterface.Hooker {
        final Target target;
        final boolean predicate;
        Guard(Target target, boolean predicate) { this.target = target; this.predicate = predicate; }
        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
            boolean blocked = false;
            try { blocked = target.blocked(chain.getThisObject(), chain.getArgs()); }
            catch (Throwable ignored) {}
            if (!blocked) return chain.proceed();
            if (predicate) return false;
            notice();
            return null;
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
    static void install(ClassLoader cl) {
        try { installMenuPolicy(cl); }
        catch (Throwable t) { Diag.once("[fucklark] recall menu policy unavailable: " + t); }
        try { installSwipePolicy(cl); }
        catch (Throwable t) { Diag.once("[fucklark] recall swipe policy unavailable: " + t); }
        try { installThreadReactions(cl.loadClass("com.ss.android.lark.replyinthread.pages.normal.ReplyInThreadViewModel"), cl.loadClass(MESSAGE)); }
        catch (Throwable t) { Diag.once("[fucklark] recall thread reaction guard unavailable: " + t); }
        String[] actions = {
            "com.ss.android.lark.chat.message.menu.provider.ChatReplyActionProvider$ReplyMessageAction",
            "com.ss.android.lark.chat.messagemenu.provider.ChatThreadReplyActionProvider$ReplyMessageAction",
            "com.ss.android.lark.chat.message.menu.provider.ReactionActionProvider$ReactionMessageAction"
        };
        for (String name : actions) try {
            Class<?> action = cl.loadClass(name), context = cl.loadClass(CONTEXT);
            final Method message = findMessageGetter(context, cl.loadClass(MESSAGE));
            Field found = null;
            for (Field f : action.getDeclaredFields()) if (!Modifier.isStatic(f.getModifiers()) && f.getType() == context) {
                if (found != null) throw new NoSuchFieldException("ambiguous action context");
                found = f;
            }
            if (found == null) throw new NoSuchFieldException("action context");
            final Field field = found;
            field.setAccessible(true);
            Target target = new Target() {
                @Override public boolean blocked(Object receiver, java.util.List<Object> args) throws Throwable {
                    return blockedMessage(message.invoke(field.get(receiver)));
                }
            };
            for (String predicate : new String[]{"isVisible", "isEnable"}) {
                Method m = action.getDeclaredMethod(predicate);
                if (m.getReturnType() != boolean.class) throw new NoSuchMethodException(predicate);
                HookRuntime.hook(m, "recall.action." + name + "." + predicate, new Guard(target, true));
            }
            Method click = action.getDeclaredMethod("a", View.class, boolean.class);
            if (click.getReturnType() != void.class) throw new NoSuchMethodException("action click");
            HookRuntime.hook(click, "recall.action." + name + ".click", new Guard(target, false));
            Diag.once("[fucklark] recall action guard: " + name);
        } catch (Throwable t) { Diag.once("[fucklark] recall action guard unavailable: " + name + ": " + t); }

        for (String name : new String[]{
                "com.ss.android.lark.chat.chatwindow.chat.menu.handler.ReactionHandler",
                "com.ss.android.lark.chat.chatwindow.chat.message.actionimpl.MessageReactionHandlerImpl"}) try {
            Class<?> type = cl.loadClass(name);
            Method found = null;
            for (Method m : type.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers()) || m.getParameterTypes().length != 1
                        || m.getParameterTypes()[0] != String.class) continue;
                try { if (m.getReturnType().getMethod("getMessage").getReturnType() != cl.loadClass(MESSAGE)) continue; }
                catch (NoSuchMethodException ignored) { continue; }
                if (found != null) throw new NoSuchMethodException("ambiguous reaction message lookup");
                found = m;
            }
            if (found == null) throw new NoSuchMethodException("reaction message lookup");
            final Method lookup = found;
            lookup.setAccessible(true);
            final Method getMessage = lookup.getReturnType().getMethod("getMessage");
            Target target = new Target() {
                @Override public boolean blocked(Object receiver, java.util.List<Object> args) throws Throwable {
                    String id = (String) args.get(0);
                    if (blockedId(id, null)) return true;
                    Object info = lookup.invoke(receiver, id);
                    return info != null && blockedMessage(getMessage.invoke(info));
                }
            };
            // APK 8.2.6: first argument is messageId; I2 toggles a reaction,
            // e9 opens the reaction detail/more panel. Block before host side effects.
            Method toggle = type.getDeclaredMethod("I2", String.class, String.class, int.class);
            Method more = type.getDeclaredMethod("e9", String.class, String.class);
            if (toggle.getReturnType() != void.class || more.getReturnType() != void.class)
                throw new NoSuchMethodException("reaction entry shape");
            HookRuntime.hook(toggle, "recall.action." + name + ".toggle", new Guard(target, false));
            HookRuntime.hook(more, "recall.action." + name + ".more", new Guard(target, false));
            Diag.once("[fucklark] recall reaction guard: " + name);
        } catch (Throwable t) { Diag.once("[fucklark] recall reaction guard unavailable: " + name + ": " + t); }
    }

    static void installThreadReactions(Class<?> type, Class<?> message) throws Throwable {
        final Method lookup = type.getMethod("getMessageInfoById", String.class);
        final Method getMessage = lookup.getReturnType().getMethod("getMessage");
        if (getMessage.getReturnType() != message) throw new NoSuchMethodException("thread reaction message");
        Target target = new Target() {
            public boolean blocked(Object receiver, List<Object> args) throws Throwable {
                // 8.2.6: (messageId, reactionType), including the existing reaction bar.
                String id = (String) args.get(0);
                if (blockedId(id, null)) return true;
                Object info = lookup.invoke(receiver, id);
                if (info != null && blockedMessage(getMessage.invoke(info))) return true;
                return blockedId(id, RecallMessages.value(receiver, "getChatId"));
            }
        };
        for (String name : new String[]{"addReaction", "deleteReaction"}) {
            Method method = type.getDeclaredMethod(name, String.class, String.class);
            if (method.getReturnType() != void.class || Modifier.isStatic(method.getModifiers()))
                throw new NoSuchMethodException("thread reaction entry");
            HookRuntime.hook(method, "recall.thread." + name, new Guard(target, false));
        }
    }

    /** 统一筛选消息菜单，新增的未知操作默认不可用于已撤回目标。 */
    private static void installMenuPolicy(ClassLoader cl) throws Throwable {
        Class<?> context = cl.loadClass(CONTEXT);
        Class<?> action = cl.loadClass("com.ss.android.lark.biz.im.extension.message_action.j");
        final Method message = findMessageGetter(context, cl.loadClass(MESSAGE));
        Class<?> manager = cl.loadClass("com.ss.android.lark.chat.message.menu.MessageActionManager");
        Method filter = findMethod(manager, boolean.class, action, context, List.class);
        HookRuntime.hook(filter, "recall.menu.policy", new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object item = chain.getArg(0), ctx = chain.getArg(1);
                try {
                    actionContexts.put(item, new WeakReference<Object>(ctx));
                    // Also guard menus opened before the recall arrived. Host callbacks
                    // remain intact for permitted actions and normal messages.
                    Method click = item.getClass().getMethod("a", View.class, boolean.class);
                    String clickId = "recall.menu.click." + click.getDeclaringClass().getName();
                    if (click.getReturnType() == void.class && HookRuntime.getByLogicalId(clickId) == null) HookRuntime.hook(click,
                            clickId, new Guard(new Target() {
                        @Override public boolean blocked(Object receiver, List<Object> args) throws Throwable {
                            WeakReference<Object> ref = actionContexts.get(receiver);
                            Object ctx = ref == null ? null : ref.get();
                            return ctx != null && blockedAction(receiver, message.invoke(ctx));
                        }
                    }, false));
                } catch (Throwable t) { Diag.once("[fucklark] recall menu click guard unavailable: " + t.getClass().getSimpleName()); }
                try {
                    if (blockedAction(item, message.invoke(ctx))) return true; // host: true = exclude
                } catch (Throwable ignored) {}
                return chain.proceed();
            }
        });
        Diag.once("[fucklark] recall menu policy: content access only");
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
        HookRuntime.hook(capture, "recall.swipe.capture", new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object model = null;
                try {
                    MotionEvent event = (MotionEvent) chain.getArg(1);
                    Object child = childAt.invoke(chain.getArg(0), event.getX(), event.getY());
                    Object holder = child == null ? null : holderAt.invoke(chain.getArg(0), child);
                    if (holder != null) model = itemAt.invoke(chain.getArg(3), position.invoke(holder));
                    if (blockedMessage(model)) return null; // host already handles unavailable capture
                } catch (Throwable ignored) {}
                Object result = chain.proceed();
                if (result != null && model != null) try {
                    Object action = actionOf.invoke(result);
                    swipeMessages.put(action, new WeakReference<Object>(model));
                    Method invoke = action.getClass().getMethod("a");
                    String invokeId = "recall.swipe.invoke." + invoke.getDeclaringClass().getName();
                    if (invoke.getReturnType() == void.class && HookRuntime.getByLogicalId(invokeId) == null) HookRuntime.hook(invoke,
                            invokeId, new Guard(new Target() {
                        @Override public boolean blocked(Object receiver, List<Object> args) {
                            WeakReference<Object> ref = swipeMessages.get(receiver);
                            return ref != null && blockedMessage(ref.get());
                        }
                    }, false));
                } catch (Throwable t) { Diag.once("[fucklark] recall swipe invoke guard unavailable: " + t.getClass().getSimpleName()); }
                return result;
            }
        });
        Diag.once("[fucklark] recall swipe policy: retained messages cannot be captured");
    }
}
