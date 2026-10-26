package com.example.services;

import android.os.Environment;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/** Пошук файлів, які можна видалити, і саме видалення. */
public class Scanner {

    public static class Item {
        public final File file;
        public final long size;
        /** true: видалити вміст папки (сама папка лишається) */
        public final boolean recursive;

        Item(File file, long size, boolean recursive) {
            this.file = file;
            this.size = size;
            this.recursive = recursive;
        }
    }

    public static class Category {
        public final String title;
        public final String hint;
        public final ArrayList<Item> items = new ArrayList<Item>();
        public long total = 0;
        public boolean checked;
        public boolean expanded = false;

        Category(String title, String hint, boolean checked) {
            this.title = title;
            this.hint = hint;
            this.checked = checked;
        }

        void add(Item it) {
            items.add(it);
            total += it.size;
        }
    }

    private static class Node {
        final File f;
        final boolean thumbs;
        final int depth;

        Node(File f, boolean thumbs, int depth) {
            this.f = f;
            this.thumbs = thumbs;
            this.depth = depth;
        }
    }

    public static ArrayList<Category> scan() {
        File root = Environment.getExternalStorageDirectory();

        Category logs = new Category("Лог-файли", "Файли .log, які залишають програми", true);
        Category temp = new Category("Тимчасові файли", "Файли .tmp, .temp і ті, що закінчуються на ~", true);
        Category thumbs = new Category("Мініатюри галереї", "Папки .thumbnails: система створить їх знову", true);
        Category appCache = new Category("Кеш програм на накопичувачі",
                "Android/data/…/cache: програми створять його знову", true);
        Category emptyDirs = new Category("Порожні папки", "Папки без жодного файлу", true);
        Category apks = new Category("Файли APK", "Встановлювальні пакунки. Переконайся, що вони тобі не потрібні", false);
        Category zero = new Category("Порожні файли (0 байт)", "Приховані файли (з крапкою на початку) не чіпаємо", false);

        Deque<Node> stack = new ArrayDeque<Node>();
        stack.push(new Node(root, false, 0));
        while (!stack.isEmpty()) {
            Node n = stack.pop();
            File[] kids = n.f.listFiles();
            if (kids == null) continue;
            if (kids.length == 0 && n.depth >= 2) {
                emptyDirs.add(new Item(n.f, 0, false));
                continue;
            }
            for (File k : kids) {
                if (k.isDirectory()) {
                    String name = k.getName();
                    if (n.depth == 0 && name.equals("Android")) continue; // обробляємо окремо
                    if (isSymlink(k)) continue;
                    stack.push(new Node(k, n.thumbs || name.equals(".thumbnails"), n.depth + 1));
                } else {
                    classify(k, n.thumbs, logs, temp, thumbs, apks, zero);
                }
            }
        }

        // Кеш інших програм на накопичувачі (Android 10 і нижче; на новіших система не пускає)
        File[] pkgs = new File(root, "Android/data").listFiles();
        if (pkgs != null) {
            for (File p : pkgs) {
                File c = new File(p, "cache");
                if (c.isDirectory()) {
                    long s = dirSize(c);
                    if (s > 0) appCache.add(new Item(c, s, true));
                }
            }
        }

        ArrayList<Category> all = new ArrayList<Category>();
        Category[] order = {logs, temp, thumbs, appCache, emptyDirs, apks, zero};
        for (Category c : order) {
            if (c.items.isEmpty()) continue;
            Collections.sort(c.items, (a, b) -> Long.compare(b.size, a.size));
            all.add(c);
        }
        return all;
    }

    private static void classify(File k, boolean inThumbs, Category logs, Category temp,
                                 Category thumbs, Category apks, Category zero) {
        long len = k.length();
        String name = k.getName().toLowerCase(Locale.ROOT);
        Item it = new Item(k, len, false);
        if (inThumbs) {
            thumbs.add(it);
        } else if (name.endsWith(".log")) {
            logs.add(it);
        } else if (name.endsWith(".tmp") || name.endsWith(".temp") || name.endsWith("~")) {
            temp.add(it);
        } else if (name.endsWith(".apk")) {
            apks.add(it);
        } else if (len == 0 && !name.startsWith(".")) {
            zero.add(it);
        }
    }

    /** Видаляє вибрані категорії, повертає скільки байт звільнено. */
    public static long clean(List<Category> cats) {
        long freed = 0;
        for (Category c : cats) {
            if (!c.checked) continue;
            for (Item it : c.items) {
                try {
                    if (it.recursive) {
                        freed += deleteContents(it.file);
                    } else if (it.file.delete()) {
                        freed += it.size;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return freed;
    }

    private static long deleteContents(File dir) {
        long freed = 0;
        File[] kids = dir.listFiles();
        if (kids == null) return 0;
        for (File k : kids) {
            if (k.isDirectory()) {
                if (isSymlink(k)) continue;
                freed += deleteContents(k);
                k.delete();
            } else {
                long len = k.length();
                if (k.delete()) freed += len;
            }
        }
        return freed;
    }

    private static long dirSize(File d) {
        long t = 0;
        File[] kids = d.listFiles();
        if (kids == null) return 0;
        for (File f : kids) {
            if (f.isDirectory()) {
                if (!isSymlink(f)) t += dirSize(f);
            } else {
                t += f.length();
            }
        }
        return t;
    }

    private static boolean isSymlink(File f) {
        try {
            File parent = f.getParentFile();
            File canon = (parent == null) ? f : new File(parent.getCanonicalFile(), f.getName());
            return !canon.getCanonicalFile().equals(canon.getAbsoluteFile());
        } catch (IOException e) {
            return true;
        }
    }

    public static String fmt(long b) {
        if (b >= 1024L * 1024 * 1024)
            return String.format(Locale.getDefault(), "%.2f ГБ", b / (1024.0 * 1024 * 1024));
        if (b >= 1024L * 1024)
            return String.format(Locale.getDefault(), "%.1f МБ", b / (1024.0 * 1024));
        if (b >= 1024)
            return String.format(Locale.getDefault(), "%.0f КБ", b / 1024.0);
        return b + " Б";
    }
}
