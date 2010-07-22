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

import android.content.Intent;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.DownloadManager;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.Downloads;
import android.test.suitebuilder.annotation.LargeTest;
import tests.http.MockResponse;
import tests.http.RecordedRequest;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.util.List;

@LargeTest
public class PublicApiFunctionalTest extends AbstractDownloadManagerFunctionalTest {
    private static final String PACKAGE_NAME = "my.package.name";
    private static final int HTTP_NOT_ACCEPTABLE = 406;
    private static final int HTTP_LENGTH_REQUIRED = 411;
    private static final String REQUEST_PATH = "/path";
    private static final String REDIRECTED_PATH = "/other_path";
    private static final String ETAG = "my_etag";

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
            ParcelFileDescriptor downloadedFile = mManager.openDownloadedFile(mId);
            assertTrue("Invalid file descriptor: " + downloadedFile,
                       downloadedFile.getFileDescriptor().valid());
            InputStream stream = new FileInputStream(downloadedFile.getFileDescriptor());
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
        mManager = new DownloadManager(mResolver, PACKAGE_NAME);

        mTestDirectory = new File(Environment.getExternalStorageDirectory() + File.separator
                                  + "download_manager_functional_test");
        if (mTestDirectory.exists()) {
            mTestDirectory.delete();
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
        runSimpleFailureTest(HTTP_NOT_FOUND);
    }

    public void testUnhandledHttpStatus() throws Exception {
        enqueueEmptyResponse(1234); // some invalid HTTP status
        runSimpleFailureTest(DownloadManager.ERROR_UNHANDLED_HTTP_CODE);
    }

