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
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.MatrixCursor.RowBuilder;
import android.graphics.Point;
import android.net.Uri;
import android.os.Binder;
import android.os.CancellationSignal;
import android.os.Environment;
import android.os.FileObserver;
import android.os.FileUtils;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Path;
import android.provider.DocumentsContract.Root;
import android.provider.Downloads;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.content.FileSystemProvider;

import libcore.io.IoUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.text.NumberFormat;
import java.util.HashSet;
import java.util.Set;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

/**
 * Presents files located in {@link Environment#DIRECTORY_DOWNLOADS} and contents from
 * {@link DownloadManager}. {@link DownloadManager} contents include active downloads and completed
 * downloads added by other applications using
 * {@link DownloadManager#addCompletedDownload(String, String, boolean, String, String, long, boolean, boolean, Uri, Uri)}
 * .
 */
public class DownloadStorageProvider extends FileSystemProvider {
    private static final String TAG = "DownloadStorageProvider";
    private static final boolean DEBUG = false;

    private static final String AUTHORITY = Constants.STORAGE_AUTHORITY;
    private static final String DOC_ID_ROOT = Constants.STORAGE_ROOT_ID;

    private static final String[] DEFAULT_ROOT_PROJECTION = new String[] {
            Root.COLUMN_ROOT_ID, Root.COLUMN_FLAGS, Root.COLUMN_ICON,
            Root.COLUMN_TITLE, Root.COLUMN_DOCUMENT_ID,
    };

    private static final String[] DEFAULT_DOCUMENT_PROJECTION = new String[] {
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_SUMMARY, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE,
    };

    private DownloadManager mDm;

    @Override
    public boolean onCreate() {
        super.onCreate(DEFAULT_DOCUMENT_PROJECTION);
        mDm = (DownloadManager) getContext().getSystemService(Context.DOWNLOAD_SERVICE);
        mDm.setAccessAllDownloads(true);
        mDm.setAccessFilename(true);

        return true;
    }

    private static String[] resolveRootProjection(String[] projection) {
        return projection != null ? projection : DEFAULT_ROOT_PROJECTION;
    }

    private static String[] resolveDocumentProjection(String[] projection) {
        return projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION;
    }

    private void copyNotificationUri(MatrixCursor result, Cursor cursor) {
        result.setNotificationUri(getContext().getContentResolver(), cursor.getNotificationUri());
    }

    /**
     * Called by {@link DownloadProvider} when deleting a row in the {@link DownloadManager}
     * database.
     */
    static void onDownloadProviderDelete(Context context, long id) {
        final Uri uri = DocumentsContract.buildDocumentUri(AUTHORITY, Long.toString(id));
        context.revokeUriPermission(uri, ~0);
    }

