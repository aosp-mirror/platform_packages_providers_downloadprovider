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

import static com.android.providers.downloads.Constants.TAG;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.database.ContentObserver;
import android.database.Cursor;
import android.media.IMediaScannerListener;
import android.media.IMediaScannerService;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.provider.Downloads;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.IndentingPrintWriter;
import com.google.android.collect.Maps;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;

import java.io.File;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Performs the background downloads requested by applications that use the Downloads provider.
 */
public class DownloadService extends Service {
    /** amount of time to wait to connect to MediaScannerService before timing out */
    private static final long WAIT_TIMEOUT = 10 * 1000;

    /** Observer to get notified when the content observer's data changes */
    private DownloadManagerContentObserver mObserver;

    /** Class to handle Notification Manager updates */
    private DownloadNotifier mNotifier;

    /**
     * The Service's view of the list of downloads, mapping download IDs to the corresponding info
     * object. This is kept independently from the content provider, and the Service only initiates
     * downloads based on this data, so that it can deal with situation where the data in the
     * content provider changes or disappears.
     */
    @GuardedBy("mDownloads")
    private Map<Long, DownloadInfo> mDownloads = Maps.newHashMap();

    /**
     * The thread that updates the internal download list from the content
     * provider.
     */
    @VisibleForTesting
    UpdateThread mUpdateThread;

    /**
     * Whether the internal download list should be updated from the content
     * provider.
     */
    private boolean mPendingUpdate;

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

    @VisibleForTesting
    SystemFacade mSystemFacade;

