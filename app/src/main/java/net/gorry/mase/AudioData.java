package net.gorry.mase;

/**
 * 内部の音声データ。16bit signed、モノラルかステレオ。チャンネルごとの配列で持つ。
 * 位置や長さは、1 チャンネルあたりのサンプル数で数える。
 */
final class AudioData {
    /** 長さの上限（秒）。この値「未満」まで */
    static final int MAX_SECONDS = 10000;
    /** 長さの上限（サンプル数）。この値「未満」まで */
    static final long MAX_SAMPLES = 1_000_000_000L;

    // 波形を描くための最小・最大の表。いちばん細かい段は PEAK_BASE サンプルごと、次の段はその PEAK_FACTOR 倍ごと
    private static final int PEAK_BASE = 64;
    private static final int PEAK_FACTOR = 8;

    final int channels;
    int sampleRate;
    /** 長さ（1 チャンネルあたりのサンプル数）。配列は、これより長いことがある */
    final int length;
    final short[][] samples;

    private int[] peakBlock;
    private short[][][] peakMin; // [チャンネル][段][ブロック]
    private short[][][] peakMax;

    // range() の結果
    int rangeMin;
    int rangeMax;

    AudioData(short[][] samples, int length, int sampleRate) {
        this(samples, length, sampleRate, null);
    }

    /**
     * @param progress 最小・最大の表を作る間の進み具合の知らせ先。なければ null。キャンセルされると Progress.Cancelled を投げる
     */
    AudioData(short[][] samples, int length, int sampleRate, Progress progress) {
        this.samples = samples;
        this.channels = samples.length;
        this.length = length;
        this.sampleRate = sampleRate;
        buildPeaks(progress);
    }

    /** このサンプリングレートで持てる、最大の長さ */
    static int maxLength(int sampleRate) {
        long bySeconds = (long) MAX_SECONDS * sampleRate - 1;
        return (int) Math.min(MAX_SAMPLES - 1, bySeconds);
    }

    double seconds() {
        return (double) length / sampleRate;
    }

    private void buildPeaks(Progress progress) {
        int levels = 0;
        for (long b = PEAK_BASE; b <= length; b *= PEAK_FACTOR) {
            levels++;
        }
        peakBlock = new int[levels];
        peakMin = new short[channels][levels][];
        peakMax = new short[channels][levels][];
        int block = PEAK_BASE;
        for (int lv = 0; lv < levels; lv++, block *= PEAK_FACTOR) {
            peakBlock[lv] = block;
            int count = length / block; // 端の半端は、表に入れない
            for (int c = 0; c < channels; c++) {
                short[] mn = new short[count];
                short[] mx = new short[count];
                if (lv == 0) {
                    short[] src = samples[c];
                    for (int i = 0; i < count; i++) {
                        if (progress != null && (i & 0xFFF) == 0) {
                            // いちばん細かい段が、時間のほとんどを占める
                            progress.step((long) c * count + i, (long) channels * count);
                        }
                        int lo = Short.MAX_VALUE;
                        int hi = Short.MIN_VALUE;
                        int base = i * block;
                        for (int j = 0; j < block; j++) {
                            int v = src[base + j];
                            if (v < lo) lo = v;
                            if (v > hi) hi = v;
                        }
                        mn[i] = (short) lo;
                        mx[i] = (short) hi;
                    }
                } else {
                    short[] pmn = peakMin[c][lv - 1];
                    short[] pmx = peakMax[c][lv - 1];
                    for (int i = 0; i < count; i++) {
                        int lo = Short.MAX_VALUE;
                        int hi = Short.MIN_VALUE;
                        int base = i * PEAK_FACTOR;
                        for (int j = 0; j < PEAK_FACTOR; j++) {
                            if (pmn[base + j] < lo) lo = pmn[base + j];
                            if (pmx[base + j] > hi) hi = pmx[base + j];
                        }
                        mn[i] = (short) lo;
                        mx[i] = (short) hi;
                    }
                }
                peakMin[c][lv] = mn;
                peakMax[c][lv] = mx;
            }
        }
    }

