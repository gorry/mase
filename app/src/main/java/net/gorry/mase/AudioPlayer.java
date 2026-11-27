package net.gorry.mase;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Log;

/**
 * 音声データの再生。開始位置から終了位置までを、AudioTrack に少しずつ書き込んで鳴らす。
 * 仕様は memo/doc/spec.md の「再生」。
 */
final class AudioPlayer {
    private static final String TAG = "mase.AudioPlayer";
    private static final int CHUNK_FRAMES = 2048;

    interface Listener {
        /** リピートなしで、終了位置まで再生し終えた。再生のスレッドから呼ばれる */
        void onPlaybackFinished();

        /**
         * リピートで、次の繰り返しを始めるときに呼ばれる。今の開始位置・終了位置を返す（{開始, 終了}）。
         * 再生のスレッドから呼ばれる
         */
        int[] rangeForRepeat();
    }

    private final Listener listener;
    private AudioTrack track;
    private Thread thread;
    private AudioData data;

    private volatile boolean running;
    private volatile boolean repeat;
    /**
     * 鳴らすチャンネル（ステレオのとき）。オフのチャンネルは、無音にして書き込む。
     * 書き込むたびに読むので、再生中に変えると、出力にたまっている分（0.1 秒ほど）の後に、聞こえ方が変わる
     */
    private volatile boolean leftOn = true;
    private volatile boolean rightOn = true;

    private final Object lock = new Object();
    // 以下は lock の中で読み書きする
    private int rangeStart;
    private int rangeEnd;
    /** 次に書き込む位置 */
    private int writePosition;
    /** 今の AudioTrack に書き込んだフレーム数（flush すると 0 に戻す） */
    private long framesWritten;
    /** 今の繰り返しの先頭と、1 つ前の繰り返しの範囲。鳴っている位置を求めるのに使う */
    private int loopStart;
    private int previousStart;
    private int previousEnd;
    /** 動かす先（シーク）。なければ -1 */
    private int pendingSeek = -1;

    AudioPlayer(Listener listener) {
        this.listener = listener;
    }

    /** このサンプリングレートとチャンネル数で再生できるか */
    static boolean canPlay(int sampleRate, int channels) {
        AudioTrack t = null;
        try {
            t = build(sampleRate, channels);
            return t.getState() == AudioTrack.STATE_INITIALIZED;
        } catch (RuntimeException e) {
            Log.i(TAG, "cannot play " + sampleRate + "Hz " + channels + "ch: " + e);
            return false;
        } finally {
            if (t != null) {
                t.release();
            }
        }
    }

    /** 総当たりで試すサンプリングレート（48kHz 系、44.1kHz 系、32kHz 系） */
    private static final int[] RATE_CANDIDATES = {
            1000, 1500, 2000, 3000, 4000, 6000, 8000, 11025, 12000, 16000, 22050, 24000, 32000,
            44100, 48000, 64000, 88200, 96000, 128000, 176400, 192000,
    };
    private static java.util.List<Integer> supportedRates;

    /** この端末で再生できるサンプリングレート。最初に呼ばれたときに、決め打ちの値を総当たりで試して調べる */
    static synchronized java.util.List<Integer> supportedRates() {
        if (supportedRates == null) {
            java.util.List<Integer> list = new java.util.ArrayList<>();
            for (int rate : RATE_CANDIDATES) {
                if (canPlay(rate, 2)) {
                    list.add(rate);
                }
            }
            supportedRates = java.util.Collections.unmodifiableList(list);
        }
        return supportedRates;
    }

