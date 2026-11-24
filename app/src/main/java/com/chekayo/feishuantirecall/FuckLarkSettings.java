package com.chekayo.feishuantirecall;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * FeishuKit Xposed 入口：在飞书「设置」页注入独立卡片，仅显示「模块设置」。
 * 字号/图标随同页原生 setting item 自适应；点击弹出 {@link SettingsPanel}。
 *
 * 入口两处：
 * 1) 飞书设置 → 卡片「模块设置」；
 * 2) 桌面图标 / LSPosed 启动 → LauncherActivity。
 */
public class FuckLarkSettings implements IXposedHookLoadPackage {

    static final String PKG_FEISHU = "com.ss.android.lark";
    static final String PKG_LARK = "com.larksuite.suite";
    static volatile String PKG = PKG_FEISHU;
    static final String ROW_TAG = "fucklark_row";
    /** 卡片内唯一可见文案 */
    static final String ROW_TEXT = "模块设置";

    /** 飞书设置页候选类（版本差异时逐个探测）。 */
    static final String[] SETTING_HOOK_TARGETS = {
            "com.ss.android.lark.setting.page.function.SettingPageFragment",
            "com.ss.android.lark.setting.page.SettingPageFragment",
            "com.ss.android.lark.setting.SettingFragment",
    };

    /** 插入位置参考：设置列表末尾/这些条目所在分组之后（兼容中文飞书与国际版 Lark）。 */
    static final String[] ANCHOR_TEXTS = {
            "关于飞书", "退出登录", "实验室", "内部设置",
            "About Lark", "About Feishu", "Log Out", "Labs"
    };

    private static final String[] ABOUT_ANCHORS = { "关于飞书", "About Lark", "About Feishu" };
    private static final String[] LOGOUT_ANCHORS = { "退出登录", "Log Out" };

