package net.gorry.mase;

import android.app.AlertDialog;
import android.graphics.Typeface;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * 「音の追加」の編集コマンド（音のミックス、音の挿入・追加）。
 * ダイアログで値を受け取り、SoundMixer で音を作って、Editor で音声データに適用する。
 * 仕様は memo/doc/spec.md の「音の追加」。
 */
final class SoundCommands {
    // 足す音の種類
    private static final int KIND_SILENCE = 0;
    private static final int KIND_SINE = 1;
    private static final int KIND_SQUARE = 2;
    private static final int KIND_FILE = 3;

    private static final double MIN_DB = -96.0;

    private final MainActivity activity;
    private final Session session;
    /** ダイアログの部品を借りる */
    private final EditCommands ui;

    // ダイアログで選んだ音声ファイル。ダイアログを閉じたら、捨てる
    private AudioData pickedFile;
    private String pickedName;
    private Uri pickedUri;
    /** 音声ファイルを選び終わったときに、開いているダイアログの表示を直す。ダイアログがなければ null */
    private Runnable onPicked;

    SoundCommands(MainActivity activity, Session session, EditCommands ui) {
        this.activity = activity;
        this.session = session;
        this.ui = ui;
    }

    // ---- 音のミックス ----

    /** 音のミックス：開始位置から終了位置までに、ほかの音を混ぜる */
    void mix() {
        Form form = new Form(false);
        form.show(R.string.cmd_mix_sound, atEnd -> {
            Double baseDb = form.db(form.baseVolume);
            Sound sound = form.sound();
            if (baseDb == null || sound == null) {
                return false;
            }
            double baseGain = SoundMixer.gain(baseDb);
            boolean repeat = form.repeat.isChecked();
            activity.runEdit(p -> {
                AudioData d = session.data;
                int start = session.start;
                int end = session.end;
                int n = end - start;
                p.phase(0f, 0.1f);
                short[][] seg = d.copyRange(start, end);
                short[][] src;
                int srcLength;
                double srcGain;
                boolean loop;
                if (sound.file == null) {
                    p.phase(0.1f, 0.5f);
                    short[] tone = SoundMixer.tone(sound.shape, n, d.sampleRate, sound.hz, sound.gain, p);
                    src = d.channels == 1 ? new short[][]{tone} : new short[][]{tone, tone};
                    srcLength = n;
                    srcGain = 1.0; // 音量は、波形を作るときにかけてある
                    loop = false;
                } else {
                    Prepared file = prepare(sound, d.channels, d.sampleRate, n, p, 0.1f, 0.5f);
                    src = file.samples;
                    srcLength = file.length;
                    srcGain = sound.gain;
                    loop = repeat;
                }
                p.phase(0.5f, 0.7f);
                // 混ぜるのは、L・R のスイッチが ON のチャンネルだけ（OFF のチャンネルは、元の音の音量も変えない）
                SoundMixer.mix(session.targets(seg), baseGain, session.targets(src), srcLength, srcGain, loop, p);
                p.phase(0.7f, 1f);
                return Editor.apply(session, start, n, seg, d.sampleRate, start, end, p);
            });
            form.remember();
            return true;
        });
    }

    // ---- 音の挿入・追加 ----

    /** 音の挿入・追加：開始位置に、ほかの音を割り込ませる。または、音声データの末尾につなげる（ダイアログのボタンで選ぶ） */
    void insert() {
        if (room() < 1) {
            activity.showMessage(R.string.err_no_room);
            return;
        }
        Form form = new Form(true);
        form.show(R.string.cmd_insert_sound, atEnd -> {
            Sound sound = form.sound();
            if (sound == null) {
                return false;
            }
            boolean wholeFile = sound.file != null && form.lengthMode.getSelectedItemPosition() == 0;
            int length = 0;
            if (!wholeFile) {
                Integer samples = form.lengthSamples();
                if (samples == null) {
                    return false;
                }
                length = samples;
            }
            int wanted = length;
            boolean repeat = form.repeat.isChecked();
            activity.runEdit(p -> {
                AudioData d = session.data;
                // 入れる位置：開始位置か、末尾
                int start = atEnd ? d.length : session.start;
                int room = AudioData.maxLength(d.sampleRate) - d.length;
                int n = Math.min(wanted, room);
                boolean truncated = false;
                short[][] seg;
                if (sound.kind == KIND_SILENCE) {
                    seg = new short[d.channels][n];
                } else if (sound.file == null) {
                    p.phase(0f, 0.6f);
                    short[] tone = SoundMixer.tone(sound.shape, n, d.sampleRate, sound.hz, sound.gain, p);
                    // 音声データに入れるので、チャンネルごとに別の配列にする
                    seg = d.channels == 1 ? new short[][]{tone} : new short[][]{tone, tone.clone()};
                } else {
                    // ファイル全体のときは、入る分だけを使う。入りきらなければ、末尾を切り捨てる
                    Prepared file = prepare(sound, d.channels, d.sampleRate, wholeFile ? room : n, p, 0f, 0.5f);
                    if (wholeFile) {
                        n = file.length;
                        truncated = file.fullLength > room;
                    }
                    p.phase(0.5f, 0.7f);
                    seg = SoundMixer.place(file.samples, file.length, d.channels, n, sound.gain,
                            !wholeFile && repeat, p);
                }
                p.phase(0.7f, 1f);
                // 挿入・追加の後は、入れた音を選択範囲にする
                Editor.Result result = Editor.apply(session, start, 0, seg, d.sampleRate, start, (long) start + n, p);
                return result == Editor.Result.OK && truncated ? Editor.Result.TRUNCATED : result;
            });
            form.remember();
            return true;
        });
    }

