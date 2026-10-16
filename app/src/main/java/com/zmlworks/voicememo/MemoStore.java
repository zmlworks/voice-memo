package com.zmlworks.voicememo;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Memos as UTF-8 text files <id>.txt in one directory (app-private storage). id = creation time in ms. */
final class MemoStore {
    static final class Memo {
        final long id; final String title; final long modified; final int length;
        Memo(long id, String title, long modified, int length) {
            this.id = id; this.title = title; this.modified = modified; this.length = length;
        }
    }

    static final int TITLE_MAX = 40;
    final File dir;

    MemoStore(File dir) { this.dir = dir; }

    static MemoStore of(android.content.Context c) { return new MemoStore(new File(c.getFilesDir(), "memos")); }

    private File file(long id) { return new File(dir, id + ".txt"); }

    boolean exists(long id) { return file(id).isFile(); }

    synchronized long create(long now) throws IOException {
        ensureDir();
        long id = now;
        while (file(id).exists()) id++;
        if (!file(id).createNewFile()) throw new IOException("cannot create memo " + id);
        return id;
    }

    synchronized List<Memo> list() {
        List<Memo> out = new ArrayList<>();
        File[] fs = dir.listFiles();
        if (fs == null) return out;
        for (File f : fs) {
            String name = f.getName();
            if (!f.isFile() || !name.endsWith(".txt")) continue;
            long id;
            try { id = Long.parseLong(name.substring(0, name.length() - 4)); } catch (NumberFormatException e) { continue; }
            String text = read(f);
            out.add(new Memo(id, titleOf(text), f.lastModified(), text.length()));
        }
        out.sort((a, b) -> a.modified != b.modified ? Long.compare(b.modified, a.modified) : Long.compare(b.id, a.id));
        return out;
    }

    synchronized String load(long id) { return read(file(id)); }

    /** Atomic save: write a temp file, then rename over the old one. */
    synchronized void save(long id, String text) throws IOException {
        ensureDir();
        File tmp = new File(dir, id + ".txt.tmp");
        try (FileOutputStream os = new FileOutputStream(tmp)) {
            os.write((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            os.getFD().sync();
        }
        if (!tmp.renameTo(file(id))) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("rename failed for memo " + id);
        }
    }

    synchronized boolean delete(long id) {
        File f = file(id);
        return !f.exists() || f.delete();
    }

    private void ensureDir() throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
    }

    private static String read(File f) {
        try {
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /** Trims ASCII whitespace and the ideographic space U+3000 (String.strip() needs API 33). */
    static String strip(String s) {
        int a = 0, b = s.length();
        while (a < b && isSpace(s.charAt(a))) a++;
        while (b > a && isSpace(s.charAt(b - 1))) b--;
        return s.substring(a, b);
    }

    private static boolean isSpace(char c) { return c <= ' ' || c == '\u3000'; }

    /** First non-blank line, trimmed, at most TITLE_MAX code points (+ "…"). Empty string for a blank memo. */
    static String titleOf(String text) {
        if (text == null) return "";
        for (String line : text.split("\n", -1)) {
            String t = strip(line);
            if (t.isEmpty()) continue;
            if (t.codePointCount(0, t.length()) > TITLE_MAX) {
                t = t.substring(0, t.offsetByCodePoints(0, TITLE_MAX)) + "…";
            }
            return t;
        }
        return "";
    }
}
