package net.sf.fdshare;

import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;
import android.test.InstrumentationTestCase;
import android.test.ProviderTestCase2;
import junit.framework.Assert;
import net.sf.fdshare.ConnectionBrokenException;
import net.sf.fdshare.FileDescriptorFactory;
import net.sf.mymodule.example.RootFileProvider;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

@RunWith(AndroidJUnit4.class)
public class ApplicationTest {
    /*@Test
    public void testFileOpening() throws FileNotFoundException {
        final ParcelFileDescriptor fd = get.openFile(Uri.parse("/system/etc/hosts"), "rw");

        Assert.assertTrue(fd.getFileDescriptor().valid());
    }
    */
}