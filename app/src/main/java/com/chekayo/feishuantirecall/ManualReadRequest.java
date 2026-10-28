package com.chekayo.feishuantirecall;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 单条回执的独立 protobuf 载体，不推进会话位置或修改自动已读缓存。 */
final class ManualReadRequest {
    static Object create(Object template, String messageId) throws Exception {
        if (!positiveId(messageId)) throw new IllegalArgumentException("无效消息 ID");
        Class<?> model = template.getClass();
        Field channel = null, ids = null, folds = null, string = null, bool = null;
        Field[] integers = new Field[2];
        int channelCount = 0, integerCount = 0, stringCount = 0, boolCount = 0;
        for (Field field : model.getDeclaredFields()) {
            field.setAccessible(true);
            if (field.getType().getName().endsWith(".Channel")) {
                channel = field; channelCount++;
            } else if (List.class.isAssignableFrom(field.getType())) {
                String generic = String.valueOf(field.getGenericType());
                if (generic.contains("String")) { if (ids != null) throw new NoSuchFieldException("ambiguous message IDs"); ids = field; }
                else if (generic.contains("Long")) { if (folds != null) throw new NoSuchFieldException("ambiguous fold IDs"); folds = field; }
            } else if (field.getType() == int.class) {
                if (integerCount < integers.length) integers[integerCount] = field;
                integerCount++;
            } else if (field.getType() == String.class) { string = field; stringCount++; }
            else if (field.getType() == boolean.class) { bool = field; boolCount++; }
        }
        if (channelCount != 1 || ids == null || folds == null || integerCount != 2
                || stringCount != 1 || boolCount != 1)
            throw new NoSuchFieldException("已读请求字段形状不匹配");

        Class<?> builder = findBuilder(model);
        Object instance = builder.getDeclaredConstructor().newInstance();
        field(builder, channel.getType()).set(instance, channel.get(template));
        listField(builder, ids).set(instance, new ArrayList<String>(Collections.singletonList(messageId)));
        listField(builder, folds).set(instance, new ArrayList<Long>());
        sameTypeField(builder, int.class, 0).setInt(instance, -1);
        sameTypeField(builder, int.class, 1).setInt(instance, 0);
        field(builder, String.class).set(instance, "");
        field(builder, boolean.class).setBoolean(instance, false);
        Method build = uniqueBuildMethod(builder, model);
        return build.invoke(instance);
    }

    private static Class<?> findBuilder(Class<?> model) throws NoSuchMethodException {
        Class<?> found = null;
        for (Class<?> nested : model.getDeclaredClasses()) {
            try { nested.getDeclaredConstructor(); } catch (NoSuchMethodException ignored) { continue; }
            for (Method method : nested.getDeclaredMethods()) {
                if (method.getParameterTypes().length != 0 || method.getReturnType() != model) continue;
                if (found != null && found != nested) throw new NoSuchMethodException("ambiguous request builder");
                found = nested;
            }
        }
        if (found == null) throw new NoSuchMethodException("request builder by shape");
        return found;
    }

    private static Method uniqueBuildMethod(Class<?> builder, Class<?> model) throws NoSuchMethodException {
        Method found = null;
        for (Method method : builder.getDeclaredMethods()) {
            if (method.getParameterTypes().length != 0 || method.getReturnType() != model) continue;
            if (found != null) throw new NoSuchMethodException("ambiguous request build method");
            found = method;
        }
        if (found == null) throw new NoSuchMethodException("request build method");
        found.setAccessible(true);
        return found;
    }

    private static Field listField(Class<?> owner, Field source) throws NoSuchFieldException {
        String generic = String.valueOf(source.getGenericType());
        int left = generic.lastIndexOf('<'), right = generic.lastIndexOf('>');
        String element = left >= 0 && right > left ? generic.substring(left + 1, right) : "";
        Field found = null;
        for (Field field : owner.getDeclaredFields()) {
            if (!List.class.isAssignableFrom(field.getType())
                    || !String.valueOf(field.getGenericType()).contains(element)) continue;
            if (found != null) throw new NoSuchFieldException("ambiguous builder list field");
            found = field;
        }
        if (found == null) throw new NoSuchFieldException("builder list field by generic type");
        found.setAccessible(true);
        return found;
    }

    private static Field sameTypeField(Class<?> owner, Class<?> type, int ordinal) throws NoSuchFieldException {
        int seen = 0;
        for (Field field : owner.getDeclaredFields()) if (field.getType() == type) {
            if (seen++ != ordinal) continue;
            field.setAccessible(true);
            return field;
        }
        throw new NoSuchFieldException("builder field ordinal " + ordinal + " type=" + type.getName());
    }

    private static Field field(Class<?> owner, Class<?> type) throws NoSuchFieldException {
        Field found = null;
        for (Field field : owner.getDeclaredFields()) if (field.getType() == type) {
            if (found != null) throw new NoSuchFieldException("ambiguous builder field type=" + type.getName());
            found = field;
        }
        if (found == null) throw new NoSuchFieldException("builder field type=" + type.getName());
        found.setAccessible(true);
        return found;
    }

    private static boolean positiveId(String id) {
        if (id == null || id.isEmpty() || id.charAt(0) == '0') return false;
        long value = 0;
        for (int i = 0; i < id.length(); i++) {
            int digit = id.charAt(i) - '0';
            if (digit < 0 || digit > 9 || value > (Long.MAX_VALUE - digit) / 10) return false;
            value = value * 10 + digit;
        }
        return value > 0;
    }

    private ManualReadRequest() {}
}
