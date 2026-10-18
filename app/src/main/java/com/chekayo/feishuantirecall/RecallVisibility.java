package com.chekayo.feishuantirecall;

import android.view.View;
import android.view.ViewTreeObserver;
import java.lang.ref.WeakReference;

/** 仅跟踪可见气泡；窗口重新显示时唤醒刷新，弱引用避免保活整个视图树。 */
class RecallVisibility implements View.OnAttachStateChangeListener,
        ViewTreeObserver.OnWindowFocusChangeListener {
    private final WeakReference<View> view;
    private WeakReference<ViewTreeObserver> observer = new WeakReference<ViewTreeObserver>(null);
    RecallVisibility(View view) { this.view = new WeakReference<View>(view); }
    static boolean visible(View view) {
        return view != null && view.isAttachedToWindow() && view.getWindowVisibility() == View.VISIBLE && view.isShown();
    }
    void watch() {
        View target = view.get();
        if (target == null) return;
        target.addOnAttachStateChangeListener(this);
        if (target.isAttachedToWindow()) observe(target);
    }
    private void observe(View target) {
        unobserve();
        ViewTreeObserver current = target.getViewTreeObserver();
        if (!current.isAlive()) return;
        // 仅用焦点事件唤醒，避免每次 RecyclerView 全局布局都启动整批消息扫描。
        // 单条气泡尺寸由 Binding 自己的 OnLayoutChangeListener 更新。
        current.addOnWindowFocusChangeListener(this);
        observer = new WeakReference<ViewTreeObserver>(current);
    }
    private void unobserve() {
        ViewTreeObserver current = observer.get();
        if (current != null && current.isAlive()) {
            current.removeOnWindowFocusChangeListener(this);
        }
        observer.clear();
    }
    void dispose() {
        unobserve();
        View target = view.get();
        if (target != null) target.removeOnAttachStateChangeListener(this);
    }
    public void onViewAttachedToWindow(View target) { observe(target); wake(); }
    public void onViewDetachedFromWindow(View target) { unobserve(); }
    public void onWindowFocusChanged(boolean focused) { if (focused) wake(); }
    boolean visible() { return visible(view.get()); }
    private void wake() { if (visible()) RecallBubbleUi.schedule(); }
}
