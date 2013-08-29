/*
 * Copyright (C) 2013 The Android Open Source Project
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
import android.app.DownloadManager.Query;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.MatrixCursor.RowBuilder;
import android.graphics.Point;
import android.os.Binder;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.DocumentColumns;
import android.provider.DocumentsContract.DocumentRoot;
import android.provider.DocumentsContract.Documents;
import android.provider.DocumentsProvider;

import com.google.common.collect.Lists;

import libcore.io.IoUtils;

import java.io.FileNotFoundException;
import java.util.List;

/**
 * Presents a {@link DocumentsContract} view of {@link DownloadManager}
 * contents.
 */
public class DownloadStorageProvider extends DocumentsProvider {
    private static final String DOC_ID_ROOT = Constants.STORAGE_DOC_ID_ROOT;

    private static final String[] SUPPORTED_COLUMNS = new String[] {
            DocumentColumns.DOC_ID, DocumentColumns.DISPLAY_NAME, DocumentColumns.SIZE,
            DocumentColumns.MIME_TYPE, DocumentColumns.LAST_MODIFIED, DocumentColumns.FLAGS
    };

    private DocumentRoot mRoot;

    private DownloadManager mDm;
    private DownloadManager.Query mBaseQuery;

    @Override
    public boolean onCreate() {

        mRoot = new DocumentRoot();
        mRoot.docId = DOC_ID_ROOT;
        mRoot.rootType = DocumentRoot.ROOT_TYPE_SHORTCUT;
        mRoot.title = getContext().getString(R.string.root_downloads);
        mRoot.icon = R.mipmap.ic_launcher_download;
        mRoot.flags = DocumentRoot.FLAG_LOCAL_ONLY;

        mDm = (DownloadManager) getContext().getSystemService(Context.DOWNLOAD_SERVICE);
        mDm.setAccessAllDownloads(true);
        mBaseQuery = new DownloadManager.Query().setOnlyIncludeVisibleInDownloadsUi(true);

        return true;
    }

    @Override
    public List<DocumentRoot> getDocumentRoots() {
        return Lists.newArrayList(mRoot);
    }

    @Override
    public void deleteDocument(String docId) throws FileNotFoundException {
        // Delegate to real provider
        final long token = Binder.clearCallingIdentity();
        try {
            if (mDm.remove(Long.parseLong(docId)) != 1) {
                throw new IllegalStateException("Failed to delete " + docId);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public Cursor queryDocument(String docId) throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(SUPPORTED_COLUMNS);

        if (DOC_ID_ROOT.equals(docId)) {
            includeDefaultDocument(result);
        } else {
            // Delegate to real provider
            final long token = Binder.clearCallingIdentity();
            Cursor cursor = null;
            try {
                cursor = mDm.query(new Query().setFilterById(Long.parseLong(docId)));
                if (cursor.moveToFirst()) {
                    includeDownloadFromCursor(result, cursor);
                }
            } finally {
                IoUtils.closeQuietly(cursor);
                Binder.restoreCallingIdentity(token);
            }
        }
        return result;
    }

    @Override
    public Cursor queryDocumentChildren(String docId) throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(SUPPORTED_COLUMNS);

        // Delegate to real provider
        final long token = Binder.clearCallingIdentity();
        Cursor cursor = null;
        try {
            cursor = mDm.query(mBaseQuery);
            while (cursor.moveToNext()) {
                includeDownloadFromCursor(result, cursor);
            }
        } finally {
            IoUtils.closeQuietly(cursor);
            Binder.restoreCallingIdentity(token);
        }
        return result;
    }

    @Override
    public ParcelFileDescriptor openDocument(String docId, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        if (!"r".equals(mode)) {
            throw new IllegalArgumentException("Downloads are read-only");
        }

        // Delegate to real provider
        final long token = Binder.clearCallingIdentity();
        try {
            return mDm.openDownloadedFile(Long.parseLong(docId));
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public AssetFileDescriptor openDocumentThumbnail(
            String docId, Point sizeHint, CancellationSignal signal) throws FileNotFoundException {
        // TODO: extend ExifInterface to support fds
        final ParcelFileDescriptor pfd = openDocument(docId, "r", signal);
        return new AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH);
    }

    private void includeDefaultDocument(MatrixCursor result) {
        final RowBuilder row = result.newRow();
        row.offer(DocumentColumns.DOC_ID, DOC_ID_ROOT);
        row.offer(DocumentColumns.MIME_TYPE, Documents.MIME_TYPE_DIR);
    }

    private void includeDownloadFromCursor(MatrixCursor result, Cursor cursor) {
        final long id = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID));
        final String docId = String.valueOf(id);

        final String displayName = cursor.getString(
                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE));
        String summary = cursor.getString(
                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_DESCRIPTION));
        String mimeType = cursor.getString(
                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_MEDIA_TYPE));
        if (mimeType == null) {
            mimeType = "application/octet-stream";
        }
        Long size = null;

        final int status = cursor.getInt(
                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
        switch (status) {
            case DownloadManager.STATUS_SUCCESSFUL:
                size = cursor.getLong(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                if (size == -1) {
                    size = null;
                }
                break;
            case DownloadManager.STATUS_PAUSED:
                mimeType = null;
                summary = getContext().getString(R.string.download_queued);
                break;
            case DownloadManager.STATUS_PENDING:
                mimeType = null;
                summary = getContext().getString(R.string.download_queued);
                break;
            case DownloadManager.STATUS_RUNNING:
                mimeType = null;
                summary = getContext().getString(R.string.download_running);
                break;
            case DownloadManager.STATUS_FAILED:
            default:
                mimeType = null;
                summary = getContext().getString(R.string.download_error);
                break;
        }

        int flags = Documents.FLAG_SUPPORTS_DELETE;
        if (mimeType != null && mimeType.startsWith("image/")) {
            flags |= Documents.FLAG_SUPPORTS_THUMBNAIL;
        }

        final long lastModified = cursor.getLong(
                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP));

        final RowBuilder row = result.newRow();
        row.offer(DocumentColumns.DOC_ID, docId);
        row.offer(DocumentColumns.DISPLAY_NAME, displayName);
        row.offer(DocumentColumns.SIZE, size);
        row.offer(DocumentColumns.MIME_TYPE, mimeType);
        row.offer(DocumentColumns.LAST_MODIFIED, lastModified);
        row.offer(DocumentColumns.FLAGS, flags);
    }
}
