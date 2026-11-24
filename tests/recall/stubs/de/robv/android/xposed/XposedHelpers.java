package de.robv.android.xposed;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public final class XposedHelpers {
    public static XC_MethodHook.Unhook findAndHookMethod(String className, ClassLoader classLoader, String methodName, Object... parameterTypesAndCallback) {
        Class<?> clazz = findClass(className, classLoader);
        return findAndHookMethod(clazz, methodName, parameterTypesAndCallback);
    }

    public static XC_MethodHook.Unhook findAndHookMethod(Class<?> clazz, String methodName, Object... parameterTypesAndCallback) {
        if (parameterTypesAndCallback == null || parameterTypesAndCallback.length == 0
                || !(parameterTypesAndCallback[parameterTypesAndCallback.length - 1] instanceof XC_MethodHook)) {
            throw new IllegalArgumentException("no callback defined");
        }
        XC_MethodHook callback = (XC_MethodHook) parameterTypesAndCallback[parameterTypesAndCallback.length - 1];
        Class<?>[] paramTypes = new Class<?>[parameterTypesAndCallback.length - 1];
        for (int i = 0; i < paramTypes.length; i++) {
            paramTypes[i] = (Class<?>) parameterTypesAndCallback[i];
        }
        try {
            Method m = findDeclaredMethod(clazz, methodName, paramTypes);
            return XposedBridge.hookMethod(m, callback);
        } catch (NoSuchMethodException e) {
            throw new NoSuchMethodError(clazz.getName() + "#" + methodName);
        }
    }

    public static Class<?> findClass(String className, ClassLoader classLoader) {
        try {
            return Class.forName(className, false, classLoader == null ? XposedHelpers.class.getClassLoader() : classLoader);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    public static Object getStaticObjectField(Class<?> clazz, String fieldName) {
        try {
            Field f = findField(clazz, fieldName);
            return f.get(null);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static Object callMethod(Object obj, String methodName, Object... args) {
        if (obj == null) throw new NullPointerException("obj == null");
        Object[] safeArgs = args == null ? new Object[0] : args;
        Method target = findBestMethod(obj.getClass(), methodName, safeArgs, false);
        if (target == null) {
            throw new NoSuchMethodError(obj.getClass().getName() + "#" + methodName);
        }
        try {
            target.setAccessible(true);
            return target.invoke(obj, safeArgs);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new RuntimeException(cause);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static Object callStaticMethod(Class<?> clazz, String methodName, Object... args) {
        if (clazz == null) throw new NullPointerException("clazz == null");
        Object[] safeArgs = args == null ? new Object[0] : args;
        Method target = findBestMethod(clazz, methodName, safeArgs, true);
        if (target == null) {
            throw new NoSuchMethodError(clazz.getName() + "#" + methodName);
        }
        try {
            target.setAccessible(true);
            return target.invoke(null, safeArgs);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new RuntimeException(cause);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static void setObjectField(Object obj, String fieldName, Object value) {
        try {
            Field f = findField(obj.getClass(), fieldName);
            f.set(obj, value);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static Object getObjectField(Object obj, String fieldName) {
        try {
            Field f = findField(obj.getClass(), fieldName);
            return f.get(obj);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    private static Field findField(Class<?> clazz, String fieldName) throws NoSuchFieldException {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(clazz.getName() + "#" + fieldName);
    }

    private static Method findDeclaredMethod(Class<?> clazz, String name, Class<?>[] paramTypes) throws NoSuchMethodException {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name, paramTypes);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {}
        }
        throw new NoSuchMethodException(clazz.getName() + "#" + name);
    }

    private static Method findBestMethod(Class<?> clazz, String name, Object[] args, boolean onlyStatic) {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!name.equals(m.getName())) continue;
                if (onlyStatic && !Modifier.isStatic(m.getModifiers())) continue;
                if (matches(m.getParameterTypes(), args)) return m;
            }
        }
        for (Method m : clazz.getMethods()) {
            if (!name.equals(m.getName())) continue;
            if (onlyStatic && !Modifier.isStatic(m.getModifiers())) continue;
            if (matches(m.getParameterTypes(), args)) return m;
        }
        return null;
    }

    private static boolean matches(Class<?>[] paramTypes, Object[] args) {
        if (paramTypes.length != args.length) return false;
        for (int i = 0; i < paramTypes.length; i++) {
            Object arg = args[i];
            Class<?> pt = paramTypes[i];
            if (arg == null) {
                if (pt.isPrimitive()) return false;
            } else if (!isAssignable(pt, arg.getClass())) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAssignable(Class<?> paramType, Class<?> argType) {
        if (paramType.isAssignableFrom(argType)) return true;
        if (!paramType.isPrimitive()) return false;
        if (paramType == boolean.class) return argType == Boolean.class;
        if (paramType == byte.class) return argType == Byte.class;
        if (paramType == short.class) return argType == Short.class;
        if (paramType == char.class) return argType == Character.class;
        if (paramType == int.class) return argType == Integer.class;
        if (paramType == long.class) return argType == Long.class;
        if (paramType == float.class) return argType == Float.class;
        if (paramType == double.class) return argType == Double.class;
        return false;
    }
}
