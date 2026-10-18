package com.chekayo.feishuantirecall;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** 撤回标记按账号异步落盘；UI 查询只读内存索引，不等待磁盘。 */
final class RecallMarkStore {
    interface Disk {
        String[] accounts(File directory);
        Set<String> read(File directory, String uid) throws IOException;
        void write(File directory, String uid, Set<String> marks) throws IOException;
    }
    static final class FileDisk implements Disk {
        public String[] accounts(File directory) {
            String[] names = directory.list();
            if (names == null) return new String[0];
            List<String> ids = new ArrayList<String>();
            for (String name : names) if (name.endsWith(".marks")) {
                String uid = name.substring(0, name.length() - 6);
                if (RecallMessages.positive(uid) > 0) ids.add(uid);
            }
            return ids.toArray(new String[0]);
        }
        public Set<String> read(File directory, String uid) throws IOException {
            Set<String> marks = new HashSet<String>();
            File source = new File(directory, uid + ".marks");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(source), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split(":", -1);
                    if (parts.length == 2 && RecallMessages.key(uid, parts[0], parts[1]) != null) marks.add(line);
                }
            } catch (FileNotFoundException failure) { if (source.exists()) throw failure; }
            return marks;
        }
        public void write(File directory, String uid, Set<String> marks) throws IOException {
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("marker directory");
            File target = new File(directory, uid + ".marks"), temp = new File(directory, uid + ".tmp");
            try (FileOutputStream bytes = new FileOutputStream(temp);
                    Writer writer = new OutputStreamWriter(bytes, "UTF-8")) {
                for (String mark : marks) { writer.write(mark); writer.write('\n'); }
                writer.flush(); bytes.getFD().sync();
            }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
    }
    private static final class Account {
        final Set<String> marks = ConcurrentHashMap.newKeySet();
        final Set<String> messages = ConcurrentHashMap.newKeySet();
        final AtomicBoolean loadQueued = new AtomicBoolean();
        volatile boolean loaded;
        void insert(String mark) { marks.add(mark); messages.add(mark.substring(mark.indexOf(':') + 1)); }
    }
    private final File directory;
    private final Disk disk;
    private final ScheduledExecutorService worker;
    private final ConcurrentHashMap<String, Account> accounts = new ConcurrentHashMap<String, Account>();
    private final Set<String> dirty = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean flushQueued = new AtomicBoolean();

    RecallMarkStore(File directory) { this(directory, new FileDisk()); }
    RecallMarkStore(File directory, Disk disk) {
        this.directory = directory; this.disk = disk;
        worker = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            public Thread newThread(Runnable task) {
                Thread thread = new Thread(task, "FeishuKit-recall-index");
                thread.setDaemon(true);
                return thread;
            }
        });
        worker.execute(new Runnable() {
            public void run() { for (String uid : RecallMarkStore.this.disk.accounts(directory)) load(uid, account(uid)); }
        });
    }
    private Account account(final String uid) {
        Account found = accounts.get(uid);
        if (found == null) {
            Account created = new Account();
            Account old = accounts.putIfAbsent(uid, created);
            found = old == null ? created : old;
        }
        final Account value = found;
        if (!value.loaded && value.loadQueued.compareAndSet(false, true)) worker.execute(new Runnable() {
            public void run() { load(uid, value); }
        });
        return value;
    }
    private void load(String uid, Account value) {
        if (value.loaded) return;
        try {
            for (String mark : disk.read(directory, uid)) value.insert(mark);
            value.loaded = true;
        } catch (IOException failure) {
            ModuleLog.log("[fucklark] recall marker read failed: " + failure.getClass().getSimpleName());
        } finally { value.loadQueued.set(false); }
    }
    // Callback runs on the same worker, after the initial disk scan.
    void afterLoad(Runnable callback) { worker.execute(callback); }
    String[] knownAccounts() { return accounts.keySet().toArray(new String[0]); }
    boolean contains(String uid, String chat, String id) {
        return RecallMessages.key(uid, chat, id) != null && account(uid).marks.contains(chat + ":" + id);
    }
    boolean containsMessage(String uid, String id) {
        return RecallMessages.positive(uid) > 0 && RecallMessages.positive(id) > 0 && account(uid).messages.contains(id);
    }
    void add(String uid, String chat, String id) {
        if (RecallMessages.key(uid, chat, id) == null) return;
        Account value = account(uid);
        String mark = chat + ":" + id;
        if (!value.marks.add(mark)) return;
        value.messages.add(id);
        dirty.add(uid);
        scheduleFlush(100);
    }
    private void scheduleFlush(long delay) {
        if (flushQueued.compareAndSet(false, true)) worker.schedule(new Runnable() {
            public void run() { flush(); }
        }, delay, TimeUnit.MILLISECONDS);
    }
    /** 同一后台线程合并写入，失败保留 dirty 等下一轮重试。 */
    private void flush() {
        for (String uid : new ArrayList<String>(dirty)) {
            if (!dirty.remove(uid)) continue;
            Account value = account(uid);
            load(uid, value);
            if (!value.loaded) { dirty.add(uid); continue; }
            try { disk.write(directory, uid, new HashSet<String>(value.marks)); }
            catch (IOException failure) {
                dirty.add(uid);
                ModuleLog.log("[fucklark] recall marker write failed: " + failure.getClass().getSimpleName());
            }
        }
        flushQueued.set(false);
        if (!dirty.isEmpty()) scheduleFlush(1000);
    }
    // Test-only barrier; production queries must never wait for disk.
    void awaitIdle() throws Exception { worker.submit(new Runnable() { public void run() { flush(); } }).get(5, TimeUnit.SECONDS); }
    void closeForTest() throws Exception { awaitIdle(); worker.shutdownNow(); }
}