    @Override
    public Cursor queryRoots(String[] projection) throws FileNotFoundException {
        // It's possible that the folder does not exist on disk, so we will create the folder if
        // that is the case. If user decides to delete the folder later, then it's OK to fail on
        // subsequent queries.
        getDownloadsDirectory().mkdirs();

        final MatrixCursor result = new MatrixCursor(resolveRootProjection(projection));
        final RowBuilder row = result.newRow();
        row.add(Root.COLUMN_ROOT_ID, DOC_ID_ROOT);
        row.add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY | Root.FLAG_SUPPORTS_RECENTS
                | Root.FLAG_SUPPORTS_CREATE | Root.FLAG_SUPPORTS_SEARCH
                | Root.FLAG_SUPPORTS_IS_CHILD);
        row.add(Root.COLUMN_ICON, R.mipmap.ic_launcher_download);
        row.add(Root.COLUMN_TITLE, getContext().getString(R.string.root_downloads));
        row.add(Root.COLUMN_DOCUMENT_ID, DOC_ID_ROOT);
        return result;
    }

    @Override
    public Path findDocumentPath(String parentDocId, String docId) throws FileNotFoundException {

        if (parentDocId == null) {
            parentDocId = DOC_ID_ROOT;
        }

        final File parent = getFileForDocId(parentDocId);

        final File doc = getFileForDocId(docId);

        final String rootId = (parentDocId == null) ? DOC_ID_ROOT : null;

        return new Path(rootId, findDocumentPath(parent, doc));
    }

    /**
     * Calls on {@link FileSystemProvider#createDocument(String, String, String)}, and then creates
     * a new database entry in {@link DownloadManager} if it is not a raw file and not a folder.
     */
    @Override
    public String createDocument(String parentDocId, String mimeType, String displayName)
            throws FileNotFoundException {
        // Delegate to real provider
        final long token = Binder.clearCallingIdentity();
        try {
            String newDocumentId = super.createDocument(parentDocId, mimeType, displayName);
            if (!Document.MIME_TYPE_DIR.equals(mimeType)
                    && !RawDocumentsHelper.isRawDocId(parentDocId)) {
                File newFile = getFileForDocId(newDocumentId);
                newDocumentId = Long.toString(mDm.addCompletedDownload(
                        newFile.getName(), newFile.getName(), true, mimeType,
                        newFile.getAbsolutePath(), 0L,
                        false, true));
            }
            return newDocumentId;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public void deleteDocument(String docId) throws FileNotFoundException {
        // Delegate to real provider
        final long token = Binder.clearCallingIdentity();
        try {
            if (RawDocumentsHelper.isRawDocId(docId)) {
                super.deleteDocument(docId);
                return;
            }
            if (mDm.remove(Long.parseLong(docId)) != 1) {
                throw new IllegalStateException("Failed to delete " + docId);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public String renameDocument(String docId, String displayName)
            throws FileNotFoundException {
        final long token = Binder.clearCallingIdentity();

        try {
            if (RawDocumentsHelper.isRawDocId(docId)) {
                return super.renameDocument(docId, displayName);
            }

            displayName = FileUtils.buildValidFatFilename(displayName);
            final long id = Long.parseLong(docId);
            if (!mDm.rename(getContext(), id, displayName)) {
                throw new IllegalStateException(
                        "Failed to rename to " + displayName + " in downloadsManager");
            }
            return null;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public Cursor queryDocument(String docId, String[] projection) throws FileNotFoundException {
        // Delegate to real provider
        final long token = Binder.clearCallingIdentity();
        Cursor cursor = null;
        try {
            if (RawDocumentsHelper.isRawDocId(docId)) {
                return super.queryDocument(docId, projection);
            }

            final DownloadsCursor result = new DownloadsCursor(projection,
                    getContext().getContentResolver());

            if (DOC_ID_ROOT.equals(docId)) {
                includeDefaultDocument(result);
            } else {
                cursor = mDm.query(new Query().setFilterById(Long.parseLong(docId)));
                copyNotificationUri(result, cursor);
                Set<String> filePaths = new HashSet<>();
                if (cursor.moveToFirst()) {
                    // We don't know if this queryDocument() call is from Downloads (manage)
                    // or Files. Safely assume it's Files.
                    includeDownloadFromCursor(result, cursor, filePaths);
                }
            }
            result.start();
            return result;
        } finally {
            IoUtils.closeQuietly(cursor);
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public Cursor queryChildDocuments(String parentDocId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        return queryChildDocuments(parentDocId, projection, sortOrder, false);
    }

    @Override
    public Cursor queryChildDocumentsForManage(
            String parentDocId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        return queryChildDocuments(parentDocId, projection, sortOrder, true);
    }

    private Cursor queryChildDocuments(String parentDocId, String[] projection,
            String sortOrder, boolean manage) throws FileNotFoundException {

        // Delegate to real provider
        final long token = Binder.clearCallingIdentity();
        Cursor cursor = null;
        try {
            if (RawDocumentsHelper.isRawDocId(parentDocId)) {
                return super.queryChildDocuments(parentDocId, projection, sortOrder);
            }

            assert (DOC_ID_ROOT.equals(parentDocId));
            final DownloadsCursor result = new DownloadsCursor(projection,
                    getContext().getContentResolver());
            if (manage) {
                cursor = mDm.query(
                        new DownloadManager.Query().setOnlyIncludeVisibleInDownloadsUi(true));
            } else {
                cursor = mDm
                        .query(new DownloadManager.Query().setOnlyIncludeVisibleInDownloadsUi(true)
                                .setFilterByStatus(DownloadManager.STATUS_SUCCESSFUL));
            }
            copyNotificationUri(result, cursor);
            Set<String> filePaths = new HashSet<>();
            while (cursor.moveToNext()) {
                includeDownloadFromCursor(result, cursor, filePaths);
            }
            includeFilesFromSharedStorage(result, filePaths, null);

            result.start();
            return result;
        } finally {
            IoUtils.closeQuietly(cursor);
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public Cursor queryRecentDocuments(String rootId, String[] projection)
            throws FileNotFoundException {
        final DownloadsCursor result =
                new DownloadsCursor(projection, getContext().getContentResolver());

        // Delegate to real provider
        final long token = Binder.clearCallingIdentity();
        Cursor cursor = null;
        try {
            cursor = mDm.query(new DownloadManager.Query().setOnlyIncludeVisibleInDownloadsUi(true)
                    .setFilterByStatus(DownloadManager.STATUS_SUCCESSFUL));
            copyNotificationUri(result, cursor);
            while (cursor.moveToNext() && result.getCount() < 12) {
                final String mimeType = cursor.getString(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_MEDIA_TYPE));
                final String uri = cursor.getString(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_MEDIAPROVIDER_URI));

                // Skip images that have been inserted into the MediaStore so we
                // don't duplicate them in the recents list.
                if (mimeType == null
                        || (mimeType.startsWith("image/") && !TextUtils.isEmpty(uri))) {
                    continue;
                }
            }
        } finally {
            IoUtils.closeQuietly(cursor);
            Binder.restoreCallingIdentity(token);
        }

        result.start();
        return result;
    }

    @Override
    public Cursor querySearchDocuments(String rootId, String query, String[] projection)
            throws FileNotFoundException {

        final DownloadsCursor result =
                new DownloadsCursor(projection, getContext().getContentResolver());

        // Delegate to real provider
        final long token = Binder.clearCallingIdentity();
        Cursor cursor = null;
        try {
            cursor = mDm.query(new DownloadManager.Query().setOnlyIncludeVisibleInDownloadsUi(true)
                    .setFilterByString(query));
            copyNotificationUri(result, cursor);
            Set<String> filePaths = new HashSet<>();
            while (cursor.moveToNext()) {
                includeDownloadFromCursor(result, cursor, filePaths);
            }
            Cursor rawFilesCursor = super.querySearchDocuments(getDownloadsDirectory(), query,
                    projection, filePaths);
            while (rawFilesCursor.moveToNext()) {
                String docId = rawFilesCursor.getString(
                        rawFilesCursor.getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID));
                File rawFile = getFileForDocId(docId);
                includeFileFromSharedStorage(result, rawFile);
            }
        } finally {
            IoUtils.closeQuietly(cursor);
            Binder.restoreCallingIdentity(token);
        }

        result.start();
        return result;
    }

    @Override
    public ParcelFileDescriptor openDocument(String docId, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        // Delegate to real provider
        final long token = Binder.clearCallingIdentity();
        try {
            if (RawDocumentsHelper.isRawDocId(docId)) {
                return super.openDocument(docId, mode, signal);
            }

            final long id = Long.parseLong(docId);
            final ContentResolver resolver = getContext().getContentResolver();
            return resolver.openFileDescriptor(mDm.getDownloadUri(id), mode, signal);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    protected File getFileForDocId(String docId, boolean visible) throws FileNotFoundException {
        if (RawDocumentsHelper.isRawDocId(docId)) {
            return new File(RawDocumentsHelper.getAbsoluteFilePath(docId));
        }

        if (DOC_ID_ROOT.equals(docId)) {
            return getDownloadsDirectory();
        }

        final long token = Binder.clearCallingIdentity();
        Cursor cursor = null;
        String localFilePath = null;
        try {
            cursor = mDm.query(new Query().setFilterById(Long.parseLong(docId)));
            if (cursor.moveToFirst()) {
                localFilePath = cursor.getString(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_FILENAME));
            }
        } finally {
            IoUtils.closeQuietly(cursor);
            Binder.restoreCallingIdentity(token);
        }

        if (localFilePath == null) {
            throw new IllegalStateException("File has no filepath. Could not be found.");
        }
        return new File(localFilePath);
    }

    @Override
    protected String getDocIdForFile(File file) throws FileNotFoundException {
        return RawDocumentsHelper.getDocIdForFile(file);
    }

    @Override
    protected Uri buildNotificationUri(String docId) {
        return DocumentsContract.buildChildDocumentsUri(AUTHORITY, docId);
    }

    private void includeDefaultDocument(MatrixCursor result) {
        final RowBuilder row = result.newRow();
        row.add(Document.COLUMN_DOCUMENT_ID, DOC_ID_ROOT);
        row.add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR);
        row.add(Document.COLUMN_FLAGS,
                Document.FLAG_DIR_PREFERS_LAST_MODIFIED | Document.FLAG_DIR_SUPPORTS_CREATE);
    }

    /**
     * Adds the entry from the cursor to the result only if the entry is valid. That is,
     * if the file exists in the file system.
     */
    private void includeDownloadFromCursor(MatrixCursor result, Cursor cursor,
            Set<String> filePaths) {
        final long id = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID));
        final String docId = String.valueOf(id);

        final String displayName = cursor.getString(
                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE));
        String summary = cursor.getString(
                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_DESCRIPTION));
        String mimeType = cursor.getString(
                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_MEDIA_TYPE));
        if (mimeType == null) {
            // Provide fake MIME type so it's openable
            mimeType = "vnd.android.document/file";
        }
        Long size = cursor.getLong(
                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
        if (size == -1) {
            size = null;
        }
        String localFilePath = cursor.getString(
                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_FILENAME));

        int extraFlags = Document.FLAG_PARTIAL;
        final int status = cursor.getInt(
                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
        switch (status) {
            case DownloadManager.STATUS_SUCCESSFUL:
                // Verify that the document still exists in external storage. This is necessary
                // because files can be deleted from the file system without their entry being
                // removed from DownloadsManager.
                if (localFilePath == null || !new File(localFilePath).exists()) {
                    return;
                }
                extraFlags = Document.FLAG_SUPPORTS_RENAME;  // only successful is non-partial
                break;
            case DownloadManager.STATUS_PAUSED:
                summary = getContext().getString(R.string.download_queued);
                break;
            case DownloadManager.STATUS_PENDING:
                summary = getContext().getString(R.string.download_queued);
                break;
            case DownloadManager.STATUS_RUNNING:
                final long progress = cursor.getLong(cursor.getColumnIndexOrThrow(
                        DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                if (size != null) {
                    String percent =
                            NumberFormat.getPercentInstance().format((double) progress / size);
                    summary = getContext().getString(R.string.download_running_percent, percent);
                } else {
                    summary = getContext().getString(R.string.download_running);
                }
                break;
            case DownloadManager.STATUS_FAILED:
            default:
                summary = getContext().getString(R.string.download_error);
                break;
        }

        int flags = Document.FLAG_SUPPORTS_DELETE | Document.FLAG_SUPPORTS_WRITE | extraFlags;
        if (mimeType.startsWith("image/")) {
            flags |= Document.FLAG_SUPPORTS_THUMBNAIL;
        }

        final long lastModified = cursor.getLong(
                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP));

        final RowBuilder row = result.newRow();
        row.add(Document.COLUMN_DOCUMENT_ID, docId);
        row.add(Document.COLUMN_DISPLAY_NAME, displayName);
        row.add(Document.COLUMN_SUMMARY, summary);
        row.add(Document.COLUMN_SIZE, size);
        row.add(Document.COLUMN_MIME_TYPE, mimeType);
        row.add(Document.COLUMN_FLAGS, flags);
        // Incomplete downloads get a null timestamp.  This prevents thrashy UI when a bunch of
        // active downloads get sorted by mod time.
        if (status != DownloadManager.STATUS_RUNNING) {
            row.add(Document.COLUMN_LAST_MODIFIED, lastModified);
        }
        filePaths.add(localFilePath);
    }

    /**
     * Takes all the top-level files from the Downloads directory and adds them to the result.
     *
     * @param result cursor containing all documents to be returned by queryChildDocuments or
     *            queryChildDocumentsForManage.
     * @param downloadedFilePaths The absolute file paths of all the files in the result Cursor.
     * @param searchString query used to filter out unwanted results.
     */
    private void includeFilesFromSharedStorage(MatrixCursor result,
            Set<String> downloadedFilePaths, @Nullable String searchString)
            throws FileNotFoundException {
        File downloadsDir = getDownloadsDirectory();
        // Add every file from the Downloads directory to the result cursor. Ignore files that
        // were in the supplied downloaded file paths.
        for (File file : downloadsDir.listFiles()) {
            boolean inResultsAlready = downloadedFilePaths.contains(file.getAbsolutePath());
            boolean containsQuery = searchString == null || file.getName().contains(searchString);
            if (!inResultsAlready && containsQuery) {
                includeFileFromSharedStorage(result, file);
            }
        }
    }

    /**
     * Adds a file to the result cursor. It uses a combination of {@code #RAW_PREFIX} and its
     * absolute file path for its id. Directories are not to be included.
     *
     * @param result cursor containing all documents to be returned by queryChildDocuments or
     *            queryChildDocumentsForManage.
     * @param file file to be included in the result cursor.
     */
    private void includeFileFromSharedStorage(MatrixCursor result, File file)
            throws FileNotFoundException {
        includeFile(result, null, file);
    }

    private static File getDownloadsDirectory() {
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
    }

    /**
     * A MatrixCursor that spins up a file observer when the first instance is
     * started ({@link #start()}, and stops the file observer when the last instance
     * closed ({@link #close()}. When file changes are observed, a content change
     * notification is sent on the Downloads content URI.
     *
     * <p>This is necessary as other processes, like ExternalStorageProvider,
     * can access and modify files directly (without sending operations
     * through DownloadStorageProvider).
     *
     * <p>Without this, contents accessible by one a Downloads cursor instance
     * (like the Downloads root in Files app) can become state.
     */
    private static final class DownloadsCursor extends MatrixCursor {

        private static final Object mLock = new Object();
        @GuardedBy("mLock")
        private static int mOpenCursorCount = 0;
        @GuardedBy("mLock")
        private static @Nullable ContentChangedRelay mFileWatcher;

        private final ContentResolver mResolver;

        DownloadsCursor(String[] projection, ContentResolver resolver) {
            super(resolveDocumentProjection(projection));
            mResolver = resolver;
        }

        void start() {
            synchronized (mLock) {
                if (mOpenCursorCount++ == 0) {
                    mFileWatcher = new ContentChangedRelay(mResolver);
                    mFileWatcher.startWatching();
                }
            }
        }

        @Override
        public void close() {
            super.close();
            synchronized (mLock) {
                if (--mOpenCursorCount == 0) {
                    mFileWatcher.stopWatching();
                    mFileWatcher = null;
                }
            }
        }
    }

    /**
     * A file observer that notifies on the Downloads content URI(s) when
     * files change on disk.
     */
    private static class ContentChangedRelay extends FileObserver {
        private static final int NOTIFY_EVENTS = ATTRIB | CLOSE_WRITE | MOVED_FROM | MOVED_TO
                | CREATE | DELETE | DELETE_SELF | MOVE_SELF;

        private static final String DOWNLOADS_PATH = getDownloadsDirectory().getAbsolutePath();
        private final ContentResolver mResolver;

        public ContentChangedRelay(ContentResolver resolver) {
            super(DOWNLOADS_PATH, NOTIFY_EVENTS);
            mResolver = resolver;
        }

        @Override
        public void startWatching() {
            super.startWatching();
            if (DEBUG) Log.d(TAG, "Started watching for file changes in: " + DOWNLOADS_PATH);
        }

        @Override
        public void stopWatching() {
            super.stopWatching();
            if (DEBUG) Log.d(TAG, "Stopped watching for file changes in: " + DOWNLOADS_PATH);
        }

        @Override
        public void onEvent(int event, String path) {
            if ((event & NOTIFY_EVENTS) != 0) {
                if (DEBUG) Log.v(TAG, "Change detected at path: " + DOWNLOADS_PATH);
                mResolver.notifyChange(Downloads.Impl.ALL_DOWNLOADS_CONTENT_URI, null, false);
                mResolver.notifyChange(Downloads.Impl.CONTENT_URI, null, false);
            }
        }
    }
}
