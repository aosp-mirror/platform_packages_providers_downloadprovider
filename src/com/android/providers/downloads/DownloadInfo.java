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

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Downloads;

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

    public DownloadInfo(int id, String uri, boolean noIntegrity,
            String hint, String fileName,
            String mimeType, int destination, int visibility, int control,
            int status, int numFailed, int retryAfter, int redirectCount, long lastMod,
            String pckg, String clazz, String extras, String cookies,
            String userAgent, String referer, int totalBytes, int currentBytes, String eTag,
            boolean mediaScanned) {
        mId = id;
        mUri = uri;
        mNoIntegrity = noIntegrity;
        mHint = hint;
        mFileName = fileName;
        mMimeType = mimeType;
        mDestination = destination;
        mVisibility = visibility;
        mControl = control;
        mStatus = status;
        mNumFailed = numFailed;
        mRetryAfter = retryAfter;
        mRedirectCount = redirectCount;
        mLastMod = lastMod;
        mPackage = pckg;
        mClass = clazz;
        mExtras = extras;
        mCookies = cookies;
        mUserAgent = userAgent;
        mReferer = referer;
        mTotalBytes = totalBytes;
        mCurrentBytes = currentBytes;
        mETag = eTag;
        mMediaScanned = mediaScanned;
        mFuzz = Helpers.sRandom.nextInt(1001); 
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
