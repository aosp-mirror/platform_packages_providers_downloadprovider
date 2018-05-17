package com.android.providers.downloads;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
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
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.SSLContext;

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
    private SSLContext mSSLContext = null;

    public void setUp() {
        mTimeMillis = 0;
        mActiveNetworkType = ConnectivityManager.TYPE_WIFI;
        mIsRoaming = false;
        mIsMetered = false;
        mMaxBytesOverMobile = Long.MAX_VALUE;
        mRecommendedMaxBytesOverMobile = Long.MAX_VALUE;
        mBroadcastsSent.clear();
        mReturnActualTime = false;
        try {
            mSSLContext = SSLContext.getDefault();
        } catch (GeneralSecurityException e) {
            throw new RuntimeException(e);
        }
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
            return info;
        }
    }

    @Override
    public boolean isNetworkMetered(Network network) {
        return mIsMetered;
    }

    @Override
    public boolean isActiveNetworkMeteredForUid(int uid) {
        return mIsMetered;
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

    @Override
    public SSLContext getSSLContextForPackage(Context context, String pckg) {
        return mSSLContext;
    }

    public void setSSLContext(SSLContext context) {
        mSSLContext = context;
    }

    public void setReturnActualTime(boolean flag) {
        mReturnActualTime = flag;
    }
}
