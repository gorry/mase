package net.gorry.mase;

import android.util.Log;

/**
 * 音声データへの編集の適用と、元に戻す・やり直す。
 * 編集はすべて「[pos, pos + removeLength) を新しい区間に置き換える」形で表し、履歴には置き換えた前後の区間（差分）を持つ。
 * 仕様は memo/doc/spec.md の「元に戻す」。
 */
final class Editor {
    private static final String TAG = "mase.Editor";

    /** 編集の結果 */
    enum Result {
        /** 実行した */
        OK,
        /** 実行した。長さの上限を越えたので、末尾を切り捨てた */
        TRUNCATED,
        /** メモリが足りず、実行しなかった */
        NO_MEMORY,
        /** 思いがけない理由で、実行できなかった */
        FAILED,
        /** キャンセルされた。音声データは変わっていない */
        CANCELLED
    }

    /** 履歴の 1 つ分 */
    static final class Entry {
        int pos;
        short[][] oldSegment;
        short[][] newSegment;
        int oldRate;
        int newRate;
        int oldStart;
        int oldEnd;
        int newStart;
        int newEnd;
    }

    private Editor() {
    }

    static boolean canUndo(Session s) {
        return s.hasData() && s.historyIndex > 0;
    }

    static boolean canRedo(Session s) {
        return s.hasData() && s.historyIndex < s.history.size();
    }

    /**
     * 編集を適用する。[pos, pos + removeLength) を segment に置き換え、サンプリングレートを rate にする。
     * 適用の後の開始位置・終了位置は selStart・selEnd（データの範囲に収める）。
     * キャンセルされたときは、音声データを元のままにして Progress.Cancelled を投げる。
     */
    static Result apply(Session s, int pos, int removeLength, short[][] segment, int rate,
                        long selStart, long selEnd, Progress p) {
        AudioData d = s.data;
        boolean truncated = false;
        float base = p.fraction(); // ここまでの進み具合。残りを、この中の段階で分ける
        p.phaseOfRest(base, 0f, 0.1f);
        try {
            // 長さの上限を越える分は、末尾を切り捨てる
            int max = AudioData.maxLength(rate);
            long newLength = (long) d.length - removeLength + segment[0].length;
            if (newLength > max) {
                truncated = true;
                if (segment.length != d.channels) {
                    segment = head(segment, max);
                } else {
                    // 置き換える範囲を末尾まで広げて、上限に収まる分だけを新しい区間にする
                    int from = Math.min(pos, max);
                    int keep = max - from;
                    short[][] combined = new short[d.channels][keep];
                    for (int c = 0; c < d.channels; c++) {
                        int at = 0;
                        at = append(d.samples[c], from, pos - from, combined[c], at);
                        at = append(segment[c], 0, segment[c].length, combined[c], at);
                        append(d.samples[c], pos + removeLength, d.length - pos - removeLength, combined[c], at);
                    }
                    pos = from;
                    removeLength = d.length - from;
                    segment = combined;
                }
            }
        } catch (OutOfMemoryError e) {
            return Result.NO_MEMORY;
        }
        if (d.length - removeLength + segment[0].length < 1) {
            throw new IllegalArgumentException("audio data cannot be empty");
        }

        // 長さが変わらない編集は、元の配列を書き換える。途中でやめたときに戻せるよう、元の内容を取っておく
        boolean inPlace = segment.length == d.channels && segment[0].length == removeLength;
        Entry entry = null;
        while (true) {
            short[][] old = null;
            try {
                p.phaseOfRest(base, 0.1f, 0.3f);
                if (s.undoMax > 0 || inPlace) {
                    old = d.copyRange(pos, pos + removeLength);
                }
                if (s.undoMax > 0) {
                    entry = new Entry();
                    entry.pos = pos;
                    entry.oldSegment = old;
                    entry.newSegment = segment;
                    entry.oldRate = d.sampleRate;
                    entry.newRate = rate;
                    entry.oldStart = s.start;
                    entry.oldEnd = s.end;
                }
                p.phaseOfRest(base, 0.3f, 1f);
                s.data = AudioData.spliced(d, pos, removeLength, segment, rate, p);
                break;
            } catch (Progress.Cancelled c) {
                restore(d, pos, old, inPlace);
                throw c;
            } catch (OutOfMemoryError e) {
                restore(d, pos, old, inPlace);
                entry = null;
                // メモリが足りないときは、履歴の古いものから捨てて、やり直す
                if (s.history.isEmpty()) {
                    Log.w(TAG, "out of memory while editing");
                    return Result.NO_MEMORY;
                }
                dropOldest(s);
                System.gc();
            }
        }
        // ここから先は、キャンセルを見ない（音声データは、もう新しい内容になっている）

        s.setSelection(selStart, selEnd);
        if (entry != null) {
            entry.newStart = s.start;
            entry.newEnd = s.end;
            // 元に戻した後に新しい編集をしたら、やり直しの分は捨てる
            while (s.history.size() > s.historyIndex) {
                s.history.remove(s.history.size() - 1);
            }
            s.history.add(entry);
            s.historyIndex = s.history.size();
            trim(s);
        } else {
            s.history.clear();
            s.historyIndex = 0;
        }
        s.dirty = true;
        return truncated ? Result.TRUNCATED : Result.OK;
    }

