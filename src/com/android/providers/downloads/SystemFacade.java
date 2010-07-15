
package com.android.providers.downloads;

import java.util.BitSet;

interface SystemFacade {
    /**
     * @see System#currentTimeMillis()
     */
    public long currentTimeMillis();

    /**
     * @return Network types (as in ConnectivityManager.TYPE_*) of all connected networks.
     */
    public BitSet getConnectedNetworkTypes();

    /**
     * @see android.telephony.TelephonyManager#isNetworkRoaming
     */
    public boolean isNetworkRoaming();
}
