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

import android.content.ContentValues;
import android.database.Cursor;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Environment;
import android.provider.Downloads;
import android.test.suitebuilder.annotation.LargeTest;
import tests.http.MockWebServer;
import tests.http.RecordedRequest;

import java.io.InputStream;
import java.net.MalformedURLException;
import java.util.List;

/**
 * This test exercises the entire download manager working together -- it requests downloads through
 * the {@link DownloadProvider}, just like a normal client would, and runs the
 * {@link DownloadService} with start intents.  It sets up a {@link MockWebServer} running on the
 * device to serve downloads.
 */
@LargeTest
public class DownloadManagerFunctionalTest extends AbstractDownloadManagerFunctionalTest {
    public void testBasicRequest() throws Exception {
        enqueueResponse(HTTP_OK, FILE_CONTENT);

        String path = "/download_manager_test_path";
        Uri downloadUri = requestDownload(path);
        assertEquals(Downloads.STATUS_PENDING, getDownloadStatus(downloadUri));
        assertTrue(mTestContext.mHasServiceBeenStarted);

        RecordedRequest request = runUntilStatus(downloadUri, Downloads.STATUS_SUCCESS);
        assertEquals("GET", request.getMethod());
        assertEquals(path, request.getPath());
        assertEquals(FILE_CONTENT, getDownloadContents(downloadUri));
        assertStartsWith(Environment.getExternalStorageDirectory().getPath(),
                         getDownloadFilename(downloadUri));
    }

    public void testDownloadToCache() throws Exception {
        enqueueResponse(HTTP_OK, FILE_CONTENT);
        Uri downloadUri = requestDownload("/path");
        updateDownload(downloadUri, Downloads.COLUMN_DESTINATION,
                       Integer.toString(Downloads.DESTINATION_CACHE_PARTITION));
        runUntilStatus(downloadUri, Downloads.STATUS_SUCCESS);
        assertEquals(FILE_CONTENT, getDownloadContents(downloadUri));
        assertStartsWith(Environment.getDownloadCacheDirectory().getPath(),
                         getDownloadFilename(downloadUri));
    }

    public void testFileNotFound() throws Exception {
        enqueueEmptyResponse(HTTP_NOT_FOUND);
        Uri downloadUri = requestDownload("/nonexistent_path");
        assertEquals(Downloads.STATUS_PENDING, getDownloadStatus(downloadUri));
        runUntilStatus(downloadUri, HTTP_NOT_FOUND);
    }

    public void testRetryAfter() throws Exception {
        final int delay = 120;
        enqueueEmptyResponse(HTTP_SERVICE_UNAVAILABLE).addHeader("Retry-after", delay);
        Uri downloadUri = requestDownload("/path");
        runUntilStatus(downloadUri, Downloads.STATUS_RUNNING_PAUSED);

        // download manager adds random 0-30s offset
        mSystemFacade.incrementTimeMillis((delay + 31) * 1000);

        enqueueResponse(HTTP_OK, FILE_CONTENT);
        runUntilStatus(downloadUri, Downloads.STATUS_SUCCESS);
    }

    public void testRedirect() throws Exception {
        enqueueEmptyResponse(301).addHeader("Location", mServer.getUrl("/other_path").toString());
        enqueueResponse(HTTP_OK, FILE_CONTENT);
        Uri downloadUri = requestDownload("/path");
        RecordedRequest request = runUntilStatus(downloadUri, Downloads.STATUS_RUNNING_PAUSED);
        assertEquals("/path", request.getPath());

        mSystemFacade.incrementTimeMillis(RETRY_DELAY_MILLIS);
        request = runUntilStatus(downloadUri, Downloads.STATUS_SUCCESS);
        assertEquals("/other_path", request.getPath());
    }

    public void testBasicConnectivityChanges() throws Exception {
        enqueueResponse(HTTP_OK, FILE_CONTENT);
        Uri downloadUri = requestDownload("/path");

        // without connectivity, download immediately pauses
        mTestContext.mFakeIConnectivityManager.setNetworkState(NetworkInfo.State.DISCONNECTED);
        startService(null);
        super.waitForDownloadToStop(getStatusReader(downloadUri), Downloads.STATUS_RUNNING_PAUSED);

        // connecting should start the download
        mTestContext.mFakeIConnectivityManager.setNetworkState(NetworkInfo.State.CONNECTED);
        runUntilStatus(downloadUri, Downloads.STATUS_SUCCESS);
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
        Uri downloadUri = requestDownload("/path");

        runUntilStatus(downloadUri, Downloads.STATUS_RUNNING_PAUSED);

        mSystemFacade.incrementTimeMillis(RETRY_DELAY_MILLIS);
        // the second response returns partial content for the rest of the data
        enqueueResponse(HTTP_PARTIAL_CONTENT, FILE_CONTENT.substring(initialLength))
                .addHeader("Content-range",
                           "bytes " + initialLength + "-" + totalLength + "/" + totalLength)
                .addHeader("Etag", etag);
        // TODO: ideally we wouldn't need to call startService again, but there's a bug where the
        // service won't retry a download until an intent comes in
        RecordedRequest request = runUntilStatus(downloadUri, Downloads.STATUS_SUCCESS);

        List<String> headers = request.getHeaders();
        assertTrue("No Range header: " + headers,
                   headers.contains("Range: bytes=" + initialLength + "-"));
        assertTrue("No ETag header: " + headers, headers.contains("If-Match: " + etag));
        assertEquals(FILE_CONTENT, getDownloadContents(downloadUri));
    }

    /**
     * Read a downloaded file from disk.
     */
    private String getDownloadContents(Uri downloadUri) throws Exception {
        InputStream inputStream = mResolver.openInputStream(downloadUri);
        try {
            return readStream(inputStream);
        } finally {
            inputStream.close();
        }
    }

    private RecordedRequest runUntilStatus(Uri downloadUri, int status) throws Exception {
        return super.runUntilStatus(getStatusReader(downloadUri), status);
    }

    private StatusReader getStatusReader(final Uri downloadUri) {
        return new StatusReader() {
            public int getStatus() {
                return getDownloadStatus(downloadUri);
            }

            public boolean isComplete(int status) {
                return !Downloads.isStatusInformational(status);
            }
        };
    }

    protected int getDownloadStatus(Uri downloadUri) {
        return Integer.valueOf(getDownloadField(downloadUri, Downloads.COLUMN_STATUS));
    }

    private String getDownloadFilename(Uri downloadUri) {
        return getDownloadField(downloadUri, Downloads._DATA);
    }

    private String getDownloadField(Uri downloadUri, String column) {
        final String[] columns = new String[] {column};
        Cursor cursor = mResolver.query(downloadUri, columns, null, null, null);
        try {
            assertEquals(1, cursor.getCount());
            cursor.moveToFirst();
            return cursor.getString(0);
        } finally {
            cursor.close();
        }
    }

    /**
     * Request a download from the Download Manager.
     */
    private Uri requestDownload(String path) throws MalformedURLException {
        ContentValues values = new ContentValues();
        values.put(Downloads.COLUMN_URI, getServerUri(path));
        values.put(Downloads.COLUMN_DESTINATION, Downloads.DESTINATION_EXTERNAL);
        return mResolver.insert(Downloads.CONTENT_URI, values);
    }

    /**
     * Update one field of a download in the provider.
     */
    private void updateDownload(Uri downloadUri, String column, String value) {
        ContentValues values = new ContentValues();
        values.put(column, value);
        int numChanged = mResolver.update(downloadUri, values, null, null);
        assertEquals(1, numChanged);
    }
}
