package io.github.a3indae.midagekeyboard;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 삼성 기본 키보드 느낌으로 직접 그리는 자판.
 *
 * 터치 처리는 안드로이드 기본 키보드(AOSP LatinIME)의 방식을 따른다.
 * - 손가락마다 따로 추적한다. 두 번째 손가락이 닿으면 앞 손가락의 글쇠를 바로 입력한다 (빠른 두 엄지 입력에서 누락 방지).
 * - 글쇠 사이 틈도 가장 가까운 글쇠의 영역으로 친다.
 * - 손가락이 조금 미끄러져도 처음 글쇠를 유지하고, 확실히 옮겨 갔을 때만 새 글쇠로 바꾼다.
 * - 길게 누르기 창은 손가락 바로 위에 뜨고, 손가락 좌우 움직임으로 고른 뒤 떼면 입력된다.
 * - 미리보기와 길게 누르기 창은 자판 위쪽의 투명한 공간에 그린다 (윗줄 글쇠도 가려지지 않음).
 */
public class KeyboardView extends View {

    public interface Listener {
        void onPiece(String piece);
        void onText(String text);
        void onBackspace();
        void onSpace();
        void onEnter();
        void onCursor(int dir);      // 스페이스 끌기: -1 왼쪽, +1 오른쪽
        void onCursorStart();
    }

    static class Key {
        String label, piece, act, hint;
        String[] alts;
        float weight = 1f;
        boolean fn, spacer, tool;
        final RectF r = new RectF();     // 그리는 영역
        final RectF hit = new RectF();   // 터치 영역 (틈 포함)
        boolean isLetter() { return act == null && !spacer; }
    }

    /** 손가락 하나의 상태 */
    static class Ptr {
        int id;
        Key key;
        float downX, downY, lastX;
        boolean done;          // 이미 입력함 (뗄 때 무시)
        boolean pop;           // 길게 누르기 창 열림
        int popSel;
        boolean spaceDrag;
        boolean chorded;       // Shift를 누른 채 다른 글쇠를 쳤음
        Runnable longPress, repeat;
    }

    private static final String HAN = "han", ENG = "eng", OLD = "old", SYM = "sym";

