package com.android.providers.downloads;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.telephony.TelephonyManager;
import android.util.Log;

class RealSystemFacade implements SystemFacade {
    private Context mContext;

    public RealSystemFacade(Context context) {
        mContext = context;
    }

    public long currentTimeMillis() {
        return System.currentTimeMillis();
    }

    public Integer getActiveNetworkType() {
        ConnectivityManager connectivity =
                (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivity == null) {
            Log.w(Constants.TAG, "couldn't get connectivity manager");
            return null;
        }

        NetworkInfo activeInfo = connectivity.getActiveNetworkInfo();
        if (activeInfo == null) {
            if (Constants.LOGVV) {
                Log.v(Constants.TAG, "network is not available");
            }
            return null;
        }
        return activeInfo.getType();
    }

    public boolean isNetworkRoaming() {
        ConnectivityManager connectivity =
            (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivity == null) {
            Log.w(Constants.TAG, "couldn't get connectivity manager");
            return false;
        }

        NetworkInfo info = connectivity.getActiveNetworkInfo();
        boolean isMobile = (info != null && info.getType() == ConnectivityManager.TYPE_MOBILE);
        boolean isRoaming = isMobile && TelephonyManager.getDefault().isNetworkRoaming();
        if (Constants.LOGVV && isRoaming) {
            Log.v(Constants.TAG, "network is roaming");
        }
        return isRoaming;
    }

    public Integer getMaxBytesOverMobile() {
        return null;
    }
}
