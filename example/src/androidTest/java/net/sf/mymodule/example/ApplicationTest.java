package net.sf.mymodule.example;

import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.support.test.runner.AndroidJUnit4;
import android.test.ProviderTestCase2;
import junit.framework.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.FileNotFoundException;

@RunWith(AndroidJUnit4.class)
public class ApplicationTest extends ProviderTestCase2<RootFileProvider> {
    public ApplicationTest() {
        super(RootFileProvider.class, RootFileProvider.AUTHORITY);
    }

    @Test
    public void testFileOpening() throws FileNotFoundException {
        final ParcelFileDescriptor fd = getProvider().openFile(Uri.parse("/system/etc/hosts"), "rw");

        Assert.assertTrue(fd.getFileDescriptor().valid());
    }
}