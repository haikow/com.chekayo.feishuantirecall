package com.chekayo.feishuantirecall;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.*;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** Initialization and UI-thread IO probes using legacy XposedBridge API 82 production installers/stores. */
public final class RecallAuditRegressionTest {
    static int checks, passportReads;
    static String uid = "1001";

    static void check(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
        checks++;
    }

    public interface Passport { String getUserId(); }

    public static final class ApiUtils {
        public static Passport getApi(Class<?> ignored) {
            passportReads++;
            return new Passport() { public String getUserId() { return uid; } };
        }
    }

    public static final class Info {
        final RecallBehaviorTest.Message message;
        Info(RecallBehaviorTest.Message message) { this.message = message; }
        public RecallBehaviorTest.Message getMessage() { return message; }
    }

    public static final class ThreadModel {
        public Info getMessageInfoById(String id) {
            RecallBehaviorTest.Message message = new RecallBehaviorTest.Message(); message.id = id;
            return new Info(message);
        }
        public String getChatId() { return "50"; }
        public void addReaction(String messageId, String reactionType) {}
        public void deleteReaction(String messageId, String reactionType) {}
    }

    public static final class CapturedClick {
        public RecallBehaviorTest.Message b;
        CapturedClick(RecallBehaviorTest.Message message) { b = message; }
    }

    static final class HostLoader extends ClassLoader {
        HostLoader() { super(RecallAuditRegressionTest.class.getClassLoader()); }
        @Override public Class<?> loadClass(String name) throws ClassNotFoundException {
            if (name.equals("com.ss.android.lark.passport_api.IPassportApi")) return Passport.class;
            if (name.equals("com.ss.android.lark.utils.ApiUtils")) return ApiUtils.class;
            if (name.equals("com.ss.android.lark.chat.entity.message.MessageInfo")) return Info.class;
            if (name.equals("com.ss.android.lark.chat.entity.message.Message")) return RecallBehaviorTest.Message.class;
            if (name.equals("com.ss.android.lark.replyinthread.pages.normal.ReplyInThreadViewModel")) return ThreadModel.class;
            return super.loadClass(name);
        }
    }

    static void startupAndThread() throws Throwable {
        XposedBridge.clearHooks();
        HostLoader loader = new HostLoader();
        RecallMessages.accountReader = null;
        RecallMessages.applicationReady = false;
        File markerDir = new File("build/recall-audit-marks-" + System.nanoTime());
        RecallMessages.markerStore = new RecallMarkStore(markerDir);
        String prevModulePath = AntiRecall.MODULE_PATH;
        boolean prevNativeStarted = AntiRecall.NATIVE_STARTED;
        try {
            // Simulate missing module path so startNative() fails safely inside installRecall.
            AntiRecall.MODULE_PATH = null;
            AntiRecall.NATIVE_STARTED = false;
            AntiRecall.installRecall(loader);
            check(RecallMessages.accountReader != null, "identity installed before native loading");
            check(passportReads == 0, "native failure does not initialize Passport prematurely");
            check(RecallMessages.account().isEmpty() && passportReads == 0, "Application readiness gate retained");

            RecallMessages.applicationReady = true;
            Config.antirecall = true;
            AntiRecall.TAMPER = 1;
            RecallBehaviorTest.Message other = new RecallBehaviorTest.Message(); other.id = "60";
            Object content = other.content;
            RecallMessages.prepare(other);
            Method contentGetter = RecallBehaviorTest.Message.class.getMethod("getContent");
            XC_MethodHook contentHook = XposedBridge.HOOKS.get(contentGetter);
            check(contentHook != null, "dynamic message content getter hook installed on first model");
            other.recall();
            // Exercise the real installed content-getter after-hook to verify Java fallback after native failure.
            RecallBehaviorTest.HookCall getterCall = RecallBehaviorTest.runHook(contentHook, other, new Object[0], other.getContent());
            check(getterCall.result == content && other.content == content && !other.isRecalled(),
                    "real installation keeps Java fallback alive after native failure");

            RecallBehaviorTest.Message self = new RecallBehaviorTest.Message();
            self.id = "61"; self.sender = uid; self.self = true;
            RecallMessages.prepare(self); self.recall(); RecallMessages.prepare(self);
            check(self.content == null && self.isRecalled(), "fallback still allows self recall");

            for (String name : new String[]{"addReaction", "deleteReaction"}) {
                Method method = ThreadModel.class.getMethod(name, String.class, String.class);
                XC_MethodHook hook = XposedBridge.HOOKS.get(method);
                check(hook != null, "thread entry installed despite native failure: " + name);
                ThreadModel receiver = new ThreadModel();
                RecallBehaviorTest.HookCall normal = RecallBehaviorTest.runHook(hook, receiver, new Object[]{"70", "60"}, null);
                check(normal.proceedCount == 1, "ordinary thread reaction proceeds once; reactionType is not messageId");
                RecallBehaviorTest.HookCall recalled = RecallBehaviorTest.runHook(hook, receiver, new Object[]{"60", "OK"}, null);
                check(recalled.proceedCount == 0, "thread reaction blocks before request and read receipt side effects");
                uid = "3003";
                RecallBehaviorTest.HookCall switched = RecallBehaviorTest.runHook(hook, receiver, new Object[]{"60", "OK"}, null);
                check(switched.proceedCount == 1, "thread reaction account isolation");
                uid = "1001";
            }

            RecallTranslationUi.CapturedTarget target = new RecallTranslationUi.CapturedTarget(CapturedClick.class.getField("b"));
            check(target.blocked(new CapturedClick(other), Arrays.<Object>asList(new Object())),
                    "thread translation stale click uses captured model, not recycled anchor");
            check(!target.blocked(new CapturedClick(self), Collections.emptyList()),
                    "ordinary thread translation click unaffected");
        } finally {
            AntiRecall.MODULE_PATH = prevModulePath;
            AntiRecall.NATIVE_STARTED = prevNativeStarted;
            RecallMessages.markerStore.closeForTest();
            RecallBehaviorTest.deleteTree(markerDir);
        }
    }

