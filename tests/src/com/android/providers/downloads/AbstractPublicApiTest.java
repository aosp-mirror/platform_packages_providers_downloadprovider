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

import android.app.DownloadManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.Downloads;
import android.util.Log;

import java.io.FileInputStream;
import java.io.InputStream;
import java.net.MalformedURLException;

/**
 * Code common to tests that use the download manager public API.
 */
public abstract class AbstractPublicApiTest extends AbstractDownloadManagerFunctionalTest {

    class Download {
        final long mId;

        private Download(long downloadId) {
            this.mId = downloadId;
        }

        public int getStatus() {
            return (int) getLongField(DownloadManager.COLUMN_STATUS);
        }

        public int getStatusIfExists() {
            Cursor cursor = mManager.query(new DownloadManager.Query().setFilterById(mId));
            try {
                if (cursor.getCount() > 0) {
                    cursor.moveToFirst();
                    return (int) cursor.getLong(cursor.getColumnIndexOrThrow(
                            DownloadManager.COLUMN_STATUS));
                } else {
                    // the row doesn't exist
                    return -1;
                }
            } finally {
                cursor.close();
            }
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

        void runUntilStatus(int status) throws Exception {
            runService();
            assertEquals(status, getStatus());
        }

        // max time to wait before giving up on the current download operation.
        private static final int MAX_TIME_TO_WAIT_FOR_OPERATION = 5;
        // while waiting for the above time period, sleep this long to yield to the
        // download thread
        private static final int TIME_TO_SLEEP = 1000;

        int runUntilDone() throws InterruptedException {
            int sleepCounter = MAX_TIME_TO_WAIT_FOR_OPERATION * 1000 / TIME_TO_SLEEP;
            for (int i = 0; i < sleepCounter; i++) {
                int status = getStatusIfExists();
                if (status == -1 || Downloads.Impl.isStatusCompleted(getStatus())) {
                    // row doesn't exist or the download is done
                    return status;
                }
                // download not done yet. sleep a while and try again
                Thread.sleep(TIME_TO_SLEEP);
            }
            return 0; // failed
        }

        // waits until progress_so_far is >= (progress)%
        boolean runUntilProgress(int progress) throws InterruptedException {
            int sleepCounter = MAX_TIME_TO_WAIT_FOR_OPERATION * 1000 / TIME_TO_SLEEP;
            int numBytesReceivedSoFar = 0;
            int totalBytes = 0;
            for (int i = 0; i < sleepCounter; i++) {
                Cursor cursor = mManager.query(new DownloadManager.Query().setFilterById(mId));
                try {
                    assertEquals(1, cursor.getCount());
                    cursor.moveToFirst();
                    numBytesReceivedSoFar = cursor.getInt(
                            cursor.getColumnIndexOrThrow(
                                    DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                    totalBytes = cursor.getInt(
                            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                } finally {
                    cursor.close();
                }
                Log.i(LOG_TAG, "in runUntilProgress, numBytesReceivedSoFar: " +
                        numBytesReceivedSoFar + ", totalBytes: " + totalBytes);
                if (totalBytes == 0) {
                    fail("total_bytes should not be zero");
                    return false;
                } else {
                    if (numBytesReceivedSoFar * 100 / totalBytes >= progress) {
                        // progress_so_far is >= progress%. we are done
                        return true;
                    }
                }
                // download not done yet. sleep a while and try again
                Thread.sleep(TIME_TO_SLEEP);
            }
            Log.i(LOG_TAG, "FAILED in runUntilProgress, numBytesReceivedSoFar: " +
                    numBytesReceivedSoFar + ", totalBytes: " + totalBytes);
            return false; // failed
        }
    }

    protected static final String PACKAGE_NAME = "my.package.name";
    protected static final String REQUEST_PATH = "/path";

    protected DownloadManager mManager;

    public AbstractPublicApiTest(FakeSystemFacade systemFacade) {
        super(systemFacade);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mManager = new DownloadManager(mResolver, PACKAGE_NAME);
    }

    protected DownloadManager.Request getRequest() throws MalformedURLException {
        return getRequest(getServerUri(REQUEST_PATH));
    }

    protected DownloadManager.Request getRequest(String path) {
        return new DownloadManager.Request(Uri.parse(path));
    }

    protected Download enqueueRequest(DownloadManager.Request request) {
        return new Download(mManager.enqueue(request));
    }
}
