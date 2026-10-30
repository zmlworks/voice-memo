package com.zmlworks.voicememo;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;

/** One memo: vertical view + voice input + selection actions + horizontal keyboard-edit fallback. */
public class EditorActivity extends Activity implements VerticalTextView.Listener, Speech.Callback {
    static final String EXTRA_ID = "memo_id";
    private static final int REQ_AUDIO = 1, REQ_INTENT = 2;
    private static final long AUTOSAVE_MS = 800;

    private MemoStore store;
    private long id;
    private String savedText = "";
    private VerticalTextView vtv;
    private EditText edit;
    private boolean editMode;
    private LinearLayout selBar, recBar;
    private TextView recStatus, recPartial, titleView;
    private Button editBtn, undoBtn;
    private final UndoStack undo = new UndoStack(100);
    private Speech speech;
    private boolean holdSession;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable autosave = this::save;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = MemoStore.of(this);
        id = b != null ? b.getLong("id", -1) : -1;
        if (id < 0) id = getIntent().getLongExtra(EXTRA_ID, -1);
        if (id < 0 || !store.exists(id)) {
            try { id = store.create(System.currentTimeMillis()); } catch (IOException e) {
                Toast.makeText(this, R.string.err_save, Toast.LENGTH_LONG).show();
                finish();
                return;
            }
        }
        // remember the memo we created: rotation / process death must reopen it, not start another one
        getIntent().putExtra(EXTRA_ID, id);
        savedText = store.load(id);
        buildUi();
        vtv.setText(savedText, savedText.length());
        if (b != null) {
            vtv.restoreState(b.getBundle("vtv"));
            if (b.getBoolean("editMode")) setEditMode(true);
        }
        updateBars();
    }

    // ------------------------------------------------------------------ UI

    private Button btn(LinearLayout parent, int label, View.OnClickListener l) {
        Button bt = new Button(this);
        bt.setText(label);
        bt.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(Ui.dp(this, 6));
        parent.addView(bt, lp);
        return bt;
    }

    private LinearLayout bar(LinearLayout root, int bg) {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setBackgroundColor(bg);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int p = Ui.dp(this, 6);
        row.setPadding(p, p, p, p);
        hs.addView(row);
        root.addView(hs, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        LinearLayout top = bar(root, Ui.BG2);
        btn(top, R.string.back, v -> finish());
        btn(top, R.string.mic, v -> micButton());
        undoBtn = btn(top, R.string.undo, v -> doUndo());
        editBtn = btn(top, R.string.kbd_edit, v -> setEditMode(!editMode));
        btn(top, R.string.share, v -> share(currentText()));
        btn(top, R.string.settings, v -> Prefs.showDialog(this, this::applyPrefs));
        btn(top, R.string.delete_memo, v -> confirmDelete());

        titleView = new TextView(this);
        titleView.setTextColor(Ui.DIM);
        titleView.setTextSize(12);
        titleView.setSingleLine(true);
        titleView.setPadding(Ui.dp(this, 10), Ui.dp(this, 2), Ui.dp(this, 10), Ui.dp(this, 2));
        root.addView(titleView);

        selBar = bar(root, Ui.PANEL);
        btn(selBar, R.string.copy, v -> copySel(false));
        btn(selBar, R.string.cut, v -> copySel(true));
        btn(selBar, R.string.delete, v -> deleteSel());
        btn(selBar, R.string.share, v -> shareSel());
        btn(selBar, R.string.select_all, v -> { vtv.setSelection(0, vtv.getText().length()); updateBars(); });
        btn(selBar, R.string.close, v -> { vtv.clearSelection(); updateBars(); });
        ((View) selBar.getParent()).setVisibility(View.GONE);

        recBar = bar(root, Ui.REC);
        TextView dot = new TextView(this);
        dot.setText(R.string.rec_dot);
        dot.setTextColor(0xFFFF5252);
        dot.setTextSize(20);
        recBar.addView(dot);
        recStatus = new TextView(this);
        recStatus.setTextColor(Ui.TEXT);
        recStatus.setTypeface(Typeface.DEFAULT_BOLD);
        recStatus.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 12), 0);
        recBar.addView(recStatus);
        btn(recBar, R.string.stop, v -> { if (speech != null) speech.stop(); });
        recPartial = new TextView(this);
        recPartial.setTextColor(Ui.HI);
        recPartial.setSingleLine(true);
        recBar.addView(recPartial);
        ((View) recBar.getParent()).setVisibility(View.GONE);

        FrameLayout stage = new FrameLayout(this);
        vtv = new VerticalTextView(this);
        vtv.setListener(this);
        vtv.setTextSizeSp(Prefs.textSize(this));
        stage.addView(vtv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        edit = new EditText(this);
        edit.setGravity(Gravity.TOP | Gravity.START);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        edit.setTextColor(Ui.TEXT);
        edit.setBackgroundColor(Ui.PANEL);
        edit.setTextSize(18);
        edit.setHint(R.string.edit_hint);
        edit.setHintTextColor(Ui.DIM);
        int p = Ui.dp(this, 12);
        edit.setPadding(p, p, p, p);
        edit.setVisibility(View.GONE);
        edit.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int c, int d) {}
            @Override public void onTextChanged(CharSequence s, int a, int c, int d) {}
            @Override public void afterTextChanged(android.text.Editable s) { if (editMode) scheduleSave(); }
        });
        stage.addView(edit, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(stage, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView hint = new TextView(this);
        hint.setText(R.string.gesture_hint);
        hint.setTextColor(Ui.DIM);
        hint.setTextSize(11);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(p / 2, p / 3, p / 2, p / 3);
        root.addView(hint);

        setContentView(root);
        Ui.edgeToEdge(this, root);
    }

    private void applyPrefs() {
        vtv.setTextSizeSp(Prefs.textSize(this));
    }

    private void updateBars() {
        ((View) selBar.getParent()).setVisibility(!editMode && vtv.hasSelection() ? View.VISIBLE : View.GONE);
        undoBtn.setEnabled(!undo.isEmpty());
        undoBtn.setAlpha(undo.isEmpty() ? 0.45f : 1f);
        String t = MemoStore.titleOf(currentText());
        titleView.setText(t.isEmpty() ? getString(R.string.untitled) : t);
    }

    // ------------------------------------------------------------------ text ops

    String currentText() { return editMode ? edit.getText().toString() : vtv.getText(); }

    /** Replace the memo text (vertical mode), with an undo snapshot. */
    private void apply(String newText, int caret, String label) {
        String old = vtv.getText();
        if (old.equals(newText)) return;
        undo.push(old, vtv.getCaret(), label);
        vtv.setText(newText, caret);
        scheduleSave();
        updateBars();
    }

    private void insertAtCaret(String s, String label) {
        if (s == null || s.isEmpty()) return;
        String t = vtv.getText();
        int a, b;
        if (vtv.hasSelection()) { a = vtv.getSelStart(); b = vtv.getSelEnd(); } else { a = b = vtv.getCaret(); }
        apply(t.substring(0, a) + s + t.substring(b), a + s.length(), label);
    }

    private void doUndo() {
        if (editMode) setEditMode(false);
        UndoStack.Entry e = undo.pop();
        if (e == null) return;
        vtv.setText(e.text, e.caret);
        scheduleSave();
        updateBars();
        Toast.makeText(this, getString(R.string.undone, e.label), Toast.LENGTH_SHORT).show();
    }

    private String selected() {
        if (!vtv.hasSelection()) return "";
        return vtv.getText().substring(vtv.getSelStart(), vtv.getSelEnd());
    }

    private void copySel(boolean cut) {
        String s = selected();
        if (s.isEmpty()) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_label), s));
        if (cut) deleteSel(); else { vtv.clearSelection(); updateBars(); }
        Toast.makeText(this, cut ? R.string.cut_done : R.string.copied, Toast.LENGTH_SHORT).show();
    }

    private void deleteSel() {
        if (!vtv.hasSelection()) return;
        String t = vtv.getText();
        int a = vtv.getSelStart(), b = vtv.getSelEnd();
        apply(t.substring(0, a) + t.substring(b), a, getString(R.string.delete));
    }

    private void shareSel() {
        String s = selected();
        if (!s.isEmpty()) share(s);
    }

    private void share(String s) {
        if (s == null || s.isEmpty()) { Toast.makeText(this, R.string.nothing_to_share, Toast.LENGTH_SHORT).show(); return; }
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, s);
        try { startActivity(Intent.createChooser(i, getString(R.string.share))); }
        catch (ActivityNotFoundException e) { Toast.makeText(this, R.string.no_share_app, Toast.LENGTH_SHORT).show(); }
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_memo_q)
                .setMessage(MemoStore.titleOf(currentText()))
                .setPositiveButton(R.string.delete, (d, w) -> {
                    h.removeCallbacks(autosave);
                    store.delete(id);
                    id = -1;
                    finish();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void setEditMode(boolean on) {
        if (on == editMode) return;
        if (speech != null) speech.cancel();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (on) {
            editMode = true;
            edit.setText(vtv.getText());
            int c = Math.min(vtv.getCaret(), edit.length());
            edit.setSelection(c);
            edit.setVisibility(View.VISIBLE);
            vtv.setVisibility(View.GONE);
            editBtn.setText(R.string.vertical_view);
            edit.requestFocus();
            if (imm != null) edit.post(() -> imm.showSoftInput(edit, 0));
        } else {
            String t = edit.getText().toString();
            int c = edit.getSelectionStart();
            editMode = false;
            if (imm != null) imm.hideSoftInputFromWindow(edit.getWindowToken(), 0);
            edit.setVisibility(View.GONE);
            vtv.setVisibility(View.VISIBLE);
            editBtn.setText(R.string.kbd_edit);
            apply(t, c < 0 ? t.length() : c, getString(R.string.kbd_edit));
            vtv.setCaret(c < 0 ? t.length() : c);
        }
        updateBars();
    }

    // ------------------------------------------------------------------ saving

    private void scheduleSave() {
        h.removeCallbacks(autosave);
        h.postDelayed(autosave, AUTOSAVE_MS);
    }

    private void save() {
        h.removeCallbacks(autosave);
        if (id < 0) return;
        String t = currentText();
        if (t.equals(savedText) && store.exists(id)) return;
        try {
            store.save(id, t);
            savedText = t;
        } catch (IOException e) {
            Toast.makeText(this, R.string.err_save, Toast.LENGTH_LONG).show();
        }
        if (titleView != null) updateBars();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (speech != null) speech.cancel();
        save();
        if (isFinishing() && id >= 0 && MemoStore.strip(currentText()).isEmpty()) store.delete(id);
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        if (speech != null) speech.destroy();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putLong("id", id);
        if (vtv != null) out.putBundle("vtv", vtv.saveState());
        out.putBoolean("editMode", editMode);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (editMode) { setEditMode(false); return; }
        if (vtv.hasSelection()) { vtv.clearSelection(); updateBars(); return; }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------ VerticalTextView.Listener

    @Override public void onCaretOrSelectionChanged() { updateBars(); }

    @Override public void onTextSizeChanged(float sp) { Prefs.setTextSize(this, sp); }

    @Override
    public void onLongPress() {
        boolean cont = Prefs.continuous(this);
        startVoice(!cont, true);
    }

    @Override
    public void onHoldRelease() {
        if (holdSession && speech != null && speech.isActive()) speech.stop();
    }

    @Override
    public void onLockedTouch() {
        // a tap anywhere on the text while recording in continuous / single mode stops it
        if (speech != null && speech.isActive()) speech.stop();
    }

    private void micButton() {
        if (speech != null && speech.isActive()) { speech.stop(); return; }
        if (editMode) setEditMode(false);
        // mic button: one utterance (ends on end of speech), or continuous mode if enabled in settings
        startVoice(false, Prefs.continuous(this));
    }

    /** hold = press-and-hold session (release stops); keepGoing = restart after each utterance. */
    private void startVoice(boolean hold, boolean keepGoing) {
        if (vtv.hasSelection()) { vtv.setCaret(vtv.getSelEnd()); vtv.clearSelection(); updateBars(); }
        if (!Speech.serviceAvailable(this)) { launchIntentFallback(); return; }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            return;
        }
        if (speech == null) speech = new Speech(this, this);
        holdSession = hold;
        speech.start(keepGoing, Prefs.preferOffline(this));
    }

    @SuppressWarnings("deprecation")
    private void launchIntentFallback() {
        Intent i = Speech.recognizerIntent(Prefs.preferOffline(this));
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.rec_speak));
        try {
            startActivityForResult(i, REQ_INTENT);
        } catch (ActivityNotFoundException e) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.no_recognizer_title)
                    .setMessage(R.string.no_recognizer)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_INTENT || res != RESULT_OK || data == null) return;
        ArrayList<String> r = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (r != null && !r.isEmpty()) insertAtCaret(r.get(0), getString(R.string.voice));
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req != REQ_AUDIO) return;
        boolean ok = res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED;
        Toast.makeText(this, ok ? R.string.perm_ok : R.string.perm_denied, Toast.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------------ Speech.Callback

    @Override
    public void onSpeechState(boolean active, boolean listening, String status) {
        ((View) recBar.getParent()).setVisibility(active ? View.VISIBLE : View.GONE);
        String mode = holdSession ? getString(R.string.rec_hold) : getString(R.string.rec_tap_stop);
        recStatus.setText(active ? status + (listening ? "  " + mode : "") : "");
        vtv.setLocked(active);
        if (active) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    public void onPartial(String text) {
        vtv.setComposing(text);
        recPartial.setText(text);
    }

    @Override
    public void onFinal(String text) {
        vtv.setComposing("");
        insertAtCaret(text, getString(R.string.voice));
    }

    @Override
    public void onSpeechError(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    /** For tests. */
    VerticalTextView verticalView() { return vtv; }
}
