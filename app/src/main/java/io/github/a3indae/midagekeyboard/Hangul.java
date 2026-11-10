package io.github.a3indae.midagekeyboard;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 옛한글 조합 엔진. 자모를 "조각(piece)" 단위로 받아 초성/중성/종성 묶음을 만든다.
 * 조각은 기본 자모 문자열이다. 예: "ㅂ", "ㅂㅇ"(ㅸ), "ㄱㄱ"(ㄲ), "ㆍㅣ"(ㆎ).
 */
public class Hangul {
    private static final String VOWELS = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅛㅜㅠㅡㅣㆍ";
    public static final String TONE1 = "〮"; // 거성 〮
    public static final String TONE2 = "〯"; // 상성 〯

    private final Map<String, String> L = new HashMap<>();
    private final Map<String, String> V = new HashMap<>();
    private final Map<String, String> T = new HashMap<>();
    private final Map<String, String> C = new HashMap<>();

    private final List<String> l = new ArrayList<>();
    private final List<String> v = new ArrayList<>();
    private final List<String> t = new ArrayList<>();

    public Hangul(Context ctx) {
        try (InputStream in = ctx.getAssets().open("jamo.json")) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            JSONObject o = new JSONObject(new String(bo.toByteArray(), StandardCharsets.UTF_8));
            load(o.getJSONObject("L"), L);
            load(o.getJSONObject("V"), V);
            load(o.getJSONObject("T"), T);
            load(o.getJSONObject("C"), C);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void load(JSONObject o, Map<String, String> m) throws Exception {
        Iterator<String> it = o.keys();
        while (it.hasNext()) {
            String k = it.next();
            m.put(k, o.getString(k));
        }
    }

    public static boolean isVowelPiece(String p) {
        return !p.isEmpty() && VOWELS.indexOf(p.charAt(0)) >= 0;
    }

    public boolean isHangulPiece(String p) {
        return L.containsKey(p) || V.containsKey(p);
    }

    public boolean isEmpty() {
        return l.isEmpty() && v.isEmpty() && t.isEmpty();
    }

    public void reset() {
        l.clear();
        v.clear();
        t.clear();
    }

    private static String join(List<String> a) {
        StringBuilder sb = new StringBuilder();
        for (String s : a) sb.append(s);
        return sb.toString();
    }

    private boolean canJoin(Map<String, String> table, List<String> pieces, String p, boolean vowel) {
        String cur = join(pieces);
        if (!vowel && p.length() == 1 && cur.endsWith(p)) return false; // ㄱ+ㄱ 은 이어 치지 않음
        return table.containsKey(cur + p);
    }

    /** 지금 조합 중인 글자를 화면에 보일 문자열로 */
    public String render() {
        String lc = l.isEmpty() ? null : L.get(join(l));
        String vc = v.isEmpty() ? null : V.get(join(v));
        String tc = t.isEmpty() ? null : T.get(join(t));
        if (lc != null && vc != null) {
            int lo = lc.charAt(0), vo = vc.charAt(0), to = tc == null ? 0 : tc.charAt(0);
            if (lo >= 0x1100 && lo <= 0x1112 && vo >= 0x1161 && vo <= 0x1175
                    && (tc == null || (to >= 0x11A8 && to <= 0x11C2))) {
                int code = 0xAC00 + ((lo - 0x1100) * 21 + (vo - 0x1161)) * 28 + (tc == null ? 0 : to - 0x11A7);
                return String.valueOf((char) code);
            }
            return lc + vc + (tc == null ? "" : tc);
        }
        if (lc != null) {
            String c = C.get(lc);
            return c != null ? c : lc + "ᅠ";
        }
        if (vc != null) {
            String c = C.get(vc);
            return c != null ? c : "ᅟ" + vc;
        }
        return "";
    }

    /** 조합 중인 글자를 확정해 돌려주고 비운다 */
    public String flush() {
        String s = render();
        reset();
        return s;
    }

    /**
     * 조각 하나를 넣는다. 확정되어 밀려난 글자가 있으면 돌려준다 (없으면 "").
     */
    public String input(String p) {
        StringBuilder out = new StringBuilder();
        if (!isVowelPiece(p)) {
            if (isEmpty()) {
                l.add(p);
            } else if (!l.isEmpty() && v.isEmpty()) {
                if (canJoin(L, l, p, false)) l.add(p);
                else { out.append(flush()); l.add(p); }
            } else if (l.isEmpty()) {
                out.append(flush()); l.add(p);
            } else if (t.isEmpty()) {
                if (T.containsKey(p)) t.add(p);
                else { out.append(flush()); l.add(p); }
            } else {
                if (canJoin(T, t, p, false)) t.add(p);
                else { out.append(flush()); l.add(p); }
            }
        } else {
            if (isEmpty() || v.isEmpty()) {
                if (isEmpty()) v.add(p);
                else v.add(p);
            } else if (t.isEmpty()) {
                if (canJoin(V, v, p, true)) v.add(p);
                else { out.append(flush()); v.add(p); }
            } else {
                String last = t.get(t.size() - 1);
                if (L.containsKey(last)) {
                    t.remove(t.size() - 1);
                    out.append(flush());
                    l.add(last);
                    v.add(p);
                } else {
                    out.append(flush());
                    v.add(p);
                }
            }
        }
        return out.toString();
    }

    /** 조합 중인 글자에서 마지막 조각을 지운다. 지울 게 없으면 false */
    public boolean backspace() {
        if (!t.isEmpty()) t.remove(t.size() - 1);
        else if (!v.isEmpty()) v.remove(v.size() - 1);
        else if (!l.isEmpty()) l.remove(l.size() - 1);
        else return false;
        return true;
    }

    private static boolean isConj(int c) {
        return (c >= 0x1100 && c <= 0x11FF) || (c >= 0xA960 && c <= 0xA97F) || (c >= 0xD7B0 && c <= 0xD7FF);
    }

    private static boolean isLead(int c) {
        return (c >= 0x1100 && c <= 0x115F) || (c >= 0xA960 && c <= 0xA97F);
    }

    /** 커서 앞 글자에서 한 글자(옛한글 음절은 통째로)를 지울 때 지워야 할 char 개수 */
    public static int deleteLength(CharSequence before) {
        if (before == null || before.length() == 0) return 1;
        int i = before.length();
        int c = before.charAt(i - 1);
        if (Character.isLowSurrogate((char) c) && i >= 2) return 2;
        i--;
        if (isConj(c) && !isLead(c)) {
            while (i > 0 && isConj(before.charAt(i - 1))) {
                int x = before.charAt(i - 1);
                i--;
                if (isLead(x)) break;
            }
        }
        return before.length() - i;
    }
}
