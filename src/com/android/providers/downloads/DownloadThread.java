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

import org.apache.http.client.methods.AbortableHttpRequest;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.HttpClient;
import org.apache.http.entity.StringEntity;
import org.apache.http.Header;
import org.apache.http.HttpResponse;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.drm.mobile1.DrmRawContent;
import android.net.http.AndroidHttpClient;
import android.net.Uri;
import android.os.FileUtils;
import android.os.PowerManager;
import android.os.Process;
import android.provider.Downloads;
import android.provider.DrmStore;
import android.util.Config;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;

/**
 * Runs an actual download
 */
public class DownloadThread extends Thread {

    /** Tag used for debugging/logging */
    private static final String TAG = Constants.TAG;

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
        String userAgent = mInfo.userAgent;
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
        boolean gotData = false;
        String filename = null;
        String mimeType = mInfo.mimetype;
        FileOutputStream stream = null;
        AndroidHttpClient client = null;
        PowerManager.WakeLock wakeLock = null;
        Uri contentUri = Uri.parse(Downloads.CONTENT_URI + "/" + mInfo.id);

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
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
            wakeLock.acquire();

            if (mInfo.filename != null) {
                // We're resuming a download that got interrupted
                File f = new File(mInfo.filename);
                if (f.exists()) {
                    long fileLength = f.length();
                    if (fileLength == 0) {
                        // The download hadn't actually started, we can restart from scratch
                        f.delete();
                    } else if (mInfo.etag == null && !mInfo.noIntegrity) {
                        // Tough luck, that's not a resumable download
                        if (Config.LOGD) {
                            Log.d(TAG, "can't resume interrupted non-resumable download"); 
                        }
                        f.delete();
                        finalStatus = Downloads.STATUS_PRECONDITION_FAILED;
                        notifyDownloadCompleted(
                                finalStatus, false, false, mInfo.filename, mInfo.mimetype);
                        return;
                    } else {
                        // All right, we'll be able to resume this download
                        filename = mInfo.filename;
                        stream = new FileOutputStream(filename, true);
                        bytesSoFar = (int) fileLength;
                        if (mInfo.totalBytes != -1) {
                            headerContentLength = Integer.toString(mInfo.totalBytes);
                        }
                        headerETag = mInfo.etag;
                        continuingDownload = true;
                    }
                }
            }

            int bytesNotified = bytesSoFar;
            // starting with MIN_VALUE means that the first write will commit
            //     progress to the database
            long timeLastNotification = 0;

            client = AndroidHttpClient.newInstance(userAgent());

