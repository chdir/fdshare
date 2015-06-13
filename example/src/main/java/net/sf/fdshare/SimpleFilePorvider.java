package net.sf.fdshare;

import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * A ContentProvider, that uses {@link ParcelFileDescriptor#open} to serve requests.
 *
 * Note, that opening Uri, pointing to a symlink is _NOT_ allowed: the caller must resolve those itself beforehand.
 * Additionally the caller must ensure that the user have been informed, which actual file is going to be open,
 * and supply the Uri of _THAT_ file in order to protect from symlink-based attacks.
 */
public class SimpleFilePorvider extends BaseProvider {
    private static final String TAG = "SimpleFileProvider";

    public static final String AUTHORITY = ".provider.simple";

    @Override
    ParcelFileDescriptor openDescriptor(String filePath, String mode) throws FileNotFoundException {
        File aFile;

        if (TextUtils.isEmpty(filePath) || !(aFile = new File(filePath)).exists())
            throw new FileNotFoundException();

        try {
            // XXX: this is vulnerable to TOCTTOU attacks! An ideal solution would use JNI to check
            // descriptor, returned by open() for being a symlink, or, better yet, open() should have
            // used NO_FOLLOW by default in the first place. That said, who cares about symlink attacks,
            // when device vendors disregard security updates for years...
            if (isSymlink(aFile))
                throw new FileNotFoundException("Symlinks are not allowed!");

            return ParcelFileDescriptor.open(aFile, modeToMode(mode));
        } catch (IOException e) {
            Log.e(TAG, "Failed to open a file due to " + e);
        }

        throw new FileNotFoundException("Failed to open a file");
    }

    private static boolean isSymlink(File file) throws IOException {
        if (file == null)
            throw new NullPointerException("File must not be null");
        File canon;
        if (file.getParent() == null) {
            canon = file;
        } else {
            File canonDir = file.getParentFile().getCanonicalFile();
            canon = new File(canonDir, file.getName());
        }
        return !canon.getCanonicalFile().equals(canon.getAbsoluteFile());
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
