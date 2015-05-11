package net.sf.fdshare;

import android.os.ParcelFileDescriptor;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;
import junit.framework.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;

@RunWith(AndroidJUnit4.class)
public class ApplicationTest {
    final File exec = new File(InstrumentationRegistry.getInstrumentation().getContext().getApplicationInfo().nativeLibraryDir,
            System.mapLibraryName(FileDescriptorFactory.EXEC_NAME));

    public static void setUp() {
        System.setProperty(FileDescriptorFactory.DEBUG_MODE, "true");
    }

    @Test(expected = IOException.class)
    public void testUnableToOpenWellKnownFile() throws IOException, ConnectionBrokenException {
        final String sockName = "someaddr1";

        try (FileDescriptorFactory fdf = FileDescriptorFactory.create(sockName, exec.getAbsolutePath(), sockName))
        {
            fdf.open(exec); // opening with default read-write permission will fail
        }
    }

    @Test(expected = ConnectionBrokenException.class)
    public void testReusingClosedThrows() throws IOException, ConnectionBrokenException {
        final String sockName = "someaddr2";

        final FileDescriptorFactory fdf = FileDescriptorFactory.create(sockName, exec.getAbsolutePath(), sockName);
        fdf.close();
        fdf.open(exec, FileDescriptorFactory.O_RDONLY);
    }

    @Test
    public void testAbleToOpenWellKnownFile() throws IOException, ConnectionBrokenException {
        final String sockName = "someaddr3";

        try (FileDescriptorFactory fdf = FileDescriptorFactory.create(sockName, exec.getAbsolutePath(), sockName))
        {
            // opening with read-only permission must succeed
            final ParcelFileDescriptor fd = fdf.open(exec, FileDescriptorFactory.O_RDONLY);

            Assert.assertTrue(fd.getFileDescriptor().valid());
            Assert.assertEquals(fd.getStatSize(), exec.length());
        }
    }
}