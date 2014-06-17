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

import static com.android.providers.downloads.Constants.TAG;
import static com.android.providers.downloads.StorageUtils.listFilesRecursive;

import android.app.DownloadManager;
import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.database.Cursor;
import android.os.Environment;
import android.provider.Downloads;
import android.system.ErrnoException;
import android.text.TextUtils;
import android.util.Slog;

import com.android.providers.downloads.StorageUtils.ConcreteFile;
import com.google.android.collect.Lists;
import com.google.android.collect.Sets;

import libcore.io.IoUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;

/**
 * Idle-time service for {@link DownloadManager}. Reconciles database
 * metadata and files on disk, which can become inconsistent when files are
 * deleted directly on disk.
 */
public class DownloadIdleService extends JobService {

    private class IdleRunnable implements Runnable {
        private JobParameters mParams;

        public IdleRunnable(JobParameters params) {
            mParams = params;
        }

        @Override
        public void run() {
            cleanOrphans();
            jobFinished(mParams, false);
        }
    };

    @Override
    public boolean onStartJob(JobParameters params) {
        new Thread(new IdleRunnable(params)).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // We're okay being killed at any point, so we don't worry about
        // checkpointing before tearing down.
        return false;
    }

    private interface DownloadQuery {
        final String[] PROJECTION = new String[] {
                Downloads.Impl._ID,
                Downloads.Impl._DATA };

        final int _ID = 0;
        final int _DATA = 1;
    }

    /**
     * Clean up orphan downloads, both in database and on disk.
     */
    public void cleanOrphans() {
        final ContentResolver resolver = getContentResolver();

        // Collect known files from database
        final HashSet<ConcreteFile> fromDb = Sets.newHashSet();
        final Cursor cursor = resolver.query(Downloads.Impl.ALL_DOWNLOADS_CONTENT_URI,
                DownloadQuery.PROJECTION, null, null, null);
        try {
            while (cursor.moveToNext()) {
                final String path = cursor.getString(DownloadQuery._DATA);
                if (TextUtils.isEmpty(path)) continue;

                final File file = new File(path);
                try {
                    fromDb.add(new ConcreteFile(file));
                } catch (ErrnoException e) {
                    // File probably no longer exists
                    final String state = Environment.getExternalStorageState(file);
                    if (Environment.MEDIA_UNKNOWN.equals(state)
                            || Environment.MEDIA_MOUNTED.equals(state)) {
                        // File appears to live on internal storage, or a
                        // currently mounted device, so remove it from database.
                        // This logic preserves files on external storage while
                        // media is removed.
                        final long id = cursor.getLong(DownloadQuery._ID);
                        Slog.d(TAG, "Missing " + file + ", deleting " + id);
                        resolver.delete(ContentUris.withAppendedId(
                                Downloads.Impl.ALL_DOWNLOADS_CONTENT_URI, id), null, null);
                    }
                }
            }
        } finally {
            IoUtils.closeQuietly(cursor);
        }

        // Collect known files from disk
        final int uid = android.os.Process.myUid();
        final ArrayList<ConcreteFile> fromDisk = Lists.newArrayList();
        fromDisk.addAll(listFilesRecursive(getCacheDir(), null, uid));
        fromDisk.addAll(listFilesRecursive(getFilesDir(), null, uid));
        fromDisk.addAll(listFilesRecursive(Environment.getDownloadCacheDirectory(), null, uid));

        Slog.d(TAG, "Found " + fromDb.size() + " files in database");
        Slog.d(TAG, "Found " + fromDisk.size() + " files on disk");

        // Delete files no longer referenced by database
        for (ConcreteFile file : fromDisk) {
            if (!fromDb.contains(file)) {
                Slog.d(TAG, "Missing db entry, deleting " + file.file);
                file.file.delete();
            }
        }
    }
}