    private StorageManager mStorageManager;

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
        @Override
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
            synchronized (DownloadService.this) {
                try {
                    mMediaScannerConnecting = false;
                    mMediaScannerService = IMediaScannerService.Stub.asInterface(service);
                    if (mMediaScannerService != null) {
                        updateFromProvider();
                    }
                } finally {
                    // notify anyone waiting on successful connection to MediaService
                    DownloadService.this.notifyAll();
                }
            }
        }

        public void disconnectMediaScanner() {
            synchronized (DownloadService.this) {
                mMediaScannerConnecting = false;
                if (mMediaScannerService != null) {
                    mMediaScannerService = null;
                    if (Constants.LOGVV) {
                        Log.v(Constants.TAG, "Disconnecting from Media Scanner");
                    }
                    try {
                        unbindService(this);
                    } catch (IllegalArgumentException ex) {
                        Log.w(Constants.TAG, "unbindService failed: " + ex);
                    } finally {
                        // notify anyone waiting on unsuccessful connection to MediaService
                        DownloadService.this.notifyAll();
                    }
                }
            }
        }

        public void onServiceDisconnected(ComponentName className) {
            try {
                if (Constants.LOGVV) {
                    Log.v(Constants.TAG, "Disconnected from Media Scanner");
                }
            } finally {
                synchronized (DownloadService.this) {
                    mMediaScannerService = null;
                    mMediaScannerConnecting = false;
                    // notify anyone waiting on disconnect from MediaService
                    DownloadService.this.notifyAll();
                }
            }
        }
    }

    /**
     * Returns an IBinder instance when someone wants to connect to this
     * service. Binding to this service is not allowed.
     *
     * @throws UnsupportedOperationException
     */
    @Override
    public IBinder onBind(Intent i) {
        throw new UnsupportedOperationException("Cannot bind to Download Manager Service");
    }

    /**
     * Initializes the service when it is first created
     */
    @Override
    public void onCreate() {
        super.onCreate();
        if (Constants.LOGVV) {
            Log.v(Constants.TAG, "Service onCreate");
        }

        if (mSystemFacade == null) {
            mSystemFacade = new RealSystemFacade(this);
        }

        mObserver = new DownloadManagerContentObserver();
        getContentResolver().registerContentObserver(Downloads.Impl.ALL_DOWNLOADS_CONTENT_URI,
                true, mObserver);

        mMediaScannerService = null;
        mMediaScannerConnecting = false;
        mMediaScannerConnection = new MediaScannerConnection();

        mNotifier = new DownloadNotifier(this);
        mNotifier.cancelAll();

        mStorageManager = StorageManager.getInstance(getApplicationContext());
        updateFromProvider();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        int returnValue = super.onStartCommand(intent, flags, startId);
        if (Constants.LOGVV) {
            Log.v(Constants.TAG, "Service onStart");
        }
        updateFromProvider();
        return returnValue;
    }

    /**
     * Cleans up when the service is destroyed
     */
    @Override
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
            mPendingUpdate = true;
            if (mUpdateThread == null) {
                mUpdateThread = new UpdateThread();
                mSystemFacade.startThread(mUpdateThread);
            }
        }
    }

    private class UpdateThread extends Thread {
        public UpdateThread() {
            super("Download Service");
        }

        @Override
        public void run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            boolean keepService = false;
            // for each update from the database, remember which download is
            // supposed to get restarted soonest in the future
            long wakeUp = Long.MAX_VALUE;
            for (;;) {
                synchronized (DownloadService.this) {
                    if (mUpdateThread != this) {
                        throw new IllegalStateException(
                                "multiple UpdateThreads in DownloadService");
                    }
                    if (!mPendingUpdate) {
                        mUpdateThread = null;
                        if (!keepService) {
                            stopSelf();
                        }
                        if (wakeUp != Long.MAX_VALUE) {
                            scheduleAlarm(wakeUp);
                        }
                        return;
                    }
                    mPendingUpdate = false;
                }

                synchronized (mDownloads) {
                    long now = mSystemFacade.currentTimeMillis();
                    boolean mustScan = false;
                    keepService = false;
                    wakeUp = Long.MAX_VALUE;
                    Set<Long> idsNoLongerInDatabase = new HashSet<Long>(mDownloads.keySet());

                    Cursor cursor = getContentResolver().query(Downloads.Impl.ALL_DOWNLOADS_CONTENT_URI,
                            null, null, null, null);
                    if (cursor == null) {
                        continue;
                    }
                    try {
                        DownloadInfo.Reader reader =
                                new DownloadInfo.Reader(getContentResolver(), cursor);
                        int idColumn = cursor.getColumnIndexOrThrow(Downloads.Impl._ID);
                        if (Constants.LOGVV) {
                            Log.i(Constants.TAG, "number of rows from downloads-db: " +
                                    cursor.getCount());
                        }
                        for (cursor.moveToFirst(); !cursor.isAfterLast(); cursor.moveToNext()) {
                            long id = cursor.getLong(idColumn);
                            idsNoLongerInDatabase.remove(id);
                            DownloadInfo info = mDownloads.get(id);
                            if (info != null) {
                                updateDownload(reader, info, now);
                            } else {
                                info = insertDownloadLocked(reader, now);
                            }

                            if (info.shouldScanFile() && !scanFile(info, true, false)) {
                                mustScan = true;
                                keepService = true;
                            }
                            if (info.hasCompletionNotification()) {
                                keepService = true;
                            }
                            long next = info.nextAction(now);
                            if (next == 0) {
                                keepService = true;
                            } else if (next > 0 && next < wakeUp) {
                                wakeUp = next;
                            }
                        }
                    } finally {
                        cursor.close();
                    }

                    for (Long id : idsNoLongerInDatabase) {
                        deleteDownloadLocked(id);
                    }

                    // is there a need to start the DownloadService? yes, if there are rows to be
                    // deleted.
                    if (!mustScan) {
                        for (DownloadInfo info : mDownloads.values()) {
                            if (info.mDeleted && TextUtils.isEmpty(info.mMediaProviderUri)) {
                                mustScan = true;
                                keepService = true;
                                break;
                            }
                        }
                    }
                    mNotifier.updateWith(mDownloads.values());
                    if (mustScan) {
                        bindMediaScanner();
                    } else {
                        mMediaScannerConnection.disconnectMediaScanner();
                    }

                    // look for all rows with deleted flag set and delete the rows from the database
                    // permanently
                    for (DownloadInfo info : mDownloads.values()) {
                        if (info.mDeleted) {
                            // this row is to be deleted from the database. but does it have
                            // mediaProviderUri?
                            if (TextUtils.isEmpty(info.mMediaProviderUri)) {
                                if (info.shouldScanFile()) {
                                    // initiate rescan of the file to - which will populate
                                    // mediaProviderUri column in this row
                                    if (!scanFile(info, false, true)) {
                                        throw new IllegalStateException("scanFile failed!");
                                    }
                                    continue;
                                }
                            } else {
                                // yes it has mediaProviderUri column already filled in.
                                // delete it from MediaProvider database.
                                getContentResolver().delete(Uri.parse(info.mMediaProviderUri), null,
                                        null);
                            }
                            // delete the file
                            deleteFileIfExists(info.mFileName);
                            // delete from the downloads db
                            getContentResolver().delete(Downloads.Impl.ALL_DOWNLOADS_CONTENT_URI,
                                    Downloads.Impl._ID + " = ? ",
                                    new String[]{String.valueOf(info.mId)});
                        }
                    }
                }
            }
        }

        private void bindMediaScanner() {
            if (!mMediaScannerConnecting) {
                Intent intent = new Intent();
                intent.setClassName("com.android.providers.media",
                        "com.android.providers.media.MediaScannerService");
                mMediaScannerConnecting = true;
                bindService(intent, mMediaScannerConnection, BIND_AUTO_CREATE);
            }
        }

        private void scheduleAlarm(long wakeUp) {
            AlarmManager alarms = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (alarms == null) {
                Log.e(Constants.TAG, "couldn't get alarm manager");
                return;
            }

            if (Constants.LOGV) {
                Log.v(Constants.TAG, "scheduling retry in " + wakeUp + "ms");
            }

            Intent intent = new Intent(Constants.ACTION_RETRY);
            intent.setClassName("com.android.providers.downloads",
                    DownloadReceiver.class.getName());
            alarms.set(
                    AlarmManager.RTC_WAKEUP,
                    mSystemFacade.currentTimeMillis() + wakeUp,
                    PendingIntent.getBroadcast(DownloadService.this, 0, intent,
                            PendingIntent.FLAG_ONE_SHOT));
        }
    }

    /**
     * Keeps a local copy of the info about a download, and initiates the
     * download if appropriate.
     */
    private DownloadInfo insertDownloadLocked(DownloadInfo.Reader reader, long now) {
        DownloadInfo info = reader.newDownloadInfo(this, mSystemFacade);
        mDownloads.put(info.mId, info);

        if (Constants.LOGVV) {
            Log.v(Constants.TAG, "processing inserted download " + info.mId);
        }

        info.startIfReady(now, mStorageManager);
        return info;
    }

    /**
     * Updates the local copy of the info about a download.
     */
    private void updateDownload(DownloadInfo.Reader reader, DownloadInfo info, long now) {
        int oldVisibility = info.mVisibility;
        int oldStatus = info.mStatus;

        reader.updateFromDatabase(info);
        if (Constants.LOGVV) {
            Log.v(Constants.TAG, "processing updated download " + info.mId +
                    ", status: " + info.mStatus);
        }
        info.startIfReady(now, mStorageManager);
    }

    /**
     * Removes the local copy of the info about a download.
     */
    private void deleteDownloadLocked(long id) {
        DownloadInfo info = mDownloads.get(id);
        if (info.mStatus == Downloads.Impl.STATUS_RUNNING) {
            info.mStatus = Downloads.Impl.STATUS_CANCELED;
        }
        if (info.mDestination != Downloads.Impl.DESTINATION_EXTERNAL && info.mFileName != null) {
            if (Constants.LOGVV) {
                Log.d(TAG, "deleteDownloadLocked() deleting " + info.mFileName);
            }
            new File(info.mFileName).delete();
        }
        mDownloads.remove(info.mId);
    }

    /**
     * Attempts to scan the file if necessary.
     * @return true if the file has been properly scanned.
     */
    private boolean scanFile(DownloadInfo info, final boolean updateDatabase,
            final boolean deleteFile) {
        synchronized (this) {
            if (mMediaScannerService == null) {
                // not bound to mediaservice. but if in the process of connecting to it, wait until
                // connection is resolved
                while (mMediaScannerConnecting) {
                    Log.d(Constants.TAG, "waiting for mMediaScannerService service: ");
                    try {
                        this.wait(WAIT_TIMEOUT);
                    } catch (InterruptedException e1) {
                        throw new IllegalStateException("wait interrupted");
                    }
                }
            }
            // do we have mediaservice?
            if (mMediaScannerService == null) {
                // no available MediaService And not even in the process of connecting to it
                return false;
            }
            if (Constants.LOGV) {
                Log.v(Constants.TAG, "Scanning file " + info.mFileName);
            }
            try {
                final Uri key = info.getAllDownloadsUri();
                final long id = info.mId;
                mMediaScannerService.requestScanFile(info.mFileName, info.mMimeType,
                        new IMediaScannerListener.Stub() {
                            public void scanCompleted(String path, Uri uri) {
                                if (updateDatabase) {
                                    // Mark this as 'scanned' in the database
                                    // so that it is NOT subject to re-scanning by MediaScanner
                                    // next time this database row row is encountered
                                    ContentValues values = new ContentValues();
                                    values.put(Constants.MEDIA_SCANNED, 1);
                                    if (uri != null) {
                                        values.put(Downloads.Impl.COLUMN_MEDIAPROVIDER_URI,
                                                uri.toString());
                                    }
                                    getContentResolver().update(key, values, null, null);
                                } else if (deleteFile) {
                                    if (uri != null) {
                                        // use the Uri returned to delete it from the MediaProvider
                                        getContentResolver().delete(uri, null, null);
                                    }
                                    // delete the file and delete its row from the downloads db
                                    deleteFileIfExists(path);
                                    getContentResolver().delete(
                                            Downloads.Impl.ALL_DOWNLOADS_CONTENT_URI,
                                            Downloads.Impl._ID + " = ? ",
                                            new String[]{String.valueOf(id)});
                                }
                            }
                        });
                return true;
            } catch (RemoteException e) {
                Log.w(Constants.TAG, "Failed to scan file " + info.mFileName);
                return false;
            }
        }
    }

    private void deleteFileIfExists(String path) {
        try {
            if (!TextUtils.isEmpty(path)) {
                if (Constants.LOGVV) {
                    Log.d(TAG, "deleteFileIfExists() deleting " + path);
                }
                File file = new File(path);
                file.delete();
            }
        } catch (Exception e) {
            Log.w(Constants.TAG, "file: '" + path + "' couldn't be deleted", e);
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        final IndentingPrintWriter pw = new IndentingPrintWriter(writer, "  ");
        synchronized (mDownloads) {
            final List<Long> ids = Lists.newArrayList(mDownloads.keySet());
            Collections.sort(ids);
            for (Long id : ids) {
                final DownloadInfo info = mDownloads.get(id);
                info.dump(pw);
            }
        }
    }
}
