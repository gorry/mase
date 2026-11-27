package net.gorry.mase;

/**
 * 無音区間・発音区間を探す。
 * 無音：指定の期間の振幅（最大値と最小値の差）が、指定値以下である状態。
 * 仕様は memo/doc/spec.md の「無音区間を選択・発音区間を選択」。
 */
final class SilenceFinder {
    private final short[][] samples;
    private final int length;
    /** 期間の長さ（サンプル数）。音声データより長いときは、音声データの長さ */
    private final int window;
    private final int amplitude;

    /**
     * @param window    期間の長さ（サンプル数）
     * @param amplitude 振幅の指定値。期間の中の最大値と最小値の差がこれ以下なら、無音とする
     */
    SilenceFinder(short[][] samples, int length, int window, int amplitude) {
        this.samples = samples;
        this.length = length;
        this.window = Math.max(1, Math.min(length, window));
        this.amplitude = amplitude;
    }

    /** 期間の長さ（ミリ秒）を、サンプル数にする */
    static int windowSamples(int sampleRate, int milliseconds) {
        return Math.max(1, (int) ((long) sampleRate * milliseconds / 1000));
    }

    /**
     * pos を含む無音区間 [from, to) を返す。pos が無音区間の中にないときは null。
     * 無音区間は、無音の期間（振幅が指定値以下の期間）が、重なったり隣り合ったりして続いている範囲。
     */
    int[] silenceAround(int pos, Progress p) {
        if (pos < 0 || pos >= length) {
            return null;
        }
        // pos を含む期間（はじまりが pos - window + 1 ～ pos）のうち、無音のものを探す
        int lo = Math.max(0, pos - window + 1);
        int hi = Math.min(pos, length - window);
        int first = -1;
        int last = -1;
        Slider slider = new Slider(false, lo);
        for (int s = lo; s <= hi; s++) {
            if (slider.silent()) {
                if (first < 0) {
                    first = s;
                }
                last = s;
            }
            if (s < hi) {
                slider.advance();
            }
        }
        if (first < 0) {
            return null;
        }
        // 右へ・左へ、無音の期間が続くかぎり広げる
        int lastWindow = chain(false, last, p);
        int firstWindow = length - window - chain(true, length - window - first, p);
        // 区間の両端は、いちばん端の無音の期間の中で、0 にいちばん近い位置にする
        int from = quietest(firstWindow, false);
        int to = quietest(lastWindow, true);
        if (to <= from) {
            // 無音が短くて、両端が同じ期間（または、入れ違い）になるときは、無音の期間の端そのままにする
            return new int[]{firstWindow, lastWindow + window};
        }
        return new int[]{from, to};
    }

    /**
     * start から始まる期間の中で、区間の端にする位置を選ぶ。
     * 0 にいちばん近い位置。同じものがいくつもあるときは、前後のサンプルとの差がいちばん小さい位置。
     * それも同じときは、無音が始まる側の端なら先頭に近い位置、無音が終わる側の端なら末尾に近い位置。
     * ステレオのときは、L と R の値を足して比べる。
     *
     * @param silenceEnds 無音が終わる側の端（無音区間の右端、発音区間の左端）なら true
     */
    int quietest(int start, boolean silenceEnds) {
        int best = start;
        long bestLevel = Long.MAX_VALUE;
        long bestDiff = Long.MAX_VALUE;
        for (int i = start; i < start + window && i < length; i++) {
            long level = 0;
            long diff = 0;
            for (short[] ch : samples) {
                level += Math.abs(ch[i]);
                if (i > 0) {
                    diff += Math.abs(ch[i] - ch[i - 1]);
                }
                if (i < length - 1) {
                    diff += Math.abs(ch[i + 1] - ch[i]);
                }
            }
            if (level < bestLevel || (level == bestLevel && diff < bestDiff)
                    || (silenceEnds && level == bestLevel && diff == bestDiff)) {
                best = i;
                bestLevel = level;
                bestDiff = diff;
            }
        }
        return best;
    }

