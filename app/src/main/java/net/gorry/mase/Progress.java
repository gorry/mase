package net.gorry.mase;

/**
 * 時間のかかる処理の進み具合と、キャンセルの求め。
 * 処理のスレッドが step で進み具合を知らせ、画面のスレッドが fraction を読んで表示する。
 * 画面のスレッドが cancel を呼ぶと、処理のスレッドは次の step で Cancelled を投げてやめる。
 */
final class Progress {
    /** キャンセルされたので、処理をやめた */
    static final class Cancelled extends RuntimeException {
        Cancelled() {
            super("cancelled", null, false, false);
        }
    }

    /**
     * 確認用。0 より大きいと、step のたびにこの時間（ミリ秒）だけ待って、処理をわざと遅くする。
     * デバッグ版で、起動のときに指定したときだけ 0 以外になる（MainActivity）。
     */
    static volatile int debugDelayMs;

    private volatile boolean cancelRequested;
    private volatile float fraction;
    // 今の段階が、全体のどこからどこまでにあたるか
    private float phaseFrom;
    private float phaseTo = 1f;

    /** 全体の進み具合（0～1） */
    float fraction() {
        return fraction;
    }

    /** キャンセルを求める。画面のスレッドから呼ぶ */
    void cancel() {
        cancelRequested = true;
    }

    boolean cancelRequested() {
        return cancelRequested;
    }

    /** 次の段階を始める。全体の from～to（0～1）にあたる */
    void phase(float from, float to) {
        phaseFrom = from;
        phaseTo = to;
        fraction = from;
        check();
    }

    /** 残り（今の進み具合から最後まで）を、割合で区切った次の段階を始める。from・to は残りの中での 0～1 */
    void phaseOfRest(float restStart, float from, float to) {
        float rest = 1f - restStart;
        phase(restStart + rest * from, restStart + rest * to);
    }

    /** 今の段階の中での進み具合を知らせる。キャンセルを求められていたら、Cancelled を投げる */
    void step(long done, long total) {
        if (cancelRequested) {
            throw new Cancelled();
        }
        if (total > 0) {
            fraction = phaseFrom + (phaseTo - phaseFrom) * Math.min(1f, (float) done / total);
        }
        if (debugDelayMs > 0) {
            try {
                Thread.sleep(debugDelayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** キャンセルを求められていたら、Cancelled を投げる */
    void check() {
        if (cancelRequested) {
            throw new Cancelled();
        }
    }
}
