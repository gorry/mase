package net.gorry.mase;

import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import java.util.Locale;

/**
 * 開始位置・終了位置の操作の 1 行。ボタンと数値の入力で、その行のバーを動かす。
 * 仕様は memo/doc/spec.md の「開始位置・終了位置の操作ボタン群」。
 */
final class PositionRow {
    private final View root;
    private final boolean isStart;
    private final Session session;
    private final Runnable onChanged;
    private final EditText value;
    private final Button unit;
    private final int[] buttonIds = {
            R.id.pos_head, R.id.pos_back_1s, R.id.pos_back_01s, R.id.pos_back_1,
            R.id.pos_fwd_1, R.id.pos_fwd_01s, R.id.pos_fwd_1s, R.id.pos_tail,
    };

    PositionRow(View root, boolean isStart, Session session, Runnable onChanged) {
        this.root = root;
        this.isStart = isStart;
        this.session = session;
        this.onChanged = onChanged;
        value = root.findViewById(R.id.pos_value);
        unit = root.findViewById(R.id.pos_unit);

        int nameId = isStart ? R.string.start_position : R.string.end_position;
        value.setContentDescription(root.getContext().getString(nameId));
        // 行の名前。開始位置の行と終了位置の行で、後ろの入力欄やボタンの位置がずれないよう、
        // 2 つの名前のうち長いほうの幅にそろえる（言語によって、名前の幅が違う。英語の Start と End など）
        TextView label = root.findViewById(R.id.pos_label);
        label.setText(isStart ? R.string.start_label : R.string.end_label);
        float widest = Math.max(
                label.getPaint().measureText(root.getContext().getString(R.string.start_label)),
                label.getPaint().measureText(root.getContext().getString(R.string.end_label)));
        label.setMinWidth((int) Math.ceil(widest) + label.getPaddingLeft() + label.getPaddingRight());

        // 表示の単位は、押すごとに「秒」と「サンプル」が切り替わる
        unit.setOnClickListener(v -> {
            int next = currentUnit() == Session.UNIT_SECONDS ? Session.UNIT_SAMPLES : Session.UNIT_SECONDS;
            if (isStart) {
                session.startUnit = next;
            } else {
                session.endUnit = next;
            }
            refresh();
        });

        root.findViewById(R.id.pos_head).setOnClickListener(v -> moveTo(0));
        root.findViewById(R.id.pos_tail).setOnClickListener(v -> moveTo(session.length()));
        root.findViewById(R.id.pos_back_1s).setOnClickListener(v -> moveBy(-step(1.0)));
        root.findViewById(R.id.pos_back_01s).setOnClickListener(v -> moveBy(-step(0.1)));
        root.findViewById(R.id.pos_back_1).setOnClickListener(v -> moveBy(-1));
        root.findViewById(R.id.pos_fwd_1).setOnClickListener(v -> moveBy(1));
        root.findViewById(R.id.pos_fwd_01s).setOnClickListener(v -> moveBy(step(0.1)));
        root.findViewById(R.id.pos_fwd_1s).setOnClickListener(v -> moveBy(step(1.0)));

        value.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_DONE || enter) {
                commitInput();
                value.clearFocus();
                InputMethodManager imm = v.getContext().getSystemService(InputMethodManager.class);
                if (imm != null) {
                    imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
                }
                return true;
            }
            return false;
        });
        value.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                commitInput();
            }
        });
    }

    private int currentUnit() {
        return isStart ? session.startUnit : session.endUnit;
    }

    private int position() {
        return isStart ? session.start : session.end;
    }

    /** seconds 秒にあたるサンプル数。1 サンプルより小さくはしない */
    private long step(double seconds) {
        return Math.max(1, Math.round(session.data.sampleRate * seconds));
    }

    private void moveBy(long delta) {
        moveTo((long) position() + delta);
    }

    /** バーを動かす。相手のバーとデータの範囲でのクリップは、Session が行う */
    private void moveTo(long pos) {
        if (!session.hasData()) {
            return;
        }
        if (isStart) {
            session.setStart(pos);
        } else {
            session.setEnd(pos);
        }
        onChanged.run();
    }

    private void commitInput() {
        if (!session.hasData()) {
            return;
        }
        String text = value.getText().toString().trim();
        try {
            long pos;
            if (currentUnit() == Session.UNIT_SECONDS) {
                // サンプルの境目に合わないときは、近いほうの境目に合わせる
                pos = Math.round(Double.parseDouble(text) * session.data.sampleRate);
            } else {
                pos = Math.round(Double.parseDouble(text));
            }
            moveTo(pos);
        } catch (NumberFormatException e) {
            refresh(); // 数値として読めないときは、元の表示に戻す
        }
    }

    private String format(long samples) {
        if (currentUnit() == Session.UNIT_SECONDS) {
            return String.format(Locale.US, "%.5f", (double) samples / session.data.sampleRate);
        }
        return Long.toString(samples);
    }

    /** 表示を、今の状態に合わせる */
    void refresh() {
        boolean has = session.hasData();
        for (int id : buttonIds) {
            root.findViewById(id).setEnabled(has);
        }
        value.setEnabled(has);
        unit.setEnabled(has);
        unit.setText(currentUnit() == Session.UNIT_SECONDS ? R.string.unit_seconds : R.string.unit_samples);
        String shown = has ? format(position()) : "";
        if (!shown.contentEquals(value.getText())) {
            value.setText(shown);
        }
    }
}
