package com.local.htmlkiosk;

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
 * 极小的只读 ContentProvider：把 cache/shots 下的 PNG 以 content:// 暴露给分享目标。
 * 系统自带的 FileProvider 属于 androidx，这个项目不引依赖，所以自己写一个。
 */
public class ShotProvider extends ContentProvider {

    public static final String AUTHORITY = "com.local.htmlkiosk.shots";

    public static File dir(Context c) {
        File d = new File(c.getCacheDir(), "shots");
        if (!d.exists()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
        return d;
    }

    public static Uri uriFor(String name) {
        return Uri.parse("content://" + AUTHORITY + "/" + name);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        String name = uri != null ? uri.getLastPathSegment() : null;
        // provider 是 exported 的，只放行分享用的 share-<随机 UUID>.png，
        // 目录里其它截图（shot-*.png）不允许被外部应用按名字读到。
        if (name == null || name.isEmpty() || name.contains("/") || name.contains("..")
                || !name.startsWith("share-")) {
            throw new FileNotFoundException("bad name");
        }
        File f = new File(dir(getContext()), name);
        if (!f.isFile()) throw new FileNotFoundException(name);
        return f;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        String name = uri != null ? uri.getLastPathSegment() : null;
        if (name != null && (name.endsWith(".jpg") || name.endsWith(".jpeg"))) {
            return "image/jpeg";
        }
        return "image/png";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        File f;
        try {
            f = resolve(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        if (projection == null) {
            projection = new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        }
        MatrixCursor c = new MatrixCursor(projection, 1);
        Object[] row = new Object[projection.length];
        for (int i = 0; i < projection.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(projection[i])) {
                row[i] = f.getName();
            } else if (OpenableColumns.SIZE.equals(projection[i])) {
                row[i] = f.length();
            } else {
                row[i] = null;
            }
        }
        c.addRow(row);
        return c;
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