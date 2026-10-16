package com.zmlworks.voicememo;

/**
 * Pure (Android-free) tategaki layout: text is split into cells (one code point each, '\n' is an invisible
 * cell that ends its column). Columns run top-to-bottom and are numbered 0,1,2… from the right.
 * A column holds {@code rows} cells; a closing punctuation mark that would start a new column hangs
 * below the last row instead (burasage, at most one cell).
 */
final class VerticalLayout {
    enum Kind { UPRIGHT, ROTATE, TOP_RIGHT, SMALL, NEWLINE }

    final String text;
    final int rows;
    private int n;            // cell count
    private int[] start, end, col, row;
    private boolean[] nl;
    private int columns;
    private int[] colFirst;   // first cell of each column (or -1 for an empty column)
    private int[] colLast;

    VerticalLayout(CharSequence text, int rows) {
        this.text = text == null ? "" : text.toString();
        this.rows = Math.max(1, rows);
        build();
    }

    static boolean hangs(int cp) {
        return cp == '、' || cp == '。' || cp == '，' || cp == '．' || cp == ',' || cp == '.';
    }

    private void build() {
        int len = text.length();
        start = new int[len]; end = new int[len]; col = new int[len]; row = new int[len]; nl = new boolean[len];
        int c = 0, r = 0, k = 0;
        for (int i = 0; i < len; ) {
            int cp = text.codePointAt(i);
            int w = Character.charCount(cp);
            if (cp == '\n') {
                start[k] = i; end[k] = i + 1; col[k] = c; row[k] = r; nl[k] = true; k++;
                c++; r = 0; i++;
                continue;
            }
            if (r >= rows) {
                boolean hang = r == rows && hangs(cp);
                if (!hang) { c++; r = 0; }
            }
            start[k] = i; end[k] = i + w; col[k] = c; row[k] = r; nl[k] = false; k++;
            r++; i += w;
        }
        n = k;
        columns = c + 1;
        colFirst = new int[columns]; colLast = new int[columns];
        java.util.Arrays.fill(colFirst, -1); java.util.Arrays.fill(colLast, -1);
        for (int j = 0; j < n; j++) {
            if (colFirst[col[j]] < 0) colFirst[col[j]] = j;
            colLast[col[j]] = j;
        }
    }

    int cellCount() { return n; }
    int columnCount() { return columns; }
    int cellStart(int k) { return start[k]; }
    int cellEnd(int k) { return end[k]; }
    int cellCol(int k) { return col[k]; }
    int cellRow(int k) { return row[k]; }
    boolean isNewline(int k) { return nl[k]; }
    int columnFirstCell(int c) { return c >= 0 && c < columns ? colFirst[c] : -1; }
    int columnLastCell(int c) { return c >= 0 && c < columns ? colLast[c] : -1; }
    int textLength() { return text.length(); }

    /** First cell in column c or later (for drawing only the visible columns); cellCount() if none. */
    int columnFirstCellAtOrAfter(int c) {
        for (int j = Math.max(0, c); j < columns; j++) if (colFirst[j] >= 0) return colFirst[j];
        return n;
    }

    /** Cell containing char index i (binary search), or -1 if i is out of range. */
    int cellOf(int i) {
        if (i < 0 || i >= text.length() || n == 0) return -1;
        int lo = 0, hi = n - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (start[mid] <= i) lo = mid; else hi = mid - 1;
        }
        return lo;
    }

    /** Snap a char index to a cell boundary (never inside a surrogate pair). */
    int snap(int i) {
        if (i <= 0) return 0;
        if (i >= text.length()) return text.length();
        int k = cellOf(i);
        return start[k];
    }

    /** Grid position {col,row} of the caret placed before char index p (0..length). */
    int[] caretPos(int p) {
        p = snap(p);
        if (p < text.length()) {
            int k = cellOf(p);
            return new int[]{col[k], row[k]};
        }
        if (n == 0) return new int[]{0, 0};
        int last = n - 1;
        if (nl[last]) return new int[]{col[last] + 1, 0};
        return new int[]{col[last], row[last] + 1};
    }

    /** Caret index for a point given in grid units: column number and fractional row (rows from the top). */
    int hitTest(int c, float rowF) {
        int len = text.length();
        if (n == 0) return 0;
        if (c < 0) c = 0;
        if (c >= columns) return len;
        int first = colFirst[c];
        if (first < 0) return len; // trailing empty column after a final newline
        if (rowF < 0) return start[first];
        int r = (int) Math.floor(rowF);
        int last = colLast[c];
        for (int k = first; k <= last; k++) {
            if (row[k] == r) {
                if (nl[k]) return start[k];
                return (rowF - r) < 0.5f ? start[k] : end[k];
            }
        }
        // below the last cell of the column
        return nl[last] ? start[last] : end[last];
    }

    /** Caret index for a pixel position. originRight = x of the right edge of column 0; top = y of row 0. */
    int hitTestPx(float x, float y, float originRight, float top, float colW, float rowH) {
        int c = (int) Math.floor((originRight - x) / colW);
        float rowF = (y - top) / rowH;
        return hitTest(c, rowF);
    }

    static Kind kind(int cp) {
        switch (cp) {
            case '\n': return Kind.NEWLINE;
            case '、': case '。': case '，': case '．':
                return Kind.TOP_RIGHT;
            case 'ー': case '－': case '—': case '―': case '‐': case '─': case '～': case '〜':
            case '…': case '‥': case '＝': case '：': case '；':
            case '「': case '」': case '『': case '』': case '（': case '）': case '【': case '】':
            case '〔': case '〕': case '［': case '］': case '｛': case '｝': case '〈': case '〉':
            case '《': case '》': case '＜': case '＞': case '〖': case '〗': case '→': case '←':
            case '(': case ')': case '[': case ']': case '{': case '}': case '<': case '>':
            case '-': case '=': case '~': case ':': case ';': case '_':
                return Kind.ROTATE;
            case 'ぁ': case 'ぃ': case 'ぅ': case 'ぇ': case 'ぉ': case 'っ': case 'ゃ': case 'ゅ': case 'ょ': case 'ゎ':
            case 'ァ': case 'ィ': case 'ゥ': case 'ェ': case 'ォ': case 'ッ': case 'ャ': case 'ュ': case 'ョ': case 'ヮ':
            case 'ヵ': case 'ヶ':
                return Kind.SMALL;
            default:
                return Kind.UPRIGHT;
        }
    }
}
