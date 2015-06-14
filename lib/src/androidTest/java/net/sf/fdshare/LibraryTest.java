package net.sf.fdshare;

import android.annotation.TargetApi;
import android.os.ParcelFileDescriptor;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;
import android.test.FlakyTest;
import junit.framework.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;

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
}