    static boolean isLarkFamily(String pkg) { return PKG_FEISHU.equals(pkg) || PKG_LARK.equals(pkg); }

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (!isLarkFamily(lpparam.packageName) && !AntiRecall.isLarkApp(lpparam.classLoader)) return;
        PKG = lpparam.packageName;
        DataViews.PKG = PKG;
        DataMigration.install();
        hookSettingPages(lpparam.classLoader);
    }

    /** 多目标 hook：任一设置页类存在即挂 onResume，保证有「模块设置」入口。 */
    static void hookSettingPages(ClassLoader cl) {
        boolean any = false;
        for (String name : SETTING_HOOK_TARGETS) {
            try {
                XposedHelpers.findAndHookMethod(name, cl, "onResume",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            try { inject(param.thisObject); }
                            catch (Throwable t) { XposedBridge.log("[fucklark] inject err " + t); }
                        }
                    });
                XposedBridge.log("[fucklark] 已 hook 设置页 " + name);
                any = true;
            } catch (Throwable ignored) { /* 该版本类名不存在，试下一个 */ }
        }
        if (!any) {
            try {
                XposedHelpers.findAndHookMethod(android.app.Activity.class, "onResume", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            android.app.Activity a = (android.app.Activity) param.thisObject;
                            String n = a.getClass().getName();
                            if (n.contains("setting") || n.contains("Setting")) injectActivity(a);
                        } catch (Throwable ignored) {}
                    }
                });
                XposedBridge.log("[fucklark] 设置页类未命中，已 hook Activity.onResume 兜底");
            } catch (Throwable t) {
                XposedBridge.log("[fucklark] hook 设置入口失败 " + t);
            }
        }
    }

    static void inject(Object fragment) {
        Object v = XposedHelpers.callMethod(fragment, "getView");
        if (!(v instanceof View)) return;
        injectIntoRoot((View) v);
    }

    static void injectActivity(android.app.Activity a) {
        View content = a.findViewById(android.R.id.content);
        if (content != null) injectIntoRoot(content);
    }

    static void injectIntoRoot(final View root) {
        if (!(root instanceof ViewGroup)) return;
        if (root.findViewWithTag(ROW_TAG) != null) return;
        final Context ctx = root.getContext();

        // 等设置列表布局完成再插卡片
        ViewGroup list = findSettingsList((ViewGroup) root);
        if (list != null && list.getWidth() <= 0) {
            root.post(new Runnable() {
                @Override public void run() {
                    try { injectIntoRoot(root); } catch (Throwable ignored) {}
                }
            });
            return;
        }
        if (list == null) {
            View sv = findScrollable((ViewGroup) root);
            if (sv instanceof ViewGroup) list = (ViewGroup) sv;
        }
        ViewGroup parent = list;
        if (parent == null && root instanceof ViewGroup) parent = (ViewGroup) root;
        if (parent == null) return;
        // RecyclerView 时不能随便 addView，取其父或自身能 add 的容器
        String pn = parent.getClass().getName();
        if (pn.contains("RecyclerView") && list != null) {
            if (list.getParent() instanceof ViewGroup) parent = (ViewGroup) list.getParent();
        }

        View card = buildAdvancedCard(ctx, findAnySettingItem((ViewGroup) root));
        int at = findInsertIndex(parent);
        try {
            parent.addView(card, Math.min(at, parent.getChildCount()));
            XposedBridge.log("[fucklark] 高级设置卡片已注入 parent=" + parent.getClass().getSimpleName()
                    + " index=" + at + "/" + parent.getChildCount());
        } catch (Throwable t) {
            try {
                parent.addView(card);
                XposedBridge.log("[fucklark] 高级设置卡片追加到末尾 parent=" + parent.getClass().getSimpleName());
            } catch (Throwable t2) {
                XposedBridge.log("[fucklark] 高级设置卡片注入失败 " + t2);
            }
        }
    }

    /**
     * 独立设置卡片：只显示「模块设置」。
     * 字号 / 文字颜色 / 右侧图标优先参考同页原生 setting item，不同机型自适应。
     */
    static View buildAdvancedCard(final Context ctx, View templateHint) {
        boolean night = Ui.isNight(ctx);

        // 从页面上任意原生设置行取样式
        float textSizePx = 0;
        int textColor = 0;
        android.graphics.drawable.Drawable icon = null;
        int iconW = 0, iconH = 0;
        View hint = templateHint;
        if (hint instanceof ViewGroup) {
            View labV = findByResourceId((ViewGroup) hint, "setting_item_left_text");
            TextView lab = (labV instanceof TextView) ? (TextView) labV : null;
            if (lab == null) lab = findAnyTextView((ViewGroup) hint, ABOUT_ANCHORS);
            if (lab == null) lab = findFirstTextView(hint);
            if (lab != null) {
                textSizePx = lab.getTextSize();
                try { textColor = lab.getCurrentTextColor(); } catch (Throwable ignored) { }
            }
            View iconV = findByResourceId((ViewGroup) hint, "setting_item_right_icon");
            if (iconV instanceof ImageView) {
                ImageView iv = (ImageView) iconV;
                try {
                    android.graphics.drawable.Drawable dd = iv.getDrawable();
                    if (dd != null) {
                        android.graphics.drawable.Drawable.ConstantState cs = dd.getConstantState();
                        icon = cs != null ? cs.newDrawable() : dd;
                    }
                } catch (Throwable ignored) { }
                iconW = iv.getWidth() > 0 ? iv.getWidth() : 44;
                iconH = iv.getHeight() > 0 ? iv.getHeight() : 44;
            }
        }

        LinearLayout wrap = new LinearLayout(ctx);
        wrap.setTag(ROW_TAG);
        wrap.setOrientation(LinearLayout.VERTICAL);
        int side = Ui.dp(ctx, 12);
        int gap = Ui.dp(ctx, 16);
        wrap.setPadding(side, gap, side, gap);
        wrap.setBackgroundColor(Color.TRANSPARENT);

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(night ? 0xFF1C1C1E : 0xFFFFFFFF);
        bg.setCornerRadius(Ui.dp(ctx, 12));
        card.setBackground(bg);
        card.setClipToOutline(true);

        // 仅一行：模块设置（带水波纹点击反馈）
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClickable(true);
        row.setFocusable(true);
        int padH = Ui.dp(ctx, 16);
        int padV = Ui.dp(ctx, 18);
        row.setPadding(padH, padV, padH, padV);
        if (Build.VERSION.SDK_INT >= 21) {
            row.setBackground(new RippleDrawable(
                    ColorStateList.valueOf(night ? 0x22FFFFFF : 0x16000000),
                    null, new ColorDrawable(Color.WHITE)));
        }

        TextView title = new TextView(ctx);
        title.setText(ROW_TEXT);
        if (textSizePx > 0) {
            title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, textSizePx);
        } else {
            title.setTextSize(16);
        }
        if (textColor != 0) title.setTextColor(textColor);
        else title.setTextColor(Ui.titleColor(ctx));
        title.setSingleLine(true);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.rightMargin = Ui.dp(ctx, 12);
        row.addView(title, tlp);

        if (icon != null) {
            ImageView chev = new ImageView(ctx);
            chev.setImageDrawable(icon);
            chev.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            int w = iconW > 0 ? iconW : ViewGroup.LayoutParams.WRAP_CONTENT;
            int h = iconH > 0 ? iconH : ViewGroup.LayoutParams.WRAP_CONTENT;
            try {
                android.graphics.drawable.Drawable dd = chev.getDrawable();
                if (dd != null && iconW > 0 && iconH > 0) dd.setBounds(0, 0, iconW, iconH);
            } catch (Throwable ignored) { }
            row.addView(chev, new LinearLayout.LayoutParams(w, h));
        } else {
            TextView chev = new TextView(ctx);
            chev.setText("›");
            if (textSizePx > 0) {
                chev.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, textSizePx);
            } else {
                chev.setTextSize(16);
            }
            chev.setTextColor(Ui.muteColor(ctx));
            row.addView(chev);
        }

        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showPanel(ctx); }
        });
        card.addView(row);
        wrap.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return wrap;
    }

    /** 找设置页内容列表：优先 setting container / SettingGroupLayout 的父级。 */
    static ViewGroup findSettingsList(ViewGroup root) {
        try {
            int id = root.getResources().getIdentifier("container", "id", PKG);
            if (id != 0) {
                View v = root.findViewById(id);
                if (v instanceof ViewGroup) return (ViewGroup) v;
            }
        } catch (Throwable ignored) {}
        View rv = findScrollable(root);
        return rv instanceof ViewGroup ? (ViewGroup) rv : null;
    }

    /** 取同页任一原生设置行，供参考字号/图标（兼容中文与英文界面）。 */
    static View findAnySettingItem(ViewGroup root) {
        String[] labels = {
                "关于飞书", "实验室", "内部设置", "通用", "通知", "隐私",
                "About Lark", "About Feishu", "Labs", "General", "Notifications", "Privacy"
        };
        for (String lab : labels) {
            TextView tv = findTextView(root, lab);
            if (tv == null) continue;
            View cur = tv;
            int hops = 0;
            while (cur != null && hops < 6) {
                if (!(cur.getParent() instanceof View)) break;
                View p = (View) cur.getParent();
                if (p instanceof ViewGroup && (p.isClickable() || p.isLongClickable())) return p;
                cur = p;
                hops++;
            }
            if (tv.getParent() instanceof View) return (View) tv.getParent();
        }
        return null;
    }

    /** 插入位置：最后一个 SettingGroup / 分组之后；找不到则末尾。 */
    static int findInsertIndex(ViewGroup parent) {
        int n = parent.getChildCount();
        int lastGroup = -1;
        int afterAbout = -1;
        for (int i = 0; i < n; i++) {
            View c = parent.getChildAt(i);
            String cn = c.getClass().getName();
            if (cn.contains("SettingGroup") || cn.contains("SettingItem")
                    || cn.contains("setting") || cn.contains("Setting")) {
                lastGroup = i;
            }
            if (c instanceof ViewGroup && findAnyTextView((ViewGroup) c, ABOUT_ANCHORS) != null) {
                afterAbout = i;
            }
            if (c instanceof ViewGroup && findAnyTextView((ViewGroup) c, LOGOUT_ANCHORS) != null) {
                if (afterAbout < 0) afterAbout = i > 0 ? i - 1 : i;
            }
        }
        if (afterAbout >= 0) return afterAbout + 1;
        if (lastGroup >= 0) return lastGroup + 1;
        return n;
    }

    private static TextView findAnyTextView(ViewGroup vg, String[] texts) {
        for (int i = 0; i < texts.length; i++) {
            TextView tv = findTextView(vg, texts[i]);
            if (tv != null) return tv;
        }
        return null;
    }

    static TextView findFirstTextView(View v) {
        if (v instanceof TextView) return (TextView) v;
        if (!(v instanceof ViewGroup)) return null;
        ViewGroup vg = (ViewGroup) v;
        int n = vg.getChildCount();
        for (int i = 0; i < n; i++) {
            TextView r = findFirstTextView(vg.getChildAt(i));
            if (r != null) return r;
        }
        return null;
    }

    static TextView findTextView(ViewGroup vg, String exact) {
        int n = vg.getChildCount();
        for (int i = 0; i < n; i++) {
            View c = vg.getChildAt(i);
            if (c instanceof TextView) {
                CharSequence t = ((TextView) c).getText();
                if (t != null && exact.contentEquals(t)) return (TextView) c;
            }
            if (c instanceof ViewGroup) {
                TextView r = findTextView((ViewGroup) c, exact);
                if (r != null) return r;
            }
        }
        return null;
    }

    /** 按 resource-id 后缀在树中查找（无需依赖宿主 R 类）。 */
    static View findByResourceId(ViewGroup root, String entryName) {
        if (root == null || entryName == null) return null;
        try {
            int id = root.getId();
            if (id != 0 && id != View.NO_ID) {
                String name = root.getResources().getResourceEntryName(id);
                if (entryName.equals(name)) return root;
            }
        } catch (Throwable ignored) {}
        int n = root.getChildCount();
        for (int i = 0; i < n; i++) {
            View c = root.getChildAt(i);
            if (c instanceof ViewGroup) {
                View r = findByResourceId((ViewGroup) c, entryName);
                if (r != null) return r;
            } else {
                try {
                    int id = c.getId();
                    if (id != 0 && id != View.NO_ID) {
                        String name = c.getResources().getResourceEntryName(id);
                        if (entryName.equals(name)) return c;
                    }
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    static View findScrollable(ViewGroup vg) {
        View sv = null;
        for (int i = 0; i < vg.getChildCount(); i++) {
            View c = vg.getChildAt(i);
            String cn = c.getClass().getName();
            if (cn.contains("RecyclerView")) return c;
            if (sv == null && cn.contains("ScrollView")) sv = c;
            if (c instanceof ViewGroup) {
                View r = findScrollable((ViewGroup) c);
                if (r != null && r.getClass().getName().contains("RecyclerView")) return r;
                if (sv == null && r != null) sv = r;
            }
        }
        return sv;
    }

    static void showPanel(final Context ctx) {
        SettingsPanel.show(ctx);
    }
}
