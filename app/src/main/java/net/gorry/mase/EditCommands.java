package net.gorry.mase;

import android.app.AlertDialog;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

/**
 * 編集コマンド。ダイアログで値を受け取り、Editor で音声データに適用する。
 * 仕様は memo/doc/spec.md の「編集コマンドのボタン群」。
 */
final class EditCommands {
    private static final double MIN_DB = -96.0;

    private static final double MAX_SEMITONES = 24.0;

    private final MainActivity activity;
    private final Session session;

    EditCommands(MainActivity activity, Session session) {
        this.activity = activity;
        this.session = session;
    }

    // 音声データを読み書きする処理は、どれも activity.runEdit に渡して、画面とは別のスレッドで実行する。
    // ダイアログで値を受け取るところまでが、画面のスレッドの仕事。

    // ---- 元に戻す・やり直す ----

    void undo() {
        activity.runEdit(p -> Editor.undo(session, p));
    }

    void redo() {
        activity.runEdit(p -> Editor.redo(session, p));
    }

    // ---- 無音区間を選択・発音区間を選択 ----

    /** 無音区間を選択：開始位置を含む無音区間を、選択範囲にする */
    void selectSilence() {
        selectRegion(true);
    }

    /** 発音区間を選択：開始位置を含む発音区間を、選択範囲にする */
    void selectSound() {
        selectRegion(false);
    }

    /** 探した結果：開始位置が、その区間の中になかった */
    private static final int[] NOT_IN_REGION = new int[0];

    private void selectRegion(boolean silence) {
        // 音声データを読むので、別のスレッドで探す。音声データは変えない（未保存にもならない）
        activity.runTask(p -> {
            try {
                AudioData d = session.data;
                // 無音を検出するのは、L・R のスイッチが ON のチャンネルだけ
                SilenceFinder finder = new SilenceFinder(session.targets(d.samples), d.length,
                        SilenceFinder.windowSamples(d.sampleRate, session.silenceMs), session.silenceAmplitude());
                int[] found = silence ? finder.silenceAround(session.start, p) : finder.soundAround(session.start, p);
                return found == null ? NOT_IN_REGION : found;
            } catch (Progress.Cancelled e) {
                return null; // キャンセルされた。選択範囲は変えない
            }
        }, found -> {
            if (found == null) {
                return;
            }
            if (found == NOT_IN_REGION) {
                activity.showMessage(silence ? R.string.err_not_in_silence : R.string.err_not_in_sound);
                return;
            }
            session.setSelection(found[0], found[1]);
            activity.refreshAll();
        });
    }

    // ---- トリム・カット・コピー・貼り付け・挿入 ----

    /** トリム：開始位置より左と、終了位置より右を捨てて、選択範囲だけを残す。コピーバッファは変えない */
    void trim() {
        if (session.start == 0 && session.end == session.data.length) {
            return; // 全体を選んでいるときは、捨てる所がないので、何もしない
        }
        activity.runEdit(p -> {
            AudioData d = session.data;
            short[][] keep = d.copyRange(session.start, session.end);
            p.phase(0.2f, 1f);
            // 音声データ全体を、選択範囲の内容に置き換える。トリムの後は、全体が選択範囲になる
            return Editor.apply(session, 0, d.length, keep, d.sampleRate, 0, keep[0].length, p);
        });
    }

    void copy() {
        activity.runEdit(p -> {
            short[][] clip = session.data.copyRange(session.start, session.end);
            p.check();
            session.clipboard = clip;
            session.clipboardRate = session.data.sampleRate;
            return Editor.Result.OK;
        });
    }

    void cut() {
        if (session.start == 0 && session.end == session.data.length) {
            activity.showMessage(R.string.err_cut_all); // 音声データを空にはできない
            return;
        }
        activity.runEdit(p -> {
            AudioData d = session.data;
            int start = session.start;
            int end = session.end;
            short[][] clip = d.copyRange(start, end);
            int clipRate = d.sampleRate;
            p.phase(0.2f, 1f);
            // カットの後は、つないだ位置から 1 サンプル分を選択範囲にする
            Editor.Result result = Editor.apply(session, start, end - start, new short[d.channels][0],
                    d.sampleRate, start, start + 1, p);
            if (result != Editor.Result.NO_MEMORY) {
                // コピーバッファを書き換えるのは、カットできた後（キャンセルされたときに、変えないため）
                session.clipboard = clip;
                session.clipboardRate = clipRate;
            }
            return result;
        });
    }

