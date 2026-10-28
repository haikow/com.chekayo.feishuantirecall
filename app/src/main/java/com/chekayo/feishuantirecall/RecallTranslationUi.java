package com.chekayo.feishuantirecall;

import android.view.View;

import java.lang.reflect.Field;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 翻译控件可能在撤回前已经开始请求，甚至持有旧消息模型。
 * 普通聊天与话题页分别绑定动画、文字标签和入口，撤回后停止内部动画、隐藏入口，
 * 同时在已排队的点击和动画启动前再次查询标记；未撤回/回收复用的行由宿主正常绘制。
 */
final class RecallTranslationUi {
    private static final String BLOCK = "com.ss.android.lark.chat.chatwindow.biz.component.messagelist.render.block.MessageTranslateBlock";
    private static final Map<View, Binding> bindings = new WeakHashMap<View, Binding>();
    static final Map<Object, String> handlerAccounts = Collections.synchronizedMap(new WeakHashMap<Object, String>());
    private static Method stopAnimation;
    private static boolean reportedCleanup;

    static void install(ClassLoader cl) throws Throwable {
        Class<?> animation = cl.loadClass("com.ss.android.lark.translate.cell_ui.TranslationAnimationView");
        // 8.2.6：a() 同时停止子动画并复位运行标志；只清外层动画仍会重新显示加载态。
        Method stop = animation.getDeclaredMethod("a"), start = animation.getDeclaredMethod("c");
        if (stop.getReturnType() != void.class || start.getReturnType() != void.class
                || Modifier.isStatic(stop.getModifiers()) || Modifier.isStatic(start.getModifiers())
                || animation.getDeclaredMethod("b").getReturnType() != boolean.class)
            throw new NoSuchMethodException("translation animation lifecycle");
        stopAnimation = stop;
        Diag.once("[fucklark] translation lifecycle matched: class=" + animation.getName()
                + " stop=" + stop.toGenericString() + " start=" + start.toGenericString());
        RecallHooks.hook(start, "recall.translation.start", new StartHook());
        try { installChat(cl, animation); }
        catch (Throwable t) { Diag.once("[fucklark] recall chat translation unavailable: " + t); }
        try { installThread(cl, animation); }
        catch (Throwable t) { Diag.once("[fucklark] recall thread translation unavailable: " + t); }
        try { installDialogGuard(cl); }
        catch (Throwable t) { Diag.once("[fucklark] recall translation dialog guard unavailable: " + t); }
        Diag.once("[fucklark] recall translation policy: stop loading and disable retained-message entry");
    }

    private static void installChat(ClassLoader cl, Class<?> animation) throws Throwable {
        Class<?> block = cl.loadClass(BLOCK), holder = cl.loadClass(BLOCK + "$TranslateHolder");
        Field view = holder.getField("mTranslateShowOriginLV");
        if (view.getType() != animation || !View.class.isAssignableFrom(animation))
            throw new NoSuchFieldException("translation view shape");
        Method bind = RecallBubbleUi.findBind(block, holder);
        Class<?> model = bind.getParameterTypes()[1];
        Method click = RecallActionGuard.findMethod(block, void.class, holder, model, View.class);
        Method longClick = RecallActionGuard.findMethod(block, boolean.class, holder, model, View.class);
        Diag.once("[fucklark] translation chat anchors matched: bind=" + bind.toGenericString()
                + " click=" + click.toGenericString() + " longClick=" + longClick.toGenericString());
        RecallHooks.hook(bind, "recall.translation.bind", new BindHook(view));
        RecallActionGuard.Target target = new RecallActionGuard.Target() {
            @Override public boolean blocked(Object receiver, java.util.List<Object> args) {
                return RecallActionGuard.blockedMessage(args.get(1));
            }
        };
        // 点击可能在撤回标记出现前已经排队，因此执行前还要做一次拦截。
        RecallHooks.hook(click, "recall.translation.click", new RecallActionGuard.Guard(target, false));
        RecallHooks.hook(longClick, "recall.translation.longClick", new RecallActionGuard.Guard(target, true));
    }