    public void testInterruptedDownload() throws Exception {
        int initialLength = 5;
        enqueueInterruptedDownloadResponses(initialLength);

        Download download = enqueueRequest(getRequest());
        download.runUntilStatus(DownloadManager.STATUS_PAUSED);
        assertEquals(initialLength,
                     download.getLongField(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
        assertEquals(FILE_CONTENT.length(),
                     download.getLongField(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));

        mSystemFacade.incrementTimeMillis(RETRY_DELAY_MILLIS);
        RecordedRequest request = download.runUntilStatus(DownloadManager.STATUS_SUCCESSFUL);
        assertEquals(FILE_CONTENT.length(),
                     download.getLongField(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
        assertEquals(FILE_CONTENT, download.getContents());

        List<String> headers = request.getHeaders();
        assertTrue("No Range header: " + headers,
                   headers.contains("Range: bytes=" + initialLength + "-"));
        assertTrue("No ETag header: " + headers, headers.contains("If-Match: " + ETAG));
    }

    private void enqueueInterruptedDownloadResponses(int initialLength) {
        int totalLength = FILE_CONTENT.length();
        // the first response has normal headers but unexpectedly closes after initialLength bytes
        enqueuePartialResponse(initialLength);
        // the second response returns partial content for the rest of the data
        enqueueResponse(HTTP_PARTIAL_CONTENT, FILE_CONTENT.substring(initialLength))
                .addHeader("Content-range",
                           "bytes " + initialLength + "-" + totalLength + "/" + totalLength)
                .addHeader("Etag", ETAG);
    }

    private MockResponse enqueuePartialResponse(int initialLength) {
        return enqueueResponse(HTTP_OK, FILE_CONTENT.substring(0, initialLength))
                               .addHeader("Content-length", FILE_CONTENT.length())
                               .addHeader("Etag", ETAG)
                               .setCloseConnectionAfter(true);
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

    public void testRequestHeaders() throws Exception {
        enqueueEmptyResponse(HTTP_OK);
        Download download = enqueueRequest(getRequest().setRequestHeader("Header1", "value1")
                                           .setRequestHeader("Header2", "value2"));
        RecordedRequest request = download.runUntilStatus(DownloadManager.STATUS_SUCCESSFUL);

        assertTrue(request.getHeaders().contains("Header1: value1"));
        assertTrue(request.getHeaders().contains("Header2: value2"));
    }

    public void testDelete() throws Exception {
        Download download = enqueueRequest(getRequest().setRequestHeader("header", "value"));
        mManager.remove(download.mId);
        Cursor cursor = mManager.query(new DownloadManager.Query());
        try {
            assertEquals(0, cursor.getCount());
        } finally {
            cursor.close();
        }
    }

    public void testSizeLimitOverMobile() throws Exception {
        mSystemFacade.mMaxBytesOverMobile = FILE_CONTENT.length() - 1;

        mSystemFacade.mActiveNetworkType = ConnectivityManager.TYPE_MOBILE;
        enqueueResponse(HTTP_OK, FILE_CONTENT);
        Download download = enqueueRequest(getRequest());
        download.runUntilStatus(DownloadManager.STATUS_PAUSED);

        mSystemFacade.mActiveNetworkType = ConnectivityManager.TYPE_WIFI;
        // first response was read, but aborted after the DL manager processed the Content-Length
        // header, so we need to enqueue a second one
        enqueueResponse(HTTP_OK, FILE_CONTENT);
        download.runUntilStatus(DownloadManager.STATUS_SUCCESSFUL);
    }

    /**
     * Test for race conditions when the service is flooded with startService() calls while running
     * a download.
     */
    public void testFloodServiceWithStarts() throws Exception {
        enqueueResponse(HTTP_OK, FILE_CONTENT);
        Download download = enqueueRequest(getRequest());
        while (download.getStatus() != DownloadManager.STATUS_SUCCESSFUL) {
            startService(null);
            Thread.sleep(10);
        }
    }

    public void testRedirect301() throws Exception {
        RecordedRequest lastRequest = runRedirectionTest(301);
        // for 301, upon retry, we reuse the redirected URI
        assertEquals(REDIRECTED_PATH, lastRequest.getPath());
    }

    // TODO: currently fails
    public void disabledTestRedirect302() throws Exception {
        RecordedRequest lastRequest = runRedirectionTest(302);
        // for 302, upon retry, we use the original URI
        assertEquals(REQUEST_PATH, lastRequest.getPath());
    }

    public void testNoEtag() throws Exception {
        enqueuePartialResponse(5).removeHeader("Etag");
        runSimpleFailureTest(HTTP_LENGTH_REQUIRED);
    }

    public void testSanitizeMediaType() throws Exception {
        enqueueEmptyResponse(HTTP_OK).addHeader("Content-Type", "text/html; charset=ISO-8859-4");
        Download download = enqueueRequest(getRequest());
        download.runUntilStatus(DownloadManager.STATUS_SUCCESSFUL);
        assertEquals("text/html", download.getStringField(DownloadManager.COLUMN_MEDIA_TYPE));
    }

    public void testNoContentLength() throws Exception {
        enqueueEmptyResponse(HTTP_OK).removeHeader("Content-Length");
        runSimpleFailureTest(HTTP_LENGTH_REQUIRED);
    }

    public void testNoContentType() throws Exception {
        enqueueResponse(HTTP_OK, "", false);
        runSimpleFailureTest(HTTP_NOT_ACCEPTABLE);
    }

    public void testInsufficientSpace() throws Exception {
        // this would be better done by stubbing the system API to check available space, but in the
        // meantime, just use an absurdly large header value
        enqueueEmptyResponse(HTTP_OK).addHeader("Content-Length",
                                                1024L * 1024 * 1024 * 1024 * 1024);
        runSimpleFailureTest(DownloadManager.ERROR_INSUFFICIENT_SPACE);
    }

    public void testCancel() throws Exception {
        enqueuePartialResponse(5);
        Download download = enqueueRequest(getRequest());
        download.runUntilStatus(DownloadManager.STATUS_PAUSED);

        mManager.remove(download.mId);
        mSystemFacade.incrementTimeMillis(RETRY_DELAY_MILLIS);
        startService(null);
        Thread.sleep(500); // TODO: eliminate this when we can run the service synchronously
        // if the cancel didn't work, we should get an unexpected request to the HTTP server
    }

    public void testDownloadCompleteBroadcast() throws Exception {
        enqueueEmptyResponse(HTTP_OK);
        Download download = enqueueRequest(getRequest());
        download.runUntilStatus(DownloadManager.STATUS_SUCCESSFUL);

        long startTimeMillis = System.currentTimeMillis();
        while (mSystemFacade.mBroadcastsSent.isEmpty()) {
            Thread.sleep(100);
            if (System.currentTimeMillis() > startTimeMillis + 500) {
                fail("Timed out waiting for broadcast intent");
            }
        }
        assertEquals(1, mSystemFacade.mBroadcastsSent.size());
        Intent broadcast = mSystemFacade.mBroadcastsSent.get(0);
        assertEquals(DownloadManager.ACTION_DOWNLOAD_COMPLETE, broadcast.getAction());
        assertEquals(PACKAGE_NAME, broadcast.getPackage());
        long intentId = broadcast.getExtras().getLong(DownloadManager.EXTRA_DOWNLOAD_ID);
        assertEquals(download.mId, intentId);
    }

    public void testNotificationClickedBroadcast() throws Exception {
        Download download = enqueueRequest(getRequest().setShowNotification(
                DownloadManager.Request.NOTIFICATION_WHEN_RUNNING));

        DownloadReceiver receiver = new DownloadReceiver();
        receiver.mSystemFacade = mSystemFacade;
        Intent intent = new Intent(Constants.ACTION_LIST);
        intent.setData(Uri.parse(Downloads.Impl.CONTENT_URI + "/" + download.mId));
        receiver.onReceive(mContext, intent);

        assertEquals(1, mSystemFacade.mBroadcastsSent.size());
        Intent broadcast = mSystemFacade.mBroadcastsSent.get(0);
        assertEquals(DownloadManager.ACTION_NOTIFICATION_CLICKED, broadcast.getAction());
        assertEquals(PACKAGE_NAME, broadcast.getPackage());
    }

    public void testAllowedNetworkTypes() throws Exception {
        mSystemFacade.mActiveNetworkType = ConnectivityManager.TYPE_MOBILE;

        // by default, use any connection
        enqueueEmptyResponse(HTTP_OK);
        Download download = enqueueRequest(getRequest());
        download.runUntilStatus(DownloadManager.STATUS_SUCCESSFUL);

        // restrict a download to wifi...
        download = enqueueRequest(getRequest()
                                  .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI));
        startService(null);
        waitForDownloadToStop(download, DownloadManager.STATUS_PAUSED);
        // ...then enable wifi
        mSystemFacade.mActiveNetworkType = ConnectivityManager.TYPE_WIFI;
        enqueueEmptyResponse(HTTP_OK);
        download.runUntilStatus(DownloadManager.STATUS_SUCCESSFUL);
    }

    public void testRoaming() throws Exception {
        mSystemFacade.mIsRoaming = true;

        // by default, allow roaming
        enqueueEmptyResponse(HTTP_OK);
        Download download = enqueueRequest(getRequest());
        download.runUntilStatus(DownloadManager.STATUS_SUCCESSFUL);

        // disallow roaming for a download...
        download = enqueueRequest(getRequest().setAllowedOverRoaming(false));
        startService(null);
        waitForDownloadToStop(download, DownloadManager.STATUS_PAUSED);
        // ...then turn off roaming
        mSystemFacade.mIsRoaming = false;
        enqueueEmptyResponse(HTTP_OK);
        download.runUntilStatus(DownloadManager.STATUS_SUCCESSFUL);
    }

    private void runSimpleFailureTest(int expectedErrorCode) throws Exception {
        Download download = enqueueRequest(getRequest());
        download.runUntilStatus(DownloadManager.STATUS_FAILED);
        assertEquals(expectedErrorCode,
                     download.getLongField(DownloadManager.COLUMN_ERROR_CODE));
    }

    /**
     * Run a redirection test consisting of
     * 1) Request to REQUEST_PATH with 3xx response redirecting to another URI
     * 2) Request to REDIRECTED_PATH with interrupted partial response
     * 3) Resume request to complete download
     * @return the last request sent to the server, resuming after the interruption
     */
    private RecordedRequest runRedirectionTest(int status)
            throws MalformedURLException, Exception {
        enqueueEmptyResponse(status).addHeader("Location",
                                               mServer.getUrl(REDIRECTED_PATH).toString());
        enqueueInterruptedDownloadResponses(5);

        Download download = enqueueRequest(getRequest());
        RecordedRequest request = download.runUntilStatus(DownloadManager.STATUS_PAUSED);
        assertEquals(REQUEST_PATH, request.getPath());

        mSystemFacade.incrementTimeMillis(RETRY_DELAY_MILLIS);
        request = download.runUntilStatus(DownloadManager.STATUS_PAUSED);
        assertEquals(REDIRECTED_PATH, request.getPath());

        mSystemFacade.incrementTimeMillis(RETRY_DELAY_MILLIS);
        request = download.runUntilStatus(DownloadManager.STATUS_SUCCESSFUL);
        return request;
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