    /** 今の音声データに、あと何サンプル足せるか */
    private int room() {
        AudioData d = session.data;
        return AudioData.maxLength(d.sampleRate) - d.length;
    }

    // ---- 音声ファイル ----

    /** ファイルを選ぶ画面で、音声ファイルが選ばれた。読み込んで、開いているダイアログに知らせる */
    void onFilePicked(Uri uri, String name) {
        loadFile(uri, name, null);
    }

    /**
     * 音声ファイルを読み込んで、開いているダイアログに知らせる。
     *
     * @param otherwise 読み込めなかったとき・やめたときに続けること。なければ null
     */
    private void loadFile(Uri uri, String name, Runnable otherwise) {
        if (onPicked == null) {
            return; // ダイアログが、もうない
        }
        activity.decode(uri, count -> {
            // 3ch 以上のファイルは、使えない。読み込みをやめる
            activity.runOnUiThread(() -> activity.showMessage(R.string.err_too_many_channels));
            return null;
        }, result -> {
            if (onPicked == null) {
                return;
            }
            pickedFile = result.data;
            pickedName = name;
            pickedUri = uri;
            onPicked.run();
        }, otherwise);
    }

    /** 編集に使える形にした音声ファイル */
    private static final class Prepared {
        /** チャンネル数とサンプリングレートを、音声データに合わせたもの。L と R が同じ配列のことがある */
        short[][] samples;
        /** samples のうち、使える長さ */
        int length;
        /** 長さを切り詰めなかったときの、ファイル全体の長さ（音声データのサンプリングレートで） */
        long fullLength;
    }

    /**
     * 音声ファイルを、音声データのチャンネル数とサンプリングレートに合わせる。先頭から need サンプルまでを作る。
     * 進み具合は、全体の from～to の中で知らせる。
     */
    private static Prepared prepare(Sound sound, int channels, int sampleRate, int need, Progress p,
                                    float from, float to) {
        AudioData f = sound.file;
        float mid = from + (to - from) * 0.2f;
        short[][] in = f.samples;
        int length = f.length;
        p.phase(from, mid);
        if (in.length == 2 && channels == 1) {
            // モノラルにする：L と R の平均。先に行う（変換するデータを減らすため）
            in = SoundMixer.matchChannels(in, length, 1, p);
        }
        Prepared out = new Prepared();
        p.phase(mid, to);
        if (f.sampleRate != sampleRate) {
            if (in[0].length != length) {
                in = f.copyRange(0, length); // 配列が、中身より長いことがある。変換は、配列の長さを見る
            }
            double step = (double) f.sampleRate / sampleRate;
            out.fullLength = Resampler.outputLength(length, step);
            out.length = (int) Math.min(out.fullLength, need);
            in = Resampler.resample(in, step, out.length, sound.algorithm, p);
        } else {
            out.fullLength = length;
            out.length = Math.min(length, need);
        }
        // ステレオにする：L と R に同じ音を入れる
        out.samples = SoundMixer.matchChannels(in, out.length, channels, p);
        return out;
    }

    // ---- ダイアログ ----

    /** ダイアログで決めた、足す音 */
    private static final class Sound {
        int kind;
        int shape;
        double hz;
        double gain = 1.0;
        /** 音声ファイルのとき。そうでなければ null */
        AudioData file;
        int algorithm;
    }