    private static AudioTrack build(int sampleRate, int channels) {
        int mask = channels >= 2 ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO;
        int min = AudioTrack.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) {
            throw new IllegalArgumentException("getMinBufferSize=" + min);
        }
        return new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(mask)
                        .build())
                .setBufferSizeInBytes(Math.max(min * 2, CHUNK_FRAMES * 2 * channels * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
    }

    boolean isPlaying() {
        return running;
    }

    void setRepeat(boolean repeat) {
        this.repeat = repeat;
    }

    /** 鳴らすチャンネルを決める（L・R のスイッチ）。再生中でもよい。モノラルの音声データには、関係しない */
    void setChannels(boolean left, boolean right) {
        leftOn = left;
        rightOn = right;
    }

    /**
     * 再生を始める。
     *
     * @return 始められなかった（このサンプリングレートでは再生できない）ときは false
     */
    boolean play(AudioData audio, int start, int end, int from, boolean repeatOn) {
        stop();
        try {
            track = build(audio.sampleRate, audio.channels);
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                track.release();
                track = null;
                return false;
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "cannot start playback", e);
            track = null;
            return false;
        }
        data = audio;
        repeat = repeatOn;
        synchronized (lock) {
            rangeStart = start;
            rangeEnd = end;
            writePosition = (from < start || from >= end) ? start : from;
            framesWritten = 0;
            loopStart = writePosition;
            previousStart = writePosition;
            previousEnd = writePosition;
            pendingSeek = -1;
        }
        running = true;
        AudioTrack t = track;
        t.play();
        thread = new Thread(() -> run(t), "mase-play");
        thread.start();
        return true;
    }

    /**
     * 再生を止める。
     *
     * @return 止めたときに鳴っていた位置。再生していなかったときは -1
     */
    int stop() {
        if (track == null) {
            return -1;
        }
        int position = currentPosition();
        running = false;
        try {
            track.pause();
            track.flush();
        } catch (RuntimeException ignored) {
        }
        if (thread != null) {
            try {
                thread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
        track.release();
        track = null;
        data = null;
        return position;
    }

    /** 再生する範囲を、今の開始位置・終了位置に合わせる。書き込む位置が範囲の外になるときは、先頭へ動かす */
    void updateRange(int start, int end) {
        synchronized (lock) {
            rangeStart = start;
            rangeEnd = end;
            if (writePosition < start || writePosition >= end) {
                pendingSeek = start;
            }
        }
    }

    /** 再生したまま、位置を動かす */
    void seek(int position) {
        synchronized (lock) {
            pendingSeek = Math.max(rangeStart, Math.min(rangeEnd - 1, position));
        }
    }

    /** 今、鳴っている位置 */
    int currentPosition() {
        AudioTrack t = track;
        if (t == null) {
            return -1;
        }
        long head;
        try {
            head = t.getPlaybackHeadPosition() & 0xFFFFFFFFL;
        } catch (RuntimeException e) {
            return -1;
        }
        synchronized (lock) {
            if (pendingSeek >= 0) {
                return pendingSeek;
            }
            long waiting = Math.max(0, framesWritten - head); // 書き込んだが、まだ鳴っていない分
            long p = writePosition - waiting;
            if (p < loopStart) {
                // 1 つ前の繰り返しの終わりのほうが、まだ鳴っている
                p = previousEnd - (loopStart - p);
                if (p < previousStart) {
                    p = previousStart;
                }
            }
            return (int) p;
        }
    }

    private void run(AudioTrack t) {
        AudioData audio = data;
        int channels = audio.channels;
        short[] buffer = new short[CHUNK_FRAMES * channels];
        try {
            while (running) {
                int from;
                int count;
                synchronized (lock) {
                    if (pendingSeek >= 0) {
                        t.pause();
                        t.flush();
                        framesWritten = 0;
                        writePosition = pendingSeek;
                        loopStart = writePosition;
                        previousStart = writePosition;
                        previousEnd = writePosition;
                        pendingSeek = -1;
                        t.play();
                    }
                    if (writePosition >= rangeEnd) {
                        if (!repeat) {
                            break;
                        }
                        // 次の繰り返しを始める。再生する範囲を、今の開始位置・終了位置に取り直す
                        // （再生中に動かした開始位置・終了位置を、ここで反映する）
                        int[] range = listener.rangeForRepeat();
                        if (range != null) {
                            int start = Math.max(0, Math.min(audio.length - 1, range[0]));
                            int end = Math.max(start + 1, Math.min(audio.length, range[1]));
                            rangeStart = start;
                            rangeEnd = end;
                        }
                        previousStart = loopStart;
                        previousEnd = writePosition;
                        writePosition = rangeStart;
                        loopStart = rangeStart;
                    }
                    from = writePosition;
                    count = Math.min(CHUNK_FRAMES, rangeEnd - from);
                }
                if (count <= 0) {
                    break;
                }
                if (channels == 1) {
                    System.arraycopy(audio.samples[0], from, buffer, 0, count);
                } else {
                    short[] l = audio.samples[0];
                    short[] r = audio.samples[1];
                    // オフのチャンネルは、無音にする（L・R のスイッチ。書き込むたびに読む）
                    boolean lOn = leftOn;
                    boolean rOn = rightOn;
                    for (int i = 0, o = 0; i < count; i++) {
                        buffer[o++] = lOn ? l[from + i] : 0;
                        buffer[o++] = rOn ? r[from + i] : 0;
                    }
                }
                int written = t.write(buffer, 0, count * channels);
                if (written < 0) {
                    Log.w(TAG, "write failed: " + written);
                    break;
                }
                synchronized (lock) {
                    if (pendingSeek < 0) {
                        int frames = written / channels;
                        writePosition = from + frames;
                        framesWritten += frames;
                    }
                }
            }
            // 書き込んだ分が鳴り終わるのを待つ。位置が進まなくなったら、あきらめる
            long lastHead = -1;
            int stalled = 0;
            while (running) {
                long head = t.getPlaybackHeadPosition() & 0xFFFFFFFFL;
                if (head == lastHead) {
                    if (++stalled > 50) {
                        break;
                    }
                } else {
                    stalled = 0;
                    lastHead = head;
                }
                boolean seekRequested;
                long written;
                synchronized (lock) {
                    seekRequested = pendingSeek >= 0;
                    written = framesWritten;
                }
                if (seekRequested) {
                    run(t); // 待っている間にシークされた。続きを再生する
                    return;
                }
                if (head >= written) {
                    break;
                }
                Thread.sleep(10);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            Log.w(TAG, "playback stopped", e);
        }
        if (running) {
            running = false;
            listener.onPlaybackFinished();
        }
    }
}
