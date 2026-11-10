package io.github.a3indae.midagekeyboard;

import android.inputmethodservice.InputMethodService;
import android.text.InputType;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import java.util.ArrayDeque;

public class MidageIME extends InputMethodService implements KeyboardView.Listener {

    private Hangul hangul;
    private KeyboardView view;

    // 조합 중인 글자의 위치와, 우리가 예상하는 커서 위치를 기억한다.
    // 편집기가 늦게 보내는 커서 알림을 사용자의 커서 이동으로 착각해 조합이 끊기는 일을 막기 위해서다.
    private boolean composing = false;
    private int compStart = -1;
    private int cursor = -1;
    private final ArrayDeque<Integer> recent = new ArrayDeque<>();

    @Override
    public void onCreate() {
        super.onCreate();
        hangul = new Hangul(this);
    }

    @Override
    public View onCreateInputView() {
        view = new KeyboardView(this);
        view.setListener(this);
        return view;
    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        return false;
    }

    /** 자판 위 투명 공간(미리보기용)은 앱 화면을 가리지도, 터치를 가로채지도 않게 한다 */
    @Override
    public void onComputeInsets(Insets outInsets) {
        super.onComputeInsets(outInsets);
        if (view == null || view.getHeight() == 0) return;
        int top = (int) view.popupSpace();
        int[] loc = new int[2];
        view.getLocationInWindow(loc);
        int kbTop = loc[1] + top;
        outInsets.contentTopInsets = kbTop;
        outInsets.visibleTopInsets = kbTop;
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION;
        outInsets.touchableRegion.set(0, kbTop, view.getWidth(), loc[1] + view.getHeight());
    }

    @Override
    public void onStartInput(EditorInfo info, boolean restarting) {
        super.onStartInput(info, restarting);
        hangul.reset();
        composing = false;
        compStart = -1;
        recent.clear();
        note(info != null && info.initialSelEnd >= 0 ? info.initialSelEnd : -1);
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        if (view != null) view.reloadPrefs();
    }

    @Override
    public void onFinishInput() {
        finishComposing();
        super.onFinishInput();
    }

    private void note(int c) {
        cursor = c;
        recent.addLast(c);
        while (recent.size() > 12) recent.removeFirst();
    }

    @Override
    public void onUpdateSelection(int oldSelStart, int oldSelEnd, int newSelStart, int newSelEnd,
                                  int candidatesStart, int candidatesEnd) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd);
        if (cursor < 0) {           // 위치를 모르던 상태 → 맞춰 둔다
            cursor = newSelEnd;
            if (composing && candidatesStart >= 0) compStart = candidatesStart;
            return;
        }
        boolean moved;
        if (newSelStart != newSelEnd) moved = true;                       // 글자를 선택함
        else if (candidatesStart >= 0) moved = newSelEnd != candidatesEnd; // 조합 글자 밖으로 커서가 감
        else moved = !recent.contains(newSelEnd);                         // 우리가 만든 위치가 아님
        if (!moved) return;
        if (composing) {
            hangul.reset();
            composing = false;
            compStart = -1;
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) ic.finishComposingText();
        }
        recent.clear();
        note(newSelEnd);
    }

    private void finishComposing() {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null && composing) ic.finishComposingText();
        hangul.reset();
        composing = false;
        compStart = -1;
    }

    private void setComposing(InputConnection ic, String s) {
        if (!composing) compStart = cursor;
        ic.setComposingText(s, 1);
        composing = true;
        note(compStart < 0 ? -1 : compStart + s.length());
    }

    private void commit(InputConnection ic, String s) {
        int base = composing ? compStart : cursor;
        ic.commitText(s, 1);
        composing = false;
        compStart = -1;
        note(base < 0 ? -1 : base + s.length());
    }

    private void show(InputConnection ic, String committed) {
        String comp = hangul.render();
        ic.beginBatchEdit();
        if (!committed.isEmpty()) commit(ic, committed);
        if (!comp.isEmpty()) setComposing(ic, comp);
        else if (composing) commit(ic, "");
        ic.endBatchEdit();
    }

    private void commitRaw(String s) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        commit(ic, hangul.flush() + s);
    }

    private void sendKey(InputConnection ic, int code) {
        ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
        ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
        note(-1); // 결과 위치는 편집기가 알려 줄 때 맞춘다
    }

    @Override
    public void onPiece(String piece) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        if (Hangul.TONE1.equals(piece) || Hangul.TONE2.equals(piece)) {
            commitRaw(piece);
        } else if (hangul.isHangulPiece(piece)) {
            String committed = hangul.input(piece);
            show(ic, committed);
        } else {
            commitRaw(piece);
        }
    }

    @Override
    public void onText(String s) {
        commitRaw(s);
    }

    @Override
    public void onBackspace() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        if (!hangul.isEmpty()) {
            hangul.backspace();
            show(ic, "");
            return;
        }
        CharSequence sel = ic.getSelectedText(0);
        if (!TextUtils.isEmpty(sel)) {
            ic.commitText("", 1);
            note(-1);
            return;
        }
        CharSequence before = ic.getTextBeforeCursor(16, 0);
        if (before == null || before.length() == 0) {
            sendKey(ic, KeyEvent.KEYCODE_DEL);
            return;
        }
        int n = Hangul.deleteLength(before);
        ic.deleteSurroundingText(n, 0);
        note(cursor < 0 ? -1 : cursor - n);
    }

    @Override
    public void onSpace() {
        commitRaw(" ");
    }

    @Override
    public void onCursorStart() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        if (composing || !hangul.isEmpty()) commit(ic, hangul.flush());
    }

    @Override
    public void onCursor(int dir) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        sendKey(ic, dir < 0 ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT);
    }

    @Override
    public void onEnter() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        String c = hangul.flush();
        if (!c.isEmpty() || composing) commit(ic, c);
        EditorInfo ei = getCurrentInputEditorInfo();
        int action = ei == null ? EditorInfo.IME_ACTION_NONE : ei.imeOptions & EditorInfo.IME_MASK_ACTION;
        boolean multiLine = ei != null && (ei.inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0;
        boolean noEnterAction = ei != null && (ei.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0;
        if (!multiLine && !noEnterAction && action != EditorInfo.IME_ACTION_NONE
                && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action);
        } else {
            sendKey(ic, KeyEvent.KEYCODE_ENTER);
        }
    }
}
