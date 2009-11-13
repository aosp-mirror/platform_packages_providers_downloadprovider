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

import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.HttpClient;
import org.apache.http.entity.StringEntity;

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.drm.mobile1.DrmRawContent;
import android.net.Uri;
import android.net.http.AndroidHttpClient;
import android.os.FileUtils;
import android.os.PowerManager;
import android.os.Process;
import android.provider.Downloads;
import android.provider.DrmStore;
import android.util.Config;
import android.util.Log;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SyncFailedException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Runs an actual download
 */
public class DownloadThread extends Thread {

    private Context mContext;
    private DownloadInfo mInfo;

    public DownloadThread(Context context, DownloadInfo info) {
        mContext = context;
        mInfo = info;
    }

    /**
     * Returns the user agent provided by the initiating app, or use the default one
     */
    private String userAgent() {
        String userAgent = mInfo.mUserAgent;
        if (userAgent != null) {
        }
        if (userAgent == null) {
            userAgent = Constants.DEFAULT_USER_AGENT;
        }
        return userAgent;
    }

    /**
     * Executes the download in a separate thread
     */
    public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);

        int finalStatus = Downloads.STATUS_UNKNOWN_ERROR;
        boolean countRetry = false;
        int retryAfter = 0;
        int redirectCount = mInfo.mRedirectCount;
        String newUri = null;
        boolean gotData = false;
        String filename = null;
        String mimeType = sanitizeMimeType(mInfo.mMimeType);
        FileOutputStream stream = null;
        AndroidHttpClient client = null;
        PowerManager.WakeLock wakeLock = null;
        Uri contentUri = Uri.parse(Downloads.CONTENT_URI + "/" + mInfo.mId);

        try {
            boolean continuingDownload = false;
            String headerAcceptRanges = null;
            String headerContentDisposition = null;
            String headerContentLength = null;
            String headerContentLocation = null;
            String headerETag = null;
            String headerTransferEncoding = null;

            byte data[] = new byte[Constants.BUFFER_SIZE];

            int bytesSoFar = 0;

            PowerManager pm = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, Constants.TAG);
            wakeLock.acquire();

            filename = mInfo.mFileName;
            if (filename != null) {
                if (!Helpers.isFilenameValid(filename)) {
                    finalStatus = Downloads.STATUS_FILE_ERROR;
                    notifyDownloadCompleted(
                            finalStatus, false, 0, 0, false, filename, null, mInfo.mMimeType);
                    return;
                }
                // We're resuming a download that got interrupted
                File f = new File(filename);
                if (f.exists()) {
                    long fileLength = f.length();
                    if (fileLength == 0) {
                        // The download hadn't actually started, we can restart from scratch
                        f.delete();
                        filename = null;
                    } else if (mInfo.mETag == null && !mInfo.mNoIntegrity) {
                        // Tough luck, that's not a resumable download
                        if (Config.LOGD) {
                            Log.d(Constants.TAG,
                                    "can't resume interrupted non-resumable download"); 
                        }
                        f.delete();
                        finalStatus = Downloads.STATUS_PRECONDITION_FAILED;
                        notifyDownloadCompleted(
                                finalStatus, false, 0, 0, false, filename, null, mInfo.mMimeType);
                        return;
                    } else {
                        // All right, we'll be able to resume this download
                        stream = new FileOutputStream(filename, true);
                        bytesSoFar = (int) fileLength;
                        if (mInfo.mTotalBytes != -1) {
                            headerContentLength = Integer.toString(mInfo.mTotalBytes);
                        }
                        headerETag = mInfo.mETag;
                        continuingDownload = true;
                    }
                }
            }

            int bytesNotified = bytesSoFar;
            // starting with MIN_VALUE means that the first write will commit
            //     progress to the database
            long timeLastNotification = 0;

            client = AndroidHttpClient.newInstance(userAgent());

            if (stream != null && mInfo.mDestination == Downloads.DESTINATION_EXTERNAL
                        && !DrmRawContent.DRM_MIMETYPE_MESSAGE_STRING
                        .equalsIgnoreCase(mimeType)) {
                try {
                    stream.close();
                    stream = null;
                } catch (IOException ex) {
                    if (Constants.LOGV) {
                        Log.v(Constants.TAG, "exception when closing the file before download : " +
                                ex);
                    }
                    // nothing can really be done if the file can't be closed
                }
            }

            /*
             * This loop is run once for every individual HTTP request that gets sent.
             * The very first HTTP request is a "virgin" request, while every subsequent
             * request is done with the original ETag and a byte-range.
             */
