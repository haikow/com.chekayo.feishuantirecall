package com.chekayo.feishuantirecall;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

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
 * 【实现】真机锚点：文案以「消息速览」开头；控件
 *   com.ss.android.lark.knowledgeai.cell.cot.ShineTextView（版本漂移时文案兜底）。
 * 三层拦截：
 *   1) ViewGroup.addView    —— 浮层根/小壳在挂树前拦掉
 *   2) View.setVisibility   —— 仅对「速览节点本身 / 小提示条」强制 GONE（不误伤大容器）
 *   3) TextView.setText     —— 命中后清空文案 + 向上收壳，杜绝空框与反复重绘
 *
 * 开关：Config.blockaipeek（默认开），设置面板「屏蔽消息速览」。
 */
public final class AiPeekBlock {
    private AiPeekBlock() {}

    /** setTag 键：已屏蔽过的节点，避免 setVisibility 反复重拦刷日志。 */
    static final int TAG_BLOCKED = 0x70e0b10c;

    static final java.util.Set<TextView> BUSY = java.util.Collections.synchronizedSet(
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<TextView, Boolean>()));

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

    public static void install() {
        // ① addView：速览浮层挂树前拦掉
        try {
            XC_MethodHook addHook = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (!Config.blockaipeek) return;
                        if (p.args == null || !(p.args[0] instanceof View)) return;
                        View child = (View) p.args[0];
                        // 只拦「速览节点 / 小壳」，绝不因大树里夹带速览而丢掉整页
                        if (isPeekNode(child) || (looksLikeTipShell(child) && subtreeHasPeekText(child, 0))) {
                            markBlocked(child);
                            hit("addView", "拦下浮层 class=" + child.getClass().getName());
                            p.setResult(null);
                        }
                    } catch (Throwable ignored) {}
                }
            };
            try { XposedHelpers.findAndHookMethod(ViewGroup.class, "addView", View.class, addHook); } catch (Throwable ignored) {}
            try { XposedHelpers.findAndHookMethod(ViewGroup.class, "addView", View.class, int.class, addHook); } catch (Throwable ignored) {}
            try { XposedHelpers.findAndHookMethod(ViewGroup.class, "addView", View.class, ViewGroup.LayoutParams.class, addHook); } catch (Throwable ignored) {}
            try { XposedHelpers.findAndHookMethod(ViewGroup.class, "addView", View.class, int.class, ViewGroup.LayoutParams.class, addHook); } catch (Throwable ignored) {}
            XposedBridge.log("[fucklark] 消息速览屏蔽: ViewGroup.addView 已 hook (进程 "
                    + AntiRecall.currentProcessName() + ")");
        } catch (Throwable t) {
            XposedBridge.log("[fucklark] peek addView hook failed: " + t);
        }

        // ② setVisibility：仅速览节点/小壳 → GONE（大容器即使含速览也不动）
        try {
            XposedHelpers.findAndHookMethod(View.class, "setVisibility", int.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (!Config.blockaipeek) return;
                        int vis = (Integer) p.args[0];
                        if (vis != View.VISIBLE) return;
                        View v = (View) p.thisObject;
                        boolean block = isBlocked(v) || isPeekNode(v)
                                || (looksLikeTipShell(v) && subtreeHasPeekText(v, 0));
                        if (!block) return;
                        markBlocked(v);
                        hit("setVisibility", "强制GONE class=" + v.getClass().getName());
                        p.args[0] = View.GONE;
                    } catch (Throwable ignored) {}
                }
            });
            XposedBridge.log("[fucklark] 消息速览屏蔽: View.setVisibility 已 hook (进程 "
                    + AntiRecall.currentProcessName() + ")");
        } catch (Throwable t) {
            XposedBridge.log("[fucklark] peek setVis hook failed: " + t);
        }

        // ③ setText：命中则清字 + 收壳（解决空框）
        try {
            XC_MethodHook h = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        if (!Config.blockaipeek) return;
                        CharSequence src = null;
                        if (p.args != null && p.args.length > 0 && p.args[0] instanceof CharSequence) {
                            src = (CharSequence) p.args[0];
                        }
                        if (src == null) return;
                        String s = src.toString();
                        if (!isPeekText(s) && !isPeekNode((View) p.thisObject)) return;
                        TextView tv = (TextView) p.thisObject;
                        if (BUSY.contains(tv)) return;
                        BUSY.add(tv);
                        try {
                            String brief = s.trim();
                            if (brief.length() > 24) brief = brief.substring(0, 24);
                            hit("setText", "文案=" + brief + " class=" + tv.getClass().getName());
                            hideTipShell(tv);
                        } finally {
                            BUSY.remove(tv);
                        }
                    } catch (Throwable ignored) {}
                }
            };
            try {
                XposedHelpers.findAndHookMethod(TextView.class, "setText",
                        CharSequence.class, TextView.BufferType.class, h);
            } catch (Throwable ignored) {}
            try {
                XposedHelpers.findAndHookMethod(TextView.class, "setText",
                        CharSequence.class, h);
            } catch (Throwable ignored) {}
            XposedBridge.log("[fucklark] 消息速览屏蔽: TextView.setText 已 hook (进程 "
                    + AntiRecall.currentProcessName() + ")");
        } catch (Throwable t) {
            XposedBridge.log("[fucklark] peek setText hook failed: " + t);
        }
    }

    /** 文案锚点：以「消息速览」开头。不含「屏蔽消息速览」「显示 AI 速览」「N 条新消息」。 */
    static boolean isPeekText(String s) {
        if (s == null || s.length() == 0) return false;
        return s.trim().startsWith("消息速览");
    }

    /** 类名锚点（真机验证）：knowledgeai / CoT / ShineTextView。 */
    static boolean isPeekClass(View v) {
        try {
            String n = v.getClass().getName();
            return n.contains("knowledgeai") || n.contains("ShineTextView") || n.contains(".cot.");
        } catch (Throwable t) {
            return false;
        }
    }

    /** 是否速览节点本身（文案或类名命中），不含「大树里夹带」。 */
    static boolean isPeekNode(View v) {
        if (v == null) return false;
        try {
            if (v instanceof TextView) {
                CharSequence cs = ((TextView) v).getText();
                if (cs != null && isPeekText(cs.toString())) return true;
            }
            return isPeekClass(v);
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean subtreeHasPeekText(View v, int depth) {
        if (v == null || depth > 4) return false;
        try {
            if (isPeekNode(v)) return true;
            if (v instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) v;
                int n = Math.min(vg.getChildCount(), 16);
                for (int i = 0; i < n; i++) {
                    if (subtreeHasPeekText(vg.getChildAt(i), depth + 1)) return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
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
            if (lp != null) {
                lp.height = 0;
                lp.width = 0;
                v.setLayoutParams(lp);
            }
        } catch (Throwable ignored) {}
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
            if (lp == null) return true;
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
