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

import android.app.DownloadManager;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.NetworkInfo.DetailedState;
import android.net.Uri;
import android.os.Environment;
import android.provider.Downloads;
import android.provider.Downloads.Impl;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Stores information about an individual download.
 */
public class DownloadInfo {
    public static class Reader {
        private ContentResolver mResolver;
        private Cursor mCursor;

        public Reader(ContentResolver resolver, Cursor cursor) {
            mResolver = resolver;
            mCursor = cursor;
        }

        public DownloadInfo newDownloadInfo(Context context, SystemFacade systemFacade) {
            DownloadInfo info = new DownloadInfo(context, systemFacade);
            updateFromDatabase(info);
            readRequestHeaders(info);
            return info;
        }

        public void updateFromDatabase(DownloadInfo info) {
            info.mId = getLong(Downloads.Impl._ID);
            info.mUri = getString(Downloads.Impl.COLUMN_URI);
            info.mNoIntegrity = getInt(Downloads.Impl.COLUMN_NO_INTEGRITY) == 1;
            info.mHint = getString(Downloads.Impl.COLUMN_FILE_NAME_HINT);
            info.mFileName = getString(Downloads.Impl._DATA);
            info.mMimeType = getString(Downloads.Impl.COLUMN_MIME_TYPE);
            info.mDestination = getInt(Downloads.Impl.COLUMN_DESTINATION);
            info.mVisibility = getInt(Downloads.Impl.COLUMN_VISIBILITY);
            info.mStatus = getInt(Downloads.Impl.COLUMN_STATUS);
            info.mNumFailed = getInt(Constants.FAILED_CONNECTIONS);
            int retryRedirect = getInt(Constants.RETRY_AFTER_X_REDIRECT_COUNT);
            info.mRetryAfter = retryRedirect & 0xfffffff;
            info.mLastMod = getLong(Downloads.Impl.COLUMN_LAST_MODIFICATION);
            info.mPackage = getString(Downloads.Impl.COLUMN_NOTIFICATION_PACKAGE);
            info.mClass = getString(Downloads.Impl.COLUMN_NOTIFICATION_CLASS);
            info.mExtras = getString(Downloads.Impl.COLUMN_NOTIFICATION_EXTRAS);
            info.mCookies = getString(Downloads.Impl.COLUMN_COOKIE_DATA);
            info.mUserAgent = getString(Downloads.Impl.COLUMN_USER_AGENT);
            info.mReferer = getString(Downloads.Impl.COLUMN_REFERER);
            info.mTotalBytes = getLong(Downloads.Impl.COLUMN_TOTAL_BYTES);
            info.mCurrentBytes = getLong(Downloads.Impl.COLUMN_CURRENT_BYTES);
            info.mETag = getString(Constants.ETAG);
            info.mUid = getInt(Constants.UID);
            info.mMediaScanned = getInt(Constants.MEDIA_SCANNED);
            info.mDeleted = getInt(Downloads.Impl.COLUMN_DELETED) == 1;
            info.mMediaProviderUri = getString(Downloads.Impl.COLUMN_MEDIAPROVIDER_URI);
            info.mIsPublicApi = getInt(Downloads.Impl.COLUMN_IS_PUBLIC_API) != 0;
            info.mAllowedNetworkTypes = getInt(Downloads.Impl.COLUMN_ALLOWED_NETWORK_TYPES);
            info.mAllowRoaming = getInt(Downloads.Impl.COLUMN_ALLOW_ROAMING) != 0;
            info.mTitle = getString(Downloads.Impl.COLUMN_TITLE);
            info.mDescription = getString(Downloads.Impl.COLUMN_DESCRIPTION);
            info.mBypassRecommendedSizeLimit =
                    getInt(Downloads.Impl.COLUMN_BYPASS_RECOMMENDED_SIZE_LIMIT);

            synchronized (this) {
                info.mControl = getInt(Downloads.Impl.COLUMN_CONTROL);
            }
        }

