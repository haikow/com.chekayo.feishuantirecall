package com.chekayo.feishuantirecall;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

/** 消息下方时间：使用绑定模型的 create_time（秒），不解析相对日期文案。 */
final class MessageTimeDisplay {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<TextView, State> VIEWS = new WeakHashMap<TextView, State>();
    private static boolean changing;
    private static boolean installed;
    private static volatile String installStatus = "未扫描";
    static String status() { return installStatus; }
    private static volatile int timeViewId;
    private static boolean reportedTimeView, reportedBindingError;
    private static Method timestampMethod;
    private static Method wrappedMessageMethod;
    private static Method messageTimestampMethod;
    private static final class State {
        long seconds;
        CharSequence original;
        int visibility;
        State(long seconds, TextView tv) {
            this.seconds = seconds; original = tv.getText(); visibility = tv.getVisibility();
        }
    }

    /** 扫描只在后台线程执行，避免枚举宿主消息渲染类阻塞首屏。 */
    static void install(final ClassLoader loader) {
        Thread scanner = new Thread(new Runnable() {
            @Override public void run() { scanAndInstall(loader); }
        }, "message-time-anchor");
        scanner.setDaemon(true);
        scanner.start();
    }

    private static synchronized void scanAndInstall(ClassLoader loader) {
        if (installed) return;
        try {
            // 等 Application 就绪，使用当前用户的真实私有目录和宿主版本。
            android.content.Context context = null;
            for (int attempt = 0; attempt < 120 && context == null; attempt++) {
                context = Config.appContextForSync();
                if (context == null) Thread.sleep(100);
            }
            if (context == null) throw new IllegalStateException("Application 尚未就绪，下次启动重试");
            // 时间 UI 只由主进程适配；后台消息进程不扫描也不触发重启。
            String process = AntiRecall.currentProcessName();
            if (!context.getPackageName().equals(process)) { installStatus = "后台进程无需时间锚点"; return; }
            java.io.File cache = new java.io.File(context.getFilesDir(), "feishukit/anchors/time.properties");
            Diag.setFilesDir(context.getFilesDir());
            android.content.pm.PackageInfo host = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            Diag.w("时间锚点: 初始化 pkg=" + context.getPackageName() + " version=" + host.versionName
                    + " code=" + host.versionCode + " process=" + process + " rule=" + AnchorCache.SCHEMA);
            String identity = AnchorCache.identity(context);
            Method cached = AnchorCache.load(cache, identity, loader);
            if (cached != null) {
                try {
                    if (!hasMessageIdentity(cached.getParameterTypes()[1])) throw new NoSuchMethodException("消息身份锚点失效");
                    resolveTimestamp(cached.getParameterTypes()[1]);
                } catch (Throwable invalid) {
                    Diag.w("时间锚点: 缓存能力校验失败，重新扫描 " + invalid);
                    cached = null;
                }
            }
            if (cached == null) {
                Diag.w("时间锚点: 开始后台扫描");
                long scanStart = android.os.SystemClock.elapsedRealtime();
                java.util.List<Method> scanned = findBindings(loader);
                Diag.w("时间锚点: 扫描结束 candidates=" + scanned.size() + " elapsedMs="
                        + (android.os.SystemClock.elapsedRealtime() - scanStart));
                if (scanned.size() != 1) throw new NoSuchMethodException("时间锚点不唯一");
                resolveTimestamp(scanned.get(0).getParameterTypes()[1]);
                AnchorCache.save(cache, identity, scanned.get(0));
                installStatus = "扫描完成并已保存，重启后加载缓存";
                Diag.w("时间锚点: " + installStatus);
                final android.app.Application app = (android.app.Application) context.getApplicationContext();
                MAIN.post(new Runnable() { public void run() { AnchorRestart.offer(app); } });
                return;
            }
            final java.util.List<Method> bindings = java.util.Collections.singletonList(cached);
            resolveTimestamp(bindings.get(0).getParameterTypes()[1]);
            for (final Method binding : bindings) {
                ViewHookAdapter.hook(binding, new ViewHookAdapter.Callback() {
                    @Override protected void beforeHookedMethod(ViewHookAdapter.Param p) { beforeBind(p.args[0]); }
                    @Override protected void afterHookedMethod(ViewHookAdapter.Param p) { afterBind(binding, p.args[0], p.args[1]); }
                });
            }
            ViewHookAdapter.hook(View.class, "setVisibility", new ViewHookAdapter.Callback() {
                @Override protected void beforeHookedMethod(ViewHookAdapter.Param p) {
                    if (!Config.messageTimeAlways || changing || Looper.myLooper() != Looper.getMainLooper()
                            || !(p.thisObject instanceof TextView)) return;
                    State state = VIEWS.get((TextView) p.thisObject);
                    if (state == null) return;
                    state.visibility = (Integer) p.args[0];
                    if (state.seconds > 0) p.args[0] = View.VISIBLE;
                }
            }, int.class);
            installed = true;
            installStatus = "缓存已加载，等待 time_tv 命中";
            Diag.w("时间锚点: hook 已安装 " + cached.toGenericString());
            ModuleLog.log("[fucklark] 消息时间候选 hook 已安装: " + installStatus);
        } catch (Throwable t) {
            installStatus = "扫描失败: " + t.getClass().getSimpleName() + ": " + t.getMessage();
            Diag.w("时间锚点: " + installStatus);
            ModuleLog.log("[fucklark] 消息时间锚点扫描失败: " + installStatus);
        }
    }