    private static void installThread(ClassLoader cl, Class<?> animation) throws Throwable {
        String name = "com.ss.android.lark.replyinthread.view.block.ReplyInThreadTranslateBlock";
        Class<?> block = cl.loadClass(name), holder = cl.loadClass(name + "$ReplyInThreadTranslateViewHolder");
        Field view = holder.getField("translateShowOriginLv");
        Field label = holder.getField("mTranslateShowOriginTV"), container = holder.getField("vLayoutTranslate");
        if (view.getType() != animation || !View.class.isAssignableFrom(label.getType())
                || !View.class.isAssignableFrom(container.getType())) throw new NoSuchFieldException("thread translation views");
        Method bind = RecallBubbleUi.findBind(block, holder);
        Diag.once("[fucklark] translation thread bind matched: " + bind.toGenericString());
        RecallHooks.hook(bind, "recall.translation.thread.bind", new BindHook(view, label, container));
        Class<?> click = cl.loadClass(name + "$a");
        Field captured = click.getDeclaredField("b");
        if (captured.getType() != bind.getParameterTypes()[1]) throw new NoSuchFieldException("thread translation model");
        captured.setAccessible(true);
        RecallHooks.hook(click.getDeclaredMethod("onSingleClick", View.class), "recall.translation.thread.click",
                new RecallActionGuard.Guard(new CapturedTarget(captured), false));
    }

    static final class CapturedTarget implements RecallActionGuard.Target {
        final Field model;
        CapturedTarget(Field model) { this.model = model; }
        public boolean blocked(Object receiver, java.util.List<Object> args) throws Throwable {
            return RecallActionGuard.blockedMessage(model.get(receiver));
        }
    }

