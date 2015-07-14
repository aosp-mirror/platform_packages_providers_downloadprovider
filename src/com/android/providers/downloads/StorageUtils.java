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

import static android.net.TrafficStats.MB_IN_BYTES;
import static android.provider.Downloads.Impl.STATUS_INSUFFICIENT_SPACE_ERROR;
import static android.text.format.DateUtils.DAY_IN_MILLIS;
import static com.android.providers.downloads.Constants.TAG;

import android.app.DownloadManager;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.IPackageDataObserver;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Environment;
import android.provider.Downloads;
import android.system.ErrnoException;
import android.system.Os;
import android.system.StructStat;
import android.system.StructStatVfs;
import android.text.TextUtils;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.google.android.collect.Lists;
import com.google.android.collect.Sets;

import libcore.io.IoUtils;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Utility methods for managing storage space related to
 * {@link DownloadManager}.
 */
public class StorageUtils {

    /**
     * Minimum age for a file to be considered for deletion.
     */
    static final long MIN_DELETE_AGE = DAY_IN_MILLIS;

    /**
     * Reserved disk space to avoid filling disk.
     */
    static final long RESERVED_BYTES = 32 * MB_IN_BYTES;

    @VisibleForTesting
    static boolean sForceFullEviction = false;

    /**
     * Ensure that requested free space exists on the partition backing the
     * given {@link FileDescriptor}. If not enough space is available, it tries
     * freeing up space as follows:
     * <ul>
     * <li>If backed by the data partition (including emulated external
     * storage), then ask {@link PackageManager} to free space from cache
     * directories.
     * <li>If backed by the cache partition, then try deleting older downloads
     * to free space.
     * </ul>
     */
    public static void ensureAvailableSpace(Context context, FileDescriptor fd, long bytes)
            throws IOException, StopRequestException {

        long availBytes = getAvailableBytes(fd);
        if (availBytes >= bytes) {
            // Underlying partition has enough space; go ahead
            return;
        }

        // Not enough space, let's try freeing some up. Start by tracking down
        // the backing partition.
        final long dev;
        try {
            dev = Os.fstat(fd).st_dev;
        } catch (ErrnoException e) {
            throw e.rethrowAsIOException();
        }

        // TODO: teach about evicting caches on adopted secondary storage devices
        final long dataDev = getDeviceId(Environment.getDataDirectory());
        final long cacheDev = getDeviceId(Environment.getDownloadCacheDirectory());
        final long externalDev = getDeviceId(Environment.getExternalStorageDirectory());

        if (dev == dataDev || (dev == externalDev && Environment.isExternalStorageEmulated())) {
            // File lives on internal storage; ask PackageManager to try freeing
            // up space from cache directories.
            final PackageManager pm = context.getPackageManager();
            final ObserverLatch observer = new ObserverLatch();
            pm.freeStorageAndNotify(sForceFullEviction ? Long.MAX_VALUE : bytes, observer);

            try {
                if (!observer.latch.await(30, TimeUnit.SECONDS)) {
                    throw new IOException("Timeout while freeing disk space");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

        } else if (dev == cacheDev) {
            // Try removing old files on cache partition
            freeCacheStorage(bytes);
        }

        // Did we free enough space?
        availBytes = getAvailableBytes(fd);
        if (availBytes < bytes) {
            throw new StopRequestException(STATUS_INSUFFICIENT_SPACE_ERROR,
                    "Not enough free space; " + bytes + " requested, " + availBytes + " available");
        }
    }

    /**
     * Free requested space on cache partition, deleting oldest files first.
     * We're only focused on freeing up disk space, and rely on the next orphan
     * pass to clean up database entries.
     */
    private static void freeCacheStorage(long bytes) {
        // Only consider finished downloads
        final List<ConcreteFile> files = listFilesRecursive(
                Environment.getDownloadCacheDirectory(), Constants.DIRECTORY_CACHE_RUNNING,
                android.os.Process.myUid());

        Slog.d(TAG, "Found " + files.size() + " downloads on cache");

        Collections.sort(files, new Comparator<ConcreteFile>() {
            @Override
            public int compare(ConcreteFile lhs, ConcreteFile rhs) {
                return (int) (lhs.file.lastModified() - rhs.file.lastModified());
            }
        });

        final long now = System.currentTimeMillis();
        for (ConcreteFile file : files) {
            if (bytes <= 0) break;

            if (now - file.file.lastModified() < MIN_DELETE_AGE) {
                Slog.d(TAG, "Skipping recently modified " + file.file);
            } else {
                final long len = file.file.length();
                Slog.d(TAG, "Deleting " + file.file + " to reclaim " + len);
                bytes -= len;
                file.file.delete();
            }
        }
    }

    /**
     * Return number of available bytes on the filesystem backing the given
     * {@link FileDescriptor}, minus any {@link #RESERVED_BYTES} buffer.
     */
    private static long getAvailableBytes(FileDescriptor fd) throws IOException {
        try {
            final StructStatVfs stat = Os.fstatvfs(fd);
            return (stat.f_bavail * stat.f_bsize) - RESERVED_BYTES;
        } catch (ErrnoException e) {
            throw e.rethrowAsIOException();
        }
    }

    private static long getDeviceId(File file) {
        try {
            return Os.stat(file.getAbsolutePath()).st_dev;
        } catch (ErrnoException e) {
            // Safe since dev_t is uint
            return -1;
        }
    }

    /**
     * Return list of all normal files under the given directory, traversing
     * directories recursively.
     *
     * @param exclude ignore dirs with this name, or {@code null} to ignore.
     * @param uid only return files owned by this UID, or {@code -1} to ignore.
     */
    static List<ConcreteFile> listFilesRecursive(File startDir, String exclude, int uid) {
        final ArrayList<ConcreteFile> files = Lists.newArrayList();
        final LinkedList<File> dirs = new LinkedList<File>();
        dirs.add(startDir);
        while (!dirs.isEmpty()) {
            final File dir = dirs.removeFirst();
            if (Objects.equals(dir.getName(), exclude)) continue;

            final File[] children = dir.listFiles();
            if (children == null) continue;

            for (File child : children) {
                if (child.isDirectory()) {
                    dirs.add(child);
                } else if (child.isFile()) {
                    try {
                        final ConcreteFile file = new ConcreteFile(child);
                        if (uid == -1 || file.stat.st_uid == uid) {
                            files.add(file);
                        }
                    } catch (ErrnoException ignored) {
                    }
                }
            }
        }
        return files;
    }

    /**
     * Concrete file on disk that has a backing device and inode. Faster than
     * {@code realpath()} when looking for identical files.
     */
    static class ConcreteFile {
        public final File file;
        public final StructStat stat;

        public ConcreteFile(File file) throws ErrnoException {
            this.file = file;
            this.stat = Os.lstat(file.getAbsolutePath());
        }

        @Override
        public int hashCode() {
            int result = 1;
            result = 31 * result + (int) (stat.st_dev ^ (stat.st_dev >>> 32));
            result = 31 * result + (int) (stat.st_ino ^ (stat.st_ino >>> 32));
            return result;
        }

        @Override
        public boolean equals(Object o) {
            if (o instanceof ConcreteFile) {
                final ConcreteFile f = (ConcreteFile) o;
                return (f.stat.st_dev == stat.st_dev) && (f.stat.st_ino == stat.st_ino);
            }
            return false;
        }
    }

    static class ObserverLatch extends IPackageDataObserver.Stub {
        public final CountDownLatch latch = new CountDownLatch(1);

        @Override
        public void onRemoveCompleted(String packageName, boolean succeeded) {
            latch.countDown();
        }
    }
}
