/*
 * Copyright (C) 2014 The Android Open Source Project
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

import static android.app.DownloadManager.COLUMN_REASON;
import static android.app.DownloadManager.ERROR_INSUFFICIENT_SPACE;
import static android.app.DownloadManager.STATUS_FAILED;
import static android.app.DownloadManager.STATUS_SUCCESSFUL;
import static android.provider.Downloads.Impl.DESTINATION_CACHE_PARTITION;
import static android.provider.Downloads.Impl.DESTINATION_SYSTEMCACHE_PARTITION;

import android.app.DownloadManager;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.os.StatFs;
import android.provider.Downloads.Impl;
import android.system.ErrnoException;
import android.system.Os;
import android.system.StructStatVfs;
import android.test.MoreAsserts;
import android.util.Log;

import com.android.providers.downloads.StorageUtils.ObserverLatch;
import com.google.mockwebserver.MockResponse;
import com.google.mockwebserver.SocketPolicy;

import libcore.io.ForwardingOs;
import libcore.io.IoUtils;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class StorageTest extends AbstractPublicApiTest {
    private static final String TAG = "StorageTest";

    private static final int DOWNLOAD_SIZE = 512 * 1024;
    private static final byte[] DOWNLOAD_BODY;

    static {
        DOWNLOAD_BODY = new byte[DOWNLOAD_SIZE];
        for (int i = 0; i < DOWNLOAD_SIZE; i++) {
            DOWNLOAD_BODY[i] = (byte) (i % 32);
        }
    }

    private libcore.io.Os mOriginal;
    private long mStealBytes;

    public StorageTest() {
        super(new FakeSystemFacade());
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        StorageUtils.sForceFullEviction = true;
        mStealBytes = 0;

        mOriginal = libcore.io.Libcore.os;
        libcore.io.Libcore.os = new ForwardingOs(mOriginal) {
            @Override
            public StructStatVfs statvfs(String path) throws ErrnoException {
                return stealBytes(os.statvfs(path));
            }

            @Override
            public StructStatVfs fstatvfs(FileDescriptor fd) throws ErrnoException {
                return stealBytes(os.fstatvfs(fd));
            }

            private StructStatVfs stealBytes(StructStatVfs s) {
                final long stealBlocks = (mStealBytes + (s.f_bsize - 1)) / s.f_bsize;
                final long f_bavail = s.f_bavail - stealBlocks;
                return new StructStatVfs(s.f_bsize, s.f_frsize, s.f_blocks, s.f_bfree, f_bavail,
                        s.f_files, s.f_ffree, s.f_favail, s.f_fsid, s.f_flag, s.f_namemax);
            }
        };
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();

        StorageUtils.sForceFullEviction = false;
        mStealBytes = 0;

        if (mOriginal != null) {
            libcore.io.Libcore.os = mOriginal;
        }
    }

    private enum CacheStatus { CLEAN, DIRTY }
    private enum BodyType { COMPLETE, CHUNKED }

    public void testDataDirtyComplete() throws Exception {
        prepareAndRunDownload(DESTINATION_CACHE_PARTITION,
                CacheStatus.DIRTY, BodyType.COMPLETE,
                STATUS_SUCCESSFUL, -1);
    }

    public void testDataDirtyChunked() throws Exception {
        prepareAndRunDownload(DESTINATION_CACHE_PARTITION,
                CacheStatus.DIRTY, BodyType.CHUNKED,
                STATUS_SUCCESSFUL, -1);
    }

    public void testDataCleanComplete() throws Exception {
        prepareAndRunDownload(DESTINATION_CACHE_PARTITION,
                CacheStatus.CLEAN, BodyType.COMPLETE,
                STATUS_FAILED, ERROR_INSUFFICIENT_SPACE);
    }

    public void testDataCleanChunked() throws Exception {
        prepareAndRunDownload(DESTINATION_CACHE_PARTITION,
                CacheStatus.CLEAN, BodyType.CHUNKED,
                STATUS_FAILED, ERROR_INSUFFICIENT_SPACE);
    }

    public void testCacheDirtyComplete() throws Exception {
        prepareAndRunDownload(DESTINATION_SYSTEMCACHE_PARTITION,
                CacheStatus.DIRTY, BodyType.COMPLETE,
                STATUS_SUCCESSFUL, -1);
    }

    public void testCacheDirtyChunked() throws Exception {
        prepareAndRunDownload(DESTINATION_SYSTEMCACHE_PARTITION,
                CacheStatus.DIRTY, BodyType.CHUNKED,
                STATUS_SUCCESSFUL, -1);
    }

    public void testCacheCleanComplete() throws Exception {
        prepareAndRunDownload(DESTINATION_SYSTEMCACHE_PARTITION,
                CacheStatus.CLEAN, BodyType.COMPLETE,
                STATUS_FAILED, ERROR_INSUFFICIENT_SPACE);
    }

    public void testCacheCleanChunked() throws Exception {
        prepareAndRunDownload(DESTINATION_SYSTEMCACHE_PARTITION,
                CacheStatus.CLEAN, BodyType.CHUNKED,
                STATUS_FAILED, ERROR_INSUFFICIENT_SPACE);
    }

    private void prepareAndRunDownload(
            int dest, CacheStatus cache, BodyType body, int expectedStatus, int expectedReason)
            throws Exception {

        // Ensure that we've purged everything possible for destination
        final File dirtyDir;
        if (dest == DESTINATION_CACHE_PARTITION) {
            final PackageManager pm = getContext().getPackageManager();
            final ObserverLatch observer = new ObserverLatch();
            pm.freeStorageAndNotify(Long.MAX_VALUE, observer);

            try {
                if (!observer.latch.await(30, TimeUnit.SECONDS)) {
                    throw new IOException("Timeout while freeing disk space");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            dirtyDir = getContext().getCacheDir();

        } else if (dest == DESTINATION_SYSTEMCACHE_PARTITION) {
            IoUtils.deleteContents(Environment.getDownloadCacheDirectory());
            dirtyDir = Environment.getDownloadCacheDirectory();

        } else {
            throw new IllegalArgumentException("Unknown destination");
        }

        // Allocate a cache file, if requested, making it large enough and old
        // enough to clear.
        final File dirtyFile;
        if (cache == CacheStatus.DIRTY) {
            dirtyFile = new File(dirtyDir, "cache_file.bin");
            assertTrue(dirtyFile.createNewFile());
            final FileOutputStream os = new FileOutputStream(dirtyFile);
            final int dirtySize = (DOWNLOAD_SIZE * 3) / 2;
            Os.posix_fallocate(os.getFD(), 0, dirtySize);
            IoUtils.closeQuietly(os);

            dirtyFile.setLastModified(
                    System.currentTimeMillis() - (StorageUtils.MIN_DELETE_AGE * 2));
        } else {
            dirtyFile = null;
        }

        // At this point, hide all other disk space to make the download fail;
        // if we have a dirty cache file it can be cleared to let us proceed.
        final long targetFree = StorageUtils.RESERVED_BYTES + (DOWNLOAD_SIZE / 2);

        final StatFs stat = new StatFs(dirtyDir.getAbsolutePath());
        Log.d(TAG, "Available bytes (before steal): " + stat.getAvailableBytes());
        mStealBytes = stat.getAvailableBytes() - targetFree;

        stat.restat(dirtyDir.getAbsolutePath());
        Log.d(TAG, "Available bytes (after steal): " + stat.getAvailableBytes());

        final MockResponse resp = new MockResponse().setResponseCode(200)
                .setHeader("Content-type", "text/plain")
                .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END);
        if (body == BodyType.CHUNKED) {
            resp.setChunkedBody(DOWNLOAD_BODY, 1021);
        } else {
            resp.setBody(DOWNLOAD_BODY);
        }
        enqueueResponse(resp);

        final DownloadManager.Request req = getRequest();
        if (dest == Impl.DESTINATION_SYSTEMCACHE_PARTITION) {
            req.setDestinationToSystemCache();
        }
        final Download download = enqueueRequest(req);
        download.runUntilStatus(expectedStatus);

        if (expectedStatus == STATUS_SUCCESSFUL) {
            MoreAsserts.assertEquals(DOWNLOAD_BODY, download.getRawContents());
        }

        if (expectedReason != -1) {
            assertEquals(expectedReason, download.getLongField(COLUMN_REASON));
        }

        if (dirtyFile != null) {
            assertFalse(dirtyFile.exists());
        }
    }
}
