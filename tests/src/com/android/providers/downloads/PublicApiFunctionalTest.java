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

import static com.google.testing.littlemock.LittleMock.anyInt;
import static com.google.testing.littlemock.LittleMock.anyString;
import static com.google.testing.littlemock.LittleMock.atLeastOnce;
import static com.google.testing.littlemock.LittleMock.isA;
import static com.google.testing.littlemock.LittleMock.never;
import static com.google.testing.littlemock.LittleMock.times;
import static com.google.testing.littlemock.LittleMock.verify;

import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Environment;
import android.provider.Downloads;
import android.test.suitebuilder.annotation.LargeTest;

import com.google.mockwebserver.MockResponse;
import com.google.mockwebserver.RecordedRequest;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.util.List;


@LargeTest
public class PublicApiFunctionalTest extends AbstractPublicApiTest {
    private static final String REDIRECTED_PATH = "/other_path";
    private static final String ETAG = "my_etag";

    protected File mTestDirectory;
    private NotificationManager mNotifManager;

    public PublicApiFunctionalTest() {
        super(new FakeSystemFacade());
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mNotifManager = (NotificationManager) getContext()
                .getSystemService(Context.NOTIFICATION_SERVICE);

        mTestDirectory = new File(Environment.getExternalStorageDirectory() + File.separator
                                  + "download_manager_functional_test");
        if (mTestDirectory.exists()) {
            for (File file : mTestDirectory.listFiles()) {
                file.delete();
            }
        } else {
            mTestDirectory.mkdir();
        }
        mSystemFacade.setStartThreadsWithoutWaiting(false);
    }

    @Override
    protected void tearDown() throws Exception {
        if (mTestDirectory != null && mTestDirectory.exists()) {
            for (File file : mTestDirectory.listFiles()) {
                file.delete();
            }
            mTestDirectory.delete();
        }
        super.tearDown();
    }

