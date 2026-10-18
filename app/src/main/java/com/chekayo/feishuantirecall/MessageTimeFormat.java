package com.chekayo.feishuantirecall;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class MessageTimeFormat {
    static final String DEFAULT = "yyyy-MM-dd HH:mm:ss";
    static boolean valid(String pattern) {
        if (pattern == null || pattern.trim().isEmpty() || pattern.length() > 80) return false;
        try { new SimpleDateFormat(pattern.replace("{relative}", "日期"), Locale.getDefault()); return true; }
        catch (IllegalArgumentException e) { return false; }
    }
    static String format(long seconds, String pattern) {
        if (seconds <= 0 || seconds > Long.MAX_VALUE / 1000) return "";
        return formatAt(seconds, pattern, System.currentTimeMillis());
    }
    static String formatAt(long seconds, String pattern, long now) {
        if (seconds <= 0 || seconds > Long.MAX_VALUE / 1000) return "";
        String selected = valid(pattern) ? pattern : DEFAULT;
        String relative = relativeDay(seconds * 1000, now);
        return new SimpleDateFormat(selected.replace("{relative}", "'" + relative + "'"), Locale.getDefault())
                .format(new Date(seconds * 1000));
    }
    private static String relativeDay(long millis, long now) {
        java.util.Calendar date = java.util.Calendar.getInstance(); date.setTimeInMillis(millis);
        java.util.Calendar today = java.util.Calendar.getInstance(); today.setTimeInMillis(now);
        if (sameDay(date, today)) return "今天";
        java.util.Calendar yesterday = (java.util.Calendar) today.clone(); yesterday.add(java.util.Calendar.DATE, -1);
        if (sameDay(date, yesterday)) return "昨天";
        java.util.Calendar before = (java.util.Calendar) today.clone(); before.add(java.util.Calendar.DATE, -2);
        if (sameDay(date, before)) return "前天";
        java.util.Calendar monday = (java.util.Calendar) today.clone();
        monday.set(java.util.Calendar.HOUR_OF_DAY, 0); monday.set(java.util.Calendar.MINUTE, 0);
        monday.set(java.util.Calendar.SECOND, 0); monday.set(java.util.Calendar.MILLISECOND, 0);
        monday.add(java.util.Calendar.DATE, -((monday.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7));
        java.util.Calendar next = (java.util.Calendar) monday.clone(); next.add(java.util.Calendar.DATE, 7);
        java.util.Calendar last = (java.util.Calendar) monday.clone(); last.add(java.util.Calendar.DATE, -7);
        String weekday = new String[]{"周日", "周一", "周二", "周三", "周四", "周五", "周六"}
                [date.get(java.util.Calendar.DAY_OF_WEEK) - 1];
        if (millis >= monday.getTimeInMillis() && millis < next.getTimeInMillis()) return weekday;
        if (millis >= last.getTimeInMillis() && millis < monday.getTimeInMillis()) return "上" + weekday;
        return new SimpleDateFormat(date.get(java.util.Calendar.YEAR) == today.get(java.util.Calendar.YEAR)
                ? "MM-dd" : "yyyy-MM-dd", Locale.getDefault()).format(new Date(millis));
    }
    private static boolean sameDay(java.util.Calendar a, java.util.Calendar b) {
        return a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR)
                && a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR);
    }
    private MessageTimeFormat() {}
}