http_request_loop:
            while (true) {
                // Prepares the request and fires it.
                HttpGet request = new HttpGet(mInfo.mUri);

                if (Constants.LOGV) {
                    Log.v(Constants.TAG, "initiating download for " + mInfo.mUri);
                }

                if (mInfo.mCookies != null) {
                    request.addHeader("Cookie", mInfo.mCookies);
                }
                if (mInfo.mReferer != null) {
                    request.addHeader("Referer", mInfo.mReferer);
                }
                if (continuingDownload) {
                    if (headerETag != null) {
                        request.addHeader("If-Match", headerETag);
                    }
                    request.addHeader("Range", "bytes=" + bytesSoFar + "-");
                }

                HttpResponse response;
                try {
                    response = client.execute(request);
                } catch (IllegalArgumentException ex) {
                    if (Constants.LOGV) {
                        Log.d(Constants.TAG, "Arg exception trying to execute request for " +
                                mInfo.mUri + " : " + ex);
                    } else if (Config.LOGD) {
                        Log.d(Constants.TAG, "Arg exception trying to execute request for " +
                                mInfo.mId + " : " +  ex);
                    }
                    finalStatus = Downloads.STATUS_BAD_REQUEST;
                    request.abort();
                    break http_request_loop;
                } catch (IOException ex) {
                    if (Constants.LOGX) {
                        if (Helpers.isNetworkAvailable(mContext)) {
                            Log.i(Constants.TAG, "Execute Failed " + mInfo.mId + ", Net Up");
                        } else {
                            Log.i(Constants.TAG, "Execute Failed " + mInfo.mId + ", Net Down");
                        }
                    }
                    if (!Helpers.isNetworkAvailable(mContext)) {
                        finalStatus = Downloads.STATUS_RUNNING_PAUSED;
                    } else if (mInfo.mNumFailed < Constants.MAX_RETRIES) {
                        finalStatus = Downloads.STATUS_RUNNING_PAUSED;
                        countRetry = true;
                    } else {
                        if (Constants.LOGV) {
                            Log.d(Constants.TAG, "IOException trying to execute request for " +
                                    mInfo.mUri + " : " + ex);
                        } else if (Config.LOGD) {
                            Log.d(Constants.TAG, "IOException trying to execute request for " +
                                    mInfo.mId + " : " + ex);
                        }
                        finalStatus = Downloads.STATUS_HTTP_DATA_ERROR;
                    }
                    request.abort();
                    break http_request_loop;
                }

                int statusCode = response.getStatusLine().getStatusCode();
                if (statusCode == 503 && mInfo.mNumFailed < Constants.MAX_RETRIES) {
                    if (Constants.LOGVV) {
                        Log.v(Constants.TAG, "got HTTP response code 503");
                    }
                    finalStatus = Downloads.STATUS_RUNNING_PAUSED;
                    countRetry = true;
                    Header header = response.getFirstHeader("Retry-After");
                    if (header != null) {
                       try {
                           if (Constants.LOGVV) {
                               Log.v(Constants.TAG, "Retry-After :" + header.getValue());
                           }
                           retryAfter = Integer.parseInt(header.getValue());
                           if (retryAfter < 0) {
                               retryAfter = 0;
                           } else {
                               if (retryAfter < Constants.MIN_RETRY_AFTER) {
                                   retryAfter = Constants.MIN_RETRY_AFTER;
                               } else if (retryAfter > Constants.MAX_RETRY_AFTER) {
                                   retryAfter = Constants.MAX_RETRY_AFTER;
                               }
                               retryAfter += Helpers.sRandom.nextInt(Constants.MIN_RETRY_AFTER + 1);
                               retryAfter *= 1000;
                           }
                       } catch (NumberFormatException ex) {
                           // ignored - retryAfter stays 0 in this case.
                       }
                    }
                    request.abort();
                    break http_request_loop;
                }
                if (statusCode == 301 ||
                        statusCode == 302 ||
                        statusCode == 303 ||
                        statusCode == 307) {
                    if (Constants.LOGVV) {
                        Log.v(Constants.TAG, "got HTTP redirect " + statusCode);
                    }
                    if (redirectCount >= Constants.MAX_REDIRECTS) {
                        if (Constants.LOGV) {
                            Log.d(Constants.TAG, "too many redirects for download " + mInfo.mId +
                                    " at " + mInfo.mUri);
                        } else if (Config.LOGD) {
                            Log.d(Constants.TAG, "too many redirects for download " + mInfo.mId);
                        }
                        finalStatus = Downloads.STATUS_TOO_MANY_REDIRECTS;
                        request.abort();
                        break http_request_loop;
                    }
                    Header header = response.getFirstHeader("Location");
                    if (header != null) {
                        if (Constants.LOGVV) {
                            Log.v(Constants.TAG, "Location :" + header.getValue());
                        }
                        try {
                            newUri = new URI(mInfo.mUri).
                                    resolve(new URI(header.getValue())).
                                    toString();
                        } catch(URISyntaxException ex) {
                            if (Constants.LOGV) {
                                Log.d(Constants.TAG,
                                        "Couldn't resolve redirect URI " +
                                        header.getValue() +
                                        " for " +
                                        mInfo.mUri);
                            } else if (Config.LOGD) {
                                Log.d(Constants.TAG,
                                        "Couldn't resolve redirect URI for download " +
                                        mInfo.mId);
                            }
                            finalStatus = Downloads.STATUS_BAD_REQUEST;
                            request.abort();
                            break http_request_loop;
                        }
                        ++redirectCount;
                        finalStatus = Downloads.STATUS_RUNNING_PAUSED;
                        request.abort();
                        break http_request_loop;
                    }
                }
                if ((!continuingDownload && statusCode != Downloads.STATUS_SUCCESS)
                        || (continuingDownload && statusCode != 206)) {
                    if (Constants.LOGV) {
                        Log.d(Constants.TAG, "http error " + statusCode + " for " + mInfo.mUri);
                    } else if (Config.LOGD) {
                        Log.d(Constants.TAG, "http error " + statusCode + " for download " +
                                mInfo.mId);
                    }
                    if (Downloads.isStatusError(statusCode)) {
                        finalStatus = statusCode;
                    } else if (statusCode >= 300 && statusCode < 400) {
                        finalStatus = Downloads.STATUS_UNHANDLED_REDIRECT;
                    } else if (continuingDownload && statusCode == Downloads.STATUS_SUCCESS) {
                        finalStatus = Downloads.STATUS_PRECONDITION_FAILED;
                    } else {
                        finalStatus = Downloads.STATUS_UNHANDLED_HTTP_CODE;
                    }
                    request.abort();
                    break http_request_loop;
                } else {
                    // Handles the response, saves the file
                    if (Constants.LOGV) {
                        Log.v(Constants.TAG, "received response for " + mInfo.mUri);
                    }

                    if (!continuingDownload) {
                        Header header = response.getFirstHeader("Accept-Ranges");
                        if (header != null) {
                            headerAcceptRanges = header.getValue();
                        }
                        header = response.getFirstHeader("Content-Disposition");
                        if (header != null) {
                            headerContentDisposition = header.getValue();
                        }
                        header = response.getFirstHeader("Content-Location");
                        if (header != null) {
                            headerContentLocation = header.getValue();
                        }
                        if (mimeType == null) {
                            header = response.getFirstHeader("Content-Type");
                            if (header != null) {
                                mimeType = sanitizeMimeType(header.getValue()); 
                            }
                        }
                        header = response.getFirstHeader("ETag");
                        if (header != null) {
                            headerETag = header.getValue();
                        }
                        header = response.getFirstHeader("Transfer-Encoding");
                        if (header != null) {
                            headerTransferEncoding = header.getValue();
                        }
                        if (headerTransferEncoding == null) {
                            header = response.getFirstHeader("Content-Length");
                            if (header != null) {
                                headerContentLength = header.getValue();
                            }
                        } else {
                            // Ignore content-length with transfer-encoding - 2616 4.4 3
                            if (Constants.LOGVV) {
                                Log.v(Constants.TAG,
                                        "ignoring content-length because of xfer-encoding");
                            }
                        }
                        if (Constants.LOGVV) {
                            Log.v(Constants.TAG, "Accept-Ranges: " + headerAcceptRanges);
                            Log.v(Constants.TAG, "Content-Disposition: " +
                                    headerContentDisposition);
                            Log.v(Constants.TAG, "Content-Length: " + headerContentLength);
                            Log.v(Constants.TAG, "Content-Location: " + headerContentLocation);
                            Log.v(Constants.TAG, "Content-Type: " + mimeType);
                            Log.v(Constants.TAG, "ETag: " + headerETag);
                            Log.v(Constants.TAG, "Transfer-Encoding: " + headerTransferEncoding);
                        }

                        if (!mInfo.mNoIntegrity && headerContentLength == null &&
                                (headerTransferEncoding == null
                                        || !headerTransferEncoding.equalsIgnoreCase("chunked"))
                                ) {
                            if (Config.LOGD) {
                                Log.d(Constants.TAG, "can't know size of download, giving up");
                            }
                            finalStatus = Downloads.STATUS_LENGTH_REQUIRED;
                            request.abort();
                            break http_request_loop;
                        }

                        DownloadFileInfo fileInfo = Helpers.generateSaveFile(
                                mContext,
                                mInfo.mUri,
                                mInfo.mHint,
                                headerContentDisposition,
                                headerContentLocation,
                                mimeType,
                                mInfo.mDestination,
                                (headerContentLength != null) ?
                                        Integer.parseInt(headerContentLength) : 0);
                        if (fileInfo.mFileName == null) {
                            finalStatus = fileInfo.mStatus;
                            request.abort();
                            break http_request_loop;
                        }
                        filename = fileInfo.mFileName;
                        stream = fileInfo.mStream;
                        if (Constants.LOGV) {
                            Log.v(Constants.TAG, "writing " + mInfo.mUri + " to " + filename);
                        }

                        ContentValues values = new ContentValues();
                        values.put(Downloads._DATA, filename);
                        if (headerETag != null) {
                            values.put(Constants.ETAG, headerETag);
                        }
                        if (mimeType != null) {
                            values.put(Downloads.COLUMN_MIME_TYPE, mimeType);
                        }
                        int contentLength = -1;
                        if (headerContentLength != null) {
                            contentLength = Integer.parseInt(headerContentLength);
                        }
                        values.put(Downloads.COLUMN_TOTAL_BYTES, contentLength);
                        mContext.getContentResolver().update(contentUri, values, null, null);
                    }

                    InputStream entityStream;
                    try {
                        entityStream = response.getEntity().getContent();
                    } catch (IOException ex) {
                        if (Constants.LOGX) {
                            if (Helpers.isNetworkAvailable(mContext)) {
                                Log.i(Constants.TAG, "Get Failed " + mInfo.mId + ", Net Up");
                            } else {
                                Log.i(Constants.TAG, "Get Failed " + mInfo.mId + ", Net Down");
                            }
                        }
                        if (!Helpers.isNetworkAvailable(mContext)) {
                            finalStatus = Downloads.STATUS_RUNNING_PAUSED;
                        } else if (mInfo.mNumFailed < Constants.MAX_RETRIES) {
                            finalStatus = Downloads.STATUS_RUNNING_PAUSED;
                            countRetry = true;
                        } else {
                            if (Constants.LOGV) {
                                Log.d(Constants.TAG,
                                        "IOException getting entity for " +
                                        mInfo.mUri +
                                        " : " +
                                        ex);
                            } else if (Config.LOGD) {
                                Log.d(Constants.TAG, "IOException getting entity for download " +
                                        mInfo.mId + " : " + ex);
                            }
                            finalStatus = Downloads.STATUS_HTTP_DATA_ERROR;
                        }
                        request.abort();
                        break http_request_loop;
                    }
                    for (;;) {
                        int bytesRead;
                        try {
                            bytesRead = entityStream.read(data);
                        } catch (IOException ex) {
                            if (Constants.LOGX) {
                                if (Helpers.isNetworkAvailable(mContext)) {
                                    Log.i(Constants.TAG, "Read Failed " + mInfo.mId + ", Net Up");
                                } else {
                                    Log.i(Constants.TAG, "Read Failed " + mInfo.mId + ", Net Down");
                                }
                            }
                            ContentValues values = new ContentValues();
                            values.put(Downloads.COLUMN_CURRENT_BYTES, bytesSoFar);
                            mContext.getContentResolver().update(contentUri, values, null, null);
                            if (!mInfo.mNoIntegrity && headerETag == null) {
                                if (Constants.LOGV) {
                                    Log.v(Constants.TAG, "download IOException for " + mInfo.mUri +
                                            " : " + ex);
                                } else if (Config.LOGD) {
                                    Log.d(Constants.TAG, "download IOException for download " +
                                            mInfo.mId + " : " + ex);
                                }
                                if (Config.LOGD) {
                                    Log.d(Constants.TAG,
                                            "can't resume interrupted download with no ETag");
                                }
                                finalStatus = Downloads.STATUS_PRECONDITION_FAILED;
                            } else if (!Helpers.isNetworkAvailable(mContext)) {
                                finalStatus = Downloads.STATUS_RUNNING_PAUSED;
                            } else if (mInfo.mNumFailed < Constants.MAX_RETRIES) {
                                finalStatus = Downloads.STATUS_RUNNING_PAUSED;
                                countRetry = true;
                            } else {
                                if (Constants.LOGV) {
                                    Log.v(Constants.TAG, "download IOException for " + mInfo.mUri +
                                            " : " + ex);
                                } else if (Config.LOGD) {
                                    Log.d(Constants.TAG, "download IOException for download " +
                                            mInfo.mId + " : " + ex);
                                }
                                finalStatus = Downloads.STATUS_HTTP_DATA_ERROR;
                            }
                            request.abort();
                            break http_request_loop;
                        }
                        if (bytesRead == -1) { // success
                            ContentValues values = new ContentValues();
                            values.put(Downloads.COLUMN_CURRENT_BYTES, bytesSoFar);
                            if (headerContentLength == null) {
                                values.put(Downloads.COLUMN_TOTAL_BYTES, bytesSoFar);
                            }
                            mContext.getContentResolver().update(contentUri, values, null, null);
                            if ((headerContentLength != null)
                                    && (bytesSoFar
                                            != Integer.parseInt(headerContentLength))) {
                                if (!mInfo.mNoIntegrity && headerETag == null) {
                                    if (Constants.LOGV) {
                                        Log.d(Constants.TAG, "mismatched content length " +
                                                mInfo.mUri);
                                    } else if (Config.LOGD) {
                                        Log.d(Constants.TAG, "mismatched content length for " +
                                                mInfo.mId);
                                    }
                                    finalStatus = Downloads.STATUS_LENGTH_REQUIRED;
                                } else if (!Helpers.isNetworkAvailable(mContext)) {
                                    finalStatus = Downloads.STATUS_RUNNING_PAUSED;
                                } else if (mInfo.mNumFailed < Constants.MAX_RETRIES) {
                                    finalStatus = Downloads.STATUS_RUNNING_PAUSED;
                                    countRetry = true;
                                } else {
                                    if (Constants.LOGV) {
                                        Log.v(Constants.TAG, "closed socket for " + mInfo.mUri);
                                    } else if (Config.LOGD) {
                                        Log.d(Constants.TAG, "closed socket for download " +
                                                mInfo.mId);
                                    }
                                    finalStatus = Downloads.STATUS_HTTP_DATA_ERROR;
                                }
                                break http_request_loop;
                            }
                            break;
                        }
                        gotData = true;
                        for (;;) {
                            try {
                                if (stream == null) {
                                    stream = new FileOutputStream(filename, true);
                                }
                                stream.write(data, 0, bytesRead);
                                if (mInfo.mDestination == Downloads.DESTINATION_EXTERNAL
                                            && !DrmRawContent.DRM_MIMETYPE_MESSAGE_STRING
                                            .equalsIgnoreCase(mimeType)) {
                                    try {
                                        stream.close();
                                        stream = null;
                                    } catch (IOException ex) {
                                        if (Constants.LOGV) {
                                            Log.v(Constants.TAG,
                                                    "exception when closing the file " +
                                                    "during download : " + ex);
                                        }
                                        // nothing can really be done if the file can't be closed
                                    }
                                }
                                break;
                            } catch (IOException ex) {
                                if (!Helpers.discardPurgeableFiles(
                                        mContext, Constants.BUFFER_SIZE)) {
                                    finalStatus = Downloads.STATUS_FILE_ERROR;
                                    break http_request_loop;
                                }
                            }
                        }
                        bytesSoFar += bytesRead;
                        long now = System.currentTimeMillis();
                        if (bytesSoFar - bytesNotified > Constants.MIN_PROGRESS_STEP
                                && now - timeLastNotification
                                        > Constants.MIN_PROGRESS_TIME) {
                            ContentValues values = new ContentValues();
                            values.put(Downloads.COLUMN_CURRENT_BYTES, bytesSoFar);
                            mContext.getContentResolver().update(
                                    contentUri, values, null, null);
                            bytesNotified = bytesSoFar;
                            timeLastNotification = now;
                        }

                        if (Constants.LOGVV) {
                            Log.v(Constants.TAG, "downloaded " + bytesSoFar + " for " + mInfo.mUri);
                        }
                        synchronized (mInfo) {
                            if (mInfo.mControl == Downloads.CONTROL_PAUSED) {
                                if (Constants.LOGV) {
                                    Log.v(Constants.TAG, "paused " + mInfo.mUri);
                                }
                                finalStatus = Downloads.STATUS_RUNNING_PAUSED;
                                request.abort();
                                break http_request_loop;
                            }
                        }
                        if (mInfo.mStatus == Downloads.STATUS_CANCELED) {
                            if (Constants.LOGV) {
                                Log.d(Constants.TAG, "canceled " + mInfo.mUri);
                            } else if (Config.LOGD) {
                                // Log.d(Constants.TAG, "canceled id " + mInfo.mId);
                            }
                            finalStatus = Downloads.STATUS_CANCELED;
                            break http_request_loop;
                        }
                    }
                    if (Constants.LOGV) {
                        Log.v(Constants.TAG, "download completed for " + mInfo.mUri);
                    }
                    finalStatus = Downloads.STATUS_SUCCESS;
                }
                break;
            }
        } catch (FileNotFoundException ex) {
            if (Config.LOGD) {
                Log.d(Constants.TAG, "FileNotFoundException for " + filename + " : " +  ex);
            }
            finalStatus = Downloads.STATUS_FILE_ERROR;
            // falls through to the code that reports an error
        } catch (RuntimeException ex) { //sometimes the socket code throws unchecked exceptions
            if (Constants.LOGV) {
                Log.d(Constants.TAG, "Exception for " + mInfo.mUri, ex);
            } else if (Config.LOGD) {
                Log.d(Constants.TAG, "Exception for id " + mInfo.mId, ex);
            }
            finalStatus = Downloads.STATUS_UNKNOWN_ERROR;
            // falls through to the code that reports an error
        } finally {
            mInfo.mHasActiveThread = false;
            if (wakeLock != null) {
                wakeLock.release();
                wakeLock = null;
            }
            if (client != null) {
                client.close();
                client = null;
            }
            try {
                // close the file
                if (stream != null) {
                    stream.close();
                }
            } catch (IOException ex) {
                if (Constants.LOGV) {
                    Log.v(Constants.TAG, "exception when closing the file after download : " + ex);
                }
                // nothing can really be done if the file can't be closed
            }
            if (filename != null) {
                // if the download wasn't successful, delete the file
                if (Downloads.isStatusError(finalStatus)) {
                    new File(filename).delete();
                    filename = null;
                } else if (Downloads.isStatusSuccess(finalStatus) &&
                        DrmRawContent.DRM_MIMETYPE_MESSAGE_STRING
                        .equalsIgnoreCase(mimeType)) {
                    // transfer the file to the DRM content provider 
                    File file = new File(filename);
                    Intent item = DrmStore.addDrmFile(mContext.getContentResolver(), file, null);
                    if (item == null) {
                        Log.w(Constants.TAG, "unable to add file " + filename + " to DrmProvider");
                        finalStatus = Downloads.STATUS_UNKNOWN_ERROR;
                    } else {
                        filename = item.getDataString();
                        mimeType = item.getType();
                    }
                    
                    file.delete();
                } else if (Downloads.isStatusSuccess(finalStatus)) {
                    // make sure the file is readable
                    FileUtils.setPermissions(filename, 0644, -1, -1);

                    // Sync to storage after completion
                    try {
                        new FileOutputStream(filename, true).getFD().sync();
                    } catch (FileNotFoundException ex) {
                        Log.w(Constants.TAG, "file " + filename + " not found: " + ex);
                    } catch (SyncFailedException ex) {
                        Log.w(Constants.TAG, "file " + filename + " sync failed: " + ex);
                    } catch (IOException ex) {
                        Log.w(Constants.TAG, "IOException trying to sync " + filename + ": " + ex);
                    } catch (RuntimeException ex) {
                        Log.w(Constants.TAG, "exception while syncing file: ", ex);
                    }
                }
            }
            notifyDownloadCompleted(finalStatus, countRetry, retryAfter, redirectCount,
                    gotData, filename, newUri, mimeType);
        }
    }

    /**
     * Stores information about the completed download, and notifies the initiating application.
     */
    private void notifyDownloadCompleted(
            int status, boolean countRetry, int retryAfter, int redirectCount, boolean gotData,
            String filename, String uri, String mimeType) {
        notifyThroughDatabase(
                status, countRetry, retryAfter, redirectCount, gotData, filename, uri, mimeType);
        if (Downloads.isStatusCompleted(status)) {
            notifyThroughIntent();
        }
    }

    private void notifyThroughDatabase(
            int status, boolean countRetry, int retryAfter, int redirectCount, boolean gotData,
            String filename, String uri, String mimeType) {
        ContentValues values = new ContentValues();
        values.put(Downloads.COLUMN_STATUS, status);
        values.put(Downloads._DATA, filename);
        if (uri != null) {
            values.put(Downloads.COLUMN_URI, uri);
        }
        values.put(Downloads.COLUMN_MIME_TYPE, mimeType);
        values.put(Downloads.COLUMN_LAST_MODIFICATION, System.currentTimeMillis());
        values.put(Constants.RETRY_AFTER_X_REDIRECT_COUNT, retryAfter + (redirectCount << 28));
        if (!countRetry) {
            values.put(Constants.FAILED_CONNECTIONS, 0);
        } else if (gotData) {
            values.put(Constants.FAILED_CONNECTIONS, 1);
        } else {
            values.put(Constants.FAILED_CONNECTIONS, mInfo.mNumFailed + 1);
        }

        mContext.getContentResolver().update(
                ContentUris.withAppendedId(Downloads.CONTENT_URI, mInfo.mId), values, null, null);
    }

    /**
     * Notifies the initiating app if it requested it. That way, it can know that the
     * download completed even if it's not actively watching the cursor.
     */
    private void notifyThroughIntent() {
        Uri uri = Uri.parse(Downloads.CONTENT_URI + "/" + mInfo.mId);
        mInfo.sendIntentIfRequested(uri, mContext);
    }

    /**
     * Clean up a mimeType string so it can be used to dispatch an intent to
     * view a downloaded asset.
     * @param mimeType either null or one or more mime types (semi colon separated).
     * @return null if mimeType was null. Otherwise a string which represents a
     * single mimetype in lowercase and with surrounding whitespaces trimmed.
     */
    private String sanitizeMimeType(String mimeType) {
        try {
            mimeType = mimeType.trim().toLowerCase(Locale.ENGLISH);

            final int semicolonIndex = mimeType.indexOf(';');
            if (semicolonIndex != -1) {
                mimeType = mimeType.substring(0, semicolonIndex);
            }
            return mimeType;
        } catch (NullPointerException npe) {
            return null;
        }
    }
}
