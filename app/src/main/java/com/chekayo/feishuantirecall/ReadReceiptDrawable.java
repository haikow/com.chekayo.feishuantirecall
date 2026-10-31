package com.chekayo.feishuantirecall;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;

/** 24dp 信封与对勾，独立于宿主资源 ID，适用于设置和滑动按钮。 */
final class ReadReceiptDrawable extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    ReadReceiptDrawable(int color, float density) {
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.8f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        size = Math.max(1, Math.round(24 * density));
    }
    private final int size;
    @Override public int getIntrinsicWidth() { return size; }
    @Override public int getIntrinsicHeight() { return size; }
    @Override public void draw(Canvas canvas) {
        int saved = canvas.save();
        canvas.translate(getBounds().left, getBounds().top);
        canvas.scale(getBounds().width() / 24f, getBounds().height() / 24f);
        Path envelope = new Path();
        envelope.moveTo(12, 18); envelope.lineTo(4, 18);
        envelope.quadTo(2.5f, 18, 2.5f, 16.5f); envelope.lineTo(2.5f, 6.5f);
        envelope.quadTo(2.5f, 5, 4, 5); envelope.lineTo(20, 5);
        envelope.quadTo(21.5f, 5, 21.5f, 6.5f); envelope.lineTo(21.5f, 11);
        canvas.drawPath(envelope, paint);
        Path flap = new Path();
        flap.moveTo(3, 6); flap.lineTo(11.1f, 12);
        flap.quadTo(12, 12.7f, 12.9f, 12); flap.lineTo(21, 6);
        canvas.drawPath(flap, paint);
        Path check = new Path();
        check.moveTo(14, 17); check.lineTo(17, 20); check.lineTo(22, 14);
        canvas.drawPath(check, paint);
        canvas.restoreToCount(saved);
    }
    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
