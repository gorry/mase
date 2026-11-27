package net.gorry.mase;

/**
 * サンプリングレート変換と、タイムストレッチ（WSOLA）。
 * 仕様は memo/doc/spec.md の「音程の変更・サンプリングレートの変更・再生レートの変更」。
 */
final class Resampler {
    static final int NEAREST = 0;
    static final int LINEAR = 1;
    static final int CUBIC = 2;
    static final int SINC = 3;
    static final int SINC_HQ = 4;
    /** 最初の選択 */
    static final int DEFAULT = SINC;

    /** sinc の表の細かさ（表の 1 単位あたりの点の数） */
    private static final int TABLE_RES = 512;

    private Resampler() {
    }

    /** 変換の後の長さ。step は、出力の 1 サンプルが入力の何サンプルにあたるか */
    static long outputLength(int inLength, double step) {
        return Math.max(1L, Math.round(inLength / step));
    }

    /**
     * すべてのチャンネルを変換する。出力の j 番目は、入力の j × step の位置の値。
     * 進み具合は、p の今の段階の中で知らせる。
     */
    static short[][] resample(short[][] in, double step, int outLength, int algorithm, Progress p) {
        short[][] out = new short[in.length][];
        long total = (long) outLength * in.length;
        for (int c = 0; c < in.length; c++) {
            out[c] = resample(in[c], step, outLength, algorithm, p, (long) outLength * c, total);
        }
        return out;
    }

    private static short[] resample(short[] in, double step, int outLength, int algorithm,
                                    Progress p, long done, long total) {
        short[] out = new short[outLength];
        int last = in.length - 1;
        if (algorithm == SINC || algorithm == SINC_HQ) {
            sinc(in, step, out, algorithm == SINC_HQ, p, done, total);
            return out;
        }
        for (int j = 0; j < outLength; j++) {
            if ((j & 0xFFFF) == 0) {
                p.step(done + j, total);
            }
            double x = j * step;
            if (algorithm == NEAREST) {
                out[j] = in[(int) Math.min(last, Math.round(x))];
                continue;
            }
            int i = (int) Math.min(last, (long) x);
            double t = Math.min(1.0, x - i);
            double y1 = in[i];
            double y2 = in[Math.min(last, i + 1)];
            double v;
            if (algorithm == LINEAR) {
                v = y1 + (y2 - y1) * t;
            } else {
                // Catmull-Rom
                double y0 = in[Math.max(0, i - 1)];
                double y3 = in[Math.min(last, i + 2)];
                v = y1 + 0.5 * t * ((y2 - y0) + t * ((2 * y0 - 5 * y1 + 4 * y2 - y3)
                        + t * (3 * (y1 - y2) + y3 - y0)));
            }
            out[j] = clip(v);
        }
        return out;
    }

    /**
     * 窓関数付き sinc（カイザー窓）。レートを下げるときは、遮断周波数を下げて折り返しを防ぐ。
     * 標準：片側 16 サンプル・β 7.86。高品質：片側 64 サンプル・β 10.06。
     */
    private static void sinc(short[] in, double step, short[] out, boolean high,
                             Progress p, long done, long total) {
        int half = high ? 64 : 16;
        double beta = high ? 10.06 : 7.86;
        // 遮断周波数（低いほうのナイキスト周波数に対する比）。窓が短いほど、なだらかに落ちるので、手前に置く
        double cutoff = high ? 0.96 : 0.90;
        float[] table = sincTable(half, beta, cutoff);
        double r = Math.min(1.0, 1.0 / step); // レートを下げるときは、窓を入力の側で 1/r 倍に広げる
        double reach = half / r;
        double scale = r * TABLE_RES;
        int n = in.length;
        for (int j = 0; j < out.length; j++) {
            if ((j & 0x3FFF) == 0) {
                p.step(done + j, total);
            }
            double x = j * step;
            int from = (int) Math.ceil(x - reach);
            int to = (int) Math.floor(x + reach);
            double sum = 0;
            double weights = 0;
            for (int k = from; k <= to; k++) {
                double u = Math.abs(k - x) * scale;
                int ui = (int) u;
                float w = table[ui] + (table[ui + 1] - table[ui]) * (float) (u - ui);
                weights += w;
                if (k >= 0 && k < n) {
                    sum += w * in[k];
                }
            }
            out[j] = clip(sum / weights);
        }
    }

    /** sinc(cutoff × u) × カイザー窓(u / half) の表。u は 0～half */
    private static float[] sincTable(int half, double beta, double cutoff) {
        int size = half * TABLE_RES;
        float[] table = new float[size + 2];
        double i0Beta = besselI0(beta);
        for (int i = 0; i <= size; i++) {
            double u = (double) i / TABLE_RES;
            double a = Math.PI * cutoff * u;
            double s = i == 0 ? 1.0 : Math.sin(a) / a;
            double q = u / half;
            double window = besselI0(beta * Math.sqrt(Math.max(0, 1 - q * q))) / i0Beta;
            table[i] = (float) (s * window);
        }
        return table;
    }

    private static double besselI0(double x) {
        double sum = 1;
        double term = 1;
        double h = x / 2;
        for (int k = 1; k < 60; k++) {
            term *= (h / k) * (h / k);
            sum += term;
            if (term < sum * 1e-14) {
                break;
            }
        }
        return sum;
    }

