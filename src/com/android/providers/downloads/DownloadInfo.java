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

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.provider.Downloads;
import android.provider.Downloads.Impl;
import android.util.Log;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Stores information about an individual download.
 */
public class DownloadInfo {
    public int mId;
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
    public int mRedirectCount;
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
    public boolean mMediaScanned;

    public int mFuzz;

    public volatile boolean mHasActiveThread;

    private Map<String, String> mRequestHeaders = new HashMap<String, String>();
    private SystemFacade mSystemFacade;
    private Context mContext;

    public DownloadInfo(Context context, SystemFacade systemFacade, Cursor cursor) {
        mContext = context;
        mSystemFacade = systemFacade;

        int retryRedirect =
            cursor.getInt(cursor.getColumnIndexOrThrow(Constants.RETRY_AFTER_X_REDIRECT_COUNT));
        mId = cursor.getInt(cursor.getColumnIndexOrThrow(Downloads.Impl._ID));
        mUri = cursor.getString(cursor.getColumnIndexOrThrow(Downloads.Impl.COLUMN_URI));
        mNoIntegrity = cursor.getInt(cursor.getColumnIndexOrThrow(
                                        Downloads.Impl.COLUMN_NO_INTEGRITY)) == 1;
        mHint = cursor.getString(cursor.getColumnIndexOrThrow(
                                        Downloads.Impl.COLUMN_FILE_NAME_HINT));
        mFileName = cursor.getString(cursor.getColumnIndexOrThrow(Downloads.Impl._DATA));
        mMimeType = cursor.getString(cursor.getColumnIndexOrThrow(Downloads.Impl.COLUMN_MIME_TYPE));
        mDestination =
                cursor.getInt(cursor.getColumnIndexOrThrow(Downloads.Impl.COLUMN_DESTINATION));
        mVisibility = cursor.getInt(cursor.getColumnIndexOrThrow(Downloads.Impl.COLUMN_VISIBILITY));
        mControl = cursor.getInt(cursor.getColumnIndexOrThrow(Downloads.Impl.COLUMN_CONTROL));
        mStatus = cursor.getInt(cursor.getColumnIndexOrThrow(Downloads.Impl.COLUMN_STATUS));
        mNumFailed = cursor.getInt(cursor.getColumnIndexOrThrow(Constants.FAILED_CONNECTIONS));
        mRetryAfter = retryRedirect & 0xfffffff;
        mRedirectCount = retryRedirect >> 28;
        mLastMod = cursor.getLong(cursor.getColumnIndexOrThrow(
                                        Downloads.Impl.COLUMN_LAST_MODIFICATION));
        mPackage = cursor.getString(cursor.getColumnIndexOrThrow(
                                        Downloads.Impl.COLUMN_NOTIFICATION_PACKAGE));
        mClass = cursor.getString(cursor.getColumnIndexOrThrow(
                                        Downloads.Impl.COLUMN_NOTIFICATION_CLASS));
        mExtras = cursor.getString(cursor.getColumnIndexOrThrow(
                                        Downloads.Impl.COLUMN_NOTIFICATION_EXTRAS));
        mCookies =
                cursor.getString(cursor.getColumnIndexOrThrow(Downloads.Impl.COLUMN_COOKIE_DATA));
        mUserAgent =
                cursor.getString(cursor.getColumnIndexOrThrow(Downloads.Impl.COLUMN_USER_AGENT));
        mReferer = cursor.getString(cursor.getColumnIndexOrThrow(Downloads.Impl.COLUMN_REFERER));
        mTotalBytes =
                cursor.getInt(cursor.getColumnIndexOrThrow(Downloads.Impl.COLUMN_TOTAL_BYTES));
        mCurrentBytes =
                cursor.getInt(cursor.getColumnIndexOrThrow(Downloads.Impl.COLUMN_CURRENT_BYTES));
        mETag = cursor.getString(cursor.getColumnIndexOrThrow(Constants.ETAG));
        mMediaScanned = cursor.getInt(cursor.getColumnIndexOrThrow(Constants.MEDIA_SCANNED)) == 1;
        mFuzz = Helpers.sRandom.nextInt(1001);

        readRequestHeaders(mId);
    }

    private void readRequestHeaders(long downloadId) {
        Uri headerUri = Downloads.Impl.CONTENT_URI.buildUpon()
                        .appendPath(Long.toString(downloadId))
                        .appendPath(Downloads.Impl.RequestHeaders.URI_SEGMENT).build();
        Cursor cursor = mContext.getContentResolver().query(headerUri, null, null, null, null);
        try {
            int headerIndex =
                    cursor.getColumnIndexOrThrow(Downloads.Impl.RequestHeaders.COLUMN_HEADER);
            int valueIndex =
                    cursor.getColumnIndexOrThrow(Downloads.Impl.RequestHeaders.COLUMN_VALUE);
            for (cursor.moveToFirst(); !cursor.isAfterLast(); cursor.moveToNext()) {
                mRequestHeaders.put(cursor.getString(headerIndex), cursor.getString(valueIndex));
            }
        } finally {
            cursor.close();
        }

        if (mCookies != null) {
            mRequestHeaders.put("Cookie", mCookies);
        }
        if (mReferer != null) {
            mRequestHeaders.put("Referer", mReferer);
        }
    }

