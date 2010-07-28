
package com.android.providers.downloads;

import android.app.Notification;
import android.content.Intent;
import android.content.pm.PackageManager.NameNotFoundException;


interface SystemFacade {
    /**
     * @see System#currentTimeMillis()
     */
    public long currentTimeMillis();

    /**
     * @return Network type (as in ConnectivityManager.TYPE_*) of currently active network, or null
     * if there's no active connection.
     */
    public Integer getActiveNetworkType();

    /**
     * @see android.telephony.TelephonyManager#isNetworkRoaming
     */
    public boolean isNetworkRoaming();

    /**
     * @return maximum size, in bytes, of downloads that may go over a mobile connection; or null if
     * there's no limit
     */
    public Long getMaxBytesOverMobile();

    /**
     * Send a broadcast intent.
     */
    public void sendBroadcast(Intent intent);

    /**
     * Returns true if the specified UID owns the specified package name.
     */
    public boolean userOwnsPackage(int uid, String pckg) throws NameNotFoundException;

    /**
     * Post a system notification to the NotificationManager.
     */
    public void postNotification(int id, Notification notification);

    /**
     * Cancel a system notification.
     */
    public void cancelNotification(int id);

    /**
     * Cancel all system notifications.
     */
    public void cancelAllNotifications();

    /**
     * Start a thread.
     */
    public void startThread(Thread thread);
}
