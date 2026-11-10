package io.github.a3indae.midagekeyboard;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 삼성 기본 키보드 느낌으로 직접 그리는 자판 */
public class KeyboardView extends View {

    public interface Listener {
        void onPiece(String piece);      // 한글 조각 / 방점
        void onText(String text);        // 그대로 넣을 글자
        void onBackspace();
        void onSpace();
        void onEnter();
    }

    static class Key {
        String label, piece, act, hint;
        String[] alts;
        float weight = 1f;
        boolean fn, spacer, tool;
        RectF r = new RectF();
    }

    private static final String HAN = "han", ENG = "eng", OLD = "old", SYM = "sym";

    private static final Map<String, String> PIECE = new HashMap<>();
    private static final Map<String, String> SHIFT = new HashMap<>();
    static {
        String[][] p = {
                {"ㅃ", "ㅂㅂ"}, {"ㅉ", "ㅈㅈ"}, {"ㄸ", "ㄷㄷ"}, {"ㄲ", "ㄱㄱ"}, {"ㅆ", "ㅅㅅ"},
                {"ㅸ", "ㅂㅇ"}, {"ㅹ", "ㅂㅂㅇ"}, {"ㅱ", "ㅁㅇ"}, {"ㆄ", "ㅍㅇ"}, {"ㅥ", "ㄴㄴ"}, {"ㆀ", "ㅇㅇ"}, {"ㆅ", "ㅎㅎ"},
                {"ㅺ", "ㅅㄱ"}, {"ㅼ", "ㅅㄷ"}, {"ㅽ", "ㅅㅂ"}, {"ㅄ", "ㅂㅅ"}, {"ㅳ", "ㅂㄷ"}, {"ㅶ", "ㅂㅈ"}, {"ㅷ", "ㅂㅌ"},
                {"ㅴ", "ㅂㅅㄱ"}, {"ㅵ", "ㅂㅅㄷ"},
                {"ㆎ", "ㆍㅣ"}, {"ᆢ", "ㆍㆍ"}, {"ㆇ", "ㅛㅑ"}, {"ㆈ", "ㅛㅒ"}, {"ㆉ", "ㅛㅣ"}, {"ㆊ", "ㅠㅕ"}, {"ㆋ", "ㅠㅖ"}, {"ㆌ", "ㅠㅣ"},
                {"거성", Hangul.TONE1}, {"상성", Hangul.TONE2},
        };
        for (String[] a : p) PIECE.put(a[0], a[1]);
        String[][] s = {{"ㅂ", "ㅃ"}, {"ㅈ", "ㅉ"}, {"ㄷ", "ㄸ"}, {"ㄱ", "ㄲ"}, {"ㅅ", "ㅆ"}, {"ㅐ", "ㅒ"}, {"ㅔ", "ㅖ"}};
        for (String[] a : s) SHIFT.put(a[0], a[1]);
    }

    private static final String[] TOOL = {"ㆍ", "ㅿ", "ㆁ", "ㆆ", "ㅸ", "ㆎ", "거성", "상성"};

    private Listener listener;
    private String mode = HAN;
    private boolean shift = false, caps = false;
    private long lastShiftTap = 0;
    private final List<List<Key>> rows = new ArrayList<>();
    private final List<Key> toolKeys = new ArrayList<>();

    private final float dp;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int cBg, cKey, cFn, cTxt, cSub, cAccent, cPress, cPop, cTool;

    private Key pressed;
    private boolean popOpen;
    private int popSel;
    private final RectF popRect = new RectF();
    private final Handler h = new Handler(Looper.getMainLooper());
    private Runnable longPress, repeat;

