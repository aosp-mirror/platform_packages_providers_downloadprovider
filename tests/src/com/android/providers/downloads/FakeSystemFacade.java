package com.android.providers.downloads;

import android.content.Intent;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.ConnectivityManager;

import java.util.ArrayList;
import java.util.List;

public class FakeSystemFacade implements SystemFacade {
    long mTimeMillis = 0;
    Integer mActiveNetworkType = ConnectivityManager.TYPE_WIFI;
    boolean mIsRoaming = false;
    Integer mMaxBytesOverMobile = null;
    List<Intent> mBroadcastsSent = new ArrayList<Intent>();

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

    public Integer getMaxBytesOverMobile() {
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
}
