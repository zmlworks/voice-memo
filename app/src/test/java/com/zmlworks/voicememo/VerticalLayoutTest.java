package com.zmlworks.voicememo;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class VerticalLayoutTest {
    static void cell(VerticalLayout l, int k, int start, int col, int row) {
        assertEquals("start of cell " + k, start, l.cellStart(k));
        assertEquals("col of cell " + k, col, l.cellCol(k));
        assertEquals("row of cell " + k, row, l.cellRow(k));
    }

    @Test public void emptyText() {
        VerticalLayout l = new VerticalLayout("", 5);
        assertEquals(0, l.cellCount());
        assertEquals(1, l.columnCount());
        assertArrayEquals(new int[]{0, 0}, l.caretPos(0));
        assertEquals(0, l.hitTest(0, 2.3f));
        assertEquals(0, l.hitTest(3, 0f));
    }

    @Test public void positionsAndColumnWrap() {
        VerticalLayout l = new VerticalLayout("あいうえおかき", 3);
        assertEquals(7, l.cellCount());
        assertEquals(3, l.columnCount());
        cell(l, 0, 0, 0, 0); cell(l, 2, 2, 0, 2);
        cell(l, 3, 3, 1, 0); cell(l, 5, 5, 1, 2);
        cell(l, 6, 6, 2, 0);
        assertArrayEquals(new int[]{2, 1}, l.caretPos(7));
        assertArrayEquals(new int[]{1, 0}, l.caretPos(3));
    }

    @Test public void newlineStartsNewColumn() {
        VerticalLayout l = new VerticalLayout("あい\nう\n\nえ\n", 5);
        // あ(0,0) い(0,1) \n(0,2) う(1,0) \n(1,1) \n(2,0) え(3,0) \n(3,1) -> trailing empty column 4
        assertEquals(5, l.columnCount());
        cell(l, 2, 2, 0, 2); assertTrue(l.isNewline(2));
        cell(l, 3, 3, 1, 0);
        cell(l, 5, 5, 2, 0); assertTrue(l.isNewline(5));
        cell(l, 6, 6, 3, 0);
        assertArrayEquals(new int[]{4, 0}, l.caretPos(l.textLength()));
        assertArrayEquals(new int[]{0, 2}, l.caretPos(2)); // caret before the newline = end of column 0
        assertEquals(-1, l.columnFirstCell(4));
    }

    @Test public void newlineExactlyAtFullColumnDoesNotAddBlankColumn() {
        VerticalLayout l = new VerticalLayout("あいう\nえ", 3);
        // newline sits at row 3 of column 0 (invisible), え starts column 1
        assertEquals(2, l.columnCount());
        cell(l, 4, 4, 1, 0);
    }

    @Test public void hangingPunctuation() {
        VerticalLayout l = new VerticalLayout("あいう。えお、、か", 3);
        cell(l, 3, 3, 0, 3);   // 。 hangs below row 2
        cell(l, 4, 4, 1, 0);   // え
        cell(l, 6, 6, 1, 2);   // 、
        cell(l, 7, 7, 1, 3);   // second 、 hangs
        cell(l, 8, 8, 2, 0);   // か wraps
        VerticalLayout m = new VerticalLayout("あいうえ。", 3);
        cell(m, 3, 3, 1, 0);   // え wraps normally (not punctuation)
        cell(m, 4, 4, 1, 1);
    }

    @Test public void surrogatePairsAreOneCell() {
        String s = "a😀b";
        VerticalLayout l = new VerticalLayout(s, 10);
        assertEquals(3, l.cellCount());
        assertEquals(1, l.cellStart(1));
        assertEquals(3, l.cellEnd(1));
        cell(l, 2, 3, 0, 2);
        assertEquals(1, l.snap(2));     // never inside the pair
        assertEquals(3, l.hitTest(0, 1.7f));
        assertEquals(1, l.hitTest(0, 1.2f));
    }

    @Test public void hitTestCaretIndex() {
        VerticalLayout l = new VerticalLayout("あいうえお\nかき", 4);
        // col0: あいうえ  col1: お \n  col2: かき
        assertEquals(0, l.hitTest(0, 0.2f));
        assertEquals(1, l.hitTest(0, 0.7f));
        assertEquals(2, l.hitTest(0, 1.9f) - 0); // lower half of い -> after い
        assertEquals(0, l.hitTest(0, -3f));     // above the column -> column start
        assertEquals(4, l.hitTest(0, 9f));      // below a full column -> after え
        assertEquals(5, l.hitTest(1, 3f));      // below お in a column ended by newline -> before newline
        assertEquals(5, l.hitTest(1, 1.6f));    // on the newline cell -> before newline
        assertEquals(6, l.hitTest(2, 0.1f));
        assertEquals(8, l.hitTest(2, 5f));
        assertEquals(8, l.hitTest(7, 0f));      // left of all columns -> end
        assertEquals(0, l.hitTest(-2, 0f));     // right of column 0 -> start of column 0
    }

    @Test public void hitTestPixels() {
        VerticalLayout l = new VerticalLayout("あいうえおかきくけこ", 5);
        float right = 1000, top = 50, colW = 70, rowH = 44;
        // column 0 spans x 930..1000, column 1 spans 860..930
        assertEquals(0, l.hitTestPx(965, 55, right, top, colW, rowH));
        assertEquals(3, l.hitTestPx(940, 50 + 2 * 44 + 30, right, top, colW, rowH)); // lower half of う
        assertEquals(7, l.hitTestPx(900, 50 + 2 * 44 + 5, right, top, colW, rowH));  // upper half of く
        assertEquals(10, l.hitTestPx(500, 60, right, top, colW, rowH));
        assertEquals(0, l.hitTestPx(1080, 60, right, top, colW, rowH));
    }

    @Test public void selectionRangeFromAnchorToCurrent() {
        VerticalLayout l = new VerticalLayout("あいうえおかきくけこ", 5);
        int anchor = l.hitTest(0, 1.1f);   // before い
        int cur = l.hitTest(1, 2.8f);      // lower half of く -> after く
        assertEquals(1, Math.min(anchor, cur));
        assertEquals(8, Math.max(anchor, cur));
        // dragging backwards gives the same normalised range
        int back = l.hitTest(0, 0.1f);
        assertEquals(0, Math.min(anchor, back));
        assertEquals(1, Math.max(anchor, back));
    }

    @Test public void glyphKinds() {
        assertEquals(VerticalLayout.Kind.UPRIGHT, VerticalLayout.kind('あ'));
        assertEquals(VerticalLayout.Kind.UPRIGHT, VerticalLayout.kind('漢'));
        assertEquals(VerticalLayout.Kind.UPRIGHT, VerticalLayout.kind('A'));
        assertEquals(VerticalLayout.Kind.UPRIGHT, VerticalLayout.kind('7'));
        assertEquals(VerticalLayout.Kind.TOP_RIGHT, VerticalLayout.kind('、'));
        assertEquals(VerticalLayout.Kind.TOP_RIGHT, VerticalLayout.kind('。'));
        assertEquals(VerticalLayout.Kind.ROTATE, VerticalLayout.kind('ー'));
        assertEquals(VerticalLayout.Kind.ROTATE, VerticalLayout.kind('「'));
        assertEquals(VerticalLayout.Kind.ROTATE, VerticalLayout.kind('）'));
        assertEquals(VerticalLayout.Kind.ROTATE, VerticalLayout.kind('～'));
        assertEquals(VerticalLayout.Kind.SMALL, VerticalLayout.kind('っ'));
        assertEquals(VerticalLayout.Kind.NEWLINE, VerticalLayout.kind('\n'));
        assertFalse(VerticalLayout.hangs('あ'));
    }

    @Test public void cellOfLargeText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20000; i++) sb.append(i % 97 == 0 ? '\n' : 'あ');
        VerticalLayout l = new VerticalLayout(sb, 17);
        for (int i = 0; i < sb.length(); i += 37) assertEquals(i, l.cellStart(l.cellOf(i)));
        int[] p = l.caretPos(sb.length());
        assertTrue(p[0] > 1000);
    }
}