    private interface Action {
        /**
         * @param atEnd 「末尾に追加」のボタンで実行した（音の挿入・追加だけ）。ほかのボタンなら false
         * @return 実行したら true。入力に誤りがあって、ダイアログを開いたままにするなら false
         */
        boolean run(boolean atEnd);
    }

    /** 「音のミックス」「音の挿入・追加」のダイアログの中身 */
    private final class Form {
        final boolean inserting;
        /** 前回の値。最初の値にし、「OK」で実行したときに書き込む */
        final DialogMemory.Sound memory;
        final int[] kinds;
        final LinearLayout box;
        EditText baseVolume;
        Spinner kind;
        LinearLayout toneBox;
        EditText frequency;
        LinearLayout fileBox;
        TextView fileInfo;
        LinearLayout algorithmBox;
        Spinner algorithm;
        LinearLayout volumeBox;
        EditText volume;
        LinearLayout lengthModeBox;
        Spinner lengthMode;
        LinearLayout lengthBox;
        EditText length;
        /** 秒数の行の項目名（音の挿入・追加）。音声ファイルのときは、空にする */
        TextView lengthLabel;
        Switch repeat;

        Form(boolean inserting) {
            this.inserting = inserting;
            memory = inserting ? session.dialogs.insert : session.dialogs.mix;
            kinds = inserting
                    ? new int[]{KIND_SILENCE, KIND_SINE, KIND_SQUARE, KIND_FILE}
                    : new int[]{KIND_SINE, KIND_SQUARE, KIND_FILE};
            box = ui.dialogBox();

            // 先に、部品を作る。並べ方は、ダイアログごとに違う（layoutMix・layoutInsert）
            String[] names = new String[kinds.length];
            for (int i = 0; i < kinds.length; i++) {
                names[i] = activity.getString(kindName(kinds[i]));
            }
            kind = ui.spinner(names);
            frequency = ui.numberField(false);
            frequency.setText(EditCommands.format(memory.hz));

            // 音声ファイル：1 行に、ファイルの表示と「開く」ボタンを並べる。ファイルの表示は、編集画面のデータファイル名と
            // 同じ作り（編集はできない入力欄。長いときは、横にスクロールして全体を見られる）
            LinearLayout fileRow = new LinearLayout(activity);
            fileRow.setOrientation(LinearLayout.HORIZONTAL);
            fileRow.setGravity(Gravity.CENTER_VERTICAL);
            EditText info = new EditText(activity);
            info.setKeyListener(null);
            info.setCursorVisible(false);
            info.setSingleLine(true);
            info.setHorizontallyScrolling(true);
            info.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
            fileInfo = info;
            fileRow.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            Button pick = new Button(activity, null, 0, R.style.CommandButton);
            pick.setText(R.string.cmd_open);
            pick.setOnClickListener(v -> activity.pickSoundFile());
            fileRow.addView(pick, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            // 変換アルゴリズムの置き方（algorithmBox）は、ダイアログごとに違う
            algorithm = ui.algorithmSpinner();
            fileBox = vertical();
            fileBox.addView(fileRow);

            lengthMode = ui.spinner(new String[]{
                    activity.getString(R.string.length_whole_file),
                    activity.getString(R.string.length_specified),
            });
            length = ui.numberField(false);
            length.setText(EditCommands.format(memory.lengthSeconds));
            repeat = new Switch(activity);
            repeat.setText(R.string.sound_repeat);
            repeat.setChecked(memory.repeat);

            if (inserting) {
                layoutInsert();
            } else {
                layoutMix();
            }

            AdapterView.OnItemSelectedListener update = new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    update();
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                }
            };
            // 前回の種類と、長さの決め方を選んでおく
            for (int i = 0; i < kinds.length; i++) {
                if (kinds[i] == memory.kind) {
                    kind.setSelection(i);
                }
            }
            lengthMode.setSelection(memory.lengthMode);
            kind.setOnItemSelectedListener(update);
            lengthMode.setOnItemSelectedListener(update);
            update();
        }

