package net.gorry.mase;

import android.content.Context;
import android.view.View;
import android.widget.ScrollView;

/**
 * スクロールする所の決まり。スクロールバーは、スクロールが止まっても消さない（常に表示する）。
 * 理由：スマートフォンの横画面などで、下が切れて見えないことに、気づきやすくするため（ユーザーの指示）。
 * 仕様は memo/doc/spec.md の「スクロールバー」。
 */
final class Scrolling {
    private Scrolling() {
    }

    /** content を、縦にスクロールできる入れ物に入れて返す。スクロールバーは常に表示する */
    static ScrollView vertical(Context context, View content) {
        ScrollView scroll = new ScrollView(context);
        keepBars(scroll);
        scroll.addView(content);
        return scroll;
    }

    /** スクロールバーを、スクロールが止まっても消さないようにする */
    static void keepBars(View view) {
        view.setScrollbarFadingEnabled(false);
    }
}
