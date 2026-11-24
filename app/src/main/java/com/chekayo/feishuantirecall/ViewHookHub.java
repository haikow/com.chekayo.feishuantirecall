package com.chekayo.feishuantirecall;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.concurrent.CopyOnWriteArrayList;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 集中管理高频 Android View 框架 Hook，避免多个功能模块重复挂接同一热路径方法。
 *
 * 覆盖方法（按需懒安装，每种仅 Hook 一次）：
 *   - TextView.setText(CharSequence, TextView.BufferType)
 *   - View.setVisibility(int)
 *   - ViewGroup.addView(View, int, ViewGroup.LayoutParams)
 */
public final class ViewHookHub {
    private ViewHookHub() {}

    public interface SetTextListener {
        void afterSetText(TextView tv, CharSequence text);
    }

    public interface SetVisibilityListener {
        int beforeSetVisibility(View view, int visibility);
    }

    public interface AddViewListener {
        boolean beforeAddView(ViewGroup parent, View child);
        void afterAddView(ViewGroup parent, View child);
    }

    private static final CopyOnWriteArrayList<SetTextListener> SET_TEXT_LISTENERS =
            new CopyOnWriteArrayList<SetTextListener>();
    private static final CopyOnWriteArrayList<SetVisibilityListener> SET_VISIBILITY_LISTENERS =
            new CopyOnWriteArrayList<SetVisibilityListener>();
    private static final CopyOnWriteArrayList<AddViewListener> ADD_VIEW_LISTENERS =
            new CopyOnWriteArrayList<AddViewListener>();

    private static volatile boolean setTextHooked;
    private static volatile boolean setVisibilityHooked;
    private static volatile boolean addViewHooked;

    private static final ThreadLocal<XC_MethodHook.MethodHookParam> BLOCKED_ADD_VIEW =
            new ThreadLocal<XC_MethodHook.MethodHookParam>();

    public static void addSetTextListener(SetTextListener listener) {
        if (listener == null) return;
        SET_TEXT_LISTENERS.addIfAbsent(listener);
        ensureSetTextHooked();
    }

    public static void addSetVisibilityListener(SetVisibilityListener listener) {
        if (listener == null) return;
        SET_VISIBILITY_LISTENERS.addIfAbsent(listener);
        ensureSetVisibilityHooked();
    }

    public static void addAddViewListener(AddViewListener listener) {
        if (listener == null) return;
        ADD_VIEW_LISTENERS.addIfAbsent(listener);
        ensureAddViewHooked();
    }

    private static void ensureSetTextHooked() {
        if (setTextHooked) return;
        synchronized (ViewHookHub.class) {
            if (setTextHooked) return;
            try {
                XposedHelpers.findAndHookMethod(TextView.class, "setText",
                        CharSequence.class, TextView.BufferType.class, new SetTextHook());
                setTextHooked = true;
                XposedBridge.log("[fucklark] ViewHookHub: TextView.setText 已集中 hook");
            } catch (Throwable t) {
                XposedBridge.log("[fucklark] ViewHookHub: TextView.setText hook failed: " + t);
            }
        }
    }

    private static void ensureSetVisibilityHooked() {
        if (setVisibilityHooked) return;
        synchronized (ViewHookHub.class) {
            if (setVisibilityHooked) return;
            try {
                XposedHelpers.findAndHookMethod(View.class, "setVisibility",
                        int.class, new SetVisibilityHook());
                setVisibilityHooked = true;
                XposedBridge.log("[fucklark] ViewHookHub: View.setVisibility 已集中 hook");
            } catch (Throwable t) {
                XposedBridge.log("[fucklark] ViewHookHub: View.setVisibility hook failed: " + t);
            }
        }
    }

    private static void ensureAddViewHooked() {
        if (addViewHooked) return;
        synchronized (ViewHookHub.class) {
            if (addViewHooked) return;
            try {
                XposedHelpers.findAndHookMethod(ViewGroup.class, "addView",
                        View.class, int.class, ViewGroup.LayoutParams.class, new AddViewHook());
                addViewHooked = true;
                XposedBridge.log("[fucklark] ViewHookHub: ViewGroup.addView 已集中 hook");
            } catch (Throwable t) {
                XposedBridge.log("[fucklark] ViewHookHub: ViewGroup.addView hook failed: " + t);
            }
        }
    }

    private static final class SetTextHook extends XC_MethodHook {
        @Override protected void afterHookedMethod(MethodHookParam p) {
            if (!(p.thisObject instanceof TextView)) return;
            CharSequence text = (p.args != null && p.args.length > 0 && p.args[0] instanceof CharSequence)
                    ? (CharSequence) p.args[0] : null;
            TextView tv = (TextView) p.thisObject;
            for (SetTextListener listener : SET_TEXT_LISTENERS) {
                try {
                    listener.afterSetText(tv, text);
                } catch (Throwable ignored) {}
            }
        }
    }

    private static final class SetVisibilityHook extends XC_MethodHook {
        @Override protected void beforeHookedMethod(MethodHookParam p) {
            if (!(p.thisObject instanceof View) || p.args == null || p.args.length == 0
                    || !(p.args[0] instanceof Integer)) return;
            View view = (View) p.thisObject;
            int orig = ((Integer) p.args[0]).intValue();
            int cur = orig;
            for (SetVisibilityListener listener : SET_VISIBILITY_LISTENERS) {
                try {
                    cur = listener.beforeSetVisibility(view, cur);
                } catch (Throwable ignored) {}
            }
            if (cur != orig) {
                p.args[0] = Integer.valueOf(cur);
            }
        }
    }

    private static final class AddViewHook extends XC_MethodHook {
        @Override protected void beforeHookedMethod(MethodHookParam p) {
            if (!(p.thisObject instanceof ViewGroup) || p.args == null || p.args.length == 0
                    || !(p.args[0] instanceof View)) return;
            ViewGroup parent = (ViewGroup) p.thisObject;
            View child = (View) p.args[0];
            for (AddViewListener listener : ADD_VIEW_LISTENERS) {
                try {
                    if (listener.beforeAddView(parent, child)) {
                        BLOCKED_ADD_VIEW.set(p);
                        p.setResult(null);
                        return;
                    }
                } catch (Throwable ignored) {}
            }
        }

        @Override protected void afterHookedMethod(MethodHookParam p) {
            if (BLOCKED_ADD_VIEW.get() == p) {
                BLOCKED_ADD_VIEW.remove();
                return;
            }
            if (!(p.thisObject instanceof ViewGroup) || p.args == null || p.args.length == 0
                    || !(p.args[0] instanceof View)) return;
            ViewGroup parent = (ViewGroup) p.thisObject;
            View child = (View) p.args[0];
            for (AddViewListener listener : ADD_VIEW_LISTENERS) {
                try {
                    listener.afterAddView(parent, child);
                } catch (Throwable ignored) {}
            }
        }
    }
}