    /**
     * 从消息渲染 block 包中扫描二参绑定方法，不依赖混淆类名/方法名。
     * 通过工厂泛型找到被混淆的 footer holder，再按绑定签名定位；运行时用 time_tv 确认。
     */
    static java.util.List<Method> findBindings(ClassLoader loader) throws Throwable {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<String>();
        Class<?> base = Class.forName("dalvik.system.BaseDexClassLoader");
        Field pathList = base.getDeclaredField("pathList"); pathList.setAccessible(true);
        Object dexPathList = pathList.get(loader);
        Field elements = dexPathList.getClass().getDeclaredField("dexElements"); elements.setAccessible(true);
        for (Object element : (Object[]) elements.get(dexPathList)) {
            Field dexField = element.getClass().getDeclaredField("dexFile"); dexField.setAccessible(true);
            Object dex = dexField.get(element);
            if (dex == null) continue;
            java.util.Enumeration<?> entries = (java.util.Enumeration<?>) dex.getClass().getMethod("entries").invoke(dex);
            while (entries.hasMoreElements()) {
                String name = String.valueOf(entries.nextElement());
                if (name.startsWith("com.ss.android.lark.chat.chatwindow.biz.component.messagelist.render.block.factory."))
                    names.add(name);
            }
        }
        java.util.ArrayList<Method> candidates = new java.util.ArrayList<Method>();
        for (String name : names) try {
            Class<?> factory = Class.forName(name, false, loader);
            for (java.lang.reflect.Type contract : factory.getGenericInterfaces()) {
                if (!(contract instanceof java.lang.reflect.ParameterizedType)) continue;
                java.lang.reflect.Type[] parameters = ((java.lang.reflect.ParameterizedType) contract).getActualTypeArguments();
                if (parameters.length != 2 || !(parameters[1] instanceof Class)) continue;
                Class<?> holder = (Class<?>) parameters[1], block = holder.getEnclosingClass();
                if (block == null) continue;
                int texts = 0;
                for (Field field : holder.getDeclaredFields())
                    if (TextView.class.isAssignableFrom(field.getType())) texts++;
                if (texts < 4) continue;
                for (Method method : block.getDeclaredMethods()) {
                    Class<?>[] args = method.getParameterTypes(); int flags = method.getModifiers();
                    if (args.length == 2 && args[0] == holder && method.getReturnType() == void.class
                            && !method.isBridge() && !method.isSynthetic()
                            && !java.lang.reflect.Modifier.isPrivate(flags) && !java.lang.reflect.Modifier.isStatic(flags)
                            && hasMessageIdentity(args[1]) && hasTimestamp(args[1])) candidates.add(method);
                }
            }
        } catch (Throwable ignored) { }
        Diag.w("时间锚点: 工厂扫描完成 factories=" + names.size() + " candidates=" + candidates.size());
        for (Method candidate : candidates) Diag.w("时间锚点: 候选 " + candidate.toGenericString());
        if (candidates.size() != 1) throw new NoSuchMethodException("Footer 工厂绑定候选=" + candidates.size());
        candidates.get(0).setAccessible(true);
        return candidates;
    }

    private static boolean hasMessageIdentity(Class<?> model) {
        try {
            return model.getMethod("getId").getReturnType() == String.class
                    && model.getMethod("getChatId").getReturnType() == String.class;
        } catch (Throwable ignored) { return false; }
    }

    private static boolean hasTimestamp(Class<?> model) {
        if (findUniqueLongGetter(model) != null) return true;
        try {
            Method getMessage = model.getMethod("getMessage");
            return findUniqueLongGetter(getMessage.getReturnType()) != null;
        } catch (Throwable ignored) { return false; }
    }

    private static Method findUniqueLongGetter(Class<?> type) {
        try {
            Method conventional;
            try { conventional = type.getMethod("getCreateTime"); }
            catch (NoSuchMethodException absent) { conventional = type.getMethod("c"); }
            if (conventional.getParameterTypes().length == 0 && conventional.getReturnType() == long.class)
                return conventional;
        } catch (Throwable ignored) { }
        Method found = null;
        for (Method method : type.getMethods()) {
            if (method.getParameterTypes().length != 0 || method.getReturnType() != long.class
                    || method.getDeclaringClass() == Object.class) continue;
            if (found != null) return null;
            found = method;
        }
        return found;
    }