    /** 貼り付け：コピーバッファの内容を、開始位置から上書きする。末尾を越える分は、音声データを伸ばす */
    void paste() {
        if (session.clipboard == null) {
            return;
        }
        activity.runEdit(p -> {
            short[][] clip = clipboardForData();
            p.phase(0.2f, 1f);
            AudioData d = session.data;
            int start = session.start;
            int n = clip[0].length;
            int remove = Math.min(n, d.length - start);
            // 加工しないチャンネル（L・R のスイッチが OFF）は、元の音のままにする。末尾を越えて伸びる分は、無音にする
            boolean[] on = targetMask(d.channels);
            for (int c = 0; c < d.channels; c++) {
                if (!on[c]) {
                    java.util.Arrays.fill(clip[c], (short) 0);
                    System.arraycopy(d.samples[c], start, clip[c], 0, remove);
                }
            }
            return Editor.apply(session, start, remove, clip, d.sampleRate, start, (long) start + n, p);
        });
    }

    /** 挿入：コピーバッファの内容を、開始位置に割り込ませる */
    void insert() {
        if (session.clipboard == null) {
            return;
        }
        activity.runEdit(p -> {
            short[][] clip = clipboardForData();
            p.phase(0.2f, 1f);
            AudioData d = session.data;
            int start = session.start;
            return Editor.apply(session, start, 0, clip, d.sampleRate, start,
                    (long) start + clip[0].length, p);
        });
    }

    /** 末尾に追加：コピーバッファの内容を、音声データの末尾につなげる。追加した部分を、選択範囲にする */
    void append() {
        if (session.clipboard == null) {
            return;
        }
        activity.runEdit(p -> {
            short[][] clip = clipboardForData();
            p.phase(0.2f, 1f);
            AudioData d = session.data;
            int end = d.length;
            return Editor.apply(session, end, 0, clip, d.sampleRate, end, (long) end + clip[0].length, p);
        });
    }

    /** コピーバッファの写しを、今の音声データのチャンネル数に合わせて作る。サンプリングレートは合わせない */
    private short[][] clipboardForData() {
        short[][] clip = session.clipboard;
        int channels = session.data.channels;
        int n = clip[0].length;
        short[][] out = new short[channels][n];
        if (clip.length == channels) {
            for (int c = 0; c < channels; c++) {
                System.arraycopy(clip[c], 0, out[c], 0, n);
            }
        } else if (channels == 2) {
            System.arraycopy(clip[0], 0, out[0], 0, n);
            System.arraycopy(clip[0], 0, out[1], 0, n);
        } else {
            for (int i = 0; i < n; i++) {
                out[0][i] = (short) ((clip[0][i] + clip[1][i]) / 2);
            }
        }
        return out;
    }

    // ---- 音量 ----

    /** 倍率の決め方。選択範囲の写しを見て決める。何もしないときは null を返す */
    private interface GainSource {
        GainCurve curve(short[][] segment);
    }

    /** 選択範囲の写しに倍率をかけて、置き換える */
    private void applyGain(GainSource source) {
        activity.runEdit(p -> {
            AudioData d = session.data;
            int start = session.start;
            int end = session.end;
            short[][] seg = d.copyRange(start, end);
            p.phase(0.1f, 0.2f);
            // 加工するのは、L・R のスイッチが ON のチャンネルだけ（OFF のチャンネルは、写しのまま＝元のまま）
            short[][] targets = session.targets(seg);
            GainCurve curve = source.curve(targets);
            if (curve == null) {
                return Editor.Result.OK;
            }
            p.phase(0.2f, 0.6f);
            int n = end - start;
            long total = (long) n * targets.length;
            long done = 0;
            for (short[] ch : targets) {
                double gain = curve.first;
                for (int i = 0; i < n; i++) {
                    if ((i & 0xFFFF) == 0) {
                        p.step(done + i, total);
                    }
                    // 16bit の範囲を越える分は、クリップする
                    double v = ch[i] * gain;
                    ch[i] = (short) Math.max(-32768, Math.min(32767, Math.round(v)));
                    gain *= curve.ratio;
                }
                done += n;
            }
            p.phase(0.6f, 1f);
            return Editor.apply(session, start, n, seg, d.sampleRate, start, end, p);
        });
    }

