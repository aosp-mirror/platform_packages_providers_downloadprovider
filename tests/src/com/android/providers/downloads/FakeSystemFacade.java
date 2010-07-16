package com.android.providers.downloads;

import android.net.ConnectivityManager;

public class FakeSystemFacade implements SystemFacade {
    long mTimeMillis = 0;
    Integer mActiveNetworkType = ConnectivityManager.TYPE_WIFI;
    boolean mIsRoaming = false;
    Integer mMaxBytesOverMobile = null;

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
}