        private void readRequestHeaders(DownloadInfo info) {
            info.mRequestHeaders.clear();
            Uri headerUri = Uri.withAppendedPath(
                    info.getAllDownloadsUri(), Downloads.Impl.RequestHeaders.URI_SEGMENT);
            Cursor cursor = mResolver.query(headerUri, null, null, null, null);
            try {
                int headerIndex =
                        cursor.getColumnIndexOrThrow(Downloads.Impl.RequestHeaders.COLUMN_HEADER);
                int valueIndex =
                        cursor.getColumnIndexOrThrow(Downloads.Impl.RequestHeaders.COLUMN_VALUE);
                for (cursor.moveToFirst(); !cursor.isAfterLast(); cursor.moveToNext()) {
                    addHeader(info, cursor.getString(headerIndex), cursor.getString(valueIndex));
                }
            } finally {
                cursor.close();
            }

            if (info.mCookies != null) {
                addHeader(info, "Cookie", info.mCookies);
            }
            if (info.mReferer != null) {
                addHeader(info, "Referer", info.mReferer);
            }
        }

        private void addHeader(DownloadInfo info, String header, String value) {
            info.mRequestHeaders.add(Pair.create(header, value));
        }

        private String getString(String column) {
            int index = mCursor.getColumnIndexOrThrow(column);
            String s = mCursor.getString(index);
            return (TextUtils.isEmpty(s)) ? null : s;
        }

        private Integer getInt(String column) {
            return mCursor.getInt(mCursor.getColumnIndexOrThrow(column));
        }

        private Long getLong(String column) {
            return mCursor.getLong(mCursor.getColumnIndexOrThrow(column));
        }
    }

    // the following NETWORK_* constants are used to indicates specfic reasons for disallowing a
    // download from using a network, since specific causes can require special handling

    /**
     * The network is usable for the given download.
     */
    public static final int NETWORK_OK = 1;

    /**
     * There is no network connectivity.
     */
    public static final int NETWORK_NO_CONNECTION = 2;

    /**
     * The download exceeds the maximum size for this network.
     */
    public static final int NETWORK_UNUSABLE_DUE_TO_SIZE = 3;

    /**
     * The download exceeds the recommended maximum size for this network, the user must confirm for
     * this download to proceed without WiFi.
     */
    public static final int NETWORK_RECOMMENDED_UNUSABLE_DUE_TO_SIZE = 4;

    /**
     * The current connection is roaming, and the download can't proceed over a roaming connection.
     */
    public static final int NETWORK_CANNOT_USE_ROAMING = 5;

    /**
     * The app requesting the download specific that it can't use the current network connection.
     */
    public static final int NETWORK_TYPE_DISALLOWED_BY_REQUESTOR = 6;

    /**
     * Current network is blocked for requesting application.
     */
    public static final int NETWORK_BLOCKED = 7;

    /**
     * For intents used to notify the user that a download exceeds a size threshold, if this extra
     * is true, WiFi is required for this download size; otherwise, it is only recommended.
     */
    public static final String EXTRA_IS_WIFI_REQUIRED = "isWifiRequired";


    public long mId;
    public String mUri;
    public boolean mNoIntegrity;
    public String mHint;
    public String mFileName;
    public String mMimeType;
    public int mDestination;
    public int mVisibility;
    public int mControl;
    public int mStatus;
    public int mNumFailed;
    public int mRetryAfter;
    public long mLastMod;
    public String mPackage;
    public String mClass;
    public String mExtras;
    public String mCookies;
    public String mUserAgent;
    public String mReferer;
    public long mTotalBytes;
    public long mCurrentBytes;
    public String mETag;
    public int mUid;
    public int mMediaScanned;
    public boolean mDeleted;
    public String mMediaProviderUri;
    public boolean mIsPublicApi;
    public int mAllowedNetworkTypes;
    public boolean mAllowRoaming;
    public String mTitle;
    public String mDescription;
    public int mBypassRecommendedSizeLimit;

    public int mFuzz;

