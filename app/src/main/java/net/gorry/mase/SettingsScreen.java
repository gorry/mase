package net.gorry.mase;

import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 設定画面。色の設定、無音の判定、元に戻せる最大回数。
 * 設定を変えると、すぐに反映して保存する（保存や適用のボタンはない）。
 * 仕様は memo/doc/spec.md の「設定画面」「色の設定」。
 */
final class SettingsScreen {
    /** 色の名前。並びは ViewColors の番号と同じ */
    private static final int[] COLOR_NAMES = {
            R.string.color_background, R.string.color_selection, R.string.color_mono,
            R.string.color_left, R.string.color_right, R.string.color_overlap,
            R.string.color_start_bar, R.string.color_end_bar, R.string.color_play_position,
            R.string.color_grid_light, R.string.color_grid_medium, R.string.color_grid_dark,
    };

    private final MainActivity activity;
    private final Session session;
    private final float density;
    private final View[] swatches = new View[ViewColors.COUNT];
    private EditText silenceMs;
    /** 数値の入力欄の、入力を終えたときの処理。設定画面を閉じるときに、すべて呼ぶ */
    private final List<Runnable> commits = new ArrayList<>();
    private SeekBar silenceBar;
    private EditText silenceValue;
    /** 無音の音量の入力欄を、プログラムから書き換えている間は true（入力として扱わない） */
    private boolean settingSilenceText;

    SettingsScreen(MainActivity activity, Session session) {
        this.activity = activity;
        this.session = session;
        this.density = activity.getResources().getDisplayMetrics().density;
    }

    /** 設定画面を出す。出したダイアログを返す（画面を作り直すときに、閉じられるように） */
    Dialog show() {
        Dialog dialog = new Dialog(activity, R.style.AppTheme);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);

