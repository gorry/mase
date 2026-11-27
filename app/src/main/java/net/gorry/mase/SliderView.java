package net.gorry.mase;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * 倍率バー。値は 0～1。横長なら左が 0、縦長なら下が 0。
 */
public final class SliderView extends View {
    interface Listener {
        void onValueChanged(float value);
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float value;
    private Listener listener;

    public SliderView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    float getValue() {
        return value;
    }

    void setValue(float value) {
        this.value = Math.max(0f, Math.min(1f, value));
        invalidate();
    }

    private boolean vertical() {
        return getHeight() > getWidth();
    }

    private float thumbRadius() {
        return Math.min(getWidth(), getHeight()) * 0.3f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        float r = thumbRadius();
        float alpha = isEnabled() ? 1f : 0.35f;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(r * 0.35f);
        paint.setColor(getContext().getColor(R.color.slider_track));
        paint.setAlpha((int) (255 * alpha));
        if (vertical()) {
            canvas.drawLine(w / 2f, r, w / 2f, h - r, paint);
        } else {
            canvas.drawLine(r, h / 2f, w - r, h / 2f, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(getContext().getColor(R.color.slider_thumb));
        paint.setAlpha((int) (255 * alpha));
        if (vertical()) {
            canvas.drawCircle(w / 2f, h - r - value * (h - 2 * r), r, paint);
        } else {
            canvas.drawCircle(r + value * (w - 2 * r), h / 2f, r, paint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) {
            return false;
        }
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
            getParent().requestDisallowInterceptTouchEvent(true);
            float r = thumbRadius();
            float v;
            if (vertical()) {
                float span = getHeight() - 2 * r;
                v = span <= 0 ? 0 : (getHeight() - r - event.getY()) / span;
            } else {
                float span = getWidth() - 2 * r;
                v = span <= 0 ? 0 : (event.getX() - r) / span;
            }
            v = Math.max(0f, Math.min(1f, v));
            if (v != value) {
                value = v;
                invalidate();
                if (listener != null) {
                    listener.onValueChanged(value);
                }
            }
            return true;
        }
        return action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL;
    }
}
