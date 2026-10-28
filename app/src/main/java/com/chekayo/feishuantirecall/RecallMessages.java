package com.chekayo.feishuantirecall;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 防撤回的 Java 层身份索引与原文兜底。
 * 消息的唯一键始终是「账号:会话:消息」；仅他人且身份明确的原文才进入有上限的缓存。
 * native 保存数据库原文和已提交撤回标记，Java 缓存只补救宿主映射器返回的空壳，
 * 不能以通知存档里看似相近的内容代替真实消息。
 */
final class RecallMessages {
    interface AccountReader { String read() throws Throwable; }
    static AccountReader accountReader;
    static volatile boolean applicationReady;
    static RecallMarkStore markerStore;
    private static volatile String hookStatus = "未安装";
    static String status() { return hookStatus; }
    /** 延迟建立标记目录：注入早期尚未确定宿主的 files 路径。 */
    private static synchronized RecallMarkStore markers() {
        if (markerStore == null) markerStore = new RecallMarkStore(new java.io.File(AntiRecall.larkFilesDir(), "recall_marks"));
        return markerStore;
    }
    private static void remember(String uid, String chat, String id) {
        try { markers().add(uid, chat, id); } catch (Throwable ignored) {}
    }
    static void restoreAccounts() {
        // 先在后台加载历史账号索引，再把账号与 SDK 的实际数据库目录绑定给 native。
        // 不能在 UI/宿主启动线程上同步扫描文件。
        try {
            final RecallMarkStore store = markers();
            store.afterLoad(new Runnable() {
                public void run() {
                    for (String uid : store.knownAccounts()) bindStorage(uid);
                }
            });
        } catch (Throwable ignored) {}
    }
    private static final java.util.Set<String> boundAccounts = java.util.Collections.newSetFromMap(
            new java.util.concurrent.ConcurrentHashMap<String, Boolean>());
    private static final java.util.Set<String> queuedAccounts = java.util.Collections.newSetFromMap(
            new java.util.concurrent.ConcurrentHashMap<String, Boolean>());
    private static volatile long lastRefresh;
    private static final int MAX_CONTENTS = 256;
    private static final Map<Class<?>, List<Method>> STRING_GETTERS =
            new java.util.concurrent.ConcurrentHashMap<Class<?>, List<Method>>();
    private static final Map<Class<?>, Method> SENDER_GETTERS =
            new java.util.concurrent.ConcurrentHashMap<Class<?>, Method>();
    private static final Map<Class<?>, Method> MESSAGE_ID_GETTERS =
            new java.util.concurrent.ConcurrentHashMap<Class<?>, Method>();
    private static final Map<Class<?>, Method> CHAT_ID_GETTERS =
            new java.util.concurrent.ConcurrentHashMap<Class<?>, Method>();
    private static final java.util.Set<Class<?>> CONTENT_HOOKS =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<Class<?>, Boolean>());
    private static final ThreadLocal<Boolean> CONTENT_CAPTURE = new ThreadLocal<Boolean>();
    // accessOrder=true：只保留近期展示过的内容；标记另行持久化，不受缓存淘汰影响。
    private static final Map<String, Entry> contents = new LinkedHashMap<String, Entry>(32, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, RecallMessages.Entry> e) { return size() > MAX_CONTENTS; }
    };
    private static final class Entry {
        final Object content;
        boolean recalled;
        Entry(Object content) { this.content = content; }
    }

    /** 宿主 Application 就绪后才查询账号；原文只在可见气泡绑定时采集。 */
    static void install(ClassLoader cl) throws Throwable {
        final Class<?> passport = cl.loadClass("com.ss.android.lark.passport_api.IPassportApi");
        final Method getApi = cl.loadClass("com.ss.android.lark.utils.ApiUtils").getMethod("getApi", Class.class);
        final Method getUid = passport.getMethod("getUserId");
        accountReader = new AccountReader() {
            @Override public String read() throws Throwable {
                // 每次重新获取账号服务；飞书可在不重启进程的情况下切换账号，
                // 长期保存旧服务实例或旧 UID 会使撤回标记串到另一个账号。
                Object api = getApi.invoke(null, passport);
                return api == null ? "" : (String) getUid.invoke(api);
            }
        };
        // 本地标记目录初始化失败不应中断 native 及气泡层的独立防撤回逻辑。
        try { markers(); } catch (Throwable ignored) {}
        hookStatus = "消息身份读取器已安装";
        Diag.once("[fucklark] 防撤回锚点命中: " + hookStatus);
    }

    /** 校验宿主提供的十进制 ID，并拒绝非数字、零和 long 溢出。 */
    static long positive(String s) {
        if (s == null || s.isEmpty()) return 0;
        long n = 0;
        for (int i = 0; i < s.length(); ++i) {
            int d = s.charAt(i) - '0';
            if (d < 0 || d > 9 || n > (Long.MAX_VALUE - d) / 10) return 0;
            n = n * 10 + d;
        }
        return n;
    }
    static String account() {
        // attachBaseContext 时宿主 Application 未初始化完毕，调用 Passport 可能触发
        // 宿主静态管理器崩溃；必须等 callApplicationOnCreate 之后再读取账号。
        if (!applicationReady) return "";
        try {
            String uid = accountReader == null ? "" : accountReader.read();
            if (positive(uid) == 0) return "";
            final String activeUid = uid;
            if (!boundAccounts.contains(uid) && queuedAccounts.add(uid)) {
                // 标记加载与 native 目录绑定交给单线程 worker；queuedAccounts 防止并发重复排队。
                try { markers().afterLoad(new Runnable() {
                    public void run() {
                        try { bindStorage(activeUid); } finally { queuedAccounts.remove(activeUid); }
                    }
                }); } catch (Throwable ignored) { queuedAccounts.remove(uid); }
            }
            return uid;
        } catch (Throwable ignored) { return ""; }
    }
    static void refreshAccount() {
        long now = System.currentTimeMillis();
        if (now - lastRefresh < 2000) return;
        lastRefresh = now;
        account();
    }
    /** 只向 native 提交实际存在的 SDK 账号目录；未知目录不能猜测归属。 */
    private static void bindStorage(String uid) {
        if (boundAccounts.contains(uid)) return;
        try {
            byte[] digest = java.security.MessageDigest.getInstance("MD5").digest(uid.getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format(java.util.Locale.US, "%02x", b & 255));
            java.io.File sdk = new java.io.File(AntiRecall.larkFilesDir().getParentFile(), "databases/sdk_storage");
            String storage = hex.toString();
            if (!new java.io.File(sdk, storage).isDirectory()) {
                storage = uid;
                if (!new java.io.File(sdk, storage).isDirectory()) return;
            }
            AntiRecall.nativeBindRecallAccount(uid, storage);
            boundAccounts.add(uid);
            Diag.once("[fucklark] recall account: verified SDK storage mapping");
        } catch (Throwable ignored) {}
    }
    static String value(Object object, String getter) throws Throwable {
        Object value = XposedHelpers.callMethod(object, getter);
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * 消息身份访问层：优先使用宿主语义方法；名称被混淆时，只接受唯一的 String getter。
     * 任何歧义都返回 fallback，不猜字段，避免把正文/标题等数字字符串当成消息身份。
     */
    static String messageText(Object message, String semantic, String fallback) {
        try { return value(message, semantic); } catch (Throwable ignored) {}
        if (!"getId".equals(semantic) && !"getChatId".equals(semantic)) return fallback == null ? "" : fallback;
        Class<?> type = message.getClass();
        Map<Class<?>, Method> cache = "getId".equals(semantic) ? MESSAGE_ID_GETTERS : CHAT_ID_GETTERS;
        Method getter = cache.get(type);
        if (getter == null) {
            String[] aliases = "getId".equals(semantic)
                    ? new String[]{"getMessageId"} : new String[]{"getConversationId", "getChatID"};
            for (String alias : aliases) try {
                Method candidate = type.getMethod(alias);
                if (candidate.getParameterTypes().length == 0 && candidate.getReturnType() == String.class) {
                    getter = candidate;
                    break;
                }
            } catch (Throwable ignored) {}
            if (getter == null) {
                Method unique = null;
                boolean ambiguous = false;
                for (Method candidate : type.getMethods()) {
                    if (Modifier.isStatic(candidate.getModifiers()) || candidate.getParameterTypes().length != 0
                            || candidate.getReturnType() != String.class || candidate.isBridge()
                            || candidate.isSynthetic() || !candidate.getName().startsWith("get")
                            || candidate.equals(SENDER_GETTERS.get(type))) continue;
                    try {
                        Object raw = candidate.invoke(message);
                        if (!(raw instanceof String) || positive((String) raw) == 0) continue;
                        if (unique != null && !unique.equals(candidate)) { ambiguous = true; break; }
                        unique = candidate;
                    } catch (Throwable ignored) {}
                }
                if (!ambiguous) getter = unique;
            }
            if (getter != null) {
                cache.put(type, getter);
                Diag.once("[fucklark] message identity getter matched: "
                        + type.getName() + "#" + getter.getName() + " semantic=" + semantic);
            } else {
                Diag.once("[fucklark] message identity getter unavailable: "
                        + type.getName() + " semantic=" + semantic);
            }
        }
        if (getter == null) return fallback == null ? "" : fallback;
        if ("getId".equals(semantic)) MESSAGE_ID_GETTERS.put(type, getter);
        else CHAT_ID_GETTERS.put(type, getter);
        try {
            Object raw = getter.invoke(message);
            return raw == null ? "" : String.valueOf(raw);
        } catch (Throwable ignored) { return fallback == null ? "" : fallback; }
    }

    /** 判断消息归属；sender getter 失败时再尝试宿主布尔谓词，仍失败则按“非自己”保守处理。 */
    static boolean messageFromMe(Object message, String uid, String chat, String id) {
        String sender;
        try { sender = senderId(message, uid, chat, id); }
        catch (Throwable ignored) { sender = ""; }
        if (positive(sender) > 0) return uid.equals(sender);
        try { return Boolean.TRUE.equals(XposedHelpers.callMethod(message, "isFromMe")); }
        catch (Throwable ignored) { return false; }
    }

    /** 判断撤回状态；状态枚举必须同时包含 NORMAL 和 RECALL/DELETED 语义才接受。 */
    static boolean messageRecalled(Object message) {
        try { return Boolean.TRUE.equals(XposedHelpers.callMethod(message, "isRecalled")); }
        catch (Throwable ignored) {}
        try {
            Object status = messageStatus(message);
            if (status instanceof Enum) {
                String name = ((Enum<?>) status).name().toUpperCase(java.util.Locale.US);
                return name.contains("RECALL") || name.contains("DELETED");
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /** 读取消息内容；语义 getter 缺失时只接受唯一的 content 命名 getter。 */
    static Object messageContent(Object message) {
        try { return XposedHelpers.callMethod(message, "getContent"); }
        catch (Throwable ignored) {}
        Method discovered = findContentGetter(message.getClass());
        if (discovered != null) try { return discovered.invoke(message); }
        catch (Throwable ignored) {}
        for (Method method : message.getClass().getMethods()) {
            if (method.getParameterTypes().length != 0 || method.getReturnType().isPrimitive()
                    || !method.getName().startsWith("get")) continue;
            try {
                Object value = method.invoke(message);
                if (value != null && method.getName().toLowerCase(java.util.Locale.US).contains("content")) return value;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /** 解析状态 getter；枚举必须具备 NORMAL 与撤回语义，避免误选其他枚举字段。 */
    static Object messageStatus(Object message) throws Throwable {
        try { return XposedHelpers.callMethod(message, "getStatus"); }
        catch (Throwable ignored) {}
        Method found = null;
        for (Method method : message.getClass().getMethods()) {
            if (method.getParameterTypes().length == 0 && method.getReturnType().isEnum()) {
                Object value;
                try { value = method.invoke(message); } catch (Throwable ignored) { continue; }
                Object[] constants = method.getReturnType().getEnumConstants();
                boolean normal = false, recalled = false;
                if (constants != null) for (Object constant : constants) {
                    String name = ((Enum<?>) constant).name().toUpperCase(java.util.Locale.US);
                    normal |= "NORMAL".equals(name);
                    recalled |= name.contains("RECALL") || name.contains("DELETED");
                }
                if (!(value instanceof Enum) || !normal || !recalled) continue;
                if (found != null) throw new NoSuchMethodException("ambiguous message status getter");
                found = method;
            }
        }
        if (found == null) throw new NoSuchMethodException("message status getter");
        Diag.once("[fucklark] message status getter matched: "
                + message.getClass().getName() + "#" + found.getName());
        return found.invoke(message);
    }

    static Object normalStatus(Class<?> statusType) throws NoSuchFieldException {
        if (!statusType.isEnum()) throw new NoSuchFieldException("message status is not enum");
        Object[] constants = statusType.getEnumConstants();
        if (constants != null) for (Object constant : constants) {
            if ("NORMAL".equalsIgnoreCase(((Enum<?>) constant).name())) return constant;
        }
        throw new NoSuchFieldException("normal status enum constant");
    }

    static void restoreMessage(Object message, Object content) throws Throwable {
        try { XposedHelpers.callMethod(message, "setMessageContent", content); }
        catch (Throwable ignored) {
            Method found = null;
            for (Method method : message.getClass().getMethods()) {
                Class<?>[] params = method.getParameterTypes();
                if (params.length != 1 || method.getReturnType() != void.class || content == null
                        || !params[0].isAssignableFrom(content.getClass())
                        || !method.getName().startsWith("set")) continue;
                if (found != null) throw new NoSuchMethodException("ambiguous message content setter");
                found = method;
            }
            if (found == null) throw new NoSuchMethodException("message content setter");
            found.invoke(message, content);
        }
    }

    static void restoreStatus(Object message, Object normal) throws Throwable {
        try { XposedHelpers.callMethod(message, "setStatus", normal); }
        catch (Throwable ignored) {
            Method found = null;
            for (Method method : message.getClass().getMethods()) {
                Class<?>[] params = method.getParameterTypes();
                if (params.length != 1 || method.getReturnType() != void.class
                        || normal == null || !params[0].isAssignableFrom(normal.getClass())
                        || !method.getName().startsWith("set")) continue;
                if (found != null) throw new NoSuchMethodException("ambiguous message status setter");
                found = method;
            }
            if (found == null) throw new NoSuchMethodException("message status setter");
            found.invoke(message, normal);
        }
    }
    /** 任一字段未知就不生成键；不能用消息 ID 单独识别某个会话。 */
    static String key(String uid, String chat, String id) {
        return positive(uid) > 0 && positive(chat) > 0 && positive(id) > 0 ? uid + ":" + chat + ":" + id : null;
    }
    /** 优先查内存/持久化索引，最后查询 native 已提交快照，不从 UI 线程碰数据库。 */
    static boolean marked(String uid, String chat, String id) {
        String key = key(uid, chat, id);
        if (key == null) return false;
        synchronized (contents) {
            Entry entry = contents.get(key);
            if (entry != null && entry.recalled) return true;
        }
        // 磁盘文件由后台 worker 异步载入；contains 本身只查询内存索引。
        try { if (markers().contains(uid, chat, id)) return true; } catch (Throwable ignored) {}
        try {
            if (!AntiRecall.nativeIsRecallMarked(uid, positive(chat), positive(id))) return false;
            remember(uid, chat, id);
            return true;
        }
        catch (Throwable ignored) { return false; }
    }
    static void clear() { synchronized (contents) { contents.clear(); } }
    /** 部分宿主动作只有消息 ID：只供操作守卫兜底，气泡仍要求完整会话 ID。 */
    static boolean markedMessage(String uid, String id) {
        if (positive(uid) == 0 || positive(id) == 0) return false;
        synchronized (contents) {
            for (Map.Entry<String, Entry> e : contents.entrySet()) {
                if (e.getValue().recalled && e.getKey().startsWith(uid + ":")
                        && e.getKey().endsWith(":" + id)) return true;
            }
        }
        try { return markers().containsMessage(uid, id); }
        catch (Throwable ignored) { return false; }
    }

    /** 只恢复身份明确、非自己的撤回消息；身份不一致时放行宿主原行为。 */
    static void prepare(Object message) {
        prepare(account(), message);
    }

    /** 调用方已取得 UID 时复用它，避免气泡 bind 对同一消息重复查询 Passport。 */
    static void prepare(String uid, Object message) {
        installContentHook(message);
        prepare(uid, message, null, false);
    }

    /** Getter Hook passes its already-read value so this path never calls the hooked getter recursively. */
    static Object prepareContent(Object message, Object currentContent) {
        if (Boolean.TRUE.equals(CONTENT_CAPTURE.get())) return currentContent;
        if (message == null || !Config.antirecall || AntiRecall.TAMPER == 2) return currentContent;
        // getContent is called repeatedly while rows render. Bound messages are
        // captured separately, so ordinary messages need only this cheap status check.
        if (!messageRecalled(message)) return currentContent;
        return prepare(account(), message, currentContent, true);
    }

    static void enableDynamicContentRestore() {
        Diag.once("[fucklark] dynamic message-content restore enabled; hook deferred until model instance");
    }

    private static void installContentHook(final Object message) {
        if (message == null || CONTENT_HOOKS.contains(message.getClass())) return;
        Method getter = findContentGetter(message.getClass());
        if (getter == null) {
            Diag.once("[fucklark] message content getter unavailable: " + message.getClass().getName());
            return;
        }
        try {
            XposedBridge.hookMethod(getter, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Object content = param.getResult();
                    Object restored = prepareContent(param.thisObject, content);
                    if (restored != content) param.setResult(restored);
                }
            });
            CONTENT_HOOKS.add(message.getClass());
            Diag.once("[fucklark] message content getter hooked: "
                    + getter.getDeclaringClass().getName() + "#" + getter.getName());
        } catch (Throwable t) {
            Diag.once("[fucklark] message content getter hook failed: " + t);
        }
    }

    private static Method findContentGetter(Class<?> messageClass) {
        for (Class<?> type = messageClass; type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (Modifier.isStatic(method.getModifiers()) || method.getParameterTypes().length != 0
                        || method.getReturnType().isPrimitive() || method.isBridge() || method.isSynthetic()) continue;
                if ("getContent".equals(method.getName())) {
                    method.setAccessible(true);
                    return method;
                }
                if ("getContentVO".equals(method.getName())) {
                    method.setAccessible(true);
                    return method;
                }
            }
        }
        Class<?> contentContract = loadContentContract(messageClass);
        if (contentContract != null) {
            Method found = null;
            for (Class<?> type = messageClass; type != null; type = type.getSuperclass()) {
                for (Method method : type.getDeclaredMethods()) {
                    if (Modifier.isStatic(method.getModifiers()) || method.getParameterTypes().length != 0
                            || method.getReturnType().isPrimitive() || method.isBridge() || method.isSynthetic()
                            || !contentContract.isAssignableFrom(method.getReturnType())) continue;
                    if (found != null && !found.equals(method)) return null;
                    found = method;
                }
            }
            if (found != null) {
                found.setAccessible(true);
                return found;
            }
        }
        // Resolve content through the model's declared message contract. Generic VO
        // implementations may erase T to a shared ContentVO/base interface and rename
        // the accessor; matching its return type is safer than scanning arbitrary getters.
        if (contentContract != null) {
            List<Method> contracts = new ArrayList<Method>();
            collectContentContracts(messageClass, contentContract, contracts,
                    java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Class<?>, Boolean>()));
            Method found = null;
            for (Method contract : contracts) {
                Method impl = findImplementation(messageClass, contract);
                if (impl == null) continue;
                if (found != null && !found.equals(impl)) return null;
                found = impl;
            }
            if (found != null) {
                found.setAccessible(true);
                return found;
            }
        }
        // Generic message models can implement a common content contract indirectly.
        for (Class<?> type = messageClass; type != null; type = type.getSuperclass()) {
            for (java.lang.reflect.TypeVariable<?> variable : type.getTypeParameters()) {
                for (java.lang.reflect.Type bound : variable.getBounds()) {
                    Class<?> contentType = rawClass(bound);
                    if (contentType == null) continue;
                    Method found = null;
                    for (Method method : messageClass.getMethods()) {
                        if (Modifier.isStatic(method.getModifiers()) || method.getParameterTypes().length != 0
                                || method.getReturnType() != contentType || method.isBridge() || method.isSynthetic()) continue;
                        if (found != null) { found = null; break; }
                        found = method;
                    }
                    if (found != null) {
                        found.setAccessible(true);
                        return found;
                    }
                }
            }
        }
        Method found = null;
        for (Class<?> type = messageClass; type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (Modifier.isStatic(method.getModifiers()) || method.getParameterTypes().length != 0
                        || method.getReturnType().isPrimitive() || method.isBridge() || method.isSynthetic()
                        || !method.getName().startsWith("get")
                        || !method.getReturnType().getName().toLowerCase(java.util.Locale.US).contains("content")) continue;
                if (found != null) return null;
                found = method;
            }
        }
        if (found != null) found.setAccessible(true);
        return found;
    }

    private static Class<?> loadContentContract(Class<?> model) {
        for (String name : new String[]{
                "com.ss.android.lark.chat.export.vo.ContentVO",
                "com.ss.android.lark.chat.export.entity.message.Content"}) {
            try { return model.getClassLoader().loadClass(name); } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Method findImplementation(Class<?> model, Method contract) {
        for (Class<?> type = model; type != null; type = type.getSuperclass()) {
            for (Method candidate : type.getDeclaredMethods()) {
                if (Modifier.isStatic(candidate.getModifiers()) || candidate.getParameterTypes().length != 0
                        || candidate.isBridge() || candidate.isSynthetic()
                        || !contract.getReturnType().isAssignableFrom(candidate.getReturnType())) continue;
                try {
                    if (candidate.getName().equals(contract.getName())) return candidate;
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    private static void collectContentContracts(Class<?> type, Class<?> contentType, List<Method> out,
                                                java.util.Set<Class<?>> visited) {
        if (type == null || !visited.add(type)) return;
        for (Method method : type.getDeclaredMethods()) {
            if (!Modifier.isStatic(method.getModifiers()) && method.getParameterTypes().length == 0
                    && contentType.isAssignableFrom(method.getReturnType())) out.add(method);
        }
        for (Class<?> parent : type.getInterfaces()) collectContentContracts(parent, contentType, out, visited);
        collectContentContracts(type.getSuperclass(), contentType, out, visited);
    }

    private static Class<?> rawClass(java.lang.reflect.Type type) {
        if (type instanceof Class) return (Class<?>) type;
        if (type instanceof java.lang.reflect.ParameterizedType) {
            java.lang.reflect.Type raw = ((java.lang.reflect.ParameterizedType) type).getRawType();
            return raw instanceof Class ? (Class<?>) raw : null;
        }
        return null;
    }

    private static Object prepare(String uid, Object message, Object currentContent, boolean contentProvided) {
        if (message == null || !Config.antirecall
                || AntiRecall.TAMPER == 2) return currentContent;
        try {
            String chat = messageText(message, "getChatId", ""), id = messageText(message, "getId", "");
            String key = key(uid, chat, id);
            if (key == null) return currentContent;
            String sender = senderId(message, uid, chat, id);
            // isFromMe=false 和发送者 UID!=当前账号必须同时成立；任一字段不可信，
            // 就不保存/回填原文，让宿主与 native 按原有策略处理。
            if (messageFromMe(message, uid, chat, id)) {
                synchronized (contents) { contents.remove(key); }
                return currentContent;
            }
            if (positive(sender) == 0 || uid.equals(sender)) {
                synchronized (contents) { contents.remove(key); }
                return currentContent;
            }
            boolean recalled = messageRecalled(message);
            if (!recalled) {
                Object content;
                if (contentProvided) content = currentContent;
                else {
                    Boolean previous = CONTENT_CAPTURE.get();
                    CONTENT_CAPTURE.set(Boolean.TRUE);
                    try { content = messageContent(message); }
                    finally {
                        if (previous == null) CONTENT_CAPTURE.remove();
                        else CONTENT_CAPTURE.set(previous);
                    }
                }
                if (content != null) synchronized (contents) {
                    Entry old = contents.get(key);
                    Entry next = new Entry(content);
                    next.recalled = old != null && old.recalled;
                    contents.put(key, next);
                }
                return currentContent;
            }
            Entry entry;
            synchronized (contents) { entry = contents.get(key); }
            if (entry == null) return currentContent; // 没见过该消息原文时，绝不从别的消息/通知猜测内容。
            Object status = messageStatus(message);
            if (!(status instanceof Enum)) return currentContent;
            Object normal = normalStatus(status.getClass());
            restoreMessage(message, entry.content);
            restoreStatus(message, normal);
            synchronized (contents) { entry.recalled = true; }
            remember(uid, chat, id);
            return entry.content;
        } catch (Throwable t) {
            Diag.once("[fucklark] 防撤回消息模型锚点失败: " + t);
            return currentContent;
        }
    }

    private static String senderId(Object message, String uid, String chat, String id) throws Throwable {
        Class<?> messageClass = message.getClass();
        Method resolved = SENDER_GETTERS.get(messageClass);
        if (resolved != null) {
            Object value = resolved.invoke(message);
            return value instanceof String ? (String) value : "";
        }
        List<Method> getters = STRING_GETTERS.get(message.getClass());
        if (getters == null) {
            List<Method> found = new ArrayList<Method>();
            for (Class<?> type = message.getClass(); type != null; type = type.getSuperclass()) {
                for (Method method : type.getDeclaredMethods()) {
                    if (Modifier.isStatic(method.getModifiers()) || method.isBridge() || method.isSynthetic()
                            || method.getParameterTypes().length != 0 || method.getReturnType() != String.class
                            || !method.getName().startsWith("get")
                            || "getId".equals(method.getName()) || "getChatId".equals(method.getName())) continue;
                    method.setAccessible(true);
                    found.add(method);
                }
            }
            java.util.Collections.sort(found, new java.util.Comparator<Method>() {
                @Override public int compare(Method left, Method right) {
                    return Integer.compare(senderGetterRank(left), senderGetterRank(right));
                }
            });
            getters = found;
            STRING_GETTERS.put(message.getClass(), getters);
        }
        String candidate = null;
        Method candidateGetter = null;
        Method accountGetter = null;
        boolean ambiguousCandidate = false;
        boolean ambiguousAccount = false;
        for (Method getter : getters) {
            try {
                Object raw = getter.invoke(message);
                if (!(raw instanceof String)) continue;
                String value = (String) raw;
                if (uid.equals(value) && positive(value) > 0) {
                    if (accountGetter != null && !accountGetter.equals(getter)) ambiguousAccount = true;
                    else accountGetter = getter;
                    continue;
                }
                if (positive(value) == 0 || value.equals(chat) || value.equals(id)) continue;
                if (candidate != null && !candidate.equals(value)) ambiguousCandidate = true;
                candidate = value;
                candidateGetter = getter;
            } catch (Throwable ignored) {}
        }
        if (ambiguousAccount) return "";
        if (accountGetter != null) candidateGetter = accountGetter;
        else if (ambiguousCandidate) return "";
        if (candidateGetter == null) return "";
        SENDER_GETTERS.put(messageClass, candidateGetter);
        Object value = candidateGetter.invoke(message);
        return value instanceof String ? (String) value : "";
    }

    private static int senderGetterRank(Method method) {
        String name = method.getName();
        if ("getFromId".equals(name)) return 0;
        if ("getSenderId".equals(name)) return 1;
        if ("getFromUserId".equals(name)) return 2;
        return 3;
    }
}
