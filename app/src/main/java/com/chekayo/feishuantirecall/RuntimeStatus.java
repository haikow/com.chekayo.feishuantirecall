package com.chekayo.feishuantirecall;

import android.content.Context;
import android.os.Bundle;

import java.util.LinkedHashMap;
import java.util.Map;

/** Provider 内收集飞书进程心跳，用实际加载版本诊断未重启的旧模块。 */
final class RuntimeStatus {
    private static final Map<String, Bundle> PROCESSES = new LinkedHashMap<String, Bundle>();
    static synchronized void report(Bundle info) {
        if (info == null || info.getString("process") == null) return;
        Bundle copy = new Bundle(info);
        copy.putLong("seenAt", System.currentTimeMillis());
        PROCESSES.put(info.getString("process"), copy);
    }
    static synchronized String describe() {
        long authority = Config.updatedAt();
        StringBuilder out = new StringBuilder("已安装模块：v" + ModuleBuild.VERSION
                + " (" + ModuleBuild.VERSION_CODE + ")\n构建：" + ModuleBuild.BUILD
                + "\n配置修订：" + authority + "\n");
        if (PROCESSES.isEmpty()) out.append("\n尚未收到飞书进程状态，请启动或重启飞书。\n");
        for (Map.Entry<String, Bundle> entry : PROCESSES.entrySet()) {
            Bundle info = entry.getValue();
            long age = Math.max(0, System.currentTimeMillis() - info.getLong("seenAt")) / 1000;
            out.append("\n").append(entry.getKey()).append("\n实际加载：v")
                    .append(info.getString("version")).append(" · ").append(info.getString("build"))
                    .append("\n配置修订：").append(info.getLong("updatedAt"))
                    .append("\n最近同步：").append(age).append(" 秒前\n");
            if (!ModuleBuild.BUILD.equals(info.getString("build"))) out.append("模块已更新，请重启此应用以加载新版。\n");
            else if (age > 10) out.append("进程暂停或未运行，恢复运行后会自动同步。\n");
            else if (info.getLong("updatedAt") != authority) out.append("正在同步最新配置。\n");
            else out.append("版本与配置一致。\n");
        }
        out.append("\n功能锚点状态：\n消息时间：").append(MessageTimeDisplay.status())
                .append("\n防撤回身份：").append(RecallMessages.status())
                .append("\n撤回气泡：").append(RecallBubbleUi.status()).append("\n");
        return out.toString();
    }
    static void show(final android.content.Context ctx) {
        final android.widget.TextView text = new android.widget.TextView(ctx);
        text.setText("正在读取同步状态…"); text.setTextColor(Ui.titleColor(ctx));
        text.setTextIsSelectable(true); text.setTextSize(13);
        int p = Ui.dp(ctx, 16); text.setPadding(p, p, p, p);
        android.widget.ScrollView scroll = new android.widget.ScrollView(ctx); scroll.addView(text);
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(ctx).setTitle("版本与配置同步").setView(scroll)
                .setPositiveButton("关闭", null).show();
        Ui.styleDialog(dialog);
        new Thread(new Runnable() {
            @Override public void run() {
                String result;
                try {
                    if (Config.MODULE_PKG.equals(ctx.getPackageName())) {
                        Config.load();
                        result = describe();
                    } else {
                        Bundle b = ctx.getContentResolver().call(ConfigProvider.URI, "status", null, null);
                        String remote = b == null ? null : b.getString("text");
                        result = remote != null ? remote : localDescription(ctx);
                    }
                } catch (Throwable t) { result = localDescription(ctx); }
                if (!Config.MODULE_PKG.equals(ctx.getPackageName())) {
                    result += "\n当前进程加载：v" + ModuleBuild.VERSION + " · " + ModuleBuild.BUILD
                            + "\n消息时间锚点：" + MessageTimeDisplay.status()
                            + "\n防撤回身份锚点：" + RecallMessages.status()
                            + "\n撤回气泡锚点：" + RecallBubbleUi.status()
                            + "\nnative 库：" + (AntiRecall.NATIVE_STARTED ? "已加载（实际 SQL 命中见日志）" : "未加载");
                    if (!Config.syncError().isEmpty()) result += "\n同步错误：" + Config.syncError();
                }
                final String display = result;
                text.post(new Runnable() { @Override public void run() { text.setText(display); } });
            }
        }, "config-status").start();
    }
    static String localDescription(Context ctx) {
        String installed;
        try {
            android.content.pm.PackageInfo info = ctx.getPackageManager().getPackageInfo(Config.MODULE_PKG, 0);
            installed = "v" + info.versionName + " (" + info.versionCode + ")";
        } catch (Throwable t) { installed = "暂不可读取"; }
        return "已安装模块：" + installed + "\n当前应用配置修订：" + Config.updatedAt()
                + "\n模块配置源暂不可用，无法核对跨进程同步状态。"
                + "\nAndroid 用户：" + ProfilePaths.userId()
                + "；分身需在同一用户中安装 FeishuKit。\n配置源不可用时继续使用当前应用的本地配置。";
    }
    private RuntimeStatus() {}
}
