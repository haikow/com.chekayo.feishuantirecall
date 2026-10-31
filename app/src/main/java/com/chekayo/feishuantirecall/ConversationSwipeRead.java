package com.chekayo.feishuantirecall;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Toast;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** 会话左右滑动的本地“标为已读”动作；宿主设置和清除角标动作保持独立。 */
final class ConversationSwipeRead {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final String ROOT = "com.ss.android.lark.feed.";
    private static final String PREFS = "feishukit_conversation_swipe";
    private static final String LABEL = "标为已读";
    private static final Map<Object, Settings> CUSTOM = Collections.synchronizedMap(new WeakHashMap<Object, Settings>());
    private static final java.util.Set<Object> HELPERS = Collections.newSetFromMap(new WeakHashMap<Object, Boolean>());
    private static volatile Class<?> baseView, cardType;
    private static final class Settings {
        final WeakReference<Activity> activity;
        final String account;
        final int direction;
        final WeakReference<List<Object>> actions;
        Settings(Activity activity, List<Object> actions) {
            this.activity = new WeakReference<Activity>(activity); this.actions = new WeakReference<List<Object>>(actions);
            account = RecallMessages.account(); direction = activity.getIntent().getIntExtra("key_setting_type", 0);
        }
    }

    static void install(final ClassLoader loader) {
        try { ConversationReadService.install(loader); }
        catch (Throwable error) { Diag.once("[fucklark] conversation-read service unavailable: " + error); }
        try {
            baseView = loader.loadClass(ROOT + "shared.page.swipe.BaseFeedSwipeActionView");
            cardType = loader.loadClass(ROOT + "app.entity.UIFeedCard");
            installSettings(loader);
            Class<?> helper = loader.loadClass(ROOT + "shared.page.binder2.SwipeViewHelper");
            for (Constructor<?> constructor : helper.getDeclaredConstructors())
                ConversationSwipeHooks.hook(constructor, new ConversationSwipeHooks.Callback() {
                    @Override void after(ConversationSwipeHooks.Param p) {
                        synchronized (HELPERS) { HELPERS.add(p.thisObject); }
                    }
                });
            Thread scanner = new Thread(new Runnable() {
                @Override public void run() {
                    try { installBinder(findBinder(loader)); }
                    catch (Throwable error) { Diag.once("[fucklark] conversation swipe discovery failed: " + error); }
                }
            }, "conversation-swipe-anchor");
            scanner.setDaemon(true); scanner.start();
        } catch (Throwable error) { Diag.once("[fucklark] conversation swipe settings unavailable: " + error); }
    }

