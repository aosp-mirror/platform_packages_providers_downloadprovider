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
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.MatrixCursor.RowBuilder;
import android.net.Uri;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.DocumentColumns;
import android.provider.DocumentsContract.Documents;
import android.provider.DocumentsContract.RootColumns;
import android.provider.DocumentsContract.Roots;

import libcore.io.IoUtils;

import java.io.FileNotFoundException;

/**
 * Presents a {@link DocumentsContract} view of {@link DownloadManager}
 * contents.
 */
public class DownloadStorageProvider extends ContentProvider {
    private static final String AUTHORITY = Constants.STORAGE_AUTHORITY;
    private static final String ROOT = Constants.STORAGE_ROOT;

    private static final UriMatcher sMatcher = new UriMatcher(UriMatcher.NO_MATCH);

    private static final int URI_ROOTS = 1;
    private static final int URI_ROOTS_ID = 2;
    private static final int URI_DOCS_ID = 3;
    private static final int URI_DOCS_ID_CONTENTS = 4;

    static {
        sMatcher.addURI(AUTHORITY, "roots", URI_ROOTS);
        sMatcher.addURI(AUTHORITY, "roots/*", URI_ROOTS_ID);
        sMatcher.addURI(AUTHORITY, "roots/*/docs/*", URI_DOCS_ID);
        sMatcher.addURI(AUTHORITY, "roots/*/docs/*/contents", URI_DOCS_ID_CONTENTS);
    }

    private static final String[] ALL_ROOTS_COLUMNS = new String[] {
            RootColumns.ROOT_ID, RootColumns.ROOT_TYPE, RootColumns.ICON, RootColumns.TITLE,
            RootColumns.SUMMARY, RootColumns.AVAILABLE_BYTES
    };

    private static final String[] ALL_DOCUMENTS_COLUMNS = new String[] {
            DocumentColumns.DOC_ID, DocumentColumns.DISPLAY_NAME, DocumentColumns.SIZE,
            DocumentColumns.MIME_TYPE, DocumentColumns.LAST_MODIFIED, DocumentColumns.FLAGS
    };

    private DownloadManager mDm;
    private DownloadManager.Query mBaseQuery;

    @Override
    public boolean onCreate() {
        mDm = (DownloadManager) getContext().getSystemService(Context.DOWNLOAD_SERVICE);
        mDm.setAccessAllDownloads(true);
        mBaseQuery = new DownloadManager.Query().setOnlyIncludeVisibleInDownloadsUi(true);

        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        switch (sMatcher.match(uri)) {
            case URI_ROOTS: {
                final MatrixCursor result = new MatrixCursor(
                        projection != null ? projection : ALL_ROOTS_COLUMNS);
                includeDefaultRoot(result);
                return result;
            }
            case URI_ROOTS_ID: {
                final MatrixCursor result = new MatrixCursor(
                        projection != null ? projection : ALL_ROOTS_COLUMNS);
                includeDefaultRoot(result);
                return result;
            }
            case URI_DOCS_ID: {
                final String docId = DocumentsContract.getDocId(uri);
                final MatrixCursor result = new MatrixCursor(
                        projection != null ? projection : ALL_DOCUMENTS_COLUMNS);

                if (Documents.DOC_ID_ROOT.equals(docId)) {
                    includeDefaultDocument(result);
                } else {
                    // Delegate to real provider
                    final long token = Binder.clearCallingIdentity();
                    Cursor cursor = null;
                    try {
                        cursor = mDm.query(
                                new Query().setFilterById(getDownloadFromDocument(docId)));
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
            case URI_DOCS_ID_CONTENTS: {
                final String docId = DocumentsContract.getDocId(uri);
                final MatrixCursor result = new MatrixCursor(
                        projection != null ? projection : ALL_DOCUMENTS_COLUMNS);

                if (!Documents.DOC_ID_ROOT.equals(docId)) {
                    throw new UnsupportedOperationException("Unsupported Uri " + uri);
                }

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
            default: {
                throw new UnsupportedOperationException("Unsupported Uri " + uri);
            }
        }
    }

    private void includeDefaultRoot(MatrixCursor result) {
        final RowBuilder row = result.newRow();
        row.offer(RootColumns.ROOT_ID, ROOT);
        row.offer(RootColumns.ROOT_TYPE, Roots.ROOT_TYPE_SHORTCUT);
        row.offer(RootColumns.TITLE, getContext().getString(R.string.root_downloads));
    }

    private void includeDefaultDocument(MatrixCursor result) {
        final RowBuilder row = result.newRow();
        row.offer(DocumentColumns.DOC_ID, Documents.DOC_ID_ROOT);
        row.offer(DocumentColumns.DISPLAY_NAME, getContext().getString(R.string.root_downloads));
        row.offer(DocumentColumns.MIME_TYPE, Documents.MIME_TYPE_DIR);
    }

    private void includeDownloadFromCursor(MatrixCursor result, Cursor cursor) {
        final long id = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID));
        final String docId = getDocumentFromDownload(id);

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

    private interface TypeQuery {
        final String[] PROJECTION = {
                DocumentColumns.MIME_TYPE };

        final int MIME_TYPE = 0;
    }

    @Override
    public String getType(Uri uri) {
        switch (sMatcher.match(uri)) {
            case URI_ROOTS: {
                return Roots.MIME_TYPE_DIR;
            }
            case URI_ROOTS_ID: {
                return Roots.MIME_TYPE_ITEM;
            }
            case URI_DOCS_ID: {
                final Cursor cursor = query(uri, TypeQuery.PROJECTION, null, null, null);
                try {
                    if (cursor.moveToFirst()) {
                        return cursor.getString(TypeQuery.MIME_TYPE);
                    } else {
                        return null;
                    }
                } finally {
                    IoUtils.closeQuietly(cursor);
                }
            }
            default: {
                throw new UnsupportedOperationException("Unsupported Uri " + uri);
            }
        }
    }

    private long getDownloadFromDocument(String docId) {
        return Long.parseLong(docId.substring(docId.indexOf(':') + 1));
    }

    private String getDocumentFromDownload(long id) {
        return "id:" + id;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        switch (sMatcher.match(uri)) {
            case URI_DOCS_ID: {
                final String docId = DocumentsContract.getDocId(uri);

                if (!"r".equals(mode)) {
                    throw new IllegalArgumentException("Downloads are read-only");
                }

                // Delegate to real provider
                final long token = Binder.clearCallingIdentity();
                try {
                    return mDm.openDownloadedFile(getDownloadFromDocument(docId));
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }
            default: {
                throw new UnsupportedOperationException("Unsupported Uri " + uri);
            }
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Unsupported Uri " + uri);
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Unsupported Uri " + uri);
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        switch (sMatcher.match(uri)) {
            case URI_DOCS_ID: {
                final String docId = DocumentsContract.getDocId(uri);

                // Delegate to real provider
                // TODO: only storage UI should be allowed to delete?
                mDm.remove(getDownloadFromDocument(docId));
            }
            default: {
                throw new UnsupportedOperationException("Unsupported Uri " + uri);
            }
        }
    }
}
