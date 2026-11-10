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
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int pad = dp(20);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad * 2, pad, pad);

        TextView title = new TextView(this);
        title.setText("중세국어 키보드");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        box.addView(title);

        TextView body = new TextView(this);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        body.setLineSpacing(0, 1.3f);
        body.setPadding(0, dp(12), 0, dp(16));
        body.setText("1. 아래 '키보드 켜기'를 눌러 목록에서 중세국어 키보드를 켜세요.\n"
                + "2. '키보드 고르기'를 눌러 중세국어 키보드로 바꾸세요.\n\n"
                + "쓰는 법\n"
                + "• 윗줄: ㆍ ㅿ ㆁ ㆆ ㅸ ㆎ 와 방점(거성 〮, 상성 〯)\n"
                + "• 길게 누르기: ㅅ→ㅿ, ㅇ→ㆁ, ㅎ→ㆆ, ㅏ→ㆍ, ㅂ→ㅸ, ㅁ→ㅱ …\n"
                + "• 이어 치기: ㅂ+ㅇ=ㅸ, ㅅ+ㄱ=ㅺ, ㆍ+ㅣ=ㆎ\n"
                + "• '옛' 글쇠: ㅴ ㆇ 같은 드문 글자 모음\n"
                + "• '한/영' 글쇠: 영어 자판");
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

        EditText test = new EditText(this);
        test.setHint("여기에 시험해 보세요");
        test.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        test.setMinLines(3);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        box.addView(test, lp);

        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        setContentView(sv);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
