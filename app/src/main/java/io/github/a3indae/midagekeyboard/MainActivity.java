package io.github.a3indae.midagekeyboard;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {

    private Prefs prefs;
    private LinearLayout box;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = Prefs.load(this);
        int pad = dp(20);
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad * 2, pad, pad * 2);

        TextView title = new TextView(this);
        title.setText("중세국어 키보드");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        box.addView(title);

        TextView body = new TextView(this);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        body.setLineSpacing(0, 1.3f);
        body.setPadding(0, dp(12), 0, dp(12));
        body.setText("1. '키보드 켜기'에서 중세국어 키보드를 켜세요.\n"
                + "2. '키보드 고르기'로 중세국어 키보드로 바꾸세요.\n\n"
                + "• 윗줄: ㆍ ㅿ ㆁ ㆆ ㅸ ㆎ 와 방점(거성 〮, 상성 〯)\n"
                + "• 길게 누르기: ㅅ→ㅿ, ㅇ→ㆁ, ㅎ→ㆆ, ㅏ→ㆍ, ㅂ→ㅸ … 누른 채 좌우로 밀어 고르고 떼면 입력\n"
                + "• 이어 치기: ㅂ+ㅇ=ㅸ, ㅅ+ㄱ=ㅺ, ㆍ+ㅣ=ㆎ\n"
                + "• 스페이스를 누른 채 좌우로 밀면 커서 이동\n"
                + "• Shift 두 번 누르면 고정");
        box.addView(body);

        Button enable = new Button(this);
        enable.setText("키보드 켜기");
        enable.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));
        box.addView(enable);

        Button pick = new Button(this);
        pick.setText("키보드 고르기");
        pick.setOnClickListener(v -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showInputMethodPicker();
        });
        box.addView(pick);

        section("진동");
        radios(new String[]{"끄기", "약하게", "강하게"}, prefs.vibration, i -> { prefs.vibration = i; prefs.save(this); });

        section("길게 누르기 시간");
        int[] lp = {220, 300, 450};
        radios(new String[]{"빠르게", "보통", "느리게"}, indexOf(lp, prefs.longPressMs, 1),
                i -> { prefs.longPressMs = lp[i]; prefs.save(this); });

        section("키보드 높이");
        int[] hp = {90, 100, 112};
        radios(new String[]{"낮게", "보통", "높게"}, indexOf(hp, prefs.heightPct, 1),
                i -> { prefs.heightPct = hp[i]; prefs.save(this); });

        CheckBox pv = new CheckBox(this);
        pv.setText("누른 글쇠 크게 보여주기");
        pv.setChecked(prefs.preview);
        pv.setOnCheckedChangeListener((v, on) -> { prefs.preview = on; prefs.save(this); });
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pl.topMargin = dp(12);
        box.addView(pv, pl);

        EditText test = new EditText(this);
        test.setHint("여기에 시험해 보세요");
        test.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        test.setMinLines(3);
        LinearLayout.LayoutParams lpm = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpm.topMargin = dp(16);
        box.addView(test, lpm);

        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        setContentView(sv);
    }

    interface OnPick { void pick(int i); }

    private void section(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setPadding(0, dp(18), 0, dp(4));
        box.addView(t);
    }

    private void radios(String[] labels, int sel, OnPick onPick) {
        RadioGroup g = new RadioGroup(this);
        g.setOrientation(RadioGroup.HORIZONTAL);
        for (int i = 0; i < labels.length; i++) {
            RadioButton r = new RadioButton(this);
            r.setText(labels[i]);
            r.setId(1000 + i);
            r.setPadding(0, 0, dp(16), 0);
            g.addView(r);
        }
        g.check(1000 + sel);
        g.setOnCheckedChangeListener((grp, id) -> onPick.pick(id - 1000));
        box.addView(g);
    }

    private static int indexOf(int[] a, int v, int def) {
        for (int i = 0; i < a.length; i++) if (a[i] == v) return i;
        return def;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
