package net.gorry.mase;

import android.graphics.Canvas;
import android.graphics.Paint;

import java.util.Locale;

/**
 * 波形ビューの目盛り。0 を示す水平線と、時刻を示す垂直線（と時刻の表示）を描く。
 * 仕様は memo/doc/spec.md の「波形ビュー」の「目盛り」。
 */
final class TimeGrid {
    /** 1 秒以上の単位（秒）。これより細かい単位は、0.1 秒、0.01 秒…と、1/10 ずつ */
    private static final long[] WHOLE_UNITS = {3600, 600, 60, 10, 1};
    /** 1 秒より細かい単位の、最大の桁数（0.000001 秒まで） */
    private static final int MAX_DECIMALS = 6;

    private final Paint line = new Paint();
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

    // 描く垂直線。draw で計算して、drawLabels でも使う
    private int count;
    private float[] xs = new float[64];
    private int[] levels = new int[64];
    private String[] labels = new String[64];
    private long[] numbers = new long[64];

    TimeGrid() {
        line.setAntiAlias(false);
        line.setStrokeWidth(1f);
    }

    /** 単位の番号（0 が 1 時間、4 が 1 秒、5 が 0.1 秒…）から、秒数 */
    private static double unitSeconds(int unit) {
        return unit < WHOLE_UNITS.length ? WHOLE_UNITS[unit] : Math.pow(10, -(unit - WHOLE_UNITS.length + 1));
    }

    /** 粗い単位 coarse が、細かい単位 fine の何倍か（必ず整数になる） */
    private static long ratio(int coarse, int fine) {
        int whole = WHOLE_UNITS.length;
        if (fine < whole) {
            return WHOLE_UNITS[coarse] / WHOLE_UNITS[fine];
        }
        long scale = pow10(fine - whole + 1);
        return coarse < whole ? WHOLE_UNITS[coarse] * scale : pow10(fine - coarse);
    }

    private static long pow10(int n) {
        long v = 1;
        for (int i = 0; i < n; i++) {
            v *= 10;
        }
        return v;
    }

    /**
     * 0 を示す水平線と、時刻を示す垂直線を描く。波形より先に描く（波形の下になる）。
     *
     * @param minGap 垂直線の間の、最小の幅（px）。間がこれ以上になる単位まで描く
     */
    void draw(Canvas canvas, ViewColors colors, int width, int height, int sampleRate, int length,
              double scroll, double pxPerSample, float minGap) {
        count = 0;
        // いちばん細かい単位：垂直線の間が minGap 以上になる、いちばん細かい単位
        int finest = -1;
        for (int unit = 0; unit < WHOLE_UNITS.length + MAX_DECIMALS; unit++) {
            if (unitSeconds(unit) * sampleRate * pxPerSample < minGap) {
                break;
            }
            finest = unit;
        }
        if (finest >= 0) {
            collect(width, sampleRate, length, scroll, pxPerSample, finest);
        }
        for (int i = 0; i < count; i++) {
            line.setColor(color(colors, levels[i]));
            canvas.drawLine(xs[i] + 0.5f, 0, xs[i] + 0.5f, height, line);
        }
        // 0 を示す水平線は、「濃」で描く
        line.setColor(colors.gridDark);
        float center = (float) Math.floor(height / 2f) + 0.5f;
        canvas.drawLine(0, center, width, center, line);
    }

    /** 時刻の表示。線の右、いちばん下に描く。いちばん細かい単位の線には描かない。波形より後に描く（波形の上になる） */
    void drawLabels(Canvas canvas, ViewColors colors, int height, float textSize, float gap) {
        text.setTextSize(textSize);
        text.setColor(colors.gridDark); // 時刻の文字は、線の色に関わらず「濃」
        for (int i = 0; i < count; i++) {
            if (labels[i] != null) {
                canvas.drawText(labels[i], xs[i] + gap, height - gap, text);
            }
        }
    }

