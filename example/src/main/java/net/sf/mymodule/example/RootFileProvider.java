package net.sf.mymodule.example;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.util.LruCache;
import android.webkit.MimeTypeMap;
import net.sf.fdshare.ConnectionBrokenException;
import net.sf.fdshare.FileDescriptorFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;

public class RootFileProvider extends ContentProvider {
    public static final String AUTHORITY = "net.sf.fdshare.provider";

    private static final MimeTypeMap map = MimeTypeMap.getSingleton();

    private FileDescriptorFactory fdfactory;

    public RootFileProvider() {
    }

    @Override
    public boolean onCreate() {
        try {
            fdfactory = FileDescriptorFactory.create(getContext());

            return true;
        } catch (IOException e) {
            e.printStackTrace();

            return false;
        }
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (TextUtils.isEmpty(uri.getPath()))
            throw new FileNotFoundException();

        final File f = new File(uri.getPath());

        try {
            try {
                return fdfactory.open(f);
            } catch (ConnectionBrokenException e) {
                return reattemptWihtNewFactory(f);
            }
        } catch (IOException ioe) {
            throw new FileNotFoundException("Failed to open a file" +
                    (TextUtils.isEmpty(ioe.getMessage()) ? "" : " : " + ioe.getMessage()));
        }
    }

    private ParcelFileDescriptor reattemptWihtNewFactory(File f) throws IOException {
        try {
            fdfactory.close();
            fdfactory = FileDescriptorFactory.create(getContext());
            return fdfactory.open(f);
        } catch (Exception hopeless) {
            throw new IOException("Failed to re-initialize descriptor factory");
        }
    }

    @Override
    public String[] getStreamTypes(Uri uri, String requestedType) {
        final String actualType = getType(uri);

        return compareMimeTypes(actualType, requestedType) ? new String[] { actualType } : null;
    }

    @Override
    public String getType(Uri uri) {
        final String maybeExtension = MimeTypeMap.getFileExtensionFromUrl(uri.toString());

        if (!TextUtils.isEmpty(maybeExtension) && map.hasExtension(maybeExtension))
            return map.getMimeTypeFromExtension(maybeExtension);
        else
            return "text/plain";
    }

    private static boolean compareMimeTypes(String concreteType, String desiredType) {
        final int typeLength = desiredType.length();
        if (typeLength == 3 && desiredType.equals("*/*")) {
            return true;
        }

        final int slashpos = desiredType.indexOf('/');
        if (slashpos > 0) {
            if (typeLength == slashpos+2 && desiredType.charAt(slashpos+1) == '*') {
                if (desiredType.regionMatches(0, concreteType, 0, slashpos+1)) {
                    return true;
                }
            } else if (desiredType.equals(concreteType)) {
                return true;
            }
        }

        return false;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        if (projection == null) {
            projection = new String[] {
                    MediaStore.MediaColumns.MIME_TYPE,
                    OpenableColumns.DISPLAY_NAME,
                    OpenableColumns.SIZE
            };
        }

        final MatrixCursor result = new MatrixCursor(projection);

        try {
            final ParcelFileDescriptor fd = openFile(uri, "rw");

            final Object[] row = new Object[projection.length];
            for (int i = 0; i < projection.length; i++) {
                if (projection[i].compareToIgnoreCase(OpenableColumns.DISPLAY_NAME) == 0) {
                    row[i] = uri.getLastPathSegment();
                } else if (projection[i].compareToIgnoreCase(OpenableColumns.SIZE) == 0) {
                    final long fdsize = fd.getStatSize();
                    row[i] = fdsize >= 0 ? fdsize : null;
                } else if (projection[i].compareToIgnoreCase(MediaStore.MediaColumns.MIME_TYPE)==0) {
                    row[i] = getType(uri);
                }
            }

            result.addRow(row);
            return result;
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        // Implement this to handle requests to delete one or more rows.
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        // TODO: Implement this to handle requests to insert a new row.
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
                      String[] selectionArgs) {
        // TODO: Implement this to handle requests to update one or more rows.
        throw new UnsupportedOperationException("Not yet implemented");
    }
}