    /**
     * pos を含む発音区間 [from, to) を返す。pos が無音区間の中にあるときは null。
     * 発音区間は、pos の左右でいちばん近い無音の期間にはさまれた範囲。
     * 両端は、その無音の期間の中で、0 にいちばん近い位置（quietest）。
     * 無音の期間が見つからない側は、音声データの端までとする。
     */
    int[] soundAround(int pos, Progress p) {
        if (pos < 0 || pos >= length || silenceAround(pos, p) != null) {
            return null;
        }
        // 右へ：pos より後ろで始まる、最初の無音の期間
        // 区間の端は、その無音の期間の中で、0 にいちばん近い位置にする
        int right = firstSilent(false, pos + 1, p);
        int to = right < 0 ? length : quietest(right, false);
        // 左へ：pos より前で終わる、いちばん近い無音の期間
        int left = firstSilent(true, length - pos, p);
        int from = left < 0 ? 0 : quietest(length - window - left, true);
        return new int[]{from, Math.max(to, from + 1)};
    }

    /** start から始まる無音の期間から、無音の期間が続いているかぎり進んで、最後の無音の期間のはじまりを返す */
    private int chain(boolean mirrored, int start, Progress p) {
        int last = start;
        int max = length - window;
        if (start >= max) {
            return last;
        }
        Slider slider = new Slider(mirrored, start + 1);
        for (int s = start + 1; s <= max; s++) {
            step(p, s - start);
            if (slider.silent()) {
                last = s;
            } else if (s - last > window) {
                break; // 無音の期間が、前の無音の期間と重なりも隣り合いもしなくなった
            }
            if (s < max) {
                slider.advance();
            }
        }
        return last;
    }

    /** from 以降で始まる、最初の無音の期間のはじまりを返す。なければ -1 */
    private int firstSilent(boolean mirrored, int from, Progress p) {
        int max = length - window;
        if (from > max) {
            return -1;
        }
        Slider slider = new Slider(mirrored, from);
        for (int s = from; s <= max; s++) {
            step(p, s - from);
            if (slider.silent()) {
                return s;
            }
            if (s < max) {
                slider.advance();
            }
        }
        return -1;
    }

    private void step(Progress p, int done) {
        if ((done & 0xFFFF) == 0) {
            p.step(done, length);
        }
    }

    /**
     * 期間を 1 サンプルずつずらしながら、その中の最大値と最小値を求める。
     * mirrored のときは、音声データを後ろから前へ見る（位置 j が、元の length - 1 - j にあたる）。
     */
    private final class Slider {
        private final boolean mirrored;
        private final int size = window + 1;
        // チャンネルごとに、最大値・最小値の候補の位置を、古い順に持つ（輪にした配列）
        private final int[][] maxQueue = new int[samples.length][size];
        private final int[][] minQueue = new int[samples.length][size];
        private final int[] maxHead = new int[samples.length];
        private final int[] maxCount = new int[samples.length];
        private final int[] minHead = new int[samples.length];
        private final int[] minCount = new int[samples.length];
        private int start;

        Slider(boolean mirrored, int start) {
            this.mirrored = mirrored;
            this.start = start;
            for (int j = start; j < start + window; j++) {
                add(j);
            }
        }

        private int value(int c, int j) {
            return samples[c][mirrored ? length - 1 - j : j];
        }

        private void add(int j) {
            for (int c = 0; c < samples.length; c++) {
                int v = value(c, j);
                int[] q = maxQueue[c];
                while (maxCount[c] > 0 && value(c, q[(maxHead[c] + maxCount[c] - 1) % size]) <= v) {
                    maxCount[c]--;
                }
                q[(maxHead[c] + maxCount[c]) % size] = j;
                maxCount[c]++;
                q = minQueue[c];
                while (minCount[c] > 0 && value(c, q[(minHead[c] + minCount[c] - 1) % size]) >= v) {
                    minCount[c]--;
                }
                q[(minHead[c] + minCount[c]) % size] = j;
                minCount[c]++;
            }
        }

        /** 今の期間が無音か。すべてのチャンネルで、最大値と最小値の差が指定値以下であること */
        boolean silent() {
            for (int c = 0; c < samples.length; c++) {
                int max = value(c, maxQueue[c][maxHead[c]]);
                int min = value(c, minQueue[c][minHead[c]]);
                if (max - min > amplitude) {
                    return false;
                }
            }
            return true;
        }

        /** 期間を、1 サンプル後ろへずらす */
        void advance() {
            for (int c = 0; c < samples.length; c++) {
                if (maxQueue[c][maxHead[c]] == start) {
                    maxHead[c] = (maxHead[c] + 1) % size;
                    maxCount[c]--;
                }
                if (minQueue[c][minHead[c]] == start) {
                    minHead[c] = (minHead[c] + 1) % size;
                    minCount[c]--;
                }
            }
            add(start + window);
            start++;
        }
    }
}
