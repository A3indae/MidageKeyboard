package io.github.a3indae.midagekeyboard;

import android.inputmethodservice.InputMethodService;
import android.text.InputType;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

public class MidageIME extends InputMethodService implements KeyboardView.Listener {

    private Hangul hangul;
    private KeyboardView view;
    private boolean composing = false;

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
    public void onStartInput(EditorInfo info, boolean restarting) {
        super.onStartInput(info, restarting);
        hangul.reset();
        composing = false;
    }

    @Override
    public void onFinishInput() {
        finishComposing();
        super.onFinishInput();
    }

    @Override
    public void onUpdateSelection(int oldSelStart, int oldSelEnd, int newSelStart, int newSelEnd,
                                  int candidatesStart, int candidatesEnd) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd);
        // 사용자가 커서를 옮기면 조합을 끝낸다
        if (composing && (candidatesStart == -1 || newSelStart != candidatesEnd || newSelEnd != candidatesEnd)) {
            hangul.reset();
            composing = false;
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) ic.finishComposingText();
        }
    }

    private void finishComposing() {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null && composing) ic.finishComposingText();
        hangul.reset();
        composing = false;
    }

    private void show(InputConnection ic, String committed) {
        String comp = hangul.render();
        ic.beginBatchEdit();
        if (!committed.isEmpty()) ic.commitText(committed, 1);
        if (comp.isEmpty()) {
            if (composing && committed.isEmpty()) ic.commitText("", 1);
            composing = false;
        } else {
            ic.setComposingText(comp, 1);
            composing = true;
        }
        ic.endBatchEdit();
    }

    private void commitRaw(String s) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        String c = hangul.flush();
        ic.commitText(c + s, 1);
        composing = false;
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
            return;
        }
        CharSequence before = ic.getTextBeforeCursor(16, 0);
        if (before == null || before.length() == 0) {
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL));
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL));
            return;
        }
        ic.deleteSurroundingText(Hangul.deleteLength(before), 0);
    }

    @Override
    public void onSpace() {
        commitRaw(" ");
    }

    @Override
    public void onEnter() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        String c = hangul.flush();
        if (!c.isEmpty()) ic.commitText(c, 1);
        composing = false;
        EditorInfo ei = getCurrentInputEditorInfo();
        int action = ei == null ? EditorInfo.IME_ACTION_NONE : ei.imeOptions & EditorInfo.IME_MASK_ACTION;
        boolean multiLine = ei != null && (ei.inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0;
        boolean noEnterAction = ei != null && (ei.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0;
        if (!multiLine && !noEnterAction && action != EditorInfo.IME_ACTION_NONE
                && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action);
        } else {
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
        }
    }
}
