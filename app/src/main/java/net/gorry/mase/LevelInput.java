package net.gorry.mase;

import android.content.Context;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * 音量（dB）などの数値を、入力欄とスライダーで入れる部品の並び。数値とスライダーのつなぎ方は、使う側が決める。
 * <pre>
 * 項目名 [   数値の入力欄   ] dB
 *        最小 [スライダー] 最大
 * </pre>
 * 「入力欄＋単位」と「範囲のラベル＋スライダー」は、同じ幅にして、縦に重ねる。
 * 仕様は memo/doc/spec.md の「音量を入れる部品」。
 */
final class LevelInput {
    /** 全体。これを、ダイアログに足す */
    final LinearLayout view;
    /** 項目名。幅をそろえたいときに、使う側が幅を決める */
    final TextView name;
    /** 数値の入力欄。小数と、負の数を受け付ける */
    final EditText value;
    /** スライダー。きざみと範囲は、使う側が決める */
    final SeekBar bar;

    /**
     * @param label   項目名
     * @param minText スライダーの左に出す、範囲の小さい側の値
     * @param maxText スライダーの右に出す、範囲の大きい側の値
     */
    LevelInput(Context context, CharSequence label, CharSequence minText, CharSequence maxText) {
        this(context, label, minText, maxText, context.getString(R.string.unit_db));
    }

    /**
     * @param unitText 入力欄の右に出す単位（「dB」「半音」など）
     */
    LevelInput(Context context, CharSequence label, CharSequence minText, CharSequence maxText,
               CharSequence unitText) {
        float density = context.getResources().getDisplayMetrics().density;
        int rowHeight = Math.round(48 * density);
        int gap = Math.round(8 * density);

        view = new LinearLayout(context);
        view.setOrientation(LinearLayout.HORIZONTAL);

        // 項目名。入力欄の行と、高さをそろえる
        name = new TextView(context);
        name.setText(label);
        name.setGravity(Gravity.CENTER_VERTICAL);
        name.setMinHeight(rowHeight);
        name.setPadding(0, 0, gap, 0);
        view.addView(name, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 項目名の右の残りを、「入力欄＋単位」と「範囲のラベル＋スライダー」が、同じ幅で使う
        LinearLayout group = new LinearLayout(context);
        group.setOrientation(LinearLayout.VERTICAL);
        view.addView(group, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout valueRow = new LinearLayout(context);
        valueRow.setOrientation(LinearLayout.HORIZONTAL);
        valueRow.setGravity(Gravity.CENTER_VERTICAL);
        valueRow.setMinimumHeight(rowHeight);
        value = new EditText(context);
        value.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
                | InputType.TYPE_NUMBER_FLAG_SIGNED);
        value.setSingleLine(true);
        value.setGravity(Gravity.END);
        value.setSelectAllOnFocus(true);
        valueRow.addView(value, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView unit = new TextView(context);
        unit.setText(unitText);
        unit.setPadding(gap / 2, 0, 0, 0);
        valueRow.addView(unit, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        group.addView(valueRow);

        LinearLayout barRow = new LinearLayout(context);
        barRow.setOrientation(LinearLayout.HORIZONTAL);
        barRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView min = new TextView(context);
        min.setText(minText);
        barRow.addView(min, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        bar = new SeekBar(context);
        barRow.addView(bar, new LinearLayout.LayoutParams(0, rowHeight, 1f));
        TextView max = new TextView(context);
        max.setText(maxText);
        barRow.addView(max, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        group.addView(barRow);
    }
}
