package com.chekayo.feishuantirecall;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/** Wire 协议字段号来自稳定 PB schema；不依赖宿主 builder 的混淆名称。 */
final class ConversationReadProtocol {
    static byte[] chat(String chat) {
        requireId(chat);
        return new Writer().text(1, chat).number(2, 1).bytes();
    }
    static byte[] page(String chat, int before, int count) {
        requireId(chat);
        if (before < 0 || count < 1 || count > 100) throw new IllegalArgumentException("invalid page");
        // PREVIOUS_PAGE，position 是上一页的最小位置；不订阅会话，不进入聊天。
        return new Writer().text(1, chat).number(2, 3).number(3, 0).number(4, before)
                .number(5, count).number(6, 3).number(7, 0).number(8, 0).number(9, 1).bytes();
    }
    static byte[] read(String chat, Collection<String> ids, int position, int badgePosition) {
        requireId(chat);
        if (position < -1 || badgePosition < 0) throw new IllegalArgumentException("invalid read position");
        Writer request = new Writer();
        for (String id : ids) { requireId(id); request.text(1, id); }
        request.blob(2, new Writer().text(1, chat).number(2, 1).bytes()); // Channel.Type.CHAT
        // 普通会话不设置 thread_id；空字符串仍是“字段存在”，Rust 会尝试将它解析为整数。
        return request.number(3, position).number(6, badgePosition).bytes();
    }
    static void requireId(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException("invalid ID");
        if (Long.parseLong(value) <= 0) throw new IllegalArgumentException("invalid ID");
    }

    /** 以点击时的会话位置为上界。分页无法证明覆盖范围时，不提交最终已读位置。 */
    static final class Snapshot {
        final String chat;
        final int readPosition, lastPosition, badgePosition;
        final LinkedHashSet<String> ids = new LinkedHashSet<String>();
        int before;
        boolean complete;
        Snapshot(String chat, int readPosition, int lastPosition, int badgePosition) {
            requireId(chat);
            if (readPosition < -1 || lastPosition < 0 || lastPosition == Integer.MAX_VALUE || badgePosition < 0)
                throw new IllegalArgumentException("invalid snapshot");
            this.chat = chat; this.readPosition = readPosition; this.lastPosition = lastPosition;
            this.badgePosition = badgePosition; before = lastPosition + 1;
            complete = readPosition >= lastPosition;
        }
        void add(List<Item> page, boolean dataComplete) {
            if (complete) return;
            if (!dataComplete || page.isEmpty()) throw new IllegalStateException("消息尚未完整同步，请稍后重试");
            int min = before;
            for (Item item : page) {
                if (item.position < 0) throw new IllegalStateException("消息位置无效");
                if (!chat.equals(item.chat)) throw new IllegalStateException("消息会话校验失败");
                if (item.position < before) min = Math.min(min, item.position);
                if (item.position <= readPosition || item.position > lastPosition) continue;
                if (item.id != null && !item.id.isEmpty()) { requireId(item.id); ids.add(item.id); }
            }
            if (min >= before) throw new IllegalStateException("消息分页没有推进，请稍后重试");
            if (min <= readPosition + 1) complete = true;
            before = min;
        }
        List<List<String>> batches(int size) {
            if (!complete || size < 1) throw new IllegalStateException("unverified conversation range");
            List<List<String>> result = new ArrayList<List<String>>();
            List<String> batch = null;
            for (String id : ids) {
                if (batch == null || batch.size() == size) { batch = new ArrayList<String>(); result.add(batch); }
                batch.add(id);
            }
            return result;
        }
    }
    static final class Item {
        final String id, chat;
        final int position;
        Item(String id, String chat, int position) { this.id = id; this.chat = chat; this.position = position; }
    }
    private static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        Writer number(int tag, long value) { varint(((long) tag) << 3); varint(value); return this; }
        Writer text(int tag, String value) { return blob(tag, value.getBytes(StandardCharsets.UTF_8)); }
        Writer blob(int tag, byte[] value) {
            varint((((long) tag) << 3) | 2); varint(value.length); out.write(value, 0, value.length); return this;
        }
        private void varint(long value) {
            while ((value & ~0x7fL) != 0) { out.write(((int) value & 0x7f) | 0x80); value >>>= 7; }
            out.write((int) value);
        }
        byte[] bytes() { return out.toByteArray(); }
    }
    private ConversationReadProtocol() {}
}