    public Map<String, String> getHeaders() {
        return Collections.unmodifiableMap(mRequestHeaders);
    }

    public void sendIntentIfRequested(Uri contentUri) {
        if (mPackage != null && mClass != null) {
            Intent intent = new Intent(Downloads.Impl.ACTION_DOWNLOAD_COMPLETED);
            intent.setClassName(mPackage, mClass);
            if (mExtras != null) {
                intent.putExtra(Downloads.Impl.COLUMN_NOTIFICATION_EXTRAS, mExtras);
            }
            // We only send the content: URI, for security reasons. Otherwise, malicious
            //     applications would have an easier time spoofing download results by
            //     sending spoofed intents.
            intent.setData(contentUri);
            mContext.sendBroadcast(intent);
        }
    }

    /**
     * Returns the time when a download should be restarted. Must only
     * be called when numFailed > 0.
     */
    public long restartTime() {
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
    public boolean isReadyToStart(long now) {
        if (mControl == Downloads.Impl.CONTROL_PAUSED) {
            // the download is paused, so it's not going to start
            return false;
        }
        if (mStatus == 0) {
            // status hasn't been initialized yet, this is a new download
            return true;
        }
        if (mStatus == Downloads.Impl.STATUS_PENDING) {
            // download is explicit marked as ready to start
            return true;
        }
        if (mStatus == Downloads.Impl.STATUS_RUNNING) {
            // download was interrupted (process killed, loss of power) while it was running,
            //     without a chance to update the database
            return true;
        }
        if (mStatus == Downloads.Impl.STATUS_RUNNING_PAUSED) {
            if (mNumFailed == 0) {
                // download is waiting for network connectivity to return before it can resume
                return true;
            }
            if (restartTime() < now) {
                // download was waiting for a delayed restart, and the delay has expired
                return true;
            }
        }
        return false;
    }

    /**
     * Returns whether this download (which the download manager has already seen
     * and therefore potentially started) should be restarted.
     *
     * In a nutshell, this returns true if the download isn't already running
     * but should be, and it can know whether the download is already running
     * by checking the status.
     */
    public boolean isReadyToRestart(long now) {
        if (mControl == Downloads.Impl.CONTROL_PAUSED) {
            // the download is paused, so it's not going to restart
            return false;
        }
        if (mStatus == 0) {
            // download hadn't been initialized yet
            return true;
        }
        if (mStatus == Downloads.Impl.STATUS_PENDING) {
            // download is explicit marked as ready to start
            return true;
        }
        if (mStatus == Downloads.Impl.STATUS_RUNNING_PAUSED) {
            if (mNumFailed == 0) {
                // download is waiting for network connectivity to return before it can resume
                return true;
            }
            if (restartTime() < now) {
                // download was waiting for a delayed restart, and the delay has expired
                return true;
            }
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
     */
    public boolean canUseNetwork() {
        Integer networkType = mSystemFacade.getActiveNetworkType();
        if (networkType == null) {
            return false;
        }
        if (!isSizeAllowedForNetwork(networkType)) {
            return false;
        }
        if (mDestination == Downloads.Impl.DESTINATION_CACHE_PARTITION_NOROAMING
                && mSystemFacade.isNetworkRoaming()) {
            return false;
        }
        return true;
    }

    /**
     * Check if the download's size prohibits it from running over the current network.
     */
    private boolean isSizeAllowedForNetwork(int networkType) {
        if (mTotalBytes <= 0) {
            return true; // we don't know the size yet
        }
        if (networkType == ConnectivityManager.TYPE_WIFI) {
            return true; // anything goes over wifi
        }
        Integer maxBytesOverMobile = mSystemFacade.getMaxBytesOverMobile();
        if (maxBytesOverMobile == null) {
            return true; // no limit
        }
        return mTotalBytes <= maxBytesOverMobile;
    }

    void start(long now) {
        if (Constants.LOGV) {
            Log.v(Constants.TAG, "Service spawning thread to handle download " + mId);
        }
        if (mHasActiveThread) {
            throw new IllegalStateException("Multiple threads on same download");
        }
        if (mStatus != Impl.STATUS_RUNNING) {
            mStatus = Impl.STATUS_RUNNING;
            ContentValues values = new ContentValues();
            values.put(Impl.COLUMN_STATUS, mStatus);
            mContext.getContentResolver().update(
                    ContentUris.withAppendedId(Impl.CONTENT_URI, mId),
                    values, null, null);
        }
        DownloadThread downloader = new DownloadThread(mContext, mSystemFacade, this);
        mHasActiveThread = true;
        downloader.start();
    }
}
