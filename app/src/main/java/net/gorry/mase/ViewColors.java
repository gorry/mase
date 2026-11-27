package net.gorry.mase;

/**
 * 波形ビュー・再生ビューに描くものの色。2 つのビューで共通。
 * 設定画面の「色の設定」で変えられる。仕様は memo/doc/spec.md の「色の設定」。
 */
final class ViewColors {
    /** 設定できる色の数 */
    static final int COUNT = 12;

    /** 最初の色。並びは get・set の番号と同じ */
    private static final int[] DEFAULTS = {
            0xFF000000, // 背景
            0xFF404040, // 選択範囲の背景
            0xFFCCCCCC, // モノラルの波形
            0xFF00D800, // ステレオの L だけの部分
            0xFFFF2020, // ステレオの R だけの部分
            0xFFCCCCCC, // ステレオの L と R が重なっている部分
            0xFFFFFFFF, // 開始位置のバー
            0xFFFFFFFF, // 終了位置のバー
            0xFFFFFFFF, // 再生位置
            // 目盛りの線は、どれも同じ色（R=0x7F、G=0xFF、B=0xFF）で、不透明度（A）だけを変える
            0x407FFFFF, // 目盛りの線（薄）。A=64
            0xA07FFFFF, // 目盛りの線（中）。A=160
            0xE07FFFFF, // 目盛りの線（濃）。A=224。0 を示す水平線と、時刻の文字も、この色
    };

    /** 設定を保存するときの名前。並びは get・set の番号と同じ */
    static final String[] KEYS = {
            "background", "selection", "mono", "left", "right", "overlap", "startBar", "endBar", "playPosition",
            "gridLight", "gridMedium", "gridDark",
    };

    int background;
    int selection;
    int mono;
    int left;
    int right;
    int overlap;
    int startBar;
    int endBar;
    int playPosition;
    int gridLight;
    int gridMedium;
    int gridDark;

    ViewColors() {
        reset();
    }

    /** index 番目の色の、最初の色 */
    static int defaultOf(int index) {
        return DEFAULTS[index];
    }

    /** すべての色を、最初の色に戻す */
    void reset() {
        for (int i = 0; i < COUNT; i++) {
            set(i, DEFAULTS[i]);
        }
    }

    int get(int index) {
        switch (index) {
            case 0: return background;
            case 1: return selection;
            case 2: return mono;
            case 3: return left;
            case 4: return right;
            case 5: return overlap;
            case 6: return startBar;
            case 7: return endBar;
            case 8: return playPosition;
            case 9: return gridLight;
            case 10: return gridMedium;
            default: return gridDark;
        }
    }

    void set(int index, int color) {
        switch (index) {
            case 0: background = color; break;
            case 1: selection = color; break;
            case 2: mono = color; break;
            case 3: left = color; break;
            case 4: right = color; break;
            case 5: overlap = color; break;
            case 6: startBar = color; break;
            case 7: endBar = color; break;
            case 8: playPosition = color; break;
            case 9: gridLight = color; break;
            case 10: gridMedium = color; break;
            default: gridDark = color; break;
        }
    }
}
