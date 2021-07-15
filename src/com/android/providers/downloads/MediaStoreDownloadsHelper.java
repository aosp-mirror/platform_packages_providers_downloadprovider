/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.content.ContentUris;
import android.net.Uri;
import android.provider.MediaStore;

public class MediaStoreDownloadsHelper {

    private static final String MEDIASTORE_DOWNLOAD_FILE_PREFIX = "msf:";
    private static final String MEDIASTORE_DOWNLOAD_DIR_PREFIX = "msd:";

    public static String getDocIdForMediaStoreDownload(long id, boolean isDir) {
        return (isDir ? MEDIASTORE_DOWNLOAD_DIR_PREFIX : MEDIASTORE_DOWNLOAD_FILE_PREFIX) + id;
    }

    public static boolean isMediaStoreDownload(String docId) {
        return docId != null && (docId.startsWith(MEDIASTORE_DOWNLOAD_FILE_PREFIX)
                || docId.startsWith(MEDIASTORE_DOWNLOAD_DIR_PREFIX));
    }

    public static long getMediaStoreId(String docId) {
        return Long.parseLong(getMediaStoreIdString(docId));
    }


    public static String getMediaStoreIdString(String docId) {
        final int index = docId.indexOf(":");
        return docId.substring(index + 1);
    }

    public static boolean isMediaStoreDownloadDir(String docId) {
        return docId != null && docId.startsWith(MEDIASTORE_DOWNLOAD_DIR_PREFIX);
    }

    /**
     * The returned uri always appends external volume {@link MediaStore#VOLUME_EXTERNAL}.
     * It doesn't consider the item is located on second volume. It can't be used to update
     * or insert.
     * @param docId the doc id
     * @return external uri for query
     */
    public static Uri getMediaStoreUriForQuery(String docId) {
        return getMediaStoreUri(MediaStore.VOLUME_EXTERNAL, docId);
    }

    public static Uri getMediaStoreUri(String volume, String docId) {
        return MediaStore.Downloads.getContentUri(volume, getMediaStoreId(docId));
    }
}
