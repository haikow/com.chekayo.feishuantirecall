package com.chekayo.feishuantirecall;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/**
 * 统一视觉规范：深浅色自适应配色 + 设置行/分组/卡片构件。
 * 所有设置面板共用，避免各处硬编码颜色与间距。
 */
final class Ui {
    private Ui() { }

    // ── 配色（深色为默认，浅色覆盖）─────────────────────────────────
    static final int ACCENT = 0xFF3B9EFF;
    static final int ACCENT_DIM = 0xFF2A7FD4;
    static final int DANGER = 0xFFFF3B30;
    static final int SUCCESS = 0xFF34C759;
    static final int WARNING = 0xFFFF9500;

    static boolean isNight(Context ctx) {
        try {
            int m = ctx.getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            return m == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) { return true; }
    }

    static int bg(Context c)        { return isNight(c) ? 0xFF101522 : 0xFFF0F4FC; }
    static int cardBg(Context c)    { return isNight(c) ? 0xE5263042 : 0xEDFFFFFF; }
    static int titleColor(Context c){ return isNight(c) ? 0xFFF2F2F7 : 0xFF111111; }
    static int subColor(Context c)  { return isNight(c) ? 0xFF9AA0A6 : 0xFF6B7280; }
    static int muteColor(Context c) { return isNight(c) ? 0xFF6B7280 : 0xFF9CA3AF; }
    static int divider(Context c)   { return isNight(c) ? 0x22FFFFFF : 0x11000000; }
    static int sectionColor(Context c) { return isNight(c) ? 0xFF7EB6FF : 0xFF2A7FD4; }

