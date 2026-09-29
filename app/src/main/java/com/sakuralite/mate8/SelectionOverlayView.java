package com.sakuralite.mate8;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

final class SelectionOverlayView extends View {
    interface Listener {
        void onSelected(float left, float top, float right, float bottom);
        void onCancelled();
    }

    private final Paint dim = new Paint();
    private final Paint border = new Paint();
    private final Paint text = new Paint();
    private final Paint cancelBg = new Paint();

    private final RectF rect = new RectF();
    private final RectF cancelRect = new RectF();
    private final Listener listener;

    private boolean dragging;
    private float startX;
    private float startY;

    SelectionOverlayView(Context context,
                         float left, float top, float right, float bottom,
                         Listener listener) {
        super(context);
        this.listener = listener;

        dim.setColor(0x88000000);

        border.setColor(Color.WHITE);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(dp(3));

        text.setColor(Color.WHITE);
        text.setTextSize(dp(16));
        text.setAntiAlias(true);

        cancelBg.setColor(0xCC222222);

        rect.set(left, top, right, bottom);
        setBackgroundColor(Color.TRANSPARENT);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        cancelRect.set(w - dp(78), dp(18), w - dp(18), dp(60));
        if (rect.width() <= 1f || rect.height() <= 1f) {
            rect.set(0f, h * 0.45f, w, h);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        canvas.drawRect(0, 0, getWidth(), getHeight(), dim);

        if (rect.width() > 0 && rect.height() > 0) {
            canvas.save();
            canvas.clipRect(rect);
            canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR);
            canvas.restore();
            canvas.drawRect(rect, border);
        }

        canvas.drawRoundRect(cancelRect, dp(8), dp(8), cancelBg);
        canvas.drawText("取消", cancelRect.left + dp(14), cancelRect.centerY() + dp(6), text);
        canvas.drawText("拖动选择日文对话区域，松手保存",
                dp(18), dp(88), text);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();

        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            if (cancelRect.contains(x, y)) {
                if (listener != null) listener.onCancelled();
                return true;
            }
            dragging = true;
            startX = x;
            startY = y;
            rect.set(x, y, x, y);
            invalidate();
            return true;
        }

        if (event.getAction() == MotionEvent.ACTION_MOVE && dragging) {
            rect.set(
                    Math.min(startX, x),
                    Math.min(startY, y),
                    Math.max(startX, x),
                    Math.max(startY, y));
            invalidate();
            return true;
        }

        if (event.getAction() == MotionEvent.ACTION_UP && dragging) {
            dragging = false;
            rect.set(
                    Math.min(startX, x),
                    Math.min(startY, y),
                    Math.max(startX, x),
                    Math.max(startY, y));
            invalidate();

            if (rect.width() < dp(80) || rect.height() < dp(40)) {
                return true;
            }

            if (listener != null && getWidth() > 0 && getHeight() > 0) {
                listener.onSelected(
                        clamp(rect.left / getWidth()),
                        clamp(rect.top / getHeight()),
                        clamp(rect.right / getWidth()),
                        clamp(rect.bottom / getHeight()));
            }
            return true;
        }

        if (event.getAction() == MotionEvent.ACTION_CANCEL) {
            dragging = false;
            return true;
        }

        return true;
    }

    private float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
