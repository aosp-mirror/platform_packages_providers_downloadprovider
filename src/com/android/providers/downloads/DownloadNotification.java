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

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.Downloads;
import android.widget.RemoteViews;

import java.util.HashMap;

/**
 * This class handles the updating of the Notification Manager for the 
 * cases where there is an ongoing download. Once the download is complete
 * (be it successful or unsuccessful) it is no longer the responsibility 
 * of this component to show the download in the notification manager.
 *
 */
class DownloadNotification {

    Context mContext;
    public NotificationManager mNotificationMgr;
    HashMap <String, NotificationItem> mNotifications;
    
    static final String LOGTAG = "DownloadNotification";
    static final String WHERE_RUNNING = 
        "(" + Downloads.STATUS + " >= '100') AND (" +
        Downloads.STATUS + " <= '199') AND (" +
        Downloads.VISIBILITY + " IS NULL OR " +
        Downloads.VISIBILITY + " == '" + Downloads.VISIBILITY_VISIBLE + "' OR " +
        Downloads.VISIBILITY + " == '" + Downloads.VISIBILITY_VISIBLE_NOTIFY_COMPLETED + "')";
    static final String WHERE_COMPLETED =
        Downloads.STATUS + " >= '200' AND " +
        Downloads.VISIBILITY + " == '" + Downloads.VISIBILITY_VISIBLE_NOTIFY_COMPLETED + "'";
    
    
    /**
     * This inner class is used to collate downloads that are owned by
     * the same application. This is so that only one notification line
     * item is used for all downloads of a given application.
     *
     */
    static class NotificationItem {
        int id;  // This first db _id for the download for the app
        int totalCurrent = 0;
        int totalTotal = 0;
        int titleCount = 0;
        String packageName;  // App package name
        String description;
        String[] titles = new String[2]; // download titles.
        
        /*
         * Add a second download to this notification item.
         */
        void addItem(String title, int currentBytes, int totalBytes) {
            totalCurrent += currentBytes;
            if (totalBytes <= 0 || totalTotal == -1) {
                totalTotal = -1;
            } else {
                totalTotal += totalBytes;
            }
            if (titleCount < 2) {
                titles[titleCount] = title;
            }
            titleCount++;
        }
    }
        
    
    /**
     * Constructor
     * @param ctx The context to use to obtain access to the 
     *            Notification Service
     */
    DownloadNotification(Context ctx) {
        mContext = ctx;
        mNotificationMgr = (NotificationManager) mContext
                .getSystemService(Context.NOTIFICATION_SERVICE);
        mNotifications = new HashMap<String, NotificationItem>();
    }
    
    /*
     * Update the notification ui. 
     */
    public void updateNotification() {
        updateActiveNotification();
        updateCompletedNotification();
    }

    private void updateActiveNotification() {
        // Active downloads
        Cursor c = mContext.getContentResolver().query(
                Downloads.CONTENT_URI, new String [] {
                        Downloads._ID, Downloads.TITLE, Downloads.DESCRIPTION,
                        Downloads.NOTIFICATION_PACKAGE,
                        Downloads.NOTIFICATION_CLASS,
                        Downloads.CURRENT_BYTES, Downloads.TOTAL_BYTES,
                        Downloads.STATUS, Downloads._DATA
                },
                WHERE_RUNNING, null, Downloads._ID);
        
        if (c == null) {
            return;
        }
        
        // Columns match projection in query above
        final int idColumn = 0;
        final int titleColumn = 1;
        final int descColumn = 2;
        final int ownerColumn = 3;
        final int classOwnerColumn = 4;
        final int currentBytesColumn = 5;
        final int totalBytesColumn = 6;
        final int statusColumn = 7;
        final int filenameColumnId = 8;

        // Collate the notifications
        mNotifications.clear();
        for (c.moveToFirst(); !c.isAfterLast(); c.moveToNext()) {
            String packageName = c.getString(ownerColumn);
            int max = c.getInt(totalBytesColumn);
            int progress = c.getInt(currentBytesColumn);
            String title = c.getString(titleColumn);
            if (title == null || title.length() == 0) {
                title = mContext.getResources().getString(
                        R.string.download_unknown_title);
            }
            if (mNotifications.containsKey(packageName)) {
                mNotifications.get(packageName).addItem(title, progress, max);
            } else {
                NotificationItem item = new NotificationItem();
                item.id = c.getInt(idColumn);
                item.packageName = packageName;
                item.description = c.getString(descColumn);
                String className = c.getString(classOwnerColumn);
                item.addItem(title, progress, max);
                mNotifications.put(packageName, item);
            }
            
        }
        c.close();
        
        // Add the notifications
        for (NotificationItem item : mNotifications.values()) {
            // Build the notification object
            Notification n = new Notification();
            n.icon = android.R.drawable.stat_sys_download;

            n.flags |= Notification.FLAG_ONGOING_EVENT;
            
            // Build the RemoteView object
            RemoteViews expandedView = new RemoteViews(
                    "com.android.providers.downloads",
                    R.layout.status_bar_ongoing_event_progress_bar);
            StringBuilder title = new StringBuilder(item.titles[0]);
            if (item.titleCount > 1) {
                title.append(mContext.getString(R.string.notification_filename_separator));
                title.append(item.titles[1]);
                n.number = item.titleCount;
                if (item.titleCount > 2) {
                    title.append(mContext.getString(R.string.notification_filename_extras,
                            new Object[] { Integer.valueOf(item.titleCount - 2) }));
                }
            } else {
                expandedView.setTextViewText(R.id.description, 
                        item.description);
            }
            expandedView.setTextViewText(R.id.title, title);
            expandedView.setProgressBar(R.id.progress_bar, 
                    item.totalTotal, 
                    item.totalCurrent, 
                    item.totalTotal == -1);
            expandedView.setTextViewText(R.id.progress_text, 
                    getDownloadingText(item.totalTotal, item.totalCurrent));
            expandedView.setImageViewResource(R.id.appIcon,
                    android.R.drawable.stat_sys_download);
            n.contentView = expandedView;

            Intent intent = new Intent(Constants.ACTION_LIST);
            intent.setClassName("com.android.providers.downloads",
                    DownloadReceiver.class.getName());
            intent.setData(Uri.parse(Downloads.CONTENT_URI + "/" + item.id));
            intent.putExtra("multiple", item.titleCount > 1);

            n.contentIntent = PendingIntent.getBroadcast(mContext, 0, intent, 0);

            mNotificationMgr.notify(item.id, n);
            
        }
    }

