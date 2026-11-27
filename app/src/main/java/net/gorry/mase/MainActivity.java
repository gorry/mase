package net.gorry.mase;

import android.content.ActivityNotFoundException;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.content.res.Configuration;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.graphics.Typeface;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.ToggleButton;
import android.window.OnBackInvokedDispatcher;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 編集画面。仕様は memo/doc/spec.md の「画面」。
 */
public final class MainActivity extends Activity implements WaveformView.Host, AudioPlayer.Listener {
    private static final String TAG = "mase.MainActivity";
    private static final int REQUEST_OPEN = 1;
    /** 「音の追加」で使う音声ファイルを選ぶ（2・3 は SaveCommands が使う） */
    private static final int REQUEST_PICK_SOUND = 4;

    /** 編集中の状態。画面が作り直されても残す */
    private static final Session session = new Session();

    private TextView fileName;
    private SliderView zoomV;
    private WaveformView wave;
    private ScrollBarView scrollBar;
    private PlayView playView;
    private PositionRow startRow;
    private PositionRow endRow;
    private Button openButton;
    private Button undoButton;
    private Button redoButton;
    private Button pasteButton;
    private Button insertButton;
    private final EditCommands commands = new EditCommands(this, session);
    private final SaveCommands saver = new SaveCommands(this, session);
    private final SoundCommands sounds = new SoundCommands(this, session, commands);
    private ToggleButton repeatButton;
    /** 末尾に追加。コピーバッファがあるときだけ押せる */
    private Button appendButton;
    /** チャンネル入れ替え。ステレオのときだけ押せる */
    private Button swapButton;
    /** 加工するチャンネルのスイッチ（ファイル名の右） */
    private Button leftButton;
    private Button rightButton;

    private final AudioPlayer player = new AudioPlayer(this);
    private final Handler handler = new Handler(Looper.getMainLooper());
    /** 再生中に、再生位置の表示を進める */
    private final Runnable playTicker = new Runnable() {
        @Override
        public void run() {
            if (!session.playing) {
                return;
            }
            int position = player.currentPosition();
            if (position >= 0) {
                session.playPosition = position;
                wave.invalidate();
                playView.invalidate();
            }
            handler.postDelayed(this, 33);
        }
    };
    /** 音声データを読み込んでいるときだけ使えるもの（まだ実装していないコマンドは、入れない） */
    private final List<View> needData = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Settings.load(this, session); // 保存してある設定（色、無音の判定、元に戻せる最大回数）
        if (BuildConfig.DEBUG) {
            // 確認用：adb shell am start -n net.gorry.mase.debug/net.gorry.mase.MainActivity --ei debug_slow_ms 1000 で、編集をわざと遅くする
            // （デバッグ用のアプリ ID は、Profile.ini の [AppId] DebugSuffix が付いたもの）
            Progress.debugDelayMs = getIntent().getIntExtra("debug_slow_ms", 0);
        }
        openGroups.add(R.string.group_file); // 起動したときは、「ファイル」だけを開いておく
        buildUi();

