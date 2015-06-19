/*
 * Copyright © 2015 Alexander Rvachev
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.sf.fdshare;

import android.annotation.SuppressLint;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.support.annotation.NonNull;
import android.support.v4.util.LruCache;
import android.text.TextUtils;
import android.util.Log;
import android.webkit.MimeTypeMap;
import com.j256.simplemagic.ContentInfo;
import com.j256.simplemagic.ContentInfoUtil;
import com.j256.simplemagic.ContentType;
import net.sf.mymodule.example.*;
import net.sf.mymodule.example.BuildConfig;
import org.w3c.dom.Text;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Timer;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A base ContentProvider class, using libmagic mime database to detect real type of files.
 *
 * Uses time-based caching of file metadata.
 *
 * SimpleFileProvider and RootFileProvider are separated from this and each other, because security nature of
 * their permissions is completely different, and they don't have to always be used together that way.
 */
public abstract class BaseProvider extends ContentProvider {
    private static final MimeTypeMap map = MimeTypeMap.getSingleton();

    private final ContentInfoUtil mimeLib = new ContentInfoUtil();

    private final LruCache<String, TimestampedMime> fileTypeCache = new LruCache<>(7);

    private static class TimestampedMime {
        long size = -1;
        long when;
        String[] mime;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public final ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return openDescriptor(uri.getPath(), mode);
    }

    @Override
    public String getType(Uri uri) {
        if (TextUtils.isEmpty(uri.getPath()))
            return null;

        final String forcedType = uri.getQueryParameter("type");
        if (!TextUtils.isEmpty(forcedType)) {
            return "null".equals(forcedType) ? null : forcedType;
        } else {
            final TimestampedMime guess = guessTypeInternal(uri.getPath());
            return guess.mime.length == 0 ? null : guess.mime[0];
        }
    }

    @Override
    public String[] getStreamTypes(Uri uri, String requestedType) {
        final String[] types = guessTypeInternal(uri.getPath()).mime;

        final ArrayList<String> acceptedTypes = new ArrayList<>();

        for (String type:types) {
            if (compareMimeTypes(type, requestedType))
                acceptedTypes.add(type);
        }

        return acceptedTypes.isEmpty() ? null : acceptedTypes.toArray(new String[acceptedTypes.size()]);
    }

    abstract ParcelFileDescriptor openDescriptor(String filePath, String mode) throws FileNotFoundException;

    @SuppressLint("NewApi")
    @NonNull TimestampedMime guessTypeInternal(String filePath) {
        final TimestampedMime cachedResult = fileTypeCache.get(filePath);

        if (cachedResult != null && System.nanoTime() - cachedResult.when > 2_000_000_000)
            return cachedResult;

        final Set<String> types = new LinkedHashSet<>();

        final TimestampedMime mime = new TimestampedMime();

        final String actualExtension = MimeTypeMap.getFileExtensionFromUrl(filePath);
        if (!TextUtils.isEmpty(actualExtension)) {
            final String extType = map.getMimeTypeFromExtension(actualExtension);
            if (!TextUtils.isEmpty(extType) && !"application/octet-stream".equals(extType))
                types.add(extType);

            final ContentType extType2 = ContentType.fromFileExtension(actualExtension);
            if (ContentType.OTHER != extType2)
                types.add(extType2.getMimeType());
        }

        try (ParcelFileDescriptor newFd = openDescriptor(filePath, "r");
             FileInputStream fs = new FileInputStream(newFd.getFileDescriptor()))
        {
            mime.size = newFd.getStatSize();

            final ContentInfo result;
            if (mime.size != 0 && (result = mimeLib.findMatch(fs)) != null) {
                final String detectedMime = result.getMimeType();

                if (!TextUtils.isEmpty(detectedMime) && !"application/octet-stream".equals(detectedMime))
                    types.add(detectedMime);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        if (types.isEmpty())
            types.add("application/octet-stream");

        mime.mime = types.toArray(new String[types.size()]);
        mime.when = System.nanoTime();

        fileTypeCache.put(filePath, mime);

        return mime;
    }

    static boolean compareMimeTypes(String concreteType, String desiredType) {
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
        final String filePath = uri.getPath();
        if (TextUtils.isEmpty(filePath))
            throw new IllegalArgumentException("Empty path!");

        if (projection == null) {
            projection = new String[] {
                    MediaStore.MediaColumns.MIME_TYPE,
                    OpenableColumns.DISPLAY_NAME,
                    OpenableColumns.SIZE
            };
        }

        final MatrixCursor result = new MatrixCursor(projection);

        final TimestampedMime info = guessTypeInternal(filePath);

        final Object[] row = new Object[projection.length];
        for (int i = 0; i < projection.length; i++) {
            String projColumn = projection[i];

            if (TextUtils.isEmpty(projColumn))
                continue;

            switch (projColumn.toLowerCase()) {
                case OpenableColumns.DISPLAY_NAME:

                    row[i] = uri.getLastPathSegment();

                    break;
                case OpenableColumns.SIZE:

                    row[i] = info.size >= 0 ? info.size : null;

                    break;
                case MediaStore.MediaColumns.MIME_TYPE:

                    final String forcedType = uri.getQueryParameter("type");

                    if (!TextUtils.isEmpty(forcedType))
                        row[i] = "null".equals(forcedType) ? null : forcedType;
                    else
                        row[i] = info.mime[0];

                    break;
                case MediaStore.MediaColumns.DATA:
                    Log.w("BaseProvider", "Relying on MediaColumns.DATA is unreliable and must be avoided!");
                    row[i] = uri.getPath();
                    break;
            }
        }

        result.addRow(row);
        return result;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
