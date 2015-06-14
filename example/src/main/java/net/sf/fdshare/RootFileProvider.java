package net.sf.fdshare;

import android.annotation.TargetApi;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.util.Log;

import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * A ContentProvider, that runs the helper binary with elevated privileges to serve requests.
 *
 * All paths, received by the provider must be absolute and canonical (fully resolved, without a single
 * symlink). Use "realpath"/"readlink -f" commands to resolve paths before passing them to ContentResolver.
 * A built-in path normalization facility will be implemented in future.
 *
 * Passing relative, non canonical or inaccessible paths will result in exception being thrown.
 */
public class RootFileProvider extends BaseProvider {
    public static final String AUTHORITY = ".provider.root";

    private static final String TAG = "RootFileProvider";

    private volatile FileDescriptorFactory fdfactory;

    /**
     * Due to racy implementation of {@link CancellationSignal} the cancellation may never happen or result
     * in no exception being thrown! The caller of this method is responsible for closing a returned descriptor
     * in such case.
     */
    @Override
    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    public ParcelFileDescriptor openFile(Uri uri, String mode, CancellationSignal signal) throws FileNotFoundException {
        try (Closeable cancellation = new ThreadInterrupter(signal))
        {
            return super.openFile(uri, mode);
        }
        catch (IOException ioe) {
            throw new FileNotFoundException(ioe.getMessage());
        }
    }

    /**
     * Due to racy implementation of {@link CancellationSignal} the cancellation may never happen or result
     * in no exception being thrown! The caller of this method is responsible for closing a returned Cursor
     * in such case.
     */
    @Override
    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder, CancellationSignal signal) {
        try (Closeable cancellation = new ThreadInterrupter(signal))
        {
            return super.query(uri, projection, selection, selectionArgs, sortOrder);
        }
        catch (IOException ioe) {
            return null;
        }
    }

    @Override
    ParcelFileDescriptor openDescriptor(String filePath, String mode) throws FileNotFoundException {
        final File aFile;

        if (TextUtils.isEmpty(filePath) || !(aFile = new File(filePath)).isAbsolute())
            throw new IllegalArgumentException("Provide a fully qualified path!");

        try {
            if (fdfactory == null) {
                synchronized (this) {
                    fdfactory = FileDescriptorFactory.create(getContext());
                }
            }

            // TODO: check entire path for being canonical here
            return fdfactory.open(aFile, parseMode(mode) | FileDescriptorFactory.O_NOFOLLOW);
        } catch (FactoryBrokenException cbe) {
            fdfactory = null;

            Log.e(TAG, "Failed to open a file, is the device even rooted?");
        } catch (Exception anything) {
            Log.e(TAG, "Failed to open a file or acquire root access due to " + anything);
        }

        throw new FileNotFoundException("Failed to open a file");
    }

    private static @FileDescriptorFactory.OpenFlag int parseMode(String mode) {
        int modeBits = 0;
        boolean read = false, write = false;

        for (char c:mode.toCharArray()) {
            switch (c) {
                case 'r':
                    read = true;
                    break;
                case 'w':
                    write = true;
                    break;
                case 'a':
                    modeBits |= FileDescriptorFactory.O_APPEND;
                    break;
                case 't':
                    modeBits |= FileDescriptorFactory.O_TRUNC;
                    break;
            }
        }

        if (write) {
            modeBits |= FileDescriptorFactory.O_CREAT;

            if (read)
                modeBits |= FileDescriptorFactory.O_RDWR;
            else
                modeBits |= FileDescriptorFactory.O_WRONLY;
        } else
            modeBits |= FileDescriptorFactory.O_RDONLY;

        return modeBits;
    }

    // Static to avoid leaks
    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    private static class ThreadInterrupter implements Closeable, CancellationSignal.OnCancelListener {
        private final Thread thread;
        private final CancellationSignal signal;

        public ThreadInterrupter(CancellationSignal signal) {
            this.signal = signal;
            thread = Thread.currentThread();

            if (signal != null)
                signal.setOnCancelListener(this);
        }

        @Override
        public synchronized void close() throws IOException {
            if (signal != null) {
                signal.setOnCancelListener(null);
                signal.throwIfCanceled();
            }
        }

        @Override
        public synchronized void onCancel() {
            thread.interrupt();
        }
    }
}
