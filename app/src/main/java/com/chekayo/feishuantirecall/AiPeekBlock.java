package com.chekayo.feishuantirecall;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XposedBridge;

/**
 * 屏蔽会话内 AI 总结「消息速览」浮层（整条，含边框）。
 *
 * 【功能说明】
 * 触发场景：会话消息较多时，飞书在输入框上方调用 AI 总结，弹出带「消息速览: …」的小提示。
 * 本功能在浮层出现前/出现时整块隐藏，不只清字，避免留下空框。
 *
 * 明确不处理（防误伤）：
 *   - 聊天列表右下角「N 条新消息」跳转钮（resource-id new_messages_*）
 *   - 设置项「屏蔽消息速览」「显示 AI 速览」
 *
 * 【实现】锚点：文案以「消息速览」开头（不靠类名，ShineTextView 在搜索 AI 卡片里也用）。
 * 统一接入 ViewHookHub（零独立全局 Hook）：
 *   1) AddViewListener       —— 仅拦已确认含「消息速览」文案的节点
 *   2) SetVisibilityListener —— 对已被 hideTipShell 标记的节点强制 GONE；关开关时恢复尺寸
 *   3) SetTextListener       —— 文案命中「消息速览」后清字 + 向上收壳（主路径）
 *
 * 开关：Config.blockaipeek（默认开），设置面板「屏蔽消息速览」。
 */
public final class AiPeekBlock {
    private AiPeekBlock() {}

    /** setTag 键：已屏蔽过的节点，避免 setVisibility 反复重拦刷日志。 */
    static final int TAG_BLOCKED = 0x70e0b10c;

