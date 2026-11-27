package net.gorry.mase;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * 波形ビューの横のスクロールバー。全体の長さ・表示している長さ・表示の先頭を、同じ単位（サンプル）で持つ。
 */
public final class ScrollBarView extends View {
    interface Listener {
        void onScrolled(double position);
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private double content;
    private double viewport;
    private double position;
    private Listener listener;
    private float grabOffset;

    public ScrollBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    void setRange(double content, double viewport, double position) {
        this.content = content;
        this.viewport = viewport;
        this.position = position;
        invalidate();
    }

    private boolean scrollable() {
        return content > 0 && viewport < content;
    }

    private float thumbWidth() {
        float min = getHeight();
        return (float) Math.max(min, getWidth() * (viewport / content));
    }

    private float thumbLeft() {
        double range = content - viewport;
        float track = getWidth() - thumbWidth();
        return range <= 0 ? 0 : (float) (track * (position / range));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        float pad = h * 0.25f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(getContext().getColor(R.color.scroll_track));
        canvas.drawRect(0, pad, w, h - pad, paint);
        if (content <= 0) {
            return;
        }
        paint.setColor(getContext().getColor(scrollable() ? R.color.scroll_thumb : R.color.scroll_thumb_disabled));
        if (scrollable()) {
            float left = thumbLeft();
            canvas.drawRect(left, pad, left + thumbWidth(), h - pad, paint);
        } else {
            canvas.drawRect(0, pad, w, h - pad, paint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!scrollable()) {
            return false;
        }
        float x = event.getX();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                getParent().requestDisallowInterceptTouchEvent(true);
                float left = thumbLeft();
                float width = thumbWidth();
                // つまみの外を押したときは、つまみの中央をそこへ持っていく
                grabOffset = (x >= left && x <= left + width) ? x - left : width / 2f;
                moveTo(x);
                return true;
            }
            case MotionEvent.ACTION_MOVE:
                moveTo(x);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                return true;
            default:
                return false;
        }
    }

    private void moveTo(float x) {
        float track = getWidth() - thumbWidth();
        double range = content - viewport;
        double p = track <= 0 ? 0 : (x - grabOffset) / track * range;
        p = Math.max(0, Math.min(range, p));
        if (p != position) {
            position = p;
            invalidate();
            if (listener != null) {
                listener.onScrolled(position);
            }
        }
    }
}
