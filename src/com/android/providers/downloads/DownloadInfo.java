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

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.Downloads;

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
    public int mTotalBytes;
    public int mCurrentBytes;
    public String mETag;
    public boolean mMediaScanned;

    public int mFuzz;

    public volatile boolean mHasActiveThread;
    private Map<String, String> mRequestHeaders = new HashMap<String, String>();

    public DownloadInfo(ContentResolver resolver, Cursor cursor) {
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

        readRequestHeaders(resolver, mId);
    }

    private void readRequestHeaders(ContentResolver resolver, long downloadId) {
        Uri headerUri = Downloads.Impl.CONTENT_URI.buildUpon()
                        .appendPath(Long.toString(downloadId))
                        .appendPath(Downloads.Impl.RequestHeaders.URI_SEGMENT).build();
        Cursor cursor = resolver.query(headerUri, null, null, null, null);
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

    public void sendIntentIfRequested(Uri contentUri, Context context) {
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
            context.sendBroadcast(intent);
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
    public boolean canUseNetwork(boolean available, boolean roaming) {
        if (!available) {
            return false;
        }
        if (mDestination == Downloads.Impl.DESTINATION_CACHE_PARTITION_NOROAMING) {
            return !roaming;
        } else {
            return true;
        }
    }
}
