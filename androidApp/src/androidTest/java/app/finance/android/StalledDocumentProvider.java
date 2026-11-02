package app.finance.android;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;

/** Test APK only, Java so its separate provider process needs no target-app Kotlin runtime. */
public final class StalledDocumentProvider extends ContentProvider {
    private final ArrayList<ParcelFileDescriptor> retained = new ArrayList<>();
    @Override public boolean onCreate() { return true; }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        synchronized (retained) {
            try {
                ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
                boolean writing = mode.contains("w");
                retained.add(writing ? pipe[0] : pipe[1]);
                return writing ? pipe[1] : pipe[0];
            } catch (IOException ex) { throw new FileNotFoundException("Synthetic pipe unavailable"); }
        }
    }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        if ("release".equals(method)) synchronized (retained) {
            for (ParcelFileDescriptor descriptor : retained) {
                try { descriptor.close(); } catch (IOException ignored) { }
            }
            retained.clear();
        }
        return null;
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { return null; }
    @Override public String getType(Uri uri) { return "application/json"; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
