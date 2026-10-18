package com.chekayo.feishuantirecall;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;


/**
 * 隐藏消息下方「相关知识 … 查看 ×」推荐条。
 * 真机确认：reply_layout → ai_smart_reply_stub → ai_smart_reply_tip。
 * tip 是自绘 View，不能靠 TextView 文案识别。仅处理这些资源名对应的独立区域。
 */
public final class RelatedKnowledgeBlock {
    private RelatedKnowledgeBlock() {}

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<View, Boolean> PENDING =
            Collections.synchronizedMap(new WeakHashMap<View, Boolean>());
    // 下列状态仅在主线程读写。value 不持有 View，避免反向保活弱引用 key。
    private static final Map<View, Boolean> CANDIDATES = new WeakHashMap<View, Boolean>();
    private static final Map<View, State> HIDDEN = new WeakHashMap<View, State>();
    private static final Map<android.content.res.Resources, Map<Integer, String>> RESOURCE_NAMES =
            Collections.synchronizedMap(new WeakHashMap<android.content.res.Resources, Map<Integer, String>>());
    private static boolean installed;
    private static boolean changingVisibility;
    private static long lastLogAt;

    private static final class State {
        int visibility;
        State(int visibility) { this.visibility = visibility; }
    }

    public static synchronized void install() {
        if (installed) return;
        installed = true;
        ViewHookAdapter.Callback addHook = new ViewHookAdapter.Callback() {
            @Override protected void afterHookedMethod(ViewHookAdapter.Param p) {
                try {
                    View parent = (View) p.thisObject;
                    View child = (View) p.args[0];
                    if (isAiNode(child) || isReplyLayout(parent)) schedule(parent);
                } catch (Throwable ignored) {}
            }
        };
        hook(ViewGroup.class, "addView", View.class, addHook);
        hook(ViewGroup.class, "addView", View.class, int.class, addHook);
        hook(ViewGroup.class, "addView", View.class, ViewGroup.LayoutParams.class, addHook);
        hook(ViewGroup.class, "addView", View.class, int.class, ViewGroup.LayoutParams.class, addHook);
        hook(View.class, "setId", int.class, new ViewHookAdapter.Callback() {
            @Override protected void afterHookedMethod(ViewHookAdapter.Param p) {
                try {
                    View v = (View) p.thisObject;
                    if (onMainThread() && HIDDEN.containsKey(v) && !isAiNode(v) && !isReplyLayout(v)) restore(v);
                    if (isAiNode(v) || isReplyLayout(v) || (onMainThread() && HIDDEN.containsKey(v))) schedule(v);
                } catch (Throwable ignored) {}
            }
        });
        hook(View.class, "onAttachedToWindow", new ViewHookAdapter.Callback() {
            @Override protected void afterHookedMethod(ViewHookAdapter.Param p) {
                try {
                    View v = (View) p.thisObject;
                    if (isAiNode(v) || isReplyLayout(v)) schedule(v);
                } catch (Throwable ignored) {}
            }
        });
        hook(View.class, "onDetachedFromWindow", new ViewHookAdapter.Callback() {
            @Override protected void afterHookedMethod(ViewHookAdapter.Param p) {
                try { if (onMainThread()) restore((View) p.thisObject); }
                catch (Throwable ignored) {}
            }
        });
        hook(View.class, "setVisibility", int.class, new ViewHookAdapter.Callback() {
            @Override protected void beforeHookedMethod(ViewHookAdapter.Param p) {
                try {
                    if (!onMainThread() || changingVisibility) return;
                    View v = (View) p.thisObject;
                    if (shouldBlock(v)) {
                        CANDIDATES.put(v, Boolean.TRUE);
                        if (Config.blockrelatedknowledge) {
                            if ((Integer) p.args[0] == View.VISIBLE) record(v, "宿主请求显示");
                            State state = HIDDEN.get(v);
                            if (state == null) HIDDEN.put(v, new State((Integer) p.args[0]));
                            else state.visibility = (Integer) p.args[0];
                            p.args[0] = View.GONE;
                        } else HIDDEN.remove(v);
                        schedule(v);
                    } else if (HIDDEN.containsKey(v)) {
                        HIDDEN.remove(v); // 宿主正在复用节点，遵从它本次请求的显示状态。
                    }
                    Object parent = v.getParent();
                    if (parent instanceof View && isReplyLayout((View) parent)) schedule((View) parent);
                } catch (Throwable ignored) {}
            }
        });
        ModuleLog.log("[fucklark] 相关知识屏蔽: ai_smart_reply 资源 hooks 已安装");
    }

    private static void hook(Class<?> type, String name, Object... args) {
        try {
            ViewHookAdapter.Callback callback = (ViewHookAdapter.Callback) args[args.length - 1];
            Class<?>[] params = new Class<?>[args.length - 1];
            for (int i = 0; i < params.length; i++) params[i] = (Class<?>) args[i];
            ViewHookAdapter.hook(type, name, callback, params);
        } catch (Throwable t) { ModuleLog.log("[fucklark] related knowledge hook " + name + " failed: " + t); }
    }

