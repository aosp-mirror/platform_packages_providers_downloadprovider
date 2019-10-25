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
import android.os.RemoteException;
import android.provider.Downloads;
import android.provider.MediaStore;

import java.io.File;

/**
 * Clean-up job to force mediascan on downloads which should have been but didn't get mediascanned.
 */
public class MediaScanTriggerJob extends JobService {
    private volatile boolean mJobStopped;

    @Override
    public boolean onStartJob(JobParameters parameters) {
        Helpers.getAsyncHandler().post(() -> {
            final String selection = _DATA + " IS NOT NULL"
                    + " AND (" + COLUMN_IS_VISIBLE_IN_DOWNLOADS_UI + "=1"
                    + " OR " + COLUMN_MEDIA_SCANNED + "=" + MEDIA_SCANNED + ")"
                    + " AND (" + COLUMN_DESTINATION + "=" + DESTINATION_EXTERNAL
                    + " OR " + COLUMN_DESTINATION + "=" + DESTINATION_FILE_URI
                    + " OR " + COLUMN_DESTINATION + "=" + DESTINATION_NON_DOWNLOADMANAGER_DOWNLOAD
                    + ")";
            try (ContentProviderClient downloadProviderClient
                    = getContentResolver().acquireContentProviderClient(Downloads.Impl.AUTHORITY);
                 ContentProviderClient mediaProviderClient
                    = getContentResolver().acquireContentProviderClient(MediaStore.AUTHORITY)) {
                try (Cursor cursor = downloadProviderClient.query(ALL_DOWNLOADS_CONTENT_URI,
                        new String[] {_ID, _DATA, COLUMN_MEDIASTORE_URI},
                        selection, null, null)) {
                    while (cursor.moveToNext()) {
                        if (mJobStopped) {
                            return;
                        }
                        // This indicates that this entry has been handled already (perhaps when
                        // this job ran earlier and got preempted), so skip.
                        if (cursor.getString(2) != null) {
                            continue;
                        }
                        final long id = cursor.getLong(0);
                        final String filePath = cursor.getString(1);
                        final ContentValues mediaValues = new ContentValues();
                        mediaValues.put(MediaStore.Files.FileColumns.SIZE, 0);
                        mediaProviderClient.update(MediaStore.Files.getContentUriForPath(filePath),
                                mediaValues,
                                MediaStore.Files.FileColumns.DATA + "=?",
                                new String[] { filePath });

                        final Uri mediaStoreUri = Helpers.triggerMediaScan(mediaProviderClient,
                                new File(filePath));
                        if (mediaStoreUri != null) {
                            final ContentValues downloadValues = new ContentValues();
                            downloadValues.put(COLUMN_MEDIASTORE_URI, mediaStoreUri.toString());
                            downloadProviderClient.update(ALL_DOWNLOADS_CONTENT_URI,
                                    downloadValues, _ID + "=" + id, null);
                        }
                    }
                } catch (RemoteException e) {
                    // Should not happen
                }
            }
            jobFinished(parameters, false);
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
