package net.gorry.mase;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * mp3 の書き出し。LAME（libmp3lame.so）を、橋渡しのライブラリ（libmasemp3.so）から呼ぶ。
 * 方針は memo/doc/design.md の「外のライブラリ」。
 */
final class Mp3Encoder {
    /** mp3 で書き出せるサンプリングレート */
    private static final int[] SAMPLE_RATES = {8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000};

    /** 1 回に渡すサンプル数 */
    private static final int CHUNK = 8192;

    private static Boolean loaded;

    private Mp3Encoder() {
    }

    private static synchronized boolean load() {
        if (loaded == null) {
            try {
                System.loadLibrary("masemp3");
                loaded = true;
            } catch (UnsatisfiedLinkError e) {
                loaded = false;
            }
        }
        return loaded;
    }

    /** このサンプリングレートとチャンネル数で、mp3 に書き出せるか */
    static boolean canWrite(int sampleRate, int channels) {
        if (channels < 1 || channels > 2) {
            return false;
        }
        for (int rate : SAMPLE_RATES) {
            if (rate == sampleRate) {
                return load();
            }
        }
        return false;
    }

    /** このサンプリングレートで選べるビットレート（bps）の上限。mp3 の規格で決まる */
    static int maxBitRate(int sampleRate) {
        return sampleRate >= 32000 ? 320_000 : sampleRate >= 16000 ? 160_000 : 64_000;
    }

    /** [start, end) を file に書き出す */
    static void write(File file, AudioData data, int start, int end, int bitRate) throws IOException {
        if (!canWrite(data.sampleRate, data.channels)) {
            throw new IOException("mp3 not available for " + data.sampleRate + "Hz");
        }
        long handle = nativeOpen(data.sampleRate, data.channels, bitRate / 1000);
        if (handle == 0) {
            throw new IOException("cannot open mp3 encoder");
        }
        try (RandomAccessFile out = new RandomAccessFile(file, "rw")) {
            if (nativeOutSampleRate(handle) != data.sampleRate) {
                throw new IOException("mp3 encoder changed the sample rate");
            }
            out.setLength(0);
            short[] left = data.samples[0];
            short[] right = data.channels == 2 ? data.samples[1] : left;
            byte[] buffer = new byte[CHUNK * 5 / 4 + 7200];
            int pos = start;
            while (pos < end) {
                int count = Math.min(CHUNK, end - pos);
                int n = nativeEncode(handle, left, right, pos, count, buffer);
                if (n < 0) {
                    throw new IOException("mp3 encoding failed: " + n);
                }
                out.write(buffer, 0, n);
                pos += count;
            }
            int n = nativeFlush(handle, buffer);
            if (n < 0) {
                throw new IOException("mp3 flush failed: " + n);
            }
            out.write(buffer, 0, n);
            // 先頭のフレームを、長さなどの情報が入ったものに書き換える（読む側が、前後の余分な無音を除けるようにする）
            n = nativeTagFrame(handle, buffer);
            if (n > 0) {
                out.seek(0);
                out.write(buffer, 0, n);
            }
        } finally {
            nativeClose(handle);
        }
    }

    private static native long nativeOpen(int sampleRate, int channels, int kbps);

    private static native int nativeOutSampleRate(long handle);

    private static native int nativeEncode(long handle, short[] left, short[] right,
                                           int offset, int count, byte[] out);

    private static native int nativeFlush(long handle, byte[] out);

    private static native int nativeTagFrame(long handle, byte[] out);

    private static native void nativeClose(long handle);
}