        // 見出しと「閉じる」
        LinearLayout header = row();
        TextView title = new TextView(activity);
        title.setText(R.string.menu_settings);
        title.setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Large);
        title.setPadding(dp(16), dp(8), dp(8), dp(8));
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button close = new Button(activity, null, 0, R.style.CommandButton);
        close.setText(R.string.close);
        close.setOnClickListener(v -> dialog.dismiss());
        header.addView(close);
        root.addView(header);

        ScrollView scroll = new ScrollView(activity);
        Scrolling.keepBars(scroll);
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), 0, dp(16), dp(24));
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        addColors(body);
        addSilence(body);
        addUndo(body);

        // 入力の途中で閉じたときも、入力を反映する
        dialog.setOnDismissListener(d -> {
            for (Runnable commit : commits) {
                commit.run();
            }
        });
        dialog.setContentView(root);
        dialog.show();
        return dialog;
    }

    // ---- 色の設定 ----

    private void addColors(LinearLayout body) {
        body.addView(section(R.string.settings_colors));
        for (int i = 0; i < ViewColors.COUNT; i++) {
            int index = i;
            LinearLayout line = row();
            line.setMinimumHeight(dp(48));
            TextView name = new TextView(activity);
            name.setText(COLOR_NAMES[i]);
            line.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            View swatch = new View(activity);
            swatches[i] = swatch;
            line.addView(swatch, new LinearLayout.LayoutParams(dp(72), dp(32)));
            line.setOnClickListener(v -> showColorPicker(index));
            body.addView(line);
        }
        showSwatches();
        body.addView(resetButton(() -> {
            session.colors.reset();
            showSwatches();
            changed();
        }));
    }

    private void showSwatches() {
        for (int i = 0; i < ViewColors.COUNT; i++) {
            swatches[i].setBackground(swatchDrawable(session.colors.get(i)));
        }
    }

    /** 色の見本。まわりに枠を付ける（背景と同じ色でも、見本の場所が分かるように） */
    private GradientDrawable swatchDrawable(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setStroke(Math.max(1, dp(1)), activity.getColor(R.color.swatch_border));
        return d;
    }

    /** カラーピッカー。R・G・B・A を、スライダーと数値（0～255）で入力する。変えると、すぐに反映する */
    private void showColorPicker(int index) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);

        View sample = new View(activity);
        sample.setBackground(swatchDrawable(session.colors.get(index)));
        box.addView(sample, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        int color = session.colors.get(index);
        int[] values = {Color.red(color), Color.green(color), Color.blue(color), Color.alpha(color)};
        String[] labels = {"R", "G", "B", "A"};
        Runnable apply = () -> {
            int c = Color.argb(values[3], values[0], values[1], values[2]);
            session.colors.set(index, c);
            sample.setBackground(swatchDrawable(c));
            swatches[index].setBackground(swatchDrawable(c));
            changed();
        };
        for (int k = 0; k < 4; k++) {
            int channel = k;
            LinearLayout line = row();
            TextView label = new TextView(activity);
            label.setText(labels[k]);
            label.setTypeface(Typeface.MONOSPACE);
            label.setPadding(0, 0, dp(8), 0);
            line.addView(label);
            SeekBar bar = new SeekBar(activity);
            bar.setMax(255);
            bar.setProgress(values[k]);
            line.addView(bar, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            EditText number = numberField(3);
            number.setText(String.valueOf(values[k]));
            line.addView(number, new LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT));
            box.addView(line);

            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (!fromUser) {
                        return;
                    }
                    values[channel] = progress;
                    String text = String.valueOf(progress);
                    if (!text.contentEquals(number.getText())) {
                        number.setText(text);
                    }
                    apply.run();
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                }
            });
            number.addTextChangedListener(new AfterChange(() -> {
                Integer v = parse(number, 0, 255);
                if (v == null || v == values[channel]) {
                    return;
                }
                values[channel] = v;
                bar.setProgress(v);
                apply.run();
            }));
        }
        new AlertDialog.Builder(activity)
                .setTitle(COLOR_NAMES[index])
                .setView(Scrolling.vertical(activity, box))
                .setPositiveButton(R.string.close, null)
                .show();
    }

    // ---- 無音の判定 ----

    private void addSilence(LinearLayout body) {
        body.addView(section(R.string.settings_silence));
        silenceMs = addNumber(body, R.string.settings_silence_ms, session.silenceMs, 4,
                Settings.MIN_SILENCE_MS, Settings.MAX_SILENCE_MS, v -> session.silenceMs = v);

        // 無音の音量：dB の数値の入力欄（小数も受け付ける）と、1dB きざみのスライダー（左が小さい音、右が大きい音）、
        // プリセットのボタン。値は、最大からどれだけ下かなので、マイナスの dB（-96～0）で表す
        // 並びは、編集コマンドのダイアログの音量と同じ（LevelInput）
        LevelInput input = new LevelInput(activity, activity.getString(R.string.settings_silence_volume),
                "-" + Math.round(Settings.MAX_SILENCE_DB), "0");
        silenceValue = input.value;
        body.addView(input.view);
        // 入力を終えたときに反映する（途中の値は、反映しない）。スライダーのつまみも合わせる。
        // 範囲の外の値や、空のまま入力を終えたときは、今の設定の値に戻す
        commitOnDone(silenceValue, () -> {
            Float db = parseSilence();
            if (db != null && db != session.silenceDb) {
                session.silenceDb = db;
                changed();
            }
            showSilenceDb();
        });

        silenceBar = input.bar;
        silenceBar.setMax(Math.round(Settings.MAX_SILENCE_DB));
        silenceBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    setSilenceDb(Settings.MAX_SILENCE_DB - progress, false); // スライダーからは、整数の dB
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        LinearLayout presets = row();
        presets.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        // 並びは、スライダーの向き（左が小さい音）と同じ、小・中・大
        presets.addView(presetButton(R.string.settings_silence_small, Settings.SILENCE_DB_SMALL));
        presets.addView(presetButton(R.string.settings_silence_medium, Settings.SILENCE_DB_MEDIUM));
        presets.addView(presetButton(R.string.settings_silence_large, Settings.SILENCE_DB_LARGE));
        body.addView(presets);
        showSilenceDb();

        body.addView(resetButton(() -> {
            session.silenceMs = Settings.DEFAULT_SILENCE_MS;
            silenceMs.setText(String.valueOf(session.silenceMs));
            setSilenceDb(Settings.DEFAULT_SILENCE_DB, true);
            changed();
        }));
    }

    private Button presetButton(int textId, float db) {
        Button button = new Button(activity, null, 0, R.style.CommandButton);
        button.setText(textId);
        button.setOnClickListener(v -> setSilenceDb(db, true));
        return button;
    }

    /** 無音の音量を変える（スライダーやボタンから）。moveBar が true のときは、スライダーのつまみも動かす */
    private void setSilenceDb(float db, boolean moveBar) {
        session.silenceDb = Math.max(0f, Math.min(Settings.MAX_SILENCE_DB, db));
        if (moveBar) {
            showSilenceDb();
        } else {
            showSilenceValue();
        }
        changed();
    }

    private void showSilenceDb() {
        silenceBar.setProgress(Math.round(Settings.MAX_SILENCE_DB - session.silenceDb));
        showSilenceValue();
    }

    /** 今の設定の値を、入力欄に表示する。マイナスの dB（0dB が最大）。小数は 4 桁までで、末尾の 0 は付けない */
    private void showSilenceValue() {
        String text = String.format(java.util.Locale.US, "%.4f", -session.silenceDb);
        text = text.replaceAll("0+$", "").replaceAll("\\.$", "");
        if (text.equals("-0")) {
            text = "0";
        }
        settingSilenceText = true;
        silenceValue.setText(text);
        settingSilenceText = false;
    }

    /** 入力欄の値を、設定の値（0～96。最大からどれだけ下か）にする。数値でないとき・範囲の外のときは null */
    private Float parseSilence() {
        try {
            float shown = Float.parseFloat(silenceValue.getText().toString().trim());
            return shown > 0f || shown < -Settings.MAX_SILENCE_DB ? null : Float.valueOf(-shown + 0f);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---- 元に戻す ----

    private void addUndo(LinearLayout body) {
        body.addView(section(R.string.settings_undo));
        addNumber(body, R.string.settings_undo_max, session.undoMax, 2, 0, Settings.MAX_UNDO_MAX, v -> {
            session.undoMax = v;
            Editor.trim(session); // 回数を減らしたときは、越えた分の履歴を、古いものから捨てる
        });
    }

    // ---- 部品 ----

    private interface IntSetter {
        void set(int value);
    }

    /** 名前と、数値の入力欄の 1 行を足す。範囲の中の数値が入っている間は、入力のたびに反映する */
    private EditText addNumber(LinearLayout body, int nameId, int value, int digits, int min, int max,
                               IntSetter setter) {
        LinearLayout line = row();
        line.setMinimumHeight(dp(48));
        TextView name = new TextView(activity);
        name.setText(nameId);
        line.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        EditText field = numberField(digits);
        field.setText(String.valueOf(value));
        line.addView(field, new LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT));
        body.addView(line);
        // 入力を終えたとき（確定のキー、ほかの所へ移ったとき、設定画面を閉じたとき）に反映する。
        // 入力の途中の値（「120」と打つ途中の「12」など）は、反映しない。
        // 範囲の外の値や、空のまま入力を終えたときは、今の設定の値に戻す
        commitOnDone(field, () -> {
            Integer v = parse(field, min, max);
            if (v != null && v != currentOf(nameId)) {
                setter.set(v);
                changed();
            }
            String shown = String.valueOf(currentOf(nameId));
            if (!shown.contentEquals(field.getText())) {
                field.setText(shown);
            }
        });
        return field;
    }

    private int currentOf(int nameId) {
        if (nameId == R.string.settings_silence_ms) {
            return session.silenceMs;
        }
        return session.undoMax;
    }

    /** 入力欄に、入力を終えたときの処理を付ける。設定画面を閉じるときにも、同じ処理を呼ぶ */
    private void commitOnDone(EditText field, Runnable commit) {
        commits.add(commit);
        field.setImeOptions(EditorInfo.IME_ACTION_DONE);
        field.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_DONE || enter) {
                commit.run();
            }
            return false; // キーボードを閉じるなどの、ふつうの動きは、そのまま
        });
        field.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                commit.run();
            }
        });
    }

    /** 入力欄の値。数値でないとき・範囲の外のときは null */
    private static Integer parse(EditText field, int min, int max) {
        try {
            int v = Integer.parseInt(field.getText().toString().trim());
            return v < min || v > max ? null : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 設定が変わった。保存して、画面に反映する */
    private void changed() {
        Settings.save(activity, session);
        activity.refreshAll();
    }

    private TextView section(int titleId) {
        TextView view = new TextView(activity);
        view.setText(titleId);
        view.setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Medium);
        // 「中くらいの文字」の見た目は、文字色が目立たせない色になる。見出しなので、プライマリの文字色にする
        android.content.res.TypedArray a = activity.obtainStyledAttributes(
                new int[]{android.R.attr.textColorPrimary});
        android.content.res.ColorStateList primary = a.getColorStateList(0);
        a.recycle();
        if (primary != null) {
            view.setTextColor(primary);
        }
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setPadding(0, dp(20), 0, dp(4));
        return view;
    }

    private Button resetButton(Runnable action) {
        Button button = new Button(activity, null, 0, R.style.CommandButton);
        button.setText(R.string.settings_reset);
        button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.END;
        button.setLayoutParams(lp);
        return button;
    }

    private LinearLayout row() {
        LinearLayout line = new LinearLayout(activity);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        return line;
    }

    private EditText numberField(int digits) {
        EditText field = new EditText(activity);
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(digits)});
        field.setSingleLine(true);
        field.setGravity(Gravity.END);
        field.setSelectAllOnFocus(true);
        return field;
    }

    private int dp(float value) {
        return Math.round(value * density);
    }

    /** 文字が変わった後に、処理を呼ぶ */
    private static final class AfterChange implements TextWatcher {
        private final Runnable action;

        AfterChange(Runnable action) {
            this.action = action;
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }

        @Override
        public void afterTextChanged(Editable s) {
            action.run();
        }
    }
}
