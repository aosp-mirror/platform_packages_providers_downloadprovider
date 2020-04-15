/*
 * Copyright (C) 2007 The Android Open Source Project
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

import static android.provider.BaseColumns._ID;
import static android.provider.Downloads.Impl.COLUMN_DESTINATION;
import static android.provider.Downloads.Impl.COLUMN_IS_VISIBLE_IN_DOWNLOADS_UI;
import static android.provider.Downloads.Impl.COLUMN_MEDIASTORE_URI;
import static android.provider.Downloads.Impl.COLUMN_MEDIA_SCANNED;
import static android.provider.Downloads.Impl.COLUMN_OTHER_UID;
import static android.provider.Downloads.Impl.DESTINATION_FILE_URI;
import static android.provider.Downloads.Impl.DESTINATION_NON_DOWNLOADMANAGER_DOWNLOAD;
import static android.provider.Downloads.Impl.MEDIA_NOT_SCANNABLE;
import static android.provider.Downloads.Impl.MEDIA_NOT_SCANNED;
import static android.provider.Downloads.Impl.MEDIA_SCANNED;
import static android.provider.Downloads.Impl.PERMISSION_ACCESS_ALL;

import static com.android.providers.downloads.Helpers.convertToMediaStoreDownloadsUri;
import static com.android.providers.downloads.Helpers.triggerMediaScan;

import static com.android.providers.downloads.Helpers.convertToMediaStoreDownloadsUri;
import static com.android.providers.downloads.Helpers.triggerMediaScan;

import android.annotation.NonNull;
import android.app.AppOpsManager;
import android.app.DownloadManager;
import android.app.DownloadManager.Request;
import android.app.job.JobScheduler;
import android.content.ContentProvider;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.UriMatcher;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.ParcelFileDescriptor.OnCloseListener;
import android.os.Process;
import android.os.RemoteException;
import android.os.storage.StorageManager;
import android.provider.BaseColumns;
import android.provider.Downloads;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.util.ArrayUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;

import libcore.io.IoUtils;

import com.google.common.annotations.VisibleForTesting;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Iterator;
import java.util.Map;

/**
 * Allows application to interact with the download manager.
 */
public final class DownloadProvider extends ContentProvider {
    /** Database filename */
    private static final String DB_NAME = "downloads.db";
    /** Current database version */
    private static final int DB_VERSION = 114;
    /** Name of table in the database */
    private static final String DB_TABLE = "downloads";
    /** Memory optimization - close idle connections after 30s of inactivity */
    private static final int IDLE_CONNECTION_TIMEOUT_MS = 30000;

    /** MIME type for the entire download list */
    private static final String DOWNLOAD_LIST_TYPE = "vnd.android.cursor.dir/download";
    /** MIME type for an individual download */
    private static final String DOWNLOAD_TYPE = "vnd.android.cursor.item/download";

    /** URI matcher used to recognize URIs sent by applications */
    private static final UriMatcher sURIMatcher = new UriMatcher(UriMatcher.NO_MATCH);
    /** URI matcher constant for the URI of all downloads belonging to the calling UID */
    private static final int MY_DOWNLOADS = 1;
    /** URI matcher constant for the URI of an individual download belonging to the calling UID */
    private static final int MY_DOWNLOADS_ID = 2;
    /** URI matcher constant for the URI of a download's request headers */
    private static final int MY_DOWNLOADS_ID_HEADERS = 3;
    /** URI matcher constant for the URI of all downloads in the system */
    private static final int ALL_DOWNLOADS = 4;
    /** URI matcher constant for the URI of an individual download */
    private static final int ALL_DOWNLOADS_ID = 5;
    /** URI matcher constant for the URI of a download's request headers */
    private static final int ALL_DOWNLOADS_ID_HEADERS = 6;
    static {
        sURIMatcher.addURI("downloads", "my_downloads", MY_DOWNLOADS);
        sURIMatcher.addURI("downloads", "my_downloads/#", MY_DOWNLOADS_ID);
        sURIMatcher.addURI("downloads", "all_downloads", ALL_DOWNLOADS);
        sURIMatcher.addURI("downloads", "all_downloads/#", ALL_DOWNLOADS_ID);
        sURIMatcher.addURI("downloads",
                "my_downloads/#/" + Downloads.Impl.RequestHeaders.URI_SEGMENT,
                MY_DOWNLOADS_ID_HEADERS);
        sURIMatcher.addURI("downloads",
                "all_downloads/#/" + Downloads.Impl.RequestHeaders.URI_SEGMENT,
                ALL_DOWNLOADS_ID_HEADERS);
        // temporary, for backwards compatibility
        sURIMatcher.addURI("downloads", "download", MY_DOWNLOADS);
        sURIMatcher.addURI("downloads", "download/#", MY_DOWNLOADS_ID);
        sURIMatcher.addURI("downloads",
                "download/#/" + Downloads.Impl.RequestHeaders.URI_SEGMENT,
                MY_DOWNLOADS_ID_HEADERS);
    }

    /** Different base URIs that could be used to access an individual download */
    private static final Uri[] BASE_URIS = new Uri[] {
            Downloads.Impl.CONTENT_URI,
            Downloads.Impl.ALL_DOWNLOADS_CONTENT_URI,
    };

    private static void addMapping(Map<String, String> map, String column) {
        if (!map.containsKey(column)) {
            map.put(column, column);
        }
    }

    private static void addMapping(Map<String, String> map, String column, String rawColumn) {
        if (!map.containsKey(column)) {
            map.put(column, rawColumn + " AS " + column);
        }
    }

    private static final Map<String, String> sDownloadsMap = new ArrayMap<>();
    static {
        final Map<String, String> map = sDownloadsMap;

        // Columns defined by public API
        addMapping(map, DownloadManager.COLUMN_ID,
                Downloads.Impl._ID);
        addMapping(map, DownloadManager.COLUMN_LOCAL_FILENAME,
                Downloads.Impl._DATA);
        addMapping(map, DownloadManager.COLUMN_MEDIAPROVIDER_URI);
        addMapping(map, DownloadManager.COLUMN_DESTINATION);
        addMapping(map, DownloadManager.COLUMN_TITLE);
        addMapping(map, DownloadManager.COLUMN_DESCRIPTION);
        addMapping(map, DownloadManager.COLUMN_URI);
        addMapping(map, DownloadManager.COLUMN_STATUS);
        addMapping(map, DownloadManager.COLUMN_FILE_NAME_HINT);
        addMapping(map, DownloadManager.COLUMN_MEDIA_TYPE,
                Downloads.Impl.COLUMN_MIME_TYPE);
        addMapping(map, DownloadManager.COLUMN_TOTAL_SIZE_BYTES,
                Downloads.Impl.COLUMN_TOTAL_BYTES);
        addMapping(map, DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP,
                Downloads.Impl.COLUMN_LAST_MODIFICATION);
        addMapping(map, DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR,
                Downloads.Impl.COLUMN_CURRENT_BYTES);
        addMapping(map, DownloadManager.COLUMN_ALLOW_WRITE);
        addMapping(map, DownloadManager.COLUMN_LOCAL_URI,
                "'placeholder'");
        addMapping(map, DownloadManager.COLUMN_REASON,
                "'placeholder'");

        // Columns defined by OpenableColumns
        addMapping(map, OpenableColumns.DISPLAY_NAME,
                Downloads.Impl.COLUMN_TITLE);
        addMapping(map, OpenableColumns.SIZE,
                Downloads.Impl.COLUMN_TOTAL_BYTES);

        // Allow references to all other columns to support DownloadInfo.Reader;
        // we're already using SQLiteQueryBuilder to block access to other rows
        // that don't belong to the calling UID.
        addMapping(map, Downloads.Impl._ID);
        addMapping(map, Downloads.Impl._DATA);
        addMapping(map, Downloads.Impl.COLUMN_ALLOWED_NETWORK_TYPES);
        addMapping(map, Downloads.Impl.COLUMN_ALLOW_METERED);
        addMapping(map, Downloads.Impl.COLUMN_ALLOW_ROAMING);
        addMapping(map, Downloads.Impl.COLUMN_ALLOW_WRITE);
        addMapping(map, Downloads.Impl.COLUMN_APP_DATA);
        addMapping(map, Downloads.Impl.COLUMN_BYPASS_RECOMMENDED_SIZE_LIMIT);
        addMapping(map, Downloads.Impl.COLUMN_CONTROL);
        addMapping(map, Downloads.Impl.COLUMN_COOKIE_DATA);
        addMapping(map, Downloads.Impl.COLUMN_CURRENT_BYTES);
        addMapping(map, Downloads.Impl.COLUMN_DELETED);
        addMapping(map, Downloads.Impl.COLUMN_DESCRIPTION);
        addMapping(map, Downloads.Impl.COLUMN_DESTINATION);
        addMapping(map, Downloads.Impl.COLUMN_ERROR_MSG);
        addMapping(map, Downloads.Impl.COLUMN_FAILED_CONNECTIONS);
        addMapping(map, Downloads.Impl.COLUMN_FILE_NAME_HINT);
        addMapping(map, Downloads.Impl.COLUMN_FLAGS);
        addMapping(map, Downloads.Impl.COLUMN_IS_PUBLIC_API);
        addMapping(map, Downloads.Impl.COLUMN_IS_VISIBLE_IN_DOWNLOADS_UI);
        addMapping(map, Downloads.Impl.COLUMN_LAST_MODIFICATION);
        addMapping(map, Downloads.Impl.COLUMN_MEDIAPROVIDER_URI);
        addMapping(map, Downloads.Impl.COLUMN_MEDIA_SCANNED);
        addMapping(map, Downloads.Impl.COLUMN_MEDIASTORE_URI);
        addMapping(map, Downloads.Impl.COLUMN_MIME_TYPE);
        addMapping(map, Downloads.Impl.COLUMN_NO_INTEGRITY);
        addMapping(map, Downloads.Impl.COLUMN_NOTIFICATION_CLASS);
        addMapping(map, Downloads.Impl.COLUMN_NOTIFICATION_EXTRAS);
        addMapping(map, Downloads.Impl.COLUMN_NOTIFICATION_PACKAGE);
        addMapping(map, Downloads.Impl.COLUMN_OTHER_UID);
        addMapping(map, Downloads.Impl.COLUMN_REFERER);
        addMapping(map, Downloads.Impl.COLUMN_STATUS);
        addMapping(map, Downloads.Impl.COLUMN_TITLE);
        addMapping(map, Downloads.Impl.COLUMN_TOTAL_BYTES);
        addMapping(map, Downloads.Impl.COLUMN_URI);
        addMapping(map, Downloads.Impl.COLUMN_USER_AGENT);
        addMapping(map, Downloads.Impl.COLUMN_VISIBILITY);

        addMapping(map, Constants.ETAG);
        addMapping(map, Constants.RETRY_AFTER_X_REDIRECT_COUNT);
        addMapping(map, Constants.UID);
    }

