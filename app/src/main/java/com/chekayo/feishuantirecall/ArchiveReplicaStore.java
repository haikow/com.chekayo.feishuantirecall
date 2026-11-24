package com.chekayo.feishuantirecall;

import java.io.File;
import java.io.IOException;

/** 推送的账号由发送方提供，接收进程不以自己缓存的账号猜测归属。 */
final class ArchiveReplicaStore {
    private static final int MAX_BYTES = 16 * 1024 * 1024;

    static synchronized void save(File files, String uid, String name, byte[] bytes) throws Exception {
        if (files == null) throw new IllegalArgumentException("files dir is null");
        if (name == null || !name.matches("[A-Za-z0-9_]+\\.(json|txt)"))
            throw new IllegalArgumentException("invalid archive name");
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES)
            throw new IllegalArgumentException("invalid archive payload size");
        if (name.endsWith(".json") && !looksLikeJson(bytes))
            throw new IllegalArgumentException("invalid json archive payload");
        boolean scoped = uid != null && uid.matches("[A-Za-z0-9_-]+") && !"unknown".equals(uid);
        String relative = name.endsWith(".json") ? "resign_tracker/" + name : name;
        if (scoped) {
            writeBytes(new File(files, "accounts/" + uid + "/" + relative), bytes);
            // 显示账号与实际写入的账号保持一致，进程重启后也能恢复。
            writeText(new File(files, "archive_active_uid.txt"), uid);
            AccountPaths.currentUid = uid;
        }
        writeBytes(new File(files, relative), bytes);
    }

    private static boolean looksLikeJson(byte[] bytes) {
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & 0xFF;
            if (b == ' ' || b == '\t' || b == '\r' || b == '\n') continue;
            // Allow UTF-8 BOM
            if (i == 0 && bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF
                    && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
                i = 2;
                continue;
            }
            return b == '{' || b == '[';
        }
        return false;
    }

    private static void writeBytes(File file, byte[] bytes) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs())
            throw new IOException("cannot create archive directory");
        Config.writeBytes(file, bytes);
    }

    private static void writeText(File file, String text) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs())
            throw new IOException("cannot create archive directory");
        Config.write(file, text);
    }

    private ArchiveReplicaStore() {}
}
