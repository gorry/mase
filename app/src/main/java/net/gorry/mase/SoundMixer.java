package net.gorry.mase;

/**
 * 「音の追加」で足す音を作る処理と、混ぜる処理。画面の部品は使わない。
 * 仕様は memo/doc/spec.md の「音の追加」。
 */
final class SoundMixer {
    /** 波形の種類 */
    static final int SINE = 0;
    static final int SQUARE = 1;

    private SoundMixer() {
    }

    /** dB の値を、倍率にする */
    static double gain(double db) {
        return Math.pow(10, db / 20);
    }

    /**
     * サイン波・矩形波を、1 チャンネル分作る。先頭のサンプルが、波の始まり（位相 0）。
     * 振幅は 32767 × gain。
     * 矩形波は、1 周期の前半が正、後半が負。帯域を制限しない（高い周波数では、折り返しの音が出る）。
     * 周波数が 0 のとき、サイン波は無音、矩形波は正の値のまま。
     * 進み具合は、p の今の段階の中で知らせる。
     */
    static short[] tone(int shape, int length, int sampleRate, double hz, double gain, Progress p) {
        short[] out = new short[length];
        double amplitude = 32767.0 * gain;
        short high = clip(amplitude);
        short low = clip(-amplitude);
        for (int i = 0; i < length; i++) {
            if ((i & 0xFFFF) == 0) {
                p.step(i, length);
            }
            // 位相（0～1）。サンプルの番号から毎回求める（足し続けると、誤差がたまる）。
            // 先にかけてから割る（割り切れる周波数で、矩形波の切り替わりが 1 サンプルずれないように）
            double cycles = i * hz / sampleRate;
            double phase = cycles - Math.floor(cycles);
            if (shape == SQUARE) {
                out[i] = phase < 0.5 ? high : low;
            } else {
                out[i] = clip(amplitude * Math.sin(2 * Math.PI * phase));
            }
        }
        return out;
    }

    /**
     * チャンネル数を合わせる。src の先頭から length サンプルを使う。
     * 同じなら、src をそのまま返す。ステレオにするときは、L と R に同じ配列を入れて返す（写しは作らない。
     * 返したものを音声データにそのまま入れてはいけない）。モノラルにするときは、L と R の平均を、新しい配列に作る。
     */
    static short[][] matchChannels(short[][] src, int length, int channels, Progress p) {
        if (src.length == channels) {
            return src;
        }
        if (channels == 2) {
            return new short[][]{src[0], src[0]};
        }
        short[] l = src[0];
        short[] r = src[1];
        short[] out = new short[length];
        for (int i = 0; i < length; i++) {
            if ((i & 0xFFFF) == 0) {
                p.step(i, length);
            }
            out[i] = (short) ((l[i] + r[i]) / 2);
        }
        return new short[][]{out};
    }

    /**
     * dst に、src を混ぜる。dst[c][i] = dst[c][i] × dstGain + src[c][i] × srcGain。16bit の範囲を越える分は、クリップする。
     * src が dst より短いとき、repeat なら src を先頭から繰り返し、そうでなければ、残りには何も足さない
     * （dstGain は、dst の全体にかかる）。src が長いときは、dst の長さまでを使う。
     * src のチャンネル数は、dst と同じ（matchChannels で合わせておく）。
     */
    static void mix(short[][] dst, double dstGain, short[][] src, int srcLength, double srcGain,
                    boolean repeat, Progress p) {
        int n = dst[0].length;
        long total = (long) n * dst.length;
        long done = 0;
        for (int c = 0; c < dst.length; c++) {
            short[] d = dst[c];
            short[] s = src[c];
            int at = 0; // src の中の位置
            for (int i = 0; i < n; i++) {
                if ((i & 0xFFFF) == 0) {
                    p.step(done + i, total);
                }
                double v = d[i] * dstGain;
                if (at < srcLength) {
                    v += s[at] * srcGain;
                    at++;
                    if (at == srcLength && repeat) {
                        at = 0;
                    }
                }
                d[i] = clip(v);
            }
            done += n;
        }
    }

    /**
     * 長さ length の無音を作り、その先頭から src × gain を入れたものを返す。チャンネルごとに、新しい配列を作る。
     * src が短いとき、repeat なら src を先頭から繰り返し、そうでなければ、残りは無音のまま。
     * src が長いときは、length までを使う。src は null でもよい（すべて無音）。
     */
    static short[][] place(short[][] src, int srcLength, int channels, int length, double gain,
                           boolean repeat, Progress p) {
        short[][] out = new short[channels][length];
        if (src == null || srcLength <= 0) {
            return out;
        }
        long total = (long) length * channels;
        long done = 0;
        for (int c = 0; c < channels; c++) {
            short[] o = out[c];
            short[] s = src[c];
            int end = repeat ? length : Math.min(length, srcLength);
            int at = 0;
            for (int i = 0; i < end; i++) {
                if ((i & 0xFFFF) == 0) {
                    p.step(done + i, total);
                }
                o[i] = gain == 1.0 ? s[at] : clip(s[at] * gain);
                if (++at == srcLength) {
                    at = 0;
                }
            }
            done += length;
        }
        return out;
    }

    private static short clip(double v) {
        return (short) Math.max(-32768, Math.min(32767, Math.round(v)));
    }
}
