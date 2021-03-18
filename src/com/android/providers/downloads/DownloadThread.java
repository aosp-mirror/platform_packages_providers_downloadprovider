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

import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING;
import static android.provider.Downloads.Impl.COLUMN_CONTROL;
import static android.provider.Downloads.Impl.COLUMN_DELETED;
import static android.provider.Downloads.Impl.COLUMN_STATUS;
import static android.provider.Downloads.Impl.CONTROL_PAUSED;
import static android.provider.Downloads.Impl.STATUS_BAD_REQUEST;
import static android.provider.Downloads.Impl.STATUS_CANCELED;
import static android.provider.Downloads.Impl.STATUS_CANNOT_RESUME;
import static android.provider.Downloads.Impl.STATUS_FILE_ERROR;
import static android.provider.Downloads.Impl.STATUS_HTTP_DATA_ERROR;
import static android.provider.Downloads.Impl.STATUS_INSUFFICIENT_SPACE_ERROR;
import static android.provider.Downloads.Impl.STATUS_PAUSED_BY_APP;
import static android.provider.Downloads.Impl.STATUS_QUEUED_FOR_WIFI;
import static android.provider.Downloads.Impl.STATUS_RUNNING;
import static android.provider.Downloads.Impl.STATUS_SUCCESS;
import static android.provider.Downloads.Impl.STATUS_TOO_MANY_REDIRECTS;
import static android.provider.Downloads.Impl.STATUS_UNHANDLED_HTTP_CODE;
import static android.provider.Downloads.Impl.STATUS_UNKNOWN_ERROR;
import static android.provider.Downloads.Impl.STATUS_WAITING_FOR_NETWORK;
import static android.provider.Downloads.Impl.STATUS_WAITING_TO_RETRY;
import static android.text.format.DateUtils.SECOND_IN_MILLIS;

import static com.android.providers.downloads.Constants.TAG;

import static java.net.HttpURLConnection.HTTP_INTERNAL_ERROR;
import static java.net.HttpURLConnection.HTTP_MOVED_PERM;
import static java.net.HttpURLConnection.HTTP_MOVED_TEMP;
import static java.net.HttpURLConnection.HTTP_OK;
import static java.net.HttpURLConnection.HTTP_PARTIAL;
import static java.net.HttpURLConnection.HTTP_PRECON_FAILED;
import static java.net.HttpURLConnection.HTTP_SEE_OTHER;
import static java.net.HttpURLConnection.HTTP_UNAVAILABLE;

import android.app.job.JobParameters;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.drm.DrmManagerClient;
import android.drm.DrmOutputStream;
import android.net.ConnectivityManager;
import android.net.INetworkPolicyListener;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkPolicyManager;
import android.net.TrafficStats;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import android.os.storage.StorageManager;
import android.provider.Downloads;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;
import android.util.MathUtils;
import android.util.Pair;

import libcore.io.IoUtils;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URL;
import java.net.URLConnection;
import java.security.GeneralSecurityException;
import java.util.Arrays;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;

/**
 * Task which executes a given {@link DownloadInfo}: making network requests,
 * persisting data to disk, and updating {@link DownloadProvider}.
 * <p>
 * To know if a download is successful, we need to know either the final content
 * length to expect, or the transfer to be chunked. To resume an interrupted
 * download, we need an ETag.
 * <p>
 * Failed network requests are retried several times before giving up. Local
 * disk errors fail immediately and are not retried.
 */
public class DownloadThread extends Thread {

    // TODO: bind each download to a specific network interface to avoid state
    // checking races once we have ConnectivityManager API

    // TODO: add support for saving to content://

    private static final int HTTP_REQUESTED_RANGE_NOT_SATISFIABLE = 416;
    private static final int HTTP_TEMP_REDIRECT = 307;

    private static final int DEFAULT_TIMEOUT = (int) (20 * SECOND_IN_MILLIS);

    private final Context mContext;
    private final SystemFacade mSystemFacade;
    private final DownloadNotifier mNotifier;
    private final NetworkPolicyManager mNetworkPolicy;
    private final StorageManager mStorage;