    public KeyboardView(Context ctx) {
        super(ctx);
        dp = ctx.getResources().getDisplayMetrics().density;
        boolean dark = (ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        if (dark) {
            cBg = 0xFF161618; cKey = 0xFF333336; cFn = 0xFF242427; cTxt = 0xFFF2F2F4; cSub = 0xFF8D8D93;
            cAccent = 0xFF6D93FF; cPress = 0xFF4A4A4E; cPop = 0xFF3C3C40; cTool = 0xFFB8B8BE;
        } else {
            cBg = 0xFFE9E9ED; cKey = 0xFFFFFFFF; cFn = 0xFFD4D5DA; cTxt = 0xFF1B1B1D; cSub = 0xFF8A8A90;
            cAccent = 0xFF3B6EF5; cPress = 0xFFC4C5CB; cPop = 0xFFFFFFFF; cTool = 0xFF5A5A60;
        }
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.DEFAULT);
        hint.setTextAlign(Paint.Align.CENTER);
        hint.setTextSize(10.5f * dp);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1.8f * dp);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        shadow.setColor(0x33000000);
        build();
    }

    public void setListener(Listener l) { listener = l; }

    // ---------- 자판 배열 ----------

    private Key k(String label, String... alts) {
        Key key = new Key();
        key.label = label;
        key.alts = alts;
        if (alts.length > 0) key.hint = alts[0];
        return key;
    }

    private Key fn(String act, String label, float w) {
        Key key = new Key();
        key.act = act; key.label = label; key.fn = true; key.weight = w;
        return key;
    }

    private Key spacer(float w) {
        Key key = new Key();
        key.spacer = true; key.weight = w;
        return key;
    }

    private List<Key> row(Object... items) {
        List<Key> r = new ArrayList<>();
        for (Object o : items) r.add(o instanceof Key ? (Key) o : k((String) o));
        return r;
    }

    private void build() {
        rows.clear();
        boolean sh = shift || caps;
        switch (mode) {
            case HAN:
                rows.add(row(k(sh ? "ㅃ" : "ㅂ", "ㅸ", "ㅹ"), sh ? "ㅉ" : "ㅈ", sh ? "ㄸ" : "ㄷ", sh ? "ㄲ" : "ㄱ",
                        k(sh ? "ㅆ" : "ㅅ", "ㅿ", "ㅺ", "ㅼ", "ㅽ"), k("ㅛ", "ㆇ", "ㆈ", "ㆉ"), "ㅕ", "ㅑ",
                        sh ? "ㅒ" : "ㅐ", sh ? "ㅖ" : "ㅔ"));
                rows.add(row(spacer(.5f), k("ㅁ", "ㅱ"), k("ㄴ", "ㅥ"), k("ㅇ", "ㆁ", "ㆀ"), "ㄹ", k("ㅎ", "ㆆ", "ㆅ"),
                        "ㅗ", "ㅓ", k("ㅏ", "ㆍ", "ᆢ"), k("ㅣ", "ㆎ"), spacer(.5f)));
                rows.add(row(fn("shift", "", 1.45f), "ㅋ", "ㅌ", "ㅊ", k("ㅍ", "ㆄ"), k("ㅠ", "ㆊ", "ㆋ", "ㆌ"),
                        "ㅜ", "ㅡ", fn("bs", "", 1.45f)));
                break;
            case ENG: {
                String a = "qwertyuiop", b = "asdfghjkl", c = "zxcvbnm";
                if (sh) { a = a.toUpperCase(); b = b.toUpperCase(); c = c.toUpperCase(); }
                rows.add(chars(a));
                List<Key> r2 = chars(b); r2.add(0, spacer(.5f)); r2.add(spacer(.5f)); rows.add(r2);
                List<Key> r3 = chars(c); r3.add(0, fn("shift", "", 1.45f)); r3.add(fn("bs", "", 1.45f)); rows.add(r3);
                break;
            }
            case OLD:
                rows.add(row("ㅿ", "ㆁ", "ㆆ", "ㅸ", "ㅱ", "ㆄ", "ㅹ", "ㆅ", "ㆀ", "ㅥ"));
                rows.add(row(spacer(.5f), "ㅺ", "ㅼ", "ㅽ", "ㅄ", "ㅳ", "ㅶ", "ㅷ", "ㅴ", "ㅵ", spacer(.5f)));
                rows.add(row("ㆍ", "ㆎ", "ᆢ", "ㆇ", "ㆈ", "ㆉ", "ㆊ", "ㆋ", "ㆌ", fn("bs", "", 1.2f)));
                break;
            default: // SYM
                rows.add(chars("1234567890"));
                rows.add(row("거성", "상성", "·", "「", "」", "(", ")", "!", "?", "~"));
                rows.add(row("-", "\"", "'", ":", ";", "/", "…", "@", fn("bs", "", 1.45f)));
                break;
        }
        List<Key> bottom = new ArrayList<>();
        bottom.add(fn(SYM.equals(mode) ? "han" : "sym", SYM.equals(mode) ? "한글" : "!#1", 1.3f));
        bottom.add(fn(OLD.equals(mode) ? "han" : "old", OLD.equals(mode) ? "한글" : "옛", 1f));
        Key comma = k(","); comma.fn = true; bottom.add(comma);
        Key sp = fn("space", ENG.equals(mode) ? "English" : "중세국어", 3.6f); sp.fn = false; bottom.add(sp);
        bottom.add(fn("lang", "한/영", 1f));
        Key dot = k(".", "?", "!", "·"); dot.fn = true; bottom.add(dot);
        bottom.add(fn("enter", "", 1.35f));
        rows.add(bottom);

        toolKeys.clear();
        for (String s : TOOL) { Key key = k(s); key.tool = true; toolKeys.add(key); }

        for (List<Key> r : rows) for (Key key : r) {
            if (key.label != null && key.act == null) {
                String p = PIECE.get(key.label);
                key.piece = p != null ? p : key.label;
            }
        }
        for (Key key : toolKeys) {
            String p = PIECE.get(key.label);
            key.piece = p != null ? p : key.label;
        }
        requestLayout();
        invalidate();
    }

    private List<Key> chars(String s) {
        List<Key> r = new ArrayList<>();
        for (int i = 0; i < s.length(); i++) r.add(k(String.valueOf(s.charAt(i))));
        return r;
    }

    // ---------- 크기와 배치 ----------

    private float toolH() { return 44 * dp; }
    private float keyH() { return 48 * dp; }
    private float gapV() { return 9 * dp; }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        float hgt = 4 * dp + toolH() + rows.size() * keyH() + (rows.size() - 1) * gapV() + 10 * dp;
        setMeasuredDimension(w, (int) hgt);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        layoutKeys();
    }

    private void layoutKeys() {
        float w = getWidth();
        float pad = 4 * dp, gap = 5 * dp;
        float tw = (w - 2 * pad) / toolKeys.size();
        for (int i = 0; i < toolKeys.size(); i++) {
            Key key = toolKeys.get(i);
            key.r.set(pad + i * tw, 4 * dp, pad + (i + 1) * tw, 4 * dp + toolH());
        }
        float y = 4 * dp + toolH();
        for (List<Key> r : rows) {
            float total = 0;
            for (Key key : r) total += key.weight;
            float unit = (w - 2 * pad - gap * (r.size() - 1)) / total;
            float x = pad;
            for (Key key : r) {
                float kw = unit * key.weight;
                key.r.set(x, y, x + kw, y + keyH());
                x += kw + gap;
            }
            y += keyH() + gapV();
        }
    }

    // ---------- 그리기 ----------

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(cBg);
        float rad = 9 * dp;
        for (Key key : toolKeys) {
            if (key == pressed) {
                fill.setColor(cPress);
                float cx = key.r.centerX(), cy = key.r.centerY();
                c.drawRoundRect(new RectF(cx - 22 * dp, cy - 18 * dp, cx + 22 * dp, cy + 18 * dp), 18 * dp, 18 * dp, fill);
            }
            boolean word = key.label.length() > 1;
            text.setColor(cTool);
            text.setTextSize((word ? 14 : 21) * dp);
            drawCentered(c, key.label, key.r);
        }
        for (List<Key> r : rows) for (Key key : r) {
            if (key.spacer) continue;
            boolean on = key == pressed && !popOpen;
            fill.setColor(on ? cPress : (key.fn ? cFn : cKey));
            c.drawRoundRect(key.r, rad, rad, fill);
            boolean lit = ("shift".equals(key.act) && (shift || caps)) || ("han".equals(key.act) && OLD.equals(mode));
            int col = lit ? cAccent : cTxt;
            if ("shift".equals(key.act)) { drawShift(c, key.r, col, caps); continue; }
            if ("bs".equals(key.act)) { drawBackspace(c, key.r, col); continue; }
            if ("enter".equals(key.act)) { drawEnter(c, key.r, col); continue; }
            text.setColor("space".equals(key.act) ? cSub : col);
            float size;
            if ("space".equals(key.act)) size = 13;
            else if (key.fn || key.label.length() > 1) size = key.label.length() > 2 ? 14 : 16;
            else size = 22;
            if (key.label.length() == 2 && !key.fn && key.act == null) size = 15; // 거성/상성
            text.setTextSize(size * dp);
            drawCentered(c, key.label, key.r);
            if (key.hint != null && !key.fn) {
                hint.setColor(cSub);
                c.drawText(key.hint, key.r.right - 9 * dp, key.r.top + 13 * dp, hint);
            }
        }
        if (popOpen && pressed != null) drawPopup(c);
    }

    private void drawCentered(Canvas c, String s, RectF r) {
        Paint.FontMetrics fm = text.getFontMetrics();
        float y = r.centerY() - (fm.ascent + fm.descent) / 2;
        c.drawText(s, r.centerX(), y, text);
    }

    private void drawPopup(Canvas c) {
        String[] alts = pressed.alts;
        float cw = 46 * dp, ch = 52 * dp, p = 4 * dp;
        float w = alts.length * cw + 2 * p;
        float left = pressed.r.centerX() - w / 2;
        left = Math.max(2 * dp, Math.min(left, getWidth() - w - 2 * dp));
        float top = Math.max(0, pressed.r.top - ch - 2 * p - 6 * dp);
        popRect.set(left, top, left + w, top + ch + 2 * p);
        RectF sh = new RectF(popRect); sh.offset(0, 2 * dp);
        c.drawRoundRect(sh, 12 * dp, 12 * dp, shadow);
        fill.setColor(cPop);
        c.drawRoundRect(popRect, 12 * dp, 12 * dp, fill);
        text.setTextSize(23 * dp);
        for (int i = 0; i < alts.length; i++) {
            RectF cell = new RectF(left + p + i * cw, top + p, left + p + (i + 1) * cw, top + p + ch);
            if (i == popSel) {
                fill.setColor(cAccent);
                c.drawRoundRect(cell, 9 * dp, 9 * dp, fill);
                text.setColor(0xFFFFFFFF);
            } else text.setColor(cTxt);
            drawCentered(c, alts[i], cell);
        }
    }

    private void drawShift(Canvas c, RectF r, int col, boolean locked) {
        float s = 11 * dp, cx = r.centerX(), cy = r.centerY();
        Path p = new Path();
        p.moveTo(cx, cy - s);
        p.lineTo(cx + s * .85f, cy - s * .05f);
        p.lineTo(cx + s * .4f, cy - s * .05f);
        p.lineTo(cx + s * .4f, cy + s * .75f);
        p.lineTo(cx - s * .4f, cy + s * .75f);
        p.lineTo(cx - s * .4f, cy - s * .05f);
        p.lineTo(cx - s * .85f, cy - s * .05f);
        p.close();
        if (locked) {
            fill.setColor(col);
            c.drawPath(p, fill);
        } else {
            stroke.setColor(col);
            c.drawPath(p, stroke);
        }
    }

    private void drawBackspace(Canvas c, RectF r, int col) {
        float s = 11 * dp, cx = r.centerX(), cy = r.centerY();
        Path p = new Path();
        p.moveTo(cx - s * .45f, cy - s * .7f);
        p.lineTo(cx + s, cy - s * .7f);
        p.lineTo(cx + s, cy + s * .7f);
        p.lineTo(cx - s * .45f, cy + s * .7f);
        p.lineTo(cx - s * 1.1f, cy);
        p.close();
        p.moveTo(cx - s * .05f, cy - s * .3f);
        p.lineTo(cx + s * .55f, cy + s * .3f);
        p.moveTo(cx + s * .55f, cy - s * .3f);
        p.lineTo(cx - s * .05f, cy + s * .3f);
        stroke.setColor(col);
        c.drawPath(p, stroke);
    }

    private void drawEnter(Canvas c, RectF r, int col) {
        float s = 10 * dp, cx = r.centerX(), cy = r.centerY();
        Path p = new Path();
        p.moveTo(cx + s * .9f, cy - s * .8f);
        p.lineTo(cx + s * .9f, cy + s * .1f);
        p.lineTo(cx - s * .9f, cy + s * .1f);
        p.moveTo(cx - s * .45f, cy - s * .35f);
        p.lineTo(cx - s * .9f, cy + s * .1f);
        p.lineTo(cx - s * .45f, cy + s * .55f);
        stroke.setColor(col);
        c.drawPath(p, stroke);
    }

    // ---------- 터치 ----------

    private Key find(float x, float y) {
        for (Key key : toolKeys) if (key.r.contains(x, y)) return key;
        Key best = null;
        float bd = Float.MAX_VALUE;
        for (List<Key> r : rows) for (Key key : r) {
            if (key.spacer) continue;
            if (key.r.contains(x, y)) return key;
            // 글쇠 사이 틈을 누르면 가장 가까운 글쇠
            float dx = Math.max(0, Math.max(key.r.left - x, x - key.r.right));
            float dy = Math.max(0, Math.max(key.r.top - y, y - key.r.bottom));
            float d = dx * dx + dy * dy;
            if (d < bd) { bd = d; best = key; }
        }
        return bd < (8 * dp) * (8 * dp) ? best : null;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                cancelTimers();
                pressed = find(e.getX(), e.getY());
                popOpen = false;
                if (pressed == null) return true;
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                final Key key = pressed;
                if ("bs".equals(key.act)) {
                    if (listener != null) listener.onBackspace();
                    repeat = new Runnable() {
                        @Override public void run() {
                            if (pressed == key && listener != null) { listener.onBackspace(); h.postDelayed(this, 60); }
                        }
                    };
                    h.postDelayed(repeat, 420);
                } else if (key.alts != null && key.alts.length > 0) {
                    longPress = () -> {
                        if (pressed == key) {
                            popOpen = true; popSel = 0;
                            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                            invalidate();
                        }
                    };
                    h.postDelayed(longPress, 380);
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE:
                if (popOpen && pressed != null) {
                    float cw = 46 * dp, p = 4 * dp;
                    int i = (int) ((e.getX() - popRect.left - p) / cw);
                    i = Math.max(0, Math.min(pressed.alts.length - 1, i));
                    if (i != popSel) { popSel = i; invalidate(); }
                }
                return true;
            case MotionEvent.ACTION_UP: {
                Key key = pressed;
                boolean wasPop = popOpen;
                cancelTimers();
                pressed = null;
                popOpen = false;
                if (key != null) {
                    if (wasPop) emit(key, key.alts[popSel]);
                    else if (!"bs".equals(key.act)) emit(key, null);
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                cancelTimers();
                pressed = null;
                popOpen = false;
                invalidate();
                return true;
        }
        return true;
    }

    private void cancelTimers() {
        if (longPress != null) h.removeCallbacks(longPress);
        if (repeat != null) h.removeCallbacks(repeat);
        longPress = null;
        repeat = null;
    }

    private void emit(Key key, String choice) {
        if (listener == null) return;
        if (key.act != null) {
            switch (key.act) {
                case "shift": {
                    long now = System.currentTimeMillis();
                    if (caps) { caps = false; shift = false; }
                    else if (shift && now - lastShiftTap < 350) { caps = true; shift = false; }
                    else shift = !shift;
                    lastShiftTap = now;
                    build();
                    return;
                }
                case "space": listener.onSpace(); return;
                case "enter": listener.onEnter(); return;
                case "lang": mode = ENG.equals(mode) ? HAN : ENG; shift = caps = false; build(); return;
                case "han": case "eng": case "old": case "sym":
                    mode = key.act; shift = caps = false; build(); return;
            }
            return;
        }
        String label = choice != null ? choice : key.label;
        String piece = PIECE.get(label);
        if (piece == null) piece = label;
        if (ENG.equals(mode) && !key.tool) {
            listener.onText(label);
        } else {
            listener.onPiece(piece);
        }
        if (shift && !key.tool) { shift = false; build(); }
    }

    public void resetShift() {
        if (shift) { shift = false; build(); }
    }
}