    private static final Map<String, String> PIECE = new HashMap<>();
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
    }

    private static final String[] TOOL = {"ㆍ", "ㅿ", "ㆁ", "ㆆ", "ㅸ", "ㆎ", "거성", "상성"};

    private Listener listener;
    private Prefs prefs;
    private String mode = HAN;
    private boolean shift = false, caps = false;
    private long lastShiftTap = 0;
    private final List<List<Key>> rows = new ArrayList<>();
    private final List<Key> toolKeys = new ArrayList<>();
    private final SparseArray<Ptr> ptrs = new SparseArray<>();

    private final float dp;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF();
    private final RectF popRect = new RectF();

    private int cBg, cKey, cFn, cTxt, cSub, cAccent, cPress, cPop, cTool;

    private final Handler h = new Handler(Looper.getMainLooper());
    private final Vibrator vibrator;

    public KeyboardView(Context ctx) {
        super(ctx);
        dp = ctx.getResources().getDisplayMetrics().density;
        prefs = Prefs.load(ctx);
        vibrator = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
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
        shadow.setColor(0x38000000);
        build();
    }

    public void setListener(Listener l) { listener = l; }

    /** 앱 화면에서 바꾼 설정 다시 읽기 */
    public void reloadPrefs() {
        int oldH = prefs.heightPct;
        prefs = Prefs.load(getContext());
        if (oldH != prefs.heightPct) requestLayout();
    }

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
                rows.add(row(k(sh ? "ㅃ" : "ㅂ", "ㅸ", "ㅹ", "ㅄ", "ㅴ"), sh ? "ㅉ" : "ㅈ", sh ? "ㄸ" : "ㄷ", sh ? "ㄲ" : "ㄱ",
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
                rows.add(row(k("1", "¹", "①"), k("2", "²", "②"), k("3", "³", "③"), k("4", "④"), k("5", "⑤"),
                        k("6", "⑥"), k("7", "⑦"), k("8", "⑧"), k("9", "⑨"), k("0", "⓪")));
                rows.add(row("거성", "상성", k("·", "ㆍ", "•", "‧"), k("「", "『", "《", "〈"), k("」", "』", "》", "〉"),
                        k("(", "[", "{"), k(")", "]", "}"), k("!", "¡"), k("?", "¿")));
                rows.add(row(k("-", "–", "—", "_"), k("\"", "“", "”"), k("'", "‘", "’"), k(":", ";"), k("/", "\\", "|"),
                        k("…", "~", "〜"), k("@", "#", "&", "*"), fn("bs", "", 1.45f)));
                break;
        }
        List<Key> bottom = new ArrayList<>();
        bottom.add(fn(SYM.equals(mode) ? "han" : "sym", SYM.equals(mode) ? "한글" : "!#1", 1.3f));
        bottom.add(fn(OLD.equals(mode) ? "han" : "old", OLD.equals(mode) ? "한글" : "옛", 1f));
        Key comma = k(",", "?", "!", "·", "…"); comma.fn = true; bottom.add(comma);
        Key sp = fn("space", ENG.equals(mode) ? "English" : "중세국어", 3.6f); sp.fn = false; bottom.add(sp);
        bottom.add(fn("lang", "한/영", 1f));
        Key dot = k(".", "?", "!", "·", "…", "~"); dot.fn = true; bottom.add(dot);
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
        if (getWidth() > 0) layoutKeys();
        invalidate();
    }

    private List<Key> chars(String s) {
        List<Key> r = new ArrayList<>();
        for (int i = 0; i < s.length(); i++) r.add(k(String.valueOf(s.charAt(i))));
        return r;
    }

    // ---------- 크기와 배치 ----------

    private float scale() { return prefs.heightPct / 100f; }
    /** 미리보기·길게 누르기 창을 그릴 위쪽 투명 공간 */
    public float popupSpace() { return 76 * dp; }
    private float toolH() { return 42 * dp; }
    private float keyH() { return 46 * dp * scale(); }
    private float gapV() { return 8 * dp * scale(); }
    private float gapH() { return 5 * dp; }
    private float padH() { return 4 * dp; }
    private float padBottom() { return 8 * dp; }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        float hgt = popupSpace() + toolH() + 4 * dp + rows.size() * keyH() + (rows.size() - 1) * gapV() + padBottom();
        setMeasuredDimension(w, (int) hgt);
    }

    @Override
    protected void onSizeChanged(int w, int hh, int ow, int oh) {
        super.onSizeChanged(w, hh, ow, oh);
        layoutKeys();
    }

    private void layoutKeys() {
        float w = getWidth();
        float top = popupSpace();
        float tw = (w - 2 * padH()) / toolKeys.size();
        for (int i = 0; i < toolKeys.size(); i++) {
            Key key = toolKeys.get(i);
            key.r.set(padH() + i * tw, top, padH() + (i + 1) * tw, top + toolH());
            key.hit.set(key.r);
            if (i == 0) key.hit.left = 0;
            if (i == toolKeys.size() - 1) key.hit.right = w;
        }
        float y = top + toolH() + 4 * dp;
        float gv = gapV(), gh = gapH();
        for (int ri = 0; ri < rows.size(); ri++) {
            List<Key> r = rows.get(ri);
            float total = 0;
            for (Key key : r) total += key.weight;
            float unit = (w - 2 * padH() - gh * (r.size() - 1)) / total;
            float x = padH();
            for (int i = 0; i < r.size(); i++) {
                Key key = r.get(i);
                float kw = unit * key.weight;
                key.r.set(x, y, x + kw, y + keyH());
                // 터치 영역: 위아래·좌우 틈의 절반씩을 더하고, 가장자리는 화면 끝까지
                key.hit.set(x - gh / 2, y - gv / 2, x + kw + gh / 2, y + keyH() + gv / 2);
                if (ri == 0) key.hit.top = y - 4 * dp;
                if (ri == rows.size() - 1) key.hit.bottom = getHeight();
                x += kw + gh;
            }
            // 줄 양 끝의 빈칸(spacer)은 옆 글쇠가 차지
            for (int i = 0; i < r.size(); i++) {
                Key key = r.get(i);
                if (!key.spacer) continue;
                if (i == 0 && r.size() > 1) r.get(1).hit.left = 0;
                if (i == r.size() - 1 && r.size() > 1) r.get(i - 1).hit.right = w;
            }
            if (!r.get(0).spacer) r.get(0).hit.left = 0;
            if (!r.get(r.size() - 1).spacer) r.get(r.size() - 1).hit.right = w;
            y += keyH() + gv;
        }
    }

    // ---------- 그리기 ----------

    private boolean isPressed(Key key) {
        for (int i = 0; i < ptrs.size(); i++) {
            Ptr p = ptrs.valueAt(i);
            if (p.key == key && !p.done) return true;
        }
        return false;
    }

    @Override
    protected void onDraw(Canvas c) {
        // 위쪽 popupSpace 는 투명하게 두고 그 아래만 칠한다
        fill.setColor(cBg);
        c.drawRect(0, popupSpace(), getWidth(), getHeight(), fill);
        float rad = 9 * dp;
        for (Key key : toolKeys) {
            if (isPressed(key)) {
                fill.setColor(cPress);
                float cx = key.r.centerX(), cy = key.r.centerY();
                tmp.set(cx - 22 * dp, cy - 17 * dp, cx + 22 * dp, cy + 17 * dp);
                c.drawRoundRect(tmp, 17 * dp, 17 * dp, fill);
            }
            boolean word = key.label.length() > 1;
            text.setColor(cTool);
            text.setTextSize((word ? 14 : 21) * dp);
            drawCentered(c, key.label, key.r);
        }
        for (List<Key> r : rows) for (Key key : r) {
            if (key.spacer) continue;
            boolean on = isPressed(key);
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
        // 미리보기와 길게 누르기 창
        for (int i = 0; i < ptrs.size(); i++) {
            Ptr p = ptrs.valueAt(i);
            if (p.done || p.key == null) continue;
            if (p.pop) drawPopup(c, p);
            else if (prefs.preview && p.key.isLetter() && !p.key.tool && !p.spaceDrag) drawPreview(c, p.key);
        }
    }

    private void drawCentered(Canvas c, String s, RectF r) {
        Paint.FontMetrics fm = text.getFontMetrics();
        float y = r.centerY() - (fm.ascent + fm.descent) / 2;
        c.drawText(s, r.centerX(), y, text);
    }

    private void drawPreview(Canvas c, Key key) {
        float w = Math.max(key.r.width() + 14 * dp, 48 * dp), hh = 58 * dp;
        float left = key.r.centerX() - w / 2;
        left = Math.max(2 * dp, Math.min(left, getWidth() - w - 2 * dp));
        float top = key.r.top - hh - 6 * dp;
        tmp.set(left, top + 2 * dp, left + w, top + hh + 2 * dp);
        c.drawRoundRect(tmp, 12 * dp, 12 * dp, shadow);
        tmp.set(left, top, left + w, top + hh);
        fill.setColor(cPop);
        c.drawRoundRect(tmp, 12 * dp, 12 * dp, fill);
        text.setColor(cTxt);
        text.setTextSize((key.label.length() > 1 ? 18 : 30) * dp);
        drawCentered(c, key.label, tmp);
    }

    private float cellW() { return 50 * dp; }
    private float cellH() { return 56 * dp; }
    private float popPad() { return 4 * dp; }

    /** 첫 후보가 손가락 바로 위에 오도록 창 위치를 정한다 */
    private void placePopup(Ptr p) {
        int n = p.key.alts.length;
        float w = n * cellW() + 2 * popPad();
        float left = p.key.r.centerX() - cellW() / 2 - popPad();
        left = Math.max(2 * dp, Math.min(left, getWidth() - w - 2 * dp));
        float top = Math.max(0, p.key.r.top - cellH() - 2 * popPad() - 6 * dp);
        popRect.set(left, top, left + w, top + cellH() + 2 * popPad());
    }

    private int popIndexAt(Ptr p, float x, float y) {
        // 손가락을 자판 아래쪽으로 많이 내리면 취소
        if (y > p.key.r.bottom + keyH() * 0.9f) return -1;
        int i = (int) Math.floor((x - popRect.left - popPad()) / cellW());
        return Math.max(0, Math.min(p.key.alts.length - 1, i));
    }

    private void drawPopup(Canvas c, Ptr p) {
        placePopup(p);
        String[] alts = p.key.alts;
        tmp.set(popRect); tmp.offset(0, 2 * dp);
        c.drawRoundRect(tmp, 12 * dp, 12 * dp, shadow);
        fill.setColor(cPop);
        c.drawRoundRect(popRect, 12 * dp, 12 * dp, fill);
        for (int i = 0; i < alts.length; i++) {
            float l = popRect.left + popPad() + i * cellW();
            tmp.set(l, popRect.top + popPad(), l + cellW(), popRect.top + popPad() + cellH());
            if (i == p.popSel) {
                fill.setColor(cAccent);
                c.drawRoundRect(tmp, 9 * dp, 9 * dp, fill);
                text.setColor(0xFFFFFFFF);
            } else text.setColor(cTxt);
            text.setTextSize((alts[i].length() > 1 ? 17 : 24) * dp);
            drawCentered(c, alts[i], tmp);
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
        if (locked) { fill.setColor(col); c.drawPath(p, fill); }
        else { stroke.setColor(col); c.drawPath(p, stroke); }
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

    // ---------- 진동 ----------

    private void buzz(boolean strong) {
        if (prefs.vibration == 0 || vibrator == null || !vibrator.hasVibrator()) return;
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                int fx = (prefs.vibration == 2 || strong) ? VibrationEffect.EFFECT_CLICK : VibrationEffect.EFFECT_TICK;
                vibrator.vibrate(VibrationEffect.createPredefined(fx));
            } else if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(VibrationEffect.createOneShot(prefs.vibration == 2 ? 20 : 10,
                        VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(prefs.vibration == 2 ? 20 : 10);
            }
        } catch (Exception ignored) { }
    }

    // ---------- 터치 ----------

    private Key find(float x, float y) {
        if (y < popupSpace()) return null;
        for (Key key : toolKeys) if (key.hit.contains(x, y)) return key;
        for (List<Key> r : rows) for (Key key : r) {
            if (!key.spacer && key.hit.contains(x, y)) return key;
        }
        // 그래도 없으면 가장 가까운 글쇠
        Key best = null;
        float bd = Float.MAX_VALUE;
        for (List<Key> r : rows) for (Key key : r) {
            if (key.spacer) continue;
            float dx = Math.max(0, Math.max(key.r.left - x, x - key.r.right));
            float dy = Math.max(0, Math.max(key.r.top - y, y - key.r.bottom));
            float d = dx * dx + dy * dy;
            if (d < bd) { bd = d; best = key; }
        }
        return best;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int idx = e.getActionIndex();
                onDown(e.getPointerId(idx), e.getX(idx), e.getY(idx));
                break;
            }
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getPointerCount(); i++) onMove(e.getPointerId(i), e.getX(i), e.getY(i));
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                int idx = e.getActionIndex();
                onUp(e.getPointerId(idx), e.getX(idx), e.getY(idx));
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                for (int i = 0; i < ptrs.size(); i++) cancelTimers(ptrs.valueAt(i));
                ptrs.clear();
                break;
        }
        invalidate();
        return true;
    }

    private void onDown(int id, float x, float y) {
        Key key = find(x, y);
        // 새 손가락이 닿으면, 아직 떼지 않은 앞 손가락의 글자를 지금 입력한다 (AOSP 방식)
        for (int i = 0; i < ptrs.size(); i++) {
            Ptr q = ptrs.valueAt(i);
            if (q.done || q.key == null) continue;
            if ("shift".equals(q.key.act)) { if (key != null && key.isLetter()) q.chorded = true; continue; }
            if (q.pop || q.spaceDrag) continue;
            cancelTimers(q);
            if (!"bs".equals(q.key.act)) emit(q.key, null);
            q.done = true;
        }
        if (key == null) return;
        Ptr p = new Ptr();
        p.id = id; p.key = key; p.downX = x; p.downY = y; p.lastX = x;
        ptrs.put(id, p);
        buzz(false);
        final Ptr fp = p;
        if ("shift".equals(key.act)) {
            pressShift();
        } else if ("bs".equals(key.act)) {
            if (listener != null) listener.onBackspace();
            fp.repeat = new Runnable() {
                int n = 0;
                @Override public void run() {
                    if (ptrs.get(fp.id) != fp || fp.done) return;
                    if (listener != null) listener.onBackspace();
                    n++;
                    h.postDelayed(this, n > 15 ? 35 : 60);
                }
            };
            h.postDelayed(fp.repeat, 400);
        } else if (key.alts != null && key.alts.length > 0) {
            fp.longPress = () -> {
                if (ptrs.get(fp.id) != fp || fp.done) return;
                fp.pop = true;
                placePopup(fp);
                fp.popSel = 0;
                buzz(true);
                invalidate();
            };
            h.postDelayed(fp.longPress, prefs.longPressMs);
        }
    }

    private void onMove(int id, float x, float y) {
        Ptr p = ptrs.get(id);
        if (p == null || p.done) return;
        if (p.pop) {
            int s = popIndexAt(p, x, y);
            if (s != p.popSel) { p.popSel = s; if (s >= 0) buzz(false); }
            return;
        }
        if ("space".equals(p.key.act)) {
            float step = 11 * dp;
            if (!p.spaceDrag && Math.abs(x - p.downX) > 16 * dp) {
                p.spaceDrag = true;
                p.lastX = x;
                if (listener != null) listener.onCursorStart();
            }
            if (p.spaceDrag) {
                while (x - p.lastX >= step) { p.lastX += step; if (listener != null) listener.onCursor(1); }
                while (p.lastX - x >= step) { p.lastX -= step; if (listener != null) listener.onCursor(-1); }
            }
            return;
        }
        // 글쇠 미끄러짐: 손가락이 확실히 다른 글쇠로 옮겨 갔을 때만 바꾼다
        if (p.key.isLetter() && ptrs.size() == 1) {
            Key nk = find(x, y);
            if (nk != null && nk != p.key && nk.isLetter()) {
                float dx = x - p.downX, dy = y - p.downY;
                float moved = (float) Math.sqrt(dx * dx + dy * dy);
                boolean deepInside = x > nk.r.left + nk.r.width() * 0.2f && x < nk.r.right - nk.r.width() * 0.2f
                        && y > nk.r.top + nk.r.height() * 0.15f && y < nk.r.bottom - nk.r.height() * 0.15f;
                if (moved > Math.min(p.key.r.width(), p.key.r.height()) * 0.6f && deepInside) {
                    cancelTimers(p);
                    p.key = nk;
                    p.downX = x; p.downY = y;
                    buzz(false);
                    if (nk.alts != null && nk.alts.length > 0) {
                        final Ptr fp = p;
                        fp.longPress = () -> {
                            if (ptrs.get(fp.id) != fp || fp.done) return;
                            fp.pop = true; placePopup(fp); fp.popSel = 0; buzz(true); invalidate();
                        };
                        h.postDelayed(fp.longPress, prefs.longPressMs);
                    }
                }
            }
        }
    }

    private void onUp(int id, float x, float y) {
        Ptr p = ptrs.get(id);
        ptrs.remove(id);
        if (p == null) return;
        cancelTimers(p);
        if (p.done) return;
        Key key = p.key;
        if (p.pop) {
            if (p.popSel >= 0) emit(key, key.alts[p.popSel]);
        } else if (p.spaceDrag) {
            // 커서만 옮김
        } else if ("shift".equals(key.act)) {
            if (p.chorded) { shift = false; caps = false; build(); }
        } else if (!"bs".equals(key.act)) {
            emit(key, null);
        }
    }

    private void cancelTimers(Ptr p) {
        if (p.longPress != null) h.removeCallbacks(p.longPress);
        if (p.repeat != null) h.removeCallbacks(p.repeat);
        p.longPress = null;
        p.repeat = null;
    }

    private void pressShift() {
        long now = System.currentTimeMillis();
        if (caps) { caps = false; shift = false; }
        else if (shift && now - lastShiftTap < 400) { caps = true; shift = false; }
        else shift = !shift;
        lastShiftTap = now;
        build();
    }

    private boolean shiftHeld() {
        for (int i = 0; i < ptrs.size(); i++) {
            Ptr p = ptrs.valueAt(i);
            if (!p.done && p.key != null && "shift".equals(p.key.act)) return true;
        }
        return false;
    }

    private void emit(Key key, String choice) {
        if (listener == null) return;
        if (key.act != null) {
            switch (key.act) {
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
        if (ENG.equals(mode) && !key.tool) listener.onText(label);
        else listener.onPiece(piece);
        // 한 번 누른 Shift 는 글자 하나 뒤에 풀린다 (누른 채 치는 중이면 유지)
        if (shift && !key.tool && !shiftHeld()) { shift = false; build(); }
    }
}