    private final DownloadJobService mJobService;
    private final JobParameters mParams;

    private final long mId;

    /**
     * Info object that should be treated as read-only. Any potentially mutated
     * fields are tracked in {@link #mInfoDelta}. If a field exists in
     * {@link #mInfoDelta}, it must not be read from {@link #mInfo}.
     */
    private final DownloadInfo mInfo;
    private final DownloadInfoDelta mInfoDelta;

    private volatile boolean mPolicyDirty;

    /**
     * Local changes to {@link DownloadInfo}. These are kept local to avoid
     * racing with the thread that updates based on change notifications.
     */
    private class DownloadInfoDelta {
        public String mUri;
        public String mFileName;
        public String mMimeType;
        public int mStatus;
        public int mNumFailed;
        public int mRetryAfter;
        public long mTotalBytes;
        public long mCurrentBytes;
        public String mETag;

        public String mErrorMsg;

        private static final String NOT_CANCELED = COLUMN_STATUS + " != '" + STATUS_CANCELED + "'";
        private static final String NOT_DELETED = COLUMN_DELETED + " == '0'";
        private static final String NOT_PAUSED = "(" + COLUMN_CONTROL + " IS NULL OR "
                + COLUMN_CONTROL + " != '" + CONTROL_PAUSED + "')";

        private static final String SELECTION_VALID = NOT_CANCELED + " AND " + NOT_DELETED + " AND "
                + NOT_PAUSED;

        public DownloadInfoDelta(DownloadInfo info) {
            mUri = info.mUri;
            mFileName = info.mFileName;
            mMimeType = info.mMimeType;
            mStatus = info.mStatus;
            mNumFailed = info.mNumFailed;
            mRetryAfter = info.mRetryAfter;
            mTotalBytes = info.mTotalBytes;
            mCurrentBytes = info.mCurrentBytes;
            mETag = info.mETag;
        }

        private ContentValues buildContentValues() {
            final ContentValues values = new ContentValues();

            values.put(Downloads.Impl.COLUMN_URI, mUri);
            values.put(Downloads.Impl._DATA, mFileName);
            values.put(Downloads.Impl.COLUMN_MIME_TYPE, mMimeType);
            values.put(Downloads.Impl.COLUMN_STATUS, mStatus);
            values.put(Downloads.Impl.COLUMN_FAILED_CONNECTIONS, mNumFailed);
            values.put(Constants.RETRY_AFTER_X_REDIRECT_COUNT, mRetryAfter);
            values.put(Downloads.Impl.COLUMN_TOTAL_BYTES, mTotalBytes);
            values.put(Downloads.Impl.COLUMN_CURRENT_BYTES, mCurrentBytes);
            values.put(Constants.ETAG, mETag);

            values.put(Downloads.Impl.COLUMN_LAST_MODIFICATION, mSystemFacade.currentTimeMillis());
            values.put(Downloads.Impl.COLUMN_ERROR_MSG, mErrorMsg);

            return values;
        }

        /**
         * Blindly push update of current delta values to provider.
         */
        public void writeToDatabase() {
            mContext.getContentResolver().update(mInfo.getAllDownloadsUri(), buildContentValues(),
                    null, null);
        }

        /**
         * Push update of current delta values to provider, asserting strongly
         * that we haven't been paused or deleted.
         */
        public void writeToDatabaseOrThrow() throws StopRequestException {
            if (mContext.getContentResolver().update(mInfo.getAllDownloadsUri(),
                    buildContentValues(), SELECTION_VALID, null) == 0) {
                if (mInfo.queryDownloadControl() == CONTROL_PAUSED) {
                    throw new StopRequestException(STATUS_PAUSED_BY_APP, "Download paused!");
                } else {
                    throw new StopRequestException(STATUS_CANCELED, "Download deleted or missing!");
                }
            }
        }
    }

    /**
     * Flag indicating if we've made forward progress transferring file data
     * from a remote server.
     */
    private boolean mMadeProgress = false;

    /**
     * Details from the last time we pushed a database update.
     */
    private long mLastUpdateBytes = 0;
    private long mLastUpdateTime = 0;