        // 戻るボタンでの終了。未保存の音声データがあるときは、保存するかを確かめる
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, () -> {
                    if (editing) {
                        return; // 編集を実行している間は、終了しない
                    }
                    stopPlayback();
                    confirmUnsaved(R.string.save_and_exit, R.string.exit_without_saving, this::exit);
                });
    }

    /** 画面の向きが変わったら、その向きの並びで、画面を作り直す。音声データや編集中の状態は、そのまま */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        int night = newConfig.uiMode & Configuration.UI_MODE_NIGHT_MASK;
        if (night != builtNightMode) {
            // ライトモード・ダークモードが切り替わった。テーマの色を取り直すために、画面を作り直す。
            // 編集を実行している間は、終わるまで待つ（runTask の終わりで作り直す）
            if (editing) {
                recreatePending = true;
            } else {
                recreate();
            }
            return;
        }
        // 向きのほか、画面の大きさ（dp）や表示の密度が変わったときも、並びを作り直す
        // （横幅で決まる大きさや、px に直した長さが変わるため。画面の分割や、端末の表示サイズの設定の変更など）
        if (newConfig.orientation != builtOrientation || newConfig.screenWidthDp != builtWidthDp
                || newConfig.screenHeightDp != builtHeightDp || newConfig.densityDpi != builtDensity) {
            buildUi();
        }
    }

    /** 今の画面の並びを作ったときの、画面の大きさ（dp）と表示の密度 */
    private int builtWidthDp;
    private int builtHeightDp;
    private int builtDensity;

    /** 出している設定画面。出していなければ null か、閉じた後のもの */
    private Dialog settingsDialog;

    @Override
    protected void onDestroy() {
        // 画面（Activity）がなくなるとき（ライトモード・ダークモードの切り替えで作り直すときなど）に、設定画面を閉じる
        if (settingsDialog != null && settingsDialog.isShowing()) {
            settingsDialog.dismiss();
        }
        super.onDestroy();
    }

    /** 今の画面を作ったときの、ライトモード・ダークモードの別 */
    private int builtNightMode;
    /** 編集が終わったら、画面（Activity）を作り直す */
    private boolean recreatePending;

    /** 今の画面の並びを作ったときの向き */
    private int builtOrientation = Configuration.ORIENTATION_UNDEFINED;
    /** 開いているグループ（見出しの文言の ID）。画面を作り直しても、開いたままにする */
    private final Set<Integer> openGroups = new HashSet<>();

    /**
     * 画面を組み立てる。起動したときと、画面の向きが変わったときに呼ぶ。
     * 並びは、今の向きのもの（縦向きは layout、横向きは layout-land）になる。
     */
    private void buildUi() {
        builtOrientation = getResources().getConfiguration().orientation;
        builtWidthDp = getResources().getConfiguration().screenWidthDp;
        builtHeightDp = getResources().getConfiguration().screenHeightDp;
        builtDensity = getResources().getConfiguration().densityDpi;
        builtNightMode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        needData.clear();
        setContentView(R.layout.activity_main);

        fileName = findViewById(R.id.file_name);
        // 編集はできない（キーボードも出さない）。横にスクロールして、全体を見るためだけの入力欄
        fileName.setKeyListener(null);
        if (fileName instanceof EditText) {
            ((EditText) fileName).setShowSoftInputOnFocus(false);
        }
        zoomV = findViewById(R.id.zoom_v);
        wave = findViewById(R.id.wave);
        scrollBar = findViewById(R.id.scroll_bar);
        playView = findViewById(R.id.play_view);

        applySizes();

        wave.bind(session, this);
        playView.bind(session, this);
        startRow = new PositionRow(findViewById(R.id.row_start), true, session, this::onPositionsChanged);
        endRow = new PositionRow(findViewById(R.id.row_end), false, session, this::onPositionsChanged);

        zoomV.setListener(value -> {
            session.zoomV = value;
            wave.invalidate();
        });
        scrollBar.setListener(position -> {
            session.scroll = position;
            wave.clampScroll();
            wave.invalidate();
        });

        findViewById(R.id.menu_button).setOnClickListener(this::showMenu);
        buildCommandGroups();

        // 再生ビューのボタン
        View playButton = findViewById(R.id.play_button);
        View stopButton = findViewById(R.id.stop_button);
        View rewindButton = findViewById(R.id.rewind_button);
        repeatButton = findViewById(R.id.repeat_button);
        leftButton = findViewById(R.id.channel_left);
        rightButton = findViewById(R.id.channel_right);
        leftButton.setOnClickListener(v -> toggleChannel(true));
        rightButton.setOnClickListener(v -> toggleChannel(false));
        // 再生・停止・巻き戻しのボタンは、離したときではなく、押したときに動く
        fireOnPress(playButton, this::startPlayback);
        fireOnPress(stopButton, this::stopPlayback);
        fireOnPress(rewindButton, this::rewind);
        repeatButton.setChecked(session.repeat);
        showRepeatState();
        repeatButton.setOnCheckedChangeListener((button, checked) -> {
            session.repeat = checked;
            showRepeatState();
            player.setRepeat(checked);
            applyRangeToPlayer();
        });
        needData.add(playButton);
        needData.add(stopButton);
        needData.add(rewindButton);
        needData.add(repeatButton);

        refreshAll();
    }

    /** アプリを終了する。編集中の状態は捨てる */
    private void exit() {
        session.close();
        finish();
    }

    /**
     * 未保存の音声データがあるときに、保存するかを確かめてから proceed に進む。なければ、すぐ進む。
     * 「保存して…」は「保存」ボタンと同じ動きで、正常に保存できたときだけ進む。
     */
    private void confirmUnsaved(int saveLabel, int withoutSavingLabel, Runnable proceed) {
        if (!session.hasData() || !session.dirty) {
            proceed.run();
            return;
        }
        new AlertDialog.Builder(this)
                .setMessage(R.string.ask_unsaved)
                .setPositiveButton(saveLabel, (d, w) -> saver.save(proceed))
                .setNegativeButton(withoutSavingLabel, (d, w) -> proceed.run())
                .setNeutralButton(R.string.cancel, null)
                .show();
    }

    /**
     * ボタンを、指を離したときではなく、押したときに動くようにする。
     * 指で触る以外の押し方（キーボードや読み上げの操作）では、ふつうのボタンと同じに動く。
     */
    private void fireOnPress(View button, Runnable action) {
        boolean[] firedByTouch = {false};
        button.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (v.isEnabled()) {
                        firedByTouch[0] = true;
                        action.run();
                    }
                    break;
                case MotionEvent.ACTION_UP:
                    // ボタンの外で離したときは、クリックにならないので、ここで印を消す
                    if (event.getX() < 0 || event.getY() < 0
                            || event.getX() > v.getWidth() || event.getY() > v.getHeight()) {
                        firedByTouch[0] = false;
                    }
                    break;
                case MotionEvent.ACTION_CANCEL:
                    firedByTouch[0] = false;
                    break;
                default:
                    break;
            }
            return false; // 押した見た目などは、ボタンに任せる
        });
        button.setOnClickListener(v -> {
            if (firedByTouch[0]) {
                firedByTouch[0] = false; // 押したときに動かしたので、離したときには動かさない
                return;
            }
            action.run();
        });
    }

    /** ビューの大きさを決める。値は memo/doc/spec.md の「大きさ」（最初の案） */
    private void applySizes() {
        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        if (!landscape) {
            // 縦向き：画面の下に、画面の横幅と同じ高さの領域を取る（再生ビュー、開始位置・終了位置の操作、編集コマンド）。
            // 波形ビューは、その上の残りの高さを使う（レイアウトで決まる）。横向きも、波形ビューは残りの高さ
            int screenWidth = getWindowManager().getCurrentWindowMetrics().getBounds().width();
            View bottom = findViewById(R.id.bottom_area);
            ViewGroup.LayoutParams lp = bottom.getLayoutParams();
            lp.height = screenWidth;
            bottom.setLayoutParams(lp);
        }
        // バーをつかめる範囲は、波形ビューの高さから決める。再生ビューの高さは、レイアウトで 24dp に固定してある
        wave.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int waveHeight = b - t;
            if (waveHeight <= 0 || waveHeight == ob - ot) {
                return;
            }
            // バーをつかめる範囲は、バーの左右それぞれに、波形ビューの三角形の高さ（波形ビューの高さの 1/24）の分
            playView.setGrabReach(waveHeight / 24f);
        });
    }

    // ---- 編集コマンドのボタン群 ----

    /**
     * 編集コマンドのボタンを、折り畳めるグループに分けて並べる。
     * どのグループを開くかは、openGroups で決まる（起動したときは「ファイル」だけ）。
     */
    private void buildCommandGroups() {
        LinearLayout root = findViewById(R.id.command_groups);

        LinearLayout file = addGroup(root, R.string.group_file);
        openButton = addCommand(file, R.string.cmd_open, null);
        openButton.setOnClickListener(v -> {
            stopPlayback(); // 編集コマンドを開くと、再生は停止する
            // 未保存の音声データがあるときは、保存するかを確かめてから進む
            confirmUnsaved(R.string.save_and_open, R.string.open_without_saving, this::openFile);
        });
        needData.add(addCommand(file, R.string.cmd_save, () -> saver.save(null)));
        needData.add(addCommand(file, R.string.cmd_save_as, () -> saver.saveAs(null)));
        needData.add(addCommand(file, R.string.cmd_save_selection, saver::saveSelection));

        LinearLayout undo = addGroup(root, R.string.group_undo);
        undoButton = addCommand(undo, R.string.cmd_undo, commands::undo);
        redoButton = addCommand(undo, R.string.cmd_redo, commands::redo);

        LinearLayout select = addGroup(root, R.string.group_select);
        needData.add(addCommand(select, R.string.cmd_select_silence, commands::selectSilence));
        needData.add(addCommand(select, R.string.cmd_select_sound, commands::selectSound));

        LinearLayout edit = addGroup(root, R.string.group_edit);
        needData.add(addCommand(edit, R.string.cmd_trim, commands::trim));
        needData.add(addCommand(edit, R.string.cmd_cut, commands::cut));
        needData.add(addCommand(edit, R.string.cmd_copy, commands::copy));
        pasteButton = addCommand(edit, R.string.cmd_paste, commands::paste);
        insertButton = addCommand(edit, R.string.cmd_insert, commands::insert);
        appendButton = addCommand(edit, R.string.cmd_append, commands::append);

        LinearLayout range = addGroup(root, R.string.group_process_selection);
        needData.add(addCommand(range, R.string.cmd_volume, commands::volume));
        needData.add(addCommand(range, R.string.cmd_normalize, commands::normalize));
        needData.add(addCommand(range, R.string.cmd_mute, commands::mute));
        needData.add(addCommand(range, R.string.cmd_fade_in, commands::fadeIn));
        needData.add(addCommand(range, R.string.cmd_fade_out, commands::fadeOut));
        needData.add(addCommand(range, R.string.cmd_invert, commands::invert));
        needData.add(addCommand(range, R.string.cmd_reverse, commands::reverse));
        swapButton = addCommand(range, R.string.cmd_swap_channels, commands::swapChannels);

        LinearLayout whole = addGroup(root, R.string.group_process_all);
        needData.add(addCommand(whole, R.string.cmd_pitch, commands::pitch));
        needData.add(addCommand(whole, R.string.cmd_resample, commands::resample));
        needData.add(addCommand(whole, R.string.cmd_play_rate, commands::playRate));
        needData.add(addCommand(whole, R.string.cmd_channels, commands::channels));

        LinearLayout add = addGroup(root, R.string.group_add_sound);
        needData.add(addCommand(add, R.string.cmd_mix_sound, sounds::mix));
        needData.add(addCommand(add, R.string.cmd_insert_sound, sounds::insert));
    }

    /**
     * 折り畳めるグループを 1 つ足す。見出しのボタンを押すたびに、開く・畳むが切り替わる。
     *
     * @return グループのボタンを入れる所（見出しより 1 段下げてある）
     */
    private LinearLayout addGroup(LinearLayout root, int titleId) {
        float density = getResources().getDisplayMetrics().density;
        Button header = new Button(this, null, 0, R.style.CommandButton);
        header.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(Math.round(24 * density));
        root.addView(body, lp);

        String title = getString(titleId);
        Runnable show = () -> {
            boolean opened = body.getVisibility() == View.VISIBLE;
            header.setText(getString(opened ? R.string.group_opened : R.string.group_closed, title));
        };
        body.setVisibility(openGroups.contains(titleId) ? View.VISIBLE : View.GONE);
        show.run();
        header.setOnClickListener(v -> {
            if (!openGroups.remove(titleId)) {
                openGroups.add(titleId);
            }
            body.setVisibility(openGroups.contains(titleId) ? View.VISIBLE : View.GONE);
            show.run();
        });
        return body;
    }

    /** ボタンを 1 つ足す。action が null のもの（まだ実装していないコマンド）は、押せないままにする */
    private Button addCommand(LinearLayout group, int textId, Runnable action) {
        Button button = new Button(this, null, 0, R.style.CommandButton);
        button.setText(textId);
        button.setEnabled(false);
        if (action != null) {
            button.setOnClickListener(v -> {
                stopPlayback(); // 編集コマンドを開くと、再生は停止する
                if (session.hasData() && !editing) {
                    action.run();
                }
            });
        }
        group.addView(button, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return button;
    }

    // ---- メニュー ----

    private void showMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        Menu menu = popup.getMenu();
        MenuItem settings = menu.add(R.string.menu_settings);
        settings.setOnMenuItemClickListener(item -> {
            stopPlayback();
            settingsDialog = new SettingsScreen(this, session).show();
            return true;
        });
        MenuItem about = menu.add(getString(R.string.menu_about, AppProfile.SHORT_TITLE));
        about.setOnMenuItemClickListener(item -> {
            showAbout();
            return true;
        });
        popup.show();
    }

    /** このアプリのライセンスの名前 */
    private static final String APP_LICENSE = "Apache License Version 2.0";
    // 使っているライブラリ（LAME）。内容は、リポジトリ直下の NOTICE と合わせる
    private static final String LAME_NAME = "LAME 3.100";
    private static final String LAME_LICENSE = "GNU Library General Public License Version 2";
    private static final String LAME_URL = "https://lame.sourceforge.io/";

    /** アプリの情報。アプリ名、バージョン、著作権表示、ライセンス、使っているライブラリ */
    private void showAbout() {
        float density = getResources().getDisplayMetrics().density;
        int pad = Math.round(24 * density);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);

        StringBuilder text = new StringBuilder();
        text.append(AppProfile.TITLE).append('\n');
        text.append(getString(R.string.about_version, AppProfile.VERSION)).append("\n\n");
        text.append(AppProfile.COPYRIGHT);
        if (!AppProfile.AUTHOR.isEmpty()) {
            text.append('\n').append(AppProfile.AUTHOR);
        }
        text.append("\n\n").append(getString(R.string.about_license, APP_LICENSE));
        box.addView(aboutText(text, false));
        if (!AppProfile.RELEASE_URL.isEmpty()) {
            box.addView(aboutLink(R.string.about_distributor, AppProfile.RELEASE_URL));
        }
        if (!AppProfile.NOTICE_URL.isEmpty()) {
            box.addView(aboutLink(R.string.about_notice, AppProfile.NOTICE_URL));
        }
        box.addView(licenseButton(AppProfile.SHORT_TITLE, "licenses/LICENSE.txt"));

        box.addView(aboutText(getString(R.string.about_libraries), true));
        StringBuilder lame = new StringBuilder();
        lame.append(LAME_NAME).append('\n');
        lame.append(getString(R.string.about_lame_usage)).append('\n');
        lame.append(getString(R.string.about_license, LAME_LICENSE));
        box.addView(aboutText(lame, false));
        box.addView(aboutLink(R.string.about_distributor, LAME_URL));
        box.addView(licenseButton(LAME_NAME, "licenses/LAME-COPYING.txt"));

        ScrollView scroll = Scrolling.vertical(this, box);
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.menu_about, AppProfile.SHORT_TITLE))
                .setView(scroll)
                .setPositiveButton(R.string.close, null)
                .show();
    }

    private TextView aboutText(CharSequence text, boolean heading) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextIsSelectable(!heading);
        if (heading) {
            view.setTypeface(Typeface.DEFAULT_BOLD);
            view.setPadding(0, Math.round(20 * getResources().getDisplayMetrics().density), 0,
                    Math.round(4 * getResources().getDisplayMetrics().density));
        }
        return view;
    }

    /** URL の行（「配布元：https://…」など）。URL の部分を押すと、外のブラウザーで開く */
    private TextView aboutLink(int labelId, String url) {
        String line = getString(labelId, url);
        SpannableString text = new SpannableString(line);
        int at = line.indexOf(url);
        if (at >= 0) {
            text.setSpan(new ClickableSpan() {
                @Override
                public void onClick(View widget) {
                    openUrl(url);
                }
            }, at, at + url.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        TextView view = new TextView(this);
        view.setText(text);
        view.setMovementMethod(LinkMovementMethod.getInstance());
        return view;
    }

    /** URL を、外のブラウザーで開く */
    private void openUrl(String url) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        intent.addCategory(Intent.CATEGORY_BROWSABLE);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "cannot open " + url, e);
            showMessage(R.string.err_open_url);
        }
    }

    /** 「ライセンスを表示」のボタン。押すと、ライセンスの全文を表示する */
    private Button licenseButton(String title, String assetPath) {
        Button button = new Button(this, null, 0, R.style.CommandButton);
        button.setText(R.string.about_show_license);
        button.setOnClickListener(v -> showLicense(title, assetPath));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        button.setLayoutParams(lp);
        return button;
    }

    /** ライセンスの全文を表示する。全文は、APK に入れてある（assets/licenses/） */
    private void showLicense(String title, String assetPath) {
        String body;
        try (InputStream in = getAssets().open(assetPath)) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.w(TAG, "cannot read " + assetPath, e);
            showMessage(R.string.err_license);
            return;
        }
        float density = getResources().getDisplayMetrics().density;
        int pad = Math.round(16 * density);
        TextView view = new TextView(this);
        view.setText(body);
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextSize(11);
        view.setTextIsSelectable(true);
        view.setPadding(pad, pad / 2, pad, 0);
        // ライセンスの文は、行の幅が決まっている。折り返さず、横にもスクロールできるようにする
        HorizontalScrollView across = new HorizontalScrollView(this);
        Scrolling.keepBars(across);
        across.addView(view);
        ScrollView scroll = Scrolling.vertical(this, across);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(scroll)
                .setPositiveButton(R.string.close, null)
                .show();
    }

    // ---- 表示の更新 ----

    /** 音声データを読み書きする処理。画面とは別のスレッドで実行される */
    interface EditTask {
        /**
         * @param progress 進み具合の知らせ先。キャンセルされたら、Progress.Cancelled を投げてやめる（音声データは変えない）
         */
        Editor.Result run(Progress progress);
    }

    /** 編集を実行している間は true。その間は、画面の操作を受け付けない */
    private boolean editing;

    /** 編集が、この時間（ミリ秒）で終わらないときに、進み具合のダイアログを出す */
    private static final int PROGRESS_DIALOG_DELAY = 2000;

    /**
     * 音声データの編集を、画面とは別のスレッドで実行する。終わったら、表示を更新する。
     * 画面のスレッドは、その間、編集のスレッドの進み具合を見守る（runTask）。
     */
    void runEdit(EditTask task) {
        runTask(progress -> {
            try {
                return task.run(progress);
            } catch (Progress.Cancelled e) {
                return Editor.Result.CANCELLED;
            } catch (OutOfMemoryError e) {
                Log.w(TAG, "out of memory while editing", e);
                return Editor.Result.NO_MEMORY;
            } catch (RuntimeException e) {
                Log.e(TAG, "edit failed", e);
                return Editor.Result.FAILED;
            }
        }, this::afterEdit);
    }

    interface Work<T> {
        T run(Progress progress);
    }

    interface Done<T> {
        void accept(T value);
    }

    /**
     * work を別のスレッドで実行し、その結果を画面のスレッドで done に渡す。
     * その間は、画面の操作を受け付けない。2 秒で終わらないときは、何 % 終わったかを表示し、キャンセルできるダイアログを出す。
     */
    <T> void runTask(Work<T> work, Done<T> done) {
        if (editing) {
            return;
        }
        editing = true;
        stopPlayback();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);

        Progress progress = new Progress();
        float density = getResources().getDisplayMetrics().density;
        int pad = Math.round(20 * density);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        box.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView percent = new TextView(this);
        percent.setGravity(android.view.Gravity.END);
        box.addView(percent, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setMessage(R.string.processing)
                .setView(box)
                .setCancelable(false)
                .setNegativeButton(R.string.cancel, null)
                .create();

        // 画面のスレッドは、編集のスレッドの進み具合を見守る
        Runnable watch = new Runnable() {
            @Override
            public void run() {
                if (!editing || isDestroyed()) {
                    return;
                }
                float fraction = progress.fraction();
                bar.setProgress(Math.round(fraction * 1000));
                percent.setText(getString(R.string.percent, Math.round(fraction * 100)));
                handler.postDelayed(this, 100);
            }
        };
        Runnable showDialog = () -> {
            if (!editing || isDestroyed()) {
                return;
            }
            dialog.show();
            // 「キャンセル」を押しても、ダイアログは閉じない。編集のスレッドがやめるのを待つ
            Button cancel = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            cancel.setOnClickListener(v -> {
                progress.cancel();
                cancel.setEnabled(false);
            });
            watch.run();
        };
        handler.postDelayed(showDialog, PROGRESS_DIALOG_DELAY);

        new Thread(() -> {
            long began = System.nanoTime();
            T value = work.run(progress);
            Log.i(TAG, "task took " + (System.nanoTime() - began) / 1_000_000 + " ms on thread "
                    + Thread.currentThread().getName());
            runOnUiThread(() -> {
                editing = false;
                handler.removeCallbacks(showDialog);
                handler.removeCallbacks(watch);
                if (isDestroyed()) {
                    return;
                }
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
                if (dialog.isShowing()) {
                    dialog.dismiss();
                }
                done.accept(value);
                if (recreatePending) {
                    recreatePending = false;
                    recreate();
                }
            });
        }, "mase-edit").start();
    }

    /** 編集の後の始末。結果に合わせてメッセージを出し、表示を更新する */
    void afterEdit(Editor.Result result) {
        refreshAll();
        if (result == Editor.Result.TRUNCATED) {
            showMessage(R.string.warn_too_long);
        } else if (result == Editor.Result.NO_MEMORY) {
            showMessage(R.string.err_memory_edit);
        } else if (result == Editor.Result.FAILED) {
            showMessage(R.string.err_edit_failed);
        }
    }

    void refreshAll() {
        boolean has = session.hasData();
        showFileName();
        zoomV.setEnabled(has);
        zoomV.setValue(session.zoomV);
        playView.setEnabled(has);
        openButton.setEnabled(true);
        for (View view : needData) {
            view.setEnabled(has);
        }
        // 元に戻すもの・やり直すものがないときは、押せなくする
        undoButton.setEnabled(Editor.canUndo(session));
        redoButton.setEnabled(Editor.canRedo(session));
        // コピーバッファが空のときは、貼り付け・挿入を押せなくする
        pasteButton.setEnabled(has && session.clipboard != null);
        insertButton.setEnabled(has && session.clipboard != null);
        appendButton.setEnabled(has && session.clipboard != null);
        showChannelState();
        // チャンネル入れ替えは、ステレオのときだけ押せる
        swapButton.setEnabled(has && session.data.channels == 2);
        wave.clampScroll();
        onPositionsChanged();
    }

    /**
     * データファイル名と、その後ろにサンプリングレート、チャンネル数、音声データ全体の長さ（秒とサンプル）を表示する。
     * 省略はしない。幅に収まらないときは、利用者が横にスクロールして見る。
     */
    private void showFileName() {
        String text;
        if (!session.hasData()) {
            text = getString(R.string.no_data_name);
        } else {
            String name = session.dirty ? getString(R.string.unsaved_name, session.fileName) : session.fileName;
            String seconds = String.format(Locale.US, "%.5f", (double) session.length() / session.data.sampleRate);
            // サンプリングレートは kHz で表す。末尾の 0 は付けない（44100 → 44.1、48000 → 48、11025 → 11.025）
            String rate = BigDecimal.valueOf(session.data.sampleRate, 3).stripTrailingZeros().toPlainString();
            text = name + " " + getString(R.string.data_length, rate, session.data.channels, seconds, session.length());
        }
        if (!text.contentEquals(fileName.getText())) {
            fileName.setText(text); // 変わったときだけ入れ直す（入れ直すと、スクロールの位置が先頭に戻る）
        }
    }

    private void updateScrollBar() {
        if (session.hasData() && wave.getWidth() > 0) {
            scrollBar.setRange(session.length(), wave.visibleSamples(), session.scroll);
        } else {
            scrollBar.setRange(0, 0, 0);
        }
    }

    /** 波形ビューの指 2 本の操作で、横の倍率や表示位置が変わった。スクロールバーを合わせる */
    @Override
    public void onViewChanged() {
        updateScrollBar();
    }

    @Override
    public void onPositionsChanged() {
        if (startRow == null || endRow == null) {
            return;
        }
        startRow.refresh();
        endRow.refresh();
        updateScrollBar();
        wave.invalidate();
        playView.invalidate();
    }

    // ---- 再生 ----

    private void startPlayback() {
        if (!session.hasData() || session.unplayableNotified) {
            return; // 再生できないことを表示した後は、新しいデータを開くかレートを変えるまで、再生しない
        }
        if (session.playing) {
            // 再生中に押したときは、開始位置から再生し直す（動かした開始位置・終了位置も、ここで反映する）
            player.updateRange(session.start, session.end);
            player.seek(session.start);
            return;
        }
        // 停止していた位置から再開する。再生する範囲は、開始位置から終了位置まで
        int from = session.playPosition >= session.end ? session.start : session.playPosition;
        player.setChannels(session.leftOn, session.rightOn); // オフのチャンネルは、鳴らさない
        if (!player.play(session.data, session.start, session.end, from, session.repeat)) {
            session.unplayableNotified = true;
            showMessage(R.string.err_cannot_play);
            return;
        }
        session.playing = true;
        handler.removeCallbacks(playTicker);
        handler.post(playTicker);
    }

    /**
     * 加工するチャンネルのスイッチを押した。どちらか、または両方がオンの状態を保つ。
     * 片方だけがオンのときに、そのスイッチを押すと、オンとオフを入れ替える（L=オン・R=オフで L を押すと、L=オフ・R=オン）
     */
    private void toggleChannel(boolean left) {
        boolean pressed = left ? session.leftOn : session.rightOn;
        boolean other = left ? session.rightOn : session.leftOn;
        if (pressed && !other) {
            pressed = false;
            other = true;
        } else {
            pressed = !pressed;
        }
        session.leftOn = left ? pressed : other;
        session.rightOn = left ? other : pressed;
        showChannelState();
        wave.invalidate(); // 波形ビューも、オンのチャンネルだけの表示にする
        player.setChannels(session.leftOn, session.rightOn); // 再生中なら、聞こえ方もすぐに変える
    }

    /**
     * 加工するチャンネルのスイッチの表示。オンのスイッチは、リピートと同じく、ボタンの色を変えて示す。
     * 押せるのは、ステレオの音声データを開いているときだけ（モノラルのときは、チャンネルが 1 つなので、関係しない）
     */
    private void showChannelState() {
        boolean stereo = session.hasData() && session.data.channels == 2;
        leftButton.setEnabled(stereo);
        rightButton.setEnabled(stereo);
        // オンの色は、波形ビューの「ステレオの L だけの部分」「R だけの部分」の色（設定画面で変えられる）
        showToggle(leftButton, stereo && session.leftOn, session.colors.left);
        showToggle(rightButton, stereo && session.rightOn, session.colors.right);
    }

    /** ボタンの元の文字の色（オフのときに戻す） */
    private android.content.res.ColorStateList channelTextColors;

    /** オンのときは、ボタンの背景を color にする。文字は、背景に合わせて、黒か白にする（読みやすさのため） */
    private void showToggle(Button button, boolean on, int color) {
        if (channelTextColors == null) {
            channelTextColors = button.getTextColors();
        }
        if (on) {
            button.getBackground().mutate().setColorFilter(
                    new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
            // 明るさ（相対輝度の近似）で、文字の色を決める
            double luma = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255;
            button.setTextColor(luma > 0.5 ? Color.BLACK : Color.WHITE);
        } else {
            button.getBackground().mutate().clearColorFilter();
            button.setTextColor(channelTextColors);
        }
        button.invalidate();
    }

    /** リピートがオンのときは、ボタンの色を変えて示す */
    private void showRepeatState() {
        // ボタンの背景に色のフィルターをかける。背景の色そのもの（テーマの色）には触らない
        if (session.repeat) {
            repeatButton.getBackground().mutate().setColorFilter(
                    new PorterDuffColorFilter(getColor(R.color.repeat_on), PorterDuff.Mode.SRC_IN));
        } else {
            repeatButton.getBackground().mutate().clearColorFilter();
        }
        repeatButton.invalidate();
    }

    /** 再生を止める。再生位置は、止めた位置に残す */
    private void stopPlayback() {
        if (!session.playing) {
            return;
        }
        int position = player.stop();
        session.playing = false;
        handler.removeCallbacks(playTicker);
        if (position >= 0) {
            session.playPosition = position;
        }
        session.clampPlayPosition();
        onPositionsChanged();
    }

    /** 再生位置を開始位置に戻す。再生中は、そこから再生を続ける */
    private void rewind() {
        if (!session.hasData()) {
            return;
        }
        if (session.playing) {
            player.updateRange(session.start, session.end);
            player.seek(session.start);
        } else {
            session.setPlayPosition(session.start);
            onPositionsChanged();
        }
    }

    /** 再生中に動かした開始位置・終了位置を、再生する範囲に反映する（再生ビューのボタンを操作したとき） */
    private void applyRangeToPlayer() {
        if (session.playing) {
            player.updateRange(session.start, session.end);
        }
    }

    @Override
    public void onSeek(int position) {
        if (session.playing) {
            player.seek(position);
        }
    }

    /** リピートで次の繰り返しを始めるときの、再生する範囲。今の開始位置・終了位置（再生のスレッドから呼ばれる） */
    @Override
    public int[] rangeForRepeat() {
        return new int[]{session.start, session.end};
    }

    @Override
    public void onPlaybackFinished() {
        // 再生のスレッドから呼ばれる。リピートなしで終了位置まで来たら、止めて再生位置を開始位置に戻す
        handler.post(() -> {
            if (!session.playing) {
                return;
            }
            player.stop();
            session.playing = false;
            handler.removeCallbacks(playTicker);
            session.setPlayPosition(session.start);
            onPositionsChanged();
        });
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopPlayback(); // 画面が見えなくなったら、再生を止める
    }

    // ---- 読み込み ----

    private void openFile() {
        pickAudioFile(REQUEST_OPEN);
    }

    /** 「音の追加」で使う音声ファイルを選ぶ。選ばれたら、SoundCommands.onFilePicked に渡す */
    void pickSoundFile() {
        pickAudioFile(REQUEST_PICK_SOUND);
    }

    private void pickAudioFile(int requestCode) {
        // 読み込みの画面は、Android 標準のファイルを選ぶ画面（SAF）
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"audio/*", "video/mp4"});
        try {
            startActivityForResult(intent, requestCode);
        } catch (Exception e) {
            Log.w(TAG, "cannot start document picker", e);
            showMessage(R.string.err_cannot_open);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == SaveCommands.REQUEST_SAVE_AS || requestCode == SaveCommands.REQUEST_SAVE_SELECTION) {
            saver.onActivityResult(requestCode, resultCode, data);
            return;
        }
        if (requestCode == REQUEST_PICK_SOUND) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                Uri uri = data.getData();
                sounds.onFilePicked(uri, displayName(uri));
            }
            return;
        }
        if (requestCode != REQUEST_OPEN) {
            return;
        }
        // ファイルを選ばずに戻ったときは、何もしない
        if (resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            load(uri, displayName(uri), null);
        }
    }

    /**
     * 音声ファイルを読み込む。
     *
     * @param name      データファイル名として表示する名前
     * @param afterOpen 読み込めた後に続けること。なければ null
     */
    void load(Uri uri, String name, Runnable afterOpen) {
        decode(uri, count -> chooseChannels(count), loaded -> {
            stopPlayback();
            session.open(loaded.data, name);
            if (afterOpen != null) {
                afterOpen.run();
            }
            refreshAll();
            if (loaded.truncated) {
                showMessage(R.string.warn_too_long);
            }
        });
    }

    /**
     * 音声ファイルを、画面とは別のスレッドで読み込む。その間は、「キャンセル」のできるダイアログを出す。
     * 読み込めたら、画面のスレッドで done に渡す。読み込めなかったときは、理由を表示する。やめたときは、何もしない。
     *
     * @param chooser 入力が 3ch 以上のときの扱い。読み込みのスレッドから呼ばれる
     */
    void decode(Uri uri, AudioLoader.ChannelChooser chooser, Done<AudioLoader.Result> done) {
        decode(uri, chooser, done, null);
    }

    /**
     * @param otherwise 読み込めなかったとき（理由を表示した後）と、やめたときに続けること。なければ null
     */
    void decode(Uri uri, AudioLoader.ChannelChooser chooser, Done<AudioLoader.Result> done, Runnable otherwise) {
        AtomicBoolean cancel = new AtomicBoolean(false);
        float density = getResources().getDisplayMetrics().density;
        ProgressBar progress = new ProgressBar(this);
        int pad = Math.round(16 * density);
        progress.setPadding(pad, pad, pad, pad);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setMessage(R.string.loading)
                .setView(progress)
                .setCancelable(false)
                .setNegativeButton(R.string.cancel, (d, which) -> cancel.set(true))
                .show();

        new Thread(() -> {
            AudioLoader.Result result = null;
            AudioLoader.Failure failure = null;
            try {
                result = AudioLoader.load(getApplicationContext(), uri, chooser, cancel);
            } catch (AudioLoader.CancelledException e) {
                // やめたときは、何もせずに元の状態に戻る
            } catch (AudioLoader.LoadException e) {
                failure = e.failure;
            } catch (OutOfMemoryError e) {
                failure = AudioLoader.Failure.MEMORY;
            } catch (RuntimeException e) {
                Log.w(TAG, "load failed", e);
                failure = AudioLoader.Failure.CORRUPT;
            }
            AudioLoader.Result loaded = result;
            AudioLoader.Failure failed = failure;
            runOnUiThread(() -> {
                if (isDestroyed()) {
                    return;
                }
                dialog.dismiss();
                if (cancel.get() || (failed == null && loaded == null)) {
                    // 読み終わっていても、やめたのなら元の状態のまま
                    if (otherwise != null) {
                        otherwise.run();
                    }
                    return;
                }
                if (failed != null) {
                    showMessage(messageFor(failed));
                    if (otherwise != null) {
                        otherwise.run();
                    }
                } else {
                    done.accept(loaded);
                }
            });
        }, "mase-load").start();
    }

    /** 入力が 3ch 以上のときに、読み込むチャンネルを 2 つまで選んでもらう。読み込みのスレッドから呼ぶ */
    private int[] chooseChannels(int count) {
        CountDownLatch latch = new CountDownLatch(1);
        int[][] picked = new int[1][];
        runOnUiThread(() -> {
            if (isDestroyed()) {
                latch.countDown();
                return;
            }
            String[] items = new String[count];
            boolean[] checked = new boolean[count];
            for (int i = 0; i < count; i++) {
                items[i] = getString(R.string.channel_item, i + 1);
            }
            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setTitle(R.string.choose_channels_title)
                    .setMultiChoiceItems(items, checked, null)
                    .setCancelable(false)
                    .setPositiveButton(R.string.ok, (d, which) -> {
                        List<Integer> list = new ArrayList<>();
                        for (int i = 0; i < count; i++) {
                            if (checked[i]) {
                                list.add(i);
                            }
                        }
                        // 番号の小さいほうを L、大きいほうを R にする
                        int[] result = new int[list.size()];
                        for (int i = 0; i < result.length; i++) {
                            result[i] = list.get(i);
                        }
                        picked[0] = result;
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .setOnDismissListener(d -> latch.countDown())
                    .create();
            dialog.show();
            Scrolling.keepBars(dialog.getListView());
            Button ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            ok.setEnabled(false);
            // 1 つか 2 つ選んでいるときだけ、OK を押せる
            dialog.getListView().setOnItemClickListener((parent, view, position, id) -> {
                checked[position] = dialog.getListView().isItemChecked(position);
                int n = 0;
                for (boolean c : checked) {
                    if (c) {
                        n++;
                    }
                }
                ok.setEnabled(n >= 1 && n <= 2);
            });
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return picked[0];
    }

    String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                return cursor.getString(0);
            }
        } catch (Exception e) {
            Log.w(TAG, "cannot query display name", e);
        }
        String last = uri.getLastPathSegment();
        return last == null ? uri.toString() : last;
    }

    private static int messageFor(AudioLoader.Failure failure) {
        switch (failure) {
            case UNSUPPORTED:
                return R.string.err_unsupported;
            case CORRUPT:
                return R.string.err_corrupt;
            case NO_AUDIO:
                return R.string.err_no_audio;
            case MEMORY:
                return R.string.err_memory;
            default:
                return R.string.err_cannot_open;
        }
    }

    void showMessage(int textId) {
        showMessage(getString(textId));
    }

    void showMessage(String text) {
        new AlertDialog.Builder(this)
                .setMessage(text)
                .setPositiveButton(R.string.ok, null)
                .show();
    }
}
