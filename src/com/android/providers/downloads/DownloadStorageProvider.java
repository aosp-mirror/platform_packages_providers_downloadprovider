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
import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.provider.BaseColumns;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.DocumentColumns;
import android.provider.DocumentsContract.RootColumns;
import android.provider.Downloads;
import android.util.Log;

import libcore.io.IoUtils;

import java.io.FileNotFoundException;

/**
 * Presents a {@link DocumentsContract} view of {@link DownloadManager}
 * contents.
 */
public class DownloadStorageProvider extends ContentProvider {
    private static final String AUTHORITY = "com.android.providers.downloads.storage";

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

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {

        // TODO: support custom projections
        final String[] rootsProjection = new String[] {
                BaseColumns._ID, RootColumns.ROOT_ID, RootColumns.ROOT_TYPE, RootColumns.ICON,
                RootColumns.TITLE, RootColumns.SUMMARY, RootColumns.AVAILABLE_BYTES };
        final String[] docsProjection = new String[] {
                BaseColumns._ID, DocumentColumns.DISPLAY_NAME, DocumentColumns.SIZE,
                DocumentColumns.DOC_ID, DocumentColumns.MIME_TYPE, DocumentColumns.LAST_MODIFIED,
                DocumentColumns.FLAGS, DocumentColumns.SUMMARY };

        switch (sMatcher.match(uri)) {
            case URI_ROOTS: {
                final MatrixCursor result = new MatrixCursor(rootsProjection);
                includeDefaultRoot(result);
                return result;
            }
            case URI_ROOTS_ID: {
                final MatrixCursor result = new MatrixCursor(rootsProjection);
                includeDefaultRoot(result);
                return result;
            }
            case URI_DOCS_ID: {
                final String docId = DocumentsContract.getDocId(uri);
                final MatrixCursor result = new MatrixCursor(docsProjection);

                if (DocumentsContract.ROOT_DOC_ID.equals(docId)) {
                    includeDefaultDocument(result);
                } else {
                    // Delegate to real provider
                    final long token = Binder.clearCallingIdentity();
                    Cursor cursor = null;
                    try {
                        final Uri downloadUri = getDownloadUriFromDocument(docId);
                        cursor = getContext()
                                .getContentResolver().query(downloadUri, null, null, null, null);
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
                final MatrixCursor result = new MatrixCursor(docsProjection);

                if (!DocumentsContract.ROOT_DOC_ID.equals(docId)) {
                    throw new UnsupportedOperationException("Unsupported Uri " + uri);
                }

                // Delegate to real provider
                // TODO: filter visible downloads?
                final long token = Binder.clearCallingIdentity();
                Cursor cursor = null;
                try {
                    cursor = getContext().getContentResolver()
                            .query(Downloads.Impl.ALL_DOWNLOADS_CONTENT_URI, null, null, null, null);
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
        final int rootType = DocumentsContract.ROOT_TYPE_SHORTCUT;
        final String rootId = "downloads";
        final int icon = 0;
        final String title = getContext().getString(R.string.root_downloads);
        final String summary = null;
        final long availableBytes = -1;

        result.addRow(new Object[] {
                rootId.hashCode(), rootId, rootType, icon, title, summary,
                availableBytes });
    }

    private void includeDefaultDocument(MatrixCursor result) {
        final long id = Long.MIN_VALUE;
        final String docId = DocumentsContract.ROOT_DOC_ID;
        final String displayName = getContext().getString(R.string.root_downloads);
        final String summary = null;
        final String mimeType = DocumentsContract.MIME_TYPE_DIRECTORY;
        final long size = -1;
        final long lastModified = -1;
        final int flags = 0;

        result.addRow(new Object[] {
                id, displayName, size, docId, mimeType, lastModified, flags, summary });
    }

    private void includeDownloadFromCursor(MatrixCursor result, Cursor cursor) {
        final long id = cursor.getLong(cursor.getColumnIndexOrThrow(Downloads.Impl._ID));
        final String docId = getDocumentFromDownload(id);

        final String displayName = cursor.getString(
                cursor.getColumnIndexOrThrow(Downloads.Impl.COLUMN_TITLE));
        final String summary = cursor.getString(
                cursor.getColumnIndexOrThrow(Downloads.Impl.COLUMN_DESCRIPTION));
        String mimeType = cursor.getString(
                cursor.getColumnIndexOrThrow(Downloads.Impl.COLUMN_MIME_TYPE));
        if (mimeType == null) {
            mimeType = "application/octet-stream";
        }

        final int status = cursor.getInt(
                cursor.getColumnIndexOrThrow(Downloads.Impl.COLUMN_STATUS));
        final long size;
        if (Downloads.Impl.isStatusCompleted(status)) {
            size = cursor.getLong(cursor.getColumnIndexOrThrow(Downloads.Impl.COLUMN_TOTAL_BYTES));
        } else {
            size = -1;
        }

        int flags = DocumentsContract.FLAG_SUPPORTS_DELETE;
        if (mimeType.startsWith("image/")) {
            flags |= DocumentsContract.FLAG_SUPPORTS_THUMBNAIL;
        }

        final long lastModified = cursor.getLong(
                cursor.getColumnIndexOrThrow(Downloads.Impl.COLUMN_LAST_MODIFICATION));

        result.addRow(new Object[] {
                id, displayName, size, docId, mimeType, lastModified, flags, summary });
    }

    @Override
    public String getType(Uri uri) {
        switch (sMatcher.match(uri)) {
            case URI_DOCS_ID: {
                final String docId = DocumentsContract.getDocId(uri);
                if (DocumentsContract.ROOT_DOC_ID.equals(docId)) {
                    return DocumentsContract.MIME_TYPE_DIRECTORY;
                } else {
                    // Delegate to real provider
                    final long token = Binder.clearCallingIdentity();
                    Cursor cursor = null;
                    String mimeType = null;
                    try {
                        final Uri downloadUri = getDownloadUriFromDocument(docId);
                        cursor = getContext().getContentResolver()
                                .query(downloadUri, null, null, null, null);
                        if (cursor.moveToFirst()) {
                            mimeType = cursor.getString(
                                    cursor.getColumnIndexOrThrow(Downloads.Impl.COLUMN_MIME_TYPE));
                        }
                    } finally {
                        IoUtils.closeQuietly(cursor);
                        Binder.restoreCallingIdentity(token);
                    }

                    if (mimeType == null) {
                        mimeType = "application/octet-stream";
                    }
                }
            }
            default: {
                throw new UnsupportedOperationException("Unsupported Uri " + uri);
            }
        }
    }

    private Uri getDownloadUriFromDocument(String docId) {
        return ContentUris.withAppendedId(
                Downloads.Impl.ALL_DOWNLOADS_CONTENT_URI, getDownloadFromDocument(docId));
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

                // Delegate to real provider
                final long token = Binder.clearCallingIdentity();
                try {
                    final Uri downloadUri = getDownloadUriFromDocument(docId);
                    return getContext().getContentResolver().openFileDescriptor(downloadUri, mode);
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
                final Uri downloadUri = getDownloadUriFromDocument(docId);
                getContext().getContentResolver().delete(downloadUri, null, null);
            }
            default: {
                throw new UnsupportedOperationException("Unsupported Uri " + uri);
            }
        }
    }
}
