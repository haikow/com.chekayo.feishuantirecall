package com.chekayo.feishuantirecall;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 阶段 4 出口验证：宿主 JVM 直跑迁移后 hook 回调的行为等价测试（不进 APK、不依赖真机）。
 * 覆盖（对应 05 文档统一转换原则与第四组验收重点）：
 * 1. ReadReqHook（构造器 hook，before 改参）：
 *    浏览路径清空 message_ids/fold_ids 并暂存；回复窗口路径合并回填；开关关时原参透传；
 *    短参数构造安全放行。
 * 2. SendReqHook（构造器 hook，纯副作用）：按会话 READ_WINDOWS 开窗。
 * 9~21. v2.4/v2.5 回复即已读·签名发现 + Kn 截获重放（feat/read-as-unread）：Kn 候选按形状
 *    全量枚举（过滤干扰项），多义全候选挂截获 + 首次落账时栈定夺重放方法（学习不吃掉浏览）；
 *    统一会话状态 KnState（审计 P2-1 原子认领）；重放按样本关联定位 ids 访问器（区分双 List，
 *    审计 P2-3 锁内原子发布）→ 模板列表原地替换为累加全集 → 后台重放一次并清缓存；
 *    失败批次并入恢复（审计 P2-2）、降级保留未补齐（审计 P2-4）、调度失败归还（审计 P2-6）、
 *    线程槽 finally 恢复（审计 P2-5）、回复窗口按会话隔离（审计 P1）。
 * 3. InvokeHook（ANTIREAD_DROP=false 现状）：任何命令都放行原调用，不短路。
 * 4. MapperHook（before 反射改对象）：文本入缓存；空文本时回填缓存内容 + 恢复 NORMAL 状态。
 * 5. RestrictedModeUnlock 共享门禁回调：开关开 -> 短路（boolean=false / void=null，原方法不执行）；
 *    开关关 -> proceed。
 * 6. 注册面：全部经 HookRuntime，幂等键去重（同 logicalId 二次安装 0 新框架安装）。
 */
public class HookMigrationBehaviorTest {

    static int failures = 0;

    // ── 伪造 Chain：proceed 记录取参，返回受控结果 ──────────────────────
    static class FakeChain implements XposedInterface.Chain {
        final Executable exe;
        final Object thisObj;
        final Object[] args;
        final Object result;
        int proceedCount = 0;
        Object[] lastProceedArgs;

        FakeChain(Executable exe, Object thisObj, Object[] args, Object result) {
            this.exe = exe; this.thisObj = thisObj; this.args = args; this.result = result;
        }
        public Executable getExecutable() { return exe; }
        public Object getThisObject() { return thisObj; }
        public List<Object> getArgs() {
            return Collections.unmodifiableList(new ArrayList<Object>(Arrays.asList(args)));
        }
        public Object getArg(int i) { return getArgs().get(i); }
        public Object proceed() { return proceed(args); }
        public Object proceed(Object[] a) {
            proceedCount++;
            lastProceedArgs = a;
            return result;
        }
        public Object proceedWith(Object r) { throw new UnsupportedOperationException(); }
        public Object proceedWith(Object t, Object[] a) { throw new UnsupportedOperationException(); }
    }

    // ── 伪造框架（复用阶段2模式） ──────────────────────────────────────
    static int installCount = 0;

    static class FakeBuilder implements XposedInterface.HookBuilder {
        public XposedInterface.HookBuilder setPriority(int p) { return this; }
        public XposedInterface.HookBuilder setExceptionMode(XposedInterface.ExceptionMode m) { return this; }
        public XposedInterface.HookBuilder setId(String id) { return this; }
        public XposedInterface.HookHandle intercept(XposedInterface.Hooker h) {
            installCount++;
            return new FakeHandle();
        }
    }

    static class FakeHandle implements XposedInterface.HookHandle {
        public Executable getExecutable() { return null; }
        public void unhook() { }
        public String getId() { return null; }
        public XposedInterface.HookHandle replaceHook(XposedInterface.Hooker h) { return this; }
    }

    static class FakeXposed implements XposedInterface {
        public int getApiVersion() { return API_102; }
        public String getFrameworkName() { return "fake"; }
        public String getFrameworkVersion() { return "0.0"; }
        public long getFrameworkVersionCode() { return 0L; }
        public long getFrameworkProperties() { return 0L; }
        public XposedInterface.HookBuilder hook(Executable target) { return new FakeBuilder(); }
        public XposedInterface.HookBuilder hookClassInitializer(Class<?> c) { throw new UnsupportedOperationException(); }
        public boolean deoptimize(Executable e) { return false; }
        public XposedInterface.Invoker<?, Method> getInvoker(Method m) { throw new UnsupportedOperationException(); }
        public <T> XposedInterface.CtorInvoker<T> getInvoker(Constructor<T> c) { throw new UnsupportedOperationException(); }
        public void log(int priority, String tag, String msg) { }
        public void log(int priority, String tag, String msg, Throwable t) { }
        public android.content.pm.ApplicationInfo getModuleApplicationInfo() { return null; }
        public android.content.SharedPreferences getRemotePreferences(String name) { throw new UnsupportedOperationException(); }
        public String[] listRemoteFiles() { throw new UnsupportedOperationException(); }
        public android.os.ParcelFileDescriptor openRemoteFile(String name) throws java.io.FileNotFoundException {
            throw new UnsupportedOperationException();
        }
    }

    static class FakeModule extends XposedModule { }

    // ── MapperHook 夹具：模仿 Wire pb 的 getter/setter 形状 ─────────────
    public static class FakeContent {
        String text;
        FakeContent(String t) { text = t; }
        public String getText() { return text; }
    }

    public static class FakeMessage {
        Object content;
        Object status;
        boolean contentSet;
        boolean statusSet;
        public Object getId() { return "111"; }
        public Object getContent() { return content; }
        public void setMessageContent(Object c) { contentSet = true; this.content = c; }
        public void setStatus(Object s) { statusSet = true; this.status = s; }
    }

    public static class FakeMsgItem {
        FakeMessage msg;
        FakeMsgItem(FakeMessage m) { msg = m; }
        public Object getMessage() { return msg; }
    }

    public static class FakeChannel {
        public String toString() { return "Channel{id=123456789, name=chat}"; }
    }

    // ── v2.4 Kn 签名发现 + 截获重放夹具 ──
    public static class FakeCallback { }
    public static class FakeOtherCb { }
    public static class FakeUnusedCb { }

