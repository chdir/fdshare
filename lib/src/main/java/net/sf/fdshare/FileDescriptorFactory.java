package net.sf.fdshare;

import android.content.Context;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.support.annotation.IntDef;
import android.support.annotation.NonNull;
import android.util.Log;

import java.io.Closeable;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.UUID;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A factory object, that can be used to create {@link ParcelFileDescriptor} instances for files, not
 * directly accessible to calling user (e.g. use root access to open file and get it's file descriptor into JVM).
 * <p>
 * Known classes, that can be used with file descriptors are:
 * <ul>
 * <li> {@link java.io.FileInputStream} and {@link java.io.FileOutputStream};
 * <li> {@link java.io.RandomAccessFile};
 * <li> {@link java.nio.channels.FileChannel}
 * <li> {@link java.nio.channels.FileLock};
 * <li> {@link android.os.MemoryFile};
 * </ul>final EditText
 * and, probably, many others. The inner workings of {@link android.content.ContentProvider} and entire Android
 * Storage Access Framework are based on them as well.
 * <p>
 * The implementation uses a helper process, run with elevated privileges, that communicates with background thread via
 * a domain socket. There is a single thread and single process per factory instance and a best effort is taken to
 * cleanup those when the instance is closed.
 * <p>
 * Semantics of this class are same as of {@link ParcelFileDescriptor#open(File, int)}, except that sets of acceptable
 * flags are different and you can set any flags, accepted by system {@code open()} function. Produced
 * {@link ParcelFileDescriptor} is backed by a {@link FileDescriptor}, backed by Linux integer file descriptor,
 * backed by entry in kernel descriptor table. Most of descriptor properties, including read/write access modes can not
 * be changed after it was created. It is, therefore, safe to pass read-only descriptors to targets, that aren't
 * supposed to have write access to corresponding files. The properties of descriptor are retained when passed between
 * processes, such as via AIDL/Binder or Unix domain sockets, but the integer number, representing descriptor in each
 * process, may change.
 *
 * @see ParcelFileDescriptor#open(File, int)
 * @see ParcelFileDescriptor#getFileDescriptor()
 */
public final class FileDescriptorFactory implements Closeable {
    public static final String DEBUG_MODE = "net.sf.fdshare.DEBUG";

    /**
     * This type covers most flags, properly supported by Bionic and this library.
     *
     * A number of flags, supported by {@code open} syscall of modern Linux kernel
     * aren't present here for various reasons. Many of them aren't very relevant,
     * various Java APIs already provide that functionality behind the scene, and
     * you can set most with {@code fcntl}. <br/>
     *
     * Note, that the API behind {@link ProcessBuilder} and {@link Runtime#exec} already
     * closes excess descriptors for you, so you won't need to manually use
     * {@code O_CLOEXEC} most of time. <br/>
     *
     * Beware, that this class uses Bionic {@code open()} function, so you may be
     * subject to many bugs in Bionic, such as missing or improper O_LARGEFILE support,
     * and misbehaving behavior of various flags. Corresponding integer constants are
     * taken directly from headers, so you may just supress the warnings and pass
     * whatever you want to {@link #open(File, int)}, but you would ve on your own.
     */
    @IntDef(value = {
            O_RDONLY,
            O_WRONLY,
            O_RDWR,
            O_APPEND,
            O_CREAT,
            O_DIRECTORY,
            O_NOFOLLOW,
            O_PATH,
            O_TRUNC
    }, flag = true)
    public @interface OpenFlag {};

    public static final int O_RDONLY = 0;
    public static final int O_WRONLY = 1;
    public static final int O_RDWR = 2;

    public static final int O_APPEND = 0;
    public static final int O_CREAT = 1;
    public static final int O_DIRECTORY = 2;
    public static final int O_NOFOLLOW = 3;
    public static final int O_PATH = 6;
    public static final int O_TRUNC = 5;

    private static final String FD_HELPER_TAG = "fdhelper";

    static final String EXEC_NAME;
    static final boolean DEBUG;

    static {
        EXEC_NAME = Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN ? "fdshare_PIC_exec" : "fdshare_exec";

        DEBUG = "true".equals(System.getProperty(DEBUG_MODE));
    }

    /**
     * Create a FileDescriptorFactory, using an internally managed helper
     * process. The helper is run with superuser privileges via the "su"
     * command, available on system's PATH. <br/>
     *
     * The device has to be rooted. The "su" command must support
     * {@code su -c "command with arguments"} syntax (most modern ones do). <br/>
     *
     * You are highly recommended to cache and reuse the returned instance.
     *
     * @throws IOException if creation of instance fails, such as due to IO error or failure to obtain root access
     */
    public static FileDescriptorFactory create(Context context) throws IOException {
        final String command = new File(context.getApplicationInfo().nativeLibraryDir,
                System.mapLibraryName(EXEC_NAME)).getAbsolutePath();

        final String address = UUID.randomUUID().toString();

        return create(address, "su", "-c", command + ' ' + address);
    }

    static FileDescriptorFactory createTest(Context context) throws IOException {
        final String command = new File(context.getApplicationInfo().nativeLibraryDir,
                System.mapLibraryName(EXEC_NAME)).getAbsolutePath();

        final String address = UUID.randomUUID().toString();

        return create(address, command, address);
    }

    static FileDescriptorFactory create(String address, String... cmd) throws IOException {
        final FileDescriptorFactory result = new FileDescriptorFactory(address);

        try {
            final Process shell = new ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .start();

            result.startServer(shell);

            return result;
        } catch (Throwable t) {
            result.close();

            throw t;
        }
    }

    private final AtomicBoolean closedStatus = new AtomicBoolean(false);
    private final SynchronousQueue<FdReq> intake = new SynchronousQueue<>();
    private final SynchronousQueue<FdResp> responses = new SynchronousQueue<>();

    private final LocalServerSocket socket;

    private volatile Server serverThread;

    private FileDescriptorFactory(String address) throws IOException {
        this.socket = new LocalServerSocket(address);
    }

    private void startServer(Process client) throws IOException {
        serverThread = new Server(client, socket);
        serverThread.start();
    }

    /**
     * Return file descriptor for supplied file, open for read-write access with default flags
     * (which are {@link #O_RDWR} and {@link #O_CREAT}) and the same creation mode as used by
     * {@link ParcelFileDescriptor#open(File, int)}.
     *
     * <p>
     *
     * <b>Do not call this method from the main thread!<b/>
     *
     * @throws IOException recoverable error, such as when file was not found
     * @throws ConnectionBrokenException irrecoverable error, that renders this factory instance unusable
     */
    public @NonNull ParcelFileDescriptor open(File file) throws IOException, ConnectionBrokenException {
        return open(file, O_RDWR | O_CREAT);
    }

    /**
     * Return file descriptor for supplied file, open for specified access with supplied flags
     * and the same creation mode as used by {@link ParcelFileDescriptor#open(File, int)}.
     *
     * <p>
     *
     * <b>Do not call this method from the main thread!<b/>
     *
     * @param file the {@link File} object, with path to the target file, not necessarily accessible to your UID
     * @param mode either {@link #O_RDONLY}, {@link #O_WRONLY} or {@link #O_RDWR}, or-ed with other {@link OpenFlag} constants
     *
     * @throws IOException recoverable error, such as when file was not found
     * @throws ConnectionBrokenException irrecoverable error, that renders this factory instance unusable
     */
    public @NonNull ParcelFileDescriptor open(File file, @OpenFlag int mode) throws IOException, ConnectionBrokenException {
        if (closedStatus.get())
            throw new ConnectionBrokenException("Already closed");

        final String path = file.getPath();

        final FdReq request = new FdReq(path, mode);

        FdResp response;
        try {
            if (intake.offer(request, 2500, TimeUnit.MILLISECONDS)) {
                while ((response = responses.poll(2500, TimeUnit.MILLISECONDS)) != null) {
                    if (response.request != request) // cleanup the queue in case some callers were interrupted early
                        continue;

                    if (response.fd != null)
                        return FdCompat.adopt(response.fd);
                    else
                        throw new IOException("Failed to open file: " + response.message);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        close();

        throw new ConnectionBrokenException("Failed to retrieve response from helper");
    }

    @Override
    public void close() {
        if (!closedStatus.compareAndSet(false, true)) {
            intake.offer(FdReq.STOP);

            try {
                socket.close();
            } catch (IOException e) {
                logException("Failed to close server socket", e);
            } finally {
                if (serverThread != null)
                    serverThread.interrupt();
            }
        }
    }

    private final class Server extends Thread {
        private final Process client;
        private final LocalServerSocket serverSocket;

        private final ByteBuffer statusMsg = ByteBuffer.allocate(512).order(ByteOrder.nativeOrder());

        int lastClientReadCount;

        Server(Process client, LocalServerSocket serverSocket) throws IOException {
            super("fd receiver");

            this.client = client;
            this.serverSocket = serverSocket;
        }

        @Override
        public void run() {
            try (ReadableByteChannel clientOutput = Channels.newChannel(client.getInputStream());
                 Closeable c = serverSocket::close)
            {
                try {
                    initializeAndHandleRequests(readHelperPid(clientOutput), serverSocket);
                } finally {
                    try {
                        do {
                            lastClientReadCount = clientOutput.read(statusMsg);

                            if (statusMsg.position() == statusMsg.limit())
                                statusMsg.clear();
                        }
                        while (lastClientReadCount != -1);
                    }
                    catch (IOException ignored) {}
                }
            } catch (Exception e) {
                logException("Server thread forced to quit by error", e);
            } finally {
                closedStatus.set(true);

                try {
                    client.waitFor();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private int readHelperPid(ReadableByteChannel clientOutput) throws IOException {
            // the client forks to obtain controlling terminal for itself
            // so we need some way of knowing it's pid
            // note, that certain people are known to write linkers, spouting random bullshit during
            // executable startup, so we must be prepared to filter that out
            final String greeting = readMessage(clientOutput);

            final Matcher m = Pattern.compile("(?:.*)PID:(\\d+)").matcher(greeting);

            if (!m.matches())
                throw new IOException("Can't get helper PID" + (greeting.length() == 0 ? "" : " : " + greeting));

            return Integer.valueOf(m.group(1));
        }

        private void initializeAndHandleRequests(int helperPid, LocalServerSocket socket) throws Exception {
            while (!isInterrupted()) {
                try (LocalSocket localSocket = socket.accept())
                {
                    final int socketPid = localSocket.getPeerCredentials().getPid();
                    if (socketPid != helperPid)
                        continue;

                    try (ReadableByteChannel status = Channels.newChannel(localSocket.getInputStream())) {
                        final String socketMsg = readMessage(status);
                        final FileDescriptor ptmxFd = getFd(localSocket);

                        if (ptmxFd == null)
                            throw new Exception("Can't get client tty" + (socketMsg.length() == 0 ? "" : " : " + socketMsg));

                        logTrace(Log.DEBUG, "Response to tty request: '" + socketMsg + "', descriptor " + ptmxFd);

                        try (OutputStreamWriter clientTty = new OutputStreamWriter(new FileOutputStream(ptmxFd))) {
                            // Indicate to the helper that it can close it's copy of it's controlling tty.
                            // When our end is closed the kernel tty driver will send SIGHUP to the helper,
                            // cleanly killing it's root process for us
                            clientTty.append("GO\n");
                            clientTty.flush();

                            // as little exercise in preparation to real deal, try to protect our helper from OOM killer
                            final String oomFile = "/proc/" + helperPid + "/oom_score_adj";

                            final FdResp oomFileTestResp = sendFdRequest(new FdReq(oomFile, O_RDWR), clientTty, status, localSocket);

                            logTrace(Log.DEBUG, "Response to " + oomFile + " request: " + oomFileTestResp);

                            if (oomFileTestResp.fd != null) {
                                try (OutputStreamWriter oow = new OutputStreamWriter(new FileOutputStream(oomFileTestResp.fd))) {
                                    oow.append("-1000");

                                    logTrace(Log.DEBUG, "Successfully adjusted helper's OOM score to -1000");
                                } catch (IOException ok) {
                                    logException("Write to " + oomFile + " failed", ok);
                                }
                            }

                            processRequestsUntilStopped(localSocket, status, clientTty);

                            break;
                        }
                    }
                }
            }
        }

        private void processRequestsUntilStopped(LocalSocket fdrecv, ReadableByteChannel status, Writer control) throws IOException, InterruptedException {
            FdReq fileOps;

            while ((fileOps = intake.take()) != FdReq.STOP) {
                FdResp response = null;

                try {
                    try {
                        response = sendFdRequest(fileOps, control, status, fdrecv);

                        responses.put(response);
                    } catch (IOException ioe) {
                        responses.put(new FdResp(fileOps, ioe.getMessage(), null));

                        throw ioe;
                    }
                } catch (InterruptedException ie) {
                    if (response != null)
                        FdCompat.closeDescriptor(response.fd);

                    throw ie;
                }
            }
        }

        private FdResp sendFdRequest(FdReq fileOps, Writer req, ReadableByteChannel resp, LocalSocket ls) throws IOException {
            req.append(String.valueOf(fileOps.fileName.getBytes().length)).append('\n').flush();

            req.append(fileOps.fileName).append(',').append(String.valueOf(fileOps.mode)).append('\n').flush();

            String responseStr = readMessage(resp);

            final FileDescriptor fd = getFd(ls);

            if (fd == null && "READY".equals(responseStr)) { // unlikely, but..
                responseStr = "Received no file descriptor from helper";
            }

            return new FdResp(fileOps, responseStr, fd);
        }


        private String readMessage(ReadableByteChannel channel) throws IOException {
            statusMsg.clear();

            lastClientReadCount = channel.read(statusMsg);

            return new String(statusMsg.array(), 0, statusMsg.position());
        }

        private FileDescriptor getFd(LocalSocket ls) throws IOException {
            final FileDescriptor[] fds = ls.getAncillaryFileDescriptors();

            return fds != null && fds.length == 1 && fds[0] != null ? fds[0] : null;
        }
    }

    private static void logTrace(int proprity, String message, Object... args) {
        if (DEBUG)
            Log.println(proprity, FD_HELPER_TAG, String.format(message, args));
    }

    private static void logException(String explanation, Exception err) {
        if (DEBUG) {
            Log.e(FD_HELPER_TAG, explanation);

            err.printStackTrace();
        } else {
            Log.d(FD_HELPER_TAG, explanation);
        }
    }

    private static final class FdReq {
        static FdReq STOP = new FdReq(null, 0);

        final String fileName;
        final int mode;

        public FdReq(String fileName, int mode) {
            this.fileName = fileName;
            this.mode = mode;
        }

        @Override
        public String toString() {
            return fileName + ',' + mode;
        }
    }

    private static final class FdResp {
        final FdReq request;
        final String message;
        final FileDescriptor fd;

        public FdResp(FdReq request, String message, FileDescriptor fd) {
            this.request = request;
            this.message = message;
            this.fd = fd;
        }

        @Override
        public String toString() {
            return "Request: " + request + ". Helper response: '" + message + "', descriptor: " + fd;
        }
    }
}

final class FdCompat {
    static ParcelFileDescriptor adopt(FileDescriptor fd) throws IOException {
        return Build.VERSION.SDK_INT >= 13 ? FdCompat13.createFdInternal(fd) : createFdInternal(fd);
    }

    static ParcelFileDescriptor adopt(int fd) throws IOException {
        return Build.VERSION.SDK_INT >= 13 ? FdCompat13.createFdInternal(fd) : createFdInternal(fd);
    }

    // classic Java FileDescriptor does not provide saner way to close itself, so...
    static void closeDescriptor(FileDescriptor fd) {
        if (fd != null)
            try {
                new FileInputStream(fd).close();
            } catch (IOException ignore) {}
    }

    // on older platforms we have to rely on the unspoken assumptions, reflection and hacks
    private static Field integerField;

    private static void readCachedField() throws NoSuchFieldException {
        if (integerField == null) {
            integerField = FileDescriptor.class.getField("descriptor");
            integerField.setAccessible(true);
        }
    }

    private static ParcelFileDescriptor createFdInternal(FileDescriptor fd) throws IOException {
        try {
            readCachedField();

            return createFdInternal(integerField.getInt(fd));
        } catch (Exception e) {
            throw new IOException("Can not obtain ParcelFileDescriptor on this Android version: " + e.getMessage());
        }
    }

    private static ParcelFileDescriptor createFdInternal(int fd) throws IOException {
        try {
            readCachedField();

            // just construct a ParcelFileDescriptor _somehow_
            final ParcelFileDescriptor result = ParcelFileDescriptor.open(new File("/dev/null"),
                    ParcelFileDescriptor.MODE_READ_WRITE);

            final FileDescriptor targetOfTransplantation = result.getFileDescriptor();

            // close the /dev/null fd
            try {
                final FileDescriptor tempHolder = new FileDescriptor();
                integerField.setInt(tempHolder, integerField.getInt(targetOfTransplantation));
                closeDescriptor(tempHolder);
            } catch (Exception ohWell) {
                ohWell.printStackTrace();
            }

            integerField.setInt(targetOfTransplantation, fd);

            return result;
        } catch (Exception e) {
            throw new IOException("Can not obtain ParcelFileDescriptor on this Android version: " + e.getMessage());
        }
    }

    private static class FdCompat13 {
        static ParcelFileDescriptor createFdInternal(FileDescriptor fd) throws IOException {
            final ParcelFileDescriptor result = ParcelFileDescriptor.dup(fd);

            closeDescriptor(fd);

            return result;
        }

        static ParcelFileDescriptor createFdInternal(int fd) {
            return ParcelFileDescriptor.adoptFd(fd);
        }
    }
}

