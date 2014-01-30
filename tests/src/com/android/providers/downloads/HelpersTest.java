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

import android.net.Uri;
import android.provider.Downloads;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

import libcore.io.IoUtils;

import java.io.File;

/**
 * This test exercises methods in the {@Helpers} utility class.
 */
@SmallTest
public class HelpersTest extends AndroidTestCase {

    @Override
    protected void tearDown() throws Exception {
        IoUtils.deleteContents(getContext().getFilesDir());
        IoUtils.deleteContents(getContext().getCacheDir());

        super.tearDown();
    }

    public void testGenerateSaveFile() throws Exception {
        final File expected = new File(getContext().getFilesDir(), "file.mp4");
        final String actual = Helpers.generateSaveFile(getContext(),
                "http://example.com/file.txt", null, null, null,
                "video/mp4", Downloads.Impl.DESTINATION_CACHE_PARTITION);
        assertEquals(expected.getAbsolutePath(), actual);
    }

    public void testGenerateSaveFileDupes() throws Exception {
        final File expected1 = new File(getContext().getFilesDir(), "file.txt");
        final String actual1 = Helpers.generateSaveFile(getContext(), "http://example.com/file.txt",
                null, null, null, null, Downloads.Impl.DESTINATION_CACHE_PARTITION);

        final File expected2 = new File(getContext().getFilesDir(), "file-1.txt");
        final String actual2 = Helpers.generateSaveFile(getContext(), "http://example.com/file.txt",
                null, null, null, null, Downloads.Impl.DESTINATION_CACHE_PARTITION);

        assertEquals(expected1.getAbsolutePath(), actual1);
        assertEquals(expected2.getAbsolutePath(), actual2);
    }

    public void testGenerateSaveFileNoExtension() throws Exception {
        final File expected = new File(getContext().getFilesDir(), "file.mp4");
        final String actual = Helpers.generateSaveFile(getContext(),
                "http://example.com/file", null, null, null,
                "video/mp4", Downloads.Impl.DESTINATION_CACHE_PARTITION);
        assertEquals(expected.getAbsolutePath(), actual);
    }

    public void testGenerateSaveFileHint() throws Exception {
        final File expected = new File(getContext().getFilesDir(), "meow");
        final String hint = Uri.fromFile(expected).toString();

        // Test that we never change requested filename.
        final String actual = Helpers.generateSaveFile(getContext(), "url", hint,
                "dispo", "locat", "video/mp4", Downloads.Impl.DESTINATION_FILE_URI);
        assertEquals(expected.getAbsolutePath(), actual);
    }

    public void testGenerateSaveFileDisposition() throws Exception {
        final File expected = new File(getContext().getFilesDir(), "real.mp4");
        final String actual = Helpers.generateSaveFile(getContext(),
                "http://example.com/file.txt", null, "attachment; filename=\"subdir/real.pdf\"",
                null, "video/mp4", Downloads.Impl.DESTINATION_CACHE_PARTITION);
        assertEquals(expected.getAbsolutePath(), actual);
    }
}
