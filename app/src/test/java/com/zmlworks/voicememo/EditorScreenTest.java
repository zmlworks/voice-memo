package com.zmlworks.voicememo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import java.io.File;
import java.io.FileOutputStream;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

/** Real EditorActivity on the JVM: gesture smoke tests through MotionEvents + PNG of the vertical text. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "w411dp-h891dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class EditorScreenTest {
    static final String SAMPLE = "縦書き音声メモ\n"
            + "今日は「ワークショップ」の打ち合わせ。午後三時から、会議室Ｂで。\n"
            + "持ち物：ノート、ペン、資料（２部）。\n"
            + "コーヒーを買っておくこと～\n"
            + "音声入力は長押しで、話し終わったら指を離す。";

    private long t0 = SystemClock.uptimeMillis();

    private MotionEvent ev(int action, float x, float y, long t) {
        return MotionEvent.obtain(t0, t, action, x, y, 0);
    }

    private ActivityController<EditorActivity> open(String text) throws Exception {
        MemoStore store = MemoStore.of(RuntimeEnvironment.getApplication());
        long id = store.create(System.currentTimeMillis());
        store.save(id, text);
        Intent i = new Intent(RuntimeEnvironment.getApplication(), EditorActivity.class).putExtra(EditorActivity.EXTRA_ID, id);
        ActivityController<EditorActivity> ac = Robolectric.buildActivity(EditorActivity.class, i).setup();
        View root = ac.get().getWindow().getDecorView();
        int w = 1233, h = 2673;
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, w, h);
        return ac;
    }

    private void drag(VerticalTextView v, float x0, float y0, float x1, float y1) {
        long t = t0;
        v.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, x0, y0, t));
        for (int i = 1; i <= 20; i++) {
            float f = i / 20f;
            v.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, x0 + (x1 - x0) * f, y0 + (y1 - y0) * f, t += 16));
        }
        v.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, x1, y1, t + 16));
    }

    @Test
    public void newMemoKeepsItsIdAcrossRecreation() {
        MemoStore store = MemoStore.of(RuntimeEnvironment.getApplication());
        int before = store.list().size();
        Intent i = new Intent(RuntimeEnvironment.getApplication(), EditorActivity.class);   // "new memo": no id
        ActivityController<EditorActivity> ac = Robolectric.buildActivity(EditorActivity.class, i).setup();
        assertEquals(before + 1, store.list().size());
        long id = ac.get().getIntent().getLongExtra(EditorActivity.EXTRA_ID, -1);
        assertTrue(id >= 0);
        ac.recreate();   // rotation: same memo, no second file
        ShadowLooper.idleMainLooper();
        assertEquals(before + 1, store.list().size());
        assertEquals(id, ac.get().getIntent().getLongExtra(EditorActivity.EXTRA_ID, -1));

        // process death: only the saved instance state survives
        android.os.Bundle saved = new android.os.Bundle();
        ac.saveInstanceState(saved);
        ActivityController<EditorActivity> ac2 = Robolectric.buildActivity(EditorActivity.class,
                new Intent(RuntimeEnvironment.getApplication(), EditorActivity.class)).setup(saved);
        assertEquals(before + 1, store.list().size());
        assertEquals(id, ac2.get().getIntent().getLongExtra(EditorActivity.EXTRA_ID, -1));
    }

    @Test
    public void gesturesAndScreenshot() throws Exception {
        StringBuilder longText = new StringBuilder(SAMPLE);
        for (int i = 0; i < 12; i++) longText.append("\n追加の行").append(i + 1).append("：あいうえおかきくけこさしすせそ");
        ActivityController<EditorActivity> ac = open(longText.toString());
        EditorActivity a = ac.get();
        VerticalTextView v = a.verticalView();
        assertTrue(v.getWidth() > 0 && v.getHeight() > 0);
        assertEquals(longText.toString(), v.getText());
        float W = v.getWidth();

        // tap on column 0, near the top -> caret near the start
        long t = t0;
        v.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, W - 80, 170, t));
        v.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, W - 80, 170, t + 50));
        assertTrue("caret " + v.getCaret(), v.getCaret() <= 2);
        assertFalse(v.hasSelection());

        // horizontal swipe right -> scrolls (reveals later columns on the left)
        float before = v.getOffset();
        drag(v, 300, 1200, 900, 1220);
        assertTrue("scrolled", v.getOffset() > before + 300);
        assertFalse(v.hasSelection());
        // and back
        drag(v, 900, 1200, 100, 1200);
        assertEquals(0f, v.getOffset(), 0.5f);

        // diagonal swipe -> nothing happens
        int caret = v.getCaret();
        drag(v, W - 300, 600, W - 700, 1000);
        assertEquals(0f, v.getOffset(), 0.5f);
        assertFalse(v.hasSelection());
        assertEquals(caret, v.getCaret());

        // vertical swipe down along column 1 (second line) -> selection from start point to current point
        drag(v, W - 200, 50, W - 200, 900);
        assertTrue("selection", v.hasSelection());
        String sel = v.getText().substring(v.getSelStart(), v.getSelEnd());
        assertTrue(sel, sel.startsWith("今日"));

        // simulate a live partial result for the screenshot
        v.clearSelection();
        v.setCaret(v.getText().indexOf("会議室"));
        v.setSelection(v.getText().indexOf("午後"), v.getText().indexOf("から"));
        v.setComposing("");
        ShadowLooper.idleMainLooper();

        View root = a.getWindow().getDecorView();
        Bitmap bmp = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(bmp));
        File dir = new File("build/reports/voice-memo");
        assertTrue(dir.isDirectory() || dir.mkdirs());
        try (FileOutputStream os = new FileOutputStream(new File(dir, "editor.png"))) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
        }

        // composing text (live partial result) is display-only and does not change the memo
        v.clearSelection();
        String text = v.getText();
        v.setComposing("こんにちは");
        assertEquals(text, v.getText());
        v.setComposing("");
        ac.pause().stop().destroy();
    }

    @Test
    public void listShowsMemos() throws Exception {
        MemoStore store = MemoStore.of(RuntimeEnvironment.getApplication());
        store.save(store.create(1), "一行目のタイトル\n本文");
        ActivityController<MainActivity> ac = Robolectric.buildActivity(MainActivity.class).setup();
        android.widget.ListView lv = find(ac.get().getWindow().getDecorView(), android.widget.ListView.class);
        assertEquals(1, lv.getAdapter().getCount());
        assertEquals("一行目のタイトル", ((MemoStore.Memo) lv.getAdapter().getItem(0)).title);
    }

    @SuppressWarnings("unchecked")
    static <T extends View> T find(View v, Class<T> c) {
        if (c.isInstance(v)) return (T) v;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                T r = find(g.getChildAt(i), c);
                if (r != null) return r;
            }
        }
        return null;
    }
}
