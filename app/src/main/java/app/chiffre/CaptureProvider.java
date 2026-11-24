package app.chiffre;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Gibt der Kamera-App genau eine Datei im Cache der App zum Beschreiben frei (Foto aus Chiffre heraus).
 * Das Foto landet so nicht in der Galerie. Nicht exportiert, nur per Berechtigung für die Kamera-App.
 */
public class CaptureProvider extends ContentProvider {

    private static final String DIR = "capture";

    static String authority(Context c) {
        return c.getPackageName() + ".capture";
    }

    private static File dir(Context c) {
        File d = new File(c.getCacheDir(), DIR);
        if (!d.exists()) {
            d.mkdirs();
        }
        return d;
    }

    /** Neue, noch leere Zieldatei für ein Foto. */
    static File newFile(Context c) {
        File d = dir(c);
        cleanAll(c);
        return new File(d, "foto-" + System.currentTimeMillis() + ".jpg");
    }

    static Uri uriFor(Context c, File f) {
        return Uri.parse("content://" + authority(c) + "/" + f.getName());
    }

    /** Löscht übrig gebliebene Fotos. */
    static void cleanAll(Context c) {
        File[] list = dir(c).listFiles();
        if (list == null) {
            return;
        }
        for (File f : list) {
            f.delete();
        }
    }

    /**
     * Löscht übrig gebliebene Fotos, die vor dem Zeitpunkt t entstanden sind. Für das Aufräumen beim Start im
     * Hintergrund: ein Foto, das danach entsteht, bleibt sicher unberührt.
     */
    static void cleanOlderThan(Context c, long t) {
        File[] list = dir(c).listFiles();
        if (list == null) {
            return;
        }
        for (File f : list) {
            if (f.lastModified() < t) {
                f.delete();
            }
        }
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || name.contains("/") || name.contains("..") || !name.endsWith(".jpg")) {
            throw new FileNotFoundException();
        }
        Context c = getContext();
        if (c == null) {
            throw new FileNotFoundException();
        }
        return new File(dir(c), name);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = resolve(uri);
        int m = mode != null && mode.contains("w")
                ? ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_CREATE
                : ParcelFileDescriptor.MODE_READ_ONLY;
        return ParcelFileDescriptor.open(f, m);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        try {
            File f = resolve(uri);
            MatrixCursor cur = new MatrixCursor(new String[] { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE });
            cur.addRow(new Object[] { f.getName(), f.length() });
            return cur;
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    @Override
    public String getType(Uri uri) {
        return "image/jpeg";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