    private List<Pair<String, String>> mRequestHeaders = new ArrayList<Pair<String, String>>();
    private SystemFacade mSystemFacade;
    private Context mContext;

    private DownloadInfo(Context context, SystemFacade systemFacade) {
        mContext = context;
        mSystemFacade = systemFacade;
        mFuzz = Helpers.sRandom.nextInt(1001);
    }

    public Collection<Pair<String, String>> getHeaders() {
        return Collections.unmodifiableList(mRequestHeaders);
    }

    public void sendIntentIfRequested() {
        if (mPackage == null) {
            return;
        }

        Intent intent;
        if (mIsPublicApi) {
            intent = new Intent(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
            intent.setPackage(mPackage);
            intent.putExtra(DownloadManager.EXTRA_DOWNLOAD_ID, mId);
        } else { // legacy behavior
            if (mClass == null) {
                return;
            }
            intent = new Intent(Downloads.Impl.ACTION_DOWNLOAD_COMPLETED);
            intent.setClassName(mPackage, mClass);
            if (mExtras != null) {
                intent.putExtra(Downloads.Impl.COLUMN_NOTIFICATION_EXTRAS, mExtras);
            }
            // We only send the content: URI, for security reasons. Otherwise, malicious
            //     applications would have an easier time spoofing download results by
            //     sending spoofed intents.
            intent.setData(getMyDownloadsUri());
        }
        mSystemFacade.sendBroadcast(intent);
    }

    /**
     * Returns the time when a download should be restarted.
     */
    public long restartTime(long now) {
        if (mNumFailed == 0) {
            return now;
        }
        if (mRetryAfter > 0) {
            return mLastMod + mRetryAfter;
        }
        return mLastMod +
                Constants.RETRY_FIRST_DELAY *
                    (1000 + mFuzz) * (1 << (mNumFailed - 1));
    }

    /**
     * Returns whether this download (which the download manager hasn't seen yet)
     * should be started.
     */
    private boolean isReadyToStart(long now) {
        if (DownloadHandler.getInstance().hasDownloadInQueue(mId)) {
            // already running
            return false;
        }
        if (mControl == Downloads.Impl.CONTROL_PAUSED) {
            // the download is paused, so it's not going to start
            return false;
        }
        switch (mStatus) {
            case 0: // status hasn't been initialized yet, this is a new download
            case Downloads.Impl.STATUS_PENDING: // download is explicit marked as ready to start
            case Downloads.Impl.STATUS_RUNNING: // download interrupted (process killed etc) while
                                                // running, without a chance to update the database
                return true;

            case Downloads.Impl.STATUS_WAITING_FOR_NETWORK:
            case Downloads.Impl.STATUS_QUEUED_FOR_WIFI:
                return checkCanUseNetwork() == NETWORK_OK;

            case Downloads.Impl.STATUS_WAITING_TO_RETRY:
                // download was waiting for a delayed restart
                return restartTime(now) <= now;
            case Downloads.Impl.STATUS_DEVICE_NOT_FOUND_ERROR:
                // is the media mounted?
                return Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED);
            case Downloads.Impl.STATUS_INSUFFICIENT_SPACE_ERROR:
                // should check space to make sure it is worth retrying the download.
                // but thats the first thing done by the thread when it retries to download
                // it will fail pretty quickly if there is no space.
                // so, it is not that bad to skip checking space availability here.
                return true;
        }
        return false;
    }

    /**
     * Returns whether this download has a visible notification after
     * completion.
     */
    public boolean hasCompletionNotification() {
        if (!Downloads.Impl.isStatusCompleted(mStatus)) {
            return false;
        }
        if (mVisibility == Downloads.Impl.VISIBILITY_VISIBLE_NOTIFY_COMPLETED) {
            return true;
        }
        return false;
    }

