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

import com.google.android.collect.Lists;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.ServiceConnection;
import android.database.ContentObserver;
import android.database.Cursor;
import android.database.CharArrayBuffer;
import android.drm.mobile1.DrmRawContent;
import android.media.IMediaScannerService;
import android.net.Uri;
import android.os.RemoteException;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Process;
import android.provider.Downloads;
import android.util.Config;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;


/**
 * Performs the background downloads requested by applications that use the Downloads provider.
 */
public class DownloadService extends Service {

    /* ------------ Constants ------------ */

    /* ------------ Members ------------ */

    /** Observer to get notified when the content observer's data changes */
    private DownloadManagerContentObserver mObserver;
    
    /** Class to handle Notification Manager updates */
    private DownloadNotification mNotifier;

    /**
     * The Service's view of the list of downloads. This is kept independently
     * from the content provider, and the Service only initiates downloads
     * based on this data, so that it can deal with situation where the data
     * in the content provider changes or disappears.
     */
    private ArrayList<DownloadInfo> mDownloads;

    /**
     * The thread that updates the internal download list from the content
     * provider.
     */
    private UpdateThread updateThread;

    /**
     * Whether the internal download list should be updated from the content
     * provider.
     */
    private boolean pendingUpdate;

    /**
     * The ServiceConnection object that tells us when we're connected to and disconnected from
     * the Media Scanner
     */
    private MediaScannerConnection mMediaScannerConnection;

    private boolean mMediaScannerConnecting;

    /**
     * The IPC interface to the Media Scanner
     */
    private IMediaScannerService mMediaScannerService;

    /**
     * Array used when extracting strings from content provider
     */
    private CharArrayBuffer oldChars;

    /**
     * Array used when extracting strings from content provider
     */
    private CharArrayBuffer newChars;

    /* ------------ Inner Classes ------------ */

    /**
     * Receives notifications when the data in the content provider changes
     */
    private class DownloadManagerContentObserver extends ContentObserver {

        public DownloadManagerContentObserver() {
            super(new Handler());
        }

        /**
         * Receives notification when the data in the observed content
         * provider changes.
         */
        public void onChange(final boolean selfChange) {
            if (Constants.LOGVV) {
                Log.v(Constants.TAG, "Service ContentObserver received notification");
            }
            updateFromProvider();
        }

    }

    /**
     * Gets called back when the connection to the media
     * scanner is established or lost.
     */
    public class MediaScannerConnection implements ServiceConnection {
        public void onServiceConnected(ComponentName className, IBinder service) {
            if (Constants.LOGVV) {
                Log.v(Constants.TAG, "Connected to Media Scanner");
            }
            mMediaScannerConnecting = false;
            synchronized (DownloadService.this) {
                mMediaScannerService = IMediaScannerService.Stub.asInterface(service);
                if (mMediaScannerService != null) {
                    updateFromProvider();
                }
            }
        }

        public void disconnectMediaScanner() {
            synchronized (DownloadService.this) {
                if (mMediaScannerService != null) {
                    mMediaScannerService = null;
                    if (Constants.LOGVV) {
                        Log.v(Constants.TAG, "Disconnecting from Media Scanner");
                    }
                    try {
                        unbindService(this);
                    } catch (IllegalArgumentException ex) {
                        if (Constants.LOGV) {
                            Log.v(Constants.TAG, "unbindService threw up: " + ex);
                        }
                    }
                }
            }
        }

        public void onServiceDisconnected(ComponentName className) {
            if (Constants.LOGVV) {
                Log.v(Constants.TAG, "Disconnected from Media Scanner");
            }
            synchronized (DownloadService.this) {
                mMediaScannerService = null;
            }
        }
    }

    /* ------------ Methods ------------ */

    /**
     * Returns an IBinder instance when someone wants to connect to this
     * service. Binding to this service is not allowed.
     *
     * @throws UnsupportedOperationException
     */
    public IBinder onBind(Intent i) {
        throw new UnsupportedOperationException("Cannot bind to Download Manager Service");
    }

