package net.sf.fdshare;

import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * A ContentProvider, that runs the helper binary with elevated privileges to serve requests.
 *
 * Note, that opening Uri, pointing to a symlink is _NOT_ allowed: the caller must resolve those itself beforehand.
 * Additionally the caller must ensure that the user have been informed, which actual file is going to be open,
 * and supply the Uri of _THAT_ file in order to protect from symlink-based attacks.
 */
public class RootFileProvider extends BaseProvider {
    public static final String AUTHORITY = ".provider.root";

    private static final String TAG = "RootFileProvider";

    private volatile FileDescriptorFactory fdfactory;

    @Override
    ParcelFileDescriptor openDescriptor(String filePath, String mode) throws FileNotFoundException {
        if (TextUtils.isEmpty(filePath))
            throw new FileNotFoundException();

        final File f = new File(filePath);
        try {
            if (fdfactory == null) {
                synchronized (this) {
                    fdfactory = FileDescriptorFactory.DEBUG
                            ? FileDescriptorFactory.createTest(getContext())
                            : FileDescriptorFactory.create(getContext());
                }
            }

            return fdfactory.open(f, parseMode(mode) | FileDescriptorFactory.O_NOFOLLOW);
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
}