    private static final Map<String, String> sHeadersMap = new ArrayMap<>();
    static {
        final Map<String, String> map = sHeadersMap;
        addMapping(map, "id");
        addMapping(map, Downloads.Impl.RequestHeaders.COLUMN_DOWNLOAD_ID);
        addMapping(map, Downloads.Impl.RequestHeaders.COLUMN_HEADER);
        addMapping(map, Downloads.Impl.RequestHeaders.COLUMN_VALUE);
    }

    @VisibleForTesting
    SystemFacade mSystemFacade;

    /** The database that lies underneath this content provider */
    private SQLiteOpenHelper mOpenHelper = null;

    /** List of uids that can access the downloads */
    private int mSystemUid = -1;

    private StorageManager mStorageManager;

    /**
     * Creates and updated database on demand when opening it.
     * Helper class to create database the first time the provider is
     * initialized and upgrade it when a new version of the provider needs
     * an updated version of the database.
     */
    private final class DatabaseHelper extends SQLiteOpenHelper {
        public DatabaseHelper(final Context context) {
            super(context, DB_NAME, null, DB_VERSION);
            setIdleConnectionTimeout(IDLE_CONNECTION_TIMEOUT_MS);
        }

        /**
         * Creates database the first time we try to open it.
         */
        @Override
        public void onCreate(final SQLiteDatabase db) {
            if (Constants.LOGVV) {
                Log.v(Constants.TAG, "populating new database");
            }
            onUpgrade(db, 0, DB_VERSION);
        }

        /**
         * Updates the database format when a content provider is used
         * with a database that was created with a different format.
         *
         * Note: to support downgrades, creating a table should always drop it first if it already
         * exists.
         */
        @Override
        public void onUpgrade(final SQLiteDatabase db, int oldV, final int newV) {
            if (oldV == 31) {
                // 31 and 100 are identical, just in different codelines. Upgrading from 31 is the
                // same as upgrading from 100.
                oldV = 100;
            } else if (oldV < 100) {
                // no logic to upgrade from these older version, just recreate the DB
                Log.i(Constants.TAG, "Upgrading downloads database from version " + oldV
                      + " to version " + newV + ", which will destroy all old data");
                oldV = 99;
            } else if (oldV > newV) {
                // user must have downgraded software; we have no way to know how to downgrade the
                // DB, so just recreate it
                Log.i(Constants.TAG, "Downgrading downloads database from version " + oldV
                      + " (current version is " + newV + "), destroying all old data");
                oldV = 99;
            }

            for (int version = oldV + 1; version <= newV; version++) {
                upgradeTo(db, version);
            }
        }

        /**
         * Upgrade database from (version - 1) to version.
         */
        private void upgradeTo(SQLiteDatabase db, int version) {
            switch (version) {
                case 100:
                    createDownloadsTable(db);
                    break;

                case 101:
                    createHeadersTable(db);
                    break;

                case 102:
                    addColumn(db, DB_TABLE, Downloads.Impl.COLUMN_IS_PUBLIC_API,
                              "INTEGER NOT NULL DEFAULT 0");
                    addColumn(db, DB_TABLE, Downloads.Impl.COLUMN_ALLOW_ROAMING,
                              "INTEGER NOT NULL DEFAULT 0");
                    addColumn(db, DB_TABLE, Downloads.Impl.COLUMN_ALLOWED_NETWORK_TYPES,
                              "INTEGER NOT NULL DEFAULT 0");
                    break;

                case 103:
                    addColumn(db, DB_TABLE, Downloads.Impl.COLUMN_IS_VISIBLE_IN_DOWNLOADS_UI,
                              "INTEGER NOT NULL DEFAULT 1");
                    makeCacheDownloadsInvisible(db);
                    break;

                case 104:
                    addColumn(db, DB_TABLE, Downloads.Impl.COLUMN_BYPASS_RECOMMENDED_SIZE_LIMIT,
                            "INTEGER NOT NULL DEFAULT 0");
                    break;

                case 105:
                    fillNullValues(db);
                    break;

                case 106:
                    addColumn(db, DB_TABLE, Downloads.Impl.COLUMN_MEDIAPROVIDER_URI, "TEXT");
                    addColumn(db, DB_TABLE, Downloads.Impl.COLUMN_DELETED,
                            "BOOLEAN NOT NULL DEFAULT 0");
                    break;

                case 107:
                    addColumn(db, DB_TABLE, Downloads.Impl.COLUMN_ERROR_MSG, "TEXT");
                    break;

                case 108:
                    addColumn(db, DB_TABLE, Downloads.Impl.COLUMN_ALLOW_METERED,
                            "INTEGER NOT NULL DEFAULT 1");
                    break;

                case 109:
                    addColumn(db, DB_TABLE, Downloads.Impl.COLUMN_ALLOW_WRITE,
                            "BOOLEAN NOT NULL DEFAULT 0");
                    break;

                case 110:
                    addColumn(db, DB_TABLE, Downloads.Impl.COLUMN_FLAGS,
                            "INTEGER NOT NULL DEFAULT 0");
                    break;

                case 111:
                    addColumn(db, DB_TABLE, Downloads.Impl.COLUMN_MEDIASTORE_URI,
                            "TEXT DEFAULT NULL");
                    addMediaStoreUris(db);
                    break;

                case 112:
                    updateMediaStoreUrisFromFilesToDownloads(db);
                    break;

                case 113:
                    canonicalizeDataPaths(db);
                    break;

                case 114:
                    nullifyMediaStoreUris(db);
                    MediaScanTriggerJob.schedule(getContext());
                    break;

                default:
                    throw new IllegalStateException("Don't know how to upgrade to " + version);
            }
        }

        /**
         * insert() now ensures these four columns are never null for new downloads, so this method
         * makes that true for existing columns, so that code can rely on this assumption.
         */
        private void fillNullValues(SQLiteDatabase db) {
            ContentValues values = new ContentValues();
            values.put(Downloads.Impl.COLUMN_CURRENT_BYTES, 0);
            fillNullValuesForColumn(db, values);
            values.put(Downloads.Impl.COLUMN_TOTAL_BYTES, -1);
            fillNullValuesForColumn(db, values);
            values.put(Downloads.Impl.COLUMN_TITLE, "");
            fillNullValuesForColumn(db, values);
            values.put(Downloads.Impl.COLUMN_DESCRIPTION, "");
            fillNullValuesForColumn(db, values);
        }

        private void fillNullValuesForColumn(SQLiteDatabase db, ContentValues values) {
            String column = values.valueSet().iterator().next().getKey();
            db.update(DB_TABLE, values, column + " is null", null);
            values.clear();
        }

        /**
         * Set all existing downloads to the cache partition to be invisible in the downloads UI.
         */
        private void makeCacheDownloadsInvisible(SQLiteDatabase db) {
            ContentValues values = new ContentValues();
            values.put(Downloads.Impl.COLUMN_IS_VISIBLE_IN_DOWNLOADS_UI, false);
            String cacheSelection = Downloads.Impl.COLUMN_DESTINATION
                    + " != " + Downloads.Impl.DESTINATION_EXTERNAL;
            db.update(DB_TABLE, values, cacheSelection, null);
        }

        /**
         * Add {@link Downloads.Impl#COLUMN_MEDIASTORE_URI} for all successful downloads and
         * add/update corresponding entries in MediaProvider.
         */
        private void addMediaStoreUris(@NonNull SQLiteDatabase db) {
            final String[] selectionArgs = new String[] {
                    Integer.toString(Downloads.Impl.DESTINATION_EXTERNAL),
                    Integer.toString(Downloads.Impl.DESTINATION_FILE_URI),
                    Integer.toString(Downloads.Impl.DESTINATION_NON_DOWNLOADMANAGER_DOWNLOAD),
            };
            final CallingIdentity token = clearCallingIdentity();
            try (Cursor cursor = db.query(DB_TABLE, null,
                    "_data IS NOT NULL AND is_visible_in_downloads_ui != '0'"
                            + " AND (destination=? OR destination=? OR destination=?)",
                    selectionArgs, null, null, null);
                    ContentProviderClient client = getContext().getContentResolver()
                            .acquireContentProviderClient(MediaStore.AUTHORITY)) {
                if (cursor.getCount() == 0) {
                    return;
                }
                final DownloadInfo.Reader reader
                        = new DownloadInfo.Reader(getContext().getContentResolver(), cursor);
                final DownloadInfo info = new DownloadInfo(getContext());
                final ContentValues updateValues = new ContentValues();
                while (cursor.moveToNext()) {
                    reader.updateFromDatabase(info);
                    final ContentValues mediaValues;
                    try {
                        mediaValues = convertToMediaProviderValues(info);
                    } catch (IllegalArgumentException e) {
                        Log.e(Constants.TAG, "Error getting media content values from " + info, e);
                        continue;
                    }
                    final Uri mediaStoreUri = updateMediaProvider(client, mediaValues);
                    if (mediaStoreUri != null) {
                        updateValues.clear();
                        updateValues.put(Downloads.Impl.COLUMN_MEDIASTORE_URI,
                                mediaStoreUri.toString());
                        db.update(DB_TABLE, updateValues, Downloads.Impl._ID + "=?",
                                new String[] { Long.toString(info.mId) });
                    }
                }
            } finally {
                restoreCallingIdentity(token);
            }
        }

