package com.zmlworks.voicememo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.OverScroller;

/**
 * Read-mostly vertical-writing (tategaki) text view.
 * Columns run top→bottom and flow right→left; the view scrolls horizontally.
 * Gestures: tap = caret · long-press = voice (hold) · horizontal drag = scroll (+fling) ·
 * vertical-start drag = select · diagonal start = nothing · pinch = text size.
 */
public class VerticalTextView extends View {
    interface Listener {
        void onCaretOrSelectionChanged();
        void onLongPress();
        void onHoldRelease();
        void onLockedTouch();
        void onTextSizeChanged(float sp);
    }

    private static final int IDLE = 0, PENDING = 1, SCROLL = 2, SELECT = 3, NOOP = 4, HOLD = 5, PINCH = 6;

    private String text = "";
    private String composing = "";
    private int caret, selStart = -1, selEnd = -1;
    private float textSizeSp = 24;
    private float ts, rowH, colW;
    private VerticalLayout layout;   // layout of the display text (text with composing inserted at caret)
    private int layoutRows = -1;
    private boolean dirty = true;
    private float offset;            // >= 0: how far the content is moved right (to show later columns)
    private boolean locked;
    private Listener listener;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint();
    private final Paint line = new Paint();
    private final float dp;
    private final int touchSlop;
    private final float decidePx;
    private final int minFling, maxFling;
    private final OverScroller scroller;
    private final ScaleGestureDetector scaleDetector;
    private VelocityTracker vt;

    private int state = IDLE;
    private float downX, downY, lastX;
    private int anchorLo, anchorHi;
    private float scaleAccum = 1f;

    private final Runnable longPress = () -> {
        if (state != PENDING) return;
        state = HOLD;
        clearSelectionInternal();
        setCaretInternal(hitTest(downX, downY));
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        invalidate();
        if (listener != null) {
            listener.onCaretOrSelectionChanged();
            listener.onLongPress();
        }
    };

