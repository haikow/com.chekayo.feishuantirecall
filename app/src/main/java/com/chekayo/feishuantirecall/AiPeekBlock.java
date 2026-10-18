package com.chekayo.feishuantirecall;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import io.github.libxposed.api.XposedInterface;

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
 * 两层拦截：
 *   1) ViewGroup.addView    —— 仅拦已确认含「消息速览」文案的节点（addView 时已有文案才拦）
 *   2) View.setVisibility   —— 对已被 hideTipShell 标记的节点强制 GONE
 *   3) TextView.setText     —— 文案命中「消息速览」后清字 + 向上收壳（主路径）
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
            ModuleLog.log("[fucklark] 消息速览命中#" + hitCount + " via=" + via
                    + " " + detail + " (进程 " + AntiRecall.currentProcessName() + ")");
        } catch (Throwable ignored) {}
    }

    public static void install() {
        // ① addView：速览浮层挂树前拦掉
        try {
            XposedInterface.Hooker addHook = new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    // legacy: 条件不命中放行原方法；命中 setResult(null) 拦下 addView(void 短路)。
                    // 只有模块自身的判定逻辑允许吞异常；放行/短路在 try 外恰好执行一次——
                    // proceed 若处于吞异常范围内，宿主异常被吞后原方法会被再执行一次（审计 F1）。
                    Object[] args = chain.getArgs().toArray();
                    boolean block = false;
                    try {
                        if (Config.blockaipeek && args.length > 0 && (args[0] instanceof View)) {
                            View child = (View) args[0];
                            // 只拦确认是速览节点本身（含 peek 类名/文案）；shell 隐藏由 setText→hideTipShell 负责
                            if (isPeekNode(child)) {
                                BlockLog.record("消息速览", child, "挂载拦截");
                                markBlocked(child);
                                hit("addView", "拦下浮层 class=" + child.getClass().getName());
                                block = true;
                            }
                        }
                    } catch (Throwable ignored) {}
                    return block ? null : chain.proceed(args);
                }
            };
            try { HookRuntime.hookMethod(ViewGroup.class, "addView", new Class<?>[]{View.class}, "aipeek.addView.1", addHook); } catch (Throwable ignored) {}
            try { HookRuntime.hookMethod(ViewGroup.class, "addView", new Class<?>[]{View.class, int.class}, "aipeek.addView.2", addHook); } catch (Throwable ignored) {}
            try { HookRuntime.hookMethod(ViewGroup.class, "addView", new Class<?>[]{View.class, ViewGroup.LayoutParams.class}, "aipeek.addView.3", addHook); } catch (Throwable ignored) {}
            try { HookRuntime.hookMethod(ViewGroup.class, "addView", new Class<?>[]{View.class, int.class, ViewGroup.LayoutParams.class}, "aipeek.addView.4", addHook); } catch (Throwable ignored) {}
            ModuleLog.log("[fucklark] 消息速览屏蔽: ViewGroup.addView 已 hook (进程 "
                    + AntiRecall.currentProcessName() + ")");
        } catch (Throwable t) {
            ModuleLog.log("[fucklark] peek addView hook failed: " + t);
        }

        // ② setVisibility：仅速览节点/小壳 → GONE（大容器即使含速览也不动）
        try {
            HookRuntime.hookMethod(View.class, "setVisibility", new Class<?>[]{int.class}, "aipeek.setVisibility",
                    new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    // legacy: 命中后 p.args[0]=GONE 且原方法继续(proceed 改参)。
                    // 本回调永远放行原方法、只改参；proceed 固定在吞异常的 try 之外恰好一次（审计 F1）。
                    Object[] args = chain.getArgs().toArray();
                    try {
                        if (Config.blockaipeek && args.length > 0
                                && Integer.valueOf(View.VISIBLE).equals(args[0])) {
                            View v = (View) chain.getThisObject();
                            // 仅拦已被 hideTipShell 标记的节点（由 setText 文案命中后标记）
                            if (isBlocked(v)) {
                                markBlocked(v);
                                hit("setVisibility", "强制GONE class=" + v.getClass().getName());
                                args[0] = View.GONE;
                            }
                        }
                    } catch (Throwable ignored) {}
                    return chain.proceed(args);
                }
            });
            ModuleLog.log("[fucklark] 消息速览屏蔽: View.setVisibility 已 hook (进程 "
                    + AntiRecall.currentProcessName() + ")");
        } catch (Throwable t) {
            ModuleLog.log("[fucklark] peek setVis hook failed: " + t);
        }

        // ③ setText：命中则清字 + 收壳（解决空框）
        try {
            XposedInterface.Hooker h = new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    // legacy after-hook：原方法先执行，回调异常不影响返回值
                    Object result = chain.proceed();
                    try {
                        if (!Config.blockaipeek) return result;
                        Object[] args = chain.getArgs().toArray();
                        CharSequence src = null;
                        if (args.length > 0 && args[0] instanceof CharSequence) {
                            src = (CharSequence) args[0];
                        }
                        if (src == null) return result;
                        String s = src.toString();
                        if (!isPeekText(s)) return result;
                        TextView tv = (TextView) chain.getThisObject();
                        if (BUSY.contains(tv)) return result;
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
                    } catch (Throwable ignored) {}
                    return result;
                }
            };
            try {
                HookRuntime.hookMethod(TextView.class, "setText",
                        new Class<?>[]{CharSequence.class, TextView.BufferType.class}, "aipeek.setText.2args", h);
            } catch (Throwable ignored) {}
            try {
                HookRuntime.hookMethod(TextView.class, "setText",
                        new Class<?>[]{CharSequence.class}, "aipeek.setText.1arg", h);
            } catch (Throwable ignored) {}
            ModuleLog.log("[fucklark] 消息速览屏蔽: TextView.setText 已 hook (进程 "
                    + AntiRecall.currentProcessName() + ")");
        } catch (Throwable t) {
            ModuleLog.log("[fucklark] peek setText hook failed: " + t);
        }
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

    /**
     * 类名锚点：已确认 ShineTextView 同时用于搜索 AI 卡片（AIRoundViewImpl2），
     * 不能作为全局拦截依据；.cot. 包亦同理。
     * 所有拦截均改为文案锚点（isPeekText），此方法保留但始终返回 false。
     */
    static boolean isPeekClass(View v) {
        return false;
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
