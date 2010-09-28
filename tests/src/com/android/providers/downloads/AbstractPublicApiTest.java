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
