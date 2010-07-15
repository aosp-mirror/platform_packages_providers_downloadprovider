package com.android.providers.downloads;

import android.net.ConnectivityManager;

import java.util.BitSet;

public class FakeSystemFacade implements SystemFacade {
    long mTimeMillis = 0;
    Integer mActiveNetworkType = ConnectivityManager.TYPE_WIFI;
    boolean mIsRoaming = false;

    void incrementTimeMillis(long delta) {
        mTimeMillis += delta;
    }

    public long currentTimeMillis() {
        return mTimeMillis;
    }

    public BitSet getConnectedNetworkTypes() {
        BitSet connectedTypes = new BitSet();
        if (mActiveNetworkType != null) {
            connectedTypes.set(mActiveNetworkType);
        }
        return connectedTypes;
    }

    public boolean isNetworkRoaming() {
        return mIsRoaming;
    }
}