    static final class Disk implements RecallMarkStore.Disk {
        final CountDownLatch reading = new CountDownLatch(1), release = new CountDownLatch(1);
        final Thread caller = Thread.currentThread();
        volatile boolean wrongThread;
        volatile int writes;
        volatile boolean failWrite;
        Set<String> saved = new HashSet<String>();
        public String[] accounts(File dir) { wrongThread |= Thread.currentThread() == caller; return new String[]{"1001"}; }
        public Set<String> read(File dir, String account) throws IOException {
            wrongThread |= Thread.currentThread() == caller;
            reading.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("test read timeout"); }
            catch (InterruptedException e) { throw new IOException(e); }
            return new HashSet<String>(Arrays.asList("50:1"));
        }
        public void write(File dir, String account, Set<String> marks) throws IOException {
            wrongThread |= Thread.currentThread() == caller; writes++;
            if (failWrite) { failWrite = false; throw new IOException("synthetic disk failure"); }
            saved = marks;
        }
    }

    static void asynchronousStore() throws Exception {
        Disk disk = new Disk();
        RecallMarkStore store = new RecallMarkStore(new File("unused-test-disk"), disk);
        check(disk.reading.await(5, TimeUnit.SECONDS), "background load started");
        try {
            check(!store.contains("1001", "50", "1"), "query returns immediately while disk load is blocked");
            for (int i = 2; i <= 501; i++) {
                store.add("1001", "50", String.valueOf(i));
                if (!store.contains("1001", "50", String.valueOf(i)) || !store.containsMessage("1001", String.valueOf(i)))
                    throw new AssertionError("mark not immediately visible");
            }
            check(disk.writes == 0 && !disk.wrongThread, "500 hot-path marks do not read/write disk on caller thread");
        } finally { disk.release.countDown(); }
        store.awaitIdle();
        check(store.contains("1001", "50", "1"), "async load merged with new memory marks");
        check(disk.saved.size() == 501, "flush preserves preexisting and newly added marks");
        check(disk.writes == 1 && !disk.wrongThread, "batch coalesced into one background write");
        disk.failWrite = true; store.add("1001", "50", "502"); store.awaitIdle();
        check(store.contains("1001", "50", "502"), "disk error keeps memory state");
        store.awaitIdle();
        check(disk.saved.size() == 502, "failed write retried without dropping earlier marks");
        check(!store.contains("2002", "50", "502") && !store.contains("1001", "51", "502"),
                "async index account/chat isolation");
        store.closeForTest();
    }

    public static void main(String[] args) throws Throwable {
        startupAndThread();
        asynchronousStore();
        System.out.println("PASS: " + checks + " recall audit regression checks");
    }
}
