package com.zmlworks.voicememo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class UndoStackTest {
    @Test public void lifoWithLimitAndDedup() {
        UndoStack u = new UndoStack(3);
        u.push("a", 1, "x");
        u.push("a", 1, "x"); // duplicate snapshot ignored
        assertEquals(1, u.size());
        u.push("ab", 2, "voice");
        u.push("abc", 3, "voice");
        u.push("abcd", 4, "voice");
        assertEquals(3, u.size());
        assertEquals("abcd", u.pop().text);
        UndoStack.Entry e = u.pop();
        assertEquals("abc", e.text);
        assertEquals(3, e.caret);
        assertEquals("ab", u.pop().text);
        assertNull(u.pop());
    }
}
