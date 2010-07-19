package com.android.providers.downloads;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.util.BitSet;

class RealSystemFacade implements SystemFacade {
    private Context mContext;

    public RealSystemFacade(Context context) {
        mContext = context;
    }

    public long currentTimeMillis() {
        return System.currentTimeMillis();
    }

    public BitSet getConnectedNetworkTypes() {
        BitSet connectedTypes = new BitSet();

        ConnectivityManager connectivity =
                (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivity == null) {
            Log.w(Constants.TAG, "couldn't get connectivity manager");
            return connectedTypes;
        }

        NetworkInfo[] infos = connectivity.getAllNetworkInfo();
        if (infos != null) {
            for (NetworkInfo info : infos) {
                if (info.getState() == NetworkInfo.State.CONNECTED) {
                    connectedTypes.set(info.getType());
                }
            }
        }

        if (Constants.LOGVV) {
            boolean isConnected = !connectedTypes.isEmpty();
            Log.v(Constants.TAG, "network is " + (isConnected ? "" : "not ") + "available");
        }
        return connectedTypes;
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
        if (Constants.LOGVV) {
            Log.v(Constants.TAG, "network is mobile: " + isMobile);
            Log.v(Constants.TAG, "network is roaming: " + isRoaming);
        }
        return isMobile && isRoaming;
    }
}