    /** 間が狭いほうから「薄・中・濃」。それより粗い単位は、すべて「濃」 */
    private static int color(ViewColors colors, int level) {
        return level == 0 ? colors.gridLight : level == 1 ? colors.gridMedium : colors.gridDark;
    }

    /** 表示の範囲に入る垂直線を集める */
    private void collect(int width, int sampleRate, int length, double scroll, double pxPerSample, int finest) {
        int whole = WHOLE_UNITS.length;
        int decimals = Math.max(0, finest - whole + 1); // いちばん細かい単位の、小数の桁数
        long divisor = pow10(decimals);
        double unitSamples = unitSeconds(finest) * sampleRate;
        long first = Math.max(0, (long) Math.floor(scroll / unitSamples));
        long last = (long) Math.ceil((scroll + width / pxPerSample) / unitSamples);
        for (long k = first; k <= last; k++) {
            // 指定の時刻と同じか、それを初めて越えたサンプル
            long sample = finest < whole
                    ? k * WHOLE_UNITS[finest] * sampleRate
                    : (k * sampleRate + divisor - 1) / divisor;
            if (sample > length) {
                break;
            }
            // そのサンプルの左端のピクセル
            float x = (float) Math.floor((sample - scroll) * pxPerSample);
            if (x < 0 || x >= width) {
                continue;
            }
            // この時刻にあたる、いちばん粗い単位（0 がいちばん細かい単位）
            int level = 0;
            for (int unit = 0; unit < finest; unit++) {
                if (k % ratio(unit, finest) == 0) {
                    level = finest - unit;
                    break;
                }
            }
            if (count == xs.length) {
                xs = java.util.Arrays.copyOf(xs, count * 2);
                levels = java.util.Arrays.copyOf(levels, count * 2);
                labels = java.util.Arrays.copyOf(labels, count * 2);
                numbers = java.util.Arrays.copyOf(numbers, count * 2);
            }
            xs[count] = x;
            levels[count] = level;
            labels[count] = level == 0 ? null : format(k, finest, divisor, decimals, decimals - 1);
            numbers[count] = k;
            count++;
        }
        // ビューの横の中央にいちばん近い線には、いちばん細かい単位の線でも、時刻を表示する。
        // 同じ距離の線が 2 本あるときは、左の線だけ（線は左から順に並んでいるので、先に見つけたほうを残す）
        int nearest = -1;
        float best = Float.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            float distance = Math.abs(xs[i] - width / 2f);
            if (distance < best) {
                best = distance;
                nearest = i;
            }
        }
        if (nearest >= 0 && labels[nearest] == null) {
            labels[nearest] = format(numbers[nearest], finest, divisor, decimals, decimals);
        }
    }

    /**
     * 時刻の表示。1 時間以上は「時:分:秒」、1 分以上は「分:秒」、それより短いときは秒だけ。
     *
     * @param digits 表示する小数の桁数。いちばん細かい単位の線は decimals、それより粗い単位の線は 1 つ少ない
     *               （いちばん細かい単位の桁が、必ず 0 になるため）
     */
    private static String format(long k, int finest, long divisor, int decimals, int digits) {
        long seconds;
        String fraction = "";
        if (finest < WHOLE_UNITS.length) {
            seconds = k * WHOLE_UNITS[finest];
        } else {
            seconds = k / divisor;
            if (digits > 0) {
                fraction = "." + String.format(Locale.US, "%0" + decimals + "d", k % divisor)
                        .substring(0, digits);
            }
        }
        long h = seconds / 3600;
        long m = seconds / 60 % 60;
        long s = seconds % 60;
        if (h > 0) {
            return String.format(Locale.US, "%d:%02d:%02d%s", h, m, s, fraction);
        }
        if (m > 0) {
            return String.format(Locale.US, "%d:%02d%s", m, s, fraction);
        }
        return s + fraction;
    }
}