    private static void resolveTimestamp(Class<?> model) throws NoSuchMethodException {
        wrappedMessageMethod = null;
        messageTimestampMethod = null;
        timestampMethod = findUniqueLongGetter(model);
        if (timestampMethod != null) return;
        wrappedMessageMethod = model.getMethod("getMessage");
        messageTimestampMethod = findUniqueLongGetter(wrappedMessageMethod.getReturnType());
        if (messageTimestampMethod == null) throw new NoSuchMethodException("消息时间 getter 不唯一");
    }

    private static long timestamp(Object model) throws Throwable {
        Object owner = wrappedMessageMethod == null ? model : wrappedMessageMethod.invoke(model);
        Method getter = wrappedMessageMethod == null ? timestampMethod : messageTimestampMethod;
        return ((Number) getter.invoke(owner)).longValue();
    }

    private static TextView findTime(Object holder) {
        View root = holderRoot(holder);
        if (root == null) return null;
        int id = timeViewId;
        if (id != 0) {
            View cached = root.findViewById(id);
            return cached instanceof TextView ? (TextView) cached : null;
        }
        if (!(root instanceof android.view.ViewGroup)) return null;
        java.util.ArrayDeque<View> pending = new java.util.ArrayDeque<View>();
        pending.add(root);
        while (!pending.isEmpty()) {
            View view = pending.removeFirst();
            if (view instanceof TextView) {
                try {
                    if ("time_tv".equals(view.getResources().getResourceEntryName(view.getId()))) {
                        timeViewId = view.getId();
                        return (TextView) view;
                    }
                } catch (Throwable ignored) { }
            }
            if (view instanceof android.view.ViewGroup) {
                android.view.ViewGroup group = (android.view.ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) pending.add(group.getChildAt(i));
            }
        }
        return null;
    }

    private static View holderRoot(Object holder) {
        if (holder == null) return null;
        java.util.ArrayList<Class<?>> hierarchy = new java.util.ArrayList<Class<?>>();
        for (Class<?> type = holder.getClass(); type != null; type = type.getSuperclass()) hierarchy.add(0, type);
        for (Class<?> type : hierarchy) {
            for (Field field : type.getDeclaredFields()) {
                if (!View.class.isAssignableFrom(field.getType())) continue;
                try {
                    field.setAccessible(true);
                    View view = (View) field.get(holder);
                    if (view != null) return view;
                } catch (Throwable ignored) { }
            }
        }
        return null;
    }

    private static void beforeBind(Object holder) {
        TextView tv = findTime(holder);
        if (tv == null) return;
        State state = VIEWS.remove(tv);
        if (state != null) restore(tv, state);
    }

    private static void afterBind(Method binding, Object holder, Object model) {
        if (!Config.messageTimeAlways && !Config.messageTimeFormatting) return;
        try {
            TextView tv = findTime(holder);
            if (tv == null) return;
            resolveTimestamp(model.getClass());
            long seconds = timestamp(model);
            State state = new State(seconds, tv);
            VIEWS.put(tv, state);
            apply(tv, state);
            installStatus = "已命中 " + binding.getDeclaringClass().getName() + "." + binding.getName();
            if (!reportedTimeView && Config.diaglog) {
                reportedTimeView = true;
                Diag.w("时间锚点: 首次绑定 time_tv 成功 " + binding.toGenericString()
                        + " always=" + Config.messageTimeAlways + " formatting=" + Config.messageTimeFormatting);
            }
        } catch (Throwable t) {
            if (!reportedBindingError && Config.diaglog) {
                reportedBindingError = true;
                Diag.w("时间锚点: 首次绑定失败 " + t);
            }
        }
    }

    private static CharSequence nativeText(State state) {
        if (state.original != null && state.original.length() > 0) return state.original;
        return MessageTimeFormat.format(state.seconds, "{relative} HH:mm:ss");
    }
    private static void apply(TextView tv, State state) {
        changing = true;
        try {
            // 两个开关都关闭时完全恢复宿主文本和可见性，不参与消息行渲染。
            if (!Config.messageTimeAlways && !Config.messageTimeFormatting) {
                tv.setText(state.original);
                tv.setVisibility(state.visibility);
                return;
            }
            if (Config.messageTimeAlways && state.seconds > 0) {
                tv.setText(Config.messageTimeFormatting ? MessageTimeFormat.format(state.seconds, Config.messageTimePattern)
                        : nativeText(state));
                tv.setVisibility(View.VISIBLE);
            } else {
                tv.setText(Config.messageTimeFormatting && state.seconds > 0
                        ? MessageTimeFormat.format(state.seconds, Config.messageTimePattern) : state.original);
                tv.setVisibility(state.visibility);
            }
        } finally { changing = false; }
    }
    private static void restore(TextView tv, State state) {
        changing = true;
        try { tv.setText(state.original); tv.setVisibility(state.visibility); }
        finally { changing = false; }
    }
    static void onConfigChanged() {
        MAIN.post(new Runnable() {
            @Override public void run() {
                for (TextView tv : new ArrayList<TextView>(VIEWS.keySet())) {
                    State state = VIEWS.get(tv);
                    if (tv != null && state != null) apply(tv, state);
                }
            }
        });
    }
    private MessageTimeDisplay() {}
}
