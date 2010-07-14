/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.providers.downloads;

import android.database.Cursor;
import android.net.DownloadManager;
import android.net.Uri;
import android.os.Environment;
import tests.http.RecordedRequest;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.MalformedURLException;

public class PublicApiFunctionalTest extends AbstractDownloadManagerFunctionalTest {
    private static final String REQUEST_PATH = "/path";

    class Download implements StatusReader {
        final long mId;

        private Download(long downloadId) {
            this.mId = downloadId;
        }

        public int getStatus() {
            return (int) getLongField(DownloadManager.COLUMN_STATUS);
        }

        public boolean isComplete(int status) {
            return status != DownloadManager.STATUS_PENDING
                    && status != DownloadManager.STATUS_RUNNING
                    && status != DownloadManager.STATUS_PAUSED;
        }

        String getStringField(String field) {
            Cursor cursor = mManager.query(new DownloadManager.Query().setFilterById(mId));
            try {
                assertEquals(1, cursor.getCount());
                cursor.moveToFirst();
                return cursor.getString(cursor.getColumnIndexOrThrow(field));
            } finally {
                cursor.close();
            }
        }

        long getLongField(String field) {
            Cursor cursor = mManager.query(new DownloadManager.Query().setFilterById(mId));
            try {
                assertEquals(1, cursor.getCount());
                cursor.moveToFirst();
                return cursor.getLong(cursor.getColumnIndexOrThrow(field));
            } finally {
                cursor.close();
            }
        }

        String getContents() throws Exception {
            InputStream stream = new FileInputStream(
                    mManager.openDownloadedFile(mId).getFileDescriptor());
            try {
                return readStream(stream);
            } finally {
                stream.close();
            }
        }

        RecordedRequest runUntilStatus(int status) throws Exception {
            return PublicApiFunctionalTest.this.runUntilStatus(this, status);
        }
    }

    private DownloadManager mManager;
    private File mTestDirectory;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mManager = new DownloadManager(mResolver);