    /**
     * Initializes the service when it is first created
     */
    public void onCreate() {
        super.onCreate();
        if (Constants.LOGVV) {
            Log.v(Constants.TAG, "Service onCreate");
        }

        mDownloads = Lists.newArrayList();

        mObserver = new DownloadManagerContentObserver();
        getContentResolver().registerContentObserver(Downloads.CONTENT_URI,
                true, mObserver);

        mMediaScannerService = null;
        mMediaScannerConnecting = false;
        mMediaScannerConnection = new MediaScannerConnection();
        
        mNotifier = new DownloadNotification(this);
        mNotifier.mNotificationMgr.cancelAll();
        mNotifier.updateNotification();

        trimDatabase();
        removeSpuriousFiles();
        updateFromProvider();
    }

    /**
     * Responds to a call to startService
     */
    public void onStart(Intent intent, int startId) {
        super.onStart(intent, startId);
        if (Constants.LOGVV) {
            Log.v(Constants.TAG, "Service onStart");
        }

        updateFromProvider();
    }

    /**
     * Cleans up when the service is destroyed
     */
    public void onDestroy() {
        getContentResolver().unregisterContentObserver(mObserver);
        if (Constants.LOGVV) {
            Log.v(Constants.TAG, "Service onDestroy");
        }
        super.onDestroy();
    }

    /**
     * Parses data from the content provider into private array
     */
    private void updateFromProvider() {
        synchronized (this) {
            pendingUpdate = true;
            if (updateThread == null) {
                updateThread = new UpdateThread();
                updateThread.start();
            }
        }
    }

    private class UpdateThread extends Thread {
        public UpdateThread() {
            super("Download Service");
        }
        
