package net.sf.fdshare;

import android.content.Context;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.util.Log;

import java.io.Closeable;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Writer;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class FileDescriptorFactory implements Closeable {
    public static final String VERBOSE_LOG = "net.sf.fdshare.DEBUG";

    private static final String FD_HELPER_TAG = "FdHelper";

    private static final String EXEC_NAME = "fdshare_exec";

    /**
     * Create a FileDescriptorFactory, using an internally managed helper
     * process. The helper is run with superuser privileges via system "su"
     * command, available on system's PATH.
     *
     * <br/>
     *
     * The device has to be rooted. The "su" command must support
     * <pre>su -c "command with arguments"</pre> syntax (most modern ones do).
     *
     * <br/>
     *
     * You are highly recommended to cache and reuse the returned instance.
     *
     * @throws IOException if creation of instance fails, such as due to IO error or failure to obtain root access
     */
    public static FileDescriptorFactory create(Context context) throws IOException {
        final String command = new File(context.getApplicationInfo().nativeLibraryDir,
                System.mapLibraryName(EXEC_NAME)).getAbsolutePath();

        final String address = UUID.randomUUID().toString();

        final FileDescriptorFactory result = new FileDescriptorFactory(address);

        try {
            final Process shell = new ProcessBuilder("su", "-c", command + ' ' + address)
                    .redirectErrorStream(true)
                    .start();

            result.startServer(shell);

            return result;
        } catch (Throwable t) {
            result.close();

            throw t;
        }
    }

    private final AtomicBoolean closedStatus = new AtomicBoolean(true);
    private final BlockingQueue<Object> exchangeQueue = new SynchronousQueue<>();

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
     * Create file descriptor from supplied file with supplied mode.
     *
     * <br/>
     *
     * Do not call this method from the main thread!
     *
     * <br/>
     *
     * @throws IOException recoverable error, such as when file was not found
     * @throws ConnectionBrokenException irrecoverable error, that renders this factory instance unusable
     */
    public ParcelFileDescriptor open(File file) throws IOException, ConnectionBrokenException {
        if (closedStatus.get())
            throw new ConnectionBrokenException("Already closed");

        final String path = file.getPath();

        try {
            final Object response;

            if (exchangeQueue.offer(path, 5, TimeUnit.SECONDS)
                    && (response = exchangeQueue.poll(5, TimeUnit.SECONDS)) != null) {
                if (response instanceof FileDescriptor)
                    return FdCompat.adopt((FileDescriptor) response);
                else
                    throw new IOException("Failed to open file: " + response);
            } else
                throw new ConnectionBrokenException("Timed out while attempting to open a file");

        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();

            throw new IOException("Interrupted while waiting for file to open");
        }
    }

    @Override
    public void close() {
        if (!closedStatus.getAndSet(true)) {
            exchangeQueue.clear();

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
        private final ByteBuffer statusMsg;

        int lastClientReadCount;

        Server(Process client, LocalServerSocket serverSocket) throws IOException {
            super("fd receiver");

            this.client = client;
            this.serverSocket = serverSocket;

            statusMsg = ByteBuffer.allocate(512).order(ByteOrder.nativeOrder());
        }

        @Override
        public void run() {
            try (ReadableByteChannel clientOutput = Channels.newChannel(client.getInputStream());
                 Closeable c = serverSocket::close)
            {
                try {
                    final String greeting = readResponse(clientOutput);

                    final Matcher m = Pattern.compile("PID:(\\d+)").matcher(greeting);

                    if (!m.matches())
                        throw new IOException("Can't get helper PID" + (greeting.length() == 0 ? "" : " : " + greeting));

                    final int helperPid = Integer.valueOf(m.group(1));

                    acceptAuthenticateAndTest(helperPid, serverSocket);
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

        private void acceptAuthenticateAndTest(int helperPid, LocalServerSocket socket) throws Exception {
            while (!isInterrupted()) {
                try (LocalSocket localSocket = socket.accept();
                     ReadableByteChannel status = Channels.newChannel(localSocket.getInputStream()))
                {
                    final int socketPid = localSocket.getPeerCredentials().getPid();
                    if (socketPid != helperPid)
                        continue;

                    final String socketMsg = readResponse(status);
                    final FileDescriptor[] fds = localSocket.getAncillaryFileDescriptors();

                    if (!"READY".equals(socketMsg) || fds == null || fds.length != 1 || fds[0] == null)
                        throw new Exception("Can't get client tty" + (socketMsg.length() ==0 ? "" : " : " + socketMsg));

                    try (OutputStreamWriter clientTty = new OutputStreamWriter(new FileOutputStream(fds[0]))) {
                        // Indicate to the helper that it can close it's copy of it's controlling tty.
                        // When our end is closed the kernel tty driver will send SIGHUP to the helper,
                        // cleanly killing it's root process for us
                        clientTty.append("GO");
                        clientTty.flush();

                        // as little exercise in preparation to real deal, try to protect our helper from OOM killer
                        final String oomFile = "/proc/" + helperPid + "/oom_adj_score";
                        final String oomFileTestStatus = sendFdRequest(oomFile, clientTty, status);

                        final FileDescriptor[] oomFd = localSocket.getAncillaryFileDescriptors();

                        if ("OK".equals(oomFileTestStatus) && oomFd != null && oomFd.length == 1 && oomFd[0] != null) {

                            try (OutputStreamWriter oow = new OutputStreamWriter(new FileOutputStream(oomFd[0])))
                            {
                                oow.append("-1000");
                            } catch (IOException ok) {
                                logException("Write to " + oomFile + " failed", ok);
                            }

                        } else {
                            Log.e(FD_HELPER_TAG, "Unexpected response for " + oomFile + ": \"" + oomFileTestStatus + "\"");
                        }

                        processRequestsUntilStopped(localSocket, status, clientTty);
                    }
                } catch (IOException ioe) {
                    logException("An error during communication attempt", ioe);
                }
            }
        }

        private void processRequestsUntilStopped(LocalSocket fdrecv, ReadableByteChannel status, Writer control) throws IOException {
            while (!isInterrupted()) {
                try {
                    Object response = "Unknown error";

                    final String fileOps = (String) exchangeQueue.take();

                    try {
                        final String statusString = sendFdRequest(fileOps, control, status);

                        final FileDescriptor[] fds = fdrecv.getAncillaryFileDescriptors();

                        if ("OK".equals(statusString)) {
                            response = fds != null && fds.length == 1 && fds[0] != null
                                    ? fds[0] : "Received no file descriptor from helper";
                        } else {
                            response = statusString;
                        }
                    } finally {
                        exchangeQueue.put(response);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private String sendFdRequest(String fileName, Writer req, ReadableByteChannel resp) throws IOException {
            req.append(String.valueOf(fileName.getBytes().length));
            req.append('\n');
            req.append(fileName);
            req.append('\n');
            req.flush();
            return readResponse(resp);
        }

        private String readResponse(ReadableByteChannel channel) throws IOException {
            lastClientReadCount = channel.read(statusMsg);
            statusMsg.clear();
            channel.read(statusMsg);
            return new String(statusMsg.array(), 0, statusMsg.position());
        }
    }

    private static void logException(String explanation, Exception err) {
        if ("true".equals(System.getProperty(VERBOSE_LOG))) {
            Log.e("fdshare", explanation);

            err.printStackTrace();
        } else {
            Log.v("fdshare", explanation);
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

    // classic Java FileDescriptor does not provide saner way to close itself, so...
    private static void closeDescriptor(FileDescriptor fd) {
        try {
            new FileInputStream(fd).close();
        } catch (IOException ignore) {}
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
