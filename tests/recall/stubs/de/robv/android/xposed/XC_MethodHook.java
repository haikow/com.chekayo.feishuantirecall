package de.robv.android.xposed;

import java.lang.reflect.Member;

public abstract class XC_MethodHook {
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}

    public final void invokeBefore(MethodHookParam param) throws Throwable {
        beforeHookedMethod(param);
    }

    public final void invokeAfter(MethodHookParam param) throws Throwable {
        afterHookedMethod(param);
    }

    public static class Unhook {}

    public static class MethodHookParam {
        public Object[] args;
        public Object thisObject;
        public Member method;
        public boolean returnEarly;
        private Object result;

        public void setResult(Object result) {
            this.result = result;
            this.returnEarly = true;
        }

        public Object getResult() {
            return result;
        }
    }
}