    private static void installDialogGuard(ClassLoader cl) throws Throwable {
        // 对话框处理器可能跨账号存活：构造时记下账号，真正发起翻译前校验目标消息。
        Class<?> type = cl.loadClass("com.ss.android.lark.chat.chatwindow.chat.menu.handler.TranslateViewHandler");
        Class<?> model = cl.loadClass("com.ss.android.lark.chat.chatwindow.chat.menu.handler.TranslateModelHandler");
        Field found = null;
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.getType() != model) continue;
            if (found != null) throw new NoSuchFieldException("ambiguous translation model");
            found = field;
        }
        if (found == null) throw new NoSuchFieldException("translation model");
        final Field field = found;
        field.setAccessible(true);
        final Method lookup = model.getMethod("getMessageInfoById", String.class);
        final Method message = lookup.getReturnType().getMethod("getMessage");
        if (message.getReturnType() != cl.loadClass("com.ss.android.lark.chat.entity.message.Message"))
            throw new NoSuchMethodException("translation message lookup");
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            RecallHooks.hook(constructor, "recall.translation.owner." + constructor.toString(), new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try { handlerAccounts.put(param.thisObject, RecallMessages.account()); }
                    catch (Throwable ignored) {}
                }
            });
        }
        final DialogTarget target = new DialogTarget(field, lookup, message);
        // 8.2.6 三个入口参数均包含 messageId；k 在 l 前改目标，n 在请求前切换状态。
        for (String name : new String[]{"k", "l", "n"}) {
            Method entry = "k".equals(name)
                    ? type.getDeclaredMethod(name, View.class, String.class, String.class)
                    : type.getDeclaredMethod(name, View.class, String.class, String.class, int.class);
            if (entry.getReturnType() != void.class || Modifier.isStatic(entry.getModifiers()))
                throw new NoSuchMethodException("translation dialog entry");
            RecallHooks.hook(entry, "recall.translation.dialog." + name, new RecallActionGuard.Guard(target, false));
        }
    }

    static final class DialogTarget implements RecallActionGuard.Target {
        final Field model;
        final Method lookup, message;
        DialogTarget(Field model, Method lookup, Method message) {
            this.model = model; this.lookup = lookup; this.message = message;
        }
        @Override public boolean blocked(Object receiver, java.util.List<Object> args) throws Throwable {
            if (AntiRecall.TAMPER == 2) return false;
            String uid = handlerAccounts.get(receiver), id = (String) args.get(2);
            if (uid == null) uid = RecallMessages.account();
            if (RecallMessages.markedMessage(uid, id)) return true;
            Object info = lookup.invoke(model.get(receiver), id);
            Object target = info == null ? null : message.invoke(info);
            return target != null && RecallMessages.marked(uid, RecallMessages.messageText(target, "getChatId", ""), id);
        }
    }

    static final class BindHook extends XC_MethodHook {
        private final Field field;
        private final Field[] extras;
        BindHook(Field field, Field... extras) { this.field = field; this.extras = extras; }
        @Override protected void beforeHookedMethod(MethodHookParam param) {
            View view = null;
            Binding binding = null;
            try {
                view = (View) field.get(param.args[0]);
                View[] controls = new View[extras.length];
                for (int i = 0; i < extras.length; i++) controls[i] = (View) extras[i].get(param.args[0]);
                binding = track(view, param.args[1], controls);
                if (binding != null && binding.suppress(view, RecallMessages.account())) { param.setResult(null); return; }
            } catch (Throwable ignored) {}
        }
        @Override protected void afterHookedMethod(MethodHookParam param) {
            // 撤回可能恰好发生在宿主 bind 过程中，故原方法后再查一次。
            // 普通或复用行保留宿主自己的可见性与点击监听器。
            try {
                View view = (View) field.get(param.args[0]);
                Binding binding = bindings.get(view);
                if (binding != null) binding.suppress(view, RecallMessages.account());
            }
            catch (Throwable ignored) {}
        }
    }

    static final class StartHook extends XC_MethodHook {
        @Override protected void beforeHookedMethod(MethodHookParam param) {
            try {
                View view = (View) param.thisObject;
                Binding binding = bindings.get(view);
                if (binding != null && binding.suppress(view, RecallMessages.account())) param.setResult(null);
            } catch (Throwable ignored) {}
        }
    }

    private static Binding track(View view, Object model, View[] controls) throws Throwable {
        // ViewHolder 复用时先解除旧消息监听；控件仅用弱引用，避免保活离屏列表。
        if (view == null) return null;
        Binding previous = bindings.remove(view);
        if (previous != null) previous.dispose();
        String uid = RecallMessages.account();
        String chat = RecallMessages.messageText(model, "getChatId", "");
        String id = RecallMessages.messageText(model, "getId", "");
        if (RecallMessages.key(uid, chat, id) == null) return null;
        Binding binding = new Binding(view, uid, chat, id, controls);
        bindings.put(view, binding);
        binding.watch();
        RecallBubbleUi.schedule();
        return binding;
    }

    // 与气泡共用刷新定时器：SQL 层撤回即使没触发宿主重绑，也能清除旧翻译控件。
    static boolean refresh(String uid) {
        boolean active = false;
        for (Map.Entry<View, Binding> item : new ArrayList<Map.Entry<View, Binding>>(bindings.entrySet())) {
            View view = item.getKey();
            if (view == null || !item.getValue().visible()) continue;
            try {
                if (!item.getValue().suppress(view, uid)) active |= Config.antirecall && AntiRecall.TAMPER != 2;
            } catch (Throwable ignored) {}
        }
        return active;
    }

    static final class Binding extends RecallVisibility {
        final String uid, chat, id;
        final java.util.List<WeakReference<View>> controls = new ArrayList<WeakReference<View>>();
        Binding(View view, String uid, String chat, String id, View[] extras) {
            super(view); this.uid = uid; this.chat = chat; this.id = id;
            for (View extra : extras) controls.add(new WeakReference<View>(extra));
        }
        @Override boolean visible() {
            if (super.visible()) return true;
            for (WeakReference<View> ref : controls) if (RecallVisibility.visible(ref.get())) return true;
            return false;
        }
        boolean blocked(String activeUid) {
            return AntiRecall.TAMPER != 2 && uid.equals(activeUid) && RecallMessages.marked(uid, chat, id);
        }
        boolean suppress(View view, String activeUid) throws Throwable {
            // 已撤回只清翻译 UI，不遮挡消息正文/云文档/媒体本身的查看入口。
            if (!blocked(activeUid)) return false;
            stopAnimation.invoke(view);
            hide(view);
            for (WeakReference<View> ref : controls) {
                View extra = ref.get();
                if (extra != null) hide(extra);
            }
            if (!reportedCleanup) {
                reportedCleanup = true;
                Diag.once("[fucklark] recall translation: animation stopped, entry cleared");
            }
            return true;
        }
        private static void hide(View view) {
            view.setVisibility(View.GONE);
            view.setOnClickListener(null);
            view.setOnLongClickListener(null);
            view.setClickable(false);
            view.setLongClickable(false);
        }
        @Override public void onViewAttachedToWindow(View view) {
            super.onViewAttachedToWindow(view);
            try { suppress(view, RecallMessages.account()); } catch (Throwable ignored) {}
            RecallBubbleUi.schedule();
        }
        @Override public void onViewDetachedFromWindow(View view) {
            super.onViewDetachedFromWindow(view);
            try { suppress(view, RecallMessages.account()); } catch (Throwable ignored) {}
        }
    }
}
