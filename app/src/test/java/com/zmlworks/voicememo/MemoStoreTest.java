package com.zmlworks.voicememo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class MemoStoreTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test public void createSaveLoadListDelete() throws Exception {
        MemoStore s = new MemoStore(new File(tmp.getRoot(), "memos"));
        assertTrue(s.list().isEmpty());
        long a = s.create(1000), b = s.create(1000);
        assertNotEquals(a, b); // same clock value -> still unique
        assertEquals("", s.load(a));
        s.save(a, "買い物リスト\n牛乳、卵");
        s.save(b, "\n\n　会議メモ　\n議題");
        assertEquals("買い物リスト\n牛乳、卵", s.load(a));
        assertTrue(new File(tmp.getRoot(), "memos/" + a + ".txt").setLastModified(5_000_000L));
        assertTrue(new File(tmp.getRoot(), "memos/" + b + ".txt").setLastModified(9_000_000L));
        List<MemoStore.Memo> l = s.list();
        assertEquals(2, l.size());
        assertEquals(b, l.get(0).id);              // newest first
        assertEquals("会議メモ", l.get(0).title);    // first non-blank line, trimmed (incl. U+3000)
        assertEquals("買い物リスト", l.get(1).title);
        assertEquals(11, l.get(1).length);
        assertTrue(s.delete(a));
        assertFalse(s.exists(a));
        assertEquals(1, s.list().size());
        assertTrue(s.delete(a)); // deleting a missing memo is fine
        assertFalse(new File(tmp.getRoot(), "memos/" + b + ".txt.tmp").exists());
    }

    @Test public void overwriteAndUnicodeRoundTrip() throws Exception {
        MemoStore s = new MemoStore(tmp.newFolder("m"));
        long id = s.create(42);
        String t = "縦書き😀テスト\r\nabc";
        s.save(id, "old");
        s.save(id, t);
        assertEquals(t, s.load(id));
        s.save(id, null);
        assertEquals("", s.load(id));
    }

    @Test public void ignoresForeignFiles() throws Exception {
        File dir = tmp.newFolder("x");
        assertTrue(new File(dir, "notes.txt").createNewFile());
        assertTrue(new File(dir, "123.txt.tmp").createNewFile());
        MemoStore s = new MemoStore(dir);
        assertTrue(s.list().isEmpty());
    }

    @Test public void titles() {
        assertEquals("", MemoStore.titleOf(""));
        assertEquals("", MemoStore.titleOf(" \n\u3000\n"));
        assertEquals("abc", MemoStore.titleOf("  abc  \nxyz"));
        String longLine = "あ".repeat(50);
        assertEquals("あ".repeat(40) + "…", MemoStore.titleOf(longLine));
        assertEquals("😀".repeat(40) + "…", MemoStore.titleOf("😀".repeat(41)));
    }
}
