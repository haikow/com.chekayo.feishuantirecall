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

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

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
        ViewHookHub.addAddViewListener(new ViewHookHub.AddViewListener() {
            @Override public boolean beforeAddView(ViewGroup parent, View child) { return false; }
            @Override public void afterAddView(ViewGroup parent, View child) {
                if (isAiNode(child) || isReplyLayout(parent)) schedule(parent);
            }
        });
        hook(View.class, "setId", int.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                try {
                    View v = (View) p.thisObject;
                    if (onMainThread() && HIDDEN.containsKey(v) && !isAiNode(v) && !isReplyLayout(v)) restore(v);
                    if (isAiNode(v) || isReplyLayout(v) || (onMainThread() && HIDDEN.containsKey(v))) schedule(v);
                } catch (Throwable ignored) {}
            }
        });
        hook(View.class, "onAttachedToWindow", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                try {
                    View v = (View) p.thisObject;
                    if (isAiNode(v) || isReplyLayout(v)) schedule(v);
                } catch (Throwable ignored) {}
            }
        });
        hook(View.class, "onDetachedFromWindow", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                try { if (onMainThread()) restore((View) p.thisObject); }
                catch (Throwable ignored) {}
            }
        });
        ViewHookHub.addSetVisibilityListener(new ViewHookHub.SetVisibilityListener() {
            @Override public int beforeSetVisibility(View v, int visibility) {
                if (!onMainThread() || changingVisibility) return visibility;
                int out = visibility;
                if (shouldBlock(v)) {
                    CANDIDATES.put(v, Boolean.TRUE);
                    if (Config.blockrelatedknowledge) {
                        if (visibility == View.VISIBLE) record(v, "宿主请求显示");
                        State state = HIDDEN.get(v);
                        if (state == null) HIDDEN.put(v, new State(visibility));
                        else state.visibility = visibility;
                        out = View.GONE;
                    } else HIDDEN.remove(v);
                    schedule(v);
                } else if (HIDDEN.containsKey(v)) {
                    HIDDEN.remove(v); // 宿主正在复用节点，遵从它本次请求的显示状态。
                }
                Object parent = v.getParent();
                if (parent instanceof View && isReplyLayout((View) parent)) schedule((View) parent);
                return out;
            }
        });
        XposedBridge.log("[fucklark] 相关知识屏蔽: ai_smart_reply 资源 hooks 已安装");
    }

    private static void hook(Class<?> type, String name, Object... args) {
        try { XposedHelpers.findAndHookMethod(type, name, args); }
        catch (Throwable t) { XposedBridge.log("[fucklark] related knowledge hook " + name + " failed: " + t); }
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
        if (v == null) return "";
        int id = v.getId();
        if (id == View.NO_ID || (id >>> 24) != 0x7f) return "";
        android.content.res.Resources resources = v.getResources();
        if (resources == null) return "";
        synchronized (RESOURCE_NAMES) {
            Map<Integer, String> names = RESOURCE_NAMES.get(resources);
            if (names == null) {
                names = new HashMap<Integer, String>();
                RESOURCE_NAMES.put(resources, names);
            }
            String cached = names.get(id);
            if (cached != null) return cached;
            String name;
            try { name = resources.getResourceEntryName(id); }
            catch (Throwable ignored) { name = ""; }
            if (name == null) name = "";
            // 空串也缓存，避免无效 ID 反复抛资源异常。
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
                    XposedBridge.log("[fucklark] related knowledge update failed: " + t);
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
            XposedBridge.log("[fucklark] 相关知识: 隐藏推荐区域 id=" + resourceName(v)
                    + " class=" + v.getClass().getName());
        }
    }

    private static void record(View v, String via) {
        // 标题、外壳共用一条事件，避免一次渲染产生多条日志。
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