        mTestDirectory = new File(Environment.getExternalStorageDirectory() + File.separator
                                  + "download_manager_functional_test");
        if (mTestDirectory.exists()) {
            throw new RuntimeException(
                    "Test directory on external storage already exists, cannot run");
        }
        if (!mTestDirectory.mkdir()) {
            throw new RuntimeException("Couldn't create test directory: "
                                       + mTestDirectory.getPath());
        }
    }

    @Override
    protected void tearDown() throws Exception {
        if (mTestDirectory != null) {
            for (File file : mTestDirectory.listFiles()) {
                file.delete();
            }
            mTestDirectory.delete();
        }
        super.tearDown();
    }

    public void testBasicRequest() throws Exception {
        enqueueResponse(HTTP_OK, FILE_CONTENT);

        Download download = enqueueRequest(getRequest());
        assertEquals(DownloadManager.STATUS_PENDING,
                     download.getLongField(DownloadManager.COLUMN_STATUS));
        assertEquals(getServerUri(REQUEST_PATH),
                     download.getStringField(DownloadManager.COLUMN_URI));
        assertEquals(download.mId, download.getLongField(DownloadManager.COLUMN_ID));
        assertEquals(mSystemFacade.currentTimeMillis(),
                     download.getLongField(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP));

        mSystemFacade.incrementTimeMillis(10);
        RecordedRequest request = download.runUntilStatus(DownloadManager.STATUS_SUCCESSFUL);
        assertEquals("GET", request.getMethod());
        assertEquals(REQUEST_PATH, request.getPath());

        Uri localUri = Uri.parse(download.getStringField(DownloadManager.COLUMN_LOCAL_URI));
        assertEquals("file", localUri.getScheme());
        assertStartsWith("//" + Environment.getDownloadCacheDirectory().getPath(),
                         localUri.getSchemeSpecificPart());
        assertEquals("text/plain", download.getStringField(DownloadManager.COLUMN_MEDIA_TYPE));

        int size = FILE_CONTENT.length();
        assertEquals(size, download.getLongField(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
        assertEquals(size, download.getLongField(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
        assertEquals(mSystemFacade.currentTimeMillis(),
                     download.getLongField(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP));

        assertEquals(FILE_CONTENT, download.getContents());
    }

    public void testTitleAndDescription() throws Exception {
        Download download = enqueueRequest(getRequest()
                                           .setTitle("my title")
                                           .setDescription("my description"));
        assertEquals("my title", download.getStringField(DownloadManager.COLUMN_TITLE));
        assertEquals("my description",
                     download.getStringField(DownloadManager.COLUMN_DESCRIPTION));
    }

    public void testDownloadError() throws Exception {
        enqueueEmptyResponse(HTTP_NOT_FOUND);
        Download download = enqueueRequest(getRequest());
        download.runUntilStatus(DownloadManager.STATUS_FAILED);
        assertEquals(HTTP_NOT_FOUND, download.getLongField(DownloadManager.COLUMN_ERROR_CODE));
    }

    public void testUnhandledHttpStatus() throws Exception {
        enqueueEmptyResponse(1234); // some invalid HTTP status
        Download download = enqueueRequest(getRequest());
        download.runUntilStatus(DownloadManager.STATUS_FAILED);
        assertEquals(DownloadManager.ERROR_UNHANDLED_HTTP_CODE,
                     download.getLongField(DownloadManager.COLUMN_ERROR_CODE));
    }

    public void testInterruptedDownload() throws Exception {
        int initialLength = 5;
        String etag = "my_etag";
        int totalLength = FILE_CONTENT.length();
        // the first response has normal headers but unexpectedly closes after initialLength bytes
        enqueueResponse(HTTP_OK, FILE_CONTENT.substring(0, initialLength))
                .addHeader("Content-length", totalLength)
                .addHeader("Etag", etag)
                .setCloseConnectionAfter(true);
        Download download = enqueueRequest(getRequest());

        download.runUntilStatus(DownloadManager.STATUS_PAUSED);
        assertEquals(initialLength,
                     download.getLongField(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));

        mSystemFacade.incrementTimeMillis(RETRY_DELAY_MILLIS);
        // the second response returns partial content for the rest of the data
        enqueueResponse(HTTP_PARTIAL_CONTENT, FILE_CONTENT.substring(initialLength))
                .addHeader("Content-range",
                           "bytes " + initialLength + "-" + totalLength + "/" + totalLength)
                .addHeader("Etag", etag);
        download.runUntilStatus(DownloadManager.STATUS_SUCCESSFUL);
        assertEquals(totalLength,
                     download.getLongField(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
    }

    public void testFiltering() throws Exception {
        enqueueEmptyResponse(HTTP_OK);
        Download download1 = enqueueRequest(getRequest());
        download1.runUntilStatus(DownloadManager.STATUS_SUCCESSFUL);
        enqueueEmptyResponse(HTTP_NOT_FOUND);

        mSystemFacade.incrementTimeMillis(1); // ensure downloads are correctly ordered by time
        Download download2 = enqueueRequest(getRequest());
        download2.runUntilStatus(DownloadManager.STATUS_FAILED);

        mSystemFacade.incrementTimeMillis(1);
        Download download3 = enqueueRequest(getRequest());

        Cursor cursor = mManager.query(new DownloadManager.Query());
        checkAndCloseCursor(cursor, download3, download2, download1);

        cursor = mManager.query(new DownloadManager.Query().setFilterById(download2.mId));
        checkAndCloseCursor(cursor, download2);

        cursor = mManager.query(new DownloadManager.Query()
                                .setFilterByStatus(DownloadManager.STATUS_PENDING));
        checkAndCloseCursor(cursor, download3);

        cursor = mManager.query(new DownloadManager.Query()
                                .setFilterByStatus(DownloadManager.STATUS_FAILED
                                              | DownloadManager.STATUS_SUCCESSFUL));
        checkAndCloseCursor(cursor, download2, download1);

        cursor = mManager.query(new DownloadManager.Query()
                                .setFilterByStatus(DownloadManager.STATUS_RUNNING));
        checkAndCloseCursor(cursor);
    }

    private void checkAndCloseCursor(Cursor cursor, Download... downloads) {
        try {
            int idIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID);
            assertEquals(downloads.length, cursor.getCount());
            cursor.moveToFirst();
            for (Download download : downloads) {
                assertEquals(download.mId, cursor.getLong(idIndex));
                cursor.moveToNext();
            }
        } finally {
            cursor.close();
        }
    }

    public void testInvalidUri() throws Exception {
        try {
            enqueueRequest(getRequest("/no_host"));
        } catch (IllegalArgumentException exc) { // expected
            return;
        }

        fail("No exception thrown for invalid URI");
    }

    public void testDestination() throws Exception {
        enqueueResponse(HTTP_OK, FILE_CONTENT);
        Uri destination = Uri.fromFile(mTestDirectory).buildUpon().appendPath("testfile").build();
        Download download = enqueueRequest(getRequest().setDestinationUri(destination));
        download.runUntilStatus(DownloadManager.STATUS_SUCCESSFUL);

        Uri localUri = Uri.parse(download.getStringField(DownloadManager.COLUMN_LOCAL_URI));
        assertEquals(destination, localUri);

        InputStream stream = new FileInputStream(destination.getSchemeSpecificPart());
        try {
            assertEquals(FILE_CONTENT, readStream(stream));
        } finally {
            stream.close();
        }
    }

    private DownloadManager.Request getRequest() throws MalformedURLException {
        return getRequest(getServerUri(REQUEST_PATH));
    }

    private DownloadManager.Request getRequest(String path) {
        return new DownloadManager.Request(Uri.parse(path));
    }

    private Download enqueueRequest(DownloadManager.Request request) {
        return new Download(mManager.enqueue(request));
    }
}
