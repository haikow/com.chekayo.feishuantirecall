package com.chekayo.feishuantirecall;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Path;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedInterface;

/** 在保留的原消息气泡上显示撤回提示或虚线标识；回收时恢复宿主布局。 */
final class RecallBubbleUi {
    private static final long POLL_BURST_MS = 2500L;
    private static final long POLL_INTERVAL_MS = 1000L;
    static final String BLOCK = "com.ss.android.lark.chat.chatwindow.biz.component.messagelist.render.block.MessageGlobalBlock";
    private static final Map<View, Binding> bindings = new WeakHashMap<View, Binding>();
    private static Handler handler;
    private static boolean scheduled;
    private static long pollUntil;
    private static Method outline;
    private static volatile String hookStatus = "未安装";
    static String status() { return hookStatus; }

    static Method findOutline(Class<?> layout) throws NoSuchMethodException {
        Method found = null;
        for (Method method : layout.getDeclaredMethods()) {
            if (Modifier.isStatic(method.getModifiers()) || method.getReturnType() != Path.class
                    || !java.util.Arrays.equals(method.getParameterTypes(),
                    new Class<?>[]{float.class, float.class, float.class, float.class})) continue;
            if (found != null) throw new NoSuchMethodException("ambiguous bubble outline");
            found = method;
        }
        if (found == null) throw new NoSuchMethodException("bubble outline");
        found.setAccessible(true);
        return found;
    }

