package com.chekayo.feishuantirecall;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 按账号取得宿主 RustClient，分页确认范围后提交真实消息回执和会话已读位置。 */
final class ConversationReadService {
    interface Completion { void done(boolean success, String message); }
    private static final Map<String, WeakReference<Object>> CLIENTS = new ConcurrentHashMap<String, WeakReference<Object>>();
    private static final java.util.Set<String> RUNNING = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static final String PB = "com.bytedance.lark.pb.im.v1.";

    static void install(ClassLoader loader) throws Throwable {
        Class<?> client = loader.loadClass("com.bytedance.lark.rustclient.internal.UserRustClient");
        for (Constructor<?> constructor : client.getDeclaredConstructors())
            ConversationSwipeHooks.hook(constructor, new ConversationSwipeHooks.Callback() {
                @Override void after(ConversationSwipeHooks.Param p) throws Throwable { remember(p.thisObject); }
            });
    }
    private static void remember(Object client) throws Exception {
        Field uid = null;
        for (Field field : client.getClass().getDeclaredFields()) {
            if (field.getType() != String.class || Modifier.isStatic(field.getModifiers())) continue;
            if (uid != null) return; // 身份字段多义时不猜测账号。
            uid = field;
        }
        if (uid == null) return;
        uid.setAccessible(true);
        String account = (String) uid.get(client);
        ConversationReadProtocol.requireId(account);
        Session session = new Session(client, account);
        CLIENTS.put(account, new WeakReference<Object>(client));
        Diag.once("[fucklark] conversation-read client matched: " + session.request.toGenericString());
    }

    static void mark(final String chat, final Completion completion) {
        final String account = RecallMessages.account();
        try { ConversationReadProtocol.requireId(chat); ConversationReadProtocol.requireId(account); }
        catch (Exception invalid) { completion.done(false, "会话或账号尚未就绪，请稍后重试"); return; }
        WeakReference<Object> ref = CLIENTS.get(account);
        final Object client = ref == null ? null : ref.get();
        if (client == null || AntiRecall.TAMPER == 2) {
            completion.done(false, "已读服务尚未就绪，请重新启动飞书后重试"); return;
        }
        final String key = account + ":" + chat;
        if (!RUNNING.add(key)) { completion.done(false, "此会话正在标记已读"); return; }
        // 点击时截取缓存；提交成功前不消费，失败可重试。
        final LinkedHashSet<String> pending = AntiRecall.snapshotPendingRead(chat);
        Thread worker = new Thread(new Runnable() {
            @Override public void run() {
                boolean submitted = false;
                try {
                    Session session = new Session(client, account);
                    Object response = session.call("MGetChatsRequest", "MGetChatsResponse", "MGET_CHATS",
                            ConversationReadProtocol.chat(chat), false);
                    Map<?, ?> chats = (Map<?, ?>) field(field(response, "entity"), "chats");
                    Object model = chats.get(chat);
                    if (model == null || !chat.equals(String.valueOf(field(model, "id"))))
                        throw new IllegalStateException("没有取得当前会话信息");
                    int last = integer(model, "last_message_position");
                    ConversationReadProtocol.Snapshot snapshot = new ConversationReadProtocol.Snapshot(chat,
                            integer(model, "read_position"), last, integer(model, "last_message_position_badge_count"));
                    for (int page = 0; !snapshot.complete; page++) {
                        if (page >= 500) throw new IllegalStateException("未读消息过多，请分批处理后重试");
                        Object messages = session.call("GetChatMessagesRequest", "GetChatMessagesResponse", "GET_CHAT_MESSAGES",
                                ConversationReadProtocol.page(chat, snapshot.before, 100), false);
                        List<ConversationReadProtocol.Item> items = new ArrayList<ConversationReadProtocol.Item>();
                        Map<?, ?> entities = (Map<?, ?>) field(field(messages, "entity"), "messages");
                        for (Object item : (List<?>) field(messages, "message_items")) {
                            String kind = String.valueOf(field(item, "item_type"));
                            if (!"NORMAL_MESSAGE".equals(kind))
                                throw new IllegalStateException("未读范围包含尚未适配的消息条目: " + kind);
                            String id = String.valueOf(field(item, "item_id"));
                            Object message = entities.get(id);
                            if (message == null) throw new IllegalStateException("消息尚未完整同步，请稍后重试");
                            String owner = String.valueOf(field(message, "chat_id"));
                            if (!chat.equals(owner)) throw new IllegalStateException("消息会话校验失败");
                            items.add(new ConversationReadProtocol.Item(id, owner, integer(message, "position")));
                        }
                        for (Object position : (List<?>) field(messages, "invalid_positions"))
                            items.add(new ConversationReadProtocol.Item("", chat, ((Number) position).intValue()));
                        boolean complete = Boolean.TRUE.equals(field(messages, "data_complete"))
                                && ((List<?>) field(messages, "missing_positions")).isEmpty();
                        snapshot.add(items, complete);
                    }
                    snapshot.ids.addAll(pending);
                    List<List<String>> batches = snapshot.batches(200);
                    // 先逐批提交 IDs，全部确认后才推进到快照上界。期间新消息不在这个上界内。
                    for (List<String> batch : batches) {
                        session.call("UpdateMessagesMeReadRequest", "UpdateMessagesMeReadResponse", "UPDATE_MESSAGES_ME_READ",
                                ConversationReadProtocol.read(chat, batch, -1, 0), true);
                        submitted = true;
                    }
                    session.call("UpdateMessagesMeReadRequest", "UpdateMessagesMeReadResponse", "UPDATE_MESSAGES_ME_READ",
                            ConversationReadProtocol.read(chat, Collections.<String>emptyList(), snapshot.lastPosition, snapshot.badgePosition), true);
                    session.check();
                    AntiRecall.ackPendingRead(chat, snapshot.ids);
                    completion.done(true, "此会话的未读消息已标为已读");
                } catch (Throwable error) {
                    Diag.once("[fucklark] conversation-read failed: " + AntiRecall.describe(error));
                    completion.done(false, submitted ? "部分消息已标为已读，其余消息请稍后重试" : "标为已读失败，请稍后重试");
                } finally { RUNNING.remove(key); }
            }
        }, "feishukit-conversation-read");
        worker.setDaemon(true);
        worker.start();
    }

