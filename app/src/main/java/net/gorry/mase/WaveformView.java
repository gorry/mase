package net.gorry.mase;

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

/**
 * 波形ビュー。波形と、開始位置・終了位置のバー、再生位置を描く。バーはドラッグで動かせる。
 * 仕様は memo/doc/spec.md の「波形ビュー」「開始位置・終了位置のバー」。
 */
public final class WaveformView extends View {
    /** 横の倍率の最大。1 サンプルが 16px */
    static final double MAX_PX_PER_SAMPLE = 16.0;
    /** 縦の倍率の最大 */
    static final double MAX_VERTICAL_ZOOM = 256.0;

    interface Host {
        /** 開始位置・終了位置・再生位置が、ビューの操作で変わった */
        void onPositionsChanged();

        /** 再生ビューで、再生位置を動かした（シーク） */
        void onSeek(int position);

        /** 波形ビューの指 2 本の操作で、横の倍率や表示位置が変わった */
        void onViewChanged();
    }

    private static final int GRAB_NONE = 0;
    private static final int GRAB_START = 1;
    private static final int GRAB_END = 2;

    private final WaveRenderer renderer = new WaveRenderer();
    private final TimeGrid grid = new TimeGrid();
    private Session session;
    private Host host;
    private int grab = GRAB_NONE;
    // 指 2 本の操作（スライドで横にスクロール、ピンチで横の倍率）
    private boolean twoFingers;
    /** 指の数が変わった直後。次の動きでは、基準を取り直すだけにする */
    private boolean twoFingersFresh;
    private float lastMid;
    private float lastSpread;
    // 指 1 本の縦のスワイプ（上から下：開始位置を動かす。下から上：終了位置を動かす）
    private boolean swipeCandidate;
    private float downY;
    /**
     * 縦のスワイプの途中で、指を離したら開始位置・終了位置になる位置。なければ -1。
     * スワイプとみなせる長さになったら、バーをこの位置に表示する（実際の位置は、指を離すまで変えない）
     */
    private long previewStart = -1;
    private long previewEnd = -1;

    private final GestureDetector doubleTapDetector;
    // ダブルタップの 2 回目で、触れたまま横に動かす操作（横の倍率を変える）
    private boolean quickZoom;
    private boolean quickZoomMoved;
    private float quickDownX;
    private float quickDownY;
    /** 触れたときの、1 サンプルの幅 */
    private double quickPps;
    /** 触れた所にあった位置（サンプル）。倍率を変えても、ここが指の下に残るようにする */
    private double quickAnchor;

    public WaveformView(Context context, AttributeSet attrs) {
        super(context, attrs);
        doubleTapDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                // 2 回目のタップで触れた。離すまでの動きは onDoubleTapEvent に来る
                if (session == null || !session.hasData()) {
                    return false;
                }
                quickZoom = true;
                quickZoomMoved = false;
                quickDownX = e.getX();
                quickDownY = e.getY();
                quickPps = pxPerSample();
                quickAnchor = session.scroll + (e.getX() - getPaddingLeft()) / quickPps;
                return true;
            }