        /**
         * 「音のミックス」の並び。「元の音」と「ミックスする音」の 2 つの見出しの下に、1 段下げて、項目を並べる。
         * 項目は、左に項目名、右に入力する所。項目名の幅をそろえて、入力する所の左端を合わせる。
         */
        private void layoutMix() {
            LevelInput base = ui.levelInput(R.string.label_sound_volume, MIN_DB, 0, memory.baseDb);
            LevelInput sound = ui.levelInput(R.string.label_sound_volume, MIN_DB, 0, memory.volumeDb);
            baseVolume = base.value;
            volume = sound.value;
            TextView kindLabel = ui.label(R.string.label_kind);
            TextView frequencyLabel = ui.label(R.string.label_frequency_name);
            TextView algorithmLabel = ui.label(R.string.label_algorithm_short);
            alignLabels(base.name, sound.name, kindLabel, frequencyLabel, algorithmLabel);
            frequency.setGravity(Gravity.END);

            box.addView(heading(R.string.heading_base_sound));
            LinearLayout baseBody = indented();
            baseBody.addView(base.view);

            box.addView(heading(R.string.label_sound_to_mix));
            LinearLayout body = indented();
            volumeBox = vertical();
            volumeBox.addView(sound.view);
            body.addView(volumeBox);
            body.addView(row(kindLabel, kind, 0));
            toneBox = row(frequencyLabel, frequency, R.string.unit_hz);
            body.addView(toneBox);
            // 音声ファイル：ファイルの行の次に、変換方法の行
            algorithmBox = row(algorithmLabel, algorithm, 0);
            fileBox.addView(algorithmBox);
            body.addView(fileBox);
            body.addView(repeat);

            // 「音の挿入・追加」だけの項目。ここでは、置かない
            lengthModeBox = vertical();
            lengthBox = vertical();
        }

        /**
         * 「音の挿入・追加」の並び。「音のミックス」と同じ形（見出しの下に、1 段下げて、左に項目名、右に入力する所）。
         * 項目は、上から、音量、長さ、種類（ユーザーの指示）。種類を変えると、上の項目（音量、長さ）の行の数が変わるので、
         * 「種類」の位置が動く。
         */
        private void layoutInsert() {
            LevelInput sound = ui.levelInput(R.string.label_sound_volume, MIN_DB, 0, memory.volumeDb);
            volume = sound.value;
            TextView kindLabel = ui.label(R.string.label_kind);
            TextView frequencyLabel = ui.label(R.string.label_frequency_name);
            TextView algorithmLabel = ui.label(R.string.label_algorithm_short);
            TextView lengthModeLabel = ui.label(R.string.label_length_name);
            lengthLabel = ui.label(R.string.label_length_name);
            alignLabels(sound.name, kindLabel, frequencyLabel, algorithmLabel, lengthModeLabel, lengthLabel);
            frequency.setGravity(Gravity.END);
            length.setGravity(Gravity.END);

            box.addView(heading(R.string.label_sound_to_insert));
            LinearLayout body = indented();
            // 上から、音量、長さ、種類（ユーザーの指示）。その下に、種類ごとの項目
            volumeBox = vertical();
            volumeBox.addView(sound.view);
            body.addView(volumeBox);
            // 長さ：音声ファイルのときは、「ファイル全体」か「長さを指定」かを選ぶ行と、その下に秒数の行（項目名は空ける）。
            // ほかのときは、秒数の行だけ（項目名は「長さ」）
            lengthModeBox = row(lengthModeLabel, lengthMode, 0);
            body.addView(lengthModeBox);
            lengthBox = row(lengthLabel, length, R.string.unit_seconds);
            body.addView(lengthBox);
            body.addView(row(kindLabel, kind, 0));
            toneBox = row(frequencyLabel, frequency, R.string.unit_hz);
            body.addView(toneBox);
            // 音声ファイル：ファイルの行の次に、変換方法の行
            algorithmBox = row(algorithmLabel, algorithm, 0);
            fileBox.addView(algorithmBox);
            body.addView(fileBox);
            body.addView(repeat);
        }

        private int dp(int value) {
            return Math.round(value * activity.getResources().getDisplayMetrics().density);
        }

        private LinearLayout vertical() {
            LinearLayout layout = new LinearLayout(activity);
            layout.setOrientation(LinearLayout.VERTICAL);
            return layout;
        }

        /** 見出し（「元の音」など） */
        private TextView heading(int textId) {
            TextView view = ui.label(textId);
            view.setTypeface(Typeface.DEFAULT_BOLD);
            view.setPadding(0, dp(8), 0, 0);
            return view;
        }

        /** 見出しの下の、1 段下げた入れ物を、box に足す */
        private LinearLayout indented() {
            LinearLayout body = vertical();
            body.setPadding(dp(16), 0, 0, 0);
            box.addView(body);
            return body;
        }