    /** 元の配列を書き換えた後で、失敗したりキャンセルされたりしたときに、元の内容へ戻す */
    private static void restore(AudioData d, int pos, short[][] old, boolean inPlace) {
        if (inPlace && old != null) {
            for (int c = 0; c < d.channels; c++) {
                System.arraycopy(old[c], 0, d.samples[c], pos, old[c].length);
            }
        }
    }

    /** 直前の編集を元に戻す */
    static Result undo(Session s, Progress p) {
        if (!canUndo(s)) {
            return Result.OK;
        }
        Entry e = s.history.get(s.historyIndex - 1);
        Result result = swap(s, e.pos, e.newSegment, e.oldSegment, e.oldRate, p);
        if (result != Result.OK) {
            return result;
        }
        s.historyIndex--;
        s.setSelection(e.oldStart, e.oldEnd);
        s.dirty = true; // 元に戻す・やり直すの後は、常に未保存にする
        return Result.OK;
    }

    /** 元に戻した編集をやり直す */
    static Result redo(Session s, Progress p) {
        if (!canRedo(s)) {
            return Result.OK;
        }
        Entry e = s.history.get(s.historyIndex);
        Result result = swap(s, e.pos, e.oldSegment, e.newSegment, e.newRate, p);
        if (result != Result.OK) {
            return result;
        }
        s.historyIndex++;
        s.setSelection(e.newStart, e.newEnd);
        s.dirty = true;
        return Result.OK;
    }

    /** pos にある current を、replacement に置き換える（元に戻す・やり直すの中身） */
    private static Result swap(Session s, int pos, short[][] current, short[][] replacement, int rate,
                               Progress p) {
        AudioData d = s.data;
        int n = current[0].length;
        boolean inPlace = replacement.length == d.channels && replacement[0].length == n;
        try {
            s.data = AudioData.spliced(d, pos, n, replacement, rate, p);
            return Result.OK;
        } catch (Progress.Cancelled c) {
            restore(d, pos, current, inPlace);
            throw c;
        } catch (OutOfMemoryError err) {
            restore(d, pos, current, inPlace);
            return Result.NO_MEMORY;
        }
    }

    /** 履歴を、元に戻せる最大回数に収める。越えた分は、古いものから捨てる */
    static void trim(Session s) {
        while (s.history.size() > s.undoMax && !s.history.isEmpty()) {
            dropOldest(s);
        }
    }

    private static void dropOldest(Session s) {
        s.history.remove(0);
        if (s.historyIndex > 0) {
            s.historyIndex--;
        }
    }

    private static int append(short[] src, int from, int count, short[] dst, int at) {
        int n = Math.max(0, Math.min(count, dst.length - at));
        if (n > 0) {
            System.arraycopy(src, from, dst, at, n);
        }
        return at + n;
    }

    private static short[][] head(short[][] segment, int length) {
        short[][] out = new short[segment.length][length];
        for (int c = 0; c < segment.length; c++) {
            System.arraycopy(segment[c], 0, out[c], 0, length);
        }
        return out;
    }
}
