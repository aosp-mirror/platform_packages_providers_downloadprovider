package com.android.providers.downloads;

import android.app.Notification;
import android.content.Intent;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.ConnectivityManager;
import android.test.AssertionFailedError;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

public class FakeSystemFacade implements SystemFacade {
    long mTimeMillis = 0;
    Integer mActiveNetworkType = ConnectivityManager.TYPE_WIFI;
    boolean mIsRoaming = false;
    Long mMaxBytesOverMobile = null;
    List<Intent> mBroadcastsSent = new ArrayList<Intent>();
    Map<Integer,Notification> mActiveNotifications = new HashMap<Integer,Notification>();
    List<Notification> mCanceledNotifications = new ArrayList<Notification>();
    Queue<Thread> mStartedThreads = new LinkedList<Thread>();

    void incrementTimeMillis(long delta) {
        mTimeMillis += delta;
    }

    public long currentTimeMillis() {
        return mTimeMillis;
    }

    public Integer getActiveNetworkType() {
        return mActiveNetworkType;
    }

    public boolean isNetworkRoaming() {
        return mIsRoaming;
    }

    public Long getMaxBytesOverMobile() {
        return mMaxBytesOverMobile ;
    }

    @Override
    public void sendBroadcast(Intent intent) {
        mBroadcastsSent.add(intent);
    }

    @Override
    public boolean userOwnsPackage(int uid, String pckg) throws NameNotFoundException {
        return true;
    }

    @Override
    public void postNotification(int id, Notification notification) {
        if (notification == null) {
            throw new AssertionFailedError("Posting null notification");
        }
        mActiveNotifications.put(id, notification);
    }

    @Override
    public void cancelNotification(int id) {
        Notification notification = mActiveNotifications.remove(id);
        if (notification != null) {
            mCanceledNotifications.add(notification);
        }
    }

    @Override
    public void cancelAllNotifications() {
        for (int id : mActiveNotifications.keySet()) {
            cancelNotification(id);
        }
    }

    @Override
    public void startThread(Thread thread) {
        mStartedThreads.add(thread);
    }

    public void runAllThreads() {
        while (!mStartedThreads.isEmpty()) {
            mStartedThreads.poll().run();
        }
    }
}