    /** 先頭の倍率と、1 サンプルごとにかける比。比が 1 なら、一定の倍率 */
    private static final class GainCurve {
        final double first;
        final double ratio;

        GainCurve(double first, double ratio) {
            this.first = first;
            this.ratio = ratio;
        }
    }

    void mute() {
        applyGain(seg -> new GainCurve(0, 1));
    }

    /** 音量の変更：-48～+48dB */
    void volume() {
        showLevelDialog(R.string.cmd_volume, R.string.label_volume, -48.0, 48.0, session.dialogs.volumeDb,
                db -> {
                    session.dialogs.volumeDb = db;
                    applyGain(seg -> new GainCurve(Math.pow(10, db / 20), 1));
                });
    }

    /** 音量の最大化：選択範囲の最大のレベルを、指定のレベル（-96～0dB）にする */
    void normalize() {
        showLevelDialog(R.string.cmd_normalize, R.string.label_normalize, MIN_DB, 0.0, session.dialogs.normalizeDb,
                db -> {
                    session.dialogs.normalizeDb = db;
                    applyGain(seg -> {
                    // ステレオのときは、L と R のうち最大のレベルが大きいほうに合わせる（加工するチャンネルの中で）
                    int peak = 0;
                    for (short[] ch : seg) {
                        for (short v : ch) {
                            int a = Math.abs((int) v);
                            if (a > peak) {
                                peak = a;
                            }
                        }
                    }
                    if (peak == 0) {
                        return null; // すべて無音のときは、何もせずに終わる
                    }
                    return new GainCurve(32767.0 * Math.pow(10, db / 20) / peak, 1);
                    });
                });
    }

    void fadeIn() {
        showFadeDialog(R.string.cmd_fade_in, R.string.label_fade_in, true);
    }

    void fadeOut() {
        showFadeDialog(R.string.cmd_fade_out, R.string.label_fade_out, false);
    }

    /** 位相反転：選択範囲のサンプルの符号を反転する（-32768 は、32767 にする）。ダイアログは出さない */
    void invert() {
        transformSelection((seg, p) -> {
            int n = seg[0].length;
            long total = (long) n * seg.length;
            long done = 0;
            for (short[] ch : seg) {
                for (int i = 0; i < n; i++) {
                    if ((i & 0xFFFF) == 0) {
                        p.step(done + i, total);
                    }
                    ch[i] = (short) Math.min(32767, -ch[i]);
                }
                done += n;
            }
        });
    }

    /** 逆再生：選択範囲のサンプルの並びを、時間の向きに反転する。ダイアログは出さない */
    void reverse() {
        transformSelection((seg, p) -> {
            int n = seg[0].length;
            long total = (long) (n / 2) * seg.length;
            long done = 0;
            for (short[] ch : seg) {
                for (int i = 0, j = n - 1; i < j; i++, j--) {
                    if ((i & 0xFFFF) == 0) {
                        p.step(done + i, total);
                    }
                    short t = ch[i];
                    ch[i] = ch[j];
                    ch[j] = t;
                }
                done += n / 2;
            }
        });
    }

    /**
     * チャンネル入れ替え：選択範囲の L と R を入れ替える。ステレオのときだけ。ダイアログは出さない。
     * L・R のスイッチには関係しない（入れ替えには、両方のチャンネルが要るため）
     */
    void swapChannels() {
        if (session.data.channels != 2) {
            return;
        }
        activity.runEdit(p -> {
            AudioData d = session.data;
            int start = session.start;
            int end = session.end;
            short[][] seg = d.copyRange(start, end);
            p.phase(0.3f, 1f);
            short[][] swapped = {seg[1], seg[0]};
            return Editor.apply(session, start, end - start, swapped, d.sampleRate, start, end, p);
        });
    }

    /** 選択範囲の写しを書き換える処理 */
    private interface SegmentTransform {
        void apply(short[][] segment, Progress p);
    }

