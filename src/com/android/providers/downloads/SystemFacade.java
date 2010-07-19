
package com.android.providers.downloads;


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
    public Integer getMaxBytesOverMobile();
}