    private static void installSettings(ClassLoader loader) throws Throwable {
        final Class<?> activity = loader.loadClass(ROOT + "app.setting.swipe.FeedSwipeSettingDetailActivity");
        final Class<?> actionType = loader.loadClass(ROOT + "app.setting.swipe.actions.SwipeAction");
        Method create = activity.getDeclaredMethod("onCreate", Bundle.class);
        ConversationSwipeHooks.hook(create, new ConversationSwipeHooks.Callback() {
            @Override void before(ConversationSwipeHooks.Param p) throws Throwable {
                for (Field field : activity.getDeclaredFields()) {
                    if (!List.class.isAssignableFrom(field.getType())) continue;
                    field.setAccessible(true);
                    Object value = field.get(p.thisObject);
                    if (!(value instanceof List) || ((List<?>) value).isEmpty()) continue;
                    @SuppressWarnings("unchecked") List<Object> actions = (List<Object>) value;
                    if (!actionType.isInstance(actions.get(0))) continue;
                    for (Object action : actions) if (CUSTOM.containsKey(action)) return;
                    Object original = null;
                    for (Object action : actions) {
                        for (Method method : action.getClass().getDeclaredMethods()) {
                            if (method.getParameterTypes().length == 0 && method.getReturnType().isEnum()) {
                                method.setAccessible(true);
                                if ("MARK_OR_CLEAR_UNREAD".equals(String.valueOf(method.invoke(action)))) original = action;
                            }
                        }
                    }
                    if (original == null) throw new NoSuchMethodException("没有原生滑动动作样式");
                    Constructor<?> constructor = original.getClass().getDeclaredConstructor(); constructor.setAccessible(true);
                    Object custom = constructor.newInstance();
                    Settings state = new Settings((Activity) p.thisObject, actions);
                    CUSTOM.put(custom, state);
                    installActionPresentation(custom.getClass());
                    actions.add(custom);
                    Diag.w("会话滑动: 已追加标为已读选项 direction=" + state.direction);
                    break;
                }
            }
        });
        Class<?> adapter = loader.loadClass("androidx.recyclerview.widget.RecyclerView$Adapter");
        Class<?> holder = loader.loadClass("androidx.recyclerview.widget.RecyclerView$ViewHolder");
        for (Method method : ConversationSwipeSettingsDiscovery.find(activity, adapter, holder)) {
            Diag.once("[fucklark] conversation-read settings binder matched: " + method.toGenericString());
            ConversationSwipeHooks.hook(method, new ConversationSwipeHooks.Callback() {
                @Override void after(ConversationSwipeHooks.Param p) throws Throwable {
                    Settings state = stateForAdapter(p.thisObject);
                    if (state == null) return;
                    int index = ((Integer) p.args[1]).intValue();
                    List<Object> actions = state.actions.get();
                    if (actions == null || index < 0 || index >= actions.size()) return;
                    View root = itemView(p.args[0]);
                    if (root == null) return;
                    View check = checkbox(root);
                    if (check == null) return;
                    boolean custom = CUSTOM.containsKey(actions.get(index));
                    if (custom) {
                        checked(check, selected(state.account, state.direction));
                        Diag.once("[fucklark] conversation-read settings row bound direction=" + state.direction);
                    }
                    else if (state.direction == 1 && selected(state.account, 1)) checked(check, false);
                    View.OnClickListener original = listener(check);
                    if (original instanceof SettingsClick) original = ((SettingsClick) original).original;
                    check.setOnClickListener(new SettingsClick(state, p.thisObject, original, custom));
                }
            });
        }
    }
    private static void installActionPresentation(Class<?> type) {
        for (Method method : type.getDeclaredMethods()) {
            if (Modifier.isStatic(method.getModifiers()) || method.getParameterTypes().length != 0) continue;
            if (method.getReturnType() == String.class) {
                ConversationSwipeHooks.hook(method, new ConversationSwipeHooks.Callback() {
                    @Override void before(ConversationSwipeHooks.Param p) { if (CUSTOM.containsKey(p.thisObject)) p.result(LABEL); }
                });
            } else if (Drawable.class.isAssignableFrom(method.getReturnType())) {
                ConversationSwipeHooks.hook(method, new ConversationSwipeHooks.Callback() {
                    @Override void before(ConversationSwipeHooks.Param p) {
                        Settings state = CUSTOM.get(p.thisObject);
                        Activity activity = state == null ? null : state.activity.get();
                        if (activity != null) p.result(new ReadReceiptDrawable(Color.rgb(45, 138, 95),
                                activity.getResources().getDisplayMetrics().density));
                    }
                });
            } else if (method.getReturnType().isEnum()) {
                final Object unknown;
                try { unknown = method.getReturnType().getField("UNKNOWN_ACTION_TYPE").get(null); }
                catch (Exception absent) { continue; }
                ConversationSwipeHooks.hook(method, new ConversationSwipeHooks.Callback() {
                    @Override void before(ConversationSwipeHooks.Param p) {
                        // 仅用作本地设置行占位，永远不写入宿主服务端动作列表。
                        if (CUSTOM.containsKey(p.thisObject)) p.result(unknown);
                    }
                });
            }
        }
    }
    private static final class SettingsClick implements View.OnClickListener {
        final Settings state;
        final Object adapter;
        final View.OnClickListener original;
        final boolean custom;
        SettingsClick(Settings state, Object adapter, View.OnClickListener original, boolean custom) {
            this.state = state; this.adapter = adapter; this.original = original; this.custom = custom;
        }
        @Override public void onClick(View view) {
            try {
                if (!state.account.equals(RecallMessages.account()) || state.account.isEmpty()) {
                    notice("账号已变化，请重新打开滑动设置"); return;
                }
                if (custom) {
                    boolean enabled = !selected(state.account, state.direction);
                    save(state.account, state.direction, enabled);
                    checked(view, enabled);
                } else {
                    if (state.direction == 1) save(state.account, 1, false);
                    if (original != null) original.onClick(view);
                }
                adapter.getClass().getMethod("notifyDataSetChanged").invoke(adapter);
            } catch (Throwable error) {
                Diag.once("[fucklark] conversation swipe selection failed: " + AntiRecall.describe(error));
                notice("滑动设置保存失败，请重新打开后重试");
            }
        }
    }