        /**
         * DownloadProvider has been updated to use MediaStore.Downloads based uris
         * for COLUMN_MEDIASTORE_URI but the existing entries would still have MediaStore.Files
         * based uris. It's possible that in the future we might incorrectly assume that all the
         * uris are MediaStore.DownloadColumns based and end up querying some
         * MediaStore.Downloads specific columns. To avoid this, update the existing entries to
         * use MediaStore.Downloads based uris only.
         */
        private void updateMediaStoreUrisFromFilesToDownloads(SQLiteDatabase db) {
            try (Cursor cursor = db.query(DB_TABLE,
                    new String[] { Downloads.Impl._ID, COLUMN_MEDIASTORE_URI },
                    COLUMN_MEDIASTORE_URI + " IS NOT NULL", null, null, null, null)) {
                final ContentValues updateValues = new ContentValues();
                while (cursor.moveToNext()) {
                    final long id = cursor.getLong(0);
                    final Uri mediaStoreFilesUri = Uri.parse(cursor.getString(1));

                    final long mediaStoreId = ContentUris.parseId(mediaStoreFilesUri);
                    final String volumeName = MediaStore.getVolumeName(mediaStoreFilesUri);
                    final Uri mediaStoreDownloadsUri
                            = MediaStore.Downloads.getContentUri(volumeName, mediaStoreId);

                    updateValues.clear();
                    updateValues.put(COLUMN_MEDIASTORE_URI, mediaStoreDownloadsUri.toString());
                    db.update(DB_TABLE, updateValues, Downloads.Impl._ID + "=?",
                            new String[] { Long.toString(id) });
                }
            }
        }

        private void canonicalizeDataPaths(SQLiteDatabase db) {
            try (Cursor cursor = db.query(DB_TABLE,
                    new String[] { Downloads.Impl._ID, Downloads.Impl._DATA},
                    Downloads.Impl._DATA + " IS NOT NULL", null, null, null, null)) {
                final ContentValues updateValues = new ContentValues();
                while (cursor.moveToNext()) {
                    final long id = cursor.getLong(0);
                    final String filePath = cursor.getString(1);
                    final String canonicalPath;
                    try {
                        canonicalPath = new File(filePath).getCanonicalPath();
                    } catch (IOException e) {
                        Log.e(Constants.TAG, "Found invalid path='" + filePath + "' for id=" + id);
                        continue;
                    }

                    updateValues.clear();
                    updateValues.put(Downloads.Impl._DATA, canonicalPath);
                    db.update(DB_TABLE, updateValues, Downloads.Impl._ID + "=?",
                            new String[] { Long.toString(id) });
                }
            }
        }

        /**
         * Set mediastore uri column to null before the clean-up job and fill it again while
         * running the job so that if the clean-up job gets preempted, we could use it
         * as a way to know the entries which are already handled when the job gets restarted.
         */
        private void nullifyMediaStoreUris(SQLiteDatabase db) {
            final String whereClause = Downloads.Impl._DATA + " IS NOT NULL"
                    + " AND (" + COLUMN_IS_VISIBLE_IN_DOWNLOADS_UI + "=1"
                    + " OR " + COLUMN_MEDIA_SCANNED + "=" + MEDIA_SCANNED + ")"
                    + " AND (" + COLUMN_DESTINATION + "=" + Downloads.Impl.DESTINATION_EXTERNAL
                    + " OR " + COLUMN_DESTINATION + "=" + DESTINATION_FILE_URI
                    + " OR " + COLUMN_DESTINATION + "=" + DESTINATION_NON_DOWNLOADMANAGER_DOWNLOAD
                    + ")";
            final ContentValues values = new ContentValues();
            values.putNull(COLUMN_MEDIASTORE_URI);
            db.update(DB_TABLE, values, whereClause, null);
        }

        /**
         * Add a column to a table using ALTER TABLE.
         * @param dbTable name of the table
         * @param columnName name of the column to add
         * @param columnDefinition SQL for the column definition
         */
        private void addColumn(SQLiteDatabase db, String dbTable, String columnName,
                               String columnDefinition) {
            db.execSQL("ALTER TABLE " + dbTable + " ADD COLUMN " + columnName + " "
                       + columnDefinition);
        }

        /**
         * Creates the table that'll hold the download information.
         */
        private void createDownloadsTable(SQLiteDatabase db) {
            try {
                db.execSQL("DROP TABLE IF EXISTS " + DB_TABLE);
                db.execSQL("CREATE TABLE " + DB_TABLE + "(" +
                        Downloads.Impl._ID + " INTEGER PRIMARY KEY AUTOINCREMENT," +
                        Downloads.Impl.COLUMN_URI + " TEXT, " +
                        Constants.RETRY_AFTER_X_REDIRECT_COUNT + " INTEGER, " +
                        Downloads.Impl.COLUMN_APP_DATA + " TEXT, " +
                        Downloads.Impl.COLUMN_NO_INTEGRITY + " BOOLEAN, " +
                        Downloads.Impl.COLUMN_FILE_NAME_HINT + " TEXT, " +
                        Constants.OTA_UPDATE + " BOOLEAN, " +
                        Downloads.Impl._DATA + " TEXT, " +
                        Downloads.Impl.COLUMN_MIME_TYPE + " TEXT, " +
                        Downloads.Impl.COLUMN_DESTINATION + " INTEGER, " +
                        Constants.NO_SYSTEM_FILES + " BOOLEAN, " +
                        Downloads.Impl.COLUMN_VISIBILITY + " INTEGER, " +
                        Downloads.Impl.COLUMN_CONTROL + " INTEGER, " +
                        Downloads.Impl.COLUMN_STATUS + " INTEGER, " +
                        Downloads.Impl.COLUMN_FAILED_CONNECTIONS + " INTEGER, " +
                        Downloads.Impl.COLUMN_LAST_MODIFICATION + " BIGINT, " +
                        Downloads.Impl.COLUMN_NOTIFICATION_PACKAGE + " TEXT, " +
                        Downloads.Impl.COLUMN_NOTIFICATION_CLASS + " TEXT, " +
                        Downloads.Impl.COLUMN_NOTIFICATION_EXTRAS + " TEXT, " +
                        Downloads.Impl.COLUMN_COOKIE_DATA + " TEXT, " +
                        Downloads.Impl.COLUMN_USER_AGENT + " TEXT, " +
                        Downloads.Impl.COLUMN_REFERER + " TEXT, " +
                        Downloads.Impl.COLUMN_TOTAL_BYTES + " INTEGER, " +
                        Downloads.Impl.COLUMN_CURRENT_BYTES + " INTEGER, " +
                        Constants.ETAG + " TEXT, " +
                        Constants.UID + " INTEGER, " +
                        Downloads.Impl.COLUMN_OTHER_UID + " INTEGER, " +
                        Downloads.Impl.COLUMN_TITLE + " TEXT, " +
                        Downloads.Impl.COLUMN_DESCRIPTION + " TEXT, " +
                        Downloads.Impl.COLUMN_MEDIA_SCANNED + " BOOLEAN);");
            } catch (SQLException ex) {
                Log.e(Constants.TAG, "couldn't create table in downloads database");
                throw ex;
            }
        }

        private void createHeadersTable(SQLiteDatabase db) {
            db.execSQL("DROP TABLE IF EXISTS " + Downloads.Impl.RequestHeaders.HEADERS_DB_TABLE);
            db.execSQL("CREATE TABLE " + Downloads.Impl.RequestHeaders.HEADERS_DB_TABLE + "(" +
                       "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                       Downloads.Impl.RequestHeaders.COLUMN_DOWNLOAD_ID + " INTEGER NOT NULL," +
                       Downloads.Impl.RequestHeaders.COLUMN_HEADER + " TEXT NOT NULL," +
                       Downloads.Impl.RequestHeaders.COLUMN_VALUE + " TEXT NOT NULL" +
                       ");");
        }
    }

    /**
     * Initializes the content provider when it is created.
     */
    @Override
    public boolean onCreate() {
        if (mSystemFacade == null) {
            mSystemFacade = new RealSystemFacade(getContext());
        }

        mOpenHelper = new DatabaseHelper(getContext());
        // Initialize the system uid
        mSystemUid = Process.SYSTEM_UID;

        mStorageManager = getContext().getSystemService(StorageManager.class);

        reconcileRemovedUidEntries();
        return true;
    }

    private void reconcileRemovedUidEntries() {
        Helpers.handleRemovedUidEntries(getContext(), this, -1, this::grantAllDownloadsPermission);
    }

    /**
     * Returns the content-provider-style MIME types of the various
     * types accessible through this content provider.
     */
    @Override
    public String getType(final Uri uri) {
        int match = sURIMatcher.match(uri);
        switch (match) {
            case MY_DOWNLOADS:
            case ALL_DOWNLOADS: {
                return DOWNLOAD_LIST_TYPE;
            }
            case MY_DOWNLOADS_ID:
            case ALL_DOWNLOADS_ID: {
                // return the mimetype of this id from the database
                final String id = getDownloadIdFromUri(uri);
                final SQLiteDatabase db = mOpenHelper.getReadableDatabase();
                final String mimeType = DatabaseUtils.stringForQuery(db,
                        "SELECT " + Downloads.Impl.COLUMN_MIME_TYPE + " FROM " + DB_TABLE +
                        " WHERE " + Downloads.Impl._ID + " = ?",
                        new String[]{id});
                if (TextUtils.isEmpty(mimeType)) {
                    return DOWNLOAD_TYPE;
                } else {
                    return mimeType;
                }
            }
            default: {
                if (Constants.LOGV) {
                    Log.v(Constants.TAG, "calling getType on an unknown URI: " + uri);
                }
                throw new IllegalArgumentException("Unknown URI: " + uri);
            }
        }
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        switch (method) {
            case Downloads.CALL_MEDIASTORE_DOWNLOADS_DELETED: {
                getContext().enforceCallingOrSelfPermission(
                        android.Manifest.permission.WRITE_MEDIA_STORAGE, Constants.TAG);
                final long[] deletedDownloadIds = extras.getLongArray(Downloads.EXTRA_IDS);
                final String[] mimeTypes = extras.getStringArray(Downloads.EXTRA_MIME_TYPES);
                DownloadStorageProvider.onMediaProviderDownloadsDelete(getContext(),
                        deletedDownloadIds, mimeTypes);
                return null;
            }
            case Downloads.CALL_CREATE_EXTERNAL_PUBLIC_DIR: {
                final String dirType = extras.getString(Downloads.DIR_TYPE);
                if (!ArrayUtils.contains(Environment.STANDARD_DIRECTORIES, dirType)) {
                    throw new IllegalStateException("Not one of standard directories: " + dirType);
                }
                final File file = Environment.getExternalStoragePublicDirectory(dirType);
                if (file.exists()) {
                    if (!file.isDirectory()) {
                        throw new IllegalStateException(file.getAbsolutePath() +
                                " already exists and is not a directory");
                    }
                } else if (!file.mkdirs()) {
                    throw new IllegalStateException("Unable to create directory: " +
                            file.getAbsolutePath());
                }
                return null;
            }
            case Downloads.CALL_REVOKE_MEDIASTORE_URI_PERMS : {
                getContext().enforceCallingOrSelfPermission(
                        android.Manifest.permission.WRITE_MEDIA_STORAGE, Constants.TAG);
                DownloadStorageProvider.revokeAllMediaStoreUriPermissions(getContext());
                return null;
            }
            default:
                throw new UnsupportedOperationException("Unsupported call: " + method);
        }
    }

