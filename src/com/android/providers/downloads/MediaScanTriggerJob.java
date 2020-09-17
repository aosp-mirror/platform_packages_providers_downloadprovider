/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static android.provider.BaseColumns._ID;
import static android.provider.Downloads.Impl.ALL_DOWNLOADS_CONTENT_URI;
import static android.provider.Downloads.Impl.COLUMN_DESTINATION;
import static android.provider.Downloads.Impl.COLUMN_IS_VISIBLE_IN_DOWNLOADS_UI;
import static android.provider.Downloads.Impl.COLUMN_MEDIASTORE_URI;
import static android.provider.Downloads.Impl.COLUMN_MEDIA_SCANNED;
import static android.provider.Downloads.Impl.DESTINATION_EXTERNAL;
import static android.provider.Downloads.Impl.DESTINATION_FILE_URI;
import static android.provider.Downloads.Impl.DESTINATION_NON_DOWNLOADMANAGER_DOWNLOAD;
import static android.provider.Downloads.Impl.MEDIA_SCANNED;
import static android.provider.Downloads.Impl._DATA;

import static com.android.providers.downloads.Constants.MEDIA_SCAN_TRIGGER_JOB_ID;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.ContentProviderClient;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.Downloads;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;

/**
 * Job to update MediaProvider with all the downloads and force mediascan on them.
 */
public class MediaScanTriggerJob extends JobService {
    private volatile boolean mJobStopped;

    @Override
    public boolean onStartJob(JobParameters parameters) {
        if (!Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
            // External storage is not available yet, so defer this job to a later time.
            jobFinished(parameters, true /* reschedule */);
            return false;
        }
        Helpers.getAsyncHandler().post(() -> {
            final String selection = _DATA + " IS NOT NULL"
                    + " AND (" + COLUMN_IS_VISIBLE_IN_DOWNLOADS_UI + "=1"
                    + " OR " + COLUMN_MEDIA_SCANNED + "=" + MEDIA_SCANNED + ")"
                    + " AND (" + COLUMN_DESTINATION + "=" + DESTINATION_EXTERNAL
                    + " OR " + COLUMN_DESTINATION + "=" + DESTINATION_FILE_URI
                    + " OR " + COLUMN_DESTINATION + "=" + DESTINATION_NON_DOWNLOADMANAGER_DOWNLOAD
                    + ")";
            try (ContentProviderClient cpc
                    = getContentResolver().acquireContentProviderClient(Downloads.Impl.AUTHORITY);
                 ContentProviderClient mediaProviderClient
                    = getContentResolver().acquireContentProviderClient(MediaStore.AUTHORITY)) {
                final DownloadProvider downloadProvider
                        = ((DownloadProvider) cpc.getLocalContentProvider());
                try (Cursor cursor = downloadProvider.query(ALL_DOWNLOADS_CONTENT_URI,
                        null, selection, null, null)) {

                    final DownloadInfo.Reader reader
                            = new DownloadInfo.Reader(getContentResolver(), cursor);
                    final DownloadInfo info = new DownloadInfo(MediaScanTriggerJob.this);
                    while (cursor.moveToNext()) {
                        if (mJobStopped) {
                            return;
                        }
                        reader.updateFromDatabase(info);
                        // This indicates that this entry has been handled already (perhaps when
                        // this job ran earlier and got preempted), so skip.
                        if (info.mMediaStoreUri != null) {
                            continue;
                        }
                        final ContentValues mediaValues;
                        try {
                            mediaValues = downloadProvider.convertToMediaProviderValues(info);
                        } catch (IllegalArgumentException e) {
                            Log.e(Constants.TAG,
                                    "Error getting media content values from " + info, e);
                            continue;
                        }
                        // Overriding size column value to 0 for forcing the mediascan
                        // later (to address http://b/138419471).
                        mediaValues.put(MediaStore.Files.FileColumns.SIZE, 0);
                        downloadProvider.updateMediaProvider(mediaProviderClient, mediaValues);

                        final Uri mediaStoreUri = Helpers.triggerMediaScan(mediaProviderClient,
                                new File(info.mFileName));
                        if (mediaStoreUri != null) {
                            final ContentValues downloadValues = new ContentValues();
                            downloadValues.put(COLUMN_MEDIASTORE_URI, mediaStoreUri.toString());
                            downloadProvider.update(ALL_DOWNLOADS_CONTENT_URI,
                                    downloadValues, _ID + "=" + info.mId, null);
                        }
                    }
                }
            }
            jobFinished(parameters, false /* reschedule */);
        });
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters parameters) {
        mJobStopped = true;
        return true;
    }

    public static void schedule(Context context) {
        final JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        final JobInfo job = new JobInfo.Builder(MEDIA_SCAN_TRIGGER_JOB_ID,
                new ComponentName(context, MediaScanTriggerJob.class))
                        .setRequiresCharging(true)
                        .setRequiresDeviceIdle(true)
                        .build();
        scheduler.schedule(job);
    }
}
