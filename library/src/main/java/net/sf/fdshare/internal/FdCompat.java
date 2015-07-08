package net.sf.fdshare.internal;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.res.AssetFileDescriptor;
import android.os.*;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.system.ErrnoException;
import android.system.Os;
import android.text.TextUtils;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.net.DatagramSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.Channel;
import java.nio.channels.FileChannel;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A number of hacks, that should be relatively safe against future platform changes.
 *
 * Some use reflection. Most have a "clean" version, used on sufficiently recent platform.
 */
public final class FdCompat {
    private FdCompat() {
        throw new AssertionError("No instances");
    }

    /**
     * Create a temporary file without a name.
     *
     * @param dirName the name of directory, holding contents of the file
     *
     * @return descriptor of the created file, open for read-write access
     */
    @SuppressWarnings({"ResultOfMethodCallIgnored", "ThrowFromFinallyBlock"})
    public static @NonNull ParcelFileDescriptor tmpfile(@NonNull String dirName) throws IOException {
        File temp = null;
        try {
            temp = File.createTempFile("fdhelper", null, new File(dirName));
            return ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_WRITE);
        } finally {
            if (temp != null && !temp.delete())
                throw new IOException("Failed to delete the temporary file");
        }
    }

    /**
     * Attempt to determine a name, associated with given file descriptor. null is returned, when the file does not
     * have a name in filesystem (or the name can not be determined). Note, that returned value does not have
     * to make any sense, refer to given descriptor or even exist, it is just a hint, that the file used to have
     * it once in the past (and was not a pipe or socket in the first place).
     */
    public static @Nullable String getFdPath(@NonNull ParcelFileDescriptor fd) {
        final String resolved;

        try {
            final File procfsFdFile = new File("/proc/self/fd/" + FdCompat.getIntFd(fd));

            if (Build.VERSION.SDK_INT >= 21) {
                // The returned name may be empty or contain something like "pipe:", "socket:", "(deleted)" etc.
                resolved = FdCompat9.readlink(procfsFdFile);
            } else {
                // The returned name is usually valid or empty.
                resolved = procfsFdFile.getCanonicalPath();
            }

            if (TextUtils.isEmpty(resolved) || resolved.charAt(0) != '/')
                return null;
        } catch (IOException e) {
            // An exception here means, that given file DID have some name, but it is too long, some of symlinks
            // in the path were broken, or most likely, one of it's components is inaccessible for reading.
            // Either way, it is almost certainly not a pipe. See also:
            // https://android.googlesource.com/platform/libcore/+/081c0de9231d6/luni/src/main/native/canonicalize_path.cpp
            return "";
        } catch (NotErrnoException e) {
            // An exception here should be VERY rare and means, that the descriptor was made unavailable by some
            // SUID-trickery or simply was invalid in the first place.
            return null;
        }

        return resolved;
    }

    /**
     * Copy contents of AssetFileDescriptor (with account for size and offset) to given target.
     *
     * @param source descriptor to copy contents from, closed upon returning from this method
     *
     * @param target must refer to ordinary file
     */
    public static void fastCopy(@NonNull AssetFileDescriptor source, @NonNull ParcelFileDescriptor target) throws IOException {
        final ParcelFileDescriptor dup = Build.VERSION.SDK_INT < 13 ? dupInternal(target) : FdCompat9.dup(target);

        try (FileChannel fileInputStream = source.createInputStream().getChannel();
             FileChannel output = new FileOutputStream(dup.getFileDescriptor()).getChannel())
        {
            long length = source.getLength();

            long remaining = length == AssetFileDescriptor.UNKNOWN_LENGTH ? -1 : length;

            final int BLOCK_SIZE = 1024 * 4;

            final ByteBuffer buffer = ByteBuffer.allocate(BLOCK_SIZE);

            do {
                if (remaining != -1) buffer.limit((int) Math.min(BLOCK_SIZE, remaining));

                if (fileInputStream.read(buffer) == -1)
                    break;

                // prepare the buffer to be drained
                buffer.flip();
                // write to the channel, may block
                remaining -= output.write(buffer);
                // If partial transfer, shift remainder down
                // If buffer is empty, same as doing clear()
                buffer.compact();
            } while (remaining != 0);

            // EOF will leave buffer in fill state
            buffer.flip();
            // make sure the buffer is fully drained.
            while (buffer.hasRemaining()) {
                remaining -= output.write(buffer);
            }

            if (remaining > 0)
                throw new IOException("reached EOF before completion");
        }
    }

    /**
     * Same as {@link ParcelFileDescriptor#dup(FileDescriptor)}. The intermediate descriptor is closed.
     */
    public static @NonNull ParcelFileDescriptor adopt(@NonNull FileDescriptor fd) throws IOException {
        return Build.VERSION.SDK_INT < 13 ? createFdInternal(fd) : FdCompat9.createFdInternal(fd);
    }

    /**
     * Same as {@link ParcelFileDescriptor#getFd()}.
     */
    public static int getIntFd(@NonNull ParcelFileDescriptor fd) throws IOException {
        return Build.VERSION.SDK_INT < 12 ? getIntFdInternal(fd) : FdCompat9.getIntFd(fd);
    }

    /**
     * Same as {@link ParcelFileDescriptor#adoptFd(int)}.
     */
    public static @NonNull ParcelFileDescriptor adopt(int fd) throws IOException {
        return Build.VERSION.SDK_INT < 13 ? createFdInternal(fd) : FdCompat9.createFdInternal(fd);
    }

    /**
     * Create a RandomAccessFile from given FileDescriptor.
     *
     * This method currently uses reflection to create the RandomAccessFile instance. While the implementation
     * is highly reliable and future-proof, you are better off using a {@link FileChannel} if you don't need
     * the read-write behavior.
     *
     * @param donor a FileDescriptor to use, Must be created with read-write mode
     *
     * @return {@link RandomAccessFile}, that now owns the given descriptor
     */
    public static @NonNull RandomAccessFile convert(@NonNull FileDescriptor donor) throws IOException {
        final RandomAccessFile raf = new RandomAccessFile("/dev/null", "rw");
        final FileDescriptor recipient = raf.getFD();

        // closeDescriptor the /dev/null fd
        closeKernelFd(recipient);
        cloneDescriptorGutsInternal(donor, recipient);

        return raf;
    }

    public static File libDir(@NonNull Context context) {
        return Build.VERSION.SDK_INT < 9
                ? new File(context.getApplicationInfo().dataDir, "lib")
                : FdCompat.FdCompat9.libDir(context);
    }

    public static void set(@NonNull AtomicBoolean value) {
        if (Build.VERSION.SDK_INT < 9) value.set(true); else FdCompat.FdCompat9.set(value);
    }

    public static void closeDescriptor(@Nullable FileDescriptor fd) {
        if (Build.VERSION.SDK_INT < 21) closeDescriptorInternal(fd); else FdCompat9.closeDescriptor(fd);
    }

    private static void closeDescriptorInternal(FileDescriptor fd) {
        // classic Java FileDescriptor does not provide saner way to close itself, so...
        if (fd != null)
            try {
                new FileInputStream(fd).close();
            } catch (IOException ignore) {
            }
    }

    private static ParcelFileDescriptor dupInternal(ParcelFileDescriptor descriptor) {
        final Parcel nothingIsImpossible = Parcel.obtain();
        try {
            descriptor.writeToParcel(nothingIsImpossible, 0);

            return nothingIsImpossible.readFileDescriptor();
        } finally {
            nothingIsImpossible.recycle();
        }
    }

    // _never_ forget that anything ever opened corresponds to entry in the kernel table
    private static void closeKernelFd(FileDescriptor untouchable) {
        try {
            final FileDescriptor tempHolder = new FileDescriptor();
            cloneDescriptorGutsInternal(untouchable, tempHolder);
            closeDescriptor(tempHolder);
        } catch (Exception ohWell) {
            ohWell.printStackTrace();
        }
    }

    // on older platforms we have to rely on the unspoken assumptions, reflection and hacks
    private static Field integerField;

    private static void readCachedField() throws NoSuchFieldException {
        if (integerField == null) {
            integerField = FileDescriptor.class.getDeclaredField("descriptor");
            integerField.setAccessible(true);
        }
    }

    private static ParcelFileDescriptor createFdInternal(FileDescriptor donor) throws IOException {
        try {
            // just construct a ParcelFileDescriptor _somehow_
            final ParcelFileDescriptor result = ParcelFileDescriptor.open(new File("/dev/null"),
                    ParcelFileDescriptor.MODE_READ_WRITE);

            final FileDescriptor targetOfTransplantation = result.getFileDescriptor();

            // closeDescriptor the /dev/null fd
            closeKernelFd(targetOfTransplantation);
            cloneDescriptorGutsInternal(donor, targetOfTransplantation);

            return result;
        } catch (Exception e) {
            throw new IOException("Can not obtain descriptor on this Android version: " + e.getMessage());
        }
    }

    private static int getIntFdInternal(ParcelFileDescriptor fd) throws IOException {
        try {
            readCachedField();

            return integerField.getInt(fd.getFileDescriptor());
        } catch (Exception e) {
            throw new IOException("Can not obtain integer descriptor on this Android version: " + e.getMessage());
        }
    }

    private static ParcelFileDescriptor createFdInternal(int value) throws IOException {
        try {
            readCachedField();

            final FileDescriptor d = new FileDescriptor();
            integerField.setInt(d, value);

            return createFdInternal(d);
        } catch (Exception e) {
            throw new IOException("Can not obtain ParcelFileDescriptor on this Android version: " + e.getMessage());
        }
    }

    private static void cloneDescriptorGutsInternal(FileDescriptor donor, FileDescriptor recipient) throws IOException {
        try {
            readCachedField();

            integerField.setInt(recipient, integerField.getInt(donor));
        } catch (Exception e) {
            cloneGutsHardWayInternal(donor, recipient);
        }
    }

    // just in case
    private static <T> void cloneGutsHardWayInternal(T donor, T recipient) throws IOException {
        final Class<?> clazz = donor.getClass();
        try {
            final Field[] f = clazz.getDeclaredFields();
            for (Field ff:f) {
                ff.setAccessible(true);
                ff.set(recipient, ff.get(donor));
            }
        } catch (IllegalAccessException e) {
            throw new IOException("failed to perform reflective cloning of " + clazz.getName());
        }
    }

    @TargetApi(9)
    private static class FdCompat9 {
        @TargetApi(13)
        static ParcelFileDescriptor createFdInternal(FileDescriptor fd) throws IOException {
            final ParcelFileDescriptor result = ParcelFileDescriptor.dup(fd);
            FdCompat.closeDescriptor(fd);
            return result;
        }

        @TargetApi(12)
        static int getIntFd(ParcelFileDescriptor descriptor) {
            return descriptor.getFd();
        }

        @TargetApi(13)
        static ParcelFileDescriptor createFdInternal(int fd) {
            return ParcelFileDescriptor.adoptFd(fd);
        }

        static File libDir(Context context) {
            return new File(context.getApplicationInfo().nativeLibraryDir);
        }

        static void set(AtomicBoolean value) {
            value.lazySet(true);
        }

        @TargetApi(21)
        static void closeDescriptor(FileDescriptor descriptor) {
            if (descriptor != null)
                try { Os.close(descriptor); } catch (Exception ignore) { /* TODO */ }
        }

        @TargetApi(21)
        static String readlink(File file) throws NotErrnoException {
            try { return Os.readlink(file.getAbsolutePath()); } catch (Exception e) { throw new NotErrnoException(e); }
        }

        @TargetApi(13)
        static ParcelFileDescriptor dup(ParcelFileDescriptor target) throws IOException {
            return ParcelFileDescriptor.dup(target.getFileDescriptor());
        }
    }

    // those jerks... Making a new exception public will give everyone TONS of hassle and fancy VerifyErrors
    private static final class NotErrnoException extends Exception {
        public NotErrnoException(Throwable throwable) {
            super(throwable.getMessage());

            setStackTrace(throwable.getStackTrace());
        }
    }
}