            if (stream != null && mInfo.destination == Downloads.DESTINATION_EXTERNAL
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
                HttpUriRequest requestU;
                AbortableHttpRequest requestA;
                if (mInfo.method == Downloads.METHOD_POST) {
                    HttpPost request = new HttpPost(mInfo.uri);
                    if (mInfo.entity != null) {
                        try {
                            request.setEntity(new StringEntity(mInfo.entity));
                        } catch (UnsupportedEncodingException ex) {
                            if (Config.LOGD) {
                                Log.d(TAG, "unsupported encoding for POST entity : " + ex); 
                            }
                            finalStatus = Downloads.STATUS_BAD_REQUEST;
                            break http_request_loop;
                        }
                    }
                    requestU = request;
                    requestA = request;
                } else {
                    HttpGet request = new HttpGet(mInfo.uri);
                    requestU = request;
                    requestA = request;
                }

                if (Constants.LOGV) {
                    Log.v(TAG, "initiating download for " + mInfo.uri);
                }

                if (mInfo.cookies != null) {
                    requestU.addHeader("Cookie", mInfo.cookies);
                }
                if (mInfo.referer != null) {
                    requestU.addHeader("Referer", mInfo.referer);
                }
                if (continuingDownload) {
                    if (headerETag != null) {
                        requestU.addHeader("If-Match", headerETag);
                    }
                    requestU.addHeader("Range", "bytes=" + bytesSoFar + "-");
                }

                HttpResponse response;
                try {
                    response = client.execute(requestU);
                } catch (IllegalArgumentException ex) {
                    if (Constants.LOGV) {
                        Log.d(TAG, "Arg exception trying to execute request for " + mInfo.uri +
                                " : " + ex);
                    } else if (Config.LOGD) {
                        Log.d(TAG, "Arg exception trying to execute request for " + mInfo.id +
                                " : " +  ex);
                    }
                    finalStatus = Downloads.STATUS_BAD_REQUEST;
                    requestA.abort();
                    break http_request_loop;
                } catch (IOException ex) {
                    if (!Helpers.isNetworkAvailable(mContext)) {
                        finalStatus = Downloads.STATUS_RUNNING_PAUSED;
                    } else if (mInfo.numFailed < Constants.MAX_RETRIES) {
                        finalStatus = Downloads.STATUS_RUNNING_PAUSED;
                        countRetry = true;
                    } else {
                        if (Constants.LOGV) {
                            Log.d(TAG, "IOException trying to execute request for " + mInfo.uri +
                                    " : " + ex);
                        } else if (Config.LOGD) {
                            Log.d(TAG, "IOException trying to execute request for " + mInfo.id +
                                    " : " + ex);
                        }
                        finalStatus = Downloads.STATUS_HTTP_DATA_ERROR;
                    }
                    requestA.abort();
                    break http_request_loop;
                }

                int statusCode = response.getStatusLine().getStatusCode();
                if ((!continuingDownload && statusCode != Downloads.STATUS_SUCCESS)
                        || (continuingDownload && statusCode != 206)) {
                    if (Constants.LOGV) {
                        Log.d(TAG, "http error " + statusCode + " for " + mInfo.uri);
                    } else if (Config.LOGD) {
                        Log.d(TAG, "http error " + statusCode + " for download " + mInfo.id);
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
                    requestA.abort();
                    break http_request_loop;
                } else {
                    // Handles the response, saves the file
                    if (Constants.LOGV) {
                        Log.v(TAG, "received response for " + mInfo.uri);
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
                                mimeType = header.getValue();
                                final int semicolonIndex = mimeType.indexOf(';');
                                if (semicolonIndex != -1) {
                                    mimeType = mimeType.substring(0, semicolonIndex);
                                }
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
                                Log.v(TAG, "ignoring content-length because of xfer-encoding");
                            }
                        }
                        if (Constants.LOGVV) {
                            Log.v(TAG, "Accept-Ranges: " + headerAcceptRanges);
                            Log.v(TAG, "Content-Disposition: " + headerContentDisposition);
                            Log.v(TAG, "Content-Length: " + headerContentLength);
                            Log.v(TAG, "Content-Location: " + headerContentLocation);
                            Log.v(TAG, "Content-Type: " + mimeType);
                            Log.v(TAG, "ETag: " + headerETag);
                            Log.v(TAG, "Transfer-Encoding: " + headerTransferEncoding);
                        }

                        if (!mInfo.noIntegrity && headerContentLength == null &&
                                (headerTransferEncoding == null
                                        || !headerTransferEncoding.equalsIgnoreCase("chunked"))
                                ) {
                            if (Config.LOGD) {
                                Log.d(TAG, "can't know size of download, giving up");
                            }
                            finalStatus = Downloads.STATUS_LENGTH_REQUIRED;
                            requestA.abort();
                            break http_request_loop;
                        }

                        DownloadFileInfo fileInfo = Helpers.generateSaveFile(
                                mContext,
                                mInfo.uri,
                                mInfo.hint,
                                headerContentDisposition,
                                headerContentLocation,
                                mimeType,
                                mInfo.destination,
                                mInfo.otaUpdate,
                                mInfo.noSystem,
                                (headerContentLength != null) ?
                                        Integer.parseInt(headerContentLength) : 0);
                        if (fileInfo.filename == null) {
                            finalStatus = fileInfo.status;
                            requestA.abort();
                            break http_request_loop;
                        }
                        filename = fileInfo.filename;
                        stream = fileInfo.stream;
                        if (Constants.LOGV) {
                            Log.v(TAG, "writing " + mInfo.uri + " to " + filename);
                        }

                        ContentValues values = new ContentValues();
                        values.put(Downloads.FILENAME, filename);
                        if (headerETag != null) {
                            values.put(Downloads.ETAG, headerETag);
                        }
                        if (mimeType != null) {
                            values.put(Downloads.MIMETYPE, mimeType);
                        }
                        int contentLength = -1;
                        if (headerContentLength != null) {
                            contentLength = Integer.parseInt(headerContentLength);
                        }
                        values.put(Downloads.TOTAL_BYTES, contentLength);
                        mContext.getContentResolver().update(contentUri, values, null, null);
                    }

                    InputStream entityStream;
                    try {
                        entityStream = response.getEntity().getContent();
                    } catch (IOException ex) {
                        if (!Helpers.isNetworkAvailable(mContext)) {
                            finalStatus = Downloads.STATUS_RUNNING_PAUSED;
                        } else if (mInfo.numFailed < Constants.MAX_RETRIES) {
                            finalStatus = Downloads.STATUS_RUNNING_PAUSED;
                            countRetry = true;
                        } else {
                            if (Constants.LOGV) {
                                Log.d(TAG, "IOException getting entity for " + mInfo.uri +
                                    " : " + ex);
                            } else if (Config.LOGD) {
                                Log.d(TAG, "IOException getting entity for download " + mInfo.id +
                                    " : " + ex);
                            }
                            finalStatus = Downloads.STATUS_HTTP_DATA_ERROR;
                        }
                        requestA.abort();
                        break http_request_loop;
                    }
                    for (;;) {
                        int bytesRead;
                        try {
                            bytesRead = entityStream.read(data);
                        } catch (IOException ex) {
                            ContentValues values = new ContentValues();
                            values.put(Downloads.CURRENT_BYTES, bytesSoFar);
                            mContext.getContentResolver().update(contentUri, values, null, null);
                            if (!mInfo.noIntegrity && headerETag == null) {
                                if (Constants.LOGV) {
                                    Log.v(TAG, "download IOException for " + mInfo.uri +
                                    " : " + ex);
                                } else if (Config.LOGD) {
                                    Log.d(TAG, "download IOException for download " + mInfo.id +
                                    " : " + ex);
                                }
                                if (Config.LOGD) {
                                    Log.d(Constants.TAG,
                                            "can't resume interrupted download with no ETag");
                                }
                                finalStatus = Downloads.STATUS_PRECONDITION_FAILED;
                            } else if (!Helpers.isNetworkAvailable(mContext)) {
                                finalStatus = Downloads.STATUS_RUNNING_PAUSED;
                            } else if (mInfo.numFailed < Constants.MAX_RETRIES) {
                                finalStatus = Downloads.STATUS_RUNNING_PAUSED;
                                countRetry = true;
                            } else {
                                if (Constants.LOGV) {
                                    Log.v(TAG, "download IOException for " + mInfo.uri +
                                    " : " + ex);
                                } else if (Config.LOGD) {
                                    Log.d(TAG, "download IOException for download " + mInfo.id +
                                    " : " + ex);
                                }
                                finalStatus = Downloads.STATUS_HTTP_DATA_ERROR;
                            }
                            requestA.abort();
                            break http_request_loop;
                        }
                        if (bytesRead == -1) { // success
                            ContentValues values = new ContentValues();
                            values.put(Downloads.CURRENT_BYTES, bytesSoFar);
                            if (headerContentLength == null) {
                                values.put(Downloads.TOTAL_BYTES, bytesSoFar);
                            }
                            mContext.getContentResolver().update(contentUri, values, null, null);
                            if ((headerContentLength != null)
                                    && (bytesSoFar
                                            != Integer.parseInt(headerContentLength))) {
                                if (Constants.LOGV) {
                                    Log.d(TAG, "mismatched content length " + mInfo.uri);
                                } else if (Config.LOGD) {
                                    Log.d(TAG, "mismatched content length for " + mInfo.id);
                                }
                                finalStatus = Downloads.STATUS_LENGTH_REQUIRED;
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
                                if (mInfo.destination == Downloads.DESTINATION_EXTERNAL
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
                            values.put(Downloads.CURRENT_BYTES, bytesSoFar);
                            mContext.getContentResolver().update(
                                    contentUri, values, null, null);
                            bytesNotified = bytesSoFar;
                            timeLastNotification = now;
                        }

                        if (Constants.LOGVV) {
                            Log.v(TAG, "downloaded " + bytesSoFar + " for " + mInfo.uri);
                        }
                        if (mInfo.status == Downloads.STATUS_CANCELED) {
                            if (Constants.LOGV) {
                                Log.d(TAG, "canceled " + mInfo.uri);
                            } else if (Config.LOGD) {
                                // Log.d(TAG, "canceled id " + mInfo.id);
                            }
                            finalStatus = Downloads.STATUS_CANCELED;
                            break http_request_loop;
                        }
                    }
                    if (Constants.LOGV) {
                        Log.v(TAG, "download completed for " + mInfo.uri);
                    }
                    finalStatus = Downloads.STATUS_SUCCESS;
                }
                break;
            }
        } catch (FileNotFoundException ex) {
            if (Config.LOGD) {
                Log.d(TAG, "FileNotFoundException for " + filename + " : " +  ex);
            }
            finalStatus = Downloads.STATUS_FILE_ERROR;
            // falls through to the code that reports an error
        } catch (Exception ex) { //sometimes the socket code throws unchecked exceptions
            if (Constants.LOGV) {
                Log.d(TAG, "Exception for " + mInfo.uri + " : " + ex);
            } else if (Config.LOGD) {
                Log.d(TAG, "Exception for id " + mInfo.id + " : " + ex);
            }
            finalStatus = Downloads.STATUS_UNKNOWN_ERROR;
            // falls through to the code that reports an error
        } finally {
            mInfo.hasActiveThread = false;
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
                        Log.w(TAG, "unable to add file " + filename + " to DrmProvider");
                        finalStatus = Downloads.STATUS_UNKNOWN_ERROR;
                    } else {
                        filename = item.getDataString();
                        mimeType = item.getType();
                    }
                    
