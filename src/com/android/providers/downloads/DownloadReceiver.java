/*
 * Copyright (C) 2008 The Android Open Source Project
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

import static android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED;
import static android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_ONLY_COMPLETION;
import static com.android.providers.downloads.Constants.TAG;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.Downloads;
import android.text.TextUtils;
import android.util.Log;
import android.util.Slog;
import android.widget.Toast;

import com.google.common.annotations.VisibleForTesting;

/**
 * Receives system broadcasts (boot, network connectivity)
 */
public class DownloadReceiver extends BroadcastReceiver {
    private static Handler sAsyncHandler;

    static {
        final HandlerThread thread = new HandlerThread("DownloadReceiver");
        thread.start();
        sAsyncHandler = new Handler(thread.getLooper());
    }

    @VisibleForTesting
    SystemFacade mSystemFacade = null;

    @Override
    public void onReceive(final Context context, final Intent intent) {
        if (mSystemFacade == null) {
            mSystemFacade = new RealSystemFacade(context);
        }

        final String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            startService(context);

        } else if (Intent.ACTION_MEDIA_MOUNTED.equals(action)) {
            startService(context);

        } else if (ConnectivityManager.CONNECTIVITY_ACTION.equals(action)) {
            final ConnectivityManager connManager = (ConnectivityManager) context
                    .getSystemService(Context.CONNECTIVITY_SERVICE);
            final NetworkInfo info = connManager.getActiveNetworkInfo();
            if (info != null && info.isConnected()) {
                startService(context);
            }

        } else if (Intent.ACTION_UID_REMOVED.equals(action)) {
            final PendingResult result = goAsync();
            sAsyncHandler.post(new Runnable() {
                @Override
                public void run() {
                    handleUidRemoved(context, intent);
                    result.finish();
                }
            });

        } else if (Constants.ACTION_RETRY.equals(action)) {
            startService(context);

        } else if (Constants.ACTION_OPEN.equals(action)
                || Constants.ACTION_LIST.equals(action)
                || Constants.ACTION_HIDE.equals(action)) {

            final PendingResult result = goAsync();
            if (result == null) {
                // TODO: remove this once test is refactored
                handleNotificationBroadcast(context, intent);
            } else {
                sAsyncHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        handleNotificationBroadcast(context, intent);
                        result.finish();
                    }
                });
            }
        }
    }

    private void handleUidRemoved(Context context, Intent intent) {
        final ContentResolver resolver = context.getContentResolver();

        final int uid = intent.getIntExtra(Intent.EXTRA_UID, -1);
        final int count = resolver.delete(
                Downloads.Impl.ALL_DOWNLOADS_CONTENT_URI, Constants.UID + "=" + uid, null);

        if (count > 0) {
            Slog.d(TAG, "Deleted " + count + " downloads owned by UID " + uid);
        }
    }

    /**
     * Handle any broadcast related to a system notification.
     */
    private void handleNotificationBroadcast(Context context, Intent intent) {
        final String action = intent.getAction();
        if (Constants.ACTION_LIST.equals(action)) {
            final long[] ids = intent.getLongArrayExtra(
                    DownloadManager.EXTRA_NOTIFICATION_CLICK_DOWNLOAD_IDS);
            sendNotificationClickedIntent(context, ids);

        } else if (Constants.ACTION_OPEN.equals(action)) {
            final long id = ContentUris.parseId(intent.getData());
            openDownload(context, id);
            hideNotification(context, id);

        } else if (Constants.ACTION_HIDE.equals(action)) {
            final long id = ContentUris.parseId(intent.getData());
            hideNotification(context, id);
        }
    }

    /**
     * Mark the given {@link DownloadManager#COLUMN_ID} as being acknowledged by
     * user so it's not renewed later.
     */
    private void hideNotification(Context context, long id) {
        final int status;
        final int visibility;

        final Uri uri = ContentUris.withAppendedId(Downloads.Impl.ALL_DOWNLOADS_CONTENT_URI, id);
        final Cursor cursor = context.getContentResolver().query(uri, null, null, null, null);
        try {
            if (cursor.moveToFirst()) {
                status = getInt(cursor, Downloads.Impl.COLUMN_STATUS);
                visibility = getInt(cursor, Downloads.Impl.COLUMN_VISIBILITY);
            } else {
                Log.w(TAG, "Missing details for download " + id);
                return;
            }
        } finally {
            cursor.close();
        }

        if (Downloads.Impl.isStatusCompleted(status) &&
                (visibility == VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                || visibility == VISIBILITY_VISIBLE_NOTIFY_ONLY_COMPLETION)) {
            final ContentValues values = new ContentValues();
            values.put(Downloads.Impl.COLUMN_VISIBILITY,
                    Downloads.Impl.VISIBILITY_VISIBLE);
            context.getContentResolver().update(uri, values, null, null);
        }
    }

    /**
     * Start activity to display the file represented by the given
     * {@link DownloadManager#COLUMN_ID}.
     */
    private void openDownload(Context context, long id) {
        if (!OpenHelper.startViewIntent(context, id, Intent.FLAG_ACTIVITY_NEW_TASK)) {
            Toast.makeText(context, R.string.download_no_application_title, Toast.LENGTH_SHORT)
                    .show();
        }
    }

    /**
     * Notify the owner of a running download that its notification was clicked.
     */
    private void sendNotificationClickedIntent(Context context, long[] ids) {
        final String packageName;
        final String clazz;
        final boolean isPublicApi;

        final Uri uri = ContentUris.withAppendedId(
                Downloads.Impl.ALL_DOWNLOADS_CONTENT_URI, ids[0]);
        final Cursor cursor = context.getContentResolver().query(uri, null, null, null, null);
        try {
            if (cursor.moveToFirst()) {
                packageName = getString(cursor, Downloads.Impl.COLUMN_NOTIFICATION_PACKAGE);
                clazz = getString(cursor, Downloads.Impl.COLUMN_NOTIFICATION_CLASS);
                isPublicApi = getInt(cursor, Downloads.Impl.COLUMN_IS_PUBLIC_API) != 0;
            } else {
                Log.w(TAG, "Missing details for download " + ids[0]);
                return;
            }
        } finally {
            cursor.close();
        }

        if (TextUtils.isEmpty(packageName)) {
            Log.w(TAG, "Missing package; skipping broadcast");
            return;
        }

        Intent appIntent = null;
        if (isPublicApi) {
            appIntent = new Intent(DownloadManager.ACTION_NOTIFICATION_CLICKED);
            appIntent.setPackage(packageName);
            appIntent.putExtra(DownloadManager.EXTRA_NOTIFICATION_CLICK_DOWNLOAD_IDS, ids);

        } else { // legacy behavior
            if (TextUtils.isEmpty(clazz)) {
                Log.w(TAG, "Missing class; skipping broadcast");
                return;
            }

            appIntent = new Intent(DownloadManager.ACTION_NOTIFICATION_CLICKED);
            appIntent.setClassName(packageName, clazz);
            appIntent.putExtra(DownloadManager.EXTRA_NOTIFICATION_CLICK_DOWNLOAD_IDS, ids);

            if (ids.length == 1) {
                appIntent.setData(uri);
            } else {
                appIntent.setData(Downloads.Impl.CONTENT_URI);
            }
        }

        mSystemFacade.sendBroadcast(appIntent);
    }

    private static String getString(Cursor cursor, String col) {
        return cursor.getString(cursor.getColumnIndexOrThrow(col));
    }

    private static int getInt(Cursor cursor, String col) {
        return cursor.getInt(cursor.getColumnIndexOrThrow(col));
    }

    private void startService(Context context) {
        context.startService(new Intent(context, DownloadService.class));
    }
}