    /**
     * Returns whether this download is allowed to use the network.
     * @return one of the NETWORK_* constants
     */
    public int checkCanUseNetwork() {
        final NetworkInfo info = mSystemFacade.getActiveNetworkInfo(mUid);
        if (info == null) {
            return NETWORK_NO_CONNECTION;
        }
        if (DetailedState.BLOCKED.equals(info.getDetailedState())) {
            return NETWORK_BLOCKED;
        }
        if (!isRoamingAllowed() && mSystemFacade.isNetworkRoaming()) {
            return NETWORK_CANNOT_USE_ROAMING;
        }
        return checkIsNetworkTypeAllowed(info.getType());
    }

    private boolean isRoamingAllowed() {
        if (mIsPublicApi) {
            return mAllowRoaming;
        } else { // legacy behavior
            return mDestination != Downloads.Impl.DESTINATION_CACHE_PARTITION_NOROAMING;
        }
    }

    /**
     * @return a non-localized string appropriate for logging corresponding to one of the
     * NETWORK_* constants.
     */
    public String getLogMessageForNetworkError(int networkError) {
        switch (networkError) {
            case NETWORK_RECOMMENDED_UNUSABLE_DUE_TO_SIZE:
                return "download size exceeds recommended limit for mobile network";

            case NETWORK_UNUSABLE_DUE_TO_SIZE:
                return "download size exceeds limit for mobile network";

            case NETWORK_NO_CONNECTION:
                return "no network connection available";

            case NETWORK_CANNOT_USE_ROAMING:
                return "download cannot use the current network connection because it is roaming";

            case NETWORK_TYPE_DISALLOWED_BY_REQUESTOR:
                return "download was requested to not use the current network type";

            case NETWORK_BLOCKED:
                return "network is blocked for requesting application";

            default:
                return "unknown error with network connectivity";
        }
    }

    /**
     * Check if this download can proceed over the given network type.
     * @param networkType a constant from ConnectivityManager.TYPE_*.
     * @return one of the NETWORK_* constants
     */
    private int checkIsNetworkTypeAllowed(int networkType) {
        if (mIsPublicApi) {
            int flag = translateNetworkTypeToApiFlag(networkType);
            if ((flag & mAllowedNetworkTypes) == 0) {
                return NETWORK_TYPE_DISALLOWED_BY_REQUESTOR;
            }
        }
        return checkSizeAllowedForNetwork(networkType);
    }

    /**
     * Translate a ConnectivityManager.TYPE_* constant to the corresponding
     * DownloadManager.Request.NETWORK_* bit flag.
     */
    private int translateNetworkTypeToApiFlag(int networkType) {
        switch (networkType) {
            case ConnectivityManager.TYPE_MOBILE:
                return DownloadManager.Request.NETWORK_MOBILE;

            case ConnectivityManager.TYPE_WIFI:
                return DownloadManager.Request.NETWORK_WIFI;

            default:
                return 0;
        }
    }

    /**
     * Check if the download's size prohibits it from running over the current network.
     * @return one of the NETWORK_* constants
     */
    private int checkSizeAllowedForNetwork(int networkType) {
        if (mTotalBytes <= 0) {
            return NETWORK_OK; // we don't know the size yet
        }
        if (networkType == ConnectivityManager.TYPE_WIFI) {
            return NETWORK_OK; // anything goes over wifi
        }
        Long maxBytesOverMobile = mSystemFacade.getMaxBytesOverMobile();
        if (maxBytesOverMobile != null && mTotalBytes > maxBytesOverMobile) {
            return NETWORK_UNUSABLE_DUE_TO_SIZE;
        }
        if (mBypassRecommendedSizeLimit == 0) {
            Long recommendedMaxBytesOverMobile = mSystemFacade.getRecommendedMaxBytesOverMobile();
            if (recommendedMaxBytesOverMobile != null
                    && mTotalBytes > recommendedMaxBytesOverMobile) {
                return NETWORK_RECOMMENDED_UNUSABLE_DUE_TO_SIZE;
            }
        }
        return NETWORK_OK;
    }

