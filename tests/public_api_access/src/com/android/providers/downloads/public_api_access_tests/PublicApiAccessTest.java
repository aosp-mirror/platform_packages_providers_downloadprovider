/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.providers.downloads.public_api_access_tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.DownloadManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.Downloads;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

/**
 * DownloadProvider allows apps without permission ACCESS_DOWNLOAD_MANAGER to access it -- this is
 * how the public API works.  But such access is subject to strict constraints on what can be
 * inserted.  This test suite checks those constraints.
 */
@RunWith(AndroidJUnit4.class)
public class PublicApiAccessTest {
    private static final String[] DISALLOWED_COLUMNS = new String[] {
                    Downloads.Impl.COLUMN_COOKIE_DATA,
                    Downloads.Impl.COLUMN_REFERER,
                    Downloads.Impl.COLUMN_USER_AGENT,
                    Downloads.Impl.COLUMN_NO_INTEGRITY,
                    Downloads.Impl.COLUMN_NOTIFICATION_CLASS,
                    Downloads.Impl.COLUMN_NOTIFICATION_EXTRAS,
                    Downloads.Impl.COLUMN_OTHER_UID,
                    Downloads.Impl.COLUMN_APP_DATA,
                    Downloads.Impl.COLUMN_CONTROL,
                    Downloads.Impl.COLUMN_STATUS,
            };

    private ContentResolver mContentResolver;
    private DownloadManager mManager;

    private Context getContext() {
        return InstrumentationRegistry.getContext();
    }

    @Before
    public void setUp() throws Exception {
        mContentResolver = getContext().getContentResolver();
        mManager = new DownloadManager(getContext());
        mManager.setAccessFilename(true);
    }

    @After
    public void tearDown() throws Exception {
        if (mContentResolver != null) {
            mContentResolver.delete(Downloads.Impl.CONTENT_URI, null, null);
        }
    }

    @Test
    public void testMinimalValidWrite() {
        mContentResolver.insert(Downloads.Impl.CONTENT_URI, buildValidValues());
    }

    @Test
    public void testMaximalValidWrite() {
        ContentValues values = buildValidValues();
        values.put(Downloads.Impl.COLUMN_TITLE, "foo");
        values.put(Downloads.Impl.COLUMN_DESCRIPTION, "foo");
        values.put(Downloads.Impl.COLUMN_MIME_TYPE, "foo");
        values.put(Downloads.Impl.COLUMN_NOTIFICATION_PACKAGE, "foo");
        values.put(Downloads.Impl.COLUMN_ALLOWED_NETWORK_TYPES, 0);
        values.put(Downloads.Impl.COLUMN_ALLOW_ROAMING, true);
        values.put(Downloads.Impl.RequestHeaders.INSERT_KEY_PREFIX + "0", "X-Some-Header: value");
        mContentResolver.insert(Downloads.Impl.CONTENT_URI, values);
    }

    private ContentValues buildValidValues() {
        ContentValues values = new ContentValues();
        values.put(Downloads.Impl.COLUMN_URI, "foo");
        values.put(Downloads.Impl.COLUMN_DESTINATION,
                Downloads.Impl.DESTINATION_CACHE_PARTITION_PURGEABLE);
        values.put(Downloads.Impl.COLUMN_VISIBILITY, Downloads.Impl.VISIBILITY_VISIBLE);
        values.put(Downloads.Impl.COLUMN_IS_PUBLIC_API, true);
        return values;
    }

    @Test
    public void testNoPublicApi() {
        ContentValues values = buildValidValues();
        values.remove(Downloads.Impl.COLUMN_IS_PUBLIC_API);
        testInvalidValues(values);
    }

    @Test
    public void testInvalidDestination() {
        ContentValues values = buildValidValues();
        values.put(Downloads.Impl.COLUMN_DESTINATION, Downloads.Impl.DESTINATION_EXTERNAL);
        testInvalidValues(values);
        values.put(Downloads.Impl.COLUMN_DESTINATION, Downloads.Impl.DESTINATION_CACHE_PARTITION);
        testInvalidValues(values);
    }

    @Ignore
    @Test
    public void testInvalidVisibility() {
        ContentValues values = buildValidValues();
        values.put(Downloads.Impl.COLUMN_VISIBILITY,
                Downloads.Impl.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        testInvalidValues(values);

        values.put(Downloads.Impl.COLUMN_VISIBILITY, Downloads.Impl.VISIBILITY_HIDDEN);
        testInvalidValues(values);

        values.remove(Downloads.Impl.COLUMN_VISIBILITY);
        testInvalidValues(values);
    }

    @Test
    public void testDisallowedColumns() {
        for (String column : DISALLOWED_COLUMNS) {
            ContentValues values = buildValidValues();
            values.put(column, 1);
            testInvalidValues(values);
        }
    }

    @Test
    public void testFileUriWithoutExternalPermission() {
        ContentValues values = buildValidValues();
        values.put(Downloads.Impl.COLUMN_DESTINATION, Downloads.Impl.DESTINATION_FILE_URI);
        values.put(Downloads.Impl.COLUMN_FILE_NAME_HINT, "file:///sdcard/foo");
        testInvalidValues(values);
    }

    private void testInvalidValues(ContentValues values) {
        try {
            mContentResolver.insert(Downloads.Impl.CONTENT_URI, values);
            fail("Didn't get SecurityException as expected");
        } catch (SecurityException exc) {
            // expected
        }
    }

    @Test
    public void testDownloadManagerRequest() {
        // first try a minimal request
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse("http://localhost/path"));
        mManager.enqueue(request);

        // now set everything we can, save for external destintion (for which we lack permission)
        request.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI);
        request.setAllowedOverRoaming(false);
        request.setTitle("test");
        request.setDescription("test");
        request.setMimeType("text/html");
        request.addRequestHeader("X-Some-Header", "value");
        mManager.enqueue(request);
    }

    /**
     * Internally, {@code DownloadManager} synchronizes its contents with
     * {@code MediaStore}, which relies heavily on using file extensions to
     * determine MIME types.
     * <p>
     * This test verifies that if an app attempts to add an already-completed
     * download without an extension, that we'll force the MIME type with what
     * {@code MediaStore} would have derived.
     */
    @Test
    public void testAddCompletedWithoutExtension() throws Exception {
        final File dir = Environment
                .getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        final File file = new File(dir, "test" + System.nanoTime());
        file.createNewFile();

        final long id = mManager.addCompletedDownload("My Title", "My Description", true,
                "application/pdf", file.getAbsolutePath(), file.length(), true, true,
                Uri.parse("http://example.com/"), Uri.parse("http://example.net/"));
        final Uri uri = mManager.getDownloadUri(id);

        // Trigger a generic update so that we push to MediaStore
        final ContentValues values = new ContentValues();
        values.put(DownloadManager.COLUMN_DESCRIPTION, "Modified Description");
        mContentResolver.update(uri, values, null);

        try (Cursor c = mContentResolver.query(uri, null, null, null)) {
            assertTrue(c.moveToFirst());

            final String actualMime = c
                    .getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_MEDIA_TYPE));
            final String actualPath = c
                    .getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_FILENAME));

            assertEquals("application/octet-stream", actualMime);
            assertEquals(file.getAbsolutePath(), actualPath);
        }
    }
}
