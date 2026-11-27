package net.gorry.mase;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 設定の保存と読み出し。Android の SharedPreferences に保存する。
 * 仕様は memo/doc/spec.md の「設定画面」。
 */
final class Settings {
    private static final String FILE = "settings";
    private static final String COLOR_PREFIX = "color.";
    private static final String SILENCE_MS = "silence.ms";
    private static final String SILENCE_DB = "silence.db";
    /** 以前の無音の設定（0 付近の幅、微分値の指定値、振幅の値）で使っていた名前。保存するときに消す */
    private static final String[] OLD_KEYS = {"silence.level", "silence.diff", "silence.amplitude"};
    private static final String UNDO_MAX = "undo.max";

    static final int DEFAULT_SILENCE_MS = 10;
    static final int MIN_SILENCE_MS = 1;
    static final int MAX_SILENCE_MS = 1000;
    static final float MAX_SILENCE_DB = 96.0f;
    /** 無音の音量のプリセット（dB）。振幅では、大が約 512、中が約 128、小が約 32 */
    static final float SILENCE_DB_LARGE = 42.0f;
    static final float SILENCE_DB_MEDIUM = 54.0f;
    static final float SILENCE_DB_SMALL = 66.0f;
    /** 無音の音量の最初の値（dB）。プリセットの「中」と同じ */
    static final float DEFAULT_SILENCE_DB = SILENCE_DB_MEDIUM;
    static final int DEFAULT_UNDO_MAX = 10;
    static final int MAX_UNDO_MAX = 99;

    private Settings() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** 保存してある設定を、session に読み込む。保存していないものは、最初の値のまま */
    static void load(Context context, Session session) {
        SharedPreferences p = prefs(context);
        for (int i = 0; i < ViewColors.COUNT; i++) {
            session.colors.set(i, p.getInt(COLOR_PREFIX + ViewColors.KEYS[i], session.colors.get(i)));
        }
        session.silenceMs = clamp(p.getInt(SILENCE_MS, DEFAULT_SILENCE_MS), MIN_SILENCE_MS, MAX_SILENCE_MS);
        session.silenceDb = Math.max(0f, Math.min(MAX_SILENCE_DB, p.getFloat(SILENCE_DB, DEFAULT_SILENCE_DB)));
        session.undoMax = clamp(p.getInt(UNDO_MAX, DEFAULT_UNDO_MAX), 0, MAX_UNDO_MAX);
    }

    /** session の今の設定を、すべて保存する */
    static void save(Context context, Session session) {
        SharedPreferences.Editor e = prefs(context).edit();
        for (int i = 0; i < ViewColors.COUNT; i++) {
            // 最初の色のままのものは、保存しない（後で最初の色を変えたときに、新しい色になるように）
            String key = COLOR_PREFIX + ViewColors.KEYS[i];
            if (session.colors.get(i) == ViewColors.defaultOf(i)) {
                e.remove(key);
            } else {
                e.putInt(key, session.colors.get(i));
            }
        }
        e.putInt(SILENCE_MS, session.silenceMs);
        e.putFloat(SILENCE_DB, session.silenceDb);
        for (String old : OLD_KEYS) {
            e.remove(old);
        }
        e.putInt(UNDO_MAX, session.undoMax);
        e.apply();
    }

    static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
