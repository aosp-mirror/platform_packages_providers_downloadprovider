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

import android.app.DownloadManager;
import android.test.suitebuilder.annotation.LargeTest;

/**
 * Download manager tests that require multithreading.
 */
@LargeTest
public class ThreadingTest extends AbstractPublicApiTest {
    private static class FakeSystemFacadeWithThreading extends FakeSystemFacade {
        @Override
        public void startThread(Thread thread) {
            thread.start();
        }
    }

    public ThreadingTest() {
        super(new FakeSystemFacadeWithThreading());
    }

    @Override
    protected void tearDown() throws Exception {
        Thread.sleep(50); // give threads a chance to finish
        super.tearDown();
    }

    /**
     * Test for race conditions when the service is flooded with startService() calls while running
     * a download.
     */
    public void testFloodServiceWithStarts() throws Exception {
        enqueueResponse(HTTP_OK, FILE_CONTENT);
        Download download = enqueueRequest(getRequest());
        while (download.getStatus() != DownloadManager.STATUS_SUCCESSFUL) {
            startService(null);
            Thread.sleep(10);
        }
    }
}
