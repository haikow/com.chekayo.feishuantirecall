package com.chekayo.feishuantirecall;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 单条回执的独立载体。不修改浏览缓存、原载体、会话已读位置或回复窗口。 */
final class ManualReadRequest {
    static Object create(Object template, String messageId) throws Exception {
        if (!positiveId(messageId))
            throw new IllegalArgumentException("无效消息 ID");
        Class<?> model = template.getClass();
        Field channel = null, ids = null, folds = null;
        Field[] ints = new Field[2]; Field string = null; Field bool = null;
        int intCount = 0, stringCount = 0, boolCount = 0, channelCount = 0;
        for (Field field : model.getDeclaredFields()) {
            field.setAccessible(true);
            if (field.getType().getName().endsWith(".Channel")) {
                channelCount++;
                channel = field;
            } else if (List.class.isAssignableFrom(field.getType())) {
                String generic = String.valueOf(field.getGenericType());
                if (generic.contains("String")) { if (ids != null) throw new NoSuchFieldException("ambiguous ids field"); ids = field; }
                else if (generic.contains("Long")) { if (folds != null) throw new NoSuchFieldException("ambiguous folds field"); folds = field; }
            } else if (field.getType() == int.class) {
                if (intCount < ints.length) ints[intCount] = field;
                intCount++;
            } else if (field.getType() == String.class) { stringCount++; string = field; }
            else if (field.getType() == boolean.class) { boolCount++; bool = field; }
        }
        if (channelCount != 1 || ids == null || folds == null || intCount != 2
                || stringCount != 1 || boolCount != 1)
            throw new NoSuchFieldException("单条已读载体字段形状未适配");
        Class<?> builder = findBuilderClass(model);
        Object value = builder.getDeclaredConstructor().newInstance();
        Field builderChannel = matchingField(builder, channel.getType());
        Field builderIds = matchingListField(builder, ids);
        Field builderFolds = matchingListField(builder, folds);
        Field builderInt1 = matchingField(builder, ints[0].getType(), 0);
        Field builderInt2 = matchingField(builder, ints[1].getType(), 1);
        Field builderString = matchingField(builder, String.class);
        Field builderBool = matchingField(builder, boolean.class);
        if (builderChannel == null || builderIds == null || builderFolds == null || builderInt1 == null
                || builderInt2 == null || builderString == null || builderBool == null)
            throw new NoSuchFieldException("单条已读 builder 字段形状未适配");
        builderChannel.set(value, channel.get(template));
        builderIds.set(value, new ArrayList<String>(Collections.singletonList(messageId)));
        builderFolds.set(value, new ArrayList<Long>());
        builderInt1.setInt(value, -1); // 不推进 max_position
        builderInt2.setInt(value, 0);  // 不清理整个会话未读角标
        builderString.set(value, "");
        builderBool.setBoolean(value, false);
        Method build = null;
        for (Method m : builder.getDeclaredMethods()) {
            if (m.getParameterTypes().length == 0 && m.getReturnType() == model) {
                if (build != null) throw new NoSuchMethodException("单条已读 builder 不唯一");
                build = m;
            }
        }
        if (build == null) throw new NoSuchMethodException("单条已读 builder");
        build.setAccessible(true);
        return build.invoke(value);
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
    private static Class<?> findBuilderClass(Class<?> model) throws NoSuchFieldException {
        Class<?> found = null;
        for (Class<?> nested : model.getDeclaredClasses()) {
            try { nested.getDeclaredConstructor(); } catch (NoSuchMethodException ignored) { continue; }
            boolean buildsModel = false;
            for (Method method : nested.getDeclaredMethods())
                if (method.getParameterTypes().length == 0 && method.getReturnType() == model) buildsModel = true;
            if (!buildsModel) continue;
            if (found != null) throw new NoSuchFieldException("ambiguous request builder class");
            found = nested;
        }
        if (found == null) throw new NoSuchFieldException("request builder class by shape");
        return found;
    }
    private static Field matchingListField(Class<?> type, Field source) throws NoSuchFieldException {
        String generic = String.valueOf(source.getGenericType());
        int left = generic.lastIndexOf('<'), right = generic.lastIndexOf('>');
        String element = left >= 0 && right > left ? generic.substring(left + 1, right) : "";
        Field found = null;
        for (Field f : type.getDeclaredFields()) {
            if (List.class.isAssignableFrom(f.getType())
                    && (element.isEmpty() || String.valueOf(f.getGenericType()).contains(element))) {
                if (found != null) throw new NoSuchFieldException("ambiguous builder list field");
                found = f;
            }
        }
        if (found != null) found.setAccessible(true);
        return found;
    }
    private static Field matchingField(Class<?> type, Class<?> fieldType) throws NoSuchFieldException {
        return matchingField(type, fieldType, -1);
    }
    private static Field matchingField(Class<?> type, Class<?> fieldType, int ordinal) throws NoSuchFieldException {
        int seen = 0;
        Field found = null;
        for (Field f : type.getDeclaredFields()) {
            if (f.getType() != fieldType) continue;
            if (ordinal >= 0) {
                if (seen++ != ordinal) continue;
                f.setAccessible(true); return f;
            }
            if (found != null) throw new NoSuchFieldException("ambiguous builder field type " + fieldType.getName());
            found = f;
        }
        if (found != null) found.setAccessible(true);
        return found;
    }
    private ManualReadRequest() {}
}
