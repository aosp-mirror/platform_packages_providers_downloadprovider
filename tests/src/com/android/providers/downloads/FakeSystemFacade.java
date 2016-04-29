package com.android.providers.downloads;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Intent;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkInfo;
import android.net.NetworkInfo.DetailedState;

import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.List;

public class FakeSystemFacade implements SystemFacade {
    long mTimeMillis = 0;
    Integer mActiveNetworkType = ConnectivityManager.TYPE_WIFI;
    boolean mIsRoaming = false;
    boolean mIsMetered = false;
    long mMaxBytesOverMobile = Long.MAX_VALUE;
    long mRecommendedMaxBytesOverMobile = Long.MAX_VALUE;
    List<Intent> mBroadcastsSent = new ArrayList<Intent>();
    boolean mCleartextTrafficPermitted = true;
    private boolean mReturnActualTime = false;

    public void setUp() {
        mTimeMillis = 0;
        mActiveNetworkType = ConnectivityManager.TYPE_WIFI;
        mIsRoaming = false;
        mIsMetered = false;
        mMaxBytesOverMobile = Long.MAX_VALUE;
        mRecommendedMaxBytesOverMobile = Long.MAX_VALUE;
        mBroadcastsSent.clear();
        mReturnActualTime = false;
    }

    void incrementTimeMillis(long delta) {
        mTimeMillis += delta;
    }

    @Override
    public long currentTimeMillis() {
        if (mReturnActualTime) {
            return System.currentTimeMillis();
        }
        return mTimeMillis;
    }

    @Override
    public Network getActiveNetwork(int uid, boolean ignoreBlocked) {
        if (mActiveNetworkType == null) {
            return null;
        } else {
            final Network network = mock(Network.class);
            try {
                when(network.openConnection(any())).then(new Answer<URLConnection>() {
                    @Override
                    public URLConnection answer(InvocationOnMock invocation) throws Throwable {
                        final URL url = (URL) invocation.getArguments()[0];
                        return url.openConnection();
                    }
                });
            } catch (IOException ignored) {
            }
            return network;
        }
    }

    @Override
    public NetworkInfo getNetworkInfo(Network network, int uid, boolean ignoreBlocked) {
        if (mActiveNetworkType == null) {
            return null;
        } else {
            final NetworkInfo info = new NetworkInfo(mActiveNetworkType, 0, null, null);
            info.setDetailedState(DetailedState.CONNECTED, null, null);
            info.setRoaming(mIsRoaming);
            info.setMetered(mIsMetered);
            return info;
        }
    }

    @Override
    public long getMaxBytesOverMobile() {
        return mMaxBytesOverMobile;
    }

    @Override
    public long getRecommendedMaxBytesOverMobile() {
        return mRecommendedMaxBytesOverMobile;
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
    public boolean isCleartextTrafficPermitted(int uid) {
        return mCleartextTrafficPermitted;
    }

    public void setReturnActualTime(boolean flag) {
        mReturnActualTime = flag;
    }
}