    /**
     * Inserts a row in the database
     */
    @Override
    public Uri insert(final Uri uri, final ContentValues values) {
        checkInsertPermissions(values);
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();

        // note we disallow inserting into ALL_DOWNLOADS
        int match = sURIMatcher.match(uri);
        if (match != MY_DOWNLOADS) {
            Log.d(Constants.TAG, "calling insert on an unknown/invalid URI: " + uri);
            throw new IllegalArgumentException("Unknown/Invalid URI " + uri);
        }

        ContentValues filteredValues = new ContentValues();

        boolean isPublicApi =
                values.getAsBoolean(Downloads.Impl.COLUMN_IS_PUBLIC_API) == Boolean.TRUE;

        // validate the destination column
        Integer dest = values.getAsInteger(Downloads.Impl.COLUMN_DESTINATION);
        if (dest != null) {
            if (getContext().checkCallingOrSelfPermission(Downloads.Impl.PERMISSION_ACCESS_ADVANCED)
                    != PackageManager.PERMISSION_GRANTED
                    && (dest == Downloads.Impl.DESTINATION_CACHE_PARTITION
                            || dest == Downloads.Impl.DESTINATION_CACHE_PARTITION_NOROAMING)) {
                throw new SecurityException("setting destination to : " + dest +
                        " not allowed, unless PERMISSION_ACCESS_ADVANCED is granted");
            }
            // for public API behavior, if an app has CACHE_NON_PURGEABLE permission, automatically
            // switch to non-purgeable download
            boolean hasNonPurgeablePermission =
                    getContext().checkCallingOrSelfPermission(
                            Downloads.Impl.PERMISSION_CACHE_NON_PURGEABLE)
                            == PackageManager.PERMISSION_GRANTED;
            if (isPublicApi && dest == Downloads.Impl.DESTINATION_CACHE_PARTITION_PURGEABLE
                    && hasNonPurgeablePermission) {
                dest = Downloads.Impl.DESTINATION_CACHE_PARTITION;
            }
            if (dest == Downloads.Impl.DESTINATION_FILE_URI) {
                checkFileUriDestination(values);
            } else if (dest == DESTINATION_NON_DOWNLOADMANAGER_DOWNLOAD) {
                checkDownloadedFilePath(values);
            } else if (dest == Downloads.Impl.DESTINATION_EXTERNAL) {
                getContext().enforceCallingOrSelfPermission(
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        "No permission to write");

                final AppOpsManager appOps = getContext().getSystemService(AppOpsManager.class);
                if (appOps.noteProxyOp(AppOpsManager.OP_WRITE_EXTERNAL_STORAGE, getCallingPackage(),
                        Binder.getCallingUid(), getCallingAttributionTag(), null)
                        != AppOpsManager.MODE_ALLOWED) {
                    throw new SecurityException("No permission to write");
                }
            }

            filteredValues.put(Downloads.Impl.COLUMN_DESTINATION, dest);
        }

        ensureDefaultColumns(values);

        // copy some of the input values as is
        copyString(Downloads.Impl.COLUMN_URI, values, filteredValues);
        copyString(Downloads.Impl.COLUMN_APP_DATA, values, filteredValues);
        copyBoolean(Downloads.Impl.COLUMN_NO_INTEGRITY, values, filteredValues);
        copyString(Downloads.Impl.COLUMN_FILE_NAME_HINT, values, filteredValues);
        copyString(Downloads.Impl.COLUMN_MIME_TYPE, values, filteredValues);
        copyBoolean(Downloads.Impl.COLUMN_IS_PUBLIC_API, values, filteredValues);

        // validate the visibility column
        Integer vis = values.getAsInteger(Downloads.Impl.COLUMN_VISIBILITY);
        if (vis == null) {
            if (dest == Downloads.Impl.DESTINATION_EXTERNAL) {
                filteredValues.put(Downloads.Impl.COLUMN_VISIBILITY,
                        Downloads.Impl.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            } else {
                filteredValues.put(Downloads.Impl.COLUMN_VISIBILITY,
                        Downloads.Impl.VISIBILITY_HIDDEN);
            }
        } else {
            filteredValues.put(Downloads.Impl.COLUMN_VISIBILITY, vis);
        }
        // copy the control column as is
        copyInteger(Downloads.Impl.COLUMN_CONTROL, values, filteredValues);

        /*
         * requests coming from
         * DownloadManager.addCompletedDownload(String, String, String,
         * boolean, String, String, long) need special treatment
         */
        if (values.getAsInteger(Downloads.Impl.COLUMN_DESTINATION) ==
                Downloads.Impl.DESTINATION_NON_DOWNLOADMANAGER_DOWNLOAD) {
            // these requests always are marked as 'completed'
            filteredValues.put(Downloads.Impl.COLUMN_STATUS, Downloads.Impl.STATUS_SUCCESS);
            filteredValues.put(Downloads.Impl.COLUMN_TOTAL_BYTES,
                    values.getAsLong(Downloads.Impl.COLUMN_TOTAL_BYTES));
            filteredValues.put(Downloads.Impl.COLUMN_CURRENT_BYTES, 0);
            copyString(Downloads.Impl._DATA, values, filteredValues);
            copyBoolean(Downloads.Impl.COLUMN_ALLOW_WRITE, values, filteredValues);
        } else {
            filteredValues.put(Downloads.Impl.COLUMN_STATUS, Downloads.Impl.STATUS_PENDING);
            filteredValues.put(Downloads.Impl.COLUMN_TOTAL_BYTES, -1);
            filteredValues.put(Downloads.Impl.COLUMN_CURRENT_BYTES, 0);
        }

        // set lastupdate to current time
        long lastMod = mSystemFacade.currentTimeMillis();
        filteredValues.put(Downloads.Impl.COLUMN_LAST_MODIFICATION, lastMod);

        // use packagename of the caller to set the notification columns
        String pckg = values.getAsString(Downloads.Impl.COLUMN_NOTIFICATION_PACKAGE);
        String clazz = values.getAsString(Downloads.Impl.COLUMN_NOTIFICATION_CLASS);
        if (pckg != null && (clazz != null || isPublicApi)) {
            int uid = Binder.getCallingUid();
            try {
                if (uid == 0 || mSystemFacade.userOwnsPackage(uid, pckg)) {
                    filteredValues.put(Downloads.Impl.COLUMN_NOTIFICATION_PACKAGE, pckg);
                    if (clazz != null) {
                        filteredValues.put(Downloads.Impl.COLUMN_NOTIFICATION_CLASS, clazz);
                    }
                }
            } catch (PackageManager.NameNotFoundException ex) {
                /* ignored for now */
            }
        }

        // copy some more columns as is
        copyString(Downloads.Impl.COLUMN_NOTIFICATION_EXTRAS, values, filteredValues);
        copyString(Downloads.Impl.COLUMN_COOKIE_DATA, values, filteredValues);
        copyString(Downloads.Impl.COLUMN_USER_AGENT, values, filteredValues);
        copyString(Downloads.Impl.COLUMN_REFERER, values, filteredValues);

        // UID, PID columns
        if (getContext().checkCallingOrSelfPermission(Downloads.Impl.PERMISSION_ACCESS_ADVANCED)
                == PackageManager.PERMISSION_GRANTED) {
            copyInteger(Downloads.Impl.COLUMN_OTHER_UID, values, filteredValues);
        }
        filteredValues.put(Constants.UID, Binder.getCallingUid());
        if (Binder.getCallingUid() == 0) {
            copyInteger(Constants.UID, values, filteredValues);
        }

        // copy some more columns as is
        copyStringWithDefault(Downloads.Impl.COLUMN_TITLE, values, filteredValues, "");
        copyStringWithDefault(Downloads.Impl.COLUMN_DESCRIPTION, values, filteredValues, "");

        // is_visible_in_downloads_ui column
        copyBoolean(COLUMN_IS_VISIBLE_IN_DOWNLOADS_UI, values, filteredValues);

        // public api requests and networktypes/roaming columns
        if (isPublicApi) {
            copyInteger(Downloads.Impl.COLUMN_ALLOWED_NETWORK_TYPES, values, filteredValues);
            copyBoolean(Downloads.Impl.COLUMN_ALLOW_ROAMING, values, filteredValues);
            copyBoolean(Downloads.Impl.COLUMN_ALLOW_METERED, values, filteredValues);
            copyInteger(Downloads.Impl.COLUMN_FLAGS, values, filteredValues);
        }

        final Integer mediaScanned = values.getAsInteger(Downloads.Impl.COLUMN_MEDIA_SCANNED);
        filteredValues.put(COLUMN_MEDIA_SCANNED,
                mediaScanned == null ? MEDIA_NOT_SCANNED : mediaScanned);

        final boolean shouldBeVisibleToUser
                = filteredValues.getAsBoolean(COLUMN_IS_VISIBLE_IN_DOWNLOADS_UI)
                        || filteredValues.getAsInteger(COLUMN_MEDIA_SCANNED) == MEDIA_NOT_SCANNED;
        if (shouldBeVisibleToUser && filteredValues.getAsInteger(COLUMN_DESTINATION)
                == DESTINATION_NON_DOWNLOADMANAGER_DOWNLOAD) {
            final CallingIdentity token = clearCallingIdentity();
            try {
                final Uri mediaStoreUri = MediaStore.scanFile(getContext().getContentResolver(),
                        new File(filteredValues.getAsString(Downloads.Impl._DATA)));
                if (mediaStoreUri != null) {
                    final ContentValues mediaValues = new ContentValues();
                    mediaValues.put(MediaStore.Downloads.DOWNLOAD_URI,
                            filteredValues.getAsString(Downloads.Impl.COLUMN_URI));
                    mediaValues.put(MediaStore.Downloads.REFERER_URI,
                            filteredValues.getAsString(Downloads.Impl.COLUMN_REFERER));
                    mediaValues.put(MediaStore.Downloads.OWNER_PACKAGE_NAME,
                            Helpers.getPackageForUid(getContext(),
                                    filteredValues.getAsInteger(Constants.UID)));
                    getContext().getContentResolver().update(
                            convertToMediaStoreDownloadsUri(mediaStoreUri),
                            mediaValues, null, null);

                    filteredValues.put(Downloads.Impl.COLUMN_MEDIASTORE_URI,
                            mediaStoreUri.toString());
                    filteredValues.put(Downloads.Impl.COLUMN_MEDIAPROVIDER_URI,
                            mediaStoreUri.toString());
                    filteredValues.put(COLUMN_MEDIA_SCANNED, MEDIA_SCANNED);
                }
            } finally {
                restoreCallingIdentity(token);
            }
        }

        if (Constants.LOGVV) {
            Log.v(Constants.TAG, "initiating download with UID "
                    + filteredValues.getAsInteger(Constants.UID));
            if (filteredValues.containsKey(Downloads.Impl.COLUMN_OTHER_UID)) {
                Log.v(Constants.TAG, "other UID " +
                        filteredValues.getAsInteger(Downloads.Impl.COLUMN_OTHER_UID));
            }
        }

        long rowID = db.insert(DB_TABLE, null, filteredValues);
        if (rowID == -1) {
            Log.d(Constants.TAG, "couldn't insert into downloads database");
            return null;
        }

        insertRequestHeaders(db, rowID, values);

        final String callingPackage = Helpers.getPackageForUid(getContext(),
                Binder.getCallingUid());
        if (callingPackage == null) {
            Log.e(Constants.TAG, "Package does not exist for calling uid");
            return null;
        }
        grantAllDownloadsPermission(callingPackage, rowID);
        notifyContentChanged(uri, match);

        final long token = Binder.clearCallingIdentity();
        try {
            Helpers.scheduleJob(getContext(), rowID);
        } finally {
            Binder.restoreCallingIdentity(token);
        }

        return ContentUris.withAppendedId(Downloads.Impl.CONTENT_URI, rowID);
    }

    /**
     * If an entry corresponding to given mediaValues doesn't already exist in MediaProvider,
     * add it, otherwise update that entry with the given values.
     */
    private Uri updateMediaProvider(@NonNull ContentProviderClient mediaProvider,
            @NonNull ContentValues mediaValues) {
        final String filePath = mediaValues.getAsString(MediaStore.DownloadColumns.DATA);
        Uri mediaStoreUri = getMediaStoreUri(mediaProvider, filePath);

        try {
            if (mediaStoreUri == null) {
                mediaStoreUri = mediaProvider.insert(
                        Helpers.getContentUriForPath(getContext(), filePath),
                        mediaValues);
                if (mediaStoreUri == null) {
                    Log.e(Constants.TAG, "Error inserting into mediaProvider: " + mediaValues);
                }
                return mediaStoreUri;
            } else {
                if (mediaProvider.update(mediaStoreUri, mediaValues, null, null) != 1) {
                    Log.e(Constants.TAG, "Error updating MediaProvider, uri: " + mediaStoreUri
                            + ", values: " + mediaValues);
                }
                return mediaStoreUri;
            }
        } catch (RemoteException e) {
            // Should not happen
        }
        return null;
    }

    private Uri getMediaStoreUri(@NonNull ContentProviderClient mediaProvider,
            @NonNull String filePath) {
        final Uri filesUri = MediaStore.setIncludePending(
                Helpers.getContentUriForPath(getContext(), filePath));
        try (Cursor cursor = mediaProvider.query(filesUri,
                new String[] { MediaStore.Files.FileColumns._ID },
                MediaStore.Files.FileColumns.DATA + "=?", new String[] { filePath }, null, null)) {
            if (cursor.moveToNext()) {
                return ContentUris.withAppendedId(filesUri, cursor.getLong(0));
            }
        } catch (RemoteException e) {
            // Should not happen
        }
        return null;
    }

    private ContentValues convertToMediaProviderValues(DownloadInfo info) {
        final String filePath;
        try {
            filePath = new File(info.mFileName).getCanonicalPath();
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
        final boolean downloadCompleted = Downloads.Impl.isStatusCompleted(info.mStatus);
        final ContentValues mediaValues = new ContentValues();
        mediaValues.put(MediaStore.Downloads.DATA,  filePath);
        mediaValues.put(MediaStore.Downloads.SIZE,
                downloadCompleted ? info.mTotalBytes : info.mCurrentBytes);
        mediaValues.put(MediaStore.Downloads.DOWNLOAD_URI, info.mUri);
        mediaValues.put(MediaStore.Downloads.REFERER_URI, info.mReferer);
        mediaValues.put(MediaStore.Downloads.MIME_TYPE, info.mMimeType);
        mediaValues.put(MediaStore.Downloads.IS_PENDING, downloadCompleted ? 0 : 1);
        mediaValues.put(MediaStore.Downloads.OWNER_PACKAGE_NAME,
                Helpers.getPackageForUid(getContext(), info.mUid));
        return mediaValues;
    }

    private static Uri getFileUri(String uriString) {
        final Uri uri = Uri.parse(uriString);
        return TextUtils.equals(uri.getScheme(), ContentResolver.SCHEME_FILE) ? uri : null;
    }

    private void ensureDefaultColumns(ContentValues values) {
        final Integer dest = values.getAsInteger(COLUMN_DESTINATION);
        if (dest != null) {
            final int mediaScannable;
            final boolean visibleInDownloadsUi;
            if (dest == Downloads.Impl.DESTINATION_EXTERNAL) {
                mediaScannable = MEDIA_NOT_SCANNED;
                visibleInDownloadsUi = true;
            } else if (dest != DESTINATION_FILE_URI
                    && dest != DESTINATION_NON_DOWNLOADMANAGER_DOWNLOAD) {
                mediaScannable = MEDIA_NOT_SCANNABLE;
                visibleInDownloadsUi = false;
            } else {
                final File file;
                if (dest == Downloads.Impl.DESTINATION_FILE_URI) {
                    final String fileUri = values.getAsString(Downloads.Impl.COLUMN_FILE_NAME_HINT);
                    file = new File(getFileUri(fileUri).getPath());
                } else {
                    file = new File(values.getAsString(Downloads.Impl._DATA));
                }

                if (Helpers.isFileInExternalAndroidDirs(file.getAbsolutePath())) {
                    mediaScannable = MEDIA_NOT_SCANNABLE;
                    visibleInDownloadsUi = false;
                } else if (Helpers.isFilenameValidInPublicDownloadsDir(file)) {
                    mediaScannable = MEDIA_NOT_SCANNED;
                    visibleInDownloadsUi = true;
                } else {
                    mediaScannable = MEDIA_NOT_SCANNED;
                    visibleInDownloadsUi = false;
                }
            }
            values.put(COLUMN_MEDIA_SCANNED, mediaScannable);
            values.put(COLUMN_IS_VISIBLE_IN_DOWNLOADS_UI, visibleInDownloadsUi);
        } else {
            if (!values.containsKey(COLUMN_IS_VISIBLE_IN_DOWNLOADS_UI)) {
                values.put(COLUMN_IS_VISIBLE_IN_DOWNLOADS_UI, true);
            }
        }
    }

    /**
     * Check that the file URI provided for DESTINATION_FILE_URI is valid.
     */
    private void checkFileUriDestination(ContentValues values) {
        String fileUri = values.getAsString(Downloads.Impl.COLUMN_FILE_NAME_HINT);
        if (fileUri == null) {
            throw new IllegalArgumentException(
                    "DESTINATION_FILE_URI must include a file URI under COLUMN_FILE_NAME_HINT");
        }
        final Uri uri = getFileUri(fileUri);
        if (uri == null) {
            throw new IllegalArgumentException("Not a file URI: " + uri);
        }
        final String path = uri.getPath();
        if (path == null || ("/" + path + "/").contains("/../")) {
            throw new IllegalArgumentException("Invalid file URI: " + uri);
        }

        final File file;
        try {
            file = new File(path).getCanonicalFile();
            values.put(Downloads.Impl.COLUMN_FILE_NAME_HINT, Uri.fromFile(file).toString());
        } catch (IOException e) {
            throw new SecurityException(e);
        }

        final int targetSdkVersion = getCallingPackageTargetSdkVersion();
        final AppOpsManager appOpsManager = getContext().getSystemService(AppOpsManager.class);
        final boolean runningLegacyMode = appOpsManager.checkOp(AppOpsManager.OP_LEGACY_STORAGE,
                Binder.getCallingUid(), getCallingPackage()) == AppOpsManager.MODE_ALLOWED;

        if (Helpers.isFilenameValidInExternalPackage(getContext(), file, getCallingPackage())
                || Helpers.isFilenameValidInKnownPublicDir(file.getAbsolutePath())) {
            // No permissions required for paths belonging to calling package or
            // public downloads dir.
            return;
        } else if (runningLegacyMode && Helpers.isFilenameValidInExternal(getContext(), file)) {
            // Otherwise we require write permission
            getContext().enforceCallingOrSelfPermission(
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    "No permission to write to " + file);

            final AppOpsManager appOps = getContext().getSystemService(AppOpsManager.class);
            if (appOps.noteProxyOp(AppOpsManager.OP_WRITE_EXTERNAL_STORAGE, getCallingPackage(),
                    Binder.getCallingUid(), getCallingAttributionTag(), null)
                    != AppOpsManager.MODE_ALLOWED) {
                throw new SecurityException("No permission to write to " + file);
            }
        } else {
            throw new SecurityException("Unsupported path " + file);
        }
    }

    private void checkDownloadedFilePath(ContentValues values) {
        final String path = values.getAsString(Downloads.Impl._DATA);
        if (path == null || ("/" + path + "/").contains("/../")) {
            throw new IllegalArgumentException("Invalid file path: "
                    + (path == null ? "null" : path));
        }

        final File file;
        try {
            file = new File(path).getCanonicalFile();
            values.put(Downloads.Impl._DATA, file.getPath());
        } catch (IOException e) {
            throw new SecurityException(e);
        }

        if (!file.exists()) {
            throw new IllegalArgumentException("File doesn't exist: " + file);
        }

        final int targetSdkVersion = getCallingPackageTargetSdkVersion();
        final AppOpsManager appOpsManager = getContext().getSystemService(AppOpsManager.class);
        final boolean runningLegacyMode = appOpsManager.checkOp(AppOpsManager.OP_LEGACY_STORAGE,
                Binder.getCallingUid(), getCallingPackage()) == AppOpsManager.MODE_ALLOWED;

        if (Binder.getCallingPid() == Process.myPid()) {
            return;
        } else if (Helpers.isFilenameValidInExternalPackage(getContext(), file, getCallingPackage())
                || Helpers.isFilenameValidInPublicDownloadsDir(file)) {
            // No permissions required for paths belonging to calling package or
            // public downloads dir.
            return;
        } else if (runningLegacyMode && Helpers.isFilenameValidInExternal(getContext(), file)) {
            // Otherwise we require write permission
            getContext().enforceCallingOrSelfPermission(
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    "No permission to write to " + file);

            final AppOpsManager appOps = getContext().getSystemService(AppOpsManager.class);
            if (appOps.noteProxyOp(AppOpsManager.OP_WRITE_EXTERNAL_STORAGE, getCallingPackage(),
                    Binder.getCallingUid(), getCallingAttributionTag(), null)
                    != AppOpsManager.MODE_ALLOWED) {
                throw new SecurityException("No permission to write to " + file);
            }
        } else {
            throw new SecurityException("Unsupported path " + file);
        }
    }

    private int getCallingPackageTargetSdkVersion() {
        final String callingPackage = getCallingPackage();
        if (callingPackage != null) {
            ApplicationInfo ai = null;
            try {
                ai = getContext().getPackageManager()
                        .getApplicationInfo(callingPackage, 0);
            } catch (PackageManager.NameNotFoundException ignored) {
            }
            if (ai != null) {
                return ai.targetSdkVersion;
            }
        }
        return Build.VERSION_CODES.CUR_DEVELOPMENT;
    }

    /**
     * Apps with the ACCESS_DOWNLOAD_MANAGER permission can access this provider freely, subject to
     * constraints in the rest of the code. Apps without that may still access this provider through
     * the public API, but additional restrictions are imposed. We check those restrictions here.
     *
     * @param values ContentValues provided to insert()
     * @throws SecurityException if the caller has insufficient permissions
     */
    private void checkInsertPermissions(ContentValues values) {
        if (getContext().checkCallingOrSelfPermission(Downloads.Impl.PERMISSION_ACCESS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }

        getContext().enforceCallingOrSelfPermission(android.Manifest.permission.INTERNET,
                "INTERNET permission is required to use the download manager");

        // ensure the request fits within the bounds of a public API request
        // first copy so we can remove values
        values = new ContentValues(values);

        // check columns whose values are restricted
        enforceAllowedValues(values, Downloads.Impl.COLUMN_IS_PUBLIC_API, Boolean.TRUE);

        // validate the destination column
        if (values.getAsInteger(Downloads.Impl.COLUMN_DESTINATION) ==
                Downloads.Impl.DESTINATION_NON_DOWNLOADMANAGER_DOWNLOAD) {
            /* this row is inserted by
             * DownloadManager.addCompletedDownload(String, String, String,
             * boolean, String, String, long)
             */
            values.remove(Downloads.Impl.COLUMN_TOTAL_BYTES);
            values.remove(Downloads.Impl._DATA);
            values.remove(Downloads.Impl.COLUMN_STATUS);
        }
        enforceAllowedValues(values, Downloads.Impl.COLUMN_DESTINATION,
                Downloads.Impl.DESTINATION_CACHE_PARTITION_PURGEABLE,
                Downloads.Impl.DESTINATION_FILE_URI,
                Downloads.Impl.DESTINATION_NON_DOWNLOADMANAGER_DOWNLOAD);

        if (getContext().checkCallingOrSelfPermission(Downloads.Impl.PERMISSION_NO_NOTIFICATION)
                == PackageManager.PERMISSION_GRANTED) {
            enforceAllowedValues(values, Downloads.Impl.COLUMN_VISIBILITY,
                    Request.VISIBILITY_HIDDEN,
                    Request.VISIBILITY_VISIBLE,
                    Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
                    Request.VISIBILITY_VISIBLE_NOTIFY_ONLY_COMPLETION);
        } else {
            enforceAllowedValues(values, Downloads.Impl.COLUMN_VISIBILITY,
                    Request.VISIBILITY_VISIBLE,
                    Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
                    Request.VISIBILITY_VISIBLE_NOTIFY_ONLY_COMPLETION);
        }

        // remove the rest of the columns that are allowed (with any value)
        values.remove(Downloads.Impl.COLUMN_URI);
        values.remove(Downloads.Impl.COLUMN_TITLE);
        values.remove(Downloads.Impl.COLUMN_DESCRIPTION);
        values.remove(Downloads.Impl.COLUMN_MIME_TYPE);
        values.remove(Downloads.Impl.COLUMN_FILE_NAME_HINT); // checked later in insert()
        values.remove(Downloads.Impl.COLUMN_NOTIFICATION_PACKAGE); // checked later in insert()
        values.remove(Downloads.Impl.COLUMN_ALLOWED_NETWORK_TYPES);
        values.remove(Downloads.Impl.COLUMN_ALLOW_ROAMING);
        values.remove(Downloads.Impl.COLUMN_ALLOW_METERED);
        values.remove(Downloads.Impl.COLUMN_FLAGS);
        values.remove(Downloads.Impl.COLUMN_IS_VISIBLE_IN_DOWNLOADS_UI);
        values.remove(Downloads.Impl.COLUMN_MEDIA_SCANNED);
        values.remove(Downloads.Impl.COLUMN_ALLOW_WRITE);
        Iterator<Map.Entry<String, Object>> iterator = values.valueSet().iterator();
        while (iterator.hasNext()) {
            String key = iterator.next().getKey();
            if (key.startsWith(Downloads.Impl.RequestHeaders.INSERT_KEY_PREFIX)) {
                iterator.remove();
            }
        }

        // any extra columns are extraneous and disallowed
        if (values.size() > 0) {
            StringBuilder error = new StringBuilder("Invalid columns in request: ");
            boolean first = true;
            for (Map.Entry<String, Object> entry : values.valueSet()) {
                if (!first) {
                    error.append(", ");
                }
                error.append(entry.getKey());
            }
            throw new SecurityException(error.toString());
        }
    }

    /**
     * Remove column from values, and throw a SecurityException if the value isn't within the
     * specified allowedValues.
     */
    private void enforceAllowedValues(ContentValues values, String column,
            Object... allowedValues) {
        Object value = values.get(column);
        values.remove(column);
        for (Object allowedValue : allowedValues) {
            if (value == null && allowedValue == null) {
                return;
            }
            if (value != null && value.equals(allowedValue)) {
                return;
            }
        }
        throw new SecurityException("Invalid value for " + column + ": " + value);
    }

    private Cursor queryCleared(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sort) {
        final long token = Binder.clearCallingIdentity();
        try {
            return query(uri, projection, selection, selectionArgs, sort);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Starts a database query
     */
    @Override
    public Cursor query(final Uri uri, String[] projection,
             final String selection, final String[] selectionArgs,
             final String sort) {

        SQLiteDatabase db = mOpenHelper.getReadableDatabase();

        int match = sURIMatcher.match(uri);
        if (match == -1) {
            if (Constants.LOGV) {
                Log.v(Constants.TAG, "querying unknown URI: " + uri);
            }
            throw new IllegalArgumentException("Unknown URI: " + uri);
        }

        if (match == MY_DOWNLOADS_ID_HEADERS || match == ALL_DOWNLOADS_ID_HEADERS) {
            if (projection != null || selection != null || sort != null) {
                throw new UnsupportedOperationException("Request header queries do not support "
                                                        + "projections, selections or sorting");
            }

            // Headers are only available to callers with full access.
            getContext().enforceCallingOrSelfPermission(
                    Downloads.Impl.PERMISSION_ACCESS_ALL, Constants.TAG);

            final SQLiteQueryBuilder qb = getQueryBuilder(uri, match);
            projection = new String[] {
                    Downloads.Impl.RequestHeaders.COLUMN_HEADER,
                    Downloads.Impl.RequestHeaders.COLUMN_VALUE
            };
            return qb.query(db, projection, null, null, null, null, null);
        }

        if (Constants.LOGVV) {
            logVerboseQueryInfo(projection, selection, selectionArgs, sort, db);
        }

        final SQLiteQueryBuilder qb = getQueryBuilder(uri, match);

        final Cursor ret = qb.query(db, projection, selection, selectionArgs, null, null, sort);

        if (ret != null) {
            ret.setNotificationUri(getContext().getContentResolver(), uri);
            if (Constants.LOGVV) {
                Log.v(Constants.TAG,
                        "created cursor " + ret + " on behalf of " + Binder.getCallingPid());
            }
        } else {
            if (Constants.LOGV) {
                Log.v(Constants.TAG, "query failed in downloads database");
            }
        }

        return ret;
    }

    private void logVerboseQueryInfo(String[] projection, final String selection,
            final String[] selectionArgs, final String sort, SQLiteDatabase db) {
        java.lang.StringBuilder sb = new java.lang.StringBuilder();
        sb.append("starting query, database is ");
        if (db != null) {
            sb.append("not ");
        }
        sb.append("null; ");
        if (projection == null) {
            sb.append("projection is null; ");
        } else if (projection.length == 0) {
            sb.append("projection is empty; ");
        } else {
            for (int i = 0; i < projection.length; ++i) {
                sb.append("projection[");
                sb.append(i);
                sb.append("] is ");
                sb.append(projection[i]);
                sb.append("; ");
            }
        }
        sb.append("selection is ");
        sb.append(selection);
        sb.append("; ");
        if (selectionArgs == null) {
            sb.append("selectionArgs is null; ");
        } else if (selectionArgs.length == 0) {
            sb.append("selectionArgs is empty; ");
        } else {
            for (int i = 0; i < selectionArgs.length; ++i) {
                sb.append("selectionArgs[");
                sb.append(i);
                sb.append("] is ");
                sb.append(selectionArgs[i]);
                sb.append("; ");
            }
        }
        sb.append("sort is ");
        sb.append(sort);
        sb.append(".");
        Log.v(Constants.TAG, sb.toString());
    }

    private String getDownloadIdFromUri(final Uri uri) {
        return uri.getPathSegments().get(1);
    }

    /**
     * Insert request headers for a download into the DB.
     */
    private void insertRequestHeaders(SQLiteDatabase db, long downloadId, ContentValues values) {
        ContentValues rowValues = new ContentValues();
        rowValues.put(Downloads.Impl.RequestHeaders.COLUMN_DOWNLOAD_ID, downloadId);
        for (Map.Entry<String, Object> entry : values.valueSet()) {
            String key = entry.getKey();
            if (key.startsWith(Downloads.Impl.RequestHeaders.INSERT_KEY_PREFIX)) {
                String headerLine = entry.getValue().toString();
                if (!headerLine.contains(":")) {
                    throw new IllegalArgumentException("Invalid HTTP header line: " + headerLine);
                }
                String[] parts = headerLine.split(":", 2);
                rowValues.put(Downloads.Impl.RequestHeaders.COLUMN_HEADER, parts[0].trim());
                rowValues.put(Downloads.Impl.RequestHeaders.COLUMN_VALUE, parts[1].trim());
                db.insert(Downloads.Impl.RequestHeaders.HEADERS_DB_TABLE, null, rowValues);
            }
        }
    }

    /**
     * Updates a row in the database
     */
    @Override
    public int update(final Uri uri, final ContentValues values,
            final String where, final String[] whereArgs) {
        final Context context = getContext();
        final ContentResolver resolver = context.getContentResolver();

        final SQLiteDatabase db = mOpenHelper.getWritableDatabase();

        int count;
        boolean updateSchedule = false;
        boolean isCompleting = false;

        ContentValues filteredValues;
        if (Binder.getCallingPid() != Process.myPid()) {
            filteredValues = new ContentValues();
            copyString(Downloads.Impl.COLUMN_APP_DATA, values, filteredValues);
            copyInteger(Downloads.Impl.COLUMN_VISIBILITY, values, filteredValues);
            Integer i = values.getAsInteger(Downloads.Impl.COLUMN_CONTROL);
            if (i != null) {
                filteredValues.put(Downloads.Impl.COLUMN_CONTROL, i);
                updateSchedule = true;
            }

            copyInteger(Downloads.Impl.COLUMN_CONTROL, values, filteredValues);
            copyString(Downloads.Impl.COLUMN_TITLE, values, filteredValues);
            copyString(Downloads.Impl.COLUMN_MEDIAPROVIDER_URI, values, filteredValues);
            copyString(Downloads.Impl.COLUMN_DESCRIPTION, values, filteredValues);
            copyInteger(Downloads.Impl.COLUMN_DELETED, values, filteredValues);
        } else {
            filteredValues = values;
            String filename = values.getAsString(Downloads.Impl._DATA);
            if (filename != null) {
                try {
                    filteredValues.put(Downloads.Impl._DATA, new File(filename).getCanonicalPath());
                } catch (IOException e) {
                    throw new IllegalStateException("Invalid path: " + filename);
                }

                Cursor c = null;
                try {
                    c = query(uri, new String[]
                            { Downloads.Impl.COLUMN_TITLE }, null, null, null);
                    if (!c.moveToFirst() || c.getString(0).isEmpty()) {
                        values.put(Downloads.Impl.COLUMN_TITLE, new File(filename).getName());
                    }
                } finally {
                    IoUtils.closeQuietly(c);
                }
            }

            Integer status = values.getAsInteger(Downloads.Impl.COLUMN_STATUS);
            boolean isRestart = status != null && status == Downloads.Impl.STATUS_PENDING;
            boolean isUserBypassingSizeLimit =
                values.containsKey(Downloads.Impl.COLUMN_BYPASS_RECOMMENDED_SIZE_LIMIT);
            if (isRestart || isUserBypassingSizeLimit) {
                updateSchedule = true;
            }
            isCompleting = status != null && Downloads.Impl.isStatusCompleted(status);
        }

        int match = sURIMatcher.match(uri);
        switch (match) {
            case MY_DOWNLOADS:
            case MY_DOWNLOADS_ID:
            case ALL_DOWNLOADS:
            case ALL_DOWNLOADS_ID:
                if (filteredValues.size() == 0) {
                    count = 0;
                    break;
                }

                final SQLiteQueryBuilder qb = getQueryBuilder(uri, match);
                count = qb.update(db, filteredValues, where, whereArgs);
                final CallingIdentity token = clearCallingIdentity();
                try (Cursor cursor = qb.query(db, null, where, whereArgs, null, null, null);
                        ContentProviderClient client = getContext().getContentResolver()
                                .acquireContentProviderClient(MediaStore.AUTHORITY)) {
                    final DownloadInfo.Reader reader = new DownloadInfo.Reader(resolver,
                            cursor);
                    final DownloadInfo info = new DownloadInfo(context);
                    final ContentValues updateValues = new ContentValues();
                    while (cursor.moveToNext()) {
                        reader.updateFromDatabase(info);
                        final boolean visibleToUser = info.mIsVisibleInDownloadsUi
                                || (info.mMediaScanned != MEDIA_NOT_SCANNABLE);
                        if (info.mFileName == null) {
                            if (info.mMediaStoreUri != null) {
                                // If there was a mediastore entry, it would be deleted in it's
                                // next idle pass.
                                updateValues.clear();
                                updateValues.putNull(Downloads.Impl.COLUMN_MEDIASTORE_URI);
                                qb.update(db, updateValues, Downloads.Impl._ID + "=?",
                                        new String[] { Long.toString(info.mId) });
                            }
                        } else if ((info.mDestination == Downloads.Impl.DESTINATION_EXTERNAL
                                || info.mDestination == Downloads.Impl.DESTINATION_FILE_URI
                                || info.mDestination == Downloads.Impl
                                        .DESTINATION_NON_DOWNLOADMANAGER_DOWNLOAD)
                                && visibleToUser) {
                            final ContentValues mediaValues = convertToMediaProviderValues(info);
                            final Uri mediaStoreUri;
                            if (Downloads.Impl.isStatusCompleted(info.mStatus)) {
                                // Set size to 0 to ensure MediaScanner will scan this file.
                                mediaValues.put(MediaStore.Downloads.SIZE, 0);
                                updateMediaProvider(client, mediaValues);
                                mediaStoreUri = triggerMediaScan(client, new File(info.mFileName));
                            } else {
                                mediaStoreUri = updateMediaProvider(client, mediaValues);
                            }
                            if (!TextUtils.equals(info.mMediaStoreUri,
                                    mediaStoreUri == null ? null : mediaStoreUri.toString())) {
                                updateValues.clear();
                                if (mediaStoreUri == null) {
                                    updateValues.putNull(Downloads.Impl.COLUMN_MEDIASTORE_URI);
                                    updateValues.putNull(Downloads.Impl.COLUMN_MEDIAPROVIDER_URI);
                                    updateValues.put(COLUMN_MEDIA_SCANNED, MEDIA_NOT_SCANNED);
                                } else {
                                    updateValues.put(Downloads.Impl.COLUMN_MEDIASTORE_URI,
                                            mediaStoreUri.toString());
                                    updateValues.put(Downloads.Impl.COLUMN_MEDIAPROVIDER_URI,
                                            mediaStoreUri.toString());
                                    updateValues.put(COLUMN_MEDIA_SCANNED, MEDIA_SCANNED);
                                }
                                qb.update(db, updateValues, Downloads.Impl._ID + "=?",
                                        new String[] { Long.toString(info.mId) });
                            }
                        }
                        if (updateSchedule) {
                            Helpers.scheduleJob(context, info);
                        }
                        if (isCompleting) {
                            info.sendIntentIfRequested();
                        }
                    }
                } finally {
                    restoreCallingIdentity(token);
                }
                break;

            default:
                Log.d(Constants.TAG, "updating unknown/invalid URI: " + uri);
                throw new UnsupportedOperationException("Cannot update URI: " + uri);
        }

        notifyContentChanged(uri, match);
        return count;
    }

    /**
     * Notify of a change through both URIs (/my_downloads and /all_downloads)
     * @param uri either URI for the changed download(s)
     * @param uriMatch the match ID from {@link #sURIMatcher}
     */
    private void notifyContentChanged(final Uri uri, int uriMatch) {
        Long downloadId = null;
        if (uriMatch == MY_DOWNLOADS_ID || uriMatch == ALL_DOWNLOADS_ID) {
            downloadId = Long.parseLong(getDownloadIdFromUri(uri));
        }
        for (Uri uriToNotify : BASE_URIS) {
            if (downloadId != null) {
                uriToNotify = ContentUris.withAppendedId(uriToNotify, downloadId);
            }
            getContext().getContentResolver().notifyChange(uriToNotify, null);
        }
    }

    /**
     * Create a query builder that filters access to the underlying database
     * based on both the requested {@link Uri} and permissions of the caller.
     */
    private SQLiteQueryBuilder getQueryBuilder(final Uri uri, int match) {
        final String table;
        final Map<String, String> projectionMap;

        final StringBuilder where = new StringBuilder();
        switch (match) {
            // The "my_downloads" view normally limits the caller to operating
            // on downloads that they either directly own, or have been given
            // indirect ownership of via OTHER_UID.
            case MY_DOWNLOADS_ID:
                appendWhereExpression(where, _ID + "=" + getDownloadIdFromUri(uri));
                // fall-through
            case MY_DOWNLOADS:
                table = DB_TABLE;
                projectionMap = sDownloadsMap;
                if (getContext().checkCallingOrSelfPermission(
                        PERMISSION_ACCESS_ALL) != PackageManager.PERMISSION_GRANTED) {
                    appendWhereExpression(where, Constants.UID + "=" + Binder.getCallingUid()
                            + " OR " + COLUMN_OTHER_UID + "=" + Binder.getCallingUid());
                }
                break;

            // The "all_downloads" view is already limited via <path-permission>
            // to only callers holding the ACCESS_ALL_DOWNLOADS permission, but
            // access may also be delegated via Uri permission grants.
            case ALL_DOWNLOADS_ID:
                appendWhereExpression(where, _ID + "=" + getDownloadIdFromUri(uri));
                // fall-through
            case ALL_DOWNLOADS:
                table = DB_TABLE;
                projectionMap = sDownloadsMap;
                break;

            // Headers are limited to callers holding the ACCESS_ALL_DOWNLOADS
            // permission, since they're only needed for executing downloads.
            case MY_DOWNLOADS_ID_HEADERS:
            case ALL_DOWNLOADS_ID_HEADERS:
                table = Downloads.Impl.RequestHeaders.HEADERS_DB_TABLE;
                projectionMap = sHeadersMap;
                appendWhereExpression(where, Downloads.Impl.RequestHeaders.COLUMN_DOWNLOAD_ID + "="
                        + getDownloadIdFromUri(uri));
                break;

            default:
                throw new UnsupportedOperationException("Unknown URI: " + uri);
        }

        final SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setTables(table);
        qb.setProjectionMap(projectionMap);
        qb.setStrict(true);
        qb.setStrictColumns(true);
        qb.setStrictGrammar(true);
        qb.appendWhere(where);
        return qb;
    }

    private static void appendWhereExpression(StringBuilder sb, String expression) {
        if (sb.length() > 0) {
            sb.append(" AND ");
        }
        sb.append('(').append(expression).append(')');
    }

    /**
     * Deletes a row in the database
     */
    @Override
    public int delete(final Uri uri, final String where, final String[] whereArgs) {
        final Context context = getContext();
        final ContentResolver resolver = context.getContentResolver();
        final JobScheduler scheduler = context.getSystemService(JobScheduler.class);

        final SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        int count;
        int match = sURIMatcher.match(uri);
        switch (match) {
            case MY_DOWNLOADS:
            case MY_DOWNLOADS_ID:
            case ALL_DOWNLOADS:
            case ALL_DOWNLOADS_ID:
                final SQLiteQueryBuilder qb = getQueryBuilder(uri, match);
                try (Cursor cursor = qb.query(db, null, where, whereArgs, null, null, null)) {
                    final DownloadInfo.Reader reader = new DownloadInfo.Reader(resolver, cursor);
                    final DownloadInfo info = new DownloadInfo(context);
                    while (cursor.moveToNext()) {
                        reader.updateFromDatabase(info);
                        scheduler.cancel((int) info.mId);

                        revokeAllDownloadsPermission(info.mId);
                        DownloadStorageProvider.onDownloadProviderDelete(getContext(), info.mId);

                        final String path = info.mFileName;
                        if (!TextUtils.isEmpty(path)) {
                            try {
                                final File file = new File(path).getCanonicalFile();
                                if (Helpers.isFilenameValid(getContext(), file)) {
                                    Log.v(Constants.TAG,
                                            "Deleting " + file + " via provider delete");
                                    file.delete();
                                    MediaStore.scanFile(getContext().getContentResolver(), file);
                                } else {
                                    Log.d(Constants.TAG, "Ignoring invalid file: " + file);
                                }
                            } catch (IOException e) {
                                Log.e(Constants.TAG, "Couldn't delete file: " + path, e);
                            }
                        }

                        // If the download wasn't completed yet, we're
                        // effectively completing it now, and we need to send
                        // any requested broadcasts
                        if (!Downloads.Impl.isStatusCompleted(info.mStatus)) {
                            info.sendIntentIfRequested();
                        }

                        // Delete any headers for this download
                        db.delete(Downloads.Impl.RequestHeaders.HEADERS_DB_TABLE,
                                Downloads.Impl.RequestHeaders.COLUMN_DOWNLOAD_ID + "=?",
                                new String[] { Long.toString(info.mId) });
                    }
                }

                count = qb.delete(db, where, whereArgs);
                break;

            default:
                Log.d(Constants.TAG, "deleting unknown/invalid URI: " + uri);
                throw new UnsupportedOperationException("Cannot delete URI: " + uri);
        }
        notifyContentChanged(uri, match);
        final long token = Binder.clearCallingIdentity();
        try {
            Helpers.getDownloadNotifier(getContext()).update();
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        return count;
    }

    /**
     * Remotely opens a file
     */
    @Override
    public ParcelFileDescriptor openFile(final Uri uri, String mode) throws FileNotFoundException {
        if (Constants.LOGVV) {
            logVerboseOpenFileInfo(uri, mode);
        }

        // Perform normal query to enforce caller identity access before
        // clearing it to reach internal-only columns
        final Cursor probeCursor = query(uri, new String[] {
                Downloads.Impl._DATA }, null, null, null);
        try {
            if ((probeCursor == null) || (probeCursor.getCount() == 0)) {
                throw new FileNotFoundException(
                        "No file found for " + uri + " as UID " + Binder.getCallingUid());
            }
        } finally {
            IoUtils.closeQuietly(probeCursor);
        }

        final Cursor cursor = queryCleared(uri, new String[] {
                Downloads.Impl._DATA, Downloads.Impl.COLUMN_STATUS,
                Downloads.Impl.COLUMN_DESTINATION, Downloads.Impl.COLUMN_MEDIA_SCANNED }, null,
                null, null);
        final String path;
        final boolean shouldScan;
        try {
            int count = (cursor != null) ? cursor.getCount() : 0;
            if (count != 1) {
                // If there is not exactly one result, throw an appropriate exception.
                if (count == 0) {
                    throw new FileNotFoundException("No entry for " + uri);
                }
                throw new FileNotFoundException("Multiple items at " + uri);
            }

            if (cursor.moveToFirst()) {
                final int status = cursor.getInt(1);
                final int destination = cursor.getInt(2);
                final int mediaScanned = cursor.getInt(3);

                path = cursor.getString(0);
                shouldScan = Downloads.Impl.isStatusSuccess(status) && (
                        destination == Downloads.Impl.DESTINATION_EXTERNAL
                        || destination == Downloads.Impl.DESTINATION_FILE_URI
                        || destination == Downloads.Impl.DESTINATION_NON_DOWNLOADMANAGER_DOWNLOAD)
                        && mediaScanned != Downloads.Impl.MEDIA_NOT_SCANNABLE;
            } else {
                throw new FileNotFoundException("Failed moveToFirst");
            }
        } finally {
            IoUtils.closeQuietly(cursor);
        }

        if (path == null) {
            throw new FileNotFoundException("No filename found.");
        }

        final File file;
        try {
            file = new File(path).getCanonicalFile();
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }

        if (!Helpers.isFilenameValid(getContext(), file)) {
            throw new FileNotFoundException("Invalid file: " + file);
        }

        final int pfdMode = ParcelFileDescriptor.parseMode(mode);
        if (pfdMode == ParcelFileDescriptor.MODE_READ_ONLY) {
            return ParcelFileDescriptor.open(file, pfdMode);
        } else {
            try {
                // When finished writing, update size and timestamp
                return ParcelFileDescriptor.open(file, pfdMode, Helpers.getAsyncHandler(),
                        new OnCloseListener() {
                    @Override
                    public void onClose(IOException e) {
                        final ContentValues values = new ContentValues();
                        values.put(Downloads.Impl.COLUMN_TOTAL_BYTES, file.length());
                        values.put(Downloads.Impl.COLUMN_LAST_MODIFICATION,
                                System.currentTimeMillis());
                        update(uri, values, null, null);

                        if (shouldScan) {
                            final Intent intent = new Intent(
                                    Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
                            intent.setData(Uri.fromFile(file));
                            getContext().sendBroadcast(intent);
                        }
                    }
                });
            } catch (IOException e) {
                throw new FileNotFoundException("Failed to open for writing: " + e);
            }
        }
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        final IndentingPrintWriter pw = new IndentingPrintWriter(writer, "  ", 120);

        pw.println("Downloads updated in last hour:");
        pw.increaseIndent();

        final SQLiteDatabase db = mOpenHelper.getReadableDatabase();
        final long modifiedAfter = mSystemFacade.currentTimeMillis() - DateUtils.HOUR_IN_MILLIS;
        final Cursor cursor = db.query(DB_TABLE, null,
                Downloads.Impl.COLUMN_LAST_MODIFICATION + ">" + modifiedAfter, null, null, null,
                Downloads.Impl._ID + " ASC");
        try {
            final String[] cols = cursor.getColumnNames();
            final int idCol = cursor.getColumnIndex(BaseColumns._ID);
            while (cursor.moveToNext()) {
                pw.println("Download #" + cursor.getInt(idCol) + ":");
                pw.increaseIndent();
                for (int i = 0; i < cols.length; i++) {
                    // Omit sensitive data when dumping
                    if (Downloads.Impl.COLUMN_COOKIE_DATA.equals(cols[i])) {
                        continue;
                    }
                    pw.printPair(cols[i], cursor.getString(i));
                }
                pw.println();
                pw.decreaseIndent();
            }
        } finally {
            cursor.close();
        }

        pw.decreaseIndent();
    }

    private void logVerboseOpenFileInfo(Uri uri, String mode) {
        Log.v(Constants.TAG, "openFile uri: " + uri + ", mode: " + mode
                + ", uid: " + Binder.getCallingUid());
        Cursor cursor = query(Downloads.Impl.CONTENT_URI,
                new String[] { "_id" }, null, null, "_id");
        if (cursor == null) {
            Log.v(Constants.TAG, "null cursor in openFile");
        } else {
            try {
                if (!cursor.moveToFirst()) {
                    Log.v(Constants.TAG, "empty cursor in openFile");
                } else {
                    do {
                        Log.v(Constants.TAG, "row " + cursor.getInt(0) + " available");
                    } while(cursor.moveToNext());
                }
            } finally {
                cursor.close();
            }
        }
        cursor = query(uri, new String[] { "_data" }, null, null, null);
        if (cursor == null) {
            Log.v(Constants.TAG, "null cursor in openFile");
        } else {
            try {
                if (!cursor.moveToFirst()) {
                    Log.v(Constants.TAG, "empty cursor in openFile");
                } else {
                    String filename = cursor.getString(0);
                    Log.v(Constants.TAG, "filename in openFile: " + filename);
                    if (new java.io.File(filename).isFile()) {
                        Log.v(Constants.TAG, "file exists in openFile");
                    }
                }
            } finally {
                cursor.close();
            }
        }
    }

    private static final void copyInteger(String key, ContentValues from, ContentValues to) {
        Integer i = from.getAsInteger(key);
        if (i != null) {
            to.put(key, i);
        }
    }

    private static final void copyBoolean(String key, ContentValues from, ContentValues to) {
        Boolean b = from.getAsBoolean(key);
        if (b != null) {
            to.put(key, b);
        }
    }

    private static final void copyString(String key, ContentValues from, ContentValues to) {
        String s = from.getAsString(key);
        if (s != null) {
            to.put(key, s);
        }
    }

    private static final void copyStringWithDefault(String key, ContentValues from,
            ContentValues to, String defaultValue) {
        copyString(key, from, to);
        if (!to.containsKey(key)) {
            to.put(key, defaultValue);
        }
    }

    private void grantAllDownloadsPermission(String toPackage, long id) {
        final Uri uri = ContentUris.withAppendedId(Downloads.Impl.ALL_DOWNLOADS_CONTENT_URI, id);
        getContext().grantUriPermission(toPackage, uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    }

    private void revokeAllDownloadsPermission(long id) {
        final Uri uri = ContentUris.withAppendedId(Downloads.Impl.ALL_DOWNLOADS_CONTENT_URI, id);
        getContext().revokeUriPermission(uri, ~0);
    }
}
