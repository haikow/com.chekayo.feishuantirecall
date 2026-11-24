package com.chekayo.feishuantirecall;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import de.robv.android.xposed.XC_MethodHook;

public final class RecallBehaviorTest {
    static int checks;
    static String uid = "1001";

    static void check(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }

    static final class HookCall {
        final XC_MethodHook.MethodHookParam param = new XC_MethodHook.MethodHookParam();
        int proceedCount;
        Object result;
    }

    static HookCall runHook(XC_MethodHook hook, Object receiver, Object[] args, Object originalResult) throws Throwable {
        HookCall call = new HookCall();
        call.param.thisObject = receiver;
        call.param.args = args == null ? new Object[0] : args;
        hook.invokeBefore(call.param);
        if (!call.param.returnEarly) {
            call.proceedCount = 1;
            call.param.setResult(originalResult);
            call.param.returnEarly = false;
            hook.invokeAfter(call.param);
        }
        call.result = call.param.getResult();
        return call;
    }

    static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) deleteTree(c);
        }
        f.delete();
    }

    private static RecallMarkStore newDiskStore(File dir) {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.US);
        if (!os.contains("win")) return new RecallMarkStore(dir);
        final RecallMarkStore.FileDisk fileDisk = new RecallMarkStore.FileDisk();
        return new RecallMarkStore(dir, new RecallMarkStore.Disk() {
            public String[] accounts(File d) { return fileDisk.accounts(d); }
            public Set<String> read(File d, String u) throws java.io.IOException { return fileDisk.read(d, u); }
            public void write(File d, String u, Set<String> marks) throws java.io.IOException {
                File target = new File(d, u + ".marks");
                if (target.exists()) target.delete();
                fileDisk.write(d, u, marks);
            }
        });
    }

    enum Status { NORMAL, RECALL }

    public static final class Message {
        String id = "1", chat = "50", sender = "2002";
        boolean self;
        Object content = new Object();
        Status status = Status.NORMAL;
        public String getId() { return id; }
        public String getChatId() { return chat; }
        public String getFromId() { return sender; }
        public boolean isFromMe() { return self; }
        public boolean isRecalled() { return status == Status.RECALL; }
        public Object getContent() { return content; }
        public Status getStatus() { return status; }
        public void setStatus(Status value) { status = value; }
        public void setMessageContent(Object value) { content = value; }
        void recall() { status = Status.RECALL; content = null; }
    }

    public static class Holder {}

    public static class Model {
        public String getId() { return "1"; }
        public String getChatId() { return "50"; }
    }

    public static class Block {
        private void helper(Holder h, Model m) {}
        protected void changedObfuscatedName(Holder h, Model m) {}
        public void unrelated(Holder h, Object m) {}
    }

    public static class AmbiguousBlock {
        public void a(Holder h, Model m) {}
        public void b(Holder h, Model m) {}
    }

    public interface AuditService {
        void auditImageDownload(String value);
        void auditMediaDownload(String value);
    }

    public static class GoodAccessor { public static AuditService a() { return null; } }
    public static class OldNameReused { public String a(int value) { return ""; } }
    public static class AmbiguousAccessor {
        public static AuditService a() { return null; }
        public static AuditService b() { return null; }
    }

    public static class OutlineShape {
        private android.graphics.Path renamed(float l, float t, float r, float b) { return null; }
        public Object unrelated(float l, float t, float r, float b) { return null; }
    }

    public static class AmbiguousOutline extends OutlineShape {
        private android.graphics.Path a(float l, float t, float r, float b) { return null; }
        private android.graphics.Path b(float l, float t, float r, float b) { return null; }
    }

    public interface ActionContext { Message renamedMessageGetter(); Object unrelated(); }
    enum ActionType { COPY, COPY_URL_LINK, SAVE_TO, MUTE_PLAY_VIDEO, REPLY_IN_THREAD, FORWARD, PIN, ADD_REACTION, UNKNOWN }

    public static class Action {
        final ActionType type;
        Action(ActionType type) { this.type = type; }
        public ActionType getType() { return type; }
    }

    public static class TranslationInfo {
        final Message message;
        TranslationInfo(String id) { message = new Message(); message.id = id; }
        public Message getMessage() { return message; }
    }

    public static class TranslationModel {
        public TranslationInfo getMessageInfoById(String id) { return new TranslationInfo(id); }
    }

    public static class TranslationHandler { public TranslationModel model = new TranslationModel(); }

    private static byte[] buildZip(String... nameAndBodyPairs) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ZipOutputStream zip = new ZipOutputStream(out);
        try {
            for (int i = 0; i < nameAndBodyPairs.length; i += 2) {
                zip.putNextEntry(new ZipEntry(nameAndBodyPairs[i]));
                zip.write(nameAndBodyPairs[i + 1].getBytes("UTF-8"));
                zip.closeEntry();
            }
        } finally {
            zip.close();
        }
        return out.toByteArray();
    }

    private static void testDataMigrationAllowlistAndZipSlip() throws Exception {
        Method allowed = DataMigration.class.getDeclaredMethod("allowed", String.class);
        allowed.setAccessible(true);
        String[] accepted = {
            "manifest.json",
            "fucklark_cfg.json",
            "block_log.json",
            "notif_archive.txt",
            "leave_log.txt",
            "kicked_12345.txt",
            "resign_tracker/profiles.json",
            "resign_tracker/resigned_all.json",
            "resign_tracker/resigned_latest.json",
            "accounts/1001/resign_tracker/profiles.json",
            "accounts/1001/resign_tracker/resigned_all.json",
            "accounts/1001/resign_tracker/resigned_latest.json",
            "accounts/1001/notif_archive.txt",
            "accounts/1001/leave_log.txt",
            "accounts/1001/kicked_50001.txt",
            "recall_marks/1001.marks"
        };
        for (String path : accepted) {
            check(Boolean.TRUE.equals(allowed.invoke(null, path)), "DataMigration allows " + path);
        }
        String[] rejected = {
            "../fucklark_cfg.json",
            "/etc/passwd",
            "accounts/../fucklark_cfg.json",
            "accounts/1001/../../evil.txt",
            "accounts/a/b/notif_archive.txt",
            "accounts/1001/evil.json",
            "recall_marks/../1001.marks",
            "recall_marks/evil.txt",
            "shared_prefs/secret.xml"
        };
        for (String path : rejected) {
            check(Boolean.FALSE.equals(allowed.invoke(null, path)), "DataMigration rejects " + path);
        }

        Method extractValidated = DataMigration.class.getDeclaredMethod(
                "extractValidated", java.io.InputStream.class, File.class);
        extractValidated.setAccessible(true);
        File root = new File("build/migration-test-" + System.nanoTime());
        File stage = new File(root, "stage");
        stage.mkdirs();
        try {
            byte[] validZip = buildZip(
                    "manifest.json", "{\"format\":2}",
                    "accounts/1001/notif_archive.txt", "hello\n",
                    "recall_marks/1001.marks", "50:1\n");
            @SuppressWarnings("unchecked")
            Set<String> extracted = (Set<String>) extractValidated.invoke(
                    null, new ByteArrayInputStream(validZip), stage);
            check(extracted.contains("accounts/1001/notif_archive.txt")
                    && extracted.contains("recall_marks/1001.marks"),
                    "DataMigration extracts format 2 account-scoped entries");

            File badStage = new File(root, "bad-stage");
            badStage.mkdirs();
            byte[] zipSlip = buildZip("../escape.txt", "pwned");
            boolean blocked = false;
            try {
                extractValidated.invoke(null, new ByteArrayInputStream(zipSlip), badStage);
            } catch (InvocationTargetException expected) {
                blocked = true;
            }
            check(blocked && !new File(root, "escape.txt").exists(),
                    "DataMigration rejects Zip-Slip traversal entry");
        } finally {
            deleteTree(root);
        }
    }

    private static void testDiagTruncateUtf8Safety() throws Exception {
        File dir = new File("build/diag-utf8-test-" + System.nanoTime());
        dir.mkdirs();
        File prevFile = Diag.FILE;
        try {
            Diag.setFilesDir(dir);
            String line = "01-01 00:00:00 撤回测试日志内容\n";
            // 15 ASCII + 8*3 UTF-8 + 1 newline = 40 bytes per line.
            // 101 lines = 4040 bytes -> half = 2020 (offset 20 inside line 50, inside a 3-byte Chinese char).
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 101; i++) sb.append(line);
            FileOutputStream os = new FileOutputStream(Diag.FILE, false);
            try {
                os.write(sb.toString().getBytes("UTF-8"));
            } finally {
                os.close();
            }
            Diag.truncate();
            String truncated = Diag.read();
            check(truncated.indexOf('\uFFFD') < 0, "Diag.truncate preserves valid UTF-8 without replacement chars");
            String[] lines = truncated.split("\n");
            check(lines.length > 2 && lines[0].contains("旧日志已截断"), "Diag.truncate retains header and tail lines");
            for (int i = 1; i < lines.length; i++) {
                check(lines[i].startsWith("01-01 00:00:00 "), "Diag.truncate aligns tail on newline boundary: " + lines[i]);
            }
        } finally {
            Diag.FILE = prevFile;
            deleteTree(dir);
        }
    }

    public static void main(String[] args) throws Throwable {
        File markerDirectory = new File("build/recall-mark-test-" + System.nanoTime());
        try {
            RecallMessages.markerStore = newDiskStore(markerDirectory);
            final int[] accountReads = {0};
            RecallMessages.accountReader = new RecallMessages.AccountReader() {
                public String read() {
                    accountReads[0]++;
                    return uid;
                }
            };
            RecallMessages.applicationReady = false;
            check(RecallMessages.account().isEmpty() && accountReads[0] == 0,
                    "startup does not initialize Passport before Application");
            RecallMessages.applicationReady = true;
            Config.antirecall = true;
            AntiRecall.TAMPER = 1;

            Message other = new Message();
            Object original = other.content;
            RecallMessages.prepare(other);
            other.recall();
            RecallMessages.prepare(other);
            check(other.content == original && other.status == Status.NORMAL, "restore exact other message");
            check(RecallMessages.marked("1001", "50", "1"), "other marked");

            Message self = new Message(); self.id = "2"; self.sender = "1001"; self.self = true;
            RecallMessages.prepare(self); self.recall(); RecallMessages.prepare(self);
            check(self.content == null && self.status == Status.RECALL, "self never restored");
            check(!RecallMessages.marked("1001", "50", "2"), "self not marked");

            Message contradictory = new Message(); contradictory.id = "3"; contradictory.sender = "1001";
            RecallMessages.prepare(contradictory); contradictory.recall(); RecallMessages.prepare(contradictory);
            check(contradictory.content == null, "sender match also excludes self");

            Message noOriginal = new Message(); noOriginal.id = "4"; noOriginal.recall();
            RecallMessages.prepare(noOriginal);
            check(noOriginal.content == null, "missing original does not use another message");

            Message anotherChat = new Message(); anotherChat.chat = "51"; anotherChat.recall();
            RecallMessages.prepare(anotherChat);
            check(anotherChat.content == null, "chat isolation");

            uid = "3003";
            Message switched = new Message(); switched.recall(); RecallMessages.prepare(switched);
            check(switched.content == null, "account switch isolates content");

            uid = "";
            Message unknown = new Message(); RecallMessages.prepare(unknown); unknown.recall(); RecallMessages.prepare(unknown);
            check(unknown.content == null, "unknown login passes through");

            uid = "1001";
            Config.antirecall = false;
            Message disabled = new Message(); disabled.recall(); RecallMessages.prepare(disabled);
            check(disabled.content == null, "disabled passes through even with cache");

            Config.antirecall = true;
            AntiRecall.TAMPER = 2;
            RecallMessages.prepare(disabled);
            check(disabled.content == original && disabled.status == Status.NORMAL,
                    "non-blocking signature check keeps recall restoration active when TAMPER=2");
            check(RecallActionGuard.blockedMessage(other) && RecallActionGuard.blockedId("1", null),
                    "non-blocking signature check keeps action guard active when TAMPER=2");
            AntiRecall.TAMPER = 1;

            Message ordinaryEmpty = new Message();
            ordinaryEmpty.content = null;
            RecallMessages.prepare(ordinaryEmpty);
            check(ordinaryEmpty.content == null, "ordinary empty message is not a recall");

            check(RecallMessages.positive("9223372036854775808") == 0, "overflow rejected");
            check(RecallMessages.key("1001", "", "1") == null, "incomplete identity rejected");
            check(RecallBubbleUi.findBind(Block.class, Holder.class).getName().equals("changedObfuscatedName"),
                    "bind discovered by shape, not short name");

            boolean rejected = false;
            try { RecallBubbleUi.findBind(AmbiguousBlock.class, Holder.class); }
            catch (IllegalStateException expected) { rejected = true; }
            check(rejected, "ambiguous bind rejected");

            check(FileDownloadUnlock.auditAccessor(GoodAccessor.class) != null, "verified audit interface accepted");
            check(FileDownloadUnlock.auditAccessor(OldNameReused.class) == null, "reused short class name rejected");
            check(FileDownloadUnlock.auditAccessor(AmbiguousAccessor.class) == null, "ambiguous audit accessor rejected");

            check(AntiRecall.isOwnRecallNotice("你撤回了一条消息"), "own notice left native");
            check(AntiRecall.isOwnRecallNotice("You recalled a message"), "English own notice");
            check(!AntiRecall.isOwnRecallNotice("测试用户撤回了一条消息"), "other notice");

            RecallMessages.clear();
            RecallMessages.markerStore.closeForTest();
            RecallMessages.markerStore = newDiskStore(markerDirectory);
            RecallMessages.markerStore.awaitIdle();
            check(RecallMessages.marked("1001", "50", "1"), "marker survives process/cache restart before native snapshot");
            check(!RecallMessages.markerStore.contains("3003", "50", "1"), "persisted markers isolated by account");
            check(!RecallMessages.markerStore.contains("1001", "51", "1"), "persisted markers isolated by chat");
            RecallMessages.markerStore.add("1001", "50", "8");
            RecallMessages.markerStore.closeForTest();
            RecallMessages.markerStore = newDiskStore(markerDirectory);
            RecallMessages.markerStore.awaitIdle();
            check(RecallMessages.markerStore.contains("1001", "50", "1")
                    && RecallMessages.markerStore.contains("1001", "50", "8"),
                    "atomic replacement preserves earlier marks");
            RecallMessages.markerStore.add("../outside", "50", "9");
            check(!Arrays.asList(RecallMessages.markerStore.knownAccounts()).contains("../outside"),
                    "invalid account paths rejected");

            check(RecallBubbleUi.findOutline(OutlineShape.class).getName().equals("renamed"),
                    "host outline resolved by exact path signature");
            rejected = false;
            try { RecallBubbleUi.findOutline(AmbiguousOutline.class); }
            catch (NoSuchMethodException expected) { rejected = true; }
            check(rejected, "ambiguous outline rejected instead of drawing a guessed shape");

            check(RecallActionGuard.findMessageGetter(ActionContext.class, Message.class).getName().equals("renamedMessageGetter"),
                    "menu context message resolved by type");
            Config.showRecallHint = false;
            check(RecallActionGuard.blockedMessage(other), "retained target stays read-only when border hidden");
            check(RecallActionGuard.blockedId("1", null), "reaction id found after restart without read-receipt mapping");
            check(!RecallActionGuard.blockedId("11", null), "message id lookup uses exact suffix boundary");
            uid = "3003";
            check(!RecallActionGuard.blockedMessage(other) && !RecallActionGuard.blockedId("1", null),
                    "action guard isolates switched accounts");
            uid = "1001";

            final Message target = new Message(); target.id = "10";
            RecallActionGuard.Target resolver = new RecallActionGuard.Target() {
                public boolean blocked(Object receiver, List<Object> args) {
                    return RecallActionGuard.blockedMessage(target);
                }
            };
            RecallActionGuard.Guard clickGuard = new RecallActionGuard.Guard(resolver, false);
            HookCall allowedCall = runHook(clickGuard, null, new Object[0], "native-result");
            check("native-result".equals(allowedCall.result) && allowedCall.proceedCount == 1,
                    "ordinary message action executes exactly once");
            RecallMessages.prepare(target); target.recall(); RecallMessages.prepare(target);
            HookCall stale = runHook(clickGuard, null, new Object[0], null);
            check(stale.result == null && stale.proceedCount == 0,
                    "already-open action blocked when target recalled before click");
            HookCall visibility = runHook(new RecallActionGuard.Guard(resolver, true), null, new Object[0], true);
            check(Boolean.FALSE.equals(visibility.result) && visibility.proceedCount == 0,
                    "recalled reply/reaction entries hidden without invoking host");

            Config.antiread = true;
            AntiRecall.KN_READY = false;
            AntiRecall.READ_WINDOWS.clear();
            AntiRecall.MSG2CH.put("1", "50");
            LinkedHashSet<String> pending = new LinkedHashSet<String>(); pending.add("1");
            AntiRecall.PENDING_READ.put("50", pending);
            HookCall request = runHook(new AntiRecall.ReactionHook(), null, new Object[]{"1", "OK"}, null);
            check(!AntiRecall.READ_WINDOWS.containsKey("50") && AntiRecall.PENDING_READ.get("50") == pending,
                    "recalled reaction constructor cannot open read window or consume pending receipts");
            check(request.proceedCount == 1, "defensive read guard keeps request construction valid");
            AntiRecall.MSG2CH.put("20", "50");
            runHook(new AntiRecall.ReactionHook(), null, new Object[]{"20", "OK"}, null);
            check(AntiRecall.READ_WINDOWS.containsKey("50"), "ordinary reaction preserves read receipt behavior");

            for (ActionType type : new ActionType[]{ActionType.COPY, ActionType.COPY_URL_LINK, ActionType.SAVE_TO, ActionType.MUTE_PLAY_VIDEO}) {
                check(!RecallActionGuard.blockedAction(new Action(type), other),
                        "retained message permits content action " + type);
            }
            Message ordinary = new Message(); ordinary.id = "99";
            for (ActionType type : new ActionType[]{ActionType.REPLY_IN_THREAD, ActionType.FORWARD, ActionType.PIN, ActionType.ADD_REACTION, ActionType.UNKNOWN}) {
                final Action action = new Action(type);
                check(RecallActionGuard.blockedAction(action, other), "retained message denies interaction " + type);
                check(!RecallActionGuard.blockedAction(action, ordinary), "ordinary message preserves action " + type);
                HookCall staleMenu = runHook(new RecallActionGuard.Guard(new RecallActionGuard.Target() {
                    public boolean blocked(Object receiver, List<Object> args) {
                        return RecallActionGuard.blockedAction(receiver, other);
                    }
                }, false), action, new Object[0], null);
                check(staleMenu.proceedCount == 0, "stale menu cannot execute " + type);
            }

            check(RecallActionGuard.blockedMessage(new Model()),
                    "swipe model identity detects persisted recall without status field");
            uid = "3003";
            check(!RecallActionGuard.blockedAction(new Action(ActionType.FORWARD), other)
                    && !RecallActionGuard.blockedMessage(new Model()),
                    "menu and swipe policies isolated by active account");
            uid = "1001";

            TranslationHandler dialog = new TranslationHandler();
            RecallTranslationUi.handlerAccounts.put(dialog, uid);
            RecallTranslationUi.DialogTarget dialogTarget = new RecallTranslationUi.DialogTarget(
                    TranslationHandler.class.getField("model"),
                    TranslationModel.class.getMethod("getMessageInfoById", String.class),
                    TranslationInfo.class.getMethod("getMessage"));
            RecallActionGuard.Guard submission = new RecallActionGuard.Guard(dialogTarget, false);
            HookCall ordinaryDialog = runHook(submission, dialog, new Object[]{null, "en", "30"}, null);
            check(ordinaryDialog.proceedCount == 1, "ordinary translation dialog submits exactly once");
            RecallMessages.markerStore.add("1001", "50", "30");
            HookCall staleDialog = runHook(submission, dialog, new Object[]{new Model(), "en", "30"}, null);
            check(staleDialog.proceedCount == 0,
                    "open translation picker blocked by captured id even after anchor recycled");
            uid = "3003";
            check(dialogTarget.blocked(dialog, Arrays.<Object>asList(null, "en", "30")),
                    "old picker keeps its owning account across a login switch");
            TranslationHandler newDialog = new TranslationHandler();
            RecallTranslationUi.handlerAccounts.put(newDialog, uid);
            check(!dialogTarget.blocked(newDialog, Arrays.<Object>asList(null, "en", "30")),
                    "new account picker does not inherit old account recall marker");
            uid = "1001";

            testDataMigrationAllowlistAndZipSlip();
            testDiagTruncateUtf8Safety();

            System.out.println("PASS: " + checks + " Java recall checks");
        } finally {
            if (RecallMessages.markerStore != null) {
                RecallMessages.markerStore.closeForTest();
            }
            deleteTree(markerDirectory);
        }
    }
}