    public static class FakeImpl {
        public void reportRead(FakeMPkt pkt, FakeCallback cb) { }          // 唯一形状命中
        public void decoyArity(FakeMPkt pkt) { }                            // 参数个数不符
        public void decoyCb(FakeMPkt pkt, FakeOtherCb cb) { }               // 回调类型不符
        public static void decoyStatic(FakeMPkt pkt, FakeCallback cb) { }   // static 排除
        public Object decoyReturn(FakeMPkt pkt, FakeCallback cb) { return null; } // 返回类型不符
    }

    public static class FakeAmbigImpl {
        public static volatile Runnable ON_KNA;   // 测试: 在 knA 栈帧内触发截获+读请求构造(供栈定夺)
        public void knA(FakeMPkt pkt, FakeCallback cb) { if (ON_KNA != null) ON_KNA.run(); }
        public void knB(FakeMPkt pkt, FakeCallback cb) { }
    }

    public static class FakeMPkt {
        boolean immutable;
        final java.util.List<String> ids = new ArrayList<String>();
        final java.util.List<String> folds = new ArrayList<String>(Arrays.asList("foldX"));
        public FakeMPkt(String... items) { ids.addAll(Arrays.asList(items)); }
        public FakeMPkt(boolean immutable, String... items) {
            this.immutable = immutable;
            ids.addAll(Arrays.asList(items));
        }
        public java.util.List<String> idsList() {   // 与 foldList 同为 List → 考验样本关联
            return immutable ? java.util.Collections.unmodifiableList(ids) : ids;
        }
        public java.util.List<String> foldList() { return folds; }
    }

    static void check(boolean cond, String what) {
        System.out.println((cond ? "[PASS] " : "[FAIL] ") + what);
        if (!cond) failures++;
    }

