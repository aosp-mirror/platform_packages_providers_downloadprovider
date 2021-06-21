/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.app.job.JobParameters;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Bundle;

import java.security.GeneralSecurityException;

import javax.net.ssl.SSLContext;

interface SystemFacade {
    /**
     * @see System#currentTimeMillis()
     */
    public long currentTimeMillis();

    public Network getNetwork(JobParameters params);

    public NetworkCapabilities getNetworkCapabilities(Network network);

    /**
     * @return maximum size, in bytes, of downloads that may go over a mobile connection; or null if
     * there's no limit
     */
    public long getMaxBytesOverMobile();

    /**
     * @return recommended maximum size, in bytes, of downloads that may go over a mobile
     * connection; or null if there's no recommended limit.  The user will have the option to bypass
     * this limit.
     */
    public long getRecommendedMaxBytesOverMobile();

    /**
     * Send a broadcast intent.
     */
    public void sendBroadcast(Intent intent);

    /**
     * Send a broadcast intent with options.
     */
    public void sendBroadcast(Intent intent, String receiverPermission, Bundle options);

    /**
     * Returns true if the specified UID owns the specified package name.
     */
    public boolean userOwnsPackage(int uid, String pckg) throws NameNotFoundException;

    /**
     * Returns true if cleartext network traffic is permitted from {@code packageName} to
     * {@code host}.
     */
    public boolean isCleartextTrafficPermitted(String packageName, String host);

    /**
     * Return a {@link SSLContext} configured using the specified package's configuration.
     */
    public SSLContext getSSLContextForPackage(Context context, String pckg)
            throws GeneralSecurityException;
}