    static Method findBind(Class<?> block, Class<?> holder) throws Throwable {
        Method found = null;
        for (Method m : block.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (Modifier.isStatic(m.getModifiers()) || Modifier.isPrivate(m.getModifiers())
                    || m.isBridge() || m.isSynthetic() || m.getReturnType() != void.class
                    || p.length != 2 || p[0] != holder) continue;
            try {
                if (p[1].getMethod("getId").getReturnType() != String.class
                        || p[1].getMethod("getChatId").getReturnType() != String.class) continue;
            } catch (NoSuchMethodException ignored) { continue; }
            if (found != null) throw new IllegalStateException("ambiguous bubble bind");
            found = m;
        }
        if (found == null) throw new NoSuchMethodException("bubble bind");
        return found;
    }
    /** 找到宿主气泡绑定入口；复用 View 时先清掉旧消息的描边，再绑定新消息。 */
    static void install(ClassLoader cl) throws Throwable {
        outline = findOutline(cl.loadClass("com.larksuite.component.ui.layout.LKUIRoundLinearLayout"));
        Class<?> block = cl.loadClass(BLOCK), holder = cl.loadClass(BLOCK + "$MessageGlobalViewHolder");
        final java.lang.reflect.Field bubble = holder.getField("mBubbleContentContainer");
        Method bind = findBind(block, holder);
        HookRuntime.hook(bind, "recall.bubble.bind", new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                View view = null;
                try {
                    view = (View) bubble.get(chain.getArgs().get(0));
                    unbind(view);
                } catch (Throwable ignored) {}
                Object result = chain.proceed();
                try {
                    Object model = chain.getArgs().get(1);
                    if (view != null && model != null) {
                        // 只为真实进入气泡的模型保存 Java 兜底原文，避免全局 hook
                        // MessageInfo.getMessage 在消息行反复取模型时执行反射。
                        RecallMessages.prepare(model);
                        bind(view, RecallMessages.account(),
                                RecallMessages.value(model, "getChatId"), RecallMessages.value(model, "getId"));
                    }
                } catch (Throwable ignored) {}
                return result;
            }
        });
        HotReloadSafety.markExternalCallback("recall.bubble.attach-layout-refresh");
        Diag.once("[fucklark] 撤回气泡: " + bind.getDeclaringClass().getName() + "." + bind.getName());
    }
    static void unbind(View view) {
        if (view == null) return;
        Binding previous = bindings.remove(view);
        if (previous == null) return;
        previous.remove(view);
        previous.dispose();
        view.removeOnLayoutChangeListener(previous);
    }
    /** 描边绑定账号、会话和消息 ID，避免切号或列表复用时标错消息。 */
    static void bind(View view, String uid, String chat, String id) {
        unbind(view);
        if (RecallMessages.key(uid, chat, id) == null) return;
        Binding binding = new Binding(view, uid, chat, id);
        bindings.put(view, binding);
        binding.watch();
        view.addOnLayoutChangeListener(binding);
        binding.refresh(view, uid);
        schedule();
    }
    static void schedule() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            if (handler == null) handler = new Handler(Looper.getMainLooper());
            handler.post(new Runnable() { @Override public void run() { schedule(); } });
            return;
        }
        long now = System.currentTimeMillis();
        if (now >= pollUntil) pollUntil = now + POLL_BURST_MS;
        if (scheduled) return;
        if (handler == null) handler = new Handler(Looper.getMainLooper());
        scheduled = true;
        handler.postDelayed(REFRESH, POLL_INTERVAL_MS);
    }
    static final Runnable REFRESH = new Runnable() {
        @Override public void run() {
            scheduled = false;
            String uid = RecallMessages.account();
            boolean active = RecallTranslationUi.refresh(uid);
            active |= System.currentTimeMillis() < pollUntil;
            for (Map.Entry<View, Binding> item : new ArrayList<Map.Entry<View, Binding>>(bindings.entrySet())) {
                View view = item.getKey();
                if (!RecallVisibility.visible(view)) continue;
                item.getValue().refresh(view, uid);
            }
            // 自动续轮询不调用 schedule()，避免每次续扫重置截止时间而变成无限扫描。
            if (active) {
                // 自动排队同样要占用调度标志，防止重绑/配置广播再开一条扫描链。
                scheduled = true;
                handler.postDelayed(REFRESH, POLL_INTERVAL_MS);
            }
        }
    };
    /** 只在当前账号的该条消息确实被标记时展示，列表回收时移除旧标记。 */
    static final class Binding extends RecallVisibility implements View.OnLayoutChangeListener {
        final String uid, chat, id;
        Border border;
        CharSequence description, markedDescription;
        int originalBottom;
        boolean hintMode;
        Binding(View view, String uid, String chat, String id) { super(view); this.uid = uid; this.chat = chat; this.id = id; }
        void refresh(View view, String activeUid) {
            boolean show = Config.antirecall && Config.showRecallHint && AntiRecall.TAMPER != 2
                    && uid.equals(activeUid) && RecallMessages.marked(uid, chat, id);
            if (!show) { remove(view); return; }
            if (border == null) {
                originalBottom = view.getPaddingBottom();
                border = new Border(view, view.getResources().getDisplayMetrics().density);
                view.getOverlay().add(border);
                description = view.getContentDescription();
                markedDescription = (description == null ? "" : description + "，") + "已撤回，原文已保留";
                view.setContentDescription(markedDescription);
            }
            // 提示模式给文案预留独立空间，避免覆盖正文；颜色模式恢复宿主布局。
            boolean nextHint = Config.recallDisplayMode == Config.RECALL_MODE_HINT;
            int bottom = originalBottom + (nextHint ? Math.round(22 * view.getResources().getDisplayMetrics().density) : 0);
            if (view.getPaddingBottom() != bottom)
                view.setPadding(view.getPaddingLeft(), view.getPaddingTop(), view.getPaddingRight(), bottom);
            hintMode = nextHint;
            border.hintMode = hintMode;
            border.setColor(Config.recallBorderColor);
            border.setBounds(0, 0, view.getWidth(), view.getHeight());
            border.invalidateSelf(); // grouping can change corner radii without changing size
        }
        void remove(View view) {
            if (border == null) return;
            view.getOverlay().remove(border);
            border = null;
            view.setPadding(view.getPaddingLeft(), view.getPaddingTop(), view.getPaddingRight(), originalBottom);
            if (markedDescription.equals(view.getContentDescription())) view.setContentDescription(description);
        }
        @Override public void onViewAttachedToWindow(View view) {
            super.onViewAttachedToWindow(view); refresh(view, RecallMessages.account());
        }
        @Override public void onViewDetachedFromWindow(View view) { super.onViewDetachedFromWindow(view); remove(view); }
        @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
            if (border != null) border.setBounds(0, 0, v.getWidth(), v.getHeight());
        }
    }
    /** 根据宿主真实气泡轮廓绘制可换色的虚线，不改正文和点击区域。 */
    static final class Border extends Drawable {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final float density;
        final Path path = new Path();
        final Matrix transform = new Matrix();
        final RectF source = new RectF(), target = new RectF();
        final java.lang.ref.WeakReference<View> host;
        Border(View view, float density) {
            host = new java.lang.ref.WeakReference<View>(view);
            this.density = density;
            setColor(Config.recallBorderColor);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.5f * density);
            paint.setPathEffect(new DashPathEffect(new float[]{4 * density, 3 * density}, 0));
        }
        void setColor(String color) {
            int value = android.graphics.Color.parseColor(Config.sanitizeRecallBorderColor(color));
            if (paint.getColor() != value) { paint.setColor(value); invalidateSelf(); }
        }
        boolean hintMode;
        final android.text.TextPaint hintPaint = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
        String cachedHint;
        int cachedWidth = -1;
        CharSequence fittedHint;
        @Override public void draw(Canvas canvas) {
            View view = host.get();
            if (hintMode && view != null) {
                String hint = Config.recallHintText;
                if (hint == null || hint.trim().isEmpty()) hint = "撤回了一条消息";
                // 此处没有可靠的发送人字段，移除占位符，不从通知存档猜发送人。
                hint = hint.replace("{name}", "").replace("{sender}", "").replace('\n', ' ');
                hintPaint.setColor(0xFF888888);
                hintPaint.setTextSize(11 * density);
                // 只在文案或宽度变化时省略文本，避免每一帧重复分配布局对象。
                if (!hint.equals(cachedHint) || cachedWidth != view.getWidth()) {
                    cachedHint = hint;
                    cachedWidth = view.getWidth();
                    fittedHint = android.text.TextUtils.ellipsize(hint, hintPaint,
                            Math.max(0, cachedWidth - 12 * density), android.text.TextUtils.TruncateAt.END);
                }
                canvas.drawText(fittedHint.toString(), 6 * density, view.getHeight() - 7 * density, hintPaint);
                return;
            }
            float inset = paint.getStrokeWidth() / 2;
            if (view == null || outline == null || getBounds().width() <= 2 * inset
                    || getBounds().height() <= 2 * inset) return;
            try {
                // 先取宿主实际使用的整数边界，再缩放私有 Path；小数边界会撞宿主缓存键。
                int width = view.getWidth(), height = view.getHeight();
                Path cached = (Path) outline.invoke(view, 0f, 0f, (float) width, (float) height);
                path.set(cached);
                source.set(0, 0, width, height);
                target.set(inset, inset, width - inset, height - inset);
                transform.setRectToRect(source, target, Matrix.ScaleToFit.FILL);
                path.transform(transform);
                canvas.drawPath(path, paint);
            } catch (Throwable ignored) { /* Unknown shape: do not invent a rectangle. */ }
        }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
