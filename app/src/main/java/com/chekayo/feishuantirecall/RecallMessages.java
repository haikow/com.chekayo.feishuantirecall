package com.chekayo.feishuantirecall;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.TypeVariable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Collections;
import io.github.libxposed.api.XposedInterface;


/** 按账号/会话/消息识别撤回，并在前台限量缓存原文作为 Java 层兜底。 */
final class RecallMessages {
    interface AccountReader { String read() throws Throwable; }
    static AccountReader accountReader;
    static volatile boolean applicationReady;
    static RecallMarkStore markerStore;
    private static volatile String hookStatus = "未安装";
    static String status() { return hookStatus; }
    private static synchronized RecallMarkStore markers() {
        if (markerStore == null) markerStore = new RecallMarkStore(new java.io.File(AntiRecall.larkFilesDir(), "recall_marks"));
        return markerStore;
    }
    private static void remember(String uid, String chat, String id) {
        try { markers().add(uid, chat, id); } catch (Throwable ignored) {}
    }
    static void restoreAccounts() {
        try {
            final RecallMarkStore store = markers();
            store.afterLoad(new Runnable() {
                public void run() {
                    for (String uid : store.knownAccounts()) bindStorage(uid);
                }
            });
        } catch (Throwable ignored) {}
    }
    private static final java.util.Set<String> boundAccounts = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final java.util.Set<String> queuedAccounts = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static volatile long lastRefresh;
    private static final int MAX_CONTENTS = 256;
    private static final Map<Class<?>, Method> SENDER_GETTERS = new java.util.concurrent.ConcurrentHashMap<Class<?>, Method>();
    private static final Map<Class<?>, Method> ID_GETTERS = new java.util.concurrent.ConcurrentHashMap<Class<?>, Method>();
    private static final Map<Class<?>, Method> CHAT_GETTERS = new java.util.concurrent.ConcurrentHashMap<Class<?>, Method>();
    private static final Set<Class<?>> CONTENT_HOOKS = Collections.newSetFromMap(
            new java.util.concurrent.ConcurrentHashMap<Class<?>, Boolean>());
    private static final ThreadLocal<Boolean> CONTENT_CAPTURE = new ThreadLocal<Boolean>();
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
                // Resolve the current service each time; a saved service/currentUid can
                // belong to the previous login after an in-process account switch.
                Object api = getApi.invoke(null, passport);
                return api == null ? "" : (String) getUid.invoke(api);
            }
        };
        // 标记目录故障不能阻止气泡层或 native 层继续工作。
        try { markers(); } catch (Throwable ignored) {}
        hookStatus = "消息身份读取器已安装";
        Diag.once("[fucklark] 防撤回锚点命中: " + hookStatus);
    }

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
        // Never initialize Passport during attachBaseContext: its static activity
        // manager requires the host Application to have finished initialization.
        if (!applicationReady) return "";
        try {
            String uid = accountReader == null ? "" : accountReader.read();
            if (positive(uid) == 0) return "";
            final String activeUid = uid;
            if (!boundAccounts.contains(uid) && queuedAccounts.add(uid)) {
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
        Object value = Reflect.callMethod(object, getter);
        return value == null ? "" : String.valueOf(value);
    }

    /** Prefer semantic identity APIs; only cache a unique typed getter as fallback. */
    static String messageText(Object message, String semantic, String fallback) {
        try { return value(message, semantic); } catch (Throwable ignored) {}
        if (!"getId".equals(semantic) && !"getChatId".equals(semantic)) return fallback == null ? "" : fallback;
        Class<?> type = message.getClass();
        Map<Class<?>, Method> cache = "getId".equals(semantic) ? ID_GETTERS : CHAT_GETTERS;
        Method getter = cache.get(type);
        if (getter == null) {
            String[] aliases = "getId".equals(semantic) ? new String[]{"getMessageId"}
                    : new String[]{"getConversationId", "getChatID"};
            for (String alias : aliases) try {
                Method candidate = type.getMethod(alias);
                if (candidate.getParameterTypes().length == 0 && candidate.getReturnType() == String.class) { getter = candidate; break; }
            } catch (Throwable ignored) {}
            if (getter == null) {
                Method unique = null; boolean ambiguous = false;
                for (Method candidate : type.getMethods()) {
                    if (Modifier.isStatic(candidate.getModifiers()) || candidate.getParameterTypes().length != 0
                            || candidate.getReturnType() != String.class || candidate.isBridge() || candidate.isSynthetic()
                            || !candidate.getName().startsWith("get") || candidate.equals(SENDER_GETTERS.get(type))) continue;
                    try {
                        Object raw = candidate.invoke(message);
                        if (!(raw instanceof String) || positive((String) raw) == 0) continue;
                        if (unique != null && !unique.equals(candidate)) { ambiguous = true; break; }
                        unique = candidate;
                    } catch (Throwable ignored) {}
                }
                if (!ambiguous) getter = unique;
            }
            if (getter != null) cache.put(type, getter);
        }
        if (getter == null) return fallback == null ? "" : fallback;
        try { Object raw = getter.invoke(message); return raw == null ? "" : String.valueOf(raw); }
        catch (Throwable ignored) { return fallback == null ? "" : fallback; }
    }

    private static String senderId(Object message, String uid, String chat, String id) {
        Class<?> type = message.getClass();
        Method cached = SENDER_GETTERS.get(type);
        if (cached != null) try { return String.valueOf(cached.invoke(message)); } catch (Throwable ignored) {}
        List<Method> methods = new ArrayList<Method>();
        for (Method m : type.getMethods()) if (!Modifier.isStatic(m.getModifiers())
                && m.getParameterTypes().length == 0 && m.getReturnType() == String.class
                && !m.isBridge() && !m.isSynthetic() && m.getName().startsWith("get")) methods.add(m);
        Collections.sort(methods, new java.util.Comparator<Method>() {
            @Override public int compare(Method a, Method b) { return Integer.compare(senderRank(a), senderRank(b)); }
        });
        String match = null; Method matchMethod = null; boolean ambiguous = false;
        for (Method m : methods) try {
            Object raw = m.invoke(message);
            if (!(raw instanceof String)) continue;
            String s = (String) raw;
            if (positive(s) == 0 || s.equals(chat) || s.equals(id)) continue;
            if (match != null && !match.equals(s)) ambiguous = true;
            match = s; matchMethod = m;
            if (uid.equals(s)) break;
        } catch (Throwable ignored) {}
        if (ambiguous && !uid.equals(match)) return "";
        if (matchMethod == null) return "";
        SENDER_GETTERS.put(type, matchMethod);
        return match == null ? "" : match;
    }
    private static int senderRank(Method m) {
        if ("getFromId".equals(m.getName())) return 0;
        if ("getSenderId".equals(m.getName())) return 1;
        if ("getFromUserId".equals(m.getName())) return 2;
        return 3;
    }
    static boolean messageFromMe(Object message, String uid, String chat, String id) {
        String sender = senderId(message, uid, chat, id);
        if (positive(sender) > 0) return uid.equals(sender);
        try { return Boolean.TRUE.equals(Reflect.callMethod(message, "isFromMe")); } catch (Throwable ignored) { return false; }
    }
    static boolean messageRecalled(Object message) {
        try { return Boolean.TRUE.equals(Reflect.callMethod(message, "isRecalled")); } catch (Throwable ignored) {}
        try {
            Object status = messageStatus(message);
            if (status instanceof Enum) {
                String n = ((Enum<?>) status).name().toUpperCase(java.util.Locale.US);
                return n.contains("RECALL") || n.contains("DELETED");
            }
        } catch (Throwable ignored) {}
        return false;
    }
    static Object messageContent(Object message) {
        try { return Reflect.callMethod(message, "getContent"); } catch (Throwable ignored) {}
        Method getter = findContentGetter(message.getClass());
        if (getter == null) return null;
        try { return getter.invoke(message); } catch (Throwable ignored) { return null; }
    }
    static Object messageStatus(Object message) throws Throwable {
        try { return Reflect.callMethod(message, "getStatus"); } catch (Throwable ignored) {}
        Method found = null;
        for (Method m : message.getClass().getMethods()) {
            if (m.getParameterTypes().length != 0 || !m.getReturnType().isEnum()) continue;
            Object value;
            try { value = m.invoke(message); } catch (Throwable ignored) { continue; }
            boolean normal = false, recalled = false;
            Object[] values = m.getReturnType().getEnumConstants();
            if (values != null) for (Object v : values) {
                String n = ((Enum<?>) v).name().toUpperCase(java.util.Locale.US);
                normal |= "NORMAL".equals(n); recalled |= n.contains("RECALL") || n.contains("DELETED");
            }
            if (!(value instanceof Enum) || !normal || !recalled) continue;
            if (found != null) throw new NoSuchMethodException("ambiguous message status getter");
            found = m;
        }
        if (found == null) throw new NoSuchMethodException("message status getter");
        return found.invoke(message);
    }
    static Object normalStatus(Class<?> type) throws NoSuchFieldException {
        if (!type.isEnum()) throw new NoSuchFieldException("status enum");
        Object[] values = type.getEnumConstants();
        if (values != null) for (Object v : values) if ("NORMAL".equalsIgnoreCase(((Enum<?>) v).name())) return v;
        throw new NoSuchFieldException("NORMAL status constant");
    }
    static void restoreMessage(Object message, Object content) throws Throwable {
        try { Reflect.callMethod(message, "setMessageContent", content); return; } catch (Throwable ignored) {}
        Method found = null;
        for (Method m : message.getClass().getMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (Modifier.isStatic(m.getModifiers()) || p.length != 1 || m.getReturnType() != void.class
                    || content == null || !p[0].isAssignableFrom(content.getClass()) || !m.getName().startsWith("set")) continue;
            if (found != null) throw new NoSuchMethodException("ambiguous content setter");
            found = m;
        }
        if (found == null) throw new NoSuchMethodException("content setter"); found.invoke(message, content);
    }
    static void restoreStatus(Object message, Object status) throws Throwable {
        try { Reflect.callMethod(message, "setStatus", status); return; } catch (Throwable ignored) {}
        Method found = null;
        for (Method m : message.getClass().getMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (Modifier.isStatic(m.getModifiers()) || p.length != 1 || m.getReturnType() != void.class
                    || status == null || !p[0].isAssignableFrom(status.getClass()) || !m.getName().startsWith("set")) continue;
            if (found != null) throw new NoSuchMethodException("ambiguous status setter");
            found = m;
        }
        if (found == null) throw new NoSuchMethodException("status setter"); found.invoke(message, status);
    }

    static void prepare(Object message) { prepare(account(), message); }
    static void prepare(String uid, Object message) {
        installContentHook(message);
        prepare(uid, message, null, false);
    }
    static Object prepareContent(Object message, Object current) {
        if (Boolean.TRUE.equals(CONTENT_CAPTURE.get())) return current;
        if (message == null || !Config.antirecall || AntiRecall.TAMPER == 2) return current;
        // Message.getContent is on the list-render hot path. Ordinary messages were
        // already captured when their bubble bound; avoid Passport and sender-ID
        // reflection unless the model actually represents a recall.
        if (!messageRecalled(message)) return current;
        return prepare(account(), message, current, true);
    }
    static void enableDynamicContentRestore() {
        Diag.once("[fucklark] 动态防撤回兜底已启用：首次遇到消息模型时安装内容访问 hook");
    }
    private static void installContentHook(final Object model) {
        if (model == null || CONTENT_HOOKS.contains(model.getClass())) return;
        Method getter = findContentGetter(model.getClass());
        if (getter == null) {
            Diag.once("[fucklark] 未找到内容访问器: " + model.getClass().getName());
            return;
        }
        HookRuntime.hook(getter, "recall.content." + model.getClass().getName(), new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object current = chain.proceed();
                Object restored = prepareContent(chain.getThisObject(), current);
                return restored;
            }
        });
        CONTENT_HOOKS.add(model.getClass());
        Diag.once("[fucklark] 消息内容访问器已挂: " + getter.toGenericString());
    }
    private static Method findContentGetter(Class<?> model) {
        for (Class<?> type = model; type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (Modifier.isStatic(method.getModifiers()) || method.getParameterTypes().length != 0
                        || method.getReturnType().isPrimitive() || method.isBridge() || method.isSynthetic()) continue;
                if ("getContent".equals(method.getName()) || "getContentVO".equals(method.getName())) {
                    method.setAccessible(true);
                    return method;
                }
            }
        }
        Method genericGetter = findGenericContentGetter(model);
        if (genericGetter != null) {
            Diag.once("[fucklark] 消息泛型内容访问器已匹配: " + genericGetter.toGenericString());
            return genericGetter;
        }
        Class<?> contract = null;
        for (String name : new String[]{"com.ss.android.lark.chat.export.vo.ContentVO",
                "com.ss.android.lark.chat.export.entity.message.Content"}) {
            try { contract = model.getClassLoader().loadClass(name); break; } catch (Throwable ignored) {}
        }
        if (contract == null) {
            Diag.once("[fucklark] 内容访问器锚点缺少 ContentVO contract: " + model.getName());
            return null;
        }
        Method found = null;
        for (Class<?> type = model; type != null; type = type.getSuperclass()) {
            for (TypeVariable<?> variable : type.getTypeParameters()) {
                for (Type bound : variable.getBounds()) {
                    Class<?> raw = rawClass(bound);
                    if (raw == null || !contract.isAssignableFrom(raw)) continue;
                    Method contentGetter = findUniqueContentAccessor(model, raw);
                    if (contentGetter == null) continue;
                    if (found != null && !found.equals(contentGetter)) return null;
                    found = contentGetter;
                }
            }
        }
        if (found != null) found.setAccessible(true);
        if (found == null) Diag.once("[fucklark] 内容访问器未匹配: model=" + model.getName()
                + " generic-contract=" + contract.getName());
        return found;
    }

    /** A message VO may expose its generic content as an obfuscated getter returning T. */
    private static Method findGenericContentGetter(Class<?> model) {
        Method selected = null;
        for (Class<?> type = model; type != null; type = type.getSuperclass()) {
            for (TypeVariable<?> variable : type.getTypeParameters()) {
                for (Type bound : variable.getBounds()) {
                    Class<?> raw = rawClass(bound);
                    if (raw == null || raw == Object.class) continue;
                    for (Method method : model.getMethods()) {
                        if (Modifier.isStatic(method.getModifiers()) || method.getParameterTypes().length != 0
                                || method.isBridge() || method.isSynthetic()
                                || !raw.isAssignableFrom(method.getReturnType())) continue;
                        if (selected != null && !selected.equals(method)) return null;
                        selected = method;
                    }
                }
            }
        }
        if (selected != null) selected.setAccessible(true);
        return selected;
    }

    private static Method findUniqueContentAccessor(Class<?> model, Class<?> rawBound) {
        Method found = null;
        for (Method method : model.getMethods()) {
            if (Modifier.isStatic(method.getModifiers()) || method.getParameterTypes().length != 0
                    || method.isBridge() || method.isSynthetic()) continue;
            if (rawBound.isAssignableFrom(method.getReturnType())) {
                if (found != null && !found.equals(method)) return null;
                found = method;
            }
        }
        return found;
    }

    private static Class<?> rawClass(Type type) {
        if (type instanceof Class) return (Class<?>) type;
        if (type instanceof ParameterizedType) {
            Type raw = ((ParameterizedType) type).getRawType();
            return raw instanceof Class ? (Class<?>) raw : null;
        }
        return null;
    }

    private static Object prepare(String uid, Object message, Object current, boolean contentProvided) {
        if (message == null || !Config.antirecall || AntiRecall.TAMPER == 2) return current;
        try {
            String chat = messageText(message, "getChatId", ""), id = messageText(message, "getId", "");
            String key = key(uid, chat, id);
            if (key == null) return current;
            String sender = senderId(message, uid, chat, id);
            if (messageFromMe(message, uid, chat, id) || positive(sender) == 0 || uid.equals(sender)) {
                synchronized (contents) { contents.remove(key); }
                return current;
            }
            if (!messageRecalled(message)) {
                Object original = current;
                if (!contentProvided) {
                    Boolean previous = CONTENT_CAPTURE.get(); CONTENT_CAPTURE.set(Boolean.TRUE);
                    try { original = messageContent(message); }
                    finally { if (previous == null) CONTENT_CAPTURE.remove(); else CONTENT_CAPTURE.set(previous); }
                }
                if (original != null) synchronized (contents) {
                    Entry old = contents.get(key), next = new Entry(original);
                    next.recalled = old != null && old.recalled; contents.put(key, next);
                }
                return current;
            }
            Entry entry;
            synchronized (contents) { entry = contents.get(key); }
            if (entry == null) return current;
            Object status = messageStatus(message);
            if (!(status instanceof Enum)) return current;
            Object normal = normalStatus(status.getClass());
            restoreMessage(message, entry.content); restoreStatus(message, normal);
            synchronized (contents) { entry.recalled = true; }
            remember(uid, chat, id);
            return entry.content;
        } catch (Throwable t) {
            Diag.once("[fucklark] 防撤回消息模型动态访问失败: " + t);
            return current;
        }
    }
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
        try { if (markers().contains(uid, chat, id)) return true; } catch (Throwable ignored) {}
        try {
            if (!AntiRecall.nativeIsRecallMarked(uid, positive(chat), positive(id))) return false;
            remember(uid, chat, id);
            return true;
        }
        catch (Throwable ignored) { return false; }
    }
    static void clear() { synchronized (contents) { contents.clear(); } }
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

}
