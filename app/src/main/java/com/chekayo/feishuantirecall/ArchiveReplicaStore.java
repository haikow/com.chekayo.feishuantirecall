package com.chekayo.feishuantirecall;

import java.io.File;

/** 推送的账号由发送方提供，接收进程不以自己缓存的账号猜测归属。 */
final class ArchiveReplicaStore {
    static synchronized void save(File files, String uid, String name, byte[] bytes) throws Exception {
        if (name == null || !name.matches("[A-Za-z0-9_]+\\.(json|txt)"))
            throw new IllegalArgumentException("invalid archive name");
        boolean scoped = uid != null && uid.matches("[A-Za-z0-9_-]+") && !"unknown".equals(uid);
        String relative = name.endsWith(".json") ? "resign_tracker/" + name : name;
        String text = new String(bytes, "UTF-8");
        if (scoped) {
            write(new File(files, "accounts/" + uid + "/" + relative), text);
            // 显示账号与实际写入的账号保持一致，进程重启后也能恢复。
            write(new File(files, "archive_active_uid.txt"), uid);
            AccountPaths.currentUid = uid;
        }
        write(new File(files, relative), text);
    }
    private static void write(File file, String text) throws Exception {
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new java.io.IOException("cannot create archive directory");
        Config.write(file, text);
    }
    private ArchiveReplicaStore() {}
}
