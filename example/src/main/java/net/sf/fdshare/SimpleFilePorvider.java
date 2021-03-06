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

import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * A simple ContentProvider, using {@link ParcelFileDescriptor#open} to serve requests.
 *
 * All paths, received by the provider must be absolute and canonical (fully resolved, without a single
 * symlink). Use {@link File#getCanonicalPath()} to resolve paths before passing them to ContentResolver.
 *
 * Passing relative, non canonical or inaccessible paths will result in exception being thrown.
 */
public class SimpleFilePorvider extends BaseProvider {
    private static final String TAG = "SimpleFileProvider";

    public static final String AUTHORITY = ".provider.simple";

    @Override
    ParcelFileDescriptor openDescriptor(String filePath, String mode, boolean secure) throws FileNotFoundException {
        File aFile;

        if (TextUtils.isEmpty(filePath) || !(aFile = new File(filePath)).isAbsolute())
            throw new IllegalArgumentException("Provide a fully qualified path!");

        if (!aFile.exists())
            throw new FileNotFoundException();

        try {
            // TODO: do something about the TOCTOU condition here
            if (secure && !aFile.equals(aFile.getCanonicalFile()))
                throw new IllegalArgumentException("Provide a fully resolved canonical path!");

            return ParcelFileDescriptor.open(aFile, modeToMode(mode));
        } catch (IOException e) {
            Log.e(TAG, "Failed to open a file due to " + e);
        }

        throw new FileNotFoundException("Failed to open a file");
    }

    private static int modeToMode(String mode) throws FileNotFoundException {
        int modeBits;
        if ("r".equals(mode)) {
            modeBits = ParcelFileDescriptor.MODE_READ_ONLY;
        } else if ("w".equals(mode) || "wt".equals(mode)) {
            modeBits = ParcelFileDescriptor.MODE_WRITE_ONLY
                    | ParcelFileDescriptor.MODE_CREATE
                    | ParcelFileDescriptor.MODE_TRUNCATE;
        } else if ("wa".equals(mode)) {
            modeBits = ParcelFileDescriptor.MODE_WRITE_ONLY
                    | ParcelFileDescriptor.MODE_CREATE
                    | ParcelFileDescriptor.MODE_APPEND;
        } else if ("rw".equals(mode)) {
            modeBits = ParcelFileDescriptor.MODE_READ_WRITE
                    | ParcelFileDescriptor.MODE_CREATE;
        } else if ("rwt".equals(mode)) {
            modeBits = ParcelFileDescriptor.MODE_READ_WRITE
                    | ParcelFileDescriptor.MODE_CREATE
                    | ParcelFileDescriptor.MODE_TRUNCATE;
        } else {
            throw new FileNotFoundException("Invalid mode: " + mode);
        }
        return modeBits;
    }
}