    private static boolean onMainThread() { return Looper.myLooper() == Looper.getMainLooper(); }

    static boolean isAiNode(View v) {
        String name = resourceName(v);
        return "ai_smart_reply_stub".equals(name) || "ai_smart_reply_tip".equals(name);
    }

    private static boolean isReplyLayout(View v) { return "reply_layout".equals(resourceName(v)); }

    /** reply_layout 只有 AI 提示时才整块折叠，包含其它可见内容时仅隐藏 AI 子节点。 */
    static boolean shouldBlock(View v) {
        if (isAiNode(v)) return !(v instanceof android.view.ViewStub);
        if (!isReplyLayout(v) || !(v instanceof ViewGroup)) return false;
        ViewGroup group = (ViewGroup) v;
        boolean hasAi = false;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (isAiNode(child)) hasAi |= shouldBlock(child);
            else if (child.getVisibility() != View.GONE) return false;
        }
        return hasAi;
    }

    private static String resourceName(View v) {
        android.content.res.Resources resources = v.getResources();
        int id = v.getId();
        synchronized (RESOURCE_NAMES) {
            Map<Integer, String> names = RESOURCE_NAMES.get(resources);
            if (names == null) {
                names = new HashMap<Integer, String>();
                RESOURCE_NAMES.put(resources, names);
            }
            if (names.containsKey(id)) return names.get(id);
            String name;
            try { name = resources.getResourceEntryName(id); }
            catch (Throwable ignored) { name = ""; }
            names.put(id, name);
            return name;
        }
    }

    private static void schedule(View v) {
        synchronized (PENDING) {
            if (PENDING.containsKey(v)) return;
            PENDING.put(v, Boolean.TRUE);
        }
        final WeakReference<View> ref = new WeakReference<View>(v);
        MAIN.post(new Runnable() {
            @Override public void run() {
                View view = ref.get();
                if (view == null) return;
                PENDING.remove(view);
                try {
                    updateTree(view, 0);
                    Object parent = view.getParent();
                    if (parent instanceof View && isReplyLayout((View) parent)) updateTree((View) parent, 0);
                } catch (Throwable t) {
                    ModuleLog.log("[fucklark] related knowledge update failed: " + t);
                }
            }
        });
    }

    static void updateTree(View v, int depth) {
        if (depth > 4) return;
        if (isAiNode(v) || isReplyLayout(v)) {
            CANDIDATES.put(v, Boolean.TRUE);
            if (Config.blockrelatedknowledge && shouldBlock(v)) hide(v);
            else restore(v);
        }
        if (v instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) v;
            for (int i = 0; i < group.getChildCount(); i++) updateTree(group.getChildAt(i), depth + 1);
        }
    }

    private static void hide(View v) {
        if (HIDDEN.containsKey(v)) return;
        if (v.getVisibility() == View.VISIBLE) record(v, "隐藏推荐条");
        HIDDEN.put(v, new State(v.getVisibility()));
        if (v.getWidth() == 0) {
            final java.lang.ref.WeakReference<View> ref = new java.lang.ref.WeakReference<View>(v);
            MAIN.postDelayed(new Runnable() {
                @Override public void run() { View node = ref.get(); if (node != null && HIDDEN.containsKey(node)) record(node, "隐藏后补充内容"); }
            }, 300);
        }
        setVisibility(v, View.GONE);
        long now = System.currentTimeMillis();
        if (now - lastLogAt > 800) {
            lastLogAt = now;
            ModuleLog.log("[fucklark] 相关知识: 隐藏推荐区域 id=" + resourceName(v)
                    + " class=" + v.getClass().getName());
        }
    }

    private static void record(View v, String via) {
        // 标题、外壳共用一条事件，避免一次渲染产生多条日志。
        View target = v;
        View identity = v;
        for (int i = 0; i < 3 && v.getParent() instanceof View; i++) {
            v = (View) v.getParent();
            if (isReplyLayout(v)) { identity = v; break; }
        }
        BlockLog.recordDeferred("相关知识", identity, via);
    }

    private static void setVisibility(View v, int visibility) {
        changingVisibility = true;
        try { v.setVisibility(visibility); }
        finally { changingVisibility = false; }
    }

    private static void restore(View v) {
        State state = HIDDEN.remove(v);
        if (state != null) setVisibility(v, state.visibility);
    }

    private static void restoreInvalidViews() {
        List<View> views = new ArrayList<View>(HIDDEN.keySet());
        for (View v : views) if (!Config.blockrelatedknowledge || !shouldBlock(v)) restore(v);
    }

    /** 面板切换或配置同步后，立即更新已经出现的推荐条。 */
    public static void onConfigChanged() {
        MAIN.post(new Runnable() {
            @Override public void run() {
                restoreInvalidViews();
                List<View> views = new ArrayList<View>(CANDIDATES.keySet());
                for (View v : views) if (v != null) updateTree(v, 0);
            }
        });
    }
}
