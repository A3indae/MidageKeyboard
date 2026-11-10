package io.github.a3indae.midagekeyboard;

import android.content.Context;
import android.content.SharedPreferences;

/** 키보드 설정 (앱 화면에서 바꿈) */
public class Prefs {
    public static final String FILE = "midage";

    public int vibration;      // 0 끔, 1 약하게, 2 강하게
    public boolean preview;    // 누른 글쇠 미리보기
    public int longPressMs;    // 길게 누르기 시간
    public int heightPct;      // 키보드 높이 (%)

    public static Prefs load(Context c) {
        SharedPreferences sp = c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        Prefs p = new Prefs();
        p.vibration = sp.getInt("vibration", 1);
        p.preview = sp.getBoolean("preview", true);
        p.longPressMs = sp.getInt("longPressMs", 300);
        p.heightPct = sp.getInt("heightPct", 100);
        return p;
    }

    public void save(Context c) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putInt("vibration", vibration)
                .putBoolean("preview", preview)
                .putInt("longPressMs", longPressMs)
                .putInt("heightPct", heightPct)
                .apply();
    }
}