        /** 項目名の幅を、いちばん長いものにそろえる（右の入力する所の、左端を合わせるため） */
        private void alignLabels(TextView... labels) {
            EditCommands.alignLabels(dp(8), labels);
        }

        /** 1 行：左に項目名、右に入力する所、その右に単位（unitId が 0 なら、なし） */
        private LinearLayout row(TextView label, View content, int unitId) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(48));
            row.addView(label, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            row.addView(content, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (unitId != 0) {
                TextView unit = ui.label(unitId);
                unit.setPadding(dp(4), 0, 0, 0);
                row.addView(unit, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            return row;
        }

        /**
         * 「OK」で実行したときの値を、次に開いたときのために覚える。選んだ音声ファイルは、場所と名前だけを覚える。
         * 今の種類で出ていない項目（無音のときの音量など）も、数値として正しければ覚える。正しくなければ、前回の値のまま
         */
        void remember() {
            if (pickedFile != null) {
                memory.fileUri = pickedUri;
                memory.fileName = pickedName;
            }
            if (baseVolume != null) {
                memory.baseDb = rememberDb(baseVolume, memory.baseDb);
            }
            memory.volumeDb = rememberDb(volume, memory.volumeDb);
            memory.kind = selectedKind();
            double hz = EditCommands.parse(frequency, Double.NaN);
            if (!Double.isNaN(hz) && !Double.isInfinite(hz) && hz >= 0) {
                memory.hz = hz;
            }
            memory.lengthMode = lengthMode.getSelectedItemPosition();
            double seconds = EditCommands.parse(length, Double.NaN);
            if (!Double.isNaN(seconds) && !Double.isInfinite(seconds) && seconds > 0) {
                memory.lengthSeconds = seconds;
            }
            memory.repeat = repeat.isChecked();
        }

        private double rememberDb(EditText field, double previous) {
            double v = EditCommands.parse(field, Double.NaN);
            return Double.isNaN(v) ? previous : EditCommands.clamp(v, MIN_DB, 0);
        }

        private int selectedKind() {
            return kinds[kind.getSelectedItemPosition()];
        }

        /** 前回の音声ファイルを、まだ読み直していない（ダイアログで、種類が「音声ファイル」になったときに読む） */
        private boolean reloadPending;

        /** 前回の音声ファイルを、読み直す。読めなかったとき・やめたときは、覚えた場所を忘れる（未選択に戻る） */
        private void reload() {
            reloadPending = false;
            loadFile(memory.fileUri, memory.fileName, () -> {
                memory.fileUri = null;
                memory.fileName = null;
            });
        }

        /** 選んだ音の種類などに合わせて、要る項目だけを見せる */
        void update() {
            int k = selectedKind();
            boolean tone = k == KIND_SINE || k == KIND_SQUARE;
            boolean file = k == KIND_FILE;
            // 種類が「音声ファイル」で、前回の音声ファイルを覚えていれば、読み直す（ダイアログが出てから）
            if (file && reloadPending && pickedFile == null && onPicked != null) {
                reload();
            }
            // 長さを自分で決めるのは、ファイル全体を挿入するとき以外
            boolean specified = inserting && (!file || lengthMode.getSelectedItemPosition() == 1);
            show(toneBox, tone);
            show(fileBox, file);
            show(volumeBox, k != KIND_SILENCE);
            show(lengthModeBox, inserting && file);
            show(lengthBox, specified);
            if (lengthLabel != null) {
                // 音声ファイルのときは、上の行に「長さ」の項目名があるので、秒数の行の項目名は空ける
                lengthLabel.setText(file ? "" : activity.getString(R.string.label_length_name));
            }
            // 繰り返しを選べるのは、音声ファイルを、決まった長さの中に入れるとき
            show(repeat, file && (!inserting || specified));
            // 変換アルゴリズムを選ぶのは、サンプリングレートの変換が要るときだけ
            show(algorithmBox, pickedFile != null && pickedFile.sampleRate != session.data.sampleRate);
            String text;
            if (pickedFile == null) {
                text = activity.getString(R.string.file_not_selected);
            } else {
                AudioData f = pickedFile;
                String seconds = String.format(Locale.US, "%.5f", (double) f.length / f.sampleRate);
                String rate = BigDecimal.valueOf(f.sampleRate, 3).stripTrailingZeros().toPlainString();
                text = pickedName + " "
                        + activity.getString(R.string.data_length, rate, f.channels, seconds, f.length);
            }
            if (!text.contentEquals(fileInfo.getText())) {
                fileInfo.setText(text); // 変わったときだけ入れ直す（入れ直すと、スクロールの位置が先頭に戻る）
            }
        }

        private void show(View view, boolean visible) {
            view.setVisibility(visible ? View.VISIBLE : View.GONE);
        }

        /** dB の入力欄の値（-96～0 に収める）。数値でなければ、メッセージを出して null */
        Double db(EditText field) {
            double v = EditCommands.parse(field, Double.NaN);
            if (Double.isNaN(v)) {
                activity.showMessage(R.string.err_bad_volume);
                return null;
            }
            return EditCommands.clamp(v, MIN_DB, 0); // 範囲の外の値は、範囲の端の値で実行する
        }

        /** 入力から、足す音を決める。入力に誤りがあれば、メッセージを出して null */
        Sound sound() {
            Sound sound = new Sound();
            sound.kind = selectedKind();
            if (sound.kind == KIND_SILENCE) {
                return sound;
            }
            if (sound.kind == KIND_FILE) {
                if (pickedFile == null) {
                    activity.showMessage(R.string.err_no_file);
                    return null;
                }
                sound.file = pickedFile;
                sound.algorithm = algorithm.getSelectedItemPosition();
                if (algorithmBox.getVisibility() == View.VISIBLE) {
                    session.dialogs.algorithm = sound.algorithm;
                }
            } else {
                sound.shape = sound.kind == KIND_SQUARE ? SoundMixer.SQUARE : SoundMixer.SINE;
                double hz = EditCommands.parse(frequency, Double.NaN);
                if (Double.isNaN(hz) || Double.isInfinite(hz) || hz < 0) {
                    activity.showMessage(R.string.err_bad_frequency);
                    return null;
                }
                sound.hz = hz;
            }
            Double db = db(volume);
            if (db == null) {
                return null;
            }
            sound.gain = SoundMixer.gain(db);
            return sound;
        }

        /** 長さの入力欄の値を、サンプル数にする。入力に誤りがあれば、メッセージを出して null */
        Integer lengthSamples() {
            int rate = session.data.sampleRate;
            int room = room();
            double seconds = EditCommands.parse(length, Double.NaN);
            double samples = Math.rint(seconds * rate);
            if (Double.isNaN(seconds) || Double.isInfinite(seconds) || samples < 1) {
                activity.showMessage(R.string.err_bad_length);
                return null;
            }
            if (samples > room) {
                // 入る長さを、秒で知らせる（小数 3 桁で、切り捨てる）
                String max = String.format(Locale.US, "%.3f", Math.floor(room * 1000.0 / rate) / 1000.0);
                activity.showMessage(activity.getString(R.string.err_length_over, max));
                return null;
            }
            return (int) samples;
        }

        void show(int titleId, Action action) {
            ScrollView scroll = Scrolling.vertical(activity, box);
            AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                    .setTitle(titleId)
                    .setView(scroll);
            if (inserting) {
                // 音の挿入・追加のボタンは、左から「キャンセル」「開始位置に挿入」「末尾に追加」（ユーザーの指示）。
                // AlertDialog のボタンの並びは、左から neutral、negative、positive なので、それに当てる
                builder.setNeutralButton(R.string.cancel, null)
                        .setNegativeButton(R.string.btn_insert_at_start, null)
                        .setPositiveButton(R.string.cmd_append, null);
            } else {
                builder.setPositiveButton(R.string.ok, null)
                        .setNegativeButton(R.string.cancel, null);
            }
            AlertDialog dialog = builder.create();
            onPicked = this::update;
            dialog.setOnDismissListener(d -> {
                // 読み込んだ音声ファイルの中身は、ダイアログを閉じたら捨てる（実行中の編集は、自分で持っている）
                onPicked = null;
                pickedFile = null;
                pickedName = null;
                pickedUri = null;
            });
            reloadPending = memory.fileUri != null;
            dialog.show();
            update(); // 前回の音声ファイルを、必要なら読み直す
            // 入力に誤りがあるときは、ダイアログを閉じない
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                if (action.run(inserting)) {
                    dialog.dismiss();
                }
            });
            if (inserting) {
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
                    if (action.run(false)) {
                        dialog.dismiss();
                    }
                });
            }
        }
    }

    private static int kindName(int kind) {
        switch (kind) {
            case KIND_SILENCE:
                return R.string.sound_silence;
            case KIND_SINE:
                return R.string.sound_sine;
            case KIND_SQUARE:
                return R.string.sound_square;
            default:
                return R.string.sound_file;
        }
    }
}
