/*
 * Copyright (C) 2011 The Android Open Source Project
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

import android.content.res.Resources;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;

public class DownloadHandler {

    private static final String TAG = "DownloadHandler";
    private final LinkedHashMap<Long, DownloadInfo> mDownloadsQueue =
            new LinkedHashMap<Long, DownloadInfo>();
    private final HashMap<Long, DownloadInfo> mDownloadsInProgress =
            new HashMap<Long, DownloadInfo>();
    private static final DownloadHandler mDownloadHandler = new DownloadHandler();
    private final int mMaxConcurrentDownloadsAllowed = Resources.getSystem().getInteger(
            com.android.internal.R.integer.config_MaxConcurrentDownloadsAllowed);

    static DownloadHandler getInstance() {
        return mDownloadHandler;
    }

    synchronized void enqueueDownload(DownloadInfo info) {
        if (!mDownloadsQueue.containsKey(info.mId)) {
            if (Constants.LOGV) {
                Log.i(TAG, "enqueued download. id: " + info.mId + ", uri: " + info.mUri);
            }
            mDownloadsQueue.put(info.mId, info);
            startDownloadThread();
        }
    }

    private synchronized void startDownloadThread() {
        Iterator<Long> keys = mDownloadsQueue.keySet().iterator();
        ArrayList<Long> ids = new ArrayList<Long>();
        while (mDownloadsInProgress.size() < mMaxConcurrentDownloadsAllowed && keys.hasNext()) {
            Long id = keys.next();
            DownloadInfo info = mDownloadsQueue.get(id);
            info.startDownloadThread();
            ids.add(id);
            mDownloadsInProgress.put(id, mDownloadsQueue.get(id));
            if (Constants.LOGV) {
                Log.i(TAG, "started download for : " + id);
            }
        }
        for (Long id : ids) {
            mDownloadsQueue.remove(id);
        }
    }

    synchronized boolean hasDownloadInQueue(long id) {
        return mDownloadsQueue.containsKey(id) || mDownloadsInProgress.containsKey(id);
    }

    synchronized void dequeueDownload(long mId) {
        mDownloadsInProgress.remove(mId);
        startDownloadThread();
        if (mDownloadsInProgress.size() == 0 && mDownloadsQueue.size() == 0) {
            notifyAll();
        }
    }

    // right now this is only used by tests. but there is no reason why it can't be used
    // by any module using DownloadManager (TODO add API to DownloadManager.java)
    public synchronized void WaitUntilDownloadsTerminate() throws InterruptedException {
        if (mDownloadsInProgress.size() == 0 && mDownloadsQueue.size() == 0) {
            if (Constants.LOGVV) {
                Log.i(TAG, "nothing to wait on");
            }
            return;
        }
        if (Constants.LOGVV) {
            for (DownloadInfo info : mDownloadsInProgress.values()) {
                Log.i(TAG, "** progress: " + info.mId + ", " + info.mUri);
            }
            for (DownloadInfo info : mDownloadsQueue.values()) {
                Log.i(TAG, "** in Q: " + info.mId + ", " + info.mUri);
            }
        }
        if (Constants.LOGVV) {
            Log.i(TAG, "waiting for 5 sec");
        }
        // wait upto 5 sec
        wait(5 * 1000);
    }
}
