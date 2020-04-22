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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.DownloadManager;
import android.app.NotificationManager;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.database.ContentObserver;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Downloads;
import android.provider.MediaStore;
import android.test.MoreAsserts;
import android.test.RenamingDelegatingContext;
import android.test.ServiceTestCase;
import android.test.mock.MockContentProvider;
import android.test.mock.MockContentResolver;
import android.util.Log;

import com.google.mockwebserver.MockResponse;
import com.google.mockwebserver.MockWebServer;
import com.google.mockwebserver.RecordedRequest;
import com.google.mockwebserver.SocketPolicy;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.UnknownHostException;

public abstract class AbstractDownloadProviderFunctionalTest extends
        ServiceTestCase<DownloadJobService> {

    protected static final String LOG_TAG = "DownloadProviderFunctionalTest";
    private static final String PROVIDER_AUTHORITY = "downloads";
    protected static final long RETRY_DELAY_MILLIS = 61 * 1000;

    protected static final String
            FILE_CONTENT = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

    private final MockitoHelper mMockitoHelper = new MockitoHelper();

    protected MockWebServer mServer;
    protected MockContentResolverWithNotify mResolver;
    protected TestContext mTestContext;
    protected FakeSystemFacade mSystemFacade;
    protected static String STRING_1K;
    static {
        StringBuilder buff = new StringBuilder();
        for (int i = 0; i < 1024; i++) {
            buff.append("a" + i % 26);
        }
        STRING_1K = buff.toString();
    }

    static class MockContentResolverWithNotify extends MockContentResolver {
        public boolean mNotifyWasCalled = false;

        public MockContentResolverWithNotify(Context context) {
            super(context);
        }

        public synchronized void resetNotified() {
            mNotifyWasCalled = false;
        }

        @Override
        public synchronized void notifyChange(
                Uri uri, ContentObserver observer) {
            mNotifyWasCalled = true;
        }
    }

    static class MockMediaProvider extends MockContentProvider {
        private static final Uri TEST_URI = Uri.parse("content://media/external/11111111");
        @Override
        public int delete(Uri uri, String selection, String[] selectionArgs) {
            return 0;
        }

        @Override
        public Uri insert(Uri uri, ContentValues values) {
            return TEST_URI;
        }

        @Override
        public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                String sortOrder) {
            return new MatrixCursor(new String[0], 0);
        }

        @Override
        public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
            return 1;
        }

        @Override
        public Bundle call(String method, String request, Bundle args) {
          return new Bundle();
        }

        @Override
        public IBinder getIContentProviderBinder() {
            return new Binder();
        }
    }

    /**
     * Context passed to the provider and the service.  Allows most methods to pass through to the
     * real Context (this is a LargeTest), with a few exceptions, including renaming file operations
     * to avoid file and DB conflicts (via RenamingDelegatingContext).
     */
    static class TestContext extends RenamingDelegatingContext {
        private static final String FILENAME_PREFIX = "test.";

        private final ContentResolver mResolver;
        private final NotificationManager mNotifManager;
        private final DownloadManager mDownloadManager;
        private final JobScheduler mJobScheduler;

        public TestContext(Context realContext) {
            super(realContext, FILENAME_PREFIX);
            mResolver = new MockContentResolverWithNotify(this);
            mNotifManager = mock(NotificationManager.class);
            mDownloadManager = mock(DownloadManager.class);
            mJobScheduler = mock(JobScheduler.class);
        }

        /**
         * Direct DownloadService to our test instance of DownloadProvider.
         */
        @Override
        public ContentResolver getContentResolver() {
            return mResolver;
        }

        /**
         * Stub some system services, allow access to others, and block the rest.
         */
        @Override
        public Object getSystemService(String name) {
            if (Context.NOTIFICATION_SERVICE.equals(name)) {
                return mNotifManager;
            } else if (Context.DOWNLOAD_SERVICE.equals(name)) {
                return mDownloadManager;
            } else if (Context.JOB_SCHEDULER_SERVICE.equals(name)) {
                return mJobScheduler;
            }

            return super.getSystemService(name);
        }
    }

    public AbstractDownloadProviderFunctionalTest(FakeSystemFacade systemFacade) {
        super(DownloadJobService.class);
        mSystemFacade = systemFacade;
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mMockitoHelper.setUp(getClass());

        // Since we're testing a system app, AppDataDirGuesser doesn't find our
        // cache dir, so set it explicitly.
        System.setProperty("dexmaker.dexcache", getContext().getCacheDir().toString());

        final Context realContext = getContext();

        mTestContext = new TestContext(realContext);
        mResolver = (MockContentResolverWithNotify) mTestContext.getContentResolver();

        final DownloadProvider provider = new DownloadProvider();
        provider.mSystemFacade = mSystemFacade;

        ProviderInfo info = new ProviderInfo();
        info.authority = "downloads";
        provider.attachInfo(mTestContext, info);

        mResolver.addProvider(PROVIDER_AUTHORITY, provider);
        mResolver.addProvider(MediaStore.AUTHORITY, new MockMediaProvider());

        setContext(mTestContext);
        setupService();
        Helpers.setSystemFacade(mSystemFacade);

        mSystemFacade.setUp();
        assertDatabaseEmpty(); // ensure we're not messing with real data
        assertDatabaseSecureAgainstBadSelection();
        mServer = new MockWebServer();
        mServer.play();
    }

    @Override
    protected void tearDown() throws Exception {
        cleanUpDownloads();
        mServer.shutdown();
        mMockitoHelper.tearDown();
        super.tearDown();
    }

    protected void startDownload(long id) {
        final JobParameters params = mock(JobParameters.class);
        when(params.getJobId()).thenReturn((int) id);
        getService().onBind(null);
        getService().onStartJob(params);
    }

    private void assertDatabaseEmpty() {
        try (Cursor cursor = mResolver.query(Downloads.Impl.ALL_DOWNLOADS_CONTENT_URI,
                null, null, null, null)) {
            assertEquals(0, cursor.getCount());
        }
    }

    private void assertDatabaseSecureAgainstBadSelection() {
        try (Cursor cursor = mResolver.query(Downloads.Impl.ALL_DOWNLOADS_CONTENT_URI, null,
                "('1'='1'))) ORDER BY lastmod DESC--", null, null)) {
            fail("Database isn't secure!");
        } catch (Exception expected) {
        }
    }

    /**
     * Remove any downloaded files and delete any lingering downloads.
     */
    void cleanUpDownloads() {
        if (mResolver == null) {
            return;
        }
        String[] columns = new String[] {Downloads.Impl._DATA};
        Cursor cursor = mResolver.query(Downloads.Impl.CONTENT_URI, columns, null, null, null);
        try {
            for (cursor.moveToFirst(); !cursor.isAfterLast(); cursor.moveToNext()) {
                String filePath = cursor.getString(0);
                if (filePath == null) continue;
                Log.d(LOG_TAG, "Deleting " + filePath);
                new File(filePath).delete();
            }
        } finally {
            cursor.close();
        }
        mResolver.delete(Downloads.Impl.CONTENT_URI, null, null);
    }

    void enqueueResponse(MockResponse resp) {
        mServer.enqueue(resp);
    }

    MockResponse buildResponse(int status, String body) {
        return new MockResponse().setResponseCode(status).setBody(body)
                .setHeader("Content-type", "text/plain")
                .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END);
    }

    MockResponse buildResponse(int status, byte[] body) {
        return new MockResponse().setResponseCode(status).setBody(body)
                .setHeader("Content-type", "text/plain")
                .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END);
    }

    MockResponse buildEmptyResponse(int status) {
        return buildResponse(status, "");
    }

    /**
     * Fetch the last request received by the MockWebServer.
     */
    protected RecordedRequest takeRequest() throws InterruptedException {
        RecordedRequest request = mServer.takeRequest();
        assertNotNull("Expected request was not made", request);
        return request;
    }

    String getServerUri(String path) throws MalformedURLException, UnknownHostException {
        return mServer.getUrl(path).toString();
    }

    protected String readStream(InputStream inputStream) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
        try {
            char[] buffer = new char[1024];
            int length = reader.read(buffer);
            assertTrue("Failed to read anything from input stream", length > -1);
            return String.valueOf(buffer, 0, length);
        } finally {
            reader.close();
        }
    }

    protected void assertStartsWith(String expectedPrefix, String actual) {
        String regex = "^" + expectedPrefix + ".*";
        MoreAsserts.assertMatchesRegex(regex, actual);
    }
}