    /**
     * タイムストレッチ（WSOLA）。音程を変えずに、長さを outLength にする。
     * 波形の似た所を探して、短い区間を半分ずつ重ねながらつなぐ。探すのは、チャンネルを混ぜた信号で行う。
     */
    static short[][] stretch(short[][] in, int sampleRate, int outLength, Progress p) {
        int channels = in.length;
        int n = in[0].length;
        short[][] out = new short[channels][outLength];
        int hop = Math.max(8, sampleRate * 15 / 1000); // 重ねる長さ（約 15 ミリ秒）。区間はこの 2 倍
        int window = hop * 2;
        int search = Math.max(4, sampleRate * 12 / 1000); // 探す範囲（前後に約 12 ミリ秒）
        int dec = Math.max(1, sampleRate / 11025);        // 粗く探すときの間引き

        // 粗く探すための、間引いた信号（チャンネルを混ぜる）
        int dn = n / dec + 1;
        short[] coarse = new short[dn];
        for (int i = 0; i < dn; i++) {
            if ((i & 0xFFFF) == 0) {
                p.step(0, 1);
            }
            long sum = 0;
            int count = 0;
            for (int k = i * dec; k < Math.min(n, i * dec + dec); k++) {
                for (short[] ch : in) {
                    sum += ch[k];
                }
                count += channels;
            }
            coarse[i] = count == 0 ? 0 : (short) (sum / count);
        }

        float[] fade = new float[hop]; // 窓の前半（後半は 1 - fade）
        for (int i = 0; i < hop; i++) {
            fade[i] = (float) (0.5 - 0.5 * Math.cos(Math.PI * (i + 0.5) / hop));
        }
        float[][] tail = new float[channels][hop]; // 前の区間の後半（窓をかけたもの）
        double ratio = (double) n / outLength;
        int prev = Integer.MIN_VALUE; // 前の区間の、入力での先頭
        int frames = (outLength + hop - 1) / hop;
        for (int m = 0; m < frames; m++) {
            if ((m & 0x3F) == 0) {
                p.step(m, frames);
            }
            int outPos = m * hop;
            // 区間が入力の末尾からはみ出さないようにする（はみ出すと、出力の末尾が小さくなる）
            int limit = Math.max(0, n - window);
            int ideal = Math.min(limit, (int) Math.round(outPos * ratio));
            int pos = ideal;
            if (m > 0 && prev + hop != ideal) {
                // 前の区間の自然な続き（prev + hop から）に、いちばん似ている所を探す
                pos = bestMatch(in, coarse, dec, prev + hop, ideal, search, window, limit);
            }
            prev = pos;
            int count = Math.min(hop, outLength - outPos);
            for (int c = 0; c < channels; c++) {
                short[] src = in[c];
                short[] dst = out[c];
                float[] t = tail[c];
                for (int i = 0; i < count; i++) {
                    // 最初の区間の前半は、重ねる相手がないので、窓をかけない
                    float head = at(src, pos + i, n) * (m == 0 ? 1f : fade[i]);
                    dst[outPos + i] = clip(t[i] + head);
                }
                for (int i = 0; i < hop; i++) {
                    t[i] = at(src, pos + hop + i, n) * (1f - fade[i]);
                }
            }
        }
        return out;
    }

    /** ideal の前後 search の範囲（0～limit の中）で、target から始まる区間にいちばん似ている所を探す */
    private static int bestMatch(short[][] in, short[] coarse, int dec, int target, int ideal,
                                 int search, int window, int limit) {
        // 粗く探す（間引いた信号で）
        int dTarget = target / dec;
        int dIdeal = ideal / dec;
        int dSearch = Math.max(1, search / dec);
        int dWindow = Math.max(4, window / dec);
        int dn = coarse.length;
        long best = Long.MIN_VALUE;
        int bestLag = 0;
        for (int lag = -dSearch; lag <= dSearch; lag++) {
            int cand = dIdeal + lag;
            int full = ideal + lag * dec;
            if (full < 0 || full > limit) {
                continue;
            }
            long sum = 0;
            for (int i = 0; i < dWindow; i++) {
                int a = dTarget + i;
                int b = cand + i;
                if (a >= 0 && a < dn && b >= 0 && b < dn) {
                    sum += coarse[a] * coarse[b];
                }
            }
            if (sum > best) {
                best = sum;
                bestLag = lag;
            }
        }
        int center = ideal + bestLag * dec;
        if (dec == 1) {
            return center;
        }
        // 細かく探す（元の信号で、粗く見つけた所のまわりだけ）
        int n = in[0].length;
        int stride = Math.max(1, dec / 2);
        best = Long.MIN_VALUE;
        int bestPos = center;
        for (int cand = center - dec + 1; cand < center + dec; cand++) {
            if (cand < 0 || cand > limit) {
                continue;
            }
            long sum = 0;
            for (int i = 0; i < window; i += stride) {
                int a = target + i;
                int b = cand + i;
                if (a >= 0 && a < n && b >= 0 && b < n) {
                    for (short[] ch : in) {
                        sum += ch[a] * ch[b];
                    }
                }
            }
            if (sum > best) {
                best = sum;
                bestPos = cand;
            }
        }
        return bestPos;
    }

    private static float at(short[] src, int index, int n) {
        return index >= 0 && index < n ? src[index] : 0f;
    }

    private static short clip(double v) {
        return (short) Math.max(-32768, Math.min(32767, Math.round(v)));
    }
}
