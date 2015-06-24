package net.sf.fdshare;

import android.annotation.TargetApi;
import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;
import android.test.FlakyTest;
import junit.framework.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.Channel;
import java.nio.channels.FileChannel;
import java.nio.charset.CharsetEncoder;

@RunWith(AndroidJUnit4.class)
@TargetApi(22)
public class LibraryTest {
    final File exec = new File(InstrumentationRegistry.getInstrumentation().getContext().getApplicationInfo().nativeLibraryDir,
            System.mapLibraryName(FileDescriptorFactory.EXEC_NAME));

    static {
        System.setProperty(FileDescriptorFactory.DEBUG_MODE, "true");
    }

    @Test(expected = IOException.class)
    public void testUnableToOpenWellKnownFile() throws IOException, FactoryBrokenException {
        try (FileDescriptorFactory fdf = FileDescriptorFactory.create(InstrumentationRegistry.getContext()))
        {
            fdf.open(exec); // opening with default read-write permission will fail
        }
    }

    @Test(expected = FactoryBrokenException.class)
    public void testReusingClosedThrows() throws IOException, FactoryBrokenException {
        final FileDescriptorFactory fdf = FileDescriptorFactory.create(InstrumentationRegistry.getContext());
        fdf.close();
        fdf.open(exec, FileDescriptorFactory.O_RDONLY);
    }

    @Test
    public void testAbleToOpenWellKnownFile() throws IOException, FactoryBrokenException {
        try (FileDescriptorFactory fdf = FileDescriptorFactory.create(InstrumentationRegistry.getContext()))
        {
            // opening with read-only permission must succeed
            final ParcelFileDescriptor fd = fdf.open(exec, FileDescriptorFactory.O_RDONLY);

            Assert.assertTrue(fd.getFileDescriptor().valid());
            Assert.assertEquals(fd.getStatSize(), exec.length());
        }
    }

    @Test(expected = IOException.class)
    public void testUnableToOpenRandomAccessFile() throws IOException, FactoryBrokenException {
        try (FileDescriptorFactory fdf = FileDescriptorFactory.create(InstrumentationRegistry.getContext()))
        {
            // opening with read-write permission with fail :(
            final RandomAccessFile fd = fdf.openRandomAccessFile(exec);
        }
    }

    @Test
    public void testAbleToOpenRandomAccessFile() throws IOException, FactoryBrokenException {
        final Context context = InstrumentationRegistry.getContext();

        final File tmpFile = File.createTempFile("test", null, context.getFilesDir());
        final PrintWriter out = new PrintWriter(tmpFile);
        out.write("TEST");
        out.close();

        try (FileDescriptorFactory fdf = FileDescriptorFactory.create(context))
        {
            final RandomAccessFile fd = fdf.openRandomAccessFile(tmpFile);

            final MappedByteBuffer mbb = fd.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, tmpFile.length());
            mbb.put(2, (byte) '!');

            final byte[] dst = new byte["TEST".length()];
            mbb.get(dst);

            Assert.assertEquals("TE!T", new String(dst, 0, dst.length));
        }

        //noinspection ResultOfMethodCallIgnored
        tmpFile.delete();
    }
}