package com.zmlworks.voicememo;

import java.util.ArrayDeque;

/** Snapshot undo (text + caret). Memos are small, so whole-text snapshots are simple and robust. */
final class UndoStack {
    static final class Entry {
        final String text; final int caret; final String label;
        Entry(String text, int caret, String label) { this.text = text; this.caret = caret; this.label = label; }
    }

    private final ArrayDeque<Entry> stack = new ArrayDeque<>();
    private final int max;

    UndoStack(int max) { this.max = Math.max(1, max); }

    void push(String text, int caret, String label) {
        Entry top = stack.peekFirst();
        if (top != null && top.text.equals(text)) return; // nothing changed since the last snapshot
        stack.addFirst(new Entry(text, caret, label));
        while (stack.size() > max) stack.removeLast();
    }

    Entry pop() { return stack.pollFirst(); }
    Entry peek() { return stack.peekFirst(); }
    boolean isEmpty() { return stack.isEmpty(); }
    int size() { return stack.size(); }
    void clear() { stack.clear(); }
}