    private void updateCompletedNotification() {
        // Completed downloads
        Cursor c = mContext.getContentResolver().query(
                Downloads.CONTENT_URI, new String [] {
                        Downloads._ID, Downloads.TITLE, Downloads.DESCRIPTION,
                        Downloads.NOTIFICATION_PACKAGE,
                        Downloads.NOTIFICATION_CLASS,
                        Downloads.CURRENT_BYTES, Downloads.TOTAL_BYTES,
                        Downloads.STATUS, Downloads._DATA,
                        Downloads.LAST_MODIFICATION, Downloads.DESTINATION
                },
                WHERE_COMPLETED, null, Downloads._ID);
        
        if (c == null) {
            return;
        }
        
        // Columns match projection in query above
        final int idColumn = 0;
        final int titleColumn = 1;
        final int descColumn = 2;
        final int ownerColumn = 3;
        final int classOwnerColumn = 4;
        final int currentBytesColumn = 5;
        final int totalBytesColumn = 6;
        final int statusColumn = 7;
        final int filenameColumnId = 8;
        final int lastModColumnId = 9;
        final int destinationColumnId = 10;

        for (c.moveToFirst(); !c.isAfterLast(); c.moveToNext()) {
            // Add the notifications
            Notification n = new Notification();
            n.icon = android.R.drawable.stat_sys_download_done;

            String title = c.getString(titleColumn);
            if (title == null || title.length() == 0) {
                title = mContext.getResources().getString(
                        R.string.download_unknown_title);
            }
            Uri contentUri = Uri.parse(Downloads.CONTENT_URI + "/" + c.getInt(idColumn));
            String caption;
            Intent intent;
            if (Downloads.isStatusError(c.getInt(statusColumn))) {
                caption = mContext.getResources()
                        .getString(R.string.notification_download_failed);
                intent = new Intent(Constants.ACTION_LIST);
            } else {
                caption = mContext.getResources()
                        .getString(R.string.notification_download_complete);
                if (c.getInt(destinationColumnId) == Downloads.DESTINATION_EXTERNAL) {
                    intent = new Intent(Constants.ACTION_OPEN);
                } else {
                    intent = new Intent(Constants.ACTION_LIST);
                }
            }
            intent.setClassName("com.android.providers.downloads",
                    DownloadReceiver.class.getName());
            intent.setData(contentUri);
            n.setLatestEventInfo(mContext, title, caption,
                    PendingIntent.getBroadcast(mContext, 0, intent, 0));

            intent = new Intent(Constants.ACTION_HIDE);
            intent.setClassName("com.android.providers.downloads",
                    DownloadReceiver.class.getName());
            intent.setData(contentUri);
            n.deleteIntent = PendingIntent.getBroadcast(mContext, 0, intent, 0);

            n.when = c.getLong(lastModColumnId);

            mNotificationMgr.notify(c.getInt(idColumn), n);
        }
        c.close();
    }

    /*
     * Helper function to build the downloading text.
     */
    private String getDownloadingText(long totalBytes, long currentBytes) {
        if (totalBytes <= 0) {
            return "";
        }
        long progress = currentBytes * 100 / totalBytes;
        StringBuilder sb = new StringBuilder();
        sb.append(progress);
        sb.append('%');
        return sb.toString();
    }
    
}
