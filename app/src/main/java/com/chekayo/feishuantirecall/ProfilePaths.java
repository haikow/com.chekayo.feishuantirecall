package com.chekayo.feishuantirecall;

import android.content.Context;
import java.io.File;

/** 当前 Android 用户的私有目录。分身不能回退到机主的 /data/data 或 /data/user/0。 */
final class ProfilePaths {
    static int userId() { return android.os.Process.myUid() / 100000; }

    static File dataDir(String pkg) {
        Context context = Config.appContextForSync();
        if (context != null && pkg.equals(context.getPackageName()))
            return context.getFilesDir().getParentFile();
        // 模块入口早于 Application 就绪时，按当前进程 UID 取用户，绝不跨用户猜路径。
        return new File("/data/user/" + userId() + "/" + pkg);
    }

    static File filesDir(String pkg) { return new File(dataDir(pkg), "files"); }
    private ProfilePaths() {}
}
