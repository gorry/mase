package net.gorry.mase;

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * 再生ビュー。常に音声データ全体と、開始位置・終了位置・再生位置を描く。
 * タップやドラッグで再生位置を動かす（シーク）。開始位置・終了位置のバーもドラッグできる。
 * 仕様は memo/doc/spec.md の「再生ビュー」。
 */
public final class PlayView extends View {
    private static final int GRAB_NONE = 0;
    private static final int GRAB_START = 1;
    private static final int GRAB_END = 2;
    private static final int GRAB_SEEK = 3;

    private final WaveRenderer renderer = new WaveRenderer();
    private Session session;
    private WaveformView.Host host;
    private float grabReach;
    private int grab = GRAB_NONE;

    public PlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    void bind(Session session, WaveformView.Host host) {
        this.session = session;
        this.host = host;
    }

    /** バーをつかめる範囲（左右それぞれ）。波形ビューの三角形の高さに合わせる */
    void setGrabReach(float reach) {
        grabReach = reach;
    }

    /** 描く所の幅。左右の余白（パディング）は含めない。余白は、何も描かないが、触るとバーをつかめる */
    private int contentWidth() {
        return Math.max(0, getWidth() - getPaddingLeft() - getPaddingRight());
    }

    private double pxPerSample() {
        int length = session == null ? 0 : session.length();
        return length <= 0 ? 1.0 : (double) contentWidth() / length;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = contentWidth();
        int h = getHeight();
        // 余白には描かない
        canvas.translate(getPaddingLeft(), 0);
        canvas.clipRect(0, 0, w, h);
        if (session == null || !session.hasData()) {
            canvas.drawColor(session == null ? 0xFF000000 : session.colors.background);
            return;
        }
        ViewColors colors = session.colors;
        double pps = pxPerSample();
        float startX = WaveRenderer.toX(session.start, 0, pps);
        float endX = WaveRenderer.toX(session.end, 0, pps);
        renderer.drawBackground(canvas, colors, w, h, true, startX, endX);
        renderer.drawWave(canvas, session.data, colors, w, h, 0, pps, 1.0);
        float tri = h / 4f; // 三角形の高さは、再生ビューの高さの 1/4
        renderer.drawBar(canvas, startX, w, h, tri, 1, colors.startBar);
        renderer.drawBar(canvas, endX, w, h, tri, -1, colors.endBar);
        renderer.drawBar(canvas, WaveRenderer.toX(session.playPosition, 0, pps), w, h, tri, 0,
                colors.playPosition);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (session == null || !session.hasData() || !isEnabled()) {
            return false;
        }
        double pps = pxPerSample();
        float x = event.getX() - getPaddingLeft(); // 余白を触ったときは、描く所の外の位置になる
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                getParent().requestDisallowInterceptTouchEvent(true);
                // 余白を触ったときは、描く所の端を触ったものとして調べる（余白のどこでも、端にあるバーをつかめる）
                float hit = Math.max(0f, Math.min(contentWidth(), x));
                boolean inPadding = hit != x;
                boolean nearPlay = Math.abs(hit - WaveRenderer.toX(session.playPosition, 0, pps)) <= grabReach / 2f;
                boolean nearStart = Math.abs(hit - WaveRenderer.toX(session.start, 0, pps)) <= grabReach;
                boolean nearEnd = Math.abs(hit - WaveRenderer.toX(session.end, 0, pps)) <= grabReach;
                // 再生位置を、開始位置・終了位置より先に調べる。つかめる範囲は、バーの半分（バーだけを広げたため）。
                // 余白を触ったときは、バーを先に調べる（余白は、端にあるバーをつかむための場所）
                if (nearPlay && !(inPadding && (nearStart || nearEnd))) {
                    grab = GRAB_SEEK;
                } else if (nearStart) {
                    grab = GRAB_START;
                } else if (nearEnd) {
                    grab = GRAB_END;
                } else {
                    grab = GRAB_SEEK;
                }
                move(x, pps);
                return true;
            }
            case MotionEvent.ACTION_MOVE:
                move(x, pps);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                grab = GRAB_NONE;
                return true;
            default:
                return false;
        }
    }

    private void move(float x, double pps) {
        long pos = Math.round(x / pps);
        switch (grab) {
            case GRAB_START:
                session.setStart(pos);
                break;
            case GRAB_END:
                session.setEnd(pos);
                break;
            case GRAB_SEEK:
                // シークできる範囲は、開始位置から終了位置の間
                session.setPlayPosition(pos);
                if (host != null) {
                    host.onSeek(session.playPosition);
                }
                break;
            default:
                return;
        }
        invalidate();
        if (host != null) {
            host.onPositionsChanged();
        }
    }
}
