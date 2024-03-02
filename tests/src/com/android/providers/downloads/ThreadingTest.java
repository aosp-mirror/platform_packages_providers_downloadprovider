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

import static java.net.HttpURLConnection.HTTP_OK;

import android.app.DownloadManager;
import android.test.suitebuilder.annotation.LargeTest;
import android.util.Pair;

import com.google.android.collect.Lists;
import com.google.android.collect.Sets;
import com.google.mockwebserver.MockResponse;
import com.google.mockwebserver.SocketPolicy;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Download manager tests that require multithreading.
 */
@LargeTest
public class ThreadingTest extends AbstractPublicApiTest {
    public ThreadingTest() {
        super(new FakeSystemFacade());
    }

    @Override
    protected void tearDown() throws Exception {
        Thread.sleep(50); // give threads a chance to finish
        super.tearDown();
    }

    public void testFilenameRace() throws Exception {
        final List<Pair<Download, String>> downloads = Lists.newArrayList();
        final HashSet<String> expectedBodies = Sets.newHashSet();

        // Request dozen files at once with same name
        for (int i = 0; i < 32; i++) {
            final String body = "DOWNLOAD " + i + " CONTENTS";
            enqueueResponse(new MockResponse().setResponseCode(HTTP_OK).setBody(body)
                    .setHeader("Content-type", "text/plain")
                    .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END));

            final Download d = enqueueRequest(getRequest());
            downloads.add(Pair.create(d, body));
            expectedBodies.add(body);
            startDownload(d.mId);
        }

        final long startMillis = mSystemFacade.currentTimeMillis();
        for (Pair<Download,String> d : downloads) {
            d.first.waitForStatus(DownloadManager.STATUS_SUCCESSFUL, startMillis);
        }

        // Ensure that contents are clean and filenames unique
        final Set<String> seenFiles = Sets.newHashSet();
        final HashSet<String> actualBodies = Sets.newHashSet();

        for (Pair<Download, String> d : downloads) {
            final String file = d.first.getStringField(DownloadManager.COLUMN_LOCAL_FILENAME);
            if (!seenFiles.add(file)) {
                fail("Another download already claimed " + file);
            }

            final String expected = d.second;
            final String actual = d.first.getContents();

            actualBodies.add(actual);
        }

        assertEquals(expectedBodies, actualBodies);
    }
}