    static final class Session {
        final Object client;
        final String account;
        final ClassLoader loader;
        final Method request;
        Session(Object client, String account) throws Exception {
            this.client = client; this.account = account; loader = client.getClass().getClassLoader();
            Method found = null;
            for (Method method : client.getClass().getMethods()) {
                if (method.isBridge() || method.isSynthetic() || method.getParameterTypes().length != 1) continue;
                Class<?> arg = method.getParameterTypes()[0];
                if (!arg.getName().startsWith("com.bytedance.lark.rustclient.api.")) continue;
                try { method.getReturnType().getMethod("execute"); }
                catch (NoSuchMethodException absent) { continue; }
                if (found != null) throw new NoSuchMethodException("Rust 请求入口不唯一");
                found = method;
            }
            if (found == null) throw new NoSuchMethodException("Rust 请求入口不可用");
            request = found;
        }
        void check() {
            if (!account.equals(RecallMessages.account()) || AntiRecall.TAMPER == 2)
                throw new IllegalStateException("账号已变化");
        }
        Object call(String requestName, String responseName, String commandName, byte[] bytes, boolean manual) throws Exception {
            check();
            Boolean previous = AntiRecall.KN_REPLAY.get();
            try {
                if (manual) AntiRecall.KN_REPLAY.set(Boolean.TRUE);
                Class<?> requestType = loader.loadClass(PB + requestName), responseType = loader.loadClass(PB + responseName);
                Object adapter = requestType.getField("ADAPTER").get(null);
                Object model = adapter.getClass().getMethod("decode", byte[].class).invoke(adapter, (Object) bytes);
                Object builder = model.getClass().getMethod("newBuilder").invoke(model);
                if (manual) clearThreadDefault(builder);
                Object responseAdapter = responseType.getField("ADAPTER").get(null);
                Class<?> commandType = loader.loadClass("com.bytedance.lark.pb.basic.v1.Command");
                Object command = commandType.getField(commandName).get(null);
                Object rustRequest = null;
                for (Constructor<?> constructor : request.getParameterTypes()[0].getDeclaredConstructors()) {
                    Class<?>[] types = constructor.getParameterTypes();
                    if (constructor.isSynthetic() || types.length != 8 || !types[0].isInstance(builder)
                            || !types[1].isInstance(responseAdapter) || types[2] != commandType || types[3] != String.class) continue;
                    if (rustRequest != null) throw new NoSuchMethodException("Rust 请求构造不唯一");
                    rustRequest = constructor.newInstance(builder, responseAdapter, command, "feishukit.conversation.read", null, null, null, null);
                }
                if (rustRequest == null) throw new NoSuchMethodException("Rust 请求构造不可用");
                check();
                Object call = request.invoke(client, rustRequest);
                Object output;
                if (manual) {
                    // 与宿主 UPDATE_MESSAGES_ME_READ 一致使用 Observable。
                    // 订阅保留在当前工作线程，避免丢失本次放行标记。
                    Method subscribe = null;
                    for (Method method : call.getClass().getMethods()) {
                        if (method.getParameterTypes().length == 0 && !method.isBridge() && !method.isSynthetic()
                                && "io.reactivex.Observable".equals(method.getReturnType().getName())) {
                            if (subscribe != null) throw new NoSuchMethodException("异步回执入口不唯一");
                            subscribe = method;
                        }
                    }
                    if (subscribe == null) throw new NoSuchMethodException("异步回执入口不可用");
                    subscribe.setAccessible(true);
                    Object observable = subscribe.invoke(call);
                    Method timeout = observable.getClass().getMethod("timeout", long.class, java.util.concurrent.TimeUnit.class);
                    Object bounded = timeout.invoke(observable, 20L, java.util.concurrent.TimeUnit.SECONDS);
                    Method first = bounded.getClass().getMethod("blockingFirst");
                    output = first.invoke(bounded);
                } else {
                    Method execute = call.getClass().getMethod("execute"); execute.setAccessible(true);
                    output = execute.invoke(call);
                }
                check();
                for (Class<?> type = output == null ? null : output.getClass(); type != null; type = type.getSuperclass()) {
                    for (Field field : type.getDeclaredFields()) {
                        if (Modifier.isStatic(field.getModifiers())) continue;
                        field.setAccessible(true);
                        Object value = field.get(output);
                        if (responseType.isInstance(value)) return value;
                    }
                }
                throw responseError(output, commandName);
            } finally {
                if (previous == null) AntiRecall.KN_REPLAY.remove(); else AntiRecall.KN_REPLAY.set(previous);
            }
        }
    }
    /** PB decode 会为省略的 thread_id 补空串；普通会话发送前需再次清除。 */
    static void clearThreadDefault(Object builder) throws Exception {
        Field thread = null;
        for (Field field : builder.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) continue;
            if (thread != null) throw new NoSuchFieldException("普通会话回执话题字段不唯一");
            thread = field;
        }
        if (thread == null) throw new NoSuchFieldException("普通会话回执缺少话题字段");
        thread.setAccessible(true);
        thread.set(builder, null);
    }

    /** 从错误载体按异常返回契约取得根因，不依赖 RustClientError 的混淆 getter 名。 */
    private static Exception responseError(Object output, String command) throws Exception {
        if (output == null) return new IllegalStateException(command + ": 空响应");
        for (Class<?> type = output.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                Object error = field.get(output);
                if (error instanceof Throwable) return new IllegalStateException(command + ": 请求失败", (Throwable) error);
                if (error == null || !error.getClass().getName().startsWith("com.bytedance.lark.rustclient.api.")) continue;
                for (Method method : error.getClass().getMethods()) {
                    if (method.getParameterTypes().length == 0 && !method.isBridge() && !method.isSynthetic()
                            && Throwable.class.isAssignableFrom(method.getReturnType())) {
                        method.setAccessible(true);
                        Throwable cause = (Throwable) method.invoke(error);
                        if (cause != null) return new IllegalStateException(command + ": 请求失败", cause);
                    }
                }
            }
        }
        return new IllegalStateException(command + ": 服务未返回成功响应 " + output.getClass().getSimpleName());
    }

    static Object field(Object value, String name) throws Exception {
        if (value == null) throw new IllegalStateException("缺少 " + name);
        return value.getClass().getField(name).get(value);
    }
    static int integer(Object value, String name) throws Exception {
        Object number = field(value, name);
        if (!(number instanceof Number)) throw new IllegalStateException("缺少位置 " + name);
        return ((Number) number).intValue();
    }
    private ConversationReadService() {}
}