    /** 選択範囲の写しを作り、transform で書き換えて、置き換える（長さは変わらない） */
    private void transformSelection(SegmentTransform transform) {
        activity.runEdit(p -> {
            AudioData d = session.data;
            int start = session.start;
            int end = session.end;
            short[][] seg = d.copyRange(start, end);
            p.phase(0.1f, 0.6f);
            transform.apply(session.targets(seg), p); // L・R のスイッチが ON のチャンネルだけ
            p.phase(0.6f, 1f);
            return Editor.apply(session, start, end - start, seg, d.sampleRate, start, end, p);
        });
    }

    private interface DbAction {
        void run(double db);
    }

    /**
     * 音量を dB で指定するダイアログ。最初の値は initial（前回の値）。
     * 数値の入力欄は、小数も受け付ける。スライダーは 1dB きざみで、スライダーから入れた値は、小数点以下が常に 0 になる。
     */
    private void showLevelDialog(int titleId, int labelId, double minDb, double maxDb, double initial,
                                 DbAction action) {
        LinearLayout box = dialogBox();
        EditText value = addLevelInput(box, labelId, minDb, maxDb, initial);

        new AlertDialog.Builder(activity)
                .setTitle(titleId)
                .setView(Scrolling.vertical(activity, box))
                .setPositiveButton(R.string.ok, (d, w) -> {
                    double v = parse(value, Double.NaN);
                    if (Double.isNaN(v)) {
                        return;
                    }
                    action.run(clamp(v, minDb, maxDb)); // 範囲の外の値は、範囲の端の値で実行する
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /**
     * 音量（dB）を入れる所を、box に足す。並びは LevelInput（項目名、数値の入力欄、単位、範囲のラベル、スライダー）。
     * 数値の入力欄は、小数も受け付ける。スライダーは 1 きざみで、スライダーから入れた値は、小数点以下が常に 0 になる。
     *
     * @return 数値の入力欄
     */
    EditText addLevelInput(LinearLayout box, int labelId, double minDb, double maxDb, double initial) {
        LevelInput input = levelInput(labelId, minDb, maxDb, initial);
        box.addView(input.view);
        return input.value;
    }

    /** 音量（dB）を入れる所を作る（どこにも足さない）。数値とスライダーは、つないである */
    LevelInput levelInput(int labelId, double minDb, double maxDb, double initial) {
        LevelInput input = new LevelInput(activity, activity.getString(labelId), format(minDb), format(maxDb));
        connect(input.value, input.bar, minDb, maxDb, initial);
        return input;
    }

    /** 数値の入力欄とスライダーを、つなぐ。スライダーは 1 きざみ */
    private void connect(EditText value, SeekBar bar, double minDb, double maxDb, double initial) {
        value.setText(format(initial));
        bar.setMax((int) Math.round(maxDb - minDb));
        // 数値に合わせて、スライダーのつまみを動かす（小数は、近い整数の位置）
        Runnable showBar = () -> {
            double v = parse(value, Double.NaN);
            if (!Double.isNaN(v)) {
                bar.setProgress((int) Math.round(clamp(v, minDb, maxDb) - minDb));
            }
        };
        showBar.run();
        boolean[] fromBar = {false};
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                fromBar[0] = true;
                value.setText(format(minDb + progress)); // 整数の dB
                fromBar[0] = false;
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        value.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable text) {
                if (!fromBar[0]) {
                    showBar.run();
                }
            }
        });
    }

