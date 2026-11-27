package net.gorry.mase;

import android.net.Uri;

/**
 * 編集コマンドのダイアログで、前回「OK」で実行したときの値。次にダイアログを開いたときに、最初の値として出す。
 * Session と同じく、アプリが起動したときに作られ、アプリが終了するまで残る（保存はしない）。
 * 仕様は memo/doc/spec.md の「ダイアログの値」。
 */
final class DialogMemory {
    /** 足す音の種類のうち、まだ選んでいないことを表す値（ダイアログの最初の選択にする） */
    static final int KIND_NONE = -1;

    /** 音量の変更（dB） */
    double volumeDb = 0;
    /** 音量の最大化（dB） */
    double normalizeDb = 0;
    /** フェードイン・フェードアウト（dB） */
    double fadeInDb = -96;
    double fadeOutDb = -96;

    /** 音程の変更（半音） */
    double pitchSemitones = 0;
    /** 音程の変更：再生時間を維持する */
    boolean pitchKeepDuration = false;

    /** サンプリングレート変換アルゴリズム。サンプリングレートの変更・音程の変更・音の追加で共通 */
    int algorithm = Resampler.DEFAULT;

    /** サンプリングレートの変更・再生レートの変更の「変更後」。0 のときは、今のサンプリングレートを選ぶ */
    int resampleRate = 0;
    int playRate = 0;

    /** チャンネル数の変更（ステレオからモノラル）の方法。0：L のみ、1：R のみ、2：LR 合成 */
    int monoChoice = 2;

    /** 音のミックス */
    final Sound mix = new Sound();
    /** 音の挿入・追加 */
    final Sound insert = new Sound();

    /**
     * 「音の追加」のダイアログの値。選んだ音声ファイルは、場所（URI）と名前だけを覚え、中身は覚えない
     * （中身は、次にダイアログで使うときに、読み直す）
     */
    static final class Sound {
        /** 元の音の音量（dB。音のミックスだけ） */
        double baseDb = 0;
        /** 足す音の音量（dB） */
        double volumeDb = 0;
        /** 足す音の種類（SoundCommands の KIND_…）。KIND_NONE なら、一覧の最初 */
        int kind = KIND_NONE;
        /** 周波数（Hz） */
        double hz = 1000;
        /** 長さの決め方（音の挿入・追加で、音声ファイルのとき）。0：ファイル全体、1：長さを指定 */
        int lengthMode = 0;
        /** 長さ（秒。音の挿入・追加だけ） */
        double lengthSeconds = 1;
        /** 繰り返し */
        boolean repeat = false;
        /** 選んだ音声ファイルの場所。選んでいないとき、読み直せなかったときは null */
        Uri fileUri;
        /** 選んだ音声ファイルの名前（表示用） */
        String fileName;
    }
}