    static Class<?> findBinder(ClassLoader loader) throws Throwable {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<String>();
        Class<?> dexLoader = Class.forName("dalvik.system.BaseDexClassLoader");
        Field pathList = dexLoader.getDeclaredField("pathList"); pathList.setAccessible(true);
        for (ClassLoader current = loader; current != null; current = current.getParent()) {
            if (!dexLoader.isInstance(current)) continue;
            Object paths = pathList.get(current);
            Field elements = paths.getClass().getDeclaredField("dexElements"); elements.setAccessible(true);
            for (Object element : (Object[]) elements.get(paths)) {
                Field file = element.getClass().getDeclaredField("dexFile"); file.setAccessible(true);
                Object dex = file.get(element); if (dex == null) continue;
                java.util.Enumeration<?> entries = (java.util.Enumeration<?>) dex.getClass().getMethod("entries").invoke(dex);
                while (entries.hasMoreElements()) {
                    String name = String.valueOf(entries.nextElement());
                    if (name.startsWith(ROOT + "shared.page.binder2.") && name.indexOf('$') < 0) names.add(name);
                }
            }
        }
        Class<?> found = null;
        for (String name : names) try {
            Class<?> candidate = Class.forName(name, false, loader);
            Method left = binding(candidate, false), right = binding(candidate, true);
            if (left == null || right == null) continue;
            if (found != null) throw new IllegalStateException("会话滑动绑定候选不唯一");
            found = candidate;
        } catch (LinkageError unavailable) { Diag.w("会话滑动: 跳过不可加载类 " + name); }
        if (found == null) throw new NoSuchMethodException("会话滑动绑定候选为空");
        return found;
    }
    private static Method binding(Class<?> type, boolean right) {
        Method found = null;
        for (Method method : type.getDeclaredMethods()) {
            if (method.isBridge() || method.isSynthetic() || Modifier.isStatic(method.getModifiers())) continue;
            Class<?>[] args = method.getParameterTypes();
            if (args.length != (right ? 5 : 4) || args[0] != cardType) continue;
            if (right ? method.getReturnType() != void.class || args[4] != baseView
                    : !List.class.isAssignableFrom(method.getReturnType())) continue;
            if (found != null) return null;
            method.setAccessible(true); found = method;
        }
        return found;
    }
    private static void installBinder(final Class<?> binder) {
        ConversationSwipeHooks.hook(binding(binder, false), new ConversationSwipeHooks.Callback() {
            @Override void after(ConversationSwipeHooks.Param p) throws Throwable {
                Object card = p.args[0];
                if (!selected(RecallMessages.account(), 0) || !isChat(card) || !(p.result() instanceof List)) return;
                ViewGroup parent = null;
                for (Field field : binder.getDeclaredFields()) if (ViewGroup.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    if (parent != null) throw new IllegalStateException("滑动容器不唯一");
                    parent = (ViewGroup) field.get(p.thisObject);
                }
                if (parent == null) return;
                List<?> nativeViews = (List<?>) p.result();
                View custom = parent.getChildAt(nativeViews.size());
                if (custom == null) {
                    for (Method method : binder.getDeclaredMethods()) {
                        if (method.getParameterTypes().length == 0 && method.getReturnType() == baseView) {
                            method.setAccessible(true); custom = (View) method.invoke(p.thisObject); break;
                        }
                    }
                    if (custom == null) return;
                    parent.addView(custom, new LinearLayout.LayoutParams(-2, -1));
                }
                decorate(custom, card, p.args[3], false);
                List<Object> views = new ArrayList<Object>(nativeViews); views.add(custom);
                parent.measure(View.MeasureSpec.makeMeasureSpec(1073741823, Integer.MIN_VALUE),
                        View.MeasureSpec.makeMeasureSpec(1073741823, Integer.MIN_VALUE));
                p.result(views);
            }
        });
        ConversationSwipeHooks.hook(binding(binder, true), new ConversationSwipeHooks.Callback() {
            @Override void after(ConversationSwipeHooks.Param p) throws Throwable {
                if (selected(RecallMessages.account(), 1) && isChat(p.args[0]))
                    decorate((View) p.args[4], p.args[0], p.args[3], true);
            }
        });
        Diag.once("[fucklark] conversation swipe matched: " + binder.getName());
    }

