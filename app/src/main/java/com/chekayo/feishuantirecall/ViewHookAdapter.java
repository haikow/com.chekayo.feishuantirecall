package com.chekayo.feishuantirecall;

import java.lang.reflect.Method;
import java.util.Arrays;
import io.github.libxposed.api.XposedInterface;

/** Before/after callbacks for the view hooks shared with the legacy build. */
final class ViewHookAdapter {
    static class Param {
        final Object thisObject;
        final Object[] args;
        Param(Object receiver, Object[] args) { thisObject = receiver; this.args = args; }
    }

    static class Callback {
        protected void beforeHookedMethod(Param param) throws Throwable {}
        protected void afterHookedMethod(Param param) throws Throwable {}
    }

    static void hook(Method method, final Callback callback) {
        final String id = "view." + callback.getClass().getName() + "."
                + method.getDeclaringClass().getName() + "." + method.getName()
                + Arrays.toString(method.getParameterTypes());
        HookRuntime.hook(method, id, new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Param param = new Param(chain.getThisObject(), chain.getArgs().toArray());
                try { callback.beforeHookedMethod(param); }
                catch (Throwable t) { ModuleLog.log("[fucklark] view hook before failed: " + t); }
                try { return chain.proceed(param.args); }
                finally {
                    try { callback.afterHookedMethod(param); }
                    catch (Throwable t) { ModuleLog.log("[fucklark] view hook after failed: " + t); }
                }
            }
        });
    }

    static void hookAll(Class<?> type, String name, Callback callback) {
        for (Method method : type.getDeclaredMethods()) {
            if (name.equals(method.getName())) hook(method, callback);
        }
    }

    static void hook(Class<?> type, String name, Callback callback, Class<?>... params)
            throws NoSuchMethodException {
        hook(type.getDeclaredMethod(name, params), callback);
    }

    private ViewHookAdapter() {}
}