    static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }

    // ── 容器 ────────────────────────────────────────────────────────

    /** 整页根容器：垂直列表 + 页面背景色。 */
    static LinearLayout page(Context c) {
        LinearLayout v = new LinearLayout(c);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                isNight(c) ? new int[]{0xFF172338, 0xFF161B2A, 0xFF211B32}
                        : new int[]{0xFFE7F0FF, 0xFFF3F5FC, 0xFFF2EAFE}));
        return v;
    }

    /** 圆角卡片：包住一组设置行。 */
    static LinearLayout card(Context c) {
        LinearLayout v = new LinearLayout(c);
        v.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable d = glass(c, 24, true);
        v.setBackground(d);
        v.setElevation(dp(c, 3));
        v.setClipToOutline(true);
        int p = dp(c, 4);
        v.setPadding(p, dp(c, 2), p, dp(c, 2));
        return v;
    }

    static GradientDrawable glass(Context c, int radius, boolean translucent) {
        boolean night = isNight(c);
        int[] colors = translucent
                ? (night ? new int[]{0xE5323D51, 0xDC202A3A} : new int[]{0xF5FFFFFF, 0xDBF7FAFF})
                : (night ? new int[]{0xFF293447, 0xFF202939} : new int[]{0xFFFFFFFF, 0xFFF3F7FF});
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR, colors);
        d.setCornerRadius(dp(c, radius));
        d.setStroke(dp(c, 1), night ? 0x38FFFFFF : 0xCCFFFFFF);
        return d;
    }

    static void styleDialog(final android.app.AlertDialog dialog) {
        final android.view.Window window = dialog.getWindow();
        if (window == null) return;
        final Context c = dialog.getContext();
        window.setBackgroundDrawable(glass(c, 28, false));
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(0.24f);
        if (Build.VERSION.SDK_INT >= 31) {
            try { Blur31.apply(window, c); }
            catch (Throwable t) { android.util.Log.w("fucklark", "系统模糊不可用，使用清晰背景", t); }
        }
    }

    /** 将新系统 API 放在独立类中，旧安卓仍使用不透明卡片。 */
    private static class Blur31 {
        static void apply(final android.view.Window window, final Context c) {
            final android.view.WindowManager wm = (android.view.WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) return;
            final java.util.function.Consumer<Boolean> changed = new java.util.function.Consumer<Boolean>() {
                @Override public void accept(Boolean enabled) {
                    window.setBackgroundDrawable(glass(c, 28, enabled));
                    window.setBackgroundBlurRadius(enabled ? dp(c, 28) : 0);
                    window.setDimAmount(enabled ? 0.16f : 0.28f);
                }
            };
            changed.accept(wm.isCrossWindowBlurEnabled());
            final View decor = window.getDecorView();
            wm.addCrossWindowBlurEnabledListener(changed);
            decor.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View v) {}
                @Override public void onViewDetachedFromWindow(View v) {
                    wm.removeCrossWindowBlurEnabledListener(changed);
                    v.removeOnAttachStateChangeListener(this);
                }
            });
        }
    }

    /** 分组小标题（卡片上方留白）。 */
    static View section(Context c, String text) {
        TextView h = new TextView(c);
        h.setText(text);
        h.setTextSize(13);
        h.setTextColor(sectionColor(c));
        h.setPadding(dp(c, 16), dp(c, 18), dp(c, 16), dp(c, 8));
        return h;
    }

    /** 1px 分隔线。 */
    static View dividerRow(Context c) {
        View v = new View(c);
        v.setBackgroundColor(divider(c));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.leftMargin = dp(c, 16);
        return setParams(v, lp);
    }

    private static View setParams(View v, ViewGroup.LayoutParams lp) {
        v.setLayoutParams(lp);
        return v;
    }

    // ── 设置行 ──────────────────────────────────────────────────────

    interface OnToggle { void on(boolean b); }

    /**
     * 开关行：左标题+副标题，右 Switch。
     * 这是主设置行样式——标题 15sp、副标题 12sp、行高约 56–72dp。
     */
    static View switchRow(Context c, String title, String sub, boolean checked, final OnToggle cb) {
        return switchRow(c, title, sub, checked, ACCENT, cb);
    }

    static View switchRow(Context c, String title, String sub, boolean checked, int accent,
                          final OnToggle cb) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackground(new android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(isNight(c) ? 0x223B9EFF : 0x163B9EFF), null, null));
        row.setMinimumHeight(dp(c, 64));
        row.setGravity(Gravity.CENTER_VERTICAL);
        int px = dp(c, 16), py = dp(c, 12);
        row.setPadding(px, py, px, py);

        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView tv = new TextView(c);
        tv.setText(title);
        tv.setTextSize(15);
        tv.setTextColor(titleColor(c));
        texts.addView(tv);
        if (sub != null && !sub.isEmpty()) {
            TextView st = new TextView(c);
            st.setText(sub);
            st.setTextSize(12);
            st.setTextColor(subColor(c));
            st.setPadding(0, dp(c, 3), 0, 0);
            texts.addView(st);
        }
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.rightMargin = dp(c, 16);
        row.addView(texts, tlp);

        final Switch sw = new Switch(c);
        sw.setChecked(checked);
        tintSwitch(sw, accent);
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean isChecked) {
                if (cb != null) cb.on(isChecked);
            }
        });
        row.addView(sw);
        return row;
    }

    /** 导航行：左标题+副标题，右 ›。整行可点。 */
    static View navRow(Context c, String title, String sub, int accent, View.OnClickListener cl) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackground(new android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(isNight(c) ? 0x223B9EFF : 0x163B9EFF), null, null));
        row.setMinimumHeight(dp(c, 64));
        row.setGravity(Gravity.CENTER_VERTICAL);
        int px = dp(c, 16), py = dp(c, 12);
        row.setPadding(px, py, px, py);
        row.setOnClickListener(cl);
        if (cl != null) row.setClickable(true);

        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView tv = new TextView(c);
        tv.setText(title);
        tv.setTextSize(15);
        tv.setTextColor(accent != 0 ? accent : titleColor(c));
        texts.addView(tv);
        if (sub != null && !sub.isEmpty()) {
            TextView st = new TextView(c);
            st.setText(sub);
            st.setTextSize(12);
            st.setTextColor(subColor(c));
            st.setPadding(0, dp(c, 3), 0, 0);
            texts.addView(st);
        }
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.rightMargin = dp(c, 12);
        row.addView(texts, tlp);

        TextView chev = new TextView(c);
        chev.setText("›");
        chev.setTextSize(20);
        chev.setTextColor(muteColor(c));
        row.addView(chev);
        return row;
    }

    /** 缩进子行（挂在某个开关下方的二级操作）。 */
    static void indent(View row, Context c) {
        row.setPadding(dp(c, 36), dp(c, 8), dp(c, 16), dp(c, 8));
    }

    /** 页脚版本号。 */
    static View footer(Context c, String text) {
        TextView v = new TextView(c);
        v.setText(text);
        v.setTextSize(12);
        v.setGravity(Gravity.CENTER);
        v.setTextColor(muteColor(c));
        v.setPadding(dp(c, 16), dp(c, 24), dp(c, 16), dp(c, 20));
        return v;
    }

    // ── Switch 着色（API 21+）──────────────────────────────────────
    private static void tintSwitch(Switch sw, int accent) {
        if (Build.VERSION.SDK_INT < 21) return;
        try {
            int trackOn = (accent & 0x00FFFFFF) | 0x66000000;
            int trackOff = 0x33808080;
            sw.setThumbTintList(new ColorStateList(new int[][] {
                    new int[] { android.R.attr.state_checked },
                    new int[] {}
            }, new int[] { accent, Color.GRAY }));
            sw.setTrackTintList(new ColorStateList(new int[][] {
                    new int[] { android.R.attr.state_checked },
                    new int[] {}
            }, new int[] { trackOn, trackOff }));
        } catch (Throwable ignored) { }
    }
}
