package net.gorry.mase;

/**
 * 編集中の状態。画面（Activity）が作り直されても残るように、Activity の外に持つ。
 */
final class Session {
    static final int UNIT_SECONDS = 0;
    static final int UNIT_SAMPLES = 1;

    /** 内部の音声データ。読み込んでいないときは null */
    AudioData data;
    /** データファイル名 */
    String fileName;
    /** 未保存 */
    boolean dirty;

    /** 開始位置（0～end-1）と終了位置（start+1～長さ）。間が選択範囲 */
    // 再生のスレッドからも読む（リピートで次の繰り返しを始めるとき）ので、volatile にする
    volatile int start;
    volatile int end;
    /** 再生位置（start～end）。再生中は、再生している範囲の中の位置 */
    int playPosition;
    /** 再生中 */
    boolean playing;
    /** リピート */
    boolean repeat;
    /** 今のサンプリングレートで再生できないことを、もう表示した */
    boolean unplayableNotified;

    /** 横の倍率バーの値（0 で全体表示、1 で 1 サンプル 16px） */
    float zoomH;
    /** 縦の倍率バーの値（0 で 1 倍、1 で 256 倍） */
    float zoomV;
    /** 波形ビューの左端の位置（サンプル） */
    double scroll;

    /** データ単位。行ごとに持つ */
    int startUnit = UNIT_SECONDS;
    int endUnit = UNIT_SECONDS;

    final ViewColors colors = new ViewColors();

    /** 加工するチャンネル（ファイル名の右の L・R のスイッチ）。どちらか、または両方が true */
    boolean leftOn = true;
    boolean rightOn = true;

    /**
     * 加工するチャンネルの番号。モノラルの音声データでは、いつも {0}（スイッチは関係しない）。
     * 長さが変わらない加工と、無音区間・発音区間の選択で使う。仕様は memo/doc/spec.md の「チャンネルのスイッチ」
     */
    int[] targetChannels() {
        if (data == null || data.channels == 1) {
            return new int[]{0};
        }
        if (leftOn && rightOn) {
            return new int[]{0, 1};
        }
        return new int[]{leftOn ? 0 : 1};
    }

    /** all（チャンネルごとの配列）のうち、加工するチャンネルの配列だけを並べて返す（配列そのものは写さない） */
    short[][] targets(short[][] all) {
        int[] picked = targetChannels();
        short[][] out = new short[picked.length][];
        for (int i = 0; i < picked.length; i++) {
            out[i] = all[picked[i]];
        }
        return out;
    }

    /** 編集コマンドのダイアログの、前回の値 */
    final DialogMemory dialogs = new DialogMemory();

    /** 元に戻すための履歴。historyIndex より前が「元に戻す」分、後ろが「やり直す」分 */
    final java.util.ArrayList<Editor.Entry> history = new java.util.ArrayList<>();
    int historyIndex;
    /** 元に戻せる最大回数（0～99）。0 のときは、履歴を保存しない */
    int undoMax = Settings.DEFAULT_UNDO_MAX;
    /** 無音の判定：期間の長さ（ミリ秒） */
    int silenceMs = Settings.DEFAULT_SILENCE_MS;
    /**
     * 無音の判定：無音の音量（dB）。0～96 の、最大の振幅からどれだけ下かの値（大きいほど小さな音）。
     * 期間の中の振幅（最大値と最小値の差）が、この音量にあたる振幅以下なら、無音とする。
     */
    float silenceDb = Settings.DEFAULT_SILENCE_DB;

    /** 無音の音量にあたる振幅（最大値と最小値の差）。0dB が 16bit の幅いっぱい（65536） */
    int silenceAmplitude() {
        return (int) Math.round(65536.0 / Math.pow(10.0, silenceDb / 20.0));
    }

    /** 「名前を付けて保存」で決めた保存先と設定。次の「保存」で使う。まだなら null */
    android.net.Uri saveUri;
    AudioSaver.Params saveParams;

    /** コピーバッファ。空のときは null */
    short[][] clipboard;
    int clipboardRate;

    boolean hasData() {
        return data != null;
    }

    int length() {
        return data == null ? 0 : data.length;
    }

    /** 読み込んだ直後の状態にする。全体表示で、開始位置は先頭、終了位置は末尾 */
    void open(AudioData newData, String name) {
        data = newData;
        fileName = name;
        dirty = false;
        saveUri = null; // 別のファイルを読み込んだら、前の保存先は使わない
        saveParams = null;
        history.clear(); // 音声ファイルを読み込むと、履歴を空にする
        historyIndex = 0;
        start = 0;
        end = newData.length;
        playPosition = 0;
        playing = false;
        unplayableNotified = false;
        zoomH = 0f;
        zoomV = 0f;
        scroll = 0;
    }

    /** 音声データを読み込んでいない状態に戻す（アプリを終了するとき）。コピーバッファも捨てる */
    void close() {
        data = null;
        fileName = null;
        dirty = false;
        saveUri = null;
        saveParams = null;
        history.clear();
        historyIndex = 0;
        clipboard = null;
        start = 0;
        end = 0;
        playPosition = 0;
        playing = false;
    }

    /** 開始位置を動かす。データの範囲と、終了位置の 1 サンプル手前でクリップする */
    void setStart(long value) {
        if (data == null) {
            return;
        }
        start = (int) Math.max(0, Math.min(end - 1, value));
        clampPlayPosition();
    }

    /** 終了位置を動かす。データの範囲と、開始位置の 1 サンプル先でクリップする */
    void setEnd(long value) {
        if (data == null) {
            return;
        }
        end = (int) Math.max(start + 1, Math.min(data.length, value));
        clampPlayPosition();
    }

    /** 開始位置・終了位置をまとめて決める。データの範囲に収め、間を 1 サンプル以上あける */
    void setSelection(long newStart, long newEnd) {
        int length = length();
        start = (int) Math.max(0, Math.min(length - 1, newStart));
        end = (int) Math.max(start + 1, Math.min(length, newEnd));
        clampPlayPosition();
    }

    void setPlayPosition(long value) {
        playPosition = (int) Math.max(start, Math.min(end, value));
    }

    /** 再生していない間は、再生位置を開始位置・終了位置でクリップする。再生中は、動かしたバーをまだ反映しない */
    void clampPlayPosition() {
        if (!playing) {
            setPlayPosition(playPosition);
        }
    }
}