    public VerticalTextView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        ViewConfiguration vc = ViewConfiguration.get(c);
        touchSlop = vc.getScaledTouchSlop();
        decidePx = DirectionClassifier.DECIDE_DP * dp;
        minFling = vc.getScaledMinimumFlingVelocity();
        maxFling = vc.getScaledMaximumFlingVelocity();
        scroller = new OverScroller(c);
        paint.setColor(Ui.TEXT);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(Typeface.create(Typeface.SERIF, Typeface.NORMAL));
        line.setStrokeWidth(Math.max(1f, dp));
        setFocusable(true);
        setContentDescription(c.getString(R.string.cd_text));
        scaleDetector = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                scaleAccum *= d.getScaleFactor();
                float sp = Prefs.clampSize(textSizeSp * scaleAccum);
                if (Math.abs(sp - textSizeSp) >= 1f) {
                    scaleAccum = 1f;
                    setTextSizeSp(sp);
                    if (listener != null) listener.onTextSizeChanged(sp);
                }
                return true;
            }
        });
        updateMetrics();
    }

    void setListener(Listener l) { listener = l; }

    // ---------------------------------------------------------------- model

    String getText() { return text; }

    void setText(String t, int newCaret) {
        text = t == null ? "" : t;
        composing = "";
        clearSelectionInternal();
        dirty = true;
        setCaretInternal(newCaret);
        ensureCaretVisible();
        invalidate();
    }

    int getCaret() { return caret; }
    boolean hasSelection() { return selStart >= 0 && selEnd > selStart; }
    int getSelStart() { return selStart; }
    int getSelEnd() { return selEnd; }

    void setSelection(int a, int b) {
        int s = clamp(Math.min(a, b)), e = clamp(Math.max(a, b));
        if (s == e) { clearSelectionInternal(); } else { selStart = s; selEnd = e; }
        invalidate();
    }

    void clearSelection() { clearSelectionInternal(); invalidate(); }

    private void clearSelectionInternal() { selStart = -1; selEnd = -1; }

    void setCaret(int p) { setCaretInternal(p); ensureCaretVisible(); invalidate(); }

    private void setCaretInternal(int p) { caret = clamp(p); }

    private int clamp(int p) { return Math.max(0, Math.min(text.length(), p)); }

    /** Live (partial) recognition text, shown at the caret but not part of the memo yet. */
    void setComposing(String s) {
        s = s == null ? "" : s;
        if (s.equals(composing)) return;
        composing = s;
        dirty = true;
        ensureVisibleIndex(caret + composing.length());
        invalidate();
    }

    String getComposing() { return composing; }

    void setLocked(boolean l) { locked = l; }

    float getTextSizeSp() { return textSizeSp; }

    void setTextSizeSp(float sp) {
        textSizeSp = Prefs.clampSize(sp);
        updateMetrics();
        dirty = true;
        requestLayoutIfNeeded();
        clampOffset();
        invalidate();
    }

    private void updateMetrics() {
        ts = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, textSizeSp, getResources().getDisplayMetrics());
        paint.setTextSize(ts);
        rowH = ts * 1.12f;
        colW = ts * 1.75f;
    }

    private void requestLayoutIfNeeded() { /* size is MATCH_PARENT; nothing to re-measure */ }

    // ---------------------------------------------------------------- geometry

    private float topY() { return getPaddingTop() + 10 * dp; }

    private float rightX() { return getWidth() - getPaddingRight() - 6 * dp + offset; }

    private int rowsFor(int h) {
        float avail = h - getPaddingTop() - getPaddingBottom() - 10 * dp - 6 * dp;
        return Math.max(1, (int) ((avail - 0.55f * rowH) / rowH));
    }

    private String displayText() {
        if (composing.isEmpty()) return text;
        return text.substring(0, caret) + composing + text.substring(caret);
    }

    VerticalLayout layout() {
        int rows = rowsFor(getHeight());
        if (dirty || layout == null || rows != layoutRows) {
            layout = new VerticalLayout(displayText(), rows);
            layoutRows = rows;
            dirty = false;
        }
        return layout;
    }

    private float contentWidth() { return layout().columnCount() * colW + 12 * dp; }

    private float maxOffset() {
        float avail = getWidth() - getPaddingLeft() - getPaddingRight();
        return Math.max(0, contentWidth() - avail);
    }

    private void clampOffset() {
        if (getWidth() == 0) return;
        offset = Math.max(0, Math.min(maxOffset(), offset));
    }

    /** Caret index (in memo text) for a view-coordinate point. Only valid while not composing. */
    int hitTest(float x, float y) {
        VerticalLayout l = layout();
        int i = l.hitTestPx(x, y, rightX(), topY(), colW, rowH);
        if (!composing.isEmpty()) {
            if (i > caret + composing.length()) i -= composing.length();
            else if (i > caret) i = caret;
        }
        return clamp(l.snap(i));
    }

    void ensureCaretVisible() { ensureVisibleIndex(caret); }

    private void ensureVisibleIndex(int idx) {
        if (getWidth() == 0) return;
        int c = layout().caretPos(Math.min(idx, layout().textLength()))[0];
        float colRight = getWidth() - getPaddingRight() - 6 * dp - c * colW; // at offset 0
        float colLeft = colRight - colW;
        float left = getPaddingLeft(), right = getWidth() - getPaddingRight();
        if (colLeft + offset < left) offset = left - colLeft + 4 * dp;
        else if (colRight + offset > right) offset = right - colRight;
        clampOffset();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        dirty = true;
        clampOffset();
        if (ow != 0) ensureCaretVisible(); // first layout: show the beginning of the memo (rightmost column)
    }

    // ---------------------------------------------------------------- drawing

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        VerticalLayout l = layout();
        float right = rightX(), top = topY();
        float bottom = top + (l.rows + 0.6f) * rowH;
        float vl = getPaddingLeft(), vr = getWidth() - getPaddingRight();
        int firstCol = Math.max(0, (int) Math.floor((right - vr) / colW));
        int lastCol = (int) Math.floor((right - vl) / colW);

        // ruled column lines (chalkboard notebook)
        line.setColor(Ui.GRID);
        for (int c = firstCol; c <= lastCol + 1; c++) {
            float x = right - c * colW;
            if (x >= vl && x <= vr) canvas.drawLine(x, top - 4 * dp, x, bottom, line);
        }

        if (text.isEmpty() && composing.isEmpty()) drawHint(canvas, right, top);

        Paint.FontMetrics fm = paint.getFontMetrics();
        float base = -(fm.ascent + fm.descent) / 2f;
        int compStart = composing.isEmpty() ? -1 : caret, compEnd = caret + composing.length();
        int s0 = selStart, s1 = selEnd;
        if (!composing.isEmpty() && hasSelection()) { s0 = -1; s1 = -1; }

        int startCell = Math.max(0, l.columnFirstCellAtOrAfter(firstCol));
        fill.setColor(0x6600A2E8);
        for (int k = startCell; k < l.cellCount(); k++) {
            int c = l.cellCol(k);
            if (c > lastCol) break;
            if (c < firstCol) continue;
            float cr = right - c * colW, cx = cr - colW / 2f;
            float cellTop = top + l.cellRow(k) * rowH, cy = cellTop + rowH / 2f;
            int si = l.cellStart(k);
            boolean inComp = compStart >= 0 && si >= compStart && si < compEnd;
            if (s0 >= 0 && si >= s0 && si < s1) {
                canvas.drawRect(cr - colW * 0.82f, cellTop, cr - colW * 0.18f, cellTop + rowH, fill);
            }
            if (l.isNewline(k)) continue;
            int cp = l.text.codePointAt(si);
            String g = l.text.substring(si, l.cellEnd(k));
            paint.setColor(inComp ? Ui.HI : Ui.TEXT);
            switch (VerticalLayout.kind(cp)) {
                case ROTATE:
                    canvas.save();
                    canvas.rotate(90, cx, cy);
                    canvas.drawText(g, cx, cy + base, paint);
                    canvas.restore();
                    break;
                case TOP_RIGHT:
                    canvas.drawText(g, cx + 0.6f * ts, cy + base - 0.5f * ts, paint);
                    break;
                case SMALL:
                    canvas.drawText(g, cx + 0.1f * ts, cy + base - 0.1f * ts, paint);
                    break;
                default:
                    canvas.drawText(g, cx, cy + base, paint);
            }
            if (inComp) {
                line.setColor(Ui.HI);
                float ux = cx + ts * 0.62f;
                canvas.drawLine(ux, cellTop, ux, cellTop + rowH, line);
            }
        }

        // caret (horizontal bar across the column) when there is no selection
        if (!hasSelection() || !composing.isEmpty()) {
            int[] p = l.caretPos(composing.isEmpty() ? caret : compEnd);
            float cr = right - p[0] * colW;
            float y = top + p[1] * rowH;
            fill.setColor(Ui.HI);
            canvas.drawRect(cr - colW * 0.85f, y - 1.5f * dp, cr - colW * 0.15f, y + 1.5f * dp, fill);
        }
    }

    private void drawHint(Canvas canvas, float right, float top) {
        String hint = getContext().getString(R.string.empty_hint);
        VerticalLayout h = new VerticalLayout(hint, layoutRows > 0 ? layoutRows : 12);
        Paint.FontMetrics fm = paint.getFontMetrics();
        float base = -(fm.ascent + fm.descent) / 2f;
        paint.setColor(Ui.DIM);
        for (int k = 0; k < h.cellCount(); k++) {
            if (h.isNewline(k)) continue;
            float cx = right - (h.cellCol(k) + 1) * colW - colW / 2f; // leave column 0 for the caret
            float cy = top + h.cellRow(k) * rowH + rowH / 2f;
            String g = hint.substring(h.cellStart(k), h.cellEnd(k));
            VerticalLayout.Kind kd = VerticalLayout.kind(g.codePointAt(0));
            if (kd == VerticalLayout.Kind.ROTATE) {
                canvas.save(); canvas.rotate(90, cx, cy); canvas.drawText(g, cx, cy + base, paint); canvas.restore();
            } else if (kd == VerticalLayout.Kind.TOP_RIGHT) {
                canvas.drawText(g, cx + 0.6f * ts, cy + base - 0.5f * ts, paint);
            } else {
                canvas.drawText(g, cx, cy + base, paint);
            }
        }
    }

    // ---------------------------------------------------------------- touch

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (state != HOLD) scaleDetector.onTouchEvent(e);
        if (vt == null) vt = VelocityTracker.obtain();
        vt.addMovement(e);
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                scroller.forceFinished(true);
                downX = x; downY = y; lastX = x;
                if (locked) {
                    state = NOOP;
                    if (listener != null) listener.onLockedTouch();
                    return true;
                }
                state = PENDING;
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (state != HOLD && state != NOOP) {
                    removeCallbacks(longPress);
                    state = PINCH;
                    scaleAccum = 1f;
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                onMove(x, y);
                return true;
            case MotionEvent.ACTION_UP:
                removeCallbacks(longPress);
                if (state == PENDING) {
                    performClick();
                    clearSelectionInternal();
                    setCaretInternal(hitTest(x, y));
                    invalidate();
                    if (listener != null) listener.onCaretOrSelectionChanged();
                } else if (state == SCROLL) {
                    vt.computeCurrentVelocity(1000, maxFling);
                    float v = vt.getXVelocity();
                    if (Math.abs(v) > minFling) {
                        scroller.fling((int) offset, 0, (int) v, 0, 0, (int) maxOffset(), 0, 0);
                        postInvalidateOnAnimation();
                    }
                } else if (state == SELECT) {
                    if (listener != null) listener.onCaretOrSelectionChanged();
                } else if (state == HOLD) {
                    if (listener != null) listener.onHoldRelease();
                }
                endGesture();
                return true;
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPress);
                if (state == HOLD && listener != null) listener.onHoldRelease();
                if (state == SELECT && listener != null) listener.onCaretOrSelectionChanged();
                endGesture();
                return true;
            default:
                return true;
        }
    }

    private void endGesture() {
        state = IDLE;
        if (vt != null) { vt.recycle(); vt = null; }
    }

    private void onMove(float x, float y) {
        switch (state) {
            case PENDING: {
                float dx = x - downX, dy = y - downY;
                if (dx * dx + dy * dy > (float) touchSlop * touchSlop) removeCallbacks(longPress);
                DirectionClassifier.Dir d = DirectionClassifier.classify(dx, dy, decidePx);
                if (d == DirectionClassifier.Dir.HORIZONTAL) {
                    removeCallbacks(longPress);
                    state = SCROLL;
                    scrollBy(dx);
                    lastX = x;
                } else if (d == DirectionClassifier.Dir.VERTICAL) {
                    removeCallbacks(longPress);
                    state = SELECT;
                    anchorLo = hitCell(downX, downY, false);
                    anchorHi = hitCell(downX, downY, true);
                    updateSelection(x, y);
                } else if (d == DirectionClassifier.Dir.DIAGONAL) {
                    removeCallbacks(longPress);
                    state = NOOP; // harmless: nothing happens until the finger lifts
                }
                break;
            }
            case SCROLL:
                scrollBy(x - lastX);
                lastX = x;
                break;
            case SELECT: {
                float edge = 28 * dp;
                if (x < getPaddingLeft() + edge) scrollBy(6 * dp);
                else if (x > getWidth() - getPaddingRight() - edge) scrollBy(-6 * dp);
                updateSelection(x, y);
                break;
            }
            default:
                break;
        }
    }

    /** Index before (after=false) or after (after=true) the character cell under the point. */
    private int hitCell(float x, float y, boolean after) {
        float top = topY();
        float r = (float) Math.floor((y - top) / rowH);
        return hitTest(x, top + (r + (after ? 0.99f : 0.01f)) * rowH);
    }

    /** Selection always includes the character under the start point and under the finger. */
    private void updateSelection(float x, float y) {
        int curLo = hitCell(x, y, false), curHi = hitCell(x, y, true);
        if (curLo >= anchorLo) { selStart = anchorLo; selEnd = Math.max(anchorHi, curHi); }
        else { selStart = curLo; selEnd = anchorHi; }
        if (selEnd <= selStart) {
            clearSelectionInternal();
            setCaretInternal(anchorLo);
        } else {
            setCaretInternal(curLo >= anchorLo ? selEnd : selStart);
        }
        invalidate();
        if (listener != null) listener.onCaretOrSelectionChanged();
    }

    private void scrollBy(float dx) {
        offset += dx;
        clampOffset();
        invalidate();
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            offset = scroller.getCurrX();
            clampOffset();
            postInvalidateOnAnimation();
        }
    }

    @Override
    public boolean performClick() { return super.performClick(); }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setText(text);
    }

    // ---------------------------------------------------------------- state

    Bundle saveState() {
        Bundle b = new Bundle();
        b.putInt("caret", caret);
        b.putFloat("offset", offset);
        return b;
    }

    void restoreState(Bundle b) {
        if (b == null) return;
        setCaretInternal(b.getInt("caret", caret));
        offset = b.getFloat("offset", 0);
        clampOffset();
        invalidate();
    }

    /** For tests/screenshots. */
    float getOffset() { return offset; }
}