    /** フェードのダイアログ。レベルを 0～-96dB で指定する */
    private void showFadeDialog(int titleId, int labelId, boolean fadeIn) {
        LinearLayout box = dialogBox();
        DialogMemory memory = session.dialogs;
        EditText value = addLevelInput(box, labelId, MIN_DB, 0, fadeIn ? memory.fadeInDb : memory.fadeOutDb);
        new AlertDialog.Builder(activity)
                .setTitle(titleId)
                .setView(Scrolling.vertical(activity, box))
                .setPositiveButton(R.string.ok, (d, w) -> {
                    double db = parse(value, Double.NaN);
                    if (Double.isNaN(db)) {
                        return;
                    }
                    double level = clamp(db, MIN_DB, 0);
                    if (fadeIn) {
                        memory.fadeInDb = level;
                    } else {
                        memory.fadeOutDb = level;
                    }
                    applyGain(seg -> {
                        // 音量は、dB の値が時間に対してまっすぐ変わるようにする（倍率は、同じ比で変わる）
                        int n = seg[0].length;
                        double first = Math.pow(10, level / 20);
                        double step = n > 1 ? Math.pow(10, -level / 20 / (n - 1)) : 1;
                        return fadeIn ? new GainCurve(first, step) : new GainCurve(1, 1 / step);
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ---- 再生レートの変更 ----

    /** 再生レートの変更：サンプルのデータは変えず、サンプリングレートの値だけを変える */
    void playRate() {
        // 再生できるサンプリングレートを調べるのに時間がかかるので、別のスレッドで調べてからダイアログを出す
        activity.runTask(p -> AudioPlayer.supportedRates(), this::showPlayRateDialog);
    }

    private void showPlayRateDialog(List<Integer> rates) {
        if (!session.hasData()) {
            return;
        }
        AudioData d = session.data;
        String[] items = new String[rates.size()];
        // 最初の選択は、前回「OK」で選んだ値（一覧にないときや、まだ選んでいないときは、今のサンプリングレート）
        int selected = rateIndex(rates, session.dialogs.playRate, d.sampleRate);
        for (int i = 0; i < items.length; i++) {
            items[i] = activity.getString(R.string.rate_item, rates.get(i));
        }
        LinearLayout box = dialogBox();
        TextView current = new TextView(activity);
        current.setText(activity.getString(R.string.current_play_rate, d.sampleRate));
        box.addView(current);
        // 1 行に、左に項目名「変更後」、右にドロップダウン（「サンプリングレートの変更」と同じ形）
        Spinner spinner = spinner(items);
        spinner.setSelection(selected);
        box.addView(labeledRow(label(R.string.label_new_rate), spinner));
        // 変更の後の長さが上限を越えるかどうかを、あらかじめ表示する
        TextView warning = new TextView(activity);
        warning.setText(R.string.warn_too_long);
        warning.setVisibility(View.INVISIBLE);
        box.addView(warning);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                boolean over = d.length > AudioData.maxLength(rates.get(position));
                warning.setVisibility(over ? View.VISIBLE : View.INVISIBLE);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        new AlertDialog.Builder(activity)
                .setTitle(R.string.cmd_play_rate)
                .setView(Scrolling.vertical(activity, box))
                .setPositiveButton(R.string.ok, (dlg, w) -> {
                    int rate = rates.get(spinner.getSelectedItemPosition());
                    session.dialogs.playRate = rate;
                    if (rate == d.sampleRate) {
                        return; // 変わらないときは、何もせずに終わる
                    }
                    session.unplayableNotified = false;
                    activity.runEdit(p -> Editor.apply(session, 0, 0,
                            new short[session.data.channels][0], rate, session.start, session.end, p));
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** チャンネルごとに、加工するなら true */
    private boolean[] targetMask(int channels) {
        boolean[] on = new boolean[channels];
        for (int c : session.targetChannels()) {
            on[c] = true;
        }
        return on;
    }

    /** 一覧の中で、want の位置。ないとき（want が 0 のときも）は、current の位置。それもなければ 0 */
    private static int rateIndex(List<Integer> rates, int want, int current) {
        int at = rates.indexOf(want);
        if (at < 0) {
            at = rates.indexOf(current);
        }
        return Math.max(0, at);
    }

    // ---- サンプリングレートの変更 ----

    /** サンプリングレートの変更：音程と長さ（秒）を変えずに、サンプリングレートを変える */
    void resample() {
        activity.runTask(p -> AudioPlayer.supportedRates(), this::showResampleDialog);
    }

    private void showResampleDialog(List<Integer> rates) {
        if (!session.hasData()) {
            return;
        }
        AudioData d = session.data;
        String[] items = new String[rates.size()];
        int selected = rateIndex(rates, session.dialogs.resampleRate, d.sampleRate);
        for (int i = 0; i < items.length; i++) {
            items[i] = activity.getString(R.string.rate_item, rates.get(i));
        }
        LinearLayout box = dialogBox();
        TextView current = new TextView(activity);
        current.setText(activity.getString(R.string.current_rate, d.sampleRate));
        box.addView(current);
        // 1 行に、左に項目名「変更後」、右にドロップダウン。「変換方法」の行と、入力する所の左端をそろえる
        Spinner rateSpinner = spinner(items);
        rateSpinner.setSelection(selected);
        TextView rateName = label(R.string.label_new_rate);
        box.addView(labeledRow(rateName, rateSpinner));
        Spinner algorithm = algorithmSpinner(box, rateName);
        TextView warning = warningView(box);
        rateSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                int rate = rates.get(position);
                long length = Resampler.outputLength(d.length, (double) d.sampleRate / rate);
                warning.setVisibility(length > AudioData.maxLength(rate) ? View.VISIBLE : View.INVISIBLE);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        new AlertDialog.Builder(activity)
                .setTitle(R.string.cmd_resample)
                .setView(Scrolling.vertical(activity, box))
                .setPositiveButton(R.string.ok, (dlg, w) -> {
                    int rate = rates.get(rateSpinner.getSelectedItemPosition());
                    session.dialogs.resampleRate = rate;
                    int alg = algorithm.getSelectedItemPosition();
                    session.dialogs.algorithm = alg;
                    if (rate == d.sampleRate) {
                        return; // 変わらないときは、何もせずに終わる
                    }
                    session.unplayableNotified = false;
                    activity.runEdit(p -> convert(p, (double) session.data.sampleRate / rate, rate, alg));
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /**
     * 音声データ全体を、サンプリングレート変換で伸び縮みさせて置き換える。
     * 出力の 1 サンプルが、入力の step サンプルにあたる。開始位置・終了位置は、伸び縮みに合わせる。
     */
    private Editor.Result convert(Progress p, double step, int rate, int algorithm) {
        AudioData d = session.data;
        long want = Resampler.outputLength(d.length, step);
        // 長さの上限を越える分は、はじめから作らない
        int length = (int) Math.min(want, AudioData.maxLength(rate));
        p.phase(0f, 0.7f);
        short[][] seg = Resampler.resample(d.samples, step, length, algorithm, p);
        p.phase(0.7f, 1f);
        Editor.Result result = Editor.apply(session, 0, d.length, seg, rate,
                Math.round(session.start / step), Math.round(session.end / step), p);
        return result == Editor.Result.OK && want > length ? Editor.Result.TRUNCATED : result;
    }

    // ---- 音程の変更 ----

    /** 音程の変更：サンプリングレートを変えずに、音程を変える。-24～+24（1 で半音） */
    void pitch() {
        AudioData d = session.data;
        LinearLayout box = dialogBox();
        // 並びは、音量を入れる部品と同じ（項目名、入力欄、単位「半音」。その下に、範囲の値とスライダー）
        LevelInput input = new LevelInput(activity, activity.getString(R.string.label_pitch),
                format(-MAX_SEMITONES), format(MAX_SEMITONES), activity.getString(R.string.unit_semitones));
        DialogMemory memory = session.dialogs;
        connect(input.value, input.bar, -MAX_SEMITONES, MAX_SEMITONES, memory.pitchSemitones);
        EditText value = input.value;
        box.addView(input.view);
        // 「12 で 1 オクターブ」の説明は、スライダーの下に、右寄せで出す
        TextView note = label(R.string.note_pitch);
        note.setGravity(Gravity.END);
        note.setPadding(0, 0, 0, Math.round(8 * activity.getResources().getDisplayMetrics().density));
        box.addView(note);
        // 変換方法の行の、入力する所の左端を、音程差の入力欄とそろえる
        Spinner algorithm = algorithmSpinner(box, input.name);
        Switch keep = new Switch(activity);
        keep.setText(R.string.keep_duration);
        keep.setChecked(memory.pitchKeepDuration);
        box.addView(keep);
        TextView warning = warningView(box);
        // 変更の後の長さが上限を越えるかどうかを、あらかじめ表示する（再生時間を維持するときは、長さが変わらない）
        Runnable update = () -> {
            double semitones = parse(value, 0);
            double step = pitchRatio(clamp(semitones, -MAX_SEMITONES, MAX_SEMITONES));
            boolean over = !keep.isChecked()
                    && Resampler.outputLength(d.length, step) > AudioData.maxLength(d.sampleRate);
            warning.setVisibility(over ? View.VISIBLE : View.INVISIBLE);
        };
        value.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable text) {
                update.run();
            }
        });
        keep.setOnCheckedChangeListener((button, checked) -> update.run());
        update.run(); // 前回の値で、上限を越えるかどうかを、はじめから表示する
        new AlertDialog.Builder(activity)
                .setTitle(R.string.cmd_pitch)
                .setView(Scrolling.vertical(activity, box))
                .setPositiveButton(R.string.ok, (dlg, w) -> {
                    double v = parse(value, Double.NaN);
                    int alg = algorithm.getSelectedItemPosition();
                    session.dialogs.algorithm = alg;
                    memory.pitchKeepDuration = keep.isChecked();
                    if (!Double.isNaN(v)) {
                        memory.pitchSemitones = clamp(v, -MAX_SEMITONES, MAX_SEMITONES);
                    }
                    if (Double.isNaN(v) || v == 0) {
                        return; // 変わらないときは、何もせずに終わる
                    }
                    double ratio = pitchRatio(clamp(v, -MAX_SEMITONES, MAX_SEMITONES));
                    boolean keepDuration = keep.isChecked();
                    activity.runEdit(p -> keepDuration
                            ? pitchKeepingDuration(p, ratio, alg)
                            : convert(p, ratio, session.data.sampleRate, alg));
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** 半音の数から、周波数の比にする（12 で 2 倍） */
    private static double pitchRatio(double semitones) {
        return Math.pow(2, semitones / 12);
    }

    /**
     * 長さを変えずに、音程を ratio 倍にする。タイムストレッチ（WSOLA）と、サンプリングレート変換を組み合わせる。
     * 途中のデータが元より長くならないよう、短くするほうを先に行う。
     */
    private Editor.Result pitchKeepingDuration(Progress p, double ratio, int algorithm) {
        AudioData d = session.data;
        int n = d.length;
        // 長さが変わらないので、L・R のスイッチが ON のチャンネルだけを加工する
        int[] picked = session.targetChannels();
        short[][] in = session.targets(d.samples);
        short[][] seg;
        if (ratio > 1) {
            // 音程を上げる：先にサンプリングレート変換で短くして（音程が上がる）、タイムストレッチで元の長さに戻す
            int shorter = (int) Resampler.outputLength(n, ratio);
            p.phase(0f, 0.3f);
            short[][] mid = Resampler.resample(in, ratio, shorter, algorithm, p);
            p.phase(0.3f, 0.7f);
            seg = Resampler.stretch(mid, d.sampleRate, n, p);
        } else {
            // 音程を下げる：先にタイムストレッチで短くして、サンプリングレート変換で元の長さに戻す（音程が下がる）
            int shorter = (int) Math.max(1, Math.round(n * ratio));
            p.phase(0f, 0.3f);
            short[][] mid = Resampler.stretch(in, d.sampleRate, shorter, p);
            p.phase(0.3f, 0.7f);
            seg = Resampler.resample(mid, (double) shorter / n, n, algorithm, p);
        }
        p.phase(0.7f, 1f);
        if (picked.length != d.channels) {
            // 加工しないチャンネルは、元の音の写しを入れる
            short[][] all = d.copyRange(0, n);
            for (int i = 0; i < picked.length; i++) {
                all[picked[i]] = seg[i];
            }
            seg = all;
        }
        return Editor.apply(session, 0, n, seg, d.sampleRate, session.start, session.end, p);
    }

    /** サンプリングレート変換アルゴリズムのドロップダウンを、box に足す */
    Spinner algorithmSpinner(LinearLayout box) {
        return algorithmSpinner(box, null);
    }

    /**
     * @param alignWith この項目名と、「変換方法」の項目名の幅をそろえる（入力する所の左端を合わせる）。なければ null
     */
    Spinner algorithmSpinner(LinearLayout box, TextView alignWith) {
        // 1 行に、左に項目名「変換方法」、右にドロップダウン（「音の追加」のダイアログと同じ形）
        TextView name = label(R.string.label_algorithm_short);
        Spinner spinner = algorithmSpinner();
        box.addView(labeledRow(name, spinner));
        if (alignWith != null) {
            alignLabels(dp(8), name, alignWith);
        }
        return spinner;
    }

    /** 1 行：左に項目名、右に入力する所（残りの幅を使う） */
    LinearLayout labeledRow(TextView name, View content) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(48));
        name.setPadding(0, 0, dp(8), 0);
        row.addView(name, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(content, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    /**
     * 項目名の幅を、いちばん長いものにそろえる（右の入力する所の、左端を合わせるため）。
     *
     * @param gap 項目名と、入力する所の間（ピクセル）
     */
    static void alignLabels(int gap, TextView... labels) {
        float widest = 0;
        for (TextView label : labels) {
            widest = Math.max(widest, label.getPaint().measureText(label.getText().toString()));
        }
        int width = (int) Math.ceil(widest) + gap;
        for (TextView label : labels) {
            label.setPadding(0, 0, gap, 0);
            label.setMinWidth(width);
        }
    }

    /** サンプリングレート変換アルゴリズムのドロップダウンを作る（どこにも足さない）。最初の選択は、最後に選んだもの */
    Spinner algorithmSpinner() {
        Spinner spinner = spinner(activity.getResources().getStringArray(R.array.algorithms));
        spinner.setSelection(session.dialogs.algorithm);
        return spinner;
    }

    /** 「長さの上限を越える」の表示を、box に足す。最初は見えない */
    private TextView warningView(LinearLayout box) {
        TextView warning = new TextView(activity);
        warning.setText(R.string.warn_too_long);
        warning.setVisibility(View.INVISIBLE);
        box.addView(warning);
        return warning;
    }

    // ---- チャンネル数の変更 ----

    void channels() {
        if (session.data.channels == 1) {
            // モノラルからステレオ：L と R に、元の音をそのままコピーする。確認はしない
            activity.runEdit(p -> {
                AudioData d = session.data;
                short[][] seg = new short[2][];
                seg[0] = d.copyRange(0, d.length)[0];
                p.phase(0.15f, 0.3f);
                seg[1] = d.copyRange(0, d.length)[0];
                p.phase(0.3f, 1f);
                return Editor.apply(session, 0, d.length, seg, d.sampleRate, session.start, session.end, p);
            });
            return;
        }
        String[] items = {
                activity.getString(R.string.mono_left),
                activity.getString(R.string.mono_right),
                activity.getString(R.string.mono_mix),
        };
        int[] choice = {session.dialogs.monoChoice}; // 最初の選択は、前回の選択（起動したときは「LR 合成」）
        android.widget.ListView list = new AlertDialog.Builder(activity)
                .setTitle(R.string.mono_title)
                .setSingleChoiceItems(items, choice[0], (dlg, which) -> choice[0] = which)
                .setPositiveButton(R.string.ok, (dlg, w) -> {
                    session.dialogs.monoChoice = choice[0];
                    activity.runEdit(p -> {
                        AudioData d = session.data;
                        short[][] seg = new short[1][d.length];
                        short[] l = d.samples[0];
                        short[] r = d.samples[1];
                        short[] out = seg[0];
                        switch (choice[0]) {
                            case 0:
                                System.arraycopy(l, 0, out, 0, d.length);
                                break;
                            case 1:
                                System.arraycopy(r, 0, out, 0, d.length);
                                break;
                            default:
                                p.phase(0f, 0.4f);
                                for (int i = 0; i < d.length; i++) {
                                    if ((i & 0xFFFF) == 0) {
                                        p.step(i, d.length);
                                    }
                                    out[i] = (short) ((l[i] + r[i]) / 2); // 足して 2 で割る
                                }
                                break;
                        }
                        p.phase(0.4f, 1f);
                        return Editor.apply(session, 0, d.length, seg, d.sampleRate, session.start, session.end, p);
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show()
                .getListView();
        Scrolling.keepBars(list);
    }

    // ---- ダイアログの部品 ----

    LinearLayout dialogBox() {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * activity.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        return box;
    }

    TextView label(int textId) {
        TextView view = new TextView(activity);
        view.setText(textId);
        return view;
    }

    EditText numberField(boolean signed) {
        EditText field = new EditText(activity);
        int type = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL;
        if (signed) {
            type |= InputType.TYPE_NUMBER_FLAG_SIGNED;
        }
        field.setInputType(type);
        field.setSingleLine(true);
        field.setSelectAllOnFocus(true);
        return field;
    }

    Spinner spinner(String[] items) {
        Spinner spinner = new Spinner(activity);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        return spinner;
    }

    static double parse(EditText field, double fallback) {
        try {
            return Double.parseDouble(field.getText().toString().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 小数は 4 桁まで。末尾の 0 は付けない */
    static String format(double v) {
        String s = String.format(Locale.US, "%.4f", v);
        if (s.contains(".")) {
            s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return s.equals("-0") ? "0" : s;
    }

    static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
