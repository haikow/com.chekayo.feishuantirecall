package com.chekayo.feishuantirecall;

import android.app.*;
import android.os.Bundle;
import android.content.*;

/** 扫描成功且缓存已经落盘后，只在当前宿主前台显示一次重启入口。 */
final class AnchorRestart {
    private static boolean pending, showing;
    static void offer(final Application app) {
        pending = true;
        Diag.w("锚点重启: 等待宿主 Activity 恢复前台，显示重启入口");
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            public void onActivityResumed(Activity activity) { show(activity); }
            public void onActivityCreated(Activity a, Bundle b) {}
            public void onActivityStarted(Activity a) {}
            public void onActivityPaused(Activity a) {}
            public void onActivityStopped(Activity a) {}
            public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            public void onActivityDestroyed(Activity a) { showing = false; }
        });
    }
    private static void show(final Activity activity) {
        if (!pending || showing || activity.isFinishing()) return;
        showing = true;
        Diag.w("锚点重启: 已显示重启提示");
        new AlertDialog.Builder(activity).setTitle("适配扫描完成")
                .setMessage("消息时间锚点已保存。重启应用后直接加载缓存；应用更新后会重新扫描。")
                .setPositiveButton("重启应用", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        Intent launch = activity.getPackageManager().getLaunchIntentForPackage(activity.getPackageName());
                        if (launch == null) { Diag.w("锚点重启: 未找到宿主启动入口"); showing = false; return; }
                        Intent restart = Intent.makeRestartActivityTask(launch.getComponent());
                        PendingIntent intent = PendingIntent.getActivity(activity, 8734, restart,
                                PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                        try {
                            // 使用非精确闹钟，不申请精确闹钟权限。系统可能延迟重新拉起。
                            AlarmManager alarm = (AlarmManager) activity.getSystemService(Context.ALARM_SERVICE);
                            alarm.set(AlarmManager.ELAPSED_REALTIME, android.os.SystemClock.elapsedRealtime() + 1500, intent);
                            Diag.w("锚点重启: 已安排重新拉起，即将结束当前主进程");
                            pending = false;
                            activity.finishAffinity();
                            android.os.Process.killProcess(android.os.Process.myPid());
                        } catch (Throwable t) {
                            Diag.w("锚点重启: 自动拉起失败，请手动打开 " + t);
                            showing = false;
                            android.widget.Toast.makeText(activity, "请从桌面重新打开应用", android.widget.Toast.LENGTH_LONG).show();
                        }
                    }
                }).setNegativeButton("稍后重启", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        Diag.w("锚点重启: 用户选择稍后重启"); pending = false; showing = false;
                    }
                }).setCancelable(false).show();
    }
    private AnchorRestart() {}
}
