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

import static android.provider.Downloads.Impl.COLUMN_DESTINATION;
import static android.provider.Downloads.Impl.DESTINATION_CACHE_PARTITION_PURGEABLE;
import static android.provider.Downloads.Impl.DESTINATION_EXTERNAL;
import static android.provider.Downloads.Impl.DESTINATION_FILE_URI;
import static android.provider.Downloads.Impl.DESTINATION_NON_DOWNLOADMANAGER_DOWNLOAD;
import static android.provider.Downloads.Impl._DATA;
import static android.provider.Downloads.Impl._ID;

import static com.android.internal.util.ArrayUtils.contains;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ContentProvider;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.Downloads;

import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.LongArray;
import android.util.LongSparseArray;

import libcore.io.IoUtils;

import java.io.File;
import java.util.Arrays;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This test exercises methods in the {@Helpers} utility class.
 */
@SmallTest
public class HelpersTest extends AndroidTestCase {

    private final static int TEST_UID1 = 11111;
    private final static int TEST_UID2 = 11112;
    private final static int TEST_UID3 = 11113;

    private final MockitoHelper mMockitoHelper = new MockitoHelper();

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mMockitoHelper.setUp(getClass());
    }

    @Override
    protected void tearDown() throws Exception {
        mMockitoHelper.tearDown();
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

    public void testIsFileInExternalAndroidDirs() throws Exception {
        assertTrue(Helpers.isFileInExternalAndroidDirs(
                "/storage/emulated/0/Android/data/com.example"));
        assertTrue(Helpers.isFileInExternalAndroidDirs(
                "/storage/emulated/0/Android/data/com.example/colors.txt"));
        assertTrue(Helpers.isFileInExternalAndroidDirs(
                "/storage/emulated/0/Android/media/com.example/file.mp4"));
        assertTrue(Helpers.isFileInExternalAndroidDirs(
                "/storage/AAAA-FFFF/Android/media/com.example/file.mp4"));
        assertFalse(Helpers.isFileInExternalAndroidDirs(
                "/storage/emulated/0/Download/foo.pdf"));
        assertFalse(Helpers.isFileInExternalAndroidDirs(
                "/storage/emulated/0/Download/dir/bar.html"));
        assertFalse(Helpers.isFileInExternalAndroidDirs(
                "/storage/AAAA-FFFF/Download/dir/bar.html"));
    }

    public void testIsFilenameValidinKnownPublicDir() throws Exception {
        assertTrue(Helpers.isFilenameValidInKnownPublicDir(
                "/storage/emulated/0/Download/dir/file.txt"));
        assertTrue(Helpers.isFilenameValidInKnownPublicDir(
                "/storage/emulated/0/Music/foo.mp4"));
        assertTrue(Helpers.isFilenameValidInKnownPublicDir(
                "/storage/emulated/0/DCIM/vacation/bar.jpg"));
        assertFalse(Helpers.isFilenameValidInKnownPublicDir(
                "/storage/emulated/0/Testing/foo.mp4"));
        assertFalse(Helpers.isFilenameValidInKnownPublicDir(
                "/storage/emulated/0/Misc/Download/bar.jpg"));
        assertFalse(Helpers.isFilenameValidInKnownPublicDir(
                "/storage/emulated/0/Android/data/com.example/bar.jpg"));
    }

    public void testHandleRemovedUidEntries() throws Exception {
        // Prepare
        final int[] testUids = {
                TEST_UID1, TEST_UID2, TEST_UID3
        };
        final int[] unknownUids = {
                TEST_UID1, TEST_UID2
        };
        final Context context = mock(Context.class);
        final PackageManager packageManager = mock(PackageManager.class);
        when(context.getPackageManager()).thenReturn(packageManager);
        for (int uid : testUids) {
            when(packageManager.getPackagesForUid(uid)).thenReturn(
                    contains(unknownUids, uid) ? null : new String[] {"com.example" + uid}
            );
        }

        final LongArray idsToRemove = new LongArray();
        final LongArray idsToOrphan = new LongArray();
        final LongSparseArray<String> validEntries = new LongSparseArray<>();
        final MatrixCursor cursor = prepareData(testUids, unknownUids,
                idsToOrphan, idsToRemove, validEntries);

        final ContentProvider downloadProvider = mock(ContentProvider.class);
        when(downloadProvider.query(eq(Downloads.Impl.ALL_DOWNLOADS_CONTENT_URI),
                any(String[].class), any(String.class),isNull(), isNull())).thenReturn(cursor);
        final BiConsumer<String, Long> validEntryConsumer = mock(BiConsumer.class);

        // Call
        Helpers.handleRemovedUidEntries(context, downloadProvider, -1, validEntryConsumer);

        // Verify
        verify(downloadProvider).update(eq(Downloads.Impl.ALL_DOWNLOADS_CONTENT_URI),
                argThat(values -> values.get(Constants.UID) == null),
                argThat(selection -> Arrays.equals(
                        idsToOrphan.toArray(), extractIdsFromSelection(selection))),
                isNull());
        verify(downloadProvider).delete(eq(Downloads.Impl.ALL_DOWNLOADS_CONTENT_URI),
                argThat(selection -> Arrays.equals(
                        idsToRemove.toArray(), extractIdsFromSelection(selection))),
                isNull());
        for (int i = 0; i < validEntries.size(); ++i) {
            verify(validEntryConsumer).accept(validEntries.valueAt(i), validEntries.keyAt(i));
        }


        // Reset
        idsToOrphan.clear();
        idsToRemove.clear();
        validEntries.clear();
        reset(downloadProvider);

        // Prepare
        final MatrixCursor cursor2 = prepareData(new int[] {TEST_UID2}, unknownUids,
                idsToOrphan, idsToRemove, validEntries);
        when(downloadProvider.query(eq(Downloads.Impl.ALL_DOWNLOADS_CONTENT_URI),
                any(String[].class), any(String.class),isNull(), isNull())).thenReturn(cursor2);

        // Call
        Helpers.handleRemovedUidEntries(context, downloadProvider, TEST_UID2, null);

        // Verify
        verify(downloadProvider).update(eq(Downloads.Impl.ALL_DOWNLOADS_CONTENT_URI),
                argThat(values -> values.get(Constants.UID) == null),
                argThat(selection -> Arrays.equals(
                        idsToOrphan.toArray(), extractIdsFromSelection(selection))),
                isNull());
        verify(downloadProvider).delete(eq(Downloads.Impl.ALL_DOWNLOADS_CONTENT_URI),
                argThat(selection -> Arrays.equals(
                        idsToRemove.toArray(), extractIdsFromSelection(selection))),
                isNull());
    }

    private MatrixCursor prepareData(int[] uids, int[] unknownUids,
            final LongArray idsToOrphan, final LongArray idsToRemove,
            LongSparseArray<String> validEntries) {
        final MatrixCursor cursor = new MatrixCursor(
                new String[] {_ID, Constants.UID, COLUMN_DESTINATION, _DATA});
        final int[] destinations = {
                DESTINATION_EXTERNAL,
                DESTINATION_FILE_URI,
                DESTINATION_NON_DOWNLOADMANAGER_DOWNLOAD,
                DESTINATION_CACHE_PARTITION_PURGEABLE
        };
        long counter = 0;
        for (int uid : uids) {
            for (int destination : destinations) {
                final String fileName = uid + "_" + destination + ".txt";
                switch (destination) {
                    case DESTINATION_EXTERNAL: {
                        final File file = new File(Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_DOWNLOADS), fileName);
                        cursor.addRow(new Object[]{++counter, uid, destination, file.getPath()});
                        if (contains(unknownUids, uid)) {
                            idsToOrphan.add(counter);
                        } else {
                            validEntries.put(counter, "com.example" + uid);
                        }
                    } break;
                    case DESTINATION_FILE_URI: {
                        final File file1 = new File(Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_DOCUMENTS), fileName);
                        cursor.addRow(new Object[]{++counter, uid, destination, file1.getPath()});
                        if (contains(unknownUids, uid)) {
                            idsToOrphan.add(counter);
                        } else {
                            validEntries.put(counter, "com.example" + uid);
                        }
                        final File file2 = new File(getContext().getExternalFilesDir(null),
                                fileName);
                        cursor.addRow(new Object[]{++counter, uid, destination, file2.getPath()});
                        if (contains(unknownUids, uid)) {
                            idsToRemove.add(counter);
                        } else {
                            validEntries.put(counter, "com.example" + uid);
                        }
                    } break;
                    case DESTINATION_NON_DOWNLOADMANAGER_DOWNLOAD: {
                        final File file1 = new File(Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_DOCUMENTS), fileName);
                        cursor.addRow(new Object[]{++counter, uid, destination, file1.getPath()});
                        if (contains(unknownUids, uid)) {
                            idsToOrphan.add(counter);
                        } else {
                            validEntries.put(counter, "com.example" + uid);
                        }
                        final File file2 = new File(getContext().getExternalFilesDir(null),
                                fileName);
                        cursor.addRow(new Object[]{++counter, uid, destination, file2.getPath()});
                        if (contains(unknownUids, uid)) {
                            idsToRemove.add(counter);
                        } else {
                            validEntries.put(counter, "com.example" + uid);
                        }
                    } break;
                    case DESTINATION_CACHE_PARTITION_PURGEABLE: {
                        final File file = new File(getContext().getCacheDir(), fileName);
                        final String filePath = file.getPath().replace(
                                getContext().getPackageName(), "com.android.providers.downloads");
                        cursor.addRow(new Object[]{++counter, uid, destination, filePath});
                        if (contains(unknownUids, uid)) {
                            idsToRemove.add(counter);
                        } else {
                            validEntries.put(counter, "com.example" + uid);
                        }
                    } break;
                }
            }
        }
        return cursor;
    }

    private long[] extractIdsFromSelection(String selection) {
        final Pattern uidsListPattern = Pattern.compile(".*\\((.+)\\)");
        final Matcher matcher = uidsListPattern.matcher(selection);
        assertTrue(matcher.matches());
        return Arrays.stream(matcher.group(1).split(","))
                .mapToLong(Long::valueOf).sorted().toArray();
    }
}