    void startIfReady(long now, StorageManager storageManager) {
        if (!isReadyToStart(now)) {
            return;
        }

        if (Constants.LOGV) {
            Log.v(Constants.TAG, "Service spawning thread to handle download " + mId);
        }
        if (mStatus != Impl.STATUS_RUNNING) {
            mStatus = Impl.STATUS_RUNNING;
            ContentValues values = new ContentValues();
            values.put(Impl.COLUMN_STATUS, mStatus);
            mContext.getContentResolver().update(getAllDownloadsUri(), values, null, null);
        }
        DownloadHandler.getInstance().enqueueDownload(this);
    }

    public boolean isOnCache() {
        return (mDestination == Downloads.Impl.DESTINATION_CACHE_PARTITION
                || mDestination == Downloads.Impl.DESTINATION_SYSTEMCACHE_PARTITION
                || mDestination == Downloads.Impl.DESTINATION_CACHE_PARTITION_NOROAMING
                || mDestination == Downloads.Impl.DESTINATION_CACHE_PARTITION_PURGEABLE);
    }

    public Uri getMyDownloadsUri() {
        return ContentUris.withAppendedId(Downloads.Impl.CONTENT_URI, mId);
    }

    public Uri getAllDownloadsUri() {
        return ContentUris.withAppendedId(Downloads.Impl.ALL_DOWNLOADS_CONTENT_URI, mId);
    }

    public void dump(PrintWriter writer) {
        writer.println("DownloadInfo:");

        writer.print("  mId="); writer.print(mId);
        writer.print(" mLastMod="); writer.print(mLastMod);
        writer.print(" mPackage="); writer.print(mPackage);
        writer.print(" mUid="); writer.println(mUid);

        writer.print("  mUri="); writer.print(mUri);
        writer.print(" mMimeType="); writer.print(mMimeType);
        writer.print(" mCookies="); writer.print((mCookies != null) ? "yes" : "no");
        writer.print(" mReferer="); writer.println((mReferer != null) ? "yes" : "no");

        writer.print("  mUserAgent="); writer.println(mUserAgent);

        writer.print("  mFileName="); writer.println(mFileName);

        writer.print("  mStatus="); writer.print(mStatus);
        writer.print(" mCurrentBytes="); writer.print(mCurrentBytes);
        writer.print(" mTotalBytes="); writer.println(mTotalBytes);

        writer.print("  mNumFailed="); writer.print(mNumFailed);
        writer.print(" mRetryAfter="); writer.println(mRetryAfter);
    }

    /**
     * Returns the amount of time (as measured from the "now" parameter)
     * at which a download will be active.
     * 0 = immediately - service should stick around to handle this download.
     * -1 = never - service can go away without ever waking up.
     * positive value - service must wake up in the future, as specified in ms from "now"
     */
    long nextAction(long now) {
        if (Downloads.Impl.isStatusCompleted(mStatus)) {
            return -1;
        }
        if (mStatus != Downloads.Impl.STATUS_WAITING_TO_RETRY) {
            return 0;
        }
        long when = restartTime(now);
        if (when <= now) {
            return 0;
        }
        return when - now;
    }

    /**
     * Returns whether a file should be scanned
     */
    boolean shouldScanFile() {
        return (mMediaScanned == 0)
                && (mDestination == Downloads.Impl.DESTINATION_EXTERNAL ||
                        mDestination == Downloads.Impl.DESTINATION_FILE_URI ||
                        mDestination == Downloads.Impl.DESTINATION_NON_DOWNLOADMANAGER_DOWNLOAD)
                && Downloads.Impl.isStatusSuccess(mStatus);
    }

    void notifyPauseDueToSize(boolean isWifiRequired) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setData(getAllDownloadsUri());
        intent.setClassName(SizeLimitActivity.class.getPackage().getName(),
                SizeLimitActivity.class.getName());
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(EXTRA_IS_WIFI_REQUIRED, isWifiRequired);
        mContext.startActivity(intent);
    }

    void startDownloadThread() {
        DownloadThread downloader = new DownloadThread(mContext, mSystemFacade, this,
                StorageManager.getInstance(mContext));
        mSystemFacade.startThread(downloader);
    }
}
