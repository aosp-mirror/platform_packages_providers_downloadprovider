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

import static android.provider.Downloads.Impl.VISIBILITY_VISIBLE;
import static android.provider.Downloads.Impl.VISIBILITY_VISIBLE_NOTIFY_COMPLETED;

import static com.android.providers.downloads.Constants.TAG;

import android.app.DownloadManager;
import android.app.job.JobInfo;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.Downloads;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.Log;
import android.util.Pair;

import com.android.internal.util.IndentingPrintWriter;

import java.io.CharArrayWriter;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Details about a specific download. Fields should only be mutated by updating
 * from database query.
 */
public class DownloadInfo {
    // TODO: move towards these in-memory objects being sources of truth, and
    // periodically pushing to provider.

    public static class Reader {
        private ContentResolver mResolver;
        private Cursor mCursor;

        public Reader(ContentResolver resolver, Cursor cursor) {
            mResolver = resolver;
            mCursor = cursor;
        }

        public void updateFromDatabase(DownloadInfo info) {
            info.mId = getLong(Downloads.Impl._ID);
            info.mUri = getString(Downloads.Impl.COLUMN_URI);
            info.mNoIntegrity = getInt(Downloads.Impl.COLUMN_NO_INTEGRITY) == 1;
            info.mHint = getString(Downloads.Impl.COLUMN_FILE_NAME_HINT);
            info.mFileName = getString(Downloads.Impl._DATA);
            info.mMimeType = Intent.normalizeMimeType(getString(Downloads.Impl.COLUMN_MIME_TYPE));
            info.mDestination = getInt(Downloads.Impl.COLUMN_DESTINATION);
            info.mVisibility = getInt(Downloads.Impl.COLUMN_VISIBILITY);
            info.mStatus = getInt(Downloads.Impl.COLUMN_STATUS);
            info.mNumFailed = getInt(Downloads.Impl.COLUMN_FAILED_CONNECTIONS);
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
            info.mMediaScanned = getInt(Downloads.Impl.COLUMN_MEDIA_SCANNED);
            info.mDeleted = getInt(Downloads.Impl.COLUMN_DELETED) == 1;
            info.mMediaProviderUri = getString(Downloads.Impl.COLUMN_MEDIAPROVIDER_URI);
            info.mIsPublicApi = getInt(Downloads.Impl.COLUMN_IS_PUBLIC_API) != 0;
            info.mAllowedNetworkTypes = getInt(Downloads.Impl.COLUMN_ALLOWED_NETWORK_TYPES);
            info.mAllowRoaming = getInt(Downloads.Impl.COLUMN_ALLOW_ROAMING) != 0;
            info.mAllowMetered = getInt(Downloads.Impl.COLUMN_ALLOW_METERED) != 0;
            info.mFlags = getInt(Downloads.Impl.COLUMN_FLAGS);
            info.mTitle = getString(Downloads.Impl.COLUMN_TITLE);
            info.mDescription = getString(Downloads.Impl.COLUMN_DESCRIPTION);
            info.mBypassRecommendedSizeLimit =
                    getInt(Downloads.Impl.COLUMN_BYPASS_RECOMMENDED_SIZE_LIMIT);

            synchronized (this) {
                info.mControl = getInt(Downloads.Impl.COLUMN_CONTROL);
            }
        }