        public void run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            
            boolean keepService = false;
            // for each update from the database, remember which download is
            // supposed to get restarted soonest in the future
            long wakeUp = Long.MAX_VALUE;
            for (;;) {
                synchronized (DownloadService.this) {
                    if (updateThread != this) {
                        throw new IllegalStateException(
                                "multiple UpdateThreads in DownloadService");
                    }
                    if (!pendingUpdate) {
                        updateThread = null;
                        if (!keepService) {
                            stopSelf();
                        }
                        if (wakeUp != Long.MAX_VALUE) {
                            AlarmManager alarms =
                                    (AlarmManager) getSystemService(Context.ALARM_SERVICE);
                            if (alarms == null) {
                                Log.e(Constants.TAG, "couldn't get alarm manager");
                            } else {
                                if (Constants.LOGV) {
                                    Log.v(Constants.TAG, "scheduling retry in " + wakeUp + "ms");
                                }
                                Intent intent = new Intent(Constants.ACTION_RETRY);
                                intent.setClassName("com.android.providers.downloads",
                                        DownloadReceiver.class.getName());
                                alarms.set(
                                        AlarmManager.RTC_WAKEUP,
                                        System.currentTimeMillis() + wakeUp,
                                        PendingIntent.getBroadcast(DownloadService.this, 0, intent,
                                                PendingIntent.FLAG_ONE_SHOT));
                            }
                        }
                        oldChars = null;
                        newChars = null;
                        return;
                    }
                    pendingUpdate = false;
                }
                boolean networkAvailable = Helpers.isNetworkAvailable(DownloadService.this);
                boolean networkRoaming = Helpers.isNetworkRoaming(DownloadService.this);
                long now = System.currentTimeMillis();

                Cursor cursor = getContentResolver().query(Downloads.CONTENT_URI,
                        null, null, null, Downloads._ID);

                if (cursor == null) {
                    return;
                }

                cursor.moveToFirst();

                int arrayPos = 0;

                boolean mustScan = false;
                keepService = false;
                wakeUp = Long.MAX_VALUE;

                boolean isAfterLast = cursor.isAfterLast();

                int idColumn = cursor.getColumnIndexOrThrow(Downloads._ID);

                /*
                 * Walk the cursor and the local array to keep them in sync. The key
                 *     to the algorithm is that the ids are unique and sorted both in
                 *     the cursor and in the array, so that they can be processed in
                 *     order in both sources at the same time: at each step, both
                 *     sources point to the lowest id that hasn't been processed from
                 *     that source, and the algorithm processes the lowest id from
                 *     those two possibilities.
                 * At each step:
                 * -If the array contains an entry that's not in the cursor, remove the
                 *     entry, move to next entry in the array.
                 * -If the array contains an entry that's in the cursor, nothing to do,
                 *     move to next cursor row and next array entry.
                 * -If the cursor contains an entry that's not in the array, insert
                 *     a new entry in the array, move to next cursor row and next
                 *     array entry.
                 */
                while (!isAfterLast || arrayPos < mDownloads.size()) {
                    if (isAfterLast) {
                        // We're beyond the end of the cursor but there's still some
                        //     stuff in the local array, which can only be junk
                        if (Constants.LOGVV) {
                            int arrayId = ((DownloadInfo) mDownloads.get(arrayPos)).id;
                            Log.v(Constants.TAG, "Array update: trimming " +
                                    arrayId + " @ "  + arrayPos);
                        }
                        if (shouldScanFile(arrayPos) && mediaScannerConnected()) {
                            scanFile(null, arrayPos);
                        }
                        deleteDownload(arrayPos); // this advances in the array
                    } else {
                        int id = cursor.getInt(idColumn);

                        if (arrayPos == mDownloads.size()) {
                            insertDownload(cursor, arrayPos, networkAvailable, networkRoaming, now);
                            if (Constants.LOGVV) {
                                Log.v(Constants.TAG, "Array update: inserting " +
                                        id + " @ " + arrayPos);
                            }
                            if (shouldScanFile(arrayPos)
                                    && (!mediaScannerConnected() || !scanFile(cursor, arrayPos))) {
                                mustScan = true;
                                keepService = true;
                            }
                            if (visibleNotification(arrayPos)) {
                                keepService = true;
                            }
                            long next = nextAction(arrayPos, now);
                            if (next == 0) {
                                keepService = true;
                            } else if (next > 0 && next < wakeUp) {
                                wakeUp = next;
                            }
                            ++arrayPos;
                            cursor.moveToNext();
                            isAfterLast = cursor.isAfterLast();
                        } else {
                            int arrayId = mDownloads.get(arrayPos).id;

                            if (arrayId < id) {
                                // The array entry isn't in the cursor
                                if (Constants.LOGVV) {
                                    Log.v(Constants.TAG, "Array update: removing " + arrayId
                                            + " @ " + arrayPos);
                                }
                                if (shouldScanFile(arrayPos) && mediaScannerConnected()) {
                                    scanFile(null, arrayPos);
                                }
                                deleteDownload(arrayPos); // this advances in the array
                            } else if (arrayId == id) {
                                // This cursor row already exists in the stored array
                                updateDownload(
                                        cursor, arrayPos,
                                        networkAvailable, networkRoaming, now);
                                if (shouldScanFile(arrayPos)
                                        && (!mediaScannerConnected()
                                                || !scanFile(cursor, arrayPos))) {
                                    mustScan = true;
                                    keepService = true;
                                }
                                if (visibleNotification(arrayPos)) {
                                    keepService = true;
                                }
                                long next = nextAction(arrayPos, now);
                                if (next == 0) {
                                    keepService = true;
                                } else if (next > 0 && next < wakeUp) {
                                    wakeUp = next;
                                }
                                ++arrayPos;
                                cursor.moveToNext();
                                isAfterLast = cursor.isAfterLast();
                            } else {
                                // This cursor entry didn't exist in the stored array
                                if (Constants.LOGVV) {
                                    Log.v(Constants.TAG, "Array update: appending " +
                                            id + " @ " + arrayPos);
                                }
                                insertDownload(
                                        cursor, arrayPos,
                                        networkAvailable, networkRoaming, now);
                                if (shouldScanFile(arrayPos)
                                        && (!mediaScannerConnected()
                                                || !scanFile(cursor, arrayPos))) {
                                    mustScan = true;
                                    keepService = true;
                                }
                                if (visibleNotification(arrayPos)) {
                                    keepService = true;
                                }
                                long next = nextAction(arrayPos, now);
                                if (next == 0) {
                                    keepService = true;
                                } else if (next > 0 && next < wakeUp) {
                                    wakeUp = next;
                                }
                                ++arrayPos;
                                cursor.moveToNext();
                                isAfterLast = cursor.isAfterLast();
                            }
                        }
                    }
                }

                mNotifier.updateNotification();

                if (mustScan) {
                    if (!mMediaScannerConnecting) {
                        Intent intent = new Intent();
                        intent.setClassName("com.android.providers.media",
                                "com.android.providers.media.MediaScannerService");
                        mMediaScannerConnecting = true;
                        bindService(intent, mMediaScannerConnection, BIND_AUTO_CREATE);
                    }
                } else {
                    mMediaScannerConnection.disconnectMediaScanner();
                }

                cursor.close();
            }
        }
    }

    /**
     * Removes files that may have been left behind in the cache directory
     */
    private void removeSpuriousFiles() {
        File[] files = Environment.getDownloadCacheDirectory().listFiles();
        if (files == null) {
            // The cache folder doesn't appear to exist (this is likely the case
            // when running the simulator).
            return;
        }
        HashSet<String> fileSet = new HashSet();
        for (int i = 0; i < files.length; i++) {
            if (files[i].getName().equals(Constants.KNOWN_SPURIOUS_FILENAME)) {
                continue;
            }
            if (files[i].getName().equalsIgnoreCase(Constants.RECOVERY_DIRECTORY)) {
                continue;
            }
            fileSet.add(files[i].getPath());
        }

        Cursor cursor = getContentResolver().query(Downloads.CONTENT_URI,
                new String[] { Downloads._DATA }, null, null, null);
        if (cursor != null) {
            if (cursor.moveToFirst()) {
                do {
                    fileSet.remove(cursor.getString(0));
                } while (cursor.moveToNext());
            }
            cursor.close();
        }
        Iterator<String> iterator = fileSet.iterator();
        while (iterator.hasNext()) {
            String filename = iterator.next();
            if (Constants.LOGV) {
                Log.v(Constants.TAG, "deleting spurious file " + filename);
            }
            new File(filename).delete();
        }
    }

    /**
     * Drops old rows from the database to prevent it from growing too large
     */
    private void trimDatabase() {
        Cursor cursor = getContentResolver().query(Downloads.CONTENT_URI,
                new String[] { Downloads._ID },
                Downloads.STATUS + " >= '200'", null,
                Downloads.LAST_MODIFICATION);
        if (cursor == null) {
            // This isn't good - if we can't do basic queries in our database, nothing's gonna work
            Log.e(Constants.TAG, "null cursor in trimDatabase");
            return;
        }
        if (cursor.moveToFirst()) {
            int numDelete = cursor.getCount() - Constants.MAX_DOWNLOADS;
            int columnId = cursor.getColumnIndexOrThrow(Downloads._ID);
            while (numDelete > 0) {
                getContentResolver().delete(
                        ContentUris.withAppendedId(Downloads.CONTENT_URI, cursor.getLong(columnId)),
                        null, null);
                if (!cursor.moveToNext()) {
                    break;
                }
                numDelete--;
            }
        }
        cursor.close();
    }

    /**
     * Keeps a local copy of the info about a download, and initiates the
     * download if appropriate.
     */
    private void insertDownload(
            Cursor cursor, int arrayPos,
            boolean networkAvailable, boolean networkRoaming, long now) {
        int statusColumn = cursor.getColumnIndexOrThrow(Downloads.STATUS);
        int failedColumn = cursor.getColumnIndexOrThrow(Constants.FAILED_CONNECTIONS);
        int retryRedirect =
                cursor.getInt(cursor.getColumnIndexOrThrow(Constants.RETRY_AFTER___REDIRECT_COUNT));
        DownloadInfo info = new DownloadInfo(
                cursor.getInt(cursor.getColumnIndexOrThrow(Downloads._ID)),
                cursor.getString(cursor.getColumnIndexOrThrow(Downloads.URI)),
                cursor.getInt(cursor.getColumnIndexOrThrow(Downloads.NO_INTEGRITY)) == 1,
                cursor.getString(cursor.getColumnIndexOrThrow(Downloads.FILENAME_HINT)),
                cursor.getString(cursor.getColumnIndexOrThrow(Downloads._DATA)),
                cursor.getString(cursor.getColumnIndexOrThrow(Downloads.MIMETYPE)),
                cursor.getInt(cursor.getColumnIndexOrThrow(Downloads.DESTINATION)),
                cursor.getInt(cursor.getColumnIndexOrThrow(Downloads.VISIBILITY)),
                cursor.getInt(cursor.getColumnIndexOrThrow(Downloads.CONTROL)),
                cursor.getInt(statusColumn),
                cursor.getInt(failedColumn),
                retryRedirect & 0xfffffff,
                retryRedirect >> 28,
                cursor.getLong(cursor.getColumnIndexOrThrow(Downloads.LAST_MODIFICATION)),
                cursor.getString(cursor.getColumnIndexOrThrow(Downloads.NOTIFICATION_PACKAGE)),
                cursor.getString(cursor.getColumnIndexOrThrow(Downloads.NOTIFICATION_CLASS)),
                cursor.getString(cursor.getColumnIndexOrThrow(Downloads.NOTIFICATION_EXTRAS)),
                cursor.getString(cursor.getColumnIndexOrThrow(Downloads.COOKIE_DATA)),
                cursor.getString(cursor.getColumnIndexOrThrow(Downloads.USER_AGENT)),
                cursor.getString(cursor.getColumnIndexOrThrow(Downloads.REFERER)),
                cursor.getInt(cursor.getColumnIndexOrThrow(Downloads.TOTAL_BYTES)),
                cursor.getInt(cursor.getColumnIndexOrThrow(Downloads.CURRENT_BYTES)),
                cursor.getString(cursor.getColumnIndexOrThrow(Constants.ETAG)),
                cursor.getInt(cursor.getColumnIndexOrThrow(Constants.MEDIA_SCANNED)) == 1);

        if (Constants.LOGVV) {
            Log.v(Constants.TAG, "Service adding new entry");
            Log.v(Constants.TAG, "ID      : " + info.id);
            Log.v(Constants.TAG, "URI     : " + ((info.uri != null) ? "yes" : "no"));
            Log.v(Constants.TAG, "NO_INTEG: " + info.noIntegrity);
            Log.v(Constants.TAG, "HINT    : " + info.hint);
            Log.v(Constants.TAG, "FILENAME: " + info.filename);
            Log.v(Constants.TAG, "MIMETYPE: " + info.mimetype);
            Log.v(Constants.TAG, "DESTINAT: " + info.destination);
            Log.v(Constants.TAG, "VISIBILI: " + info.visibility);
            Log.v(Constants.TAG, "CONTROL : " + info.control);
            Log.v(Constants.TAG, "STATUS  : " + info.status);
            Log.v(Constants.TAG, "FAILED_C: " + info.numFailed);
            Log.v(Constants.TAG, "RETRY_AF: " + info.retryAfter);
            Log.v(Constants.TAG, "REDIRECT: " + info.redirectCount);
            Log.v(Constants.TAG, "LAST_MOD: " + info.lastMod);
            Log.v(Constants.TAG, "PACKAGE : " + info.pckg);
            Log.v(Constants.TAG, "CLASS   : " + info.clazz);
            Log.v(Constants.TAG, "COOKIES : " + ((info.cookies != null) ? "yes" : "no"));
            Log.v(Constants.TAG, "AGENT   : " + info.userAgent);
            Log.v(Constants.TAG, "REFERER : " + ((info.referer != null) ? "yes" : "no"));
            Log.v(Constants.TAG, "TOTAL   : " + info.totalBytes);
            Log.v(Constants.TAG, "CURRENT : " + info.currentBytes);
            Log.v(Constants.TAG, "ETAG    : " + info.etag);
            Log.v(Constants.TAG, "SCANNED : " + info.mediaScanned);
        }

        mDownloads.add(arrayPos, info);

        if (info.status == 0
                && (info.destination == Downloads.DESTINATION_EXTERNAL
                    || info.destination == Downloads.DESTINATION_CACHE_PARTITION_PURGEABLE)
                && info.mimetype != null
                && !DrmRawContent.DRM_MIMETYPE_MESSAGE_STRING.equalsIgnoreCase(info.mimetype)) {
            // Check to see if we are allowed to download this file. Only files
            // that can be handled by the platform can be downloaded.
            // special case DRM files, which we should always allow downloading.
            Intent mimetypeIntent = new Intent(Intent.ACTION_VIEW);
            
            // We can provide data as either content: or file: URIs,
            // so allow both.  (I think it would be nice if we just did
            // everything as content: URIs)
            // Actually, right now the download manager's UId restrictions
            // prevent use from using content: so it's got to be file: or
            // nothing
            
            mimetypeIntent.setDataAndType(Uri.fromParts("file", "", null), info.mimetype);
            ResolveInfo ri = getPackageManager().resolveActivity(mimetypeIntent,
                    PackageManager.MATCH_DEFAULT_ONLY);
            //Log.i(Constants.TAG, "*** QUERY " + mimetypeIntent + ": " + list);
            
            if (ri == null) {
                if (Config.LOGD) {
                    Log.d(Constants.TAG, "no application to handle MIME type " + info.mimetype);
                }
                info.status = Downloads.STATUS_NOT_ACCEPTABLE;

                Uri uri = ContentUris.withAppendedId(Downloads.CONTENT_URI, info.id);
                ContentValues values = new ContentValues();
                values.put(Downloads.STATUS, Downloads.STATUS_NOT_ACCEPTABLE);
                getContentResolver().update(uri, values, null, null);
                info.sendIntentIfRequested(uri, this);
                return;
            }
        }

        if (info.canUseNetwork(networkAvailable, networkRoaming)) {
            if (info.isReadyToStart(now)) {
                if (Constants.LOGV) {
                    Log.v(Constants.TAG, "Service spawning thread to handle new download " +
                            info.id);
                }
                if (info.hasActiveThread) {
                    throw new IllegalStateException("Multiple threads on same download on insert");
                }
                if (info.status != Downloads.STATUS_RUNNING) {
                    info.status = Downloads.STATUS_RUNNING;
                    ContentValues values = new ContentValues();
                    values.put(Downloads.STATUS, info.status);
                    getContentResolver().update(
                            ContentUris.withAppendedId(Downloads.CONTENT_URI, info.id),
                            values, null, null);
                }
                DownloadThread downloader = new DownloadThread(this, info);
                info.hasActiveThread = true;
                downloader.start();
            }
        } else {
            if (info.status == 0
                    || info.status == Downloads.STATUS_PENDING
                    || info.status == Downloads.STATUS_RUNNING) {
                info.status = Downloads.STATUS_RUNNING_PAUSED;
                Uri uri = ContentUris.withAppendedId(Downloads.CONTENT_URI, info.id);
                ContentValues values = new ContentValues();
                values.put(Downloads.STATUS, Downloads.STATUS_RUNNING_PAUSED);
                getContentResolver().update(uri, values, null, null);
            }
        }
    }

    /**
     * Updates the local copy of the info about a download.
     */
    private void updateDownload(
            Cursor cursor, int arrayPos,
            boolean networkAvailable, boolean networkRoaming, long now) {
        DownloadInfo info = (DownloadInfo) mDownloads.get(arrayPos);
        int statusColumn = cursor.getColumnIndexOrThrow(Downloads.STATUS);
        int failedColumn = cursor.getColumnIndexOrThrow(Constants.FAILED_CONNECTIONS);
        info.id = cursor.getInt(cursor.getColumnIndexOrThrow(Downloads._ID));
        info.uri = stringFromCursor(info.uri, cursor, Downloads.URI);
        info.noIntegrity =
                cursor.getInt(cursor.getColumnIndexOrThrow(Downloads.NO_INTEGRITY)) == 1;
        info.hint = stringFromCursor(info.hint, cursor, Downloads.FILENAME_HINT);
        info.filename = stringFromCursor(info.filename, cursor, Downloads._DATA);
        info.mimetype = stringFromCursor(info.mimetype, cursor, Downloads.MIMETYPE);
        info.destination = cursor.getInt(cursor.getColumnIndexOrThrow(Downloads.DESTINATION));
        int newVisibility = cursor.getInt(cursor.getColumnIndexOrThrow(Downloads.VISIBILITY));
        if (info.visibility == Downloads.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                && newVisibility != Downloads.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                && Downloads.isStatusCompleted(info.status)) {
            mNotifier.mNotificationMgr.cancel(info.id);
        }
        info.visibility = newVisibility;
        synchronized(info) {
            info.control = cursor.getInt(cursor.getColumnIndexOrThrow(Downloads.CONTROL));
        }
        int newStatus = cursor.getInt(statusColumn);
        if (!Downloads.isStatusCompleted(info.status) && Downloads.isStatusCompleted(newStatus)) {
            mNotifier.mNotificationMgr.cancel(info.id);
        }
        info.status = newStatus;
        info.numFailed = cursor.getInt(failedColumn);
        int retryRedirect =
                cursor.getInt(cursor.getColumnIndexOrThrow(Constants.RETRY_AFTER___REDIRECT_COUNT));
        info.retryAfter = retryRedirect & 0xfffffff;
        info.redirectCount = retryRedirect >> 28;
        info.lastMod = cursor.getLong(cursor.getColumnIndexOrThrow(Downloads.LAST_MODIFICATION));
        info.pckg = stringFromCursor(info.pckg, cursor, Downloads.NOTIFICATION_PACKAGE);
        info.clazz = stringFromCursor(info.clazz, cursor, Downloads.NOTIFICATION_CLASS);
        info.cookies = stringFromCursor(info.cookies, cursor, Downloads.COOKIE_DATA);
        info.userAgent = stringFromCursor(info.userAgent, cursor, Downloads.USER_AGENT);
        info.referer = stringFromCursor(info.referer, cursor, Downloads.REFERER);
        info.totalBytes = cursor.getInt(cursor.getColumnIndexOrThrow(Downloads.TOTAL_BYTES));
        info.currentBytes = cursor.getInt(cursor.getColumnIndexOrThrow(Downloads.CURRENT_BYTES));
        info.etag = stringFromCursor(info.etag, cursor, Constants.ETAG);
        info.mediaScanned =
                cursor.getInt(cursor.getColumnIndexOrThrow(Constants.MEDIA_SCANNED)) == 1;

        if (info.canUseNetwork(networkAvailable, networkRoaming)) {
            if (info.isReadyToRestart(now)) {
                if (Constants.LOGV) {
                    Log.v(Constants.TAG, "Service spawning thread to handle updated download " +
                            info.id);
                }
                if (info.hasActiveThread) {
                    throw new IllegalStateException("Multiple threads on same download on update");
                }
                info.status = Downloads.STATUS_RUNNING;
                ContentValues values = new ContentValues();
                values.put(Downloads.STATUS, info.status);
                getContentResolver().update(
                        ContentUris.withAppendedId(Downloads.CONTENT_URI, info.id),
                        values, null, null);
                DownloadThread downloader = new DownloadThread(this, info);
                info.hasActiveThread = true;
                downloader.start();
            }
        }
    }

    /**
     * Returns a String that holds the current value of the column,
     * optimizing for the case where the value hasn't changed.
     */
    private String stringFromCursor(String old, Cursor cursor, String column) {
        int index = cursor.getColumnIndexOrThrow(column);
        if (old == null) {
            return cursor.getString(index);
        }
        if (newChars == null) {
            newChars = new CharArrayBuffer(128);
        }
        cursor.copyStringToBuffer(index, newChars);
        int length = newChars.sizeCopied;
        if (length != old.length()) {
            return cursor.getString(index);
        }
        if (oldChars == null || oldChars.sizeCopied < length) {
            oldChars = new CharArrayBuffer(length);
        }
        char[] oldArray = oldChars.data;
        char[] newArray = newChars.data;
        old.getChars(0, length, oldArray, 0);
        for (int i = length - 1; i >= 0; --i) {
            if (oldArray[i] != newArray[i]) {
                return new String(newArray, 0, length);
            }
        }
        return old;
    }

    /**
     * Removes the local copy of the info about a download.
     */
    private void deleteDownload(int arrayPos) {
        DownloadInfo info = (DownloadInfo) mDownloads.get(arrayPos);
        if (info.status == Downloads.STATUS_RUNNING) {
            info.status = Downloads.STATUS_CANCELED;
        } else if (info.destination != Downloads.DESTINATION_EXTERNAL && info.filename != null) {
            new File(info.filename).delete();
        }
        mNotifier.mNotificationMgr.cancel(info.id);

        mDownloads.remove(arrayPos);
    }

    /**
     * Returns the amount of time (as measured from the "now" parameter)
     * at which a download will be active.
     * 0 = immediately - service should stick around to handle this download.
     * -1 = never - service can go away without ever waking up.
     * positive value - service must wake up in the future, as specified in ms from "now"
     */
    private long nextAction(int arrayPos, long now) {
        DownloadInfo info = (DownloadInfo) mDownloads.get(arrayPos);
        if (Downloads.isStatusCompleted(info.status)) {
            return -1;
        }
        if (info.status != Downloads.STATUS_RUNNING_PAUSED) {
            return 0;
        }
        if (info.numFailed == 0) {
            return 0;
        }
        long when = info.restartTime();
        if (when <= now) {
            return 0;
        }
        return when - now;
    }

    /**
     * Returns whether there's a visible notification for this download
     */
    private boolean visibleNotification(int arrayPos) {
        DownloadInfo info = (DownloadInfo) mDownloads.get(arrayPos);
        return info.hasCompletionNotification();
    }

    /**
     * Returns whether a file should be scanned
     */
    private boolean shouldScanFile(int arrayPos) {
        DownloadInfo info = (DownloadInfo) mDownloads.get(arrayPos);
        return !info.mediaScanned
                && info.destination == Downloads.DESTINATION_EXTERNAL
                && Downloads.isStatusSuccess(info.status)
                && !DrmRawContent.DRM_MIMETYPE_MESSAGE_STRING.equalsIgnoreCase(info.mimetype);
    }

    /**
     * Returns whether we have a live connection to the Media Scanner
     */
    private boolean mediaScannerConnected() {
        return mMediaScannerService != null;
    }

    /**
     * Attempts to scan the file if necessary.
     * Returns true if the file has been properly scanned.
     */
    private boolean scanFile(Cursor cursor, int arrayPos) {
        DownloadInfo info = (DownloadInfo) mDownloads.get(arrayPos);
        synchronized (this) {
            if (mMediaScannerService != null) {
                try {
                    if (Constants.LOGV) {
                        Log.v(Constants.TAG, "Scanning file " + info.filename);
                    }
                    mMediaScannerService.scanFile(info.filename, info.mimetype);
                    if (cursor != null) {
                        ContentValues values = new ContentValues();
                        values.put(Constants.MEDIA_SCANNED, 1);
                        getContentResolver().update(
                                ContentUris.withAppendedId(Downloads.CONTENT_URI,
                                       cursor.getLong(cursor.getColumnIndexOrThrow(Downloads._ID))),
                                values, null, null);
                    }
                    return true;
                } catch (RemoteException e) {
                    if (Config.LOGD) {
                        Log.d(Constants.TAG, "Failed to scan file " + info.filename);
                    }
                }
            }
        }
        return false;
    }

}
