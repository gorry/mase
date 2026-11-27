package net.gorry.mase;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 保存・名前を付けて保存・選択範囲を保存。
 * 仕様は memo/doc/spec.md の「保存・名前を付けて保存・選択範囲を保存」。
 */
final class SaveCommands {
    private static final String TAG = "mase.SaveCommands";
    static final int REQUEST_SAVE_AS = 2;
    static final int REQUEST_SAVE_SELECTION = 3;

    private static final int[] BIT_RATES = {64_000, 96_000, 128_000, 160_000, 192_000, 256_000, 320_000};

    private final MainActivity activity;
    private final Session session;
    /** 保存先を選ぶ画面を出している間の、パラメータと、保存できた後に続けること */
    private AudioSaver.Params pendingParams;
    private Runnable pendingAfter;

    SaveCommands(MainActivity activity, Session session) {
        this.activity = activity;
        this.session = session;
    }

    /**
     * 保存。「名前を付けて保存」を実行済みなら、そのときの設定でダイアログを出さずに保存する。
     *
     * @param after 正常に保存できた後に続けること。なければ null
     */
    void save(Runnable after) {
        if (session.saveUri != null && session.saveParams != null) {
            write(session.saveUri, session.saveParams, true, after);
        } else {
            saveAs(after);
        }
    }

    /** 名前を付けて保存 */
    void saveAs(Runnable after) {
        showParamsDialog(R.string.cmd_save_as, params -> pickDestination(params, REQUEST_SAVE_AS, after));
    }

    /** 選択範囲を保存。ファイルに書き出すだけで、内部の音声データ・未保存の状態・データファイル名は変えない */
    void saveSelection() {
        showParamsDialog(R.string.cmd_save_selection,
                params -> pickDestination(params, REQUEST_SAVE_SELECTION, null));
    }

    private void pickDestination(AudioSaver.Params params, int request, Runnable after) {
        pendingParams = params;
        pendingAfter = after;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(params.mimeType());
        intent.putExtra(Intent.EXTRA_TITLE, baseName(session.fileName) + params.extension());
        try {
            activity.startActivityForResult(intent, request);
        } catch (Exception e) {
            Log.w(TAG, "cannot start document creator", e);
            activity.showMessage(R.string.err_save);
        }
    }

