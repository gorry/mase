package net.gorry.mase;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

/**
 * 波形・選択範囲・バーを描く。波形ビューと再生ビューで共通に使う。
 * 横の位置は、サンプルの位置 p を x = (p - scroll) * pxPerSample で決める。
 */
final class WaveRenderer {
    private final Paint paint = new Paint();
    private final Path path = new Path();

    // 色ごとの縦線（x0, y0, x1, y1 の並び）
    private float[] monoLines = new float[0];
    private float[] leftLines = new float[0];
    private float[] rightLines = new float[0];
    private float[] overlapLines = new float[0];
    private int monoCount;
    private int leftCount;
    private int rightCount;
    private int overlapCount;

    WaveRenderer() {
        paint.setAntiAlias(false);
        paint.setStrokeWidth(1f);
    }

    static float toX(double pos, double scroll, double pxPerSample) {
        return (float) ((pos - scroll) * pxPerSample);
    }

    /** 背景と選択範囲 */
    void drawBackground(Canvas canvas, ViewColors colors, int width, int height,
                        boolean hasSelection, float startX, float endX) {
        canvas.drawColor(colors.background);
        if (hasSelection) {
            float x0 = Math.max(0, startX);
            float x1 = Math.min(width, endX);
            if (x1 > x0) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(colors.selection);
                canvas.drawRect(x0, 0, x1, height, paint);
            }
        }
    }

    /** 波形。縦は 0 を中央にして、verticalZoom 倍に拡大する。はみ出した分は、ビューの端で切れる */
    void drawWave(Canvas canvas, AudioData data, ViewColors colors, int width, int height,
                  double scroll, double pxPerSample, double verticalZoom) {
        drawWave(canvas, data, colors, width, height, scroll, pxPerSample, verticalZoom, -1);
    }

    /**
     * @param onlyChannel ステレオのときに、このチャンネル（0：L、1：R）だけを描く。-1 なら両方を重ねて描く。
     *                    片方だけのときの色は、「L だけの部分」「R だけの部分」の色。モノラルのときは、関係しない
     */
    void drawWave(Canvas canvas, AudioData data, ViewColors colors, int width, int height,
                  double scroll, double pxPerSample, double verticalZoom, int onlyChannel) {
        if (data == null || width <= 0 || height <= 0 || pxPerSample <= 0) {
            return;
        }
        ensure(width);
        monoCount = leftCount = rightCount = overlapCount = 0;
        // 片方だけを描くときは、そのチャンネルを、モノラルと同じ形で読む（色は L・R の色）
        int single = data.channels >= 2 && onlyChannel >= 0 ? onlyChannel : -1;
        int first = single >= 0 ? single : 0;
        boolean stereo = data.channels >= 2 && single < 0;
        float center = height / 2f;
        float scale = (float) (center / 32768.0 * verticalZoom);
        int length = data.length;

        for (int x = 0; x < width; x++) {
            double p0 = scroll + x / pxPerSample;
            double p1 = scroll + (x + 1) / pxPerSample;
            if (p1 <= 0 || p0 >= length) {
                continue;
            }
            float lTop;
            float lBottom;
            float rTop = 0;
            float rBottom = 0;
            if (pxPerSample < 1) {
                int s = Math.max(0, (int) Math.floor(p0));
                int e = Math.min(length, (int) Math.ceil(p1));
                if (e <= s) {
                    e = s + 1;
                }
                data.range(first, s, e);
                lTop = center - data.rangeMax * scale;
                lBottom = center - data.rangeMin * scale;
                if (stereo) {
                    data.range(1, s, e);
                    rTop = center - data.rangeMax * scale;
                    rBottom = center - data.rangeMin * scale;
                }
            } else {
                double a = Math.max(0, p0);
                double b = Math.min(length, p1);
                float lo = spanLow(data, first, a, b);
                float hi = spanHigh;
                lTop = center - hi * scale;
                lBottom = center - lo * scale;
                if (stereo) {
                    lo = spanLow(data, 1, a, b);
                    hi = spanHigh;
                    rTop = center - hi * scale;
                    rBottom = center - lo * scale;
                }
            }
            if (lBottom - lTop < 1) {
                lBottom = lTop + 1;
            }
            float cx = x + 0.5f;
            if (!stereo) {
                if (single == 0) {
                    leftCount = add(leftLines, leftCount, cx, lTop, lBottom);
                } else if (single == 1) {
                    rightCount = add(rightLines, rightCount, cx, lTop, lBottom);
                } else {
                    monoCount = add(monoLines, monoCount, cx, lTop, lBottom);
                }
                continue;
            }
            if (rBottom - rTop < 1) {
                rBottom = rTop + 1;
            }
            float oTop = Math.max(lTop, rTop);
            float oBottom = Math.min(lBottom, rBottom);
            if (oBottom <= oTop) {
                // 重なっていない
                leftCount = add(leftLines, leftCount, cx, lTop, lBottom);
                rightCount = add(rightLines, rightCount, cx, rTop, rBottom);
                continue;
            }
            overlapCount = add(overlapLines, overlapCount, cx, oTop, oBottom);
            if (lTop < oTop) {
                leftCount = add(leftLines, leftCount, cx, lTop, oTop);
            }
            if (lBottom > oBottom) {
                leftCount = add(leftLines, leftCount, cx, oBottom, lBottom);
            }
            if (rTop < oTop) {
                rightCount = add(rightLines, rightCount, cx, rTop, oTop);
            }
            if (rBottom > oBottom) {
                rightCount = add(rightLines, rightCount, cx, oBottom, rBottom);
            }
        }

        paint.setStyle(Paint.Style.STROKE);
        flush(canvas, monoLines, monoCount, colors.mono);
        flush(canvas, leftLines, leftCount, colors.left);
        flush(canvas, rightLines, rightCount, colors.right);
        flush(canvas, overlapLines, overlapCount, colors.overlap);
    }

    private float spanHigh;

    /** [a, b] の間の値の最小を返し、最大を spanHigh に入れる（1 サンプルが 1px 以上のとき） */
    private float spanLow(AudioData data, int channel, double a, double b) {
        float v0 = data.valueAt(channel, a);
        float v1 = data.valueAt(channel, b);
        float lo = Math.min(v0, v1);
        float hi = Math.max(v0, v1);
        // 間にあるサンプルの中央（折れ線の頂点）も含める
        double k = Math.ceil(a - 0.5) + 0.5;
        while (k < b) {
            float v = data.valueAt(channel, k);
            if (v < lo) lo = v;
            if (v > hi) hi = v;
            k += 1.0;
        }
        spanHigh = hi;
        return lo;
    }

    /**
     * 開始位置・終了位置のバー。縦線と、中央の三角形。
     *
     * @param direction 1 なら右向きの三角形（開始位置）、-1 なら左向き（終了位置）、0 なら縦線だけ（再生位置）
     */
    void drawBar(Canvas canvas, float x, int width, int height, float triangleHeight, int direction, int color) {
        if (x < -triangleHeight || x > width + triangleHeight) {
            return; // 表示範囲の外にあるバーは、表示しない
        }
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        float lineX = Math.max(0.5f, Math.min(width - 0.5f, (float) Math.floor(x) + 0.5f));
        if (x < 0 || x > width) {
            return;
        }
        canvas.drawLine(lineX, 0, lineX, height, paint);
        if (direction != 0) {
            float cy = height / 2f;
            float half = triangleHeight / 2f;
            float depth = triangleHeight * 0.866f;
            path.rewind();
            path.moveTo(lineX, cy - half);
            path.lineTo(lineX, cy + half);
            path.lineTo(lineX + depth * direction, cy);
            path.close();
            paint.setStyle(Paint.Style.FILL);
            canvas.drawPath(path, paint);
        }
    }

    private void ensure(int width) {
        int need = width * 8; // 1 列に最大 2 本
        if (monoLines.length < need) {
            monoLines = new float[need];
            leftLines = new float[need];
            rightLines = new float[need];
            overlapLines = new float[need];
        }
    }

    private static int add(float[] lines, int count, float x, float top, float bottom) {
        lines[count] = x;
        lines[count + 1] = top;
        lines[count + 2] = x;
        lines[count + 3] = bottom;
        return count + 4;
    }

    private void flush(Canvas canvas, float[] lines, int count, int color) {
        if (count > 0) {
            paint.setColor(color);
            canvas.drawLines(lines, 0, count, paint);
        }
    }
}
