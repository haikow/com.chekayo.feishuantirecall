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

import io.github.libxposed.api.XposedInterface;

/** 撤回后停止翻译动画并移除入口，兼顾普通聊天与话题回复页。 */
final class RecallTranslationUi {
    private static final String BLOCK = "com.ss.android.lark.chat.chatwindow.biz.component.messagelist.render.block.MessageTranslateBlock";
    private static final Map<View, Binding> bindings = new WeakHashMap<View, Binding>();
    static final Map<Object, String> handlerAccounts = Collections.synchronizedMap(new WeakHashMap<Object, String>());
    private static Method stopAnimation;
    private static boolean reportedCleanup;

    static void install(ClassLoader cl) throws Throwable {
        Class<?> animation = cl.loadClass("com.ss.android.lark.translate.cell_ui.TranslationAnimationView");
        // 8.2.6: a() clears the CHILD animation and resets the running flag;
        // clearing the outer FrameLayout's animation alone does neither.
        Method stop = animation.getDeclaredMethod("a"), start = animation.getDeclaredMethod("c");
        if (stop.getReturnType() != void.class || start.getReturnType() != void.class
                || Modifier.isStatic(stop.getModifiers()) || Modifier.isStatic(start.getModifiers())
                || animation.getDeclaredMethod("b").getReturnType() != boolean.class)
            throw new NoSuchMethodException("translation animation lifecycle");
        stopAnimation = stop;
        HookRuntime.hook(start, "recall.translation.start", new StartHook());
        try { installChat(cl, animation); }
        catch (Throwable t) { Diag.once("[fucklark] recall chat translation unavailable: " + t); }
        try { installThread(cl, animation); }
        catch (Throwable t) { Diag.once("[fucklark] recall thread translation unavailable: " + t); }
        try { installDialogGuard(cl); }
        catch (Throwable t) { Diag.once("[fucklark] recall translation dialog guard unavailable: " + t); }
        HotReloadSafety.markExternalCallback("recall.translation.attach-refresh");
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
        HookRuntime.hook(bind, "recall.translation.bind", new BindHook(view));
        RecallActionGuard.Target target = new RecallActionGuard.Target() {
            @Override public boolean blocked(Object receiver, java.util.List<Object> args) {
                return RecallActionGuard.blockedMessage(args.get(1));
            }
        };
        // Cover a click queued just before the recall marker became available.
        HookRuntime.hook(click, "recall.translation.click", new RecallActionGuard.Guard(target, false));
        HookRuntime.hook(longClick, "recall.translation.longClick", new RecallActionGuard.Guard(target, true));
    }

    private static void installThread(ClassLoader cl, Class<?> animation) throws Throwable {
        String name = "com.ss.android.lark.replyinthread.view.block.ReplyInThreadTranslateBlock";
        Class<?> block = cl.loadClass(name), holder = cl.loadClass(name + "$ReplyInThreadTranslateViewHolder");
        Field view = holder.getField("translateShowOriginLv");
        Field label = holder.getField("mTranslateShowOriginTV"), container = holder.getField("vLayoutTranslate");
        if (view.getType() != animation || !View.class.isAssignableFrom(label.getType())
                || !View.class.isAssignableFrom(container.getType())) throw new NoSuchFieldException("thread translation views");
        Method bind = RecallBubbleUi.findBind(block, holder);
        HookRuntime.hook(bind, "recall.translation.thread.bind", new BindHook(view, label, container));
        Class<?> click = cl.loadClass(name + "$a");
        Field captured = click.getDeclaredField("b");
        if (captured.getType() != bind.getParameterTypes()[1]) throw new NoSuchFieldException("thread translation model");
        captured.setAccessible(true);
        HookRuntime.hook(click.getDeclaredMethod("onSingleClick", View.class), "recall.translation.thread.click",
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
            HookRuntime.hook(constructor, "recall.translation.owner." + constructor.toString(), new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    try { handlerAccounts.put(chain.getThisObject(), RecallMessages.account()); }
                    catch (Throwable ignored) {}
                    return result;
                }
            });
        }
        final DialogTarget target = new DialogTarget(field, lookup, message);
        // APK 8.2.6: these take (anchor, language, messageId[, source]).
        // k mutates the target before l; n toggles translation before dispatch.
        for (String name : new String[]{"k", "l", "n"}) {
            Method entry = "k".equals(name)
                    ? type.getDeclaredMethod(name, View.class, String.class, String.class)
                    : type.getDeclaredMethod(name, View.class, String.class, String.class, int.class);
            if (entry.getReturnType() != void.class || Modifier.isStatic(entry.getModifiers()))
                throw new NoSuchMethodException("translation dialog entry");
            HookRuntime.hook(entry, "recall.translation.dialog." + name, new RecallActionGuard.Guard(target, false));
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

    static final class BindHook implements XposedInterface.Hooker {
        private final Field field;
        private final Field[] extras;
        BindHook(Field field, Field... extras) { this.field = field; this.extras = extras; }
        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
            View view = null;
            Binding binding = null;
            try {
                view = (View) field.get(chain.getArg(0));
                View[] controls = new View[extras.length];
                for (int i = 0; i < extras.length; i++) controls[i] = (View) extras[i].get(chain.getArg(0));
                binding = track(view, chain.getArg(1), controls);
                if (binding != null && binding.suppress(view, RecallMessages.account())) return null;
            } catch (Throwable ignored) {}
            Object result = chain.proceed();
            // A recall can arrive during binding. Normal and recycled rows use
            // the host bind unchanged, including its visibility and listeners.
            try { if (binding != null) binding.suppress(view, RecallMessages.account()); }
            catch (Throwable ignored) {}
            return result;
        }
    }

    static final class StartHook implements XposedInterface.Hooker {
        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
            try {
                View view = (View) chain.getThisObject();
                Binding binding = bindings.get(view);
                if (binding != null && binding.suppress(view, RecallMessages.account())) return null;
            } catch (Throwable ignored) {}
            return chain.proceed();
        }
    }

    private static Binding track(View view, Object model, View[] controls) throws Throwable {
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