    public void testBasicRequest() throws Exception {
        enqueueResponse(buildResponse(HTTP_OK, FILE_CONTENT));

        Download download = enqueueRequest(getRequest());
        assertEquals(DownloadManager.STATUS_PENDING,
                     download.getLongField(DownloadManager.COLUMN_STATUS));
        assertEquals(getServerUri(REQUEST_PATH),
                     download.getStringField(DownloadManager.COLUMN_URI));
        assertEquals(download.mId, download.getLongField(DownloadManager.COLUMN_ID));
        assertEquals(mSystemFacade.currentTimeMillis(),
                     download.getLongField(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP));

        mSystemFacade.incrementTimeMillis(10);
        download.runUntilStatus(DownloadManager.STATUS_SUCCESSFUL);
        RecordedRequest request = takeRequest();
        assertEquals("GET", request.getMethod());
        assertEquals(REQUEST_PATH, request.getPath());

        Uri localUri = Uri.parse(download.getStringField(DownloadManager.COLUMN_LOCAL_URI));
        assertEquals("content", localUri.getScheme());
        checkUriContent(localUri);
        assertEquals("text/plain", download.getStringField(DownloadManager.COLUMN_MEDIA_TYPE));

        int size = FILE_CONTENT.length();
        assertEquals(size, download.getLongField(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
        assertEquals(size, download.getLongField(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
        assertEquals(mSystemFacade.currentTimeMillis(),
                     download.getLongField(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP));

        checkCompleteDownload(download);
    }

    private void checkUriContent(Uri uri) throws FileNotFoundException, IOException {
        InputStream inputStream = mResolver.openInputStream(uri);
        try {
            assertEquals(FILE_CONTENT, readStream(inputStream));
        } finally {
            inputStream.close();
        }
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
        enqueueResponse(buildEmptyResponse(HTTP_NOT_FOUND));
        runSimpleFailureTest(HTTP_NOT_FOUND);
    }

    public void testUnhandledHttpStatus() throws Exception {
        enqueueResponse(buildEmptyResponse(1234)); // some invalid HTTP status
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
        takeRequest(); // get the first request out of the queue

        mSystemFacade.incrementTimeMillis(RETRY_DELAY_MILLIS);
        download.runUntilStatus(DownloadManager.STATUS_SUCCESSFUL);
        checkCompleteDownload(download);

        List<String> headers = takeRequest().getHeaders();
        assertTrue("No Range header: " + headers,
                   headers.contains("Range: bytes=" + initialLength + "-"));
        assertTrue("No ETag header: " + headers, headers.contains("If-Match: " + ETAG));
    }

    public void testInterruptedExternalDownload() throws Exception {
        enqueueInterruptedDownloadResponses(5);
        Download download = enqueueRequest(getRequest().setDestinationUri(getExternalUri()));
        download.runUntilStatus(DownloadManager.STATUS_PAUSED);
        mSystemFacade.incrementTimeMillis(RETRY_DELAY_MILLIS);
        download.runUntilStatus(DownloadManager.STATUS_SUCCESSFUL);
        checkCompleteDownload(download);
    }

    private void enqueueInterruptedDownloadResponses(int initialLength) {
        // the first response has normal headers but unexpectedly closes after initialLength bytes
        enqueueResponse(buildPartialResponse(0, initialLength));
        // the second response returns partial content for the rest of the data
        enqueueResponse(buildPartialResponse(initialLength, FILE_CONTENT.length()));
    }

    private MockResponse buildPartialResponse(int start, int end) {
        int totalLength = FILE_CONTENT.length();
        boolean isFirstResponse = (start == 0);
        int status = isFirstResponse ? HTTP_OK : HTTP_PARTIAL_CONTENT;
        MockResponse response = buildResponse(status, FILE_CONTENT.substring(start, end))
                .setHeader("Content-length", totalLength)
                .setHeader("Etag", ETAG);
        if (!isFirstResponse) {
            response.setHeader(
                    "Content-range", "bytes " + start + "-" + totalLength + "/" + totalLength);
        }
        return response;
    }

    // enqueue a huge response to keep the receiveing thread in DownloadThread.java busy for a while
    // give enough time to do something (cancel/remove etc) on that downloadrequest
    // while it is in progress
    private MockResponse buildContinuingResponse() {
        int numPackets = 100;
        int contentLength = STRING_1K.length() * numPackets;
        return buildResponse(HTTP_OK, STRING_1K)
               .setHeader("Content-length", contentLength)
               .setHeader("Etag", ETAG)
               .setBytesPerSecond(1024);
    }

    public void testFiltering() throws Exception {
        enqueueResponse(buildEmptyResponse(HTTP_OK));
        enqueueResponse(buildEmptyResponse(HTTP_NOT_FOUND));

        Download download1 = enqueueRequest(getRequest());
        download1.runUntilStatus(DownloadManager.STATUS_SUCCESSFUL);

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

        mSystemFacade.incrementTimeMillis(1);
        Download invisibleDownload = enqueueRequest(getRequest().setVisibleInDownloadsUi(false));
        cursor = mManager.query(new DownloadManager.Query());
        checkAndCloseCursor(cursor, invisibleDownload, download3, download2, download1);
        cursor = mManager.query(new DownloadManager.Query().setOnlyIncludeVisibleInDownloadsUi(true));
        checkAndCloseCursor(cursor, download3, download2, download1);
    }

    public void testOrdering() throws Exception {
        enqueueResponse(buildResponse(HTTP_OK, "small contents"));
        enqueueResponse(buildResponse(HTTP_OK, "large contents large contents"));
        enqueueResponse(buildEmptyResponse(HTTP_NOT_FOUND));

        Download download1 = enqueueRequest(getRequest());
        download1.runUntilStatus(DownloadManager.STATUS_SUCCESSFUL);

        mSystemFacade.incrementTimeMillis(1);
        Download download2 = enqueueRequest(getRequest());
        download2.runUntilStatus(DownloadManager.STATUS_SUCCESSFUL);

        mSystemFacade.incrementTimeMillis(1);
        Download download3 = enqueueRequest(getRequest());
        download3.runUntilStatus(DownloadManager.STATUS_FAILED);

        // default ordering -- by timestamp descending
        Cursor cursor = mManager.query(new DownloadManager.Query());
        checkAndCloseCursor(cursor, download3, download2, download1);

        cursor = mManager.query(new DownloadManager.Query()
                .orderBy(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP,
                        DownloadManager.Query.ORDER_ASCENDING));
        checkAndCloseCursor(cursor, download1, download2, download3);

        cursor = mManager.query(new DownloadManager.Query()
                .orderBy(DownloadManager.COLUMN_TOTAL_SIZE_BYTES,
                        DownloadManager.Query.ORDER_DESCENDING));
        checkAndCloseCursor(cursor, download2, download1, download3);

        cursor = mManager.query(new DownloadManager.Query()
                .orderBy(DownloadManager.COLUMN_TOTAL_SIZE_BYTES,
                        DownloadManager.Query.ORDER_ASCENDING));
        checkAndCloseCursor(cursor, download3, download1, download2);
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
        enqueueResponse(buildResponse(HTTP_OK, FILE_CONTENT));
        Uri destination = getExternalUri();
        Download download = enqueueRequest(getRequest().setDestinationUri(destination));
        download.runUntilStatus(DownloadManager.STATUS_SUCCESSFUL);

        Uri localUri = Uri.parse(download.getStringField(DownloadManager.COLUMN_LOCAL_URI));
        assertEquals(destination, localUri);

        InputStream stream = new FileInputStream(destination.getPath());
        try {
            assertEquals(FILE_CONTENT, readStream(stream));
        } finally {
            stream.close();
        }
    }

    private Uri getExternalUri() {
        return Uri.fromFile(mTestDirectory).buildUpon().appendPath("testfile.txt").build();
    }

    public void testRequestHeaders() throws Exception {
        enqueueResponse(buildEmptyResponse(HTTP_OK));
        Download download = enqueueRequest(getRequest().addRequestHeader("Header1", "value1")
                                           .addRequestHeader("Header2", "value2"));
        download.runUntilStatus(DownloadManager.STATUS_SUCCESSFUL);

        List<String> headers = takeRequest().getHeaders();
        assertTrue(headers.contains("Header1: value1"));
        assertTrue(headers.contains("Header2: value2"));
    }

    public void testDelete() throws Exception {
        Download download = enqueueRequest(getRequest().addRequestHeader("header", "value"));
        mManager.remove(download.mId);
        Cursor cursor = mManager.query(new DownloadManager.Query());
        try {
            assertEquals(0, cursor.getCount());
        } finally {
            cursor.close();
        }
    }

    public void testSizeLimitOverMobile() throws Exception {
        enqueueResponse(buildResponse(HTTP_OK, FILE_CONTENT));
        enqueueResponse(buildResponse(HTTP_OK, FILE_CONTENT));

        mSystemFacade.mMaxBytesOverMobile = (long) FILE_CONTENT.length() - 1;
        mSystemFacade.mActiveNetworkType = ConnectivityManager.TYPE_MOBILE;
        Download download = enqueueRequest(getRequest());
        download.runUntilStatus(DownloadManager.STATUS_PAUSED);

        mSystemFacade.mActiveNetworkType = ConnectivityManager.TYPE_WIFI;
        // first response was read, but aborted after the DL manager processed the Content-Length
        // header, so we need to enqueue a second one
        download.runUntilStatus(DownloadManager.STATUS_SUCCESSFUL);
    }

    public void testRedirect301() throws Exception {
        RecordedRequest lastRequest = runRedirectionTest(301);
        // for 301, upon retry/resume, we reuse the redirected URI
        assertEquals(REDIRECTED_PATH, lastRequest.getPath());
    }

    public void testRedirect302() throws Exception {
        RecordedRequest lastRequest = runRedirectionTest(302);
        // for 302, upon retry/resume, we use the original URI
        assertEquals(REQUEST_PATH, lastRequest.getPath());
    }

    public void testNoEtag() throws Exception {
        enqueueResponse(buildPartialResponse(0, 5).removeHeader("Etag"));
        runSimpleFailureTest(DownloadManager.ERROR_CANNOT_RESUME);
    }

    public void testSanitizeMediaType() throws Exception {
        enqueueResponse(buildEmptyResponse(HTTP_OK)
                .setHeader("Content-Type", "text/html; charset=ISO-8859-4"));
        Download download = enqueueRequest(getRequest());
        download.runUntilStatus(DownloadManager.STATUS_SUCCESSFUL);
        assertEquals("text/html", download.getStringField(DownloadManager.COLUMN_MEDIA_TYPE));
    }

    public void testNoContentLength() throws Exception {
        enqueueResponse(buildEmptyResponse(HTTP_OK).removeHeader("Content-length"));
        runSimpleFailureTest(DownloadManager.ERROR_HTTP_DATA_ERROR);
    }

    public void testInsufficientSpace() throws Exception {
        // this would be better done by stubbing the system API to check available space, but in the
        // meantime, just use an absurdly large header value
        enqueueResponse(buildEmptyResponse(HTTP_OK)
                .setHeader("Content-Length", 1024L * 1024 * 1024 * 1024 * 1024));
        runSimpleFailureTest(DownloadManager.ERROR_INSUFFICIENT_SPACE);
    }

    public void testCancel() throws Exception {
        mSystemFacade.setStartThreadsWithoutWaiting(true);
        // return 'real time' from FakeSystemFacade so that DownloadThread will report progress
        mSystemFacade.setReturnActualTime(true);
        enqueueResponse(buildContinuingResponse());
        Download download = enqueueRequest(getRequest());
        startService(null);
        // give the download time to get started and progress to 1% completion
        // before cancelling it.
        boolean rslt = download.runUntilProgress(1);
        assertTrue(rslt);
        mManager.remove(download.mId);
        startService(null);
        int status = download.runUntilDone();
        // make sure the row is gone from the database
        assertEquals(-1, status);
        mSystemFacade.setReturnActualTime(false);
    }

    public void testDownloadCompleteBroadcast() throws Exception {
        enqueueResponse(buildEmptyResponse(HTTP_OK));
        Download download = enqueueRequest(getRequest());
        download.runUntilStatus(DownloadManager.STATUS_SUCCESSFUL);

        assertEquals(1, mSystemFacade.mBroadcastsSent.size());
        Intent broadcast = mSystemFacade.mBroadcastsSent.get(0);
        assertEquals(DownloadManager.ACTION_DOWNLOAD_COMPLETE, broadcast.getAction());
        assertEquals(PACKAGE_NAME, broadcast.getPackage());
        long intentId = broadcast.getExtras().getLong(DownloadManager.EXTRA_DOWNLOAD_ID);
        assertEquals(download.mId, intentId);
    }

    public void testNotificationClickedBroadcast() throws Exception {
        Download download = enqueueRequest(getRequest());

        DownloadReceiver receiver = new DownloadReceiver();
        receiver.mSystemFacade = mSystemFacade;
        Intent intent = new Intent(Constants.ACTION_LIST);
        intent.setData(Uri.parse(Downloads.Impl.CONTENT_URI + "/" + download.mId));
        intent.putExtra(DownloadManager.EXTRA_NOTIFICATION_CLICK_DOWNLOAD_IDS,
                new long[] { download.mId });
        receiver.onReceive(mContext, intent);

        assertEquals(1, mSystemFacade.mBroadcastsSent.size());
        Intent broadcast = mSystemFacade.mBroadcastsSent.get(0);
        assertEquals(DownloadManager.ACTION_NOTIFICATION_CLICKED, broadcast.getAction());
        assertEquals(PACKAGE_NAME, broadcast.getPackage());
    }

    public void testBasicConnectivityChanges() throws Exception {
        enqueueResponse(buildResponse(HTTP_OK, FILE_CONTENT));

        // without connectivity, download immediately pauses
        mSystemFacade.mActiveNetworkType = null;
        Download download = enqueueRequest(getRequest());
        download.runUntilStatus(DownloadManager.STATUS_PAUSED);

        // connecting should start the download
        mSystemFacade.mActiveNetworkType = ConnectivityManager.TYPE_WIFI;
        download.runUntilStatus(DownloadManager.STATUS_SUCCESSFUL);
    }

    public void testAllowedNetworkTypes() throws Exception {
        enqueueResponse(buildEmptyResponse(HTTP_OK));
        enqueueResponse(buildEmptyResponse(HTTP_OK));

        mSystemFacade.mActiveNetworkType = ConnectivityManager.TYPE_MOBILE;

        // by default, use any connection
        Download download = enqueueRequest(getRequest());
        download.runUntilStatus(DownloadManager.STATUS_SUCCESSFUL);

        // restrict a download to wifi...
        download = enqueueRequest(getRequest()
                                  .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI));
        download.runUntilStatus(DownloadManager.STATUS_PAUSED);
        // ...then enable wifi
        mSystemFacade.mActiveNetworkType = ConnectivityManager.TYPE_WIFI;
        download.runUntilStatus(DownloadManager.STATUS_SUCCESSFUL);
    }

    public void testRoaming() throws Exception {
        enqueueResponse(buildEmptyResponse(HTTP_OK));
        enqueueResponse(buildEmptyResponse(HTTP_OK));

        mSystemFacade.mIsRoaming = true;

        // by default, allow roaming
        Download download = enqueueRequest(getRequest());
        download.runUntilStatus(DownloadManager.STATUS_SUCCESSFUL);

        // disallow roaming for a download...
        download = enqueueRequest(getRequest().setAllowedOverRoaming(false));
        download.runUntilStatus(DownloadManager.STATUS_PAUSED);
        // ...then turn off roaming
        mSystemFacade.mIsRoaming = false;
        download.runUntilStatus(DownloadManager.STATUS_SUCCESSFUL);
    }

    public void testContentObserver() throws Exception {
        enqueueResponse(buildEmptyResponse(HTTP_OK));
        enqueueRequest(getRequest());
        mResolver.resetNotified();
        runService();
        assertTrue(mResolver.mNotifyWasCalled);
    }

    public void testNotificationNever() throws Exception {
        enqueueResponse(buildEmptyResponse(HTTP_OK));

        final Download download = enqueueRequest(
                getRequest().setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN));
        download.runUntilStatus(DownloadManager.STATUS_SUCCESSFUL);
        runService();

        verify(mNotifManager, never()).notify(anyString(), anyInt(), isA(Notification.class));
        // TODO: verify that it never cancels
    }

    public void testNotificationVisible() throws Exception {
        enqueueResponse(buildEmptyResponse(HTTP_OK));

        // only shows in-progress notifications
        final Download download = enqueueRequest(getRequest());
        download.runUntilStatus(DownloadManager.STATUS_SUCCESSFUL);
        runService();

        // TODO: verify different notif types with tags
        verify(mNotifManager, atLeastOnce()).notify(anyString(), anyInt(), isA(Notification.class));
        verify(mNotifManager, times(1)).cancel(anyString(), anyInt());
    }

    public void testNotificationVisibleComplete() throws Exception {
        enqueueResponse(buildEmptyResponse(HTTP_OK));

        final Download download = enqueueRequest(getRequest().setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED));
        download.runUntilStatus(DownloadManager.STATUS_SUCCESSFUL);
        runService();

        // TODO: verify different notif types with tags
        verify(mNotifManager, atLeastOnce()).notify(anyString(), anyInt(), isA(Notification.class));
        verify(mNotifManager, times(1)).cancel(anyString(), anyInt());
    }

    public void testRetryAfter() throws Exception {
        final int delay = 120;
        enqueueResponse(
                buildEmptyResponse(HTTP_SERVICE_UNAVAILABLE).setHeader("Retry-after", delay));
        enqueueResponse(buildEmptyResponse(HTTP_OK));

        Download download = enqueueRequest(getRequest());
        download.runUntilStatus(DownloadManager.STATUS_PAUSED);

        // download manager adds random 0-30s offset
        mSystemFacade.incrementTimeMillis((delay + 31) * 1000);
        download.runUntilStatus(DownloadManager.STATUS_SUCCESSFUL);
    }

    public void testManyInterruptions() throws Exception {
        final int length = FILE_CONTENT.length();
        for (int i = 0; i < length; i++) {
            enqueueResponse(buildPartialResponse(i, i + 1));
        }

        Download download = enqueueRequest(getRequest());
        for (int i = 0; i < length - 1; i++) {
            download.runUntilStatus(DownloadManager.STATUS_PAUSED);
            mSystemFacade.incrementTimeMillis(RETRY_DELAY_MILLIS);
        }

        download.runUntilStatus(DownloadManager.STATUS_SUCCESSFUL);
        checkCompleteDownload(download);
    }

    public void testExistingFile() throws Exception {
        enqueueResponse(buildEmptyResponse(HTTP_OK));

        // download a file which already exists.
        // downloadservice should simply create filename with "-" and a number attached
        // at the end; i.e., download shouldnot fail.
        Uri destination = getExternalUri();
        new File(destination.getPath()).createNewFile();

        Download download = enqueueRequest(getRequest().setDestinationUri(destination));
        download.runUntilStatus(DownloadManager.STATUS_SUCCESSFUL);
    }

    public void testEmptyFields() throws Exception {
        Download download = enqueueRequest(getRequest());
        assertEquals("", download.getStringField(DownloadManager.COLUMN_TITLE));
        assertEquals("", download.getStringField(DownloadManager.COLUMN_DESCRIPTION));
        assertNull(download.getStringField(DownloadManager.COLUMN_MEDIA_TYPE));
        assertEquals(0, download.getLongField(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
        assertEquals(-1, download.getLongField(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
        // just ensure no exception is thrown
        download.getLongField(DownloadManager.COLUMN_REASON);
    }

    public void testRestart() throws Exception {
        enqueueResponse(buildEmptyResponse(HTTP_NOT_FOUND));
        enqueueResponse(buildEmptyResponse(HTTP_OK));

        Download download = enqueueRequest(getRequest());
        download.runUntilStatus(DownloadManager.STATUS_FAILED);

        mManager.restartDownload(download.mId);
        assertEquals(DownloadManager.STATUS_PENDING,
                download.getLongField(DownloadManager.COLUMN_STATUS));
        download.runUntilStatus(DownloadManager.STATUS_SUCCESSFUL);
    }

    private void checkCompleteDownload(Download download) throws Exception {
        assertEquals(FILE_CONTENT.length(),
                     download.getLongField(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
        assertEquals(FILE_CONTENT, download.getContents());
    }

    private void runSimpleFailureTest(int expectedErrorCode) throws Exception {
        Download download = enqueueRequest(getRequest());
        download.runUntilStatus(DownloadManager.STATUS_FAILED);
        assertEquals(expectedErrorCode,
                     download.getLongField(DownloadManager.COLUMN_REASON));
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
        enqueueResponse(buildEmptyResponse(status)
                .setHeader("Location", mServer.getUrl(REDIRECTED_PATH).toString()));
        enqueueInterruptedDownloadResponses(5);

        Download download = enqueueRequest(getRequest());
        runService();
        assertEquals(REQUEST_PATH, takeRequest().getPath());
        assertEquals(REDIRECTED_PATH, takeRequest().getPath());

        mSystemFacade.incrementTimeMillis(RETRY_DELAY_MILLIS);
        download.runUntilStatus(DownloadManager.STATUS_SUCCESSFUL);
        return takeRequest();
    }
}