    private static void decorate(final View view, final Object card, final Object status, boolean right) throws Throwable {
        final ClassLoader loader = card.getClass().getClassLoader();
        Drawable icon = new ReadReceiptDrawable(Color.WHITE, view.getResources().getDisplayMetrics().density);
        for (Method method : baseView.getMethods()) {
            Class<?>[] args = method.getParameterTypes();
            if (args.length == 3 && args[0] == Drawable.class && args[1] == String.class && args[2] == int.class) {
                method.invoke(view, icon, LABEL, Color.rgb(45, 138, 95)); break;
            }
        }
        final String account = RecallMessages.account();
        final String chat = String.valueOf(card.getClass().getMethod("getId").invoke(card));
        final Object event = enumValue(loader, ROOT + "export.feedcard.action.FeedActionEvent", right ? "RIGHT_SWIPE_SHORT" : "LEFT_SWIPE");
        final Runnable action = new Runnable() {
            @Override public void run() {
                if (!account.equals(RecallMessages.account())) { notice("账号已变化，请重新滑动会话"); return; }
                try { status(status, card, event, "START"); }
                catch (Throwable error) { Diag.w("会话已读: 开始状态通知失败 " + error); }
                ConversationReadService.mark(chat, new ConversationReadService.Completion() {
                    @Override public void done(boolean success, final String message) { notice(message); }
                });
                try { status(status, card, event, "END"); }
                catch (Throwable error) { Diag.w("会话已读: 结束状态通知失败 " + error); }
            }
        };
        view.setVisibility(View.VISIBLE);
        view.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { action.run(); } });
        Method setter = baseView.getMethod("setFeedActionHandler", baseView.getMethod("getFeedActionHandler").getReturnType());
        final Class<?> handler = setter.getParameterTypes()[0];
        Object proxy = Proxy.newProxyInstance(handler.getClassLoader(), new Class<?>[] { handler },
                new java.lang.reflect.InvocationHandler() {
                    @Override public Object invoke(Object self, Method method, Object[] args) {
                        if (method.getDeclaringClass() == Object.class) return objectMethod(self, method, args);
                        // 右滑到底同样只调用当前会话的主动已读，不调用原生“完成/移除”。
                        if (args != null && args.length == 3 && cardType.isInstance(args[0])) action.run();
                        return null;
                    }
                });
        setter.invoke(view, proxy);
    }
    private static void status(Object listener, Object card, Object event, String name) throws Throwable {
        ClassLoader loader = card.getClass().getClassLoader();
        Object action = enumValue(loader, ROOT + "export.feedcard.action.FeedActionType", "CLEAR_UNREAD");
        Object status = enumValue(loader, ROOT + "export.feedcard.action.FeedActionStatus", name);
        for (Method method : listener.getClass().getMethods()) {
            Class<?>[] args = method.getParameterTypes();
            if (args.length == 4 && args[0].isInstance(card) && args[1].isInstance(action) && args[2].isInstance(status) && args[3].isInstance(event)) {
                method.setAccessible(true); method.invoke(listener, card, action, status, event); return;
            }
        }
    }
    private static boolean isChat(Object card) throws Exception {
        return "CHAT".equals(String.valueOf(card.getClass().getMethod("getType").invoke(card)));
    }
    private static Object enumValue(ClassLoader loader, String type, String value) throws Exception {
        return loader.loadClass(type).getField(value).get(null);
    }
    private static Settings stateForAdapter(Object adapter) throws Exception {
        Activity owner = null;
        for (Field field : adapter.getClass().getDeclaredFields()) if (Activity.class.isAssignableFrom(field.getType())) {
            field.setAccessible(true); owner = (Activity) field.get(adapter); break;
        }
        if (owner == null) return null;
        synchronized (CUSTOM) {
            for (Settings value : CUSTOM.values()) if (value.activity.get() == owner) return value;
        }
        return null;
    }
    private static View itemView(Object holder) throws Exception {
        return (View) holder.getClass().getField("itemView").get(holder);
    }
    private static View checkbox(View root) {
        try {
            if (root.getClass().getMethod("isChecked").getReturnType() == boolean.class) {
                root.getClass().getMethod("setChecked", boolean.class); return root;
            }
        } catch (NoSuchMethodException ignored) {}
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) { View found = checkbox(group.getChildAt(i)); if (found != null) return found; }
        }
        return null;
    }
    private static void checked(View view, boolean value) throws Exception {
        view.getClass().getMethod("setChecked", boolean.class).invoke(view, value);
    }
    private static View.OnClickListener listener(View view) throws Exception {
        Field info = View.class.getDeclaredField("mListenerInfo"); info.setAccessible(true);
        Object value = info.get(view); if (value == null) return null;
        Field click = value.getClass().getDeclaredField("mOnClickListener"); click.setAccessible(true);
        return (View.OnClickListener) click.get(value);
    }
    private static boolean selected(String account, int direction) {
        Context context = Config.appContextForSync();
        return context != null && account != null && !account.isEmpty()
                && context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(account + ":" + direction, false);
    }
    private static void save(String account, int direction, boolean value) throws Throwable {
        Context context = Config.appContextForSync();
        if (context == null) throw new IllegalStateException("Application 尚未就绪");
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(account + ":" + direction, value).apply();
        ClassLoader loader = baseView.getClassLoader();
        Class<?> setting = loader.loadClass("com.bytedance.lark.pb.feed.v1.FeedSlideActionSetting");
        Object adapter = setting.getField("ADAPTER").get(null);
        Object empty = adapter.getClass().getMethod("decode", byte[].class).invoke(adapter, (Object) new byte[0]);
        List<Object> helpers;
        synchronized (HELPERS) { helpers = new ArrayList<Object>(HELPERS); }
        for (Object helper : helpers) for (Field field : helper.getClass().getDeclaredFields()) {
            if (!"androidx.lifecycle.Observer".equals(field.getType().getName())) continue;
            field.setAccessible(true); Object observer = field.get(helper);
            if (observer != null) {
                Method changed = observer.getClass().getMethod("onChanged", Object.class); changed.setAccessible(true);
                changed.invoke(observer, empty);
            }
        }
    }
    private static Object objectMethod(Object self, Method method, Object[] args) {
        if ("equals".equals(method.getName())) return args != null && args.length == 1 && self == args[0];
        if ("hashCode".equals(method.getName())) return System.identityHashCode(self);
        return "FeishuKitConversationRead";
    }
    private static void notice(final String message) {
        MAIN.post(new Runnable() { @Override public void run() {
            Context context = Config.appContextForSync();
            if (context != null) Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
        } });
    }
    private ConversationSwipeRead() {}
}
