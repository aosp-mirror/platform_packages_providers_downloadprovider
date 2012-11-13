package com.android.providers.downloads;

import android.content.Intent;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.NetworkInfo.DetailedState;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

public class FakeSystemFacade implements SystemFacade {
    long mTimeMillis = 0;
    Integer mActiveNetworkType = ConnectivityManager.TYPE_WIFI;
    boolean mIsRoaming = false;
    boolean mIsMetered = false;
    Long mMaxBytesOverMobile = null;
    Long mRecommendedMaxBytesOverMobile = null;
    List<Intent> mBroadcastsSent = new ArrayList<Intent>();
    Queue<Thread> mStartedThreads = new LinkedList<Thread>();
    private boolean returnActualTime = false;

    void incrementTimeMillis(long delta) {
        mTimeMillis += delta;
    }

    public long currentTimeMillis() {
        if (returnActualTime) {
            return System.currentTimeMillis();
        }
        return mTimeMillis;
    }

    public NetworkInfo getActiveNetworkInfo(int uid) {
        if (mActiveNetworkType == null) {
            return null;
        } else {
            final NetworkInfo info = new NetworkInfo(mActiveNetworkType, 0, null, null);
            info.setDetailedState(DetailedState.CONNECTED, null, null);
            return info;
        }
    }

    @Override
    public boolean isActiveNetworkMetered() {
        return mIsMetered;
    }

    public boolean isNetworkRoaming() {
        return mIsRoaming;
    }

    public Long getMaxBytesOverMobile() {
        return mMaxBytesOverMobile ;
    }

    public Long getRecommendedMaxBytesOverMobile() {
        return mRecommendedMaxBytesOverMobile ;
    }

    @Override
    public void sendBroadcast(Intent intent) {
        mBroadcastsSent.add(intent);
    }

    @Override
    public boolean userOwnsPackage(int uid, String pckg) throws NameNotFoundException {
        return true;
    }

    public boolean startThreadsWithoutWaiting = false;
    public void setStartThreadsWithoutWaiting(boolean flag) {
        this.startThreadsWithoutWaiting = flag;
    }

    @Override
    public void startThread(Thread thread) {
        if (startThreadsWithoutWaiting) {
            thread.start();
        } else {
            mStartedThreads.add(thread);
        }
    }

    public void runAllThreads() {
        while (!mStartedThreads.isEmpty()) {
            mStartedThreads.poll().run();
        }
    }

    public void setReturnActualTime(boolean flag) {
        returnActualTime = flag;
    }
}