    static final java.util.Set<TextView> BUSY = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<TextView, Boolean>()));
    private static final Map<View, int[]> SAVED_LP =
            Collections.synchronizedMap(new WeakHashMap<View, int[]>());
    private static final View.OnAttachStateChangeListener DETACH_RESTORE =
            new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View v) {}
                @Override public void onViewDetachedFromWindow(View v) {
                    restoreView(v);
                    try { v.removeOnAttachStateChangeListener(this); } catch (Throwable ignored) {}
                }
            };

    private static volatile boolean installed = false;
    static volatile long lastHitLogAt = 0;
    static volatile int hitCount = 0;

    static void hit(String via, String detail) {
        try {
            hitCount++;
            long now = System.currentTimeMillis();
            if (now - lastHitLogAt < 800) return;
            lastHitLogAt = now;
            XposedBridge.log("[fucklark] 消息速览命中#" + hitCount + " via=" + via
                    + " " + detail + " (进程 " + AntiRecall.currentProcessName() + ")");
        } catch (Throwable ignored) {}
    }

    public static synchronized void install() {
        if (installed) return;
        installed = true;

        // ① addView：速览浮层挂树前拦掉
        ViewHookHub.addAddViewListener(new ViewHookHub.AddViewListener() {
            @Override public boolean beforeAddView(ViewGroup parent, View child) {
                if (!Config.blockaipeek) return false;
                if (isPeekNode(child)) {
                    BlockLog.record("消息速览", child, "挂载拦截");
                    markBlocked(child);
                    hit("addView", "拦下浮层 class=" + child.getClass().getName());
                    return true;
                }
                return false;
            }
            @Override public void afterAddView(ViewGroup parent, View child) {}
        });

        // ② setVisibility：仅速览节点/小壳 → GONE（大容器即使含速览也不动）
        ViewHookHub.addSetVisibilityListener(new ViewHookHub.SetVisibilityListener() {
            @Override public int beforeSetVisibility(View v, int visibility) {
                if (!Config.blockaipeek) {
                    if (isBlocked(v)) restoreView(v);
                    return visibility;
                }
                if (visibility != View.VISIBLE) return visibility;
                if (!isBlocked(v)) return visibility;
                hit("setVisibility", "强制GONE class=" + v.getClass().getName());
                return View.GONE;
            }
        });

        // ③ setText：命中则清字 + 收壳（解决空框）
        ViewHookHub.addSetTextListener(new ViewHookHub.SetTextListener() {
            @Override public void afterSetText(TextView tv, CharSequence src) {
                if (!Config.blockaipeek || src == null) return;
                String s = src.toString();
                if (!isPeekText(s)) return;
                if (BUSY.contains(tv)) return;
                BUSY.add(tv);
                try {
                    String brief = s.trim();
                    if (brief.length() > 24) brief = brief.substring(0, 24);
                    hit("setText", "文案=" + brief + " class=" + tv.getClass().getName());
                    BlockLog.record("消息速览", tv, "文案拦截", s);
                    hideTipShell(tv);
                } finally {
                    BUSY.remove(tv);
                }
            }
        });

        XposedBridge.log("[fucklark] 消息速览屏蔽: 已注册到 ViewHookHub (进程 "
                + AntiRecall.currentProcessName() + ")");
    }

    /** 文案锚点：以「消息速览」开头。不含「屏蔽消息速览」「显示 AI 速览」「N 条新消息」。 */
    static boolean isPeekText(String s) {
        if (s == null || s.length() == 0) return false;
        String text = s.trim();
        if (!text.startsWith("消息速览")) return false;
        if (text.length() == 4) return true;
        char boundary = text.charAt(4);
        return boundary == ':' || boundary == '：' || Character.isWhitespace(boundary);
    }

    /** 是否速览节点本身（文案命中），不含「大树里夹带」。 */
    static boolean isPeekNode(View v) {
        if (v == null) return false;
        try {
            if (v instanceof TextView) {
                CharSequence cs = ((TextView) v).getText();
                if (cs != null && isPeekText(cs.toString())) return true;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 整壳隐藏：清空文案（防反复命中）+ GONE 命中节点 + 向上收掉小提示条壳。
     * 只收壳，不动聊天列表/输入区等大容器。
     */
    static void hideTipShell(TextView tv) {
        // 清字：后续 setVisibility 不再靠文案重拦，也避免空字残留
        try {
            BUSY.add(tv);
            try { tv.setText(""); } finally { BUSY.remove(tv); }
        } catch (Throwable ignored) {}
        hideView(tv);
        View cur = tv;
        int shells = 0;
        for (int i = 0; i < 3; i++) {
            Object p = cur.getParent();
            if (!(p instanceof View)) break;
            View parent = (View) p;
            if (!looksLikeTipShell(parent)) break;
            hideView(parent);
            shells++;
            cur = parent;
        }
        hit("hideShell", "清字+收壳 " + (shells + 1) + " 层 top=" + cur.getClass().getName());
    }

    static void hideView(View v) {
        markBlocked(v);
        try { v.setVisibility(View.GONE); } catch (Throwable ignored) {}
        try {
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (lp != null && (lp.width != 0 || lp.height != 0)) {
                if (!SAVED_LP.containsKey(v)) {
                    SAVED_LP.put(v, new int[]{ lp.width, lp.height });
                    v.addOnAttachStateChangeListener(DETACH_RESTORE);
                }
                lp.height = 0;
                lp.width = 0;
                v.setLayoutParams(lp);
            }
        } catch (Throwable ignored) {}
    }

    static void restoreView(View v) {
        if (v == null) return;
        try { v.setTag(TAG_BLOCKED, null); } catch (Throwable ignored) {}
        int[] orig = SAVED_LP.remove(v);
        if (orig != null) {
            try {
                ViewGroup.LayoutParams lp = v.getLayoutParams();
                if (lp != null && lp.width == 0 && lp.height == 0) {
                    lp.width = orig[0];
                    lp.height = orig[1];
                    v.setLayoutParams(lp);
                }
            } catch (Throwable ignored) {}
        }
    }

    static void markBlocked(View v) {
        try { v.setTag(TAG_BLOCKED, Boolean.TRUE); } catch (Throwable ignored) {}
    }

    static boolean isBlocked(View v) {
        try { return Boolean.TRUE.equals(v.getTag(TAG_BLOCKED)); } catch (Throwable t) { return false; }
    }

    /**
     * 小提示条启发式：wrap/小高度 + 子少。
     * 聊天列表、输入区、全屏根、new_messages_* 一律 false。
     */
    static boolean looksLikeTipShell(View v) {
        try {
            try {
                int id = v.getId();
                if (id != -1) {
                    String name = v.getResources().getResourceEntryName(id);
                    if (name != null && name.startsWith("new_messages")) return false;
                }
            } catch (Throwable ignored) {}

            ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (lp == null) return false;   // 未附到父容器的 view 无法判断尺寸，保守跳过
            int h = lp.height;
            int w = lp.width;
            if (h == ViewGroup.LayoutParams.MATCH_PARENT) return false;
            if (w == ViewGroup.LayoutParams.MATCH_PARENT
                    && h == ViewGroup.LayoutParams.MATCH_PARENT) return false;

            float density = v.getResources().getDisplayMetrics().density;
            boolean smallH = (h == ViewGroup.LayoutParams.WRAP_CONTENT)
                    || (h > 0 && h <= 120 * density);
            int kids = (v instanceof ViewGroup) ? ((ViewGroup) v).getChildCount() : 1;
            return smallH && kids <= 10;
        } catch (Throwable t) {
            return false;
        }
    }
}