    static Object[] argsOf(FakeChain c) { return c.lastProceedArgs; }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Throwable {
        // 前置：绑定伪造框架（ModuleLog 经 FakeXposed 落地，alog 走 nativeLog 失败回退后吞掉）
        XposedInterface fake = new FakeXposed();
        FakeModule module = new FakeModule();
        module.attachFramework(fake, new Runnable() { public void run() { } });
        check(ModuleRuntime.bind(module, "p4.test.process"), "ModuleRuntime.bind(伪造模块)");
        ModuleRuntime.setTargetPackage("com.ss.android.lark",
                new URLClassLoader(new URL[0], null));

        Method readReqCtor = FakeChain.class.getDeclaredMethods()[0]; // 仅占位，executable 语义不参与断言

        // ── 1. ReadReqHook：浏览路径（无发送窗口）清空 message_ids/fold_ids 并暂存 ──
        AntiRecall.READ_WINDOWS.clear();   // 窗口关闭 = 纯浏览
        Config.antiread = true;
        AntiRecall.TAMPER = 0;
        java.util.List<String> ids = new ArrayList<String>(Arrays.asList("a", "b"));
        java.util.List<Long> folds = new ArrayList<Long>(Arrays.asList(7L));
        Object[] ctorArgs = new Object[]{ ids, new FakeChannel(), Long.valueOf(5), null, null, null, null, folds };
        AntiRecall.ReadReqHook readHook = new AntiRecall.ReadReqHook();
        FakeChain c1 = new FakeChain(readReqCtor, null, ctorArgs, null);
        readHook.intercept(c1);
        check(c1.proceedCount == 1, "浏览路径：构造器仍执行一次（proceed）");
        Object[] a1 = argsOf(c1);
        check(a1[0] instanceof List && ((List<Object>) a1[0]).isEmpty(),
                "浏览路径：message_ids 已清空（proceed 收到空表）");
        check(a1[7] instanceof List && ((List<Object>) a1[7]).isEmpty(),
                "浏览路径：fold_ids 已清空");
        LinkedHashSet<String> stash = AntiRecall.PENDING_READ.get("123456789");
        check(stash != null && stash.size() == 2 && stash.contains("a") && stash.contains("b"),
                "浏览路径：原 message_ids 按会话暂存（123456789）");
        check(ids.size() == 2, "浏览路径：调用方原 List 不被原地清空（modern 走副本改参）");

        // ── 2. ReadReqHook：回复窗口路径合并回填暂存 ids（窗口按会话记录） ──
        AntiRecall.SendReqHook sendHook = new AntiRecall.SendReqHook();
        FakeChain cs = new FakeChain(readReqCtor, null, new Object[]{ "123456789" }, null);
        sendHook.intercept(cs);
        Long win = AntiRecall.READ_WINDOWS.get("123456789");
        check(win != null && win.longValue() > System.currentTimeMillis(),
                "SendReqHook：拦截发送请求构造后该会话 READ_WINDOWS 已开窗");
        check(cs.proceedCount == 1, "SendReqHook：构造器仍执行一次");
        java.util.List<String> cur = new ArrayList<String>(Arrays.asList("c"));
        Object[] ctorArgs2 = new Object[]{ cur, new FakeChannel(), Long.valueOf(6), null, null, null, null, folds };
        FakeChain c2 = new FakeChain(readReqCtor, null, ctorArgs2, null);
        readHook.intercept(c2);
        Object[] a2 = argsOf(c2);
        check(a2[0] instanceof List && ((List<Object>) a2[0]).containsAll(Arrays.asList("a", "b", "c"))
                        && ((List<Object>) a2[0]).size() == 3,
                "回复窗口：暂存 ids(a,b) 与本次(c) 合并回填");
        check(((List<?>) a2[7]) == folds, "回复窗口：fold_ids 不动（仅浏览路径清空）");
        check(AntiRecall.PENDING_READ.get("123456789") == null,
                "回复窗口：暂存表该会话条目已消费（remove）");

        // ── 3. ReadReqHook：开关关 = 原参透传 ──
        Config.antiread = false;
        Object[] ctorArgs3 = new Object[]{ ids, new FakeChannel(), Long.valueOf(5), null, null, null, null, folds };
        FakeChain c3 = new FakeChain(readReqCtor, null, ctorArgs3, null);
        readHook.intercept(c3);
        Object[] a3 = argsOf(c3);
        check(a3[0] == ids && a3[7] == folds,
                "开关关：message_ids/fold_ids 原样透传给 proceed");

        // ── 4. ReadReqHook：短参数构造安全放行 ──
        Object[] shortArgs = new Object[]{ ids };
        FakeChain c4 = new FakeChain(readReqCtor, null, shortArgs, null);
        readHook.intercept(c4);
        check(c4.proceedCount == 1 && argsOf(c4).length == 1,
                "短参数(<8)构造：不抛异常、原参放行");

        // ── 5. InvokeHook：现状 ANTIREAD_DROP=false 只记录不短路 ──
        AntiRecall.InvokeHook invokeHook = new AntiRecall.InvokeHook();
        FakeChain c5 = new FakeChain(readReqCtor, null, new Object[]{ Integer.valueOf(1021) }, "RESULT");
        Object r5 = invokeHook.intercept(c5);
        check(c5.proceedCount == 1 && "RESULT".equals(r5),
                "InvokeHook(1021)：原调用执行且返回值透传（不短路）");
        FakeChain c5b = new FakeChain(readReqCtor, null, new Object[]{ Integer.valueOf(10000) }, "R");
        invokeHook.intercept(c5b);
        check(c5b.proceedCount == 1, "InvokeHook(10000 噪音)：仍放行原调用");

        // ── 6. MapperHook：文本入缓存；空文本回填缓存 + 恢复状态 ──
        AntiRecall.CACHE.clear();
        Object normal = new Object();   // Message$Status.NORMAL 替身
        AntiRecall.MapperHook mapper = new AntiRecall.MapperHook(normal);
        FakeMessage m1 = new FakeMessage();
        m1.content = new FakeContent("hello");
        FakeChain c6 = new FakeChain(readReqCtor, null, new Object[]{ new FakeMsgItem(m1) }, null);
        mapper.intercept(c6);
        check(AntiRecall.CACHE.get("111") == m1.content && c6.proceedCount == 1,
                "MapperHook：有文本 -> 入缓存且原方法执行");
        FakeMessage m2 = new FakeMessage();
        m2.content = new FakeContent(null);
        AntiRecall.CACHE.put("111", m1.content);   // 确保缓存命中
        FakeChain c6b = new FakeChain(readReqCtor, null, new Object[]{ new FakeMsgItem(m2) }, null);
        mapper.intercept(c6b);
        check(m2.contentSet && m2.content == m1.content && m2.statusSet && m2.status == normal,
                "MapperHook：空文本 -> 回填缓存内容 + setStatus(NORMAL)");
        check(c6b.proceedCount == 1, "MapperHook：原方法仍执行");

        // ── 7. RestrictedModeUnlock 共享门禁回调 ──
        Config.restrictunlock = true;
        FakeChain c7 = new FakeChain(readReqCtor, null, new Object[]{}, "BOOL");
        Object r7 = RestrictedModeUnlock.FALSE_GATE.intercept(c7);
        check(Boolean.FALSE.equals(r7) && c7.proceedCount == 0,
                "FALSE_GATE 开关开：返回 false 且不执行原方法（setResult(false) 等价）");
        FakeChain c7b = new FakeChain(readReqCtor, null, new Object[]{}, null);
        Object r7b = RestrictedModeUnlock.VOID_GATE_RESTRICT.intercept(c7b);
        check(r7b == null && c7b.proceedCount == 0,
                "VOID_GATE_RESTRICT 开关开：返回 null 且不执行原方法（setResult(null) 等价）");
        Config.restrictunlock = false;
        FakeChain c7c = new FakeChain(readReqCtor, null, new Object[]{}, "GO");
        Object r7c = RestrictedModeUnlock.FALSE_GATE.intercept(c7c);
        check("GO".equals(r7c) && c7c.proceedCount == 1,
                "FALSE_GATE 开关关：proceed 放行且返回值透传");
        Config.noauditall = true;
        FakeChain c7d = new FakeChain(readReqCtor, null, new Object[]{}, null);
        Object r7d = RestrictedModeUnlock.VOID_GATE_NOAUDIT.intercept(c7d);
        check(r7d == null && c7d.proceedCount == 0, "VOID_GATE_NOAUDIT 开关开：审计上报短路");
        Config.noauditall = false;

        // ── 8. 注册面：HookRuntime 幂等（同 logicalId 二次安装 0 新框架安装） ──
        XposedInterface.Hooker noop = new XposedInterface.Hooker() {
            public Object intercept(XposedInterface.Chain chain) throws Throwable { return chain.proceed(); }
        };
        int before = installCount;
        HookRuntime.InstalledHook first =
                HookRuntime.hookMethod(FakeChannel.class, "toString", new Class<?>[0], "p4/e2e/toString", noop);
        check(first != null && installCount - before == 1, "HookRuntime.hookMethod 首次安装");
        HookRuntime.InstalledHook dup =
                HookRuntime.hookMethod(FakeChannel.class, "toString", new Class<?>[0], "p4/e2e/toString", noop);
        check(dup == first && installCount - before == 1, "同 logicalId 二次安装幂等（0 新框架安装）");

        // ── 9. v2.4 签名发现：候选全量枚举(干扰项过滤)；多义全挂截获 + 首次落账时栈定夺重放方法 ──
        java.util.List<java.lang.reflect.Method> cands = AntiRecall.discoverKn(FakeImpl.class, FakeCallback.class);
        check(cands.size() == 1 && "reportRead".equals(cands.get(0).getName()),
                "签名发现：唯一命中(过滤参数个数/回调类型/static/返回类型干扰项)");
        java.util.List<java.lang.reflect.Method> amb = AntiRecall.discoverKn(FakeAmbigImpl.class, FakeCallback.class);
        check(amb.size() == 2, "签名发现：多义全量列出(knA/knB), 不猜测");
        check(AntiRecall.discoverKn(FakeImpl.class, FakeUnusedCb.class).isEmpty(),
                "签名发现：回调类型无任何方法使用 → 零命中");
        // 多义态: 全候选挂 KnCaptureHook(截获随时可用), 重放方法由首次落账的调用栈定夺 —— 学习不吃掉浏览
        Config.antiread = true;
        AntiRecall.TAMPER = 0;
        AntiRecall.knCandidates = amb;
        AntiRecall.knLearnPending = true;
        AntiRecall.KN_READY = true;
        AntiRecall.KN_STATE.clear();
        AntiRecall.CURRENT_KN.remove();
        AntiRecall.knMethod = null;
        AntiRecall.READ_WINDOWS.clear();
        AntiRecall.KnCaptureHook knCapA = new AntiRecall.KnCaptureHook();
        final Object ambObj = new FakeAmbigImpl();
        final FakeCallback cbObj2 = new FakeCallback();
        final FakeMPkt pkt9 = new FakeMPkt("m1", "m2");
        final java.util.List<String> ids9 = pkt9.idsList();
        FakeAmbigImpl.ON_KNA = new Runnable() {
            public void run() {
                // 模拟 KnCaptureHook 已截获落槽(真实路径: hook 在 Kn 的 proceed 内落槽, 构造在其后同线程发生)
                AntiRecall.CURRENT_KN.set(new Object[]{ ambObj, pkt9, cbObj2 });
                try {
                    readHook.intercept(new FakeChain(readReqCtor, null,
                            new Object[]{ ids9, new FakeChannel(), Long.valueOf(9), null, null, null, null, folds }, null));
                } catch (Throwable t) { throw new RuntimeException(t); }
            }
        };
        new FakeAmbigImpl().knA(pkt9, cbObj2);   // 落账+栈定夺都发生在 knA 帧内
        FakeAmbigImpl.ON_KNA = null;
        AntiRecall.KnState st9 = AntiRecall.KN_STATE.get("123456789");
        check(st9 != null && st9.m == pkt9 && st9.impl == ambObj && st9.cb == cbObj2
                        && st9.idsSample == ids9 && st9.pendingIds.size() == 2
                        && st9.pendingIds.containsAll(Arrays.asList("m1", "m2"))
                        && AntiRecall.CURRENT_KN.get() == null,
                "多义：首次浏览即落账(模板+累加集, 学习不吃掉浏览)");
        check("knA".equals(AntiRecall.knMethod.getName()) && !AntiRecall.knLearnPending,
                "多义：重放方法由调用栈定夺为真实帧 knA");
        // 审计 P2-5: Kn 截获的线程槽在调用返回后恢复 —— 不越过调用生命周期污染后续构造
        knCapA.intercept(new FakeChain(readReqCtor, ambObj, new Object[]{ pkt9, cbObj2 }, null));
        check(AntiRecall.CURRENT_KN.get() == null,
                "P2-5：Kn 截获线程槽在调用返回后恢复(不越过调用生命周期)");

        // ── 10. 截获落账：多包拆分累加不丢 + 模板/样本刷新为最新包 ──
        Config.antiread = true;
        AntiRecall.TAMPER = 0;
        AntiRecall.KN_READY = true;
        AntiRecall.KN_STATE.clear();
        AntiRecall.knAccResolved = false;
        AntiRecall.knAccIds = null;
        AntiRecall.KnCaptureHook knCap = new AntiRecall.KnCaptureHook();
        Object implObj = new FakeImpl(), cbObj = new FakeCallback();
        AntiRecall.READ_WINDOWS.clear();   // 强制浏览分支(其他用例开的窗口可能仍在)
        FakeMPkt pkt1 = new FakeMPkt("m1", "m2");
        // 模拟 KnCaptureHook 已截获落槽(P2-5 语义: 槽生存期=被拦截调用, 测试直接落槽等价)
        AntiRecall.CURRENT_KN.set(new Object[]{ implObj, pkt1, cbObj });
        check(AntiRecall.CURRENT_KN.get() != null && AntiRecall.KN_STATE.isEmpty(),
                "Kn截获：入口只落线程槽(不调用任何混淆访问器)");
        java.util.List<String> ids1 = pkt1.idsList();   // 同一引用进构造参数(builder 直传)
        FakeChain c10a = new FakeChain(readReqCtor, null,
                new Object[]{ ids1, new FakeChannel(), Long.valueOf(9), null, null, null, null, folds }, null);
        readHook.intercept(c10a);
        check(AntiRecall.CURRENT_KN.get() == null,
                "Kn截获：读请求构造落账后线程槽已消费");
        AntiRecall.KnState st10 = AntiRecall.KN_STATE.get("123456789");
        check(st10 != null && st10.impl == implObj && st10.m == pkt1
                        && st10.cb == cbObj && st10.idsSample == ids1,
                "Kn截获：按会话落账 {impl,m,cb,ids样本}(样本=构造参数原列表引用)");
        check(st10.pendingIds.size() == 2 && st10.pendingIds.containsAll(Arrays.asList("m1", "m2")),
                "Kn截获：ids 按会话累加(第1包 2条)");
        // 多包拆分: 第2包(模板刷新+累加不丢)
        FakeMPkt pkt2 = new FakeMPkt("m3");
        AntiRecall.CURRENT_KN.set(new Object[]{ implObj, pkt2, cbObj });
        java.util.List<String> ids2 = pkt2.idsList();
        readHook.intercept(new FakeChain(readReqCtor, null,
                new Object[]{ ids2, new FakeChannel(), Long.valueOf(10), null, null, null, null, folds }, null));
        AntiRecall.KnState st10b = AntiRecall.KN_STATE.get("123456789");
        check(st10b != null && st10b.pendingIds.size() == 3
                        && st10b.pendingIds.containsAll(Arrays.asList("m1", "m2", "m3"))
                        && st10b.m == pkt2 && st10b.idsSample == ids2,
                "Kn截获：多包拆分累加不丢(3条) 且模板/样本刷新为最新包");

        // ── 11. 重放：原子认领 → 样本关联定位 ids 访问器 → 模板列表原地替换为累加全集 → 重放并清缓存 ──
        final int[] replayed = new int[1];
        final Object[] repArgs = new Object[3];
        final boolean[] fail = new boolean[1];
        AntiRecall.sReplayer = new AntiRecall.KnReplayer() {
            public void replay(Object impl, Object m, Object cb) {
                replayed[0]++; repArgs[0] = impl; repArgs[1] = m; repArgs[2] = cb;
                if (fail[0]) throw new RuntimeException("boom");
            }
        };
        AntiRecall.sScheduler = new AntiRecall.ReplayScheduler() {
            public void schedule(Runnable r) { r.run(); }   // 测试: 同步执行(生产=独立后台线程)
        };
        FakeChain c9 = new FakeChain(readReqCtor, null, new Object[]{ "123456789" }, null);
        sendHook.intercept(c9);
        check(replayed[0] == 1 && repArgs[0] == implObj && repArgs[1] == pkt2 && repArgs[2] == cbObj,
                "重放：回复瞬间触发一次且三元组传递(模板=最新包)");
        check(AntiRecall.knAccIds != null
                        && ((FakeMPkt) repArgs[1]).idsList().size() == 3
                        && ((FakeMPkt) repArgs[1]).idsList().containsAll(Arrays.asList("m1", "m2", "m3"))
                        && ((FakeMPkt) repArgs[1]).foldList().equals(Arrays.asList("foldX")),
                "重放：样本关联定位 ids 访问器(区分双 List) 且原地替换为累加全集、fold 不动");
        check(AntiRecall.KN_STATE.get("123456789") == null
                        && AntiRecall.PENDING_READ.get("123456789") == null,
                "重放：成功后该会话重放缓存与兜底暂存已清");
        check(c9.proceedCount == 1, "重放：回复构造器仍执行一次");
        check(!Boolean.TRUE.equals(AntiRecall.KN_REPLAY.get()), "重放：卫兵已复位");
        // 载体兜底幂等：重放后窗口内载体再到来 -> merged 只剩载体自身 ids，不重复
        java.util.List<String> carrier = new ArrayList<String>(Arrays.asList("c"));
        FakeChain c9b = new FakeChain(readReqCtor, null,
                new Object[]{ carrier, new FakeChannel(), Long.valueOf(6), null, null, null, null, folds }, null);
        readHook.intercept(c9b);
        Object[] a9b = argsOf(c9b);
        check(((List<?>) a9b[0]).equals(Arrays.asList("c")),
                "重放后载体兜底：缓存已清，仅放行载体自身 ids（幂等）");

        // ── 12. 重放失败：失败批次恢复回待重放集，可重试 ──
        fail[0] = true;
        AntiRecall.READ_WINDOWS.clear();   // §11 重开了窗口, 落账需要浏览分支
        FakeMPkt xPkt = new FakeMPkt("x");
        AntiRecall.CURRENT_KN.set(new Object[]{ implObj, xPkt, cbObj });
        readHook.intercept(new FakeChain(readReqCtor, null,
                new Object[]{ xPkt.idsList(), new FakeChannel(), Long.valueOf(11), null, null, null, null, folds }, null));
        FakeChain c10 = new FakeChain(readReqCtor, null, new Object[]{ "123456789" }, null);
        sendHook.intercept(c10);
        AntiRecall.KnState st12 = AntiRecall.KN_STATE.get("123456789");
        check(replayed[0] == 2 && st12 != null && st12.pendingIds.contains("x"),
                "重放失败：异常被吞且失败批次恢复回待重放集（走载体兜底/下次回复重试）");
        fail[0] = false;
        sendHook.intercept(c10);
        check(replayed[0] == 3 && AntiRecall.KN_STATE.get("123456789") == null,
                "重放重试：下次回复成功后清缓存");

        // ── 13. 会话隔离：回 B 不重放 A ──
        AntiRecall.READ_WINDOWS.clear();
        FakeMPkt aPkt = new FakeMPkt("aOnly");
        Object implA = new FakeImpl();
        AntiRecall.CURRENT_KN.set(new Object[]{ implA, aPkt, cbObj });
        readHook.intercept(new FakeChain(readReqCtor, null,
                new Object[]{ aPkt.idsList(), new FakeChannel(), Long.valueOf(12), null, null, null, null, folds }, null));
        FakeChain c11 = new FakeChain(readReqCtor, null, new Object[]{ "22222222" }, null);
        sendHook.intercept(c11);
        AntiRecall.KnState st13 = AntiRecall.KN_STATE.get("123456789");
        check(replayed[0] == 3 && st13 != null && st13.m == aPkt,
                "会话隔离：回复 B(22222222) 不触发 A(123456789) 的重放，A 缓存原样");

        // ── 14. 开关关 / 通道未就绪：完全不触发 ──
        Config.antiread = false;
        sendHook.intercept(c11);
        check(replayed[0] == 3 && AntiRecall.KN_STATE.get("123456789") != null,
                "开关关：不重放且缓存不动");
        AntiRecall.KN_READY = false;
        Config.antiread = true;
        sendHook.intercept(c11);
        check(replayed[0] == 3 && AntiRecall.KN_STATE.get("123456789") != null,
                "通道未就绪(KN_READY=false)：不重放（纯载体模式）");
        AntiRecall.KN_READY = true;

        // ── 15. KN_REPLAY 卫兵：重放期间浏览抑制、Kn 截获、落账全部直通 ──
        AntiRecall.READ_WINDOWS.clear();   // 强制浏览分支（若无卫兵会清空 ids）
        AntiRecall.PENDING_READ.remove("123456789");
        AntiRecall.KN_REPLAY.set(Boolean.TRUE);
        java.util.List<String> selfIds = new ArrayList<String>(Arrays.asList("self1"));
        FakeChain c13 = new FakeChain(readReqCtor, null,
                new Object[]{ selfIds, new FakeChannel(), Long.valueOf(1), null, null, null, null, folds }, null);
        readHook.intercept(c13);
        Object[] a13 = argsOf(c13);
        check(a13[0] == selfIds && ((List<?>) a13[0]).size() == 1
                        && AntiRecall.PENDING_READ.get("123456789") == null,
                "卫兵直通：重放期间读请求 hook 不做任何改参/暂存");
        FakeMPkt replayPkt = new FakeMPkt("rp1");
        knCap.intercept(new FakeChain(readReqCtor, implA, new Object[]{ replayPkt, cbObj }, null));
        check(AntiRecall.CURRENT_KN.get() == null,
                "卫兵直通：重放期间 Kn 截获不落线程槽");
        AntiRecall.KN_REPLAY.set(Boolean.FALSE);
        readHook.intercept(c13);
        Object[] a13b = argsOf(c13);
        check(((List<?>) a13b[0]).isEmpty()
                        && AntiRecall.PENDING_READ.get("123456789").contains("self1"),
                "卫兵复位后：同一请求恢复浏览抑制语义（清空+暂存）");
        AntiRecall.KN_READY = false;

        // ── 16. 审计 P1 回归：回复窗口按会话隔离 —— 回复 B 不放行 A 的浏览请求/不消费 A 的暂存 ──
        AntiRecall.READ_WINDOWS.clear();
        AntiRecall.PENDING_READ.clear();
        AntiRecall.KN_STATE.clear();
        Config.antiread = true;
        AntiRecall.KN_READY = true;
        // A 会话浏览(窗口关) → 暂存
        readHook.intercept(new FakeChain(readReqCtor, null,
                new Object[]{ new ArrayList<String>(Arrays.asList("a1")), new FakeChannel(), Long.valueOf(20), null, null, null, null, folds }, null));
        check(AntiRecall.PENDING_READ.get("123456789") != null
                        && AntiRecall.PENDING_READ.get("123456789").contains("a1"),
                "P1前置：A 浏览已暂存");
        // 回复 B → 只开 B 的窗口
        sendHook.intercept(new FakeChain(readReqCtor, null, new Object[]{ "22222222" }, null));
        check(AntiRecall.READ_WINDOWS.containsKey("22222222")
                        && !AntiRecall.READ_WINDOWS.containsKey("123456789"),
                "P1：回复 B 只开 B 的窗口");
        // B 的窗口期内浏览 A → 不放行(照常清空+暂存), A 的暂存(旧+新)不被消费
        FakeChain cA = new FakeChain(readReqCtor, null,
                new Object[]{ new ArrayList<String>(Arrays.asList("a2")), new FakeChannel(), Long.valueOf(21), null, null, null, null, folds }, null);
        readHook.intercept(cA);
        Object[] aA = argsOf(cA);
        check(((List<?>) aA[0]).isEmpty()
                        && AntiRecall.PENDING_READ.get("123456789").containsAll(Arrays.asList("a1", "a2")),
                "P1：B 的窗口不放行 A 的浏览请求, A 的暂存(旧+新)原样保留");
        // 回复 A → 开 A 的窗口 → A 的载体请求放行并消费全部暂存
        sendHook.intercept(new FakeChain(readReqCtor, null, new Object[]{ "123456789" }, null));
        FakeChain cA2 = new FakeChain(readReqCtor, null,
                new Object[]{ new ArrayList<String>(Arrays.asList("a3")), new FakeChannel(), Long.valueOf(22), null, null, null, null, folds }, null);
        readHook.intercept(cA2);
        Object[] aA2 = argsOf(cA2);
        check(((List<?>) aA2[0]).containsAll(Arrays.asList("a1", "a2", "a3"))
                        && AntiRecall.PENDING_READ.get("123456789") == null,
                "P1：回复 A 后窗口内 A 的载体请求放行并消费全部暂存");

        // ── 17. 审计 P2-6 回归：调度失败必须归还被认领的缓存(含其间的新批次) ──
        AntiRecall.READ_WINDOWS.clear();
        AntiRecall.KN_STATE.clear();
        AntiRecall.PENDING_READ.clear();
        AntiRecall.knAccResolved = false;
        AntiRecall.knAccIds = null;
        final java.util.List<Runnable> deferred = new ArrayList<Runnable>();
        AntiRecall.sScheduler = new AntiRecall.ReplayScheduler() {
            public void schedule(Runnable r) { deferred.add(r); }   // 延迟执行
        };
        AntiRecall.CURRENT_KN.set(new Object[]{ implObj, new FakeMPkt("s-old"), cbObj });
        readHook.intercept(new FakeChain(readReqCtor, null,
                new Object[]{ new FakeMPkt("s-old").idsList(), new FakeChannel(), Long.valueOf(30), null, null, null, null, folds }, null));
        sendHook.intercept(new FakeChain(readReqCtor, null, new Object[]{ "123456789" }, null));
        check(AntiRecall.KN_STATE.get("123456789") == null && deferred.size() == 1,
                "P2-6：回复原子认领, 任务进入调度队列");
        AntiRecall.sScheduler = new AntiRecall.ReplayScheduler() {
            public void schedule(Runnable r) { throw new RuntimeException("scheduler down"); }
        };
        AntiRecall.READ_WINDOWS.clear();   // 封闭: reply1 开的窗口不拦截本步浏览落账
        AntiRecall.CURRENT_KN.set(new Object[]{ implObj, new FakeMPkt("s-new"), cbObj });
        readHook.intercept(new FakeChain(readReqCtor, null,
                new Object[]{ new FakeMPkt("s-new").idsList(), new FakeChannel(), Long.valueOf(31), null, null, null, null, folds }, null));
        sendHook.intercept(new FakeChain(readReqCtor, null, new Object[]{ "123456789" }, null));
        AntiRecall.KnState st17 = AntiRecall.KN_STATE.get("123456789");
        check(st17 != null && st17.pendingIds.contains("s-new")
                        && AntiRecall.PENDING_READ.get("123456789") != null,
                "P2-6：调度失败后失败批次(s-new)已恢复(s-old 在延迟任务中在途, 不属失败恢复)");
        // 恢复正常调度: 跑延迟任务(s-old 批) + 再回复(s-new 批) → 全部重放
        AntiRecall.sScheduler = new AntiRecall.ReplayScheduler() {
            public void schedule(Runnable r) { r.run(); }
        };
        for (Runnable r : deferred) r.run();
        sendHook.intercept(new FakeChain(readReqCtor, null, new Object[]{ "123456789" }, null));
        check(replayed[0] == 5 && AntiRecall.KN_STATE.get("123456789") == null,
                "P2-6：恢复调度后延迟批次与新批次全部重放完成");

        // ── 18. 审计 P2-2 回归：失败批次并入更新截获的待重放集(不丢弃) ──
        AntiRecall.READ_WINDOWS.clear();
        AntiRecall.KN_STATE.clear();
        AntiRecall.PENDING_READ.clear();
        AntiRecall.sScheduler = new AntiRecall.ReplayScheduler() {
            public void schedule(Runnable r) { deferred.add(r); }   // 延迟执行, 便于控制失败时机
        };
        AntiRecall.CURRENT_KN.set(new Object[]{ implObj, new FakeMPkt("old-1", "old-2"), cbObj });
        readHook.intercept(new FakeChain(readReqCtor, null,
                new Object[]{ new FakeMPkt("old-1", "old-2").idsList(), new FakeChannel(), Long.valueOf(40), null, null, null, null, folds }, null));
        sendHook.intercept(new FakeChain(readReqCtor, null, new Object[]{ "123456789" }, null));   // 认领→延迟
        AntiRecall.READ_WINDOWS.clear();   // 封闭: 上一次回复开的窗口不拦截本步浏览落账
        AntiRecall.CURRENT_KN.set(new Object[]{ implObj, new FakeMPkt("new-1"), cbObj });
        readHook.intercept(new FakeChain(readReqCtor, null,
                new Object[]{ new FakeMPkt("new-1").idsList(), new FakeChannel(), Long.valueOf(41), null, null, null, null, folds }, null));   // 其间新截获
        fail[0] = true;
        deferred.remove(deferred.size() - 1).run();   // 旧批次任务失败 → 恢复必须并入新状态的待重放集
        fail[0] = false;
        AntiRecall.KnState st18 = AntiRecall.KN_STATE.get("123456789");
        check(st18 != null && st18.pendingIds.containsAll(Arrays.asList("new-1", "old-1", "old-2")),
                "P2-2：失败批次(old-1/old-2)并入更新截获(new-1)的待重放集, 无一丢失");
        AntiRecall.sScheduler = new AntiRecall.ReplayScheduler() {
            public void schedule(Runnable r) { r.run(); }   // 恢复同步执行后再验证最终回复
        };
        sendHook.intercept(new FakeChain(readReqCtor, null, new Object[]{ "123456789" }, null));
        check(replayed[0] == 7 && ((FakeMPkt) repArgs[1]).idsList().containsAll(Arrays.asList("new-1", "old-1", "old-2")),
                "P2-2：下次回复一次性重放新旧全部批次");

        // ── 19. 审计 P2-4 回归：降级重放(列表不可变)保留未补齐部分 ──
        AntiRecall.READ_WINDOWS.clear();
        AntiRecall.KN_STATE.clear();
        AntiRecall.PENDING_READ.clear();
        AntiRecall.sScheduler = new AntiRecall.ReplayScheduler() {
            public void schedule(Runnable r) { r.run(); }   // 同步执行
        };
        FakeMPkt dPkt = new FakeMPkt(true, "d-first");   // idsList() 不可变 → 模板替换必然失败
        AntiRecall.CURRENT_KN.set(new Object[]{ implObj, dPkt, cbObj });
        readHook.intercept(new FakeChain(readReqCtor, null,
                new Object[]{ dPkt.idsList(), new FakeChannel(), Long.valueOf(50), null, null, null, null, folds }, null));
        sendHook.intercept(new FakeChain(readReqCtor, null, new Object[]{ "123456789" }, null));   // 重放"成功"但为降级
        AntiRecall.KnState st19 = AntiRecall.KN_STATE.get("123456789");
        check(st19 != null && st19.pendingIds.contains("d-first")
                        && AntiRecall.PENDING_READ.get("123456789").contains("d-first"),
                "P2-4：降级重放未补齐的部分保留给兜底/下次重试(不随缓存清空而丢失)");
        // 下一轮重试(列表已不可变, 仍降级) → 未补齐部分继续保留
        sendHook.intercept(new FakeChain(readReqCtor, null, new Object[]{ "123456789" }, null));
        check(AntiRecall.KN_STATE.get("123456789") != null
                        && AntiRecall.KN_STATE.get("123456789").pendingIds.contains("d-first"),
                "P2-4：持续降级下未补齐部分持续保留(不消失)");

        // ── 20. 审计补充回归：PENDING_READ 追加/消费线性化 —— 确定性两种顺序 + 真线程压力, 零丢失 ──
        // (复审 P3 采纳: 不再用 sleep 碰运气, 两种线性化顺序各自确定性断言 mutex+零丢失;
        //  另补复审 P2 混合类型列表回归 —— 异类元素不得使整批绕过清空, 仅合并 String。)
        AntiRecall.PENDING_READ.clear();
        AntiRecall.KN_STATE.clear();
        AntiRecall.READ_WINDOWS.clear();
        AntiRecall.KN_READY = false;   // 纯载体兜底模式(审计反例场景)
        // 顺序1(追加→消费): 追加全部完成后载体原子 remove → consumed 恰为全部, 与剩余互斥
        readHook.intercept(new FakeChain(readReqCtor, null,
                new Object[]{ new ArrayList<String>(Arrays.asList("o1", "o2")), new FakeChannel(), Long.valueOf(70), null, null, null, null, folds }, null));
        readHook.intercept(new FakeChain(readReqCtor, null,
                new Object[]{ new ArrayList<String>(Arrays.asList("o3")), new FakeChannel(), Long.valueOf(71), null, null, null, null, folds }, null));
        LinkedHashSet<String> taken1 = AntiRecall.PENDING_READ.remove("123456789");
        check(taken1 != null && taken1.containsAll(Arrays.asList("o1", "o2", "o3"))
                        && AntiRecall.PENDING_READ.get("123456789") == null,
                "顺序1(追加→消费)：原子 remove 全量消费, 与剩余互斥");
        // 顺序2(消费→追加): 消费后追加 → consumed 只含已消费, remaining 只含新追加, 互斥
        readHook.intercept(new FakeChain(readReqCtor, null,
                new Object[]{ new ArrayList<String>(Arrays.asList("c1")), new FakeChannel(), Long.valueOf(72), null, null, null, null, folds }, null));
        LinkedHashSet<String> taken2 = AntiRecall.PENDING_READ.remove("123456789");
        readHook.intercept(new FakeChain(readReqCtor, null,
                new Object[]{ new ArrayList<String>(Arrays.asList("n1", "n2")), new FakeChannel(), Long.valueOf(73), null, null, null, null, folds }, null));
        LinkedHashSet<String> rem2 = AntiRecall.PENDING_READ.get("123456789");
        check(taken2 != null && taken2.contains("c1") && rem2 != null
                        && rem2.containsAll(Arrays.asList("n1", "n2"))
                        && java.util.Collections.disjoint(taken2, rem2),
                "顺序2(消费→追加)：消费与新追加互斥, 零丢失");
        // 复审 P2 复现回归: 混合类型列表(String+异类) → 清空生效 + 仅合并 String, 无 CCE 中止
        AntiRecall.PENDING_READ.clear();
        java.util.List<Object> mixed = new ArrayList<Object>();
        mixed.add("visible-message");
        mixed.add(Integer.valueOf(7));
        FakeChain cMix = new FakeChain(readReqCtor, null,
                new Object[]{ mixed, new FakeChannel(), Long.valueOf(74), null, null, null, null, folds }, null);
        readHook.intercept(cMix);
        Object[] aMix = argsOf(cMix);
        check(((List<?>) aMix[0]).isEmpty()
                        && AntiRecall.PENDING_READ.get("123456789") != null
                        && AntiRecall.PENDING_READ.get("123456789").contains("visible-message")
                        && AntiRecall.PENDING_READ.get("123456789").size() == 1,
                "复审P2回归：混合类型列表 → 清空生效+仅合并 String(逐项过滤, 无 CCE 中止)");
        // 真线程压力: 双浏览线程各 100 条并发追加 + 中途原子消费 —— 仅断言零丢失(已消费∪剩余⊇全部追加)。
        // 同一 key 上 compute 与 remove 原子且串行化: compute 先完成则 remove 取走更新后的整组,
        // remove 先完成则 compute 从新追加项建组 —— 交错本身不会把已消费的整组重新插回 map;
        // 可能的重复上报来自独立的重复上报(如载体与重放先后各报一次), 服务端幂等无害。
        AntiRecall.PENDING_READ.clear();
        final int perThread = 100;
        final java.util.Set<String> appended = java.util.Collections.synchronizedSet(new LinkedHashSet<String>());
        final AntiRecall.ReadReqHook hookRef = readHook;
        Thread browseA = new Thread(new Runnable() { public void run() {
            for (int i = 0; i < perThread; i++) {
                final String id = "tA-" + i;
                appended.add(id);
                try {
                    hookRef.intercept(new FakeChain(readReqCtor, null,
                            new Object[]{ new ArrayList<String>(Arrays.asList(id)), new FakeChannel(),
                                    Long.valueOf(1000 + i), null, null, null, null, folds }, null));
                } catch (Throwable t) { throw new RuntimeException(t); }
            }
        }}, "browse-A");
        Thread browseB = new Thread(new Runnable() { public void run() {
            for (int i = 0; i < perThread; i++) {
                final String id = "tB-" + i;
                appended.add(id);
                try {
                    hookRef.intercept(new FakeChain(readReqCtor, null,
                            new Object[]{ new ArrayList<String>(Arrays.asList(id)), new FakeChannel(),
                                    Long.valueOf(2000 + i), null, null, null, null, folds }, null));
                } catch (Throwable t) { throw new RuntimeException(t); }
            }
        }}, "browse-B");
        final java.util.Set<String> consumed = java.util.Collections.synchronizedSet(new LinkedHashSet<String>());
        Thread carrierTh = new Thread(new Runnable() { public void run() {
            try { Thread.sleep(5); } catch (InterruptedException e) { return; }
            LinkedHashSet<String> taken = AntiRecall.PENDING_READ.remove("123456789");
            if (taken != null) consumed.addAll(taken);   // 与载体兜底路径等价的原子消费
        }}, "carrier");
        browseA.start(); browseB.start(); carrierTh.start();
        browseA.join(); browseB.join(); carrierTh.join();
        LinkedHashSet<String> union = new LinkedHashSet<String>(appended);
        union.addAll(consumed);
        if (AntiRecall.PENDING_READ.get("123456789") != null) union.addAll(AntiRecall.PENDING_READ.get("123456789"));
        check(union.size() == 200 && union.containsAll(appended),
                "真线程压力：200 条并发追加在载体原子消费下零丢失(已消费∪剩余⊇全部追加)");

        // ── 21. 表情回复触发：贴表情按 message_id 反查会话, 触发与回复相同的认领重放(用户需求回归) ──
        AntiRecall.READ_WINDOWS.clear();
        AntiRecall.KN_STATE.clear();
        AntiRecall.PENDING_READ.clear();
        AntiRecall.MSG2CH.clear();
        Config.antiread = true;
        AntiRecall.TAMPER = 0;
        AntiRecall.KN_READY = true;
        // 浏览落账: ids(r1,r2) 暂存 + MSG2CH 建立 消息id→会话 映射
        FakeMPkt rPkt = new FakeMPkt("r1", "r2");
        AntiRecall.CURRENT_KN.set(new Object[]{ implObj, rPkt, cbObj });
        readHook.intercept(new FakeChain(readReqCtor, null,
                new Object[]{ rPkt.idsList(), new FakeChannel(), Long.valueOf(80), null, null, null, null, folds }, null));
        check("123456789".equals(AntiRecall.MSG2CH.get("r1")) && "123456789".equals(AntiRecall.MSG2CH.get("r2")),
                "表情回复前置：浏览落账已建立 消息id→会话 映射");
        // 贴表情(CreateReactionRequest(message_id="r1", type="OK")) → 触发该会话重放
        final int base21 = replayed[0];
        new AntiRecall.ReactionHook().intercept(new FakeChain(readReqCtor, null, new Object[]{ "r1", "OK" }, null));
        check(replayed[0] == base21 + 1 && repArgs[1] instanceof FakeMPkt
                        && ((FakeMPkt) repArgs[1]).idsList().containsAll(Arrays.asList("r1", "r2"))
                        && AntiRecall.KN_STATE.get("123456789") == null,
                "表情回复：贴表情触发该会话全部已浏览消息重放");
        // 未映射的 message_id → 不触发(该条由表情 RPC 自行标读, 其余维持未读语义)
        new AntiRecall.ReactionHook().intercept(new FakeChain(readReqCtor, null, new Object[]{ "zzz-unknown", "OK" }, null));
        check(replayed[0] == base21 + 1, "表情回复：未映射 message_id 不触发");

        // ── 22. 复审 P2 回归：表情触发继承载体兜底窗口 —— Kn 未就绪降级态与打字回复完全等价 ──
        AntiRecall.READ_WINDOWS.clear();
        AntiRecall.KN_STATE.clear();
        AntiRecall.PENDING_READ.clear();
        AntiRecall.MSG2CH.clear();
        Config.antiread = true;
        AntiRecall.TAMPER = 0;
        AntiRecall.KN_READY = false;   // Kn 通道未就绪(审计列举的降级场景)
        // 早前就绪期遗留的 消息id→会话 映射
        AntiRecall.MSG2CH.put("m-slot", "123456789");
        // A 会话浏览(窗口关) → 暂存 seed
        readHook.intercept(new FakeChain(readReqCtor, null,
                new Object[]{ new ArrayList<String>(Arrays.asList("w1")), new FakeChannel(), Long.valueOf(90), null, null, null, null, folds }, null));
        check(AntiRecall.PENDING_READ.get("123456789") != null
                        && AntiRecall.PENDING_READ.get("123456789").contains("w1"),
                "P2-窗口前置：A 浏览已暂存");
        // 贴表情(message_id="m-slot") → 即使 Kn 未就绪也必须按会话开窗(载体兜底等价)
        new AntiRecall.ReactionHook().intercept(new FakeChain(readReqCtor, null, new Object[]{ "m-slot", "OK" }, null));
        check(AntiRecall.READ_WINDOWS.containsKey("123456789"),
                "表情触发：降级态仍按会话打开载体兜底窗口");
        // 窗口内 A 的载体请求 → 放行并消费全部暂存
        FakeChain c22 = new FakeChain(readReqCtor, null,
                new Object[]{ new ArrayList<String>(Arrays.asList("w2")), new FakeChannel(), Long.valueOf(91), null, null, null, null, folds }, null);
        readHook.intercept(c22);
        Object[] a22 = argsOf(c22);
        check(((List<?>) a22[0]).containsAll(Arrays.asList("w1", "w2"))
                        && AntiRecall.PENDING_READ.get("123456789") == null,
                "表情触发：降级态窗口内载体请求放行全部暂存(与打字回复等价)");

        // ── 收尾：还原全局状态，避免影响同 JVM 其它用例 ──
        Config.antiread = false;
        AntiRecall.READ_WINDOWS.clear();
        AntiRecall.CACHE.clear();
        AntiRecall.PENDING_READ.clear();
        AntiRecall.KN_STATE.clear();
        AntiRecall.MSG2CH.clear();
        AntiRecall.knAccResolved = false;
        AntiRecall.knAccIds = null;
        AntiRecall.knMethod = null;
        AntiRecall.knLearnPending = false;
        AntiRecall.knCandidates = null;
        AntiRecall.sReplayer = new AntiRecall.DefaultKnReplayer();
        AntiRecall.sScheduler = new AntiRecall.DefaultReplayScheduler();

        System.out.println(failures == 0
                ? "== PASS：全部 hook 迁移行为断言通过 =="
                : "== FAIL：" + failures + " 条断言未过 ==");
        System.exit(failures == 0 ? 0 : 1);
    }
}
