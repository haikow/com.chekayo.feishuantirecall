package com.chekayo.feishuantirecall;

import java.lang.reflect.Member;
import de.robv.android.xposed.XC_MethodHook;

/** 会话滑动功能的 API 适配层。 */
final class ConversationSwipeHooks {
    static final class Param {
        final Object thisObject;
        final Object[] args;
        private final XC_MethodHook.MethodHookParam host;
        Param(XC_MethodHook.MethodHookParam host) { this.host = host; thisObject = host.thisObject; args = host.args; }
        Object result() { return host.getResult(); }
        void result(Object value) { host.setResult(value); }
    }
    static abstract class Callback {
        void before(Param p) throws Throwable {}
        void after(Param p) throws Throwable {}
    }
    static void hook(Member method, final Callback callback) {
        RecallHooks.hook(method, "conversation.read." + method.toString(), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable { callback.before(new Param(p)); }
            @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable { callback.after(new Param(p)); }
        });
    }
    private ConversationSwipeHooks() {}
}