    /**
     * [start, end) の最小・最大を rangeMin・rangeMax に入れる。start &lt; end で、範囲はデータの中であること。
     */
    void range(int channel, int start, int end) {
        rangeMin = Short.MAX_VALUE;
        rangeMax = Short.MIN_VALUE;
        accumulate(channel, start, end, peakBlock.length - 1);
    }

    private void accumulate(int c, int start, int end, int level) {
        if (start >= end) {
            return;
        }
        while (level >= 0 && peakBlock[level] > end - start) {
            level--;
        }
        if (level < 0) {
            short[] src = samples[c];
            int lo = rangeMin;
            int hi = rangeMax;
            for (int i = start; i < end; i++) {
                int v = src[i];
                if (v < lo) lo = v;
                if (v > hi) hi = v;
            }
            rangeMin = lo;
            rangeMax = hi;
            return;
        }
        int block = peakBlock[level];
        int b0 = (start + block - 1) / block;
        int b1 = Math.min(end / block, peakMin[c][level].length);
        if (b0 >= b1) {
            accumulate(c, start, end, level - 1);
            return;
        }
        accumulate(c, start, b0 * block, level - 1);
        short[] mn = peakMin[c][level];
        short[] mx = peakMax[c][level];
        int lo = rangeMin;
        int hi = rangeMax;
        for (int i = b0; i < b1; i++) {
            if (mn[i] < lo) lo = mn[i];
            if (mx[i] > hi) hi = mx[i];
        }
        rangeMin = lo;
        rangeMax = hi;
        accumulate(c, b1 * block, end, level - 1);
    }

    /** [start, end) の写しを作る */
    short[][] copyRange(int start, int end) {
        short[][] out = new short[channels][end - start];
        for (int c = 0; c < channels; c++) {
            System.arraycopy(samples[c], start, out[c], 0, end - start);
        }
        return out;
    }

    /**
     * [pos, pos + removeLength) を segment に置き換えた音声データを作る。
     * 長さが変わらないときは、元の配列を書き換えて使い回す（元の AudioData は、もう使えない）。
     * チャンネル数を変えられるのは、全体を置き換えるときだけ。
     */
    static AudioData spliced(AudioData d, int pos, int removeLength, short[][] segment, int sampleRate,
                             Progress progress) {
        int n = segment[0].length;
        if (segment.length == d.channels && n == removeLength) {
            for (int c = 0; c < d.channels; c++) {
                System.arraycopy(segment[c], 0, d.samples[c], pos, n);
            }
            return new AudioData(d.samples, d.length, sampleRate, progress);
        }
        if (segment.length != d.channels) {
            if (pos != 0 || removeLength != d.length) {
                throw new IllegalArgumentException("channel count can change only when replacing everything");
            }
            return new AudioData(segment, n, sampleRate, progress);
        }
        int newLength = d.length - removeLength + n;
        short[][] out = new short[d.channels][newLength];
        for (int c = 0; c < d.channels; c++) {
            System.arraycopy(d.samples[c], 0, out[c], 0, pos);
            System.arraycopy(segment[c], 0, out[c], pos, n);
            System.arraycopy(d.samples[c], pos + removeLength, out[c], pos + n, d.length - pos - removeLength);
        }
        return new AudioData(out, newLength, sampleRate, progress);
    }

    /** サンプリングレートだけを変えた音声データを作る。サンプルの配列は使い回す */
    AudioData withSampleRate(int rate) {
        return new AudioData(samples, length, rate);
    }

    /**
     * 位置 pos の値。サンプル i は位置 i～i+1 を占め、その中央（i+0.5）に値があるとして、間を直線でつなぐ。
     */
    float valueAt(int channel, double pos) {
        double p = pos - 0.5;
        int i = (int) Math.floor(p);
        float t = (float) (p - i);
        short[] src = samples[channel];
        int i0 = Math.max(0, Math.min(length - 1, i));
        int i1 = Math.max(0, Math.min(length - 1, i + 1));
        return src[i0] + (src[i1] - src[i0]) * t;
    }
}
