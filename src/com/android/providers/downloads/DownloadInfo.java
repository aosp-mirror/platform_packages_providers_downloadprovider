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

import android.net.Uri;
import android.content.Context;
import android.content.Intent;
import android.provider.Downloads;

/**
 * Stores information about an individual download.
 */
public class DownloadInfo {
    public int id;
    public String uri;
    public int method;
    public String entity;
    public boolean noIntegrity;
    public String hint;
    public String filename;
    public boolean otaUpdate;
    public String mimetype;
    public int destination;
    public boolean noSystem;
    public int visibility;
    public int control;
    public int status;
    public int numFailed;
    public long lastMod;
    public String pckg;
    public String clazz;
    public String extras;
    public String cookies;
    public String userAgent;
    public String referer;
    public int totalBytes;
    public int currentBytes;
    public String etag;
    public boolean mediaScanned;

    public volatile boolean hasActiveThread;

    public DownloadInfo(int id, String uri, int method, String entity, boolean noIntegrity,
            String hint, String filename, boolean otaUpdate,
            String mimetype, int destination, boolean noSystem, int visibility,
            int control, int status, int numFailed, long lastMod,
            String pckg, String clazz, String extras, String cookies,
            String userAgent, String referer, int totalBytes, int currentBytes, String etag,
            boolean mediaScanned) {
        this.id = id;
        this.uri = uri;
        this.method = method;
        this.entity = entity;
        this.noIntegrity = noIntegrity;
        this.hint = hint;
        this.filename = filename;
        this.otaUpdate = otaUpdate;
        this.mimetype = mimetype;
        this.destination = destination;
        this.noSystem = noSystem;
        this.visibility = visibility;
        this.control = control;
        this.status = status;
        this.numFailed = numFailed;
        this.lastMod = lastMod;
        this.pckg = pckg;
        this.clazz = clazz;
        this.extras = extras;
        this.cookies = cookies;
        this.userAgent = userAgent;
        this.referer = referer;
        this.totalBytes = totalBytes;
        this.currentBytes = currentBytes;
        this.etag = etag;
        this.mediaScanned = mediaScanned;
    }

    public void sendIntentIfRequested(Uri contentUri, Context context) {
        if (pckg != null && clazz != null) {
            Intent intent = new Intent(Downloads.DOWNLOAD_COMPLETED_ACTION);
            intent.setClassName(pckg, clazz);
            if (extras != null) {
                intent.putExtra(Downloads.NOTIFICATION_EXTRAS, extras);
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
        return lastMod + Constants.RETRY_FIRST_DELAY * 1000 * (1 << (numFailed - 1));
    }

    /**
     * Returns whether this download should be started at the time when
     * it's first inserted in the database.
     */
    public boolean isReadyToStart(long now) {
        if (status == 0) {
            // status hasn't been initialized yet, this is a new download
            return true;
        }
        if (status == Downloads.STATUS_PENDING) {
            // download is explicit marked as ready to start
            return true;
        }
        if (status == Downloads.STATUS_RUNNING) {
            // download was interrupted (process killed, loss of power) while it was running,
            //     without a chance to update the database
            return true;
        }
        if (status == Downloads.STATUS_RUNNING_PAUSED) {
            if (numFailed == 0) {
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
     * Returns whether this download should be restarted at the time when
     * it was already known by the download manager
     */
    public boolean isReadyToRestart(long now) {
        if (status == 0) {
            // download hadn't been initialized yet
            return true;
        }
        if (status == Downloads.STATUS_PENDING) {
            // download is explicit marked as ready to start
            return true;
        }
        if (status == Downloads.STATUS_RUNNING_PAUSED) {
            if (numFailed == 0) {
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
        if (!Downloads.isStatusCompleted(status)) {
            return false;
        }
        if (visibility == Downloads.VISIBILITY_VISIBLE_NOTIFY_COMPLETED) {
            return true;
        }
        return false;
    }
}
