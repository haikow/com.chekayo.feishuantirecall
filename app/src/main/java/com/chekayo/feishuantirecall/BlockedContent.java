package com.chekayo.feishuantirecall;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.util.LinkedHashSet;

/** 在隐藏前读取实际显示的文字，包括自绘推荐条；不访问聊天数据库。 */
final class BlockedContent {

    static String read(View view) {
        LinkedHashSet<String> text = new LinkedHashSet<String>();
        collect(view, text, 0, new int[] { 48 });
        StringBuilder out = new StringBuilder();
        for (String value : text) {
            if (out.length() > 0) out.append('\n');
            out.append(value);
            if (out.length() >= 6000) break;
        }
        return out.length() > 6000 ? out.substring(0, 6000) : out.toString();
    }

    private static void collect(View view, LinkedHashSet<String> text, int depth, int[] budget) {
        if (depth > 5 || --budget[0] < 0) return;
        if (view instanceof TextView) add(text, ((TextView) view).getText().toString());
        // 自绘控件通常保留 Spannable / StaticLayout；只读取文字字段，不遍历业务对象。
        if (!(view instanceof TextView) && !(view instanceof ViewGroup)) {
            for (Class<?> type = view.getClass(); type != null && type != View.class; type = type.getSuperclass()) {
                for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                    try {
                        if (!CharSequence.class.isAssignableFrom(field.getType())
                                && !android.text.Layout.class.isAssignableFrom(field.getType())) continue;
                        if (field.getType() == String.class) {
                            String name = field.getName().toLowerCase(java.util.Locale.ROOT);
                            if (!name.contains("text") && !name.contains("content") && !name.contains("title")
                                    && !name.contains("summary") && !name.contains("label")) continue;
                        }
                        field.setAccessible(true);
                        Object value = field.get(view);
                        if (value instanceof android.text.Layout) add(text, ((android.text.Layout) value).getText().toString());
                        else if (value instanceof CharSequence) add(text, value.toString());
                    } catch (Throwable ignored) {}
                }
            }
        }
        if (view.getContentDescription() != null) add(text, view.getContentDescription().toString());
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount() && budget[0] > 0; i++)
                collect(group.getChildAt(i), text, depth + 1, budget);
        }
    }
    private static void add(LinkedHashSet<String> text, String value) {
        if (value != null && !value.trim().isEmpty() && text.size() < 64) text.add(value.trim());
    }
    private BlockedContent() {}
}