        public void readRequestHeaders(DownloadInfo info) {
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

    public long mId;
    public String mUri;
    @Deprecated
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
    public boolean mAllowMetered;
    public int mFlags;
    public String mTitle;
    public String mDescription;
    public int mBypassRecommendedSizeLimit;

    private List<Pair<String, String>> mRequestHeaders = new ArrayList<Pair<String, String>>();

    private final Context mContext;
    private final SystemFacade mSystemFacade;

    public DownloadInfo(Context context) {
        mContext = context;
        mSystemFacade = Helpers.getSystemFacade(context);
    }

    public static DownloadInfo queryDownloadInfo(Context context, long downloadId) {
        final ContentResolver resolver = context.getContentResolver();
        try (Cursor cursor = resolver.query(
                ContentUris.withAppendedId(Downloads.Impl.ALL_DOWNLOADS_CONTENT_URI, downloadId),
                null, null, null, null)) {
            final DownloadInfo.Reader reader = new DownloadInfo.Reader(resolver, cursor);
            final DownloadInfo info = new DownloadInfo(context);
            if (cursor.moveToFirst()) {
                reader.updateFromDatabase(info);
                reader.readRequestHeaders(info);
                return info;
            }
        }
        return null;
    }

    public Collection<Pair<String, String>> getHeaders() {
        return Collections.unmodifiableList(mRequestHeaders);
    }

    public String getUserAgent() {
        if (mUserAgent != null) {
            return mUserAgent;
        } else {
            return Constants.DEFAULT_USER_AGENT;
        }
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
     * Return if this download is visible to the user while running.
     */
    public boolean isVisible() {
        switch (mVisibility) {
            case VISIBILITY_VISIBLE:
            case VISIBILITY_VISIBLE_NOTIFY_COMPLETED:
                return true;
            default:
                return false;
        }
    }

    /**
     * Add random fuzz to the given delay so it's anywhere between 1-1.5x the
     * requested delay.
     */
    private long fuzzDelay(long delay) {
        return delay + Helpers.sRandom.nextInt((int) (delay / 2));
    }

    /**
     * Return minimum latency in milliseconds required before this download is
     * allowed to start again.
     *
     * @see android.app.job.JobInfo.Builder#setMinimumLatency(long)
     */
    public long getMinimumLatency() {
        if (mStatus == Downloads.Impl.STATUS_WAITING_TO_RETRY) {
            final long now = mSystemFacade.currentTimeMillis();
            final long startAfter;
            if (mNumFailed == 0) {
                startAfter = now;
            } else if (mRetryAfter > 0) {
                startAfter = mLastMod + fuzzDelay(mRetryAfter);
            } else {
                final long delay = (Constants.RETRY_FIRST_DELAY * DateUtils.SECOND_IN_MILLIS
                        * (1 << (mNumFailed - 1)));
                startAfter = mLastMod + fuzzDelay(delay);
            }
            return Math.max(0, startAfter - now);
        } else {
            return 0;
        }
    }

    /**
     * Return the network type constraint required by this download.
     *
     * @see android.app.job.JobInfo.Builder#setRequiredNetworkType(int)
     */
    public int getRequiredNetworkType(long totalBytes) {
        if (!mAllowMetered) {
            return JobInfo.NETWORK_TYPE_UNMETERED;
        }
        if (mAllowedNetworkTypes == DownloadManager.Request.NETWORK_WIFI) {
            return JobInfo.NETWORK_TYPE_UNMETERED;
        }
        if (totalBytes > mSystemFacade.getMaxBytesOverMobile()) {
            return JobInfo.NETWORK_TYPE_UNMETERED;
        }
        if (totalBytes > mSystemFacade.getRecommendedMaxBytesOverMobile()
                && mBypassRecommendedSizeLimit == 0) {
            return JobInfo.NETWORK_TYPE_UNMETERED;
        }
        if (!mAllowRoaming) {
            return JobInfo.NETWORK_TYPE_NOT_ROAMING;
        }
        return JobInfo.NETWORK_TYPE_ANY;
    }

    /**
     * Returns whether this download is ready to be scheduled.
     */
    public boolean isReadyToSchedule() {
        if (mControl == Downloads.Impl.CONTROL_PAUSED) {
            // the download is paused, so it's not going to start
            return false;
        }
        switch (mStatus) {
            case 0:
            case Downloads.Impl.STATUS_PENDING:
            case Downloads.Impl.STATUS_RUNNING:
            case Downloads.Impl.STATUS_WAITING_FOR_NETWORK:
            case Downloads.Impl.STATUS_WAITING_TO_RETRY:
            case Downloads.Impl.STATUS_QUEUED_FOR_WIFI:
                return true;

            case Downloads.Impl.STATUS_DEVICE_NOT_FOUND_ERROR:
                // is the media mounted?
                final Uri uri = Uri.parse(mUri);
                if (ContentResolver.SCHEME_FILE.equals(uri.getScheme())) {
                    final File file = new File(uri.getPath());
                    return Environment.MEDIA_MOUNTED
                            .equals(Environment.getExternalStorageState(file));
                } else {
                    Log.w(TAG, "Expected file URI on external storage: " + mUri);
                    return false;
                }

            default:
                return false;
        }
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

    public boolean isMeteredAllowed(long totalBytes) {
        return getRequiredNetworkType(totalBytes) != JobInfo.NETWORK_TYPE_UNMETERED;
    }

    public boolean isRoamingAllowed() {
        if (mIsPublicApi) {
            return mAllowRoaming;
        } else { // legacy behavior
            return mDestination != Downloads.Impl.DESTINATION_CACHE_PARTITION_NOROAMING;
        }
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

    @Override
    public String toString() {
        final CharArrayWriter writer = new CharArrayWriter();
        dump(new IndentingPrintWriter(writer, "  "));
        return writer.toString();
    }

    public void dump(IndentingPrintWriter pw) {
        pw.println("DownloadInfo:");
        pw.increaseIndent();

        pw.printPair("mId", mId);
        pw.printPair("mLastMod", mLastMod);
        pw.printPair("mPackage", mPackage);
        pw.printPair("mUid", mUid);
        pw.println();

        pw.printPair("mUri", mUri);
        pw.println();

        pw.printPair("mMimeType", mMimeType);
        pw.printPair("mCookies", (mCookies != null) ? "yes" : "no");
        pw.printPair("mReferer", (mReferer != null) ? "yes" : "no");
        pw.printPair("mUserAgent", mUserAgent);
        pw.println();

        pw.printPair("mFileName", mFileName);
        pw.printPair("mDestination", mDestination);
        pw.println();

        pw.printPair("mStatus", Downloads.Impl.statusToString(mStatus));
        pw.printPair("mCurrentBytes", mCurrentBytes);
        pw.printPair("mTotalBytes", mTotalBytes);
        pw.println();

        pw.printPair("mNumFailed", mNumFailed);
        pw.printPair("mRetryAfter", mRetryAfter);
        pw.printPair("mETag", mETag);
        pw.printPair("mIsPublicApi", mIsPublicApi);
        pw.println();

        pw.printPair("mAllowedNetworkTypes", mAllowedNetworkTypes);
        pw.printPair("mAllowRoaming", mAllowRoaming);
        pw.printPair("mAllowMetered", mAllowMetered);
        pw.printPair("mFlags", mFlags);
        pw.println();

        pw.decreaseIndent();
    }

    /**
     * Returns whether a file should be scanned
     */
    public boolean shouldScanFile(int status) {
        return (mMediaScanned == 0)
                && (mDestination == Downloads.Impl.DESTINATION_EXTERNAL ||
                        mDestination == Downloads.Impl.DESTINATION_FILE_URI ||
                        mDestination == Downloads.Impl.DESTINATION_NON_DOWNLOADMANAGER_DOWNLOAD)
                && Downloads.Impl.isStatusSuccess(status);
    }

    /**
     * Query and return status of requested download.
     */
    public int queryDownloadStatus() {
        return queryDownloadInt(Downloads.Impl.COLUMN_STATUS, Downloads.Impl.STATUS_PENDING);
    }

    public int queryDownloadControl() {
        return queryDownloadInt(Downloads.Impl.COLUMN_CONTROL, Downloads.Impl.CONTROL_RUN);
    }

    public int queryDownloadInt(String columnName, int defaultValue) {
        try (Cursor cursor = mContext.getContentResolver().query(getAllDownloadsUri(),
                new String[] { columnName }, null, null, null)) {
            if (cursor.moveToFirst()) {
                return cursor.getInt(0);
            } else {
                return defaultValue;
            }
        }
    }
}