    /** 保存先を選ぶ画面から戻ってきた */
    void onActivityResult(int requestCode, int resultCode, Intent data) {
        AudioSaver.Params params = pendingParams;
        Runnable after = pendingAfter;
        pendingParams = null;
        pendingAfter = null;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null
                || params == null || !session.hasData()) {
            return; // 保存先を選ばずに戻ったときは、何もしない
        }
        write(data.getData(), params, requestCode == REQUEST_SAVE_AS, after);
    }

    /**
     * 書き出す。
     *
     * @param whole true なら全区間（保存・名前を付けて保存）、false なら選択範囲（選択範囲を保存）
     */
    private void write(Uri uri, AudioSaver.Params params, boolean whole, Runnable after) {
        AudioData data = session.data;
        int start = whole ? 0 : session.start;
        int end = whole ? data.length : session.end;

        ProgressBar progress = new ProgressBar(activity);
        int pad = Math.round(16 * activity.getResources().getDisplayMetrics().density);
        progress.setPadding(pad, pad, pad, pad);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setMessage(R.string.saving)
                .setView(progress)
                .setCancelable(false)
                .show();

        new Thread(() -> {
            boolean ok;
            try {
                AudioSaver.save(activity.getApplicationContext(), uri, data, start, end, params);
                ok = true;
            } catch (Exception | OutOfMemoryError e) {
                Log.w(TAG, "save failed", e);
                ok = false;
            }
            boolean saved = ok;
            activity.runOnUiThread(() -> {
                if (activity.isDestroyed()) {
                    return;
                }
                dialog.dismiss();
                if (!saved) {
                    activity.showMessage(R.string.err_save);
                    return;
                }
                if (!whole) {
                    return;
                }
                // 保存済みにする。次の「保存」は、この保存先と設定を使う
                String name = activity.displayName(uri);
                session.saveUri = uri;
                session.saveParams = params;
                session.fileName = name;
                session.dirty = false;
                activity.refreshAll();
                if (params.lossy()) {
                    askReplace(uri, params, name, after);
                } else if (after != null) {
                    after.run();
                }
            });
        }, "mase-save").start();
    }

    /** 書き出した内容で、内部の音声データを置き換えるかを問う */
    private void askReplace(Uri uri, AudioSaver.Params params, String name, Runnable after) {
        new AlertDialog.Builder(activity)
                .setMessage(R.string.ask_replace)
                .setCancelable(false)
                .setPositiveButton(R.string.replace, (d, w) ->
                        // 書き出したファイルを読み込んだのと同じに扱う。保存先と設定は引き継ぐ
                        activity.load(uri, name, () -> {
                            session.saveUri = uri;
                            session.saveParams = params;
                            if (after != null) {
                                after.run();
                            }
                        }))
                .setNegativeButton(R.string.dont_replace, (d, w) -> {
                    if (after != null) {
                        after.run();
                    }
                })
                .show();
    }

    private interface ParamsAction {
        void run(AudioSaver.Params params);
    }

    /** ファイル出力のパラメータのダイアログ。選べる形式は、内部の音声データのサンプリングレートで決まる */
    private void showParamsDialog(int titleId, ParamsAction action) {
        AudioData d = session.data;
        List<Integer> formats = new ArrayList<>();
        List<String> formatNames = new ArrayList<>();
        formats.add(AudioSaver.FORMAT_WAV);
        formatNames.add("wav");
        if (Mp3Encoder.canWrite(d.sampleRate, d.channels)) {
            formats.add(AudioSaver.FORMAT_MP3);
            formatNames.add("mp3");
        }
        if (AudioSaver.canWriteM4a(d.sampleRate, d.channels)) {
            formats.add(AudioSaver.FORMAT_M4A);
            formatNames.add("m4a");
        }

        AudioSaver.Params last = session.saveParams;
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * activity.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);

        box.addView(label(R.string.label_format));
        Spinner formatSpinner = spinner(formatNames.toArray(new String[0]));
        box.addView(formatSpinner);
        TextView detailLabel = new TextView(activity);
        box.addView(detailLabel);
        Spinner detailSpinner = new Spinner(activity);
        box.addView(detailSpinner, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // いま出しているビットレートの選択肢（形式とサンプリングレートで変わる）
        List<Integer> rates = new ArrayList<>();

        formatSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                // 形式ごとに選ぶもの：wav は量子化ビット数、mp3・m4a はビットレート
                String[] items;
                int selected = 0;
                if (formats.get(position) == AudioSaver.FORMAT_WAV) {
                    detailLabel.setText(R.string.label_bits);
                    items = new String[]{
                            activity.getString(R.string.bits_item, 16),
                            activity.getString(R.string.bits_item, 8),
                    };
                    selected = last != null && last.bits == 8 ? 1 : 0;
                } else {
                    detailLabel.setText(R.string.label_bit_rate);
                    // mp3 は、サンプリングレートが低いと、選べるビットレートの上限が下がる
                    int max = formats.get(position) == AudioSaver.FORMAT_MP3
                            ? Mp3Encoder.maxBitRate(d.sampleRate) : Integer.MAX_VALUE;
                    rates.clear();
                    for (int rate : BIT_RATES) {
                        if (rate <= max) {
                            rates.add(rate);
                        }
                    }
                    items = new String[rates.size()];
                    int want = last != null ? last.bitRate : 128_000;
                    for (int i = 0; i < items.length; i++) {
                        items[i] = activity.getString(R.string.bit_rate_item, rates.get(i) / 1000);
                        if (rates.get(i) <= want) {
                            selected = i; // 前の値が選べないときは、それより小さい中でいちばん大きい値
                        }
                    }
                }
                ArrayAdapter<String> adapter = new ArrayAdapter<>(activity,
                        android.R.layout.simple_spinner_item, items);
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                detailSpinner.setAdapter(adapter);
                detailSpinner.setSelection(selected);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        if (last != null && formats.contains(last.format)) {
            formatSpinner.setSelection(formats.indexOf(last.format));
        }

        new AlertDialog.Builder(activity)
                .setTitle(titleId)
                .setView(Scrolling.vertical(activity, box))
                .setPositiveButton(R.string.ok, (dlg, w) -> {
                    AudioSaver.Params params = new AudioSaver.Params();
                    params.format = formats.get(formatSpinner.getSelectedItemPosition());
                    int detail = Math.max(0, detailSpinner.getSelectedItemPosition());
                    if (params.format == AudioSaver.FORMAT_WAV) {
                        params.bits = detail == 1 ? 8 : 16;
                    } else {
                        params.bitRate = rates.get(Math.min(detail, rates.size() - 1));
                    }
                    action.run(params);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private TextView label(int textId) {
        TextView view = new TextView(activity);
        view.setText(textId);
        return view;
    }

    private Spinner spinner(String[] items) {
        Spinner spinner = new Spinner(activity);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        return spinner;
    }

    /** ファイル名から、拡張子を除いた部分 */
    private static String baseName(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
