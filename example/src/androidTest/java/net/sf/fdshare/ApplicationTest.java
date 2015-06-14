package net.sf.fdshare;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;
import android.test.mock.MockContentResolver;
import android.text.TextUtils;
import junit.framework.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

@RunWith(AndroidJUnit4.class)
public class ApplicationTest {
    static {
        System.setProperty(FileDescriptorFactory.DEBUG_MODE, "true");
    }

    private final ContentResolver resolver;

    private RootFileProvider provider = new RootFileProvider(); {
        final MockContentResolver fakeResolver = new MockContentResolver();
        provider.attachInfo(InstrumentationRegistry.getTargetContext(), null);
        fakeResolver.addProvider(RootFileProvider.AUTHORITY, provider);
        resolver = fakeResolver;
    }

    private static final Uri baseUri = Uri.parse("content://" + RootFileProvider.AUTHORITY);

    @Test(expected = FileNotFoundException.class)
    public void fileNotFoundThrown() throws IOException {
        final File totallyNotExistingFile = File.createTempFile("foo", "bar", provider.getContext().getFilesDir());

        Assert.assertTrue(!totallyNotExistingFile.exists() || totallyNotExistingFile.delete());

        resolver.openFileDescriptor(Uri.withAppendedPath(baseUri, totallyNotExistingFile.getAbsolutePath()), "ignored");
    }

    @Test
    public void testFileOpening() throws IOException {
        final File someExistingFile = new File(provider.getContext().getFilesDir(), "testfile");
        try (FileOutputStream fos = new FileOutputStream(someExistingFile);
             Closeable tmpFileRemoval = someExistingFile::delete)
        {
            fos.write(9);
            fos.close();

            final Uri uri = Uri.withAppendedPath(baseUri, someExistingFile.getAbsolutePath());

            try (ParcelFileDescriptor fd =  resolver.openFileDescriptor(uri, "ignored");
                 Cursor openableInfo = resolver.query(uri, null, null, null, null))
            {
                Assert.assertTrue(openableInfo.moveToNext());

                final long reportedSize = openableInfo.getLong(openableInfo.getColumnIndexOrThrow(OpenableColumns.SIZE));
                final String reportedName = openableInfo.getString(openableInfo.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME));

                Assert.assertFalse(TextUtils.isEmpty(reportedName));
                Assert.assertTrue(fd.getFileDescriptor().valid());
                Assert.assertEquals(fd.getStatSize(), reportedSize);
            }
        }
    }
}