            @Override
            public boolean onDoubleTapEvent(MotionEvent e) {
                if (!quickZoom) {
                    return false;
                }
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_MOVE:
                        moveQuickZoom(e);
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!quickZoomMoved) {
                            fitSelection(); // 動かさずに離したら、ふつうのダブルタップ
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });
        doubleTapDetector.setIsLongpressEnabled(false);
    }

    /**
     * ダブルタップの 2 回目で触れたまま、指を動かした。横の動きで、横の倍率を変える（右で拡大、左で縮小）。
     * 縦の動きでは、何もしない。
     */
    private void moveQuickZoom(MotionEvent e) {
        float density = getResources().getDisplayMetrics().density;
        float dx = e.getX() - quickDownX;
        float dy = e.getY() - quickDownY;
        if (!quickZoomMoved) {
            // 少しでも動かしたら、ふつうのダブルタップ（選択範囲に合わせる）にはしない
            if (Math.hypot(dx, dy) < 8f * density) {
                return;
            }
            quickZoomMoved = true;
        }
        // 96dp 動かすごとに、倍率が 2 倍（左なら半分）になる
        setPxPerSample(quickPps * Math.pow(2.0, dx / (96f * density)));
        session.scroll = quickAnchor - (quickDownX - getPaddingLeft()) / pxPerSample();
        clampScroll();
        invalidate();
        if (host != null) {
            host.onViewChanged();
        }
    }

    /**
     * 選択範囲の全体が、ビューの横幅いっぱいに表示される横の倍率にする（ダブルタップ）。
     * 選択範囲が短くて、最大の倍率でも横幅に足りないときは、最大の倍率にして、選択範囲を中央に置く。
     */
    void fitSelection() {
        if (session == null || !session.hasData() || contentWidth() <= 0) {
            return;
        }
        int selection = session.end - session.start;
        setPxPerSample((double) contentWidth() / selection);
        session.scroll = session.start - (visibleSamples() - selection) / 2;
        clampScroll();
        invalidate();
        if (host != null) {
            host.onViewChanged();
        }
    }

    void bind(Session session, Host host) {
        this.session = session;
        this.host = host;
    }

    /** 描く所の幅。左右の余白（パディング）は含めない。余白は、何も描かないが、触るとバーをつかめる */
    private int contentWidth() {
        return Math.max(0, getWidth() - getPaddingLeft() - getPaddingRight());
    }

    /** 三角形の高さ。波形ビューの高さの 1/24 */
    float triangleHeight() {
        return getHeight() / 24f;
    }

    /** 1 サンプルの幅（px）。倍率バーが 0 のとき、音声データ全体がビューに収まる */
    double pxPerSample() {
        int length = session == null ? 0 : session.length();
        if (length <= 0 || contentWidth() <= 0) {
            return 1.0;
        }
        double fit = (double) contentWidth() / length;
        if (fit >= MAX_PX_PER_SAMPLE) {
            return fit; // 短い音声は、全体表示でも 16px を越える。拡大はしない
        }
        return fit * Math.pow(MAX_PX_PER_SAMPLE / fit, session.zoomH);
    }

    /** 1 サンプルの幅が pps になるように、横の倍率を決める（倍率バーの範囲に収める） */
    private void setPxPerSample(double pps) {
        int length = session.length();
        int width = contentWidth();
        if (length <= 0 || width <= 0) {
            return;
        }
        double fit = (double) width / length;
        if (fit >= MAX_PX_PER_SAMPLE) {
            return; // 短い音声は、拡大しない
        }
        double zoom = Math.log(pps / fit) / Math.log(MAX_PX_PER_SAMPLE / fit);
        session.zoomH = (float) Math.max(0.0, Math.min(1.0, zoom));
    }

    double verticalZoom() {
        return Math.pow(MAX_VERTICAL_ZOOM, session == null ? 0 : session.zoomV);
    }

    /** ビューに表示しているサンプル数 */
    double visibleSamples() {
        return contentWidth() / pxPerSample();
    }

    /** scroll を、表示できる範囲に収める */
    void clampScroll() {
        if (session == null) {
            return;
        }
        double max = Math.max(0, session.length() - visibleSamples());
        session.scroll = Math.max(0, Math.min(max, session.scroll));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        clampScroll();
        if (host != null) {
            host.onPositionsChanged();
        }
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
        double scroll = session.scroll;
        // 縦のスワイプの途中は、バーと選択範囲を、指を離したらなる位置で描く
        float startX = WaveRenderer.toX(previewStart >= 0 ? previewStart : session.start, scroll, pps);
        float endX = WaveRenderer.toX(previewEnd >= 0 ? previewEnd : session.end, scroll, pps);
        renderer.drawBackground(canvas, colors, w, h, true, startX, endX);
        // 目盛り：0 を示す水平線と、時刻を示す垂直線。線は波形の下、時刻の表示は波形の上に描く
        float density = getResources().getDisplayMetrics().density;
        grid.draw(canvas, colors, w, h, session.data.sampleRate, session.length(), scroll, pps, 25f * density);
        // L・R のスイッチで片方だけがオンのときは、そのチャンネルだけを描く
        int only = session.leftOn && session.rightOn ? -1 : (session.leftOn ? 0 : 1);
        renderer.drawWave(canvas, session.data, colors, w, h, scroll, pps, verticalZoom(), only);
        grid.drawLabels(canvas, colors, h, 10f * getResources().getDisplayMetrics().scaledDensity, 2f * density);
        float tri = triangleHeight();
        renderer.drawBar(canvas, WaveRenderer.toX(session.playPosition, scroll, pps), w, h, tri, 0,
                colors.playPosition);
        renderer.drawBar(canvas, startX, w, h, tri, 1, colors.startBar);
        renderer.drawBar(canvas, endX, w, h, tri, -1, colors.endBar);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (session == null || !session.hasData()) {
            return false;
        }
        doubleTapDetector.onTouchEvent(event);
        if (quickZoom) {
            // ダブルタップの 2 回目の指。バーのドラッグや縦のスワイプにはしない
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                quickZoom = false;
            }
            getParent().requestDisallowInterceptTouchEvent(true);
            return true;
        }
        double pps = pxPerSample();
        float x = event.getX() - getPaddingLeft(); // 余白を触ったときは、描く所の外の位置になる
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_POINTER_DOWN:
                // 2 本目の指が触れたら、バーのドラッグはやめて、指 2 本の操作にする
                grab = GRAB_NONE;
                swipeCandidate = false;
                clearPreview();
                twoFingers = true;
                twoFingersFresh = true;
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                // 指が 1 本になったら、指 2 本の操作を終える。残った指では、何もしない
                if (event.getPointerCount() <= 2) {
                    twoFingers = false;
                }
                twoFingersFresh = true;
                return true;
            case MotionEvent.ACTION_DOWN: {
                twoFingers = false;
                swipeCandidate = false;
                downY = event.getY();
                // つかめる範囲は、横が三角形の高さと同じくらい、縦はバーいっぱい
                float reach = triangleHeight(); // バーの左右それぞれに、三角形の高さの分
                // 余白を触ったときは、描く所の端を触ったものとして調べる（余白のどこでも、端にあるバーをつかめる）
                float hit = Math.max(0f, Math.min(contentWidth(), x));
                float ds = Math.abs(hit - WaveRenderer.toX(session.start, session.scroll, pps));
                float de = Math.abs(hit - WaveRenderer.toX(session.end, session.scroll, pps));
                if (ds <= reach && ds <= de) {
                    grab = GRAB_START;
                } else if (de <= reach) {
                    grab = GRAB_END;
                } else {
                    grab = GRAB_NONE; // バーでない所。縦のスワイプか、2 本目の指が触れるのを待つ
                    swipeCandidate = true;
                }
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (twoFingers && event.getPointerCount() >= 2) {
                    moveTwoFingers(event, pps);
                    return true;
                }
                if (grab == GRAB_NONE) {
                    if (swipeCandidate) {
                        previewSwipe(x, event.getY() - downY, pps);
                    }
                    return true;
                }
                long pos = Math.round(session.scroll + x / pps);
                if (grab == GRAB_START) {
                    session.setStart(pos);
                } else {
                    session.setEnd(pos);
                }
                invalidate();
                if (host != null) {
                    host.onPositionsChanged();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
                if (swipeCandidate) {
                    finishSwipe(x, event.getY() - downY, pps);
                }
                // fall through
            case MotionEvent.ACTION_CANCEL:
                grab = GRAB_NONE;
                twoFingers = false;
                swipeCandidate = false;
                clearPreview();
                return true;
            default:
                return false;
        }
    }

    /**
     * 指 2 本の操作。2 本の指の中点が動いた分だけ横にスクロールし、指の間隔が変わった比で横の倍率を変える。
     * 中点の下にある音声の位置が、指に付いて動く。
     */
    private void moveTwoFingers(MotionEvent event, double pps) {
        float x0 = event.getX(0) - getPaddingLeft();
        float x1 = event.getX(1) - getPaddingLeft();
        float mid = (x0 + x1) / 2f;
        float spread = (float) Math.hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1));
        if (twoFingersFresh) {
            twoFingersFresh = false;
            lastMid = mid;
            lastSpread = spread;
            return;
        }
        // 前の中点の下にあった位置（サンプル）
        double anchor = session.scroll + lastMid / pps;
        float min = 16f * getResources().getDisplayMetrics().density; // 指が近すぎるときは、倍率を変えない
        if (spread > min && lastSpread > min) {
            setPxPerSample(pps * spread / lastSpread);
        }
        session.scroll = anchor - mid / pxPerSample();
        clampScroll();
        lastMid = mid;
        lastSpread = spread;
        invalidate();
        if (host != null) {
            host.onViewChanged();
        }
    }

    /**
     * 指 1 本の縦のスワイプが終わった。上から下なら開始位置を、下から上なら終了位置を、指を離した所へ動かす。
     * 開始位置が終了位置より左、という並びを保てない所へは、動かさない。
     *
     * @param x  指を離した所（描く所の中での位置）
     * @param dy 触れた所から離した所までの、縦の動き（下向きが正）
     */
    private void finishSwipe(float x, float dy, double pps) {
        clearPreview();
        long pos = swipeTarget(x, dy, pps);
        if (pos < 0) {
            return;
        }
        if (dy > 0) {
            session.setStart(pos);
        } else {
            session.setEnd(pos);
        }
        invalidate();
        if (host != null) {
            host.onPositionsChanged();
        }
    }

    /** 縦のスワイプの途中。スワイプとみなせる長さになっていたら、バーを、指を離したらなる位置に表示する */
    private void previewSwipe(float x, float dy, double pps) {
        long pos = swipeTarget(x, dy, pps);
        long start = pos >= 0 && dy > 0 ? pos : -1;
        long end = pos >= 0 && dy < 0 ? pos : -1;
        if (start != previewStart || end != previewEnd) {
            previewStart = start;
            previewEnd = end;
            invalidate();
        }
    }

    private void clearPreview() {
        if (previewStart >= 0 || previewEnd >= 0) {
            previewStart = -1;
            previewEnd = -1;
            invalidate();
        }
    }

    /**
     * 縦のスワイプで、開始位置（dy が正）・終了位置（dy が負）にする位置。
     * スワイプとみなせない短い動きのときと、並びを保てない位置のときは -1。
     */
    private long swipeTarget(float x, float dy, double pps) {
        float min = 48f * getResources().getDisplayMetrics().density; // これより短い動きは、スワイプとみなさない
        if (Math.abs(dy) < min) {
            return -1;
        }
        float inside = Math.max(0f, Math.min(contentWidth(), x));
        long pos = Math.max(0, Math.min(session.length(), Math.round(session.scroll + inside / pps)));
        if (dy > 0) {
            return pos >= session.end ? -1 : pos; // 終了位置より右（同じ位置も含む）には、開始位置を置かない
        }
        return pos <= session.start ? -1 : pos;   // 開始位置より左（同じ位置も含む）には、終了位置を置かない
    }
}
