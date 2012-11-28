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

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.INetworkPolicyListener;
import android.net.NetworkPolicyManager;
import android.net.Proxy;
import android.net.TrafficStats;
import android.net.http.AndroidHttpClient;
import android.os.FileUtils;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Downloads;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;

import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.params.ConnRouteParams;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SyncFailedException;
import java.net.URI;
import java.net.URISyntaxException;

/**
 * Runs an actual download
 */
public class DownloadThread extends Thread {

    private final Context mContext;
    private final DownloadInfo mInfo;
    private final SystemFacade mSystemFacade;
    private final StorageManager mStorageManager;
    private DrmConvertSession mDrmConvertSession;

    private volatile boolean mPolicyDirty;

    public DownloadThread(Context context, SystemFacade systemFacade, DownloadInfo info,
            StorageManager storageManager) {
        mContext = context;
        mSystemFacade = systemFacade;
        mInfo = info;
        mStorageManager = storageManager;
    }

    /**
     * Returns the user agent provided by the initiating app, or use the default one
     */
    private String userAgent() {
        String userAgent = mInfo.mUserAgent;
        if (userAgent == null) {
            userAgent = Constants.DEFAULT_USER_AGENT;
        }
        return userAgent;
    }

    /**
     * State for the entire run() method.
     */
    static class State {
        public String mFilename;
        public FileOutputStream mStream;
        public String mMimeType;
        public boolean mCountRetry = false;
        public int mRetryAfter = 0;
        public int mRedirectCount = 0;
        public String mNewUri;
        public boolean mGotData = false;
        public String mRequestUri;
        public long mTotalBytes = -1;
        public long mCurrentBytes = 0;
        public String mHeaderETag;
        public boolean mContinuingDownload = false;
        public long mBytesNotified = 0;
        public long mTimeLastNotification = 0;

        /** Historical bytes/second speed of this download. */
        public long mSpeed;
        /** Time when current sample started. */
        public long mSpeedSampleStart;
        /** Bytes transferred since current sample started. */
        public long mSpeedSampleBytes;

        public State(DownloadInfo info) {
            mMimeType = Intent.normalizeMimeType(info.mMimeType);
            mRequestUri = info.mUri;
            mFilename = info.mFileName;
            mTotalBytes = info.mTotalBytes;
            mCurrentBytes = info.mCurrentBytes;
        }
    }

    /**
     * State within executeDownload()
     */
    private static class InnerState {
        public String mHeaderContentLength;
        public String mHeaderContentDisposition;
        public String mHeaderContentLocation;
    }

    /**
     * Raised from methods called by executeDownload() to indicate that the download should be
     * retried immediately.
     */
    private class RetryDownload extends Throwable {}