                    file.delete();
                } else if (Downloads.isStatusSuccess(finalStatus)) {
                    // make sure the file is readable
                    FileUtils.setPermissions(filename, 0644, -1, -1);
                }
            }
            notifyDownloadCompleted(finalStatus, countRetry, gotData, filename, mimeType);
        }
    }

    /**
     * Stores information about the completed download, and notifies the initiating application.
     */
    private void notifyDownloadCompleted(
            int status, boolean countRetry, boolean gotData, String filename, String mimeType) {
        notifyThroughDatabase(status, countRetry, gotData, filename, mimeType);
        if (Downloads.isStatusCompleted(status)) {
            notifyThroughIntent();
        }
    }

    private void notifyThroughDatabase(
            int status, boolean countRetry, boolean gotData, String filename, String mimeType) {
        // Updates database when the download completes.
        Cursor cursor = null;

        String projection[] = {};
        cursor = mContext.getContentResolver().query(Downloads.CONTENT_URI,
                projection, Downloads._ID + "=" + mInfo.id, null, null);

        if (cursor != null) {
            // Looping makes the code more solid in case there are 2 entries with the same id
            while (cursor.moveToNext()) {
                cursor.updateInt(cursor.getColumnIndexOrThrow(Downloads.STATUS), status);
                cursor.updateString(cursor.getColumnIndexOrThrow(Downloads.FILENAME), filename);
                cursor.updateString(cursor.getColumnIndexOrThrow(Downloads.MIMETYPE), mimeType);
                cursor.updateLong(cursor.getColumnIndexOrThrow(Downloads.LAST_MODIFICATION),
                        System.currentTimeMillis());
                if (!countRetry) {
                    // if there's no reason to get delayed retry, clear this field
                    cursor.updateInt(cursor.getColumnIndexOrThrow(Downloads.FAILED_CONNECTIONS), 0);
                } else if (gotData) {
                    // if there's a reason to get a delayed retry but we got some data in this
                    //     try, reset the retry count.
                    cursor.updateInt(cursor.getColumnIndexOrThrow(Downloads.FAILED_CONNECTIONS), 1);
                } else {
                    // should get a retry and didn't make any progress this time - increment count
                    cursor.updateInt(cursor.getColumnIndexOrThrow(Downloads.FAILED_CONNECTIONS),
                            mInfo.numFailed + 1);
                }
            }
            cursor.commitUpdates();
            cursor.close();
        }
    }

    /**
     * Notifies the initiating app if it requested it. That way, it can know that the
     * download completed even if it's not actively watching the cursor.
     */
    private void notifyThroughIntent() {
        Uri uri = Uri.parse(Downloads.CONTENT_URI + "/" + mInfo.id);
        Intent intent = new Intent(Downloads.DOWNLOAD_COMPLETED_ACTION);
        intent.setData(uri);
        mContext.sendBroadcast(intent, "android.permission.ACCESS_DOWNLOAD_DATA");
        mInfo.sendIntentIfRequested(uri, mContext);
    }

}