    private boolean mIgnoreBlocked;
    private Network mNetwork;

    /** Historical bytes/second speed of this download. */
    private long mSpeed;
    /** Time when current sample started. */
    private long mSpeedSampleStart;
    /** Bytes transferred since current sample started. */
    private long mSpeedSampleBytes;

    /** Flag indicating that thread must be halted */
    private volatile boolean mShutdownRequested;

    public DownloadThread(DownloadJobService service, JobParameters params, DownloadInfo info) {
        mContext = service;
        mSystemFacade = Helpers.getSystemFacade(mContext);
        mNotifier = Helpers.getDownloadNotifier(mContext);
        mNetworkPolicy = mContext.getSystemService(NetworkPolicyManager.class);
        mStorage = mContext.getSystemService(StorageManager.class);

        mJobService = service;
        mParams = params;

        mId = info.mId;
        mInfo = info;
        mInfoDelta = new DownloadInfoDelta(info);
    }

    @Override
    public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);

        // Skip when download already marked as finished; this download was
        // probably started again while racing with UpdateThread.
        if (mInfo.queryDownloadStatus() == Downloads.Impl.STATUS_SUCCESS) {
            logDebug("Already finished; skipping");
            return;
        }

        try {
            // while performing download, register for rules updates
            mNetworkPolicy.registerListener(mPolicyListener);

            logDebug("Starting");

            mInfoDelta.mStatus = STATUS_RUNNING;
            mInfoDelta.writeToDatabase();

            // If we're showing a foreground notification for the requesting
            // app, the download isn't affected by the blocked status of the
            // requesting app
            mIgnoreBlocked = mInfo.isVisible();

            // Use the caller's default network to make this connection, since
            // they might be subject to restrictions that we shouldn't let them
            // circumvent
            mNetwork = mSystemFacade.getNetwork(mParams);
            if (mNetwork == null) {
                throw new StopRequestException(STATUS_WAITING_FOR_NETWORK,
                        "No network associated with requesting UID");
            }

            // Network traffic on this thread should be counted against the
            // requesting UID, and is tagged with well-known value.
            TrafficStats.setThreadStatsTag(TrafficStats.TAG_SYSTEM_DOWNLOAD);
            TrafficStats.setThreadStatsUid(mInfo.mUid);

            executeDownload();

            mInfoDelta.mStatus = STATUS_SUCCESS;
            TrafficStats.incrementOperationCount(1);

            // If we just finished a chunked file, record total size
            if (mInfoDelta.mTotalBytes == -1) {
                mInfoDelta.mTotalBytes = mInfoDelta.mCurrentBytes;
            }

        } catch (StopRequestException e) {
            mInfoDelta.mStatus = e.getFinalStatus();
            mInfoDelta.mErrorMsg = e.getMessage();

            logWarning("Stop requested with status "
                    + Downloads.Impl.statusToString(mInfoDelta.mStatus) + ": "
                    + mInfoDelta.mErrorMsg);

            // Nobody below our level should request retries, since we handle
            // failure counts at this level.
            if (mInfoDelta.mStatus == STATUS_WAITING_TO_RETRY) {
                throw new IllegalStateException("Execution should always throw final error codes");
            }

            // Some errors should be retryable, unless we fail too many times.
            if (isStatusRetryable(mInfoDelta.mStatus)) {
                if (mMadeProgress) {
                    mInfoDelta.mNumFailed = 1;
                } else {
                    mInfoDelta.mNumFailed += 1;
                }

                if (mInfoDelta.mNumFailed < Constants.MAX_RETRIES) {
                    if (null != mSystemFacade.getNetworkCapabilities(mNetwork)) {
                        // Underlying network is still intact, use normal backoff
                        mInfoDelta.mStatus = STATUS_WAITING_TO_RETRY;
                    } else {
                        // Network unavailable, retry on any next available
                        mInfoDelta.mStatus = STATUS_WAITING_FOR_NETWORK;
                    }

                    if ((mInfoDelta.mETag == null && mMadeProgress)
                            || DownloadDrmHelper.isDrmConvertNeeded(mInfoDelta.mMimeType)) {
                        // However, if we wrote data and have no ETag to verify
                        // contents against later, we can't actually resume.
                        mInfoDelta.mStatus = STATUS_CANNOT_RESUME;
                    }
                }
            }

            // If we're waiting for a network that must be unmetered, our status
            // is actually queued so we show relevant notifications
            if (mInfoDelta.mStatus == STATUS_WAITING_FOR_NETWORK
                    && !mInfo.isMeteredAllowed(mInfoDelta.mTotalBytes)) {
                mInfoDelta.mStatus = STATUS_QUEUED_FOR_WIFI;
            }

        } catch (Throwable t) {
            mInfoDelta.mStatus = STATUS_UNKNOWN_ERROR;
            mInfoDelta.mErrorMsg = t.toString();

            logError("Failed: " + mInfoDelta.mErrorMsg, t);

        } finally {
            logDebug("Finished with status " + Downloads.Impl.statusToString(mInfoDelta.mStatus));

            mNotifier.notifyDownloadSpeed(mId, 0);

            finalizeDestination();

            mInfoDelta.writeToDatabase();

            TrafficStats.clearThreadStatsTag();
            TrafficStats.clearThreadStatsUid();

            mNetworkPolicy.unregisterListener(mPolicyListener);
        }

        boolean needsReschedule = false;
        if (mInfoDelta.mStatus == STATUS_WAITING_TO_RETRY
                || mInfoDelta.mStatus == STATUS_WAITING_FOR_NETWORK
                || mInfoDelta.mStatus == STATUS_QUEUED_FOR_WIFI) {
            needsReschedule = true;
        }

        mJobService.jobFinishedInternal(mParams, needsReschedule);
    }

    public void requestShutdown() {
        mShutdownRequested = true;
    }

    /**
     * Fully execute a single download request. Setup and send the request,
     * handle the response, and transfer the data to the destination file.
     */
    private void executeDownload() throws StopRequestException {
        final boolean resuming = mInfoDelta.mCurrentBytes != 0;

        URL url;
        try {
            // TODO: migrate URL sanity checking into client side of API
            url = new URL(mInfoDelta.mUri);
        } catch (MalformedURLException e) {
            throw new StopRequestException(STATUS_BAD_REQUEST, e);
        }

        boolean cleartextTrafficPermitted
                = mSystemFacade.isCleartextTrafficPermitted(mInfo.mPackage, url.getHost());
        SSLContext appContext;
        try {
            appContext = mSystemFacade.getSSLContextForPackage(mContext, mInfo.mPackage);
        } catch (GeneralSecurityException e) {
            // This should never happen.
            throw new StopRequestException(STATUS_UNKNOWN_ERROR, "Unable to create SSLContext.");
        }
        int redirectionCount = 0;
        while (redirectionCount++ < Constants.MAX_REDIRECTS) {
            // Enforce the cleartext traffic opt-out for the UID. This cannot be enforced earlier
            // because of HTTP redirects which can change the protocol between HTTP and HTTPS.
            if ((!cleartextTrafficPermitted) && ("http".equalsIgnoreCase(url.getProtocol()))) {
                throw new StopRequestException(STATUS_BAD_REQUEST,
                        "Cleartext traffic not permitted for package " + mInfo.mPackage + ": "
                        + Uri.parse(url.toString()).toSafeString());
            }

            // Open connection and follow any redirects until we have a useful
            // response with body.
            HttpURLConnection conn = null;
            try {
                // Check that the caller is allowed to make network connections. If so, make one on
                // their behalf to open the url.
                checkConnectivity();
                conn = (HttpURLConnection) mNetwork.openConnection(url);
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(DEFAULT_TIMEOUT);
                conn.setReadTimeout(DEFAULT_TIMEOUT);
                // If this is going over HTTPS configure the trust to be the same as the calling
                // package.
                if (conn instanceof HttpsURLConnection) {
                    ((HttpsURLConnection)conn).setSSLSocketFactory(appContext.getSocketFactory());
                }

                addRequestHeaders(conn, resuming);

                final int responseCode = conn.getResponseCode();
                switch (responseCode) {
                    case HTTP_OK:
                        if (resuming) {
                            throw new StopRequestException(
                                    STATUS_CANNOT_RESUME, "Expected partial, but received OK");
                        }
                        parseOkHeaders(conn);
                        transferData(conn);
                        return;

                    case HTTP_PARTIAL:
                        if (!resuming) {
                            throw new StopRequestException(
                                    STATUS_CANNOT_RESUME, "Expected OK, but received partial");
                        }
                        transferData(conn);
                        return;

                    case HTTP_MOVED_PERM:
                    case HTTP_MOVED_TEMP:
                    case HTTP_SEE_OTHER:
                    case HTTP_TEMP_REDIRECT:
                        final String location = conn.getHeaderField("Location");
                        url = new URL(url, location);
                        if (responseCode == HTTP_MOVED_PERM) {
                            // Push updated URL back to database
                            mInfoDelta.mUri = url.toString();
                        }
                        continue;

                    case HTTP_PRECON_FAILED:
                        throw new StopRequestException(
                                STATUS_CANNOT_RESUME, "Precondition failed");

                    case HTTP_REQUESTED_RANGE_NOT_SATISFIABLE:
                        throw new StopRequestException(
                                STATUS_CANNOT_RESUME, "Requested range not satisfiable");

                    case HTTP_UNAVAILABLE:
                        parseUnavailableHeaders(conn);
                        throw new StopRequestException(
                                HTTP_UNAVAILABLE, conn.getResponseMessage());

                    case HTTP_INTERNAL_ERROR:
                        throw new StopRequestException(
                                HTTP_INTERNAL_ERROR, conn.getResponseMessage());

                    default:
                        StopRequestException.throwUnhandledHttpError(
                                responseCode, conn.getResponseMessage());
                }

            } catch (IOException e) {
                if (e instanceof ProtocolException
                        && e.getMessage().startsWith("Unexpected status line")) {
                    throw new StopRequestException(STATUS_UNHANDLED_HTTP_CODE, e);
                } else {
                    // Trouble with low-level sockets
                    throw new StopRequestException(STATUS_HTTP_DATA_ERROR, e);
                }

            } finally {
                if (conn != null) conn.disconnect();
            }
        }

        throw new StopRequestException(STATUS_TOO_MANY_REDIRECTS, "Too many redirects");
    }

    /**
     * Transfer data from the given connection to the destination file.
     */
    private void transferData(HttpURLConnection conn) throws StopRequestException {

        // To detect when we're really finished, we either need a length, closed
        // connection, or chunked encoding.
        final boolean hasLength = mInfoDelta.mTotalBytes != -1;
        final boolean isConnectionClose = "close".equalsIgnoreCase(
                conn.getHeaderField("Connection"));
        final boolean isEncodingChunked = "chunked".equalsIgnoreCase(
                conn.getHeaderField("Transfer-Encoding"));

        final boolean finishKnown = hasLength || isConnectionClose || isEncodingChunked;
        if (!finishKnown) {
            throw new StopRequestException(
                    STATUS_CANNOT_RESUME, "can't know size of download, giving up");
        }

        DrmManagerClient drmClient = null;
        ParcelFileDescriptor outPfd = null;
        FileDescriptor outFd = null;
        InputStream in = null;
        OutputStream out = null;
        try {
            try {
                in = conn.getInputStream();
            } catch (IOException e) {
                throw new StopRequestException(STATUS_HTTP_DATA_ERROR, e);
            }

            try {
                outPfd = mContext.getContentResolver()
                        .openFileDescriptor(mInfo.getAllDownloadsUri(), "rw");
                outFd = outPfd.getFileDescriptor();

                if (DownloadDrmHelper.isDrmConvertNeeded(mInfoDelta.mMimeType)) {
                    drmClient = new DrmManagerClient(mContext);
                    out = new DrmOutputStream(drmClient, outPfd, mInfoDelta.mMimeType);
                } else {
                    out = new ParcelFileDescriptor.AutoCloseOutputStream(outPfd);
                }

                // Move into place to begin writing
                Os.lseek(outFd, mInfoDelta.mCurrentBytes, OsConstants.SEEK_SET);
            } catch (ErrnoException e) {
                throw new StopRequestException(STATUS_FILE_ERROR, e);
            } catch (IOException e) {
                throw new StopRequestException(STATUS_FILE_ERROR, e);
            }

            try {
                // Pre-flight disk space requirements, when known
                if (mInfoDelta.mTotalBytes > 0 && mStorage.isAllocationSupported(outFd)) {
                    mStorage.allocateBytes(outFd, mInfoDelta.mTotalBytes);
                }
            } catch (IOException e) {
                throw new StopRequestException(STATUS_INSUFFICIENT_SPACE_ERROR, e);
            }

            // Start streaming data, periodically watch for pause/cancel
            // commands and checking disk space as needed.
            transferData(in, out, outFd);

            try {
                if (out instanceof DrmOutputStream) {
                    ((DrmOutputStream) out).finish();
                }
            } catch (IOException e) {
                throw new StopRequestException(STATUS_FILE_ERROR, e);
            }

        } finally {
            if (drmClient != null) {
                drmClient.close();
            }

            IoUtils.closeQuietly(in);

            try {
                if (out != null) out.flush();
                if (outFd != null) outFd.sync();
            } catch (IOException e) {
            } finally {
                IoUtils.closeQuietly(out);
            }
        }
    }

    /**
     * Transfer as much data as possible from the HTTP response to the
     * destination file.
     */
    private void transferData(InputStream in, OutputStream out, FileDescriptor outFd)
            throws StopRequestException {
        final byte buffer[] = new byte[Constants.BUFFER_SIZE];
        while (true) {
            if (mPolicyDirty) checkConnectivity();

            if (mShutdownRequested) {
                throw new StopRequestException(STATUS_HTTP_DATA_ERROR,
                        "Local halt requested; job probably timed out");
            }

            int len = -1;
            try {
                len = in.read(buffer);
            } catch (IOException e) {
                throw new StopRequestException(
                        STATUS_HTTP_DATA_ERROR, "Failed reading response: " + e, e);
            }

            if (len == -1) {
                break;
            }

            try {
                out.write(buffer, 0, len);

                mMadeProgress = true;
                mInfoDelta.mCurrentBytes += len;

                updateProgress(outFd);

            } catch (IOException e) {
                throw new StopRequestException(STATUS_FILE_ERROR, e);
            }
        }

        // Finished without error; verify length if known
        if (mInfoDelta.mTotalBytes != -1 && mInfoDelta.mCurrentBytes != mInfoDelta.mTotalBytes) {
            throw new StopRequestException(STATUS_HTTP_DATA_ERROR, "Content length mismatch; found "
                    + mInfoDelta.mCurrentBytes + " instead of " + mInfoDelta.mTotalBytes);
        }
    }

    /**
     * Called just before the thread finishes, regardless of status, to take any
     * necessary action on the downloaded file.
     */
    private void finalizeDestination() {
        if (Downloads.Impl.isStatusError(mInfoDelta.mStatus)) {
            // When error, free up any disk space
            try {
                final ParcelFileDescriptor target = mContext.getContentResolver()
                        .openFileDescriptor(mInfo.getAllDownloadsUri(), "rw");
                try {
                    Os.ftruncate(target.getFileDescriptor(), 0);
                } catch (ErrnoException ignored) {
                } finally {
                    IoUtils.closeQuietly(target);
                }
            } catch (FileNotFoundException ignored) {
            }

            // Delete if local file
            if (mInfoDelta.mFileName != null) {
                new File(mInfoDelta.mFileName).delete();
                mInfoDelta.mFileName = null;
            }

        } else if (Downloads.Impl.isStatusSuccess(mInfoDelta.mStatus)) {
            // When success, open access if local file
            if (mInfoDelta.mFileName != null) {
                if (Helpers.isFileInExternalAndroidDirs(mInfoDelta.mFileName)) {
                    // Files that are downloaded in Android/ may need fixing up
                    // of permissions on devices without sdcardfs; do so here,
                    // before we give the file back to the client
                    File file = new File(mInfoDelta.mFileName);
                    mStorage.fixupAppDir(file.getParentFile());
                }
                if (mInfo.mDestination != Downloads.Impl.DESTINATION_FILE_URI) {
                    try {
                        // Move into final resting place, if needed
                        final File before = new File(mInfoDelta.mFileName);
                        final File beforeDir = Helpers.getRunningDestinationDirectory(
                                mContext, mInfo.mDestination);
                        final File afterDir = Helpers.getSuccessDestinationDirectory(
                                mContext, mInfo.mDestination);
                        if (!beforeDir.equals(afterDir)
                                && before.getParentFile().equals(beforeDir)) {
                            final File after = new File(afterDir, before.getName());
                            if (before.renameTo(after)) {
                                mInfoDelta.mFileName = after.getAbsolutePath();
                            }
                        }
                    } catch (IOException ignored) {
                    }
                }
            }
        }
    }

    /**
     * Check if current connectivity is valid for this request.
     */
    private void checkConnectivity() throws StopRequestException {
        // checking connectivity will apply current policy
        mPolicyDirty = false;

        final NetworkCapabilities caps = mSystemFacade.getNetworkCapabilities(mNetwork);
        if (caps == null) {
            throw new StopRequestException(STATUS_WAITING_FOR_NETWORK, "Network is disconnected");
        }
        if (!caps.hasCapability(NET_CAPABILITY_NOT_ROAMING)
                && !mInfo.isRoamingAllowed()) {
            throw new StopRequestException(STATUS_WAITING_FOR_NETWORK, "Network is roaming");
        }
        if (!caps.hasCapability(NET_CAPABILITY_NOT_METERED)
                && !mInfo.isMeteredAllowed(mInfoDelta.mTotalBytes)) {
            throw new StopRequestException(STATUS_WAITING_FOR_NETWORK, "Network is metered");
        }
    }

    /**
     * Report download progress through the database if necessary.
     */
    private void updateProgress(FileDescriptor outFd) throws IOException, StopRequestException {
        final long now = SystemClock.elapsedRealtime();
        final long currentBytes = mInfoDelta.mCurrentBytes;

        final long sampleDelta = now - mSpeedSampleStart;
        if (sampleDelta > 500) {
            final long sampleSpeed = ((currentBytes - mSpeedSampleBytes) * 1000)
                    / sampleDelta;

            if (mSpeed == 0) {
                mSpeed = sampleSpeed;
            } else {
                mSpeed = ((mSpeed * 3) + sampleSpeed) / 4;
            }

            // Only notify once we have a full sample window
            if (mSpeedSampleStart != 0) {
                mNotifier.notifyDownloadSpeed(mId, mSpeed);
            }

            mSpeedSampleStart = now;
            mSpeedSampleBytes = currentBytes;
        }

        final long bytesDelta = currentBytes - mLastUpdateBytes;
        final long timeDelta = now - mLastUpdateTime;
        if (bytesDelta > Constants.MIN_PROGRESS_STEP && timeDelta > Constants.MIN_PROGRESS_TIME) {
            // fsync() to ensure that current progress has been flushed to disk,
            // so we can always resume based on latest database information.
            outFd.sync();

            mInfoDelta.writeToDatabaseOrThrow();

            mLastUpdateBytes = currentBytes;
            mLastUpdateTime = now;
        }
    }

    /**
     * Process response headers from first server response. This derives its
     * filename, size, and ETag.
     */
    private void parseOkHeaders(HttpURLConnection conn) throws StopRequestException {
        if (mInfoDelta.mFileName == null) {
            final String contentDisposition = conn.getHeaderField("Content-Disposition");
            final String contentLocation = conn.getHeaderField("Content-Location");

            try {
                mInfoDelta.mFileName = Helpers.generateSaveFile(mContext, mInfoDelta.mUri,
                        mInfo.mHint, contentDisposition, contentLocation, mInfoDelta.mMimeType,
                        mInfo.mDestination);
            } catch (IOException e) {
                throw new StopRequestException(
                        Downloads.Impl.STATUS_FILE_ERROR, "Failed to generate filename: " + e);
            }
        }

        if (mInfoDelta.mMimeType == null) {
            mInfoDelta.mMimeType = Intent.normalizeMimeType(conn.getContentType());
        }

        final String transferEncoding = conn.getHeaderField("Transfer-Encoding");
        if (transferEncoding == null) {
            mInfoDelta.mTotalBytes = getHeaderFieldLong(conn, "Content-Length", -1);
        } else {
            mInfoDelta.mTotalBytes = -1;
        }

        mInfoDelta.mETag = conn.getHeaderField("ETag");

        mInfoDelta.writeToDatabaseOrThrow();

        // Check connectivity again now that we know the total size
        checkConnectivity();
    }

    private void parseUnavailableHeaders(HttpURLConnection conn) {
        long retryAfter = conn.getHeaderFieldInt("Retry-After", -1);
        retryAfter = MathUtils.constrain(retryAfter, Constants.MIN_RETRY_AFTER,
                Constants.MAX_RETRY_AFTER);
        mInfoDelta.mRetryAfter = (int) (retryAfter * SECOND_IN_MILLIS);
    }

    /**
     * Add custom headers for this download to the HTTP request.
     */
    private void addRequestHeaders(HttpURLConnection conn, boolean resuming) {
        for (Pair<String, String> header : mInfo.getHeaders()) {
            conn.addRequestProperty(header.first, header.second);
        }

        // Only splice in user agent when not already defined
        if (conn.getRequestProperty("User-Agent") == null) {
            conn.addRequestProperty("User-Agent", mInfo.getUserAgent());
        }

        // Defeat transparent gzip compression, since it doesn't allow us to
        // easily resume partial downloads.
        conn.setRequestProperty("Accept-Encoding", "identity");

        // Defeat connection reuse, since otherwise servers may continue
        // streaming large downloads after cancelled.
        conn.setRequestProperty("Connection", "close");

        if (resuming) {
            if (mInfoDelta.mETag != null) {
                conn.addRequestProperty("If-Match", mInfoDelta.mETag);
            }
            conn.addRequestProperty("Range", "bytes=" + mInfoDelta.mCurrentBytes + "-");
        }
    }

    private void logDebug(String msg) {
        Log.d(TAG, "[" + mId + "] " + msg);
    }

    private void logWarning(String msg) {
        Log.w(TAG, "[" + mId + "] " + msg);
    }

    private void logError(String msg, Throwable t) {
        Log.e(TAG, "[" + mId + "] " + msg, t);
    }

    private INetworkPolicyListener mPolicyListener = new NetworkPolicyManager.Listener() {
        @Override
        public void onUidRulesChanged(int uid, int uidRules) {
            // caller is NPMS, since we only register with them
            if (uid == mInfo.mUid) {
                mPolicyDirty = true;
            }
        }

        @Override
        public void onMeteredIfacesChanged(String[] meteredIfaces) {
            // caller is NPMS, since we only register with them
            mPolicyDirty = true;
        }

        @Override
        public void onRestrictBackgroundChanged(boolean restrictBackground) {
            // caller is NPMS, since we only register with them
            mPolicyDirty = true;
        }

        @Override
        public void onUidPoliciesChanged(int uid, int uidPolicies) {
            // caller is NPMS, since we only register with them
            if (uid == mInfo.mUid) {
                mPolicyDirty = true;
            }
        }
    };

    private static long getHeaderFieldLong(URLConnection conn, String field, long defaultValue) {
        try {
            return Long.parseLong(conn.getHeaderField(field));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Return if given status is eligible to be treated as
     * {@link android.provider.Downloads.Impl#STATUS_WAITING_TO_RETRY}.
     */
    public static boolean isStatusRetryable(int status) {
        switch (status) {
            case STATUS_HTTP_DATA_ERROR:
            case HTTP_UNAVAILABLE:
            case HTTP_INTERNAL_ERROR:
            case STATUS_FILE_ERROR:
                return true;
            default:
                return false;
        }
    }
}
