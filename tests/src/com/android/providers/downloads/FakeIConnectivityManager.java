/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.providers.downloads;

import android.net.ConnectivityManager;
import android.net.IConnectivityManager;
import android.net.NetworkInfo;
import android.net.NetworkInfo.State;
import android.os.IBinder;
import android.os.RemoteException;

class FakeIConnectivityManager implements IConnectivityManager {
    private static class MockNetworkInfo extends NetworkInfo {
        private State mState;

        @SuppressWarnings("deprecation")
        public MockNetworkInfo(State state) {
            super(0);
            mState = state;
        }

        @Override
        public State getState() {
            return mState;
        }

        @Override
        public int getType() {
            return ConnectivityManager.TYPE_MOBILE;
        }
    }

    private State mCurrentState = State.CONNECTED;

    public void setNetworkState(State state) {
        mCurrentState = state;
    }

    public IBinder asBinder() {
        throw new UnsupportedOperationException();
    }

    public NetworkInfo getActiveNetworkInfo() throws RemoteException {
        return new MockNetworkInfo(mCurrentState);
    }

    public NetworkInfo[] getAllNetworkInfo() throws RemoteException {
        return new NetworkInfo[] {getActiveNetworkInfo()};
    }

    public boolean getBackgroundDataSetting() throws RemoteException {
        throw new UnsupportedOperationException();
    }

    public int getLastTetherError(String iface) throws RemoteException {
        throw new UnsupportedOperationException();
    }

    public boolean getMobileDataEnabled() throws RemoteException {
        throw new UnsupportedOperationException();
    }

    public NetworkInfo getNetworkInfo(int networkType) throws RemoteException {
        throw new UnsupportedOperationException();
    }

    public int getNetworkPreference() throws RemoteException {
        throw new UnsupportedOperationException();
    }

    public String[] getTetherableIfaces() throws RemoteException {
        throw new UnsupportedOperationException();
    }

    public String[] getTetherableUsbRegexs() throws RemoteException {
        throw new UnsupportedOperationException();
    }

    public String[] getTetherableWifiRegexs() throws RemoteException {
        throw new UnsupportedOperationException();
    }

    public String[] getTetheredIfaces() throws RemoteException {
        throw new UnsupportedOperationException();
    }

    public String[] getTetheringErroredIfaces() throws RemoteException {
        throw new UnsupportedOperationException();
    }

    public boolean isTetheringSupported() throws RemoteException {
        throw new UnsupportedOperationException();
    }

    public void requestNetworkTransitionWakelock(String forWhom) {
        throw new UnsupportedOperationException();
    }

    public boolean requestRouteToHost(int networkType, int hostAddress) throws RemoteException {
        throw new UnsupportedOperationException();
    }

    public void setBackgroundDataSetting(boolean allowBackgroundData) throws RemoteException {
        throw new UnsupportedOperationException();
    }

    public void setMobileDataEnabled(boolean enabled) throws RemoteException {
        throw new UnsupportedOperationException();
    }

    public void setNetworkPreference(int pref) throws RemoteException {
        throw new UnsupportedOperationException();
    }

    public boolean setRadio(int networkType, boolean turnOn) throws RemoteException {
        throw new UnsupportedOperationException();
    }

    public boolean setRadios(boolean onOff) throws RemoteException {
        throw new UnsupportedOperationException();
    }

    public int startUsingNetworkFeature(int networkType, String feature, IBinder binder)
            throws RemoteException {
        throw new UnsupportedOperationException();
    }

    public int stopUsingNetworkFeature(int networkType, String feature) throws RemoteException {
        throw new UnsupportedOperationException();
    }

    public int tether(String iface) throws RemoteException {
        throw new UnsupportedOperationException();
    }

    public int untether(String iface) throws RemoteException {
        throw new UnsupportedOperationException();
    }
}
