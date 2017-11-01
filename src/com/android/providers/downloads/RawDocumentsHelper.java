/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static com.android.providers.downloads.Constants.TAG;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.util.Log;

import java.io.File;

/**
 * Contains helper methods to convert between raw document ids and their file-system file paths.
 */
public class RawDocumentsHelper {
    /** The default prefix to raw file documentIds */
    public static final String RAW_PREFIX = "raw:";

    public static boolean isRawDocId(String docId) {
        return docId != null && docId.startsWith(RAW_PREFIX);
    }

    public static String getDocIdForFile(File file) {
        return RAW_PREFIX + file.getAbsolutePath();
    }

    public static String getAbsoluteFilePath(String rawDocumentId) {
        return rawDocumentId.substring(RAW_PREFIX.length());
    }

    /**
     * Build and start an {@link Intent} to view the download with given raw documentId.
     */
    public static boolean startViewIntent(Context context, Uri documentUri) {
        final Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setData(documentUri);
        intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        intent.putExtra(Intent.EXTRA_ORIGINATING_UID, PackageInstaller.SessionParams.UID_UNKNOWN);

        try {
            context.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "Failed to start " + intent + ": " + e);
            return false;
        }
    }

}
