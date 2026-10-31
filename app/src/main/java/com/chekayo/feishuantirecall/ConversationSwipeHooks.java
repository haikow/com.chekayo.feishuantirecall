package com.chekayo.feishuantirecall;

import java.lang.reflect.Executable;
import java.lang.reflect.Member;
import io.github.libxposed.api.XposedInterface;

/** 会话滑动功能的 API102 适配层。 */
final class ConversationSwipeHooks {
    static final class Param {
        final Object thisObject;
        final Object[] args;
        private Object result;
        private boolean changed;
        Param(Object receiver, Object[] args) { thisObject = receiver; this.args = args; }
        Object result() { return result; }
        void result(Object value) { result = value; changed = true; }
    }
    static abstract class Callback {
        void before(Param p) throws Throwable {}
        void after(Param p) throws Throwable {}
    }
    static void hook(Member member, final Callback callback) {
        HookRuntime.hook((Executable) member, "conversation.read." + member.toString(), new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Param p = new Param(chain.getThisObject(), chain.getArgs().toArray());
                try { callback.before(p); }
                catch (Throwable error) { ModuleLog.log("[fucklark] conversation hook before failed: " + error); }
                Throwable failure = null;
                if (!p.changed) try { p.result = chain.proceed(p.args); }
                catch (Throwable error) { failure = error; }
                try { callback.after(p); }
                catch (Throwable error) { ModuleLog.log("[fucklark] conversation hook after failed: " + error); }
                if (failure != null) throw failure;
                return p.result;
            }
        });
    }
    private ConversationSwipeHooks() {}
}
