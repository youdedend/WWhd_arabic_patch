package org.wwhdrecomp.app;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Hesham's Arabic fan translation (https://arabichesham.com/games/the-legend-of-zelda-the-wind-waker-hd):
 * The translation's .zip archive (or folder) is unpacked into files/arabic. When Arabic translation
 * is active, the runtime automatically shapes and orders the right-to-left text (docs/rtl-text.md)
 * and loads the translated message packs (permanent_2d_*.pack) and Arabic title screen (Title_00.szs)
 * via native file redirection.
 */
final class Arabic {
    private Arabic() {}

    /** Folder next to files/save where the native runtime and overlay look for translation files */
    static File dir(Context c) {
        File f = c.getExternalFilesDir(null);
        return new File(f != null ? f : c.getFilesDir(), "arabic");
    }

    /** Finds the installed pack file, if any */
    static File packFile(Context c) {
        File d = dir(c);
        File[] direct = d.listFiles((dir, n) -> n.toLowerCase(Locale.ROOT).startsWith("permanent_2d_") && n.endsWith(".pack"));
        if (direct != null && direct.length > 0) return direct[0];
        File sub = new File(d, "content/Common/Pack");
        File[] subPacks = sub.listFiles((dir, n) -> n.toLowerCase(Locale.ROOT).startsWith("permanent_2d_") && n.endsWith(".pack"));
        if (subPacks != null && subPacks.length > 0) return subPacks[0];
        return null;
    }

    static boolean isInstalled(Context c) {
        return packFile(c) != null;
    }

    static boolean hasTitle(Context c) {
        File d = dir(c);
        return new File(d, "Title_00.szs").exists() || new File(d, "content/Common/Package/Title_00.szs").exists();
    }

    static void remove(Context c) {
        Backup.deleteTree(dir(c));
    }

    /**
     * Unpacks a translation .zip (Hesham's): extracts its permanent_2d_*.pack and Title_00.szs,
     * wherever they sit in the archive hierarchy.
     * Returns a human-readable success description, or "!error_message" on failure.
     */
    static String installZip(Context c, Uri uri) {
        File tmp = new File(dir(c), ".install");
        Backup.deleteTree(tmp);
        if (!tmp.mkdirs()) return "!cannot create " + tmp;

        String pack = null, title = null;
        long total = 0;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("cannot read selected file");
            ZipInputStream zip = new ZipInputStream(in);
            byte[] buf = new byte[1 << 16];
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                String n = e.getName();
                int slash = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
                String base = slash < 0 ? n : n.substring(slash + 1);
                boolean isPack = base.toLowerCase(Locale.ROOT).startsWith("permanent_2d_") && base.endsWith(".pack");
                boolean isTitle = base.equalsIgnoreCase("Title_00.szs");
                if (e.isDirectory() || !(isPack || isTitle)) continue;
                if ((isPack && pack != null) || (isTitle && title != null)) continue; // first one found wins

                try (OutputStream out = new FileOutputStream(new File(tmp, base))) {
                    for (int k; (k = zip.read(buf)) > 0; ) {
                        total += k;
                        if (total > (256L << 20)) throw new IOException("archive is too large");
                        out.write(buf, 0, k);
                    }
                }
                if (isPack) pack = base;
                else title = base;
            }
        } catch (IOException e) {
            Backup.deleteTree(tmp);
            return "!" + e.getMessage();
        }

        if (pack == null) {
            Backup.deleteTree(tmp);
            return "!" + c.getString(R.string.arabic_not_package);
        }

        File dst = dir(c);
        //noinspection ResultOfMethodCallIgnored
        dst.mkdirs();
        // Remove old translation files so clean files take their place
        File[] old = dst.listFiles((d, n) -> (n.startsWith("permanent_2d_") && n.endsWith(".pack")) || n.equals("Title_00.szs"));
        if (old != null) {
            for (File f : old) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }

        //noinspection ResultOfMethodCallIgnored
        new File(tmp, pack).renameTo(new File(dst, pack));
        if (title != null) {
            //noinspection ResultOfMethodCallIgnored
            new File(tmp, title).renameTo(new File(dst, title));
        }
        Backup.deleteTree(tmp);

        // Also write a label file
        try (OutputStream o = new FileOutputStream(new File(dst, "label.txt"))) {
            o.write("ترجمة هشام (Arabic Hesham)\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException ignored) {}

        return c.getString(title != null ? R.string.arabic_both : R.string.arabic_text_only);
    }

    /**
     * Installs translation files from a folder selected by the user.
     */
    static String installFolder(Context c, Uri tree) {
        ContentResolver cr = c.getContentResolver();
        File tmp = new File(dir(c), ".install");
        Backup.deleteTree(tmp);
        if (!tmp.mkdirs()) return "!cannot create " + tmp;

        String[] pack = {null};
        String[] title = {null};
        try {
            walk(cr, tree, DocumentsContract.getTreeDocumentId(tree), 0, (name, uri) -> {
                String lower = name.toLowerCase(Locale.ROOT);
                boolean isPack = lower.startsWith("permanent_2d_") && lower.endsWith(".pack");
                boolean isTitle = lower.equals("title_00.szs");
                if (isPack && pack[0] == null) {
                    copy(cr, uri, new File(tmp, name));
                    pack[0] = name;
                } else if (isTitle && title[0] == null) {
                    copy(cr, uri, new File(tmp, "Title_00.szs"));
                    title[0] = "Title_00.szs";
                }
            });
        } catch (IOException e) {
            Backup.deleteTree(tmp);
            return "!" + e.getMessage();
        }

        if (pack[0] == null) {
            Backup.deleteTree(tmp);
            return "!" + c.getString(R.string.arabic_not_package);
        }

        File dst = dir(c);
        //noinspection ResultOfMethodCallIgnored
        dst.mkdirs();
        File[] old = dst.listFiles((d, n) -> (n.startsWith("permanent_2d_") && n.endsWith(".pack")) || n.equals("Title_00.szs"));
        if (old != null) {
            for (File f : old) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }

        //noinspection ResultOfMethodCallIgnored
        new File(tmp, pack[0]).renameTo(new File(dst, pack[0]));
        if (title[0] != null) {
            //noinspection ResultOfMethodCallIgnored
            new File(tmp, title[0]).renameTo(new File(dst, title[0]));
        }
        Backup.deleteTree(tmp);

        try (OutputStream o = new FileOutputStream(new File(dst, "label.txt"))) {
            o.write("ترجمة هشام (Arabic Hesham)\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException ignored) {}

        return c.getString(title[0] != null ? R.string.arabic_both : R.string.arabic_text_only);
    }

    private interface Visitor { void file(String name, Uri uri) throws IOException; }

    private static void walk(ContentResolver cr, Uri tree, String docId, int depth, Visitor v) throws IOException {
        if (depth > 12) return;
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        try (Cursor cur = cr.query(children, new String[] {DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            while (cur != null && cur.moveToNext()) {
                String id = cur.getString(0), name = cur.getString(1), mime = cur.getString(2);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) walk(cr, tree, id, depth + 1, v);
                else v.file(name, DocumentsContract.buildDocumentUriUsingTree(tree, id));
            }
        }
    }

    private static void copy(ContentResolver cr, Uri from, File to) throws IOException {
        try (InputStream in = cr.openInputStream(from); OutputStream out = new FileOutputStream(to)) {
            if (in == null) throw new IOException("cannot read " + from);
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        }
    }
}