    /**
     * Executes the download in a separate thread
     */
    @Override
    public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        try {
            runInternal();
        } finally {
            DownloadHandler.getInstance().dequeueDownload(mInfo.mId);
        }
    }

    private void runInternal() {
        // Skip when download already marked as finished; this download was
        // probably started again while racing with UpdateThread.
        if (DownloadInfo.queryDownloadStatus(mContext.getContentResolver(), mInfo.mId)
                == Downloads.Impl.STATUS_SUCCESS) {
            Log.d(TAG, "Download " + mInfo.mId + " already finished; skipping");
            return;
        }

        State state = new State(mInfo);
        AndroidHttpClient client = null;
        PowerManager.WakeLock wakeLock = null;
        int finalStatus = Downloads.Impl.STATUS_UNKNOWN_ERROR;
        String errorMsg = null;

        final NetworkPolicyManager netPolicy = NetworkPolicyManager.from(mContext);
        final PowerManager pm = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);

        try {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, Constants.TAG);
            wakeLock.acquire();

            // while performing download, register for rules updates
            netPolicy.registerListener(mPolicyListener);

            if (Constants.LOGV) {
                Log.v(Constants.TAG, "initiating download for " + mInfo.mUri);
            }

            client = AndroidHttpClient.newInstance(userAgent(), mContext);

            // network traffic on this thread should be counted against the
            // requesting uid, and is tagged with well-known value.
            TrafficStats.setThreadStatsTag(TrafficStats.TAG_SYSTEM_DOWNLOAD);
            TrafficStats.setThreadStatsUid(mInfo.mUid);

            boolean finished = false;
            while(!finished) {
                Log.i(Constants.TAG, "Initiating request for download " + mInfo.mId);
                // Set or unset proxy, which may have changed since last GET request.
                // setDefaultProxy() supports null as proxy parameter.
                ConnRouteParams.setDefaultProxy(client.getParams(),
                        Proxy.getPreferredHttpHost(mContext, state.mRequestUri));
                HttpGet request = new HttpGet(state.mRequestUri);
                try {
                    executeDownload(state, client, request);
                    finished = true;
                } catch (RetryDownload exc) {
                    // fall through
                } finally {
                    request.abort();
                    request = null;
                }
            }

            if (Constants.LOGV) {
                Log.v(Constants.TAG, "download completed for " + mInfo.mUri);
            }
            finalizeDestinationFile(state);
            finalStatus = Downloads.Impl.STATUS_SUCCESS;
        } catch (StopRequestException error) {
            // remove the cause before printing, in case it contains PII
            errorMsg = error.getMessage();
            String msg = "Aborting request for download " + mInfo.mId + ": " + errorMsg;
            Log.w(Constants.TAG, msg);
            if (Constants.LOGV) {
                Log.w(Constants.TAG, msg, error);
            }
            finalStatus = error.mFinalStatus;
            // fall through to finally block
        } catch (Throwable ex) { //sometimes the socket code throws unchecked exceptions
            errorMsg = ex.getMessage();
            String msg = "Exception for id " + mInfo.mId + ": " + errorMsg;
            Log.w(Constants.TAG, msg, ex);
            finalStatus = Downloads.Impl.STATUS_UNKNOWN_ERROR;
            // falls through to the code that reports an error
        } finally {
            TrafficStats.clearThreadStatsTag();
            TrafficStats.clearThreadStatsUid();

            if (client != null) {
                client.close();
                client = null;
            }
            cleanupDestination(state, finalStatus);
            notifyDownloadCompleted(finalStatus, state.mCountRetry, state.mRetryAfter,
                                    state.mGotData, state.mFilename,
                                    state.mNewUri, state.mMimeType, errorMsg);

            netPolicy.unregisterListener(mPolicyListener);

            if (wakeLock != null) {
                wakeLock.release();
                wakeLock = null;
            }
        }
        mStorageManager.incrementNumDownloadsSoFar();
    }

    /**
     * Fully execute a single download request - setup and send the request, handle the response,
     * and transfer the data to the destination file.
     */
    private void executeDownload(State state, AndroidHttpClient client, HttpGet request)
            throws StopRequestException, RetryDownload {
        InnerState innerState = new InnerState();
        byte data[] = new byte[Constants.BUFFER_SIZE];

        setupDestinationFile(state, innerState);
        addRequestHeaders(state, request);

        // skip when already finished; remove after fixing race in 5217390
        if (state.mCurrentBytes == state.mTotalBytes) {
            Log.i(Constants.TAG, "Skipping initiating request for download " +
                  mInfo.mId + "; already completed");
            return;
        }

        // check just before sending the request to avoid using an invalid connection at all
        checkConnectivity();

        HttpResponse response = sendRequest(state, client, request);
        handleExceptionalStatus(state, innerState, response);

        if (Constants.LOGV) {
            Log.v(Constants.TAG, "received response for " + mInfo.mUri);
        }

        processResponseHeaders(state, innerState, response);
        InputStream entityStream = openResponseEntity(state, response);
        transferData(state, innerState, data, entityStream);
    }

    /**
     * Check if current connectivity is valid for this request.
     */
    private void checkConnectivity() throws StopRequestException {
        // checking connectivity will apply current policy
        mPolicyDirty = false;

        int networkUsable = mInfo.checkCanUseNetwork();
        if (networkUsable != DownloadInfo.NETWORK_OK) {
            int status = Downloads.Impl.STATUS_WAITING_FOR_NETWORK;
            if (networkUsable == DownloadInfo.NETWORK_UNUSABLE_DUE_TO_SIZE) {
                status = Downloads.Impl.STATUS_QUEUED_FOR_WIFI;
                mInfo.notifyPauseDueToSize(true);
            } else if (networkUsable == DownloadInfo.NETWORK_RECOMMENDED_UNUSABLE_DUE_TO_SIZE) {
                status = Downloads.Impl.STATUS_QUEUED_FOR_WIFI;
                mInfo.notifyPauseDueToSize(false);
            }
            throw new StopRequestException(status,
                    mInfo.getLogMessageForNetworkError(networkUsable));
        }
    }

    /**
     * Transfer as much data as possible from the HTTP response to the destination file.
     * @param data buffer to use to read data
     * @param entityStream stream for reading the HTTP response entity
     */
    private void transferData(
            State state, InnerState innerState, byte[] data, InputStream entityStream)
            throws StopRequestException {
        for (;;) {
            int bytesRead = readFromResponse(state, innerState, data, entityStream);
            if (bytesRead == -1) { // success, end of stream already reached
                handleEndOfStream(state, innerState);
                return;
            }

            state.mGotData = true;
            writeDataToDestination(state, data, bytesRead);
            state.mCurrentBytes += bytesRead;
            reportProgress(state, innerState);

            if (Constants.LOGVV) {
                Log.v(Constants.TAG, "downloaded " + state.mCurrentBytes + " for "
                      + mInfo.mUri);
            }

            checkPausedOrCanceled(state);
        }
    }

    /**
     * Called after a successful completion to take any necessary action on the downloaded file.
     */
    private void finalizeDestinationFile(State state) throws StopRequestException {
        if (state.mFilename != null) {
            // make sure the file is readable
            FileUtils.setPermissions(state.mFilename, 0644, -1, -1);
            syncDestination(state);
        }
    }

    /**
     * Called just before the thread finishes, regardless of status, to take any necessary action on
     * the downloaded file.
     */
    private void cleanupDestination(State state, int finalStatus) {
        if (mDrmConvertSession != null) {
            finalStatus = mDrmConvertSession.close(state.mFilename);
        }

        closeDestination(state);
        if (state.mFilename != null && Downloads.Impl.isStatusError(finalStatus)) {
            if (Constants.LOGVV) {
                Log.d(TAG, "cleanupDestination() deleting " + state.mFilename);
            }
            new File(state.mFilename).delete();
            state.mFilename = null;
        }
    }

    /**
     * Sync the destination file to storage.
     */
    private void syncDestination(State state) {
        FileOutputStream downloadedFileStream = null;
        try {
            downloadedFileStream = new FileOutputStream(state.mFilename, true);
            downloadedFileStream.getFD().sync();
        } catch (FileNotFoundException ex) {
            Log.w(Constants.TAG, "file " + state.mFilename + " not found: " + ex);
        } catch (SyncFailedException ex) {
            Log.w(Constants.TAG, "file " + state.mFilename + " sync failed: " + ex);
        } catch (IOException ex) {
            Log.w(Constants.TAG, "IOException trying to sync " + state.mFilename + ": " + ex);
        } catch (RuntimeException ex) {
            Log.w(Constants.TAG, "exception while syncing file: ", ex);
        } finally {
            if(downloadedFileStream != null) {
                try {
                    downloadedFileStream.close();
                } catch (IOException ex) {
                    Log.w(Constants.TAG, "IOException while closing synced file: ", ex);
                } catch (RuntimeException ex) {
                    Log.w(Constants.TAG, "exception while closing file: ", ex);
                }
            }
        }
    }

    /**
     * Close the destination output stream.
     */
    private void closeDestination(State state) {
        try {
            // close the file
            if (state.mStream != null) {
                state.mStream.close();
                state.mStream = null;
            }
        } catch (IOException ex) {
            if (Constants.LOGV) {
                Log.v(Constants.TAG, "exception when closing the file after download : " + ex);
            }
            // nothing can really be done if the file can't be closed
        }
    }

    /**
     * Check if the download has been paused or canceled, stopping the request appropriately if it
     * has been.
     */
    private void checkPausedOrCanceled(State state) throws StopRequestException {
        synchronized (mInfo) {
            if (mInfo.mControl == Downloads.Impl.CONTROL_PAUSED) {
                throw new StopRequestException(
                        Downloads.Impl.STATUS_PAUSED_BY_APP, "download paused by owner");
            }
            if (mInfo.mStatus == Downloads.Impl.STATUS_CANCELED) {
                throw new StopRequestException(Downloads.Impl.STATUS_CANCELED, "download canceled");
            }
        }

        // if policy has been changed, trigger connectivity check
        if (mPolicyDirty) {
            checkConnectivity();
        }
    }

    /**
     * Report download progress through the database if necessary.
     */
    private void reportProgress(State state, InnerState innerState) {
        final long now = SystemClock.elapsedRealtime();

        final long sampleDelta = now - state.mSpeedSampleStart;
        if (sampleDelta > 500) {
            final long sampleSpeed = ((state.mCurrentBytes - state.mSpeedSampleBytes) * 1000)
                    / sampleDelta;

            if (state.mSpeed == 0) {
                state.mSpeed = sampleSpeed;
            } else {
                state.mSpeed = ((state.mSpeed * 3) + sampleSpeed) / 4;
            }

            state.mSpeedSampleStart = now;
            state.mSpeedSampleBytes = state.mCurrentBytes;

            DownloadHandler.getInstance().setCurrentSpeed(mInfo.mId, state.mSpeed);
        }

        if (state.mCurrentBytes - state.mBytesNotified > Constants.MIN_PROGRESS_STEP &&
            now - state.mTimeLastNotification > Constants.MIN_PROGRESS_TIME) {
            ContentValues values = new ContentValues();
            values.put(Downloads.Impl.COLUMN_CURRENT_BYTES, state.mCurrentBytes);
            mContext.getContentResolver().update(mInfo.getAllDownloadsUri(), values, null, null);
            state.mBytesNotified = state.mCurrentBytes;
            state.mTimeLastNotification = now;
        }
    }

    /**
     * Write a data buffer to the destination file.
     * @param data buffer containing the data to write
     * @param bytesRead how many bytes to write from the buffer
     */
    private void writeDataToDestination(State state, byte[] data, int bytesRead)
            throws StopRequestException {
        for (;;) {
            try {
                if (state.mStream == null) {
                    state.mStream = new FileOutputStream(state.mFilename, true);
                }
                mStorageManager.verifySpaceBeforeWritingToFile(mInfo.mDestination, state.mFilename,
                        bytesRead);
                if (!DownloadDrmHelper.isDrmConvertNeeded(mInfo.mMimeType)) {
                    state.mStream.write(data, 0, bytesRead);
                } else {
                    byte[] convertedData = mDrmConvertSession.convert(data, bytesRead);
                    if (convertedData != null) {
                        state.mStream.write(convertedData, 0, convertedData.length);
                    } else {
                        throw new StopRequestException(Downloads.Impl.STATUS_FILE_ERROR,
                                "Error converting drm data.");
                    }
                }
                return;
            } catch (IOException ex) {
                // couldn't write to file. are we out of space? check.
                // TODO this check should only be done once. why is this being done
                // in a while(true) loop (see the enclosing statement: for(;;)
                if (state.mStream != null) {
                    mStorageManager.verifySpace(mInfo.mDestination, state.mFilename, bytesRead);
                }
            } finally {
                if (mInfo.mDestination == Downloads.Impl.DESTINATION_EXTERNAL) {
                    closeDestination(state);
                }
            }
        }
    }

    /**
     * Called when we've reached the end of the HTTP response stream, to update the database and
     * check for consistency.
     */
    private void handleEndOfStream(State state, InnerState innerState) throws StopRequestException {
        ContentValues values = new ContentValues();
        values.put(Downloads.Impl.COLUMN_CURRENT_BYTES, state.mCurrentBytes);
        if (innerState.mHeaderContentLength == null) {
            values.put(Downloads.Impl.COLUMN_TOTAL_BYTES, state.mCurrentBytes);
        }
        mContext.getContentResolver().update(mInfo.getAllDownloadsUri(), values, null, null);

        boolean lengthMismatched = (innerState.mHeaderContentLength != null)
                && (state.mCurrentBytes != Integer.parseInt(innerState.mHeaderContentLength));
        if (lengthMismatched) {
            if (cannotResume(state)) {
                throw new StopRequestException(Downloads.Impl.STATUS_CANNOT_RESUME,
                        "mismatched content length");
            } else {
                throw new StopRequestException(getFinalStatusForHttpError(state),
                        "closed socket before end of file");
            }
        }
    }

    private boolean cannotResume(State state) {
        return state.mCurrentBytes > 0 && !mInfo.mNoIntegrity && state.mHeaderETag == null;
    }

    /**
     * Read some data from the HTTP response stream, handling I/O errors.
     * @param data buffer to use to read data
     * @param entityStream stream for reading the HTTP response entity
     * @return the number of bytes actually read or -1 if the end of the stream has been reached
     */
    private int readFromResponse(State state, InnerState innerState, byte[] data,
                                 InputStream entityStream) throws StopRequestException {
        try {
            return entityStream.read(data);
        } catch (IOException ex) {
            logNetworkState(mInfo.mUid);
            ContentValues values = new ContentValues();
            values.put(Downloads.Impl.COLUMN_CURRENT_BYTES, state.mCurrentBytes);
            mContext.getContentResolver().update(mInfo.getAllDownloadsUri(), values, null, null);
            if (cannotResume(state)) {
                String message = "while reading response: " + ex.toString()
                + ", can't resume interrupted download with no ETag";
                throw new StopRequestException(Downloads.Impl.STATUS_CANNOT_RESUME,
                        message, ex);
            } else {
                throw new StopRequestException(getFinalStatusForHttpError(state),
                        "while reading response: " + ex.toString(), ex);
            }
        }
    }

    /**
     * Open a stream for the HTTP response entity, handling I/O errors.
     * @return an InputStream to read the response entity
     */
    private InputStream openResponseEntity(State state, HttpResponse response)
            throws StopRequestException {
        try {
            return response.getEntity().getContent();
        } catch (IOException ex) {
            logNetworkState(mInfo.mUid);
            throw new StopRequestException(getFinalStatusForHttpError(state),
                    "while getting entity: " + ex.toString(), ex);
        }
    }

    private void logNetworkState(int uid) {
        if (Constants.LOGX) {
            Log.i(Constants.TAG,
                    "Net " + (Helpers.isNetworkAvailable(mSystemFacade, uid) ? "Up" : "Down"));
        }
    }

    /**
     * Read HTTP response headers and take appropriate action, including setting up the destination
     * file and updating the database.
     */
    private void processResponseHeaders(State state, InnerState innerState, HttpResponse response)
            throws StopRequestException {
        if (state.mContinuingDownload) {
            // ignore response headers on resume requests
            return;
        }

        readResponseHeaders(state, innerState, response);
        if (DownloadDrmHelper.isDrmConvertNeeded(state.mMimeType)) {
            mDrmConvertSession = DrmConvertSession.open(mContext, state.mMimeType);
            if (mDrmConvertSession == null) {
                throw new StopRequestException(Downloads.Impl.STATUS_NOT_ACCEPTABLE, "Mimetype "
                        + state.mMimeType + " can not be converted.");
            }
        }

        state.mFilename = Helpers.generateSaveFile(
                mContext,
                mInfo.mUri,
                mInfo.mHint,
                innerState.mHeaderContentDisposition,
                innerState.mHeaderContentLocation,
                state.mMimeType,
                mInfo.mDestination,
                (innerState.mHeaderContentLength != null) ?
                        Long.parseLong(innerState.mHeaderContentLength) : 0,
                mInfo.mIsPublicApi, mStorageManager);
        try {
            state.mStream = new FileOutputStream(state.mFilename);
        } catch (FileNotFoundException exc) {
            throw new StopRequestException(Downloads.Impl.STATUS_FILE_ERROR,
                    "while opening destination file: " + exc.toString(), exc);
        }
        if (Constants.LOGV) {
            Log.v(Constants.TAG, "writing " + mInfo.mUri + " to " + state.mFilename);
        }

        updateDatabaseFromHeaders(state, innerState);
        // check connectivity again now that we know the total size
        checkConnectivity();
    }

    /**
     * Update necessary database fields based on values of HTTP response headers that have been
     * read.
     */
    private void updateDatabaseFromHeaders(State state, InnerState innerState) {
        ContentValues values = new ContentValues();
        values.put(Downloads.Impl._DATA, state.mFilename);
        if (state.mHeaderETag != null) {
            values.put(Constants.ETAG, state.mHeaderETag);
        }
        if (state.mMimeType != null) {
            values.put(Downloads.Impl.COLUMN_MIME_TYPE, state.mMimeType);
        }
        values.put(Downloads.Impl.COLUMN_TOTAL_BYTES, mInfo.mTotalBytes);
        mContext.getContentResolver().update(mInfo.getAllDownloadsUri(), values, null, null);
    }

    /**
     * Read headers from the HTTP response and store them into local state.
     */
    private void readResponseHeaders(State state, InnerState innerState, HttpResponse response)
            throws StopRequestException {
        Header header = response.getFirstHeader("Content-Disposition");
        if (header != null) {
            innerState.mHeaderContentDisposition = header.getValue();
        }
        header = response.getFirstHeader("Content-Location");
        if (header != null) {
            innerState.mHeaderContentLocation = header.getValue();
        }
        if (state.mMimeType == null) {
            header = response.getFirstHeader("Content-Type");
            if (header != null) {
                state.mMimeType = Intent.normalizeMimeType(header.getValue());
            }
        }
        header = response.getFirstHeader("ETag");
        if (header != null) {
            state.mHeaderETag = header.getValue();
        }
        String headerTransferEncoding = null;
        header = response.getFirstHeader("Transfer-Encoding");
        if (header != null) {
            headerTransferEncoding = header.getValue();
        }
        if (headerTransferEncoding == null) {
            header = response.getFirstHeader("Content-Length");
            if (header != null) {
                innerState.mHeaderContentLength = header.getValue();
                state.mTotalBytes = mInfo.mTotalBytes =
                        Long.parseLong(innerState.mHeaderContentLength);
            }
        } else {
            // Ignore content-length with transfer-encoding - 2616 4.4 3
            if (Constants.LOGVV) {
                Log.v(Constants.TAG,
                        "ignoring content-length because of xfer-encoding");
            }
        }
        if (Constants.LOGVV) {
            Log.v(Constants.TAG, "Content-Disposition: " +
                    innerState.mHeaderContentDisposition);
            Log.v(Constants.TAG, "Content-Length: " + innerState.mHeaderContentLength);
            Log.v(Constants.TAG, "Content-Location: " + innerState.mHeaderContentLocation);
            Log.v(Constants.TAG, "Content-Type: " + state.mMimeType);
            Log.v(Constants.TAG, "ETag: " + state.mHeaderETag);
            Log.v(Constants.TAG, "Transfer-Encoding: " + headerTransferEncoding);
        }

        boolean noSizeInfo = innerState.mHeaderContentLength == null
                && (headerTransferEncoding == null
                    || !headerTransferEncoding.equalsIgnoreCase("chunked"));
        if (!mInfo.mNoIntegrity && noSizeInfo) {
            throw new StopRequestException(Downloads.Impl.STATUS_HTTP_DATA_ERROR,
                    "can't know size of download, giving up");
        }
    }

    /**
     * Check the HTTP response status and handle anything unusual (e.g. not 200/206).
     */
    private void handleExceptionalStatus(State state, InnerState innerState, HttpResponse response)
            throws StopRequestException, RetryDownload {
        int statusCode = response.getStatusLine().getStatusCode();
        if (statusCode == 503 && mInfo.mNumFailed < Constants.MAX_RETRIES) {
            handleServiceUnavailable(state, response);
        }
        if (statusCode == 301 || statusCode == 302 || statusCode == 303 || statusCode == 307) {
            handleRedirect(state, response, statusCode);
        }

        if (Constants.LOGV) {
            Log.i(Constants.TAG, "recevd_status = " + statusCode +
                    ", mContinuingDownload = " + state.mContinuingDownload);
        }
        int expectedStatus = state.mContinuingDownload ? 206 : Downloads.Impl.STATUS_SUCCESS;
        if (statusCode != expectedStatus) {
            handleOtherStatus(state, innerState, statusCode);
        }
    }

    /**
     * Handle a status that we don't know how to deal with properly.
     */
    private void handleOtherStatus(State state, InnerState innerState, int statusCode)
            throws StopRequestException {
        if (statusCode == 416) {
            // range request failed. it should never fail.
            throw new IllegalStateException("Http Range request failure: totalBytes = " +
                    state.mTotalBytes + ", bytes recvd so far: " + state.mCurrentBytes);
        }
        int finalStatus;
        if (Downloads.Impl.isStatusError(statusCode)) {
            finalStatus = statusCode;
        } else if (statusCode >= 300 && statusCode < 400) {
            finalStatus = Downloads.Impl.STATUS_UNHANDLED_REDIRECT;
        } else if (state.mContinuingDownload && statusCode == Downloads.Impl.STATUS_SUCCESS) {
            finalStatus = Downloads.Impl.STATUS_CANNOT_RESUME;
        } else {
            finalStatus = Downloads.Impl.STATUS_UNHANDLED_HTTP_CODE;
        }
        throw new StopRequestException(finalStatus, "http error " +
                statusCode + ", mContinuingDownload: " + state.mContinuingDownload);
    }

    /**
     * Handle a 3xx redirect status.
     */
    private void handleRedirect(State state, HttpResponse response, int statusCode)
            throws StopRequestException, RetryDownload {
        if (Constants.LOGVV) {
            Log.v(Constants.TAG, "got HTTP redirect " + statusCode);
        }
        if (state.mRedirectCount >= Constants.MAX_REDIRECTS) {
            throw new StopRequestException(Downloads.Impl.STATUS_TOO_MANY_REDIRECTS,
                    "too many redirects");
        }
        Header header = response.getFirstHeader("Location");
        if (header == null) {
            return;
        }
        if (Constants.LOGVV) {
            Log.v(Constants.TAG, "Location :" + header.getValue());
        }

        String newUri;
        try {
            newUri = new URI(mInfo.mUri).resolve(new URI(header.getValue())).toString();
        } catch(URISyntaxException ex) {
            if (Constants.LOGV) {
                Log.d(Constants.TAG, "Couldn't resolve redirect URI " + header.getValue()
                        + " for " + mInfo.mUri);
            }
            throw new StopRequestException(Downloads.Impl.STATUS_HTTP_DATA_ERROR,
                    "Couldn't resolve redirect URI");
        }
        ++state.mRedirectCount;
        state.mRequestUri = newUri;
        if (statusCode == 301 || statusCode == 303) {
            // use the new URI for all future requests (should a retry/resume be necessary)
            state.mNewUri = newUri;
        }
        throw new RetryDownload();
    }

    /**
     * Handle a 503 Service Unavailable status by processing the Retry-After header.
     */
    private void handleServiceUnavailable(State state, HttpResponse response)
            throws StopRequestException {
        if (Constants.LOGVV) {
            Log.v(Constants.TAG, "got HTTP response code 503");
        }
        state.mCountRetry = true;
        Header header = response.getFirstHeader("Retry-After");
        if (header != null) {
           try {
               if (Constants.LOGVV) {
                   Log.v(Constants.TAG, "Retry-After :" + header.getValue());
               }
               state.mRetryAfter = Integer.parseInt(header.getValue());
               if (state.mRetryAfter < 0) {
                   state.mRetryAfter = 0;
               } else {
                   if (state.mRetryAfter < Constants.MIN_RETRY_AFTER) {
                       state.mRetryAfter = Constants.MIN_RETRY_AFTER;
                   } else if (state.mRetryAfter > Constants.MAX_RETRY_AFTER) {
                       state.mRetryAfter = Constants.MAX_RETRY_AFTER;
                   }
                   state.mRetryAfter += Helpers.sRandom.nextInt(Constants.MIN_RETRY_AFTER + 1);
                   state.mRetryAfter *= 1000;
               }
           } catch (NumberFormatException ex) {
               // ignored - retryAfter stays 0 in this case.
           }
        }
        throw new StopRequestException(Downloads.Impl.STATUS_WAITING_TO_RETRY,
                "got 503 Service Unavailable, will retry later");
    }

    /**
     * Send the request to the server, handling any I/O exceptions.
     */
    private HttpResponse sendRequest(State state, AndroidHttpClient client, HttpGet request)
            throws StopRequestException {
        try {
            return client.execute(request);
        } catch (IllegalArgumentException ex) {
            throw new StopRequestException(Downloads.Impl.STATUS_HTTP_DATA_ERROR,
                    "while trying to execute request: " + ex.toString(), ex);
        } catch (IOException ex) {
            logNetworkState(mInfo.mUid);
            throw new StopRequestException(getFinalStatusForHttpError(state),
                    "while trying to execute request: " + ex.toString(), ex);
        }
    }

    private int getFinalStatusForHttpError(State state) {
        int networkUsable = mInfo.checkCanUseNetwork();
        if (networkUsable != DownloadInfo.NETWORK_OK) {
            switch (networkUsable) {
                case DownloadInfo.NETWORK_UNUSABLE_DUE_TO_SIZE:
                case DownloadInfo.NETWORK_RECOMMENDED_UNUSABLE_DUE_TO_SIZE:
                    return Downloads.Impl.STATUS_QUEUED_FOR_WIFI;
                default:
                    return Downloads.Impl.STATUS_WAITING_FOR_NETWORK;
            }
        } else if (mInfo.mNumFailed < Constants.MAX_RETRIES) {
            state.mCountRetry = true;
            return Downloads.Impl.STATUS_WAITING_TO_RETRY;
        } else {
            Log.w(Constants.TAG, "reached max retries for " + mInfo.mId);
            return Downloads.Impl.STATUS_HTTP_DATA_ERROR;
        }
    }

    /**
     * Prepare the destination file to receive data.  If the file already exists, we'll set up
     * appropriately for resumption.
     */
    private void setupDestinationFile(State state, InnerState innerState)
            throws StopRequestException {
        if (!TextUtils.isEmpty(state.mFilename)) { // only true if we've already run a thread for this download
            if (Constants.LOGV) {
                Log.i(Constants.TAG, "have run thread before for id: " + mInfo.mId +
                        ", and state.mFilename: " + state.mFilename);
            }
            if (!Helpers.isFilenameValid(state.mFilename,
                    mStorageManager.getDownloadDataDirectory())) {
                // this should never happen
                throw new StopRequestException(Downloads.Impl.STATUS_FILE_ERROR,
                        "found invalid internal destination filename");
            }
            // We're resuming a download that got interrupted
            File f = new File(state.mFilename);
            if (f.exists()) {
                if (Constants.LOGV) {
                    Log.i(Constants.TAG, "resuming download for id: " + mInfo.mId +
                            ", and state.mFilename: " + state.mFilename);
                }
                long fileLength = f.length();
                if (fileLength == 0) {
                    // The download hadn't actually started, we can restart from scratch
                    if (Constants.LOGVV) {
                        Log.d(TAG, "setupDestinationFile() found fileLength=0, deleting "
                                + state.mFilename);
                    }
                    f.delete();
                    state.mFilename = null;
                    if (Constants.LOGV) {
                        Log.i(Constants.TAG, "resuming download for id: " + mInfo.mId +
                                ", BUT starting from scratch again: ");
                    }
                } else if (mInfo.mETag == null && !mInfo.mNoIntegrity) {
                    // This should've been caught upon failure
                    if (Constants.LOGVV) {
                        Log.d(TAG, "setupDestinationFile() unable to resume download, deleting "
                                + state.mFilename);
                    }
                    f.delete();
                    throw new StopRequestException(Downloads.Impl.STATUS_CANNOT_RESUME,
                            "Trying to resume a download that can't be resumed");
                } else {
                    // All right, we'll be able to resume this download
                    if (Constants.LOGV) {
                        Log.i(Constants.TAG, "resuming download for id: " + mInfo.mId +
                                ", and starting with file of length: " + fileLength);
                    }
                    try {
                        state.mStream = new FileOutputStream(state.mFilename, true);
                    } catch (FileNotFoundException exc) {
                        throw new StopRequestException(Downloads.Impl.STATUS_FILE_ERROR,
                                "while opening destination for resuming: " + exc.toString(), exc);
                    }
                    state.mCurrentBytes = (int) fileLength;
                    if (mInfo.mTotalBytes != -1) {
                        innerState.mHeaderContentLength = Long.toString(mInfo.mTotalBytes);
                    }
                    state.mHeaderETag = mInfo.mETag;
                    state.mContinuingDownload = true;
                    if (Constants.LOGV) {
                        Log.i(Constants.TAG, "resuming download for id: " + mInfo.mId +
                                ", state.mCurrentBytes: " + state.mCurrentBytes +
                                ", and setting mContinuingDownload to true: ");
                    }
                }
            }
        }

        if (state.mStream != null && mInfo.mDestination == Downloads.Impl.DESTINATION_EXTERNAL) {
            closeDestination(state);
        }
    }

    /**
     * Add custom headers for this download to the HTTP request.
     */
    private void addRequestHeaders(State state, HttpGet request) {
        for (Pair<String, String> header : mInfo.getHeaders()) {
            request.addHeader(header.first, header.second);
        }

        if (state.mContinuingDownload) {
            if (state.mHeaderETag != null) {
                request.addHeader("If-Match", state.mHeaderETag);
            }
            request.addHeader("Range", "bytes=" + state.mCurrentBytes + "-");
            if (Constants.LOGV) {
                Log.i(Constants.TAG, "Adding Range header: " +
                        "bytes=" + state.mCurrentBytes + "-");
                Log.i(Constants.TAG, "  totalBytes = " + state.mTotalBytes);
            }
        }
    }

    /**
     * Stores information about the completed download, and notifies the initiating application.
     */
    private void notifyDownloadCompleted(
            int status, boolean countRetry, int retryAfter, boolean gotData,
            String filename, String uri, String mimeType, String errorMsg) {
        notifyThroughDatabase(
                status, countRetry, retryAfter, gotData, filename, uri, mimeType,
                errorMsg);
        if (Downloads.Impl.isStatusCompleted(status)) {
            mInfo.sendIntentIfRequested();
        }
    }

    private void notifyThroughDatabase(
            int status, boolean countRetry, int retryAfter, boolean gotData,
            String filename, String uri, String mimeType, String errorMsg) {
        ContentValues values = new ContentValues();
        values.put(Downloads.Impl.COLUMN_STATUS, status);
        values.put(Downloads.Impl._DATA, filename);
        if (uri != null) {
            values.put(Downloads.Impl.COLUMN_URI, uri);
        }
        values.put(Downloads.Impl.COLUMN_MIME_TYPE, mimeType);
        values.put(Downloads.Impl.COLUMN_LAST_MODIFICATION, mSystemFacade.currentTimeMillis());
        values.put(Constants.RETRY_AFTER_X_REDIRECT_COUNT, retryAfter);
        if (!countRetry) {
            values.put(Constants.FAILED_CONNECTIONS, 0);
        } else if (gotData) {
            values.put(Constants.FAILED_CONNECTIONS, 1);
        } else {
            values.put(Constants.FAILED_CONNECTIONS, mInfo.mNumFailed + 1);
        }
        // save the error message. could be useful to developers.
        if (!TextUtils.isEmpty(errorMsg)) {
            values.put(Downloads.Impl.COLUMN_ERROR_MSG, errorMsg);
        }
        mContext.getContentResolver().update(mInfo.getAllDownloadsUri(), values, null, null);
    }

    private INetworkPolicyListener mPolicyListener = new INetworkPolicyListener.Stub() {
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
    };
}
