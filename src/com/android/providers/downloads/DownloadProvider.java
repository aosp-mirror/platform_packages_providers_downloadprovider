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

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.UriMatcher;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteQueryBuilder;
import android.database.SQLException;
import android.net.Uri;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.provider.BaseColumns;
import android.provider.Downloads;
import android.util.Config;
import android.util.Log;

import java.io.FileNotFoundException;

/**
 * Allows application to interact with the download manager.
 */
public final class DownloadProvider extends ContentProvider {

    /** Tag used in logging */
    private static final String TAG = Constants.TAG;

    /** Database filename */
    private static final String DB_NAME = "downloads.db";
    /** Current database vesion */
    private static final int DB_VERSION = 31;
    /** Name of table in the database */
    private static final String DB_TABLE = "downloads";

    /** MIME type for the entire download list */
    private static final String DOWNLOAD_LIST_TYPE = "vnd.android.cursor.dir/download";
    /** MIME type for an individual download */
    private static final String DOWNLOAD_TYPE = "vnd.android.cursor.item/download";

    /** URI matcher used to recognize URIs sent by applications */
    private static final UriMatcher sURIMatcher = new UriMatcher(UriMatcher.NO_MATCH);
    /** URI matcher constant for the URI of the entire download list */
    private static final int DOWNLOADS = 1;
    /** URI matcher constant for the URI of an individual download */
    private static final int DOWNLOADS_ID = 2;
    static {
        sURIMatcher.addURI("downloads", "download", DOWNLOADS);
        sURIMatcher.addURI("downloads", "download/#", DOWNLOADS_ID);
    }

    /** The database that lies underneath this content provider */
    private SQLiteOpenHelper mOpenHelper = null;

    /**
     * Creates and updated database on demand when opening it.
     * Helper class to create database the first time the provider is
     * initialized and upgrade it when a new version of the provider needs
     * an updated version of the database.
     */
    private final class DatabaseHelper extends SQLiteOpenHelper {

        public DatabaseHelper(final Context context) {
            super(context, DB_NAME, null, DB_VERSION);
        }

        /**
         * Creates database the first time we try to open it.
         */
        @Override
        public void onCreate(final SQLiteDatabase db) {
            if (Constants.LOGVV) {
                Log.v(Constants.TAG, "populating new database");
            }
            createTable(db);
        }

        /* (not a javadoc comment)
         * Checks data integrity when opening the database.
         */
        /*
         * @Override
         * public void onOpen(final SQLiteDatabase db) {
         *     super.onOpen(db);
         * }
         */

        /**
         * Updates the database format when a content provider is used
         * with a database that was created with a different format.
         */
        // Note: technically, this could also be a downgrade, so if we want
        //       to gracefully handle upgrades we should be careful about
        //       what to do on downgrades.
        @Override
        public void onUpgrade(final SQLiteDatabase db, final int oldV, final int newV) {
            Log.i(TAG, "Upgrading downloads database from version " + oldV + " to " + newV
                    + ", which will destroy all old data");
            dropTable(db);
            createTable(db);
        }
    }

    /**
     * Initializes the content provider when it is created.
     */
    @Override
    public boolean onCreate() {
        mOpenHelper = new DatabaseHelper(getContext());
        return true;
    }

    /**
     * Returns the content-provider-style MIME types of the various
     * types accessible through this content provider.
     */
    @Override
    public String getType(final Uri uri) {
        int match = sURIMatcher.match(uri);
        switch (match) {
            case DOWNLOADS: {
                return DOWNLOAD_LIST_TYPE;
            }
            case DOWNLOADS_ID: {
                return DOWNLOAD_TYPE;
            }
            default: {
                if (Constants.LOGV) {
                    Log.v(Constants.TAG, "calling getType on an unknown URI: " + uri);
                }
                throw new IllegalArgumentException("Unknown URI: " + uri);
            }
        }
    }

    /**
     * Creates the table that'll hold the download information.
     */
    private void createTable(SQLiteDatabase db) {
        try {
            db.execSQL("CREATE TABLE " + DB_TABLE + "(" +
                    BaseColumns._ID + " INTEGER PRIMARY KEY AUTOINCREMENT," +
                    Downloads.URI + " TEXT, " +
                    Downloads.METHOD + " INTEGER, " +
                    Downloads.ENTITY + " TEXT, " +
                    Downloads.NO_INTEGRITY + " BOOLEAN, " +
                    Downloads.FILENAME_HINT + " TEXT, " +
                    Downloads.OTA_UPDATE + " BOOLEAN, " +
                    Downloads.FILENAME + " TEXT, " +
                    Downloads.MIMETYPE + " TEXT, " +
                    Downloads.DESTINATION + " INTEGER, " +
                    Downloads.NO_SYSTEM_FILES + " BOOLEAN, " +
                    Downloads.VISIBILITY + " INTEGER, " +
                    Downloads.CONTROL + " INTEGER, " +
                    Downloads.STATUS + " INTEGER, " +
                    Downloads.FAILED_CONNECTIONS + " INTEGER, " +
                    Downloads.LAST_MODIFICATION + " BIGINT, " +
                    Downloads.NOTIFICATION_PACKAGE + " TEXT, " +
                    Downloads.NOTIFICATION_CLASS + " TEXT, " +
                    Downloads.NOTIFICATION_EXTRAS + " TEXT, " +
                    Downloads.COOKIE_DATA + " TEXT, " +
                    Downloads.USER_AGENT + " TEXT, " +
                    Downloads.REFERER + " TEXT, " +
                    Downloads.TOTAL_BYTES + " INTEGER, " +
                    Downloads.CURRENT_BYTES + " INTEGER, " +
                    Downloads.ETAG + " TEXT, " +
                    Downloads.UID + " INTEGER, " +
                    Downloads.OTHER_UID + " INTEGER, " +
                    Downloads.TITLE + " TEXT, " +
                    Downloads.DESCRIPTION + " TEXT, " +
                    Downloads.MEDIA_SCANNED + " BOOLEAN);");
        } catch (SQLException ex) {
            Log.e(Constants.TAG, "couldn't create table in downloads database");
            throw ex;
        }
    }

    /**
     * Deletes the table that holds the download information.
     */
    private void dropTable(SQLiteDatabase db) {
        try {
            db.execSQL("DROP TABLE IF EXISTS " + DB_TABLE);
        } catch (SQLException ex) {
            Log.e(Constants.TAG, "couldn't drop table in downloads database");
            throw ex;
        }
    }

    /**
     * Inserts a row in the database
     */
    @Override
    public Uri insert(final Uri uri, final ContentValues values) {
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();

        if (sURIMatcher.match(uri) != DOWNLOADS) {
            if (Config.LOGD) {
                Log.d(Constants.TAG, "calling insert on an unknown/invalid URI: " + uri);
            }
            throw new IllegalArgumentException("Unknown/Invalid URI " + uri);
        }

        boolean hasUID = values.containsKey(Downloads.UID);
        if (hasUID && Binder.getCallingUid() != 0) {
            values.remove(Downloads.UID);
            hasUID = false;
        }
        if (!hasUID) {
            values.put(Downloads.UID, Binder.getCallingUid());
        }
        if (Constants.LOGVV) {
            Log.v(TAG, "initiating download with UID " + Binder.getCallingUid());
            if (values.containsKey(Downloads.OTHER_UID)) {
                Log.v(TAG, "other UID " + values.getAsInteger(Downloads.OTHER_UID));
            }
        }

        if (values.containsKey(Downloads.LAST_MODIFICATION)) {
            values.remove(Downloads.LAST_MODIFICATION);
        }
        values.put(Downloads.LAST_MODIFICATION, System.currentTimeMillis());

        if (values.containsKey(Downloads.STATUS)) {
            values.remove(Downloads.STATUS);
        }
        values.put(Downloads.STATUS, Downloads.STATUS_PENDING);

        if (values.containsKey(Downloads.OTA_UPDATE)
                && getContext().checkCallingPermission(Constants.OTA_UPDATE_PERMISSION)
                        != PackageManager.PERMISSION_GRANTED) {
            values.remove(Downloads.OTA_UPDATE);
        }

        Context context = getContext();
        context.startService(new Intent(context, DownloadService.class));

        long rowID = db.insert(DB_TABLE, null, values);

        Uri ret = null;

        if (rowID != -1) {
            context.startService(new Intent(context, DownloadService.class));
            ret = Uri.parse(Downloads.CONTENT_URI + "/" + rowID);
            context.getContentResolver().notifyChange(uri, null);
        } else {
            if (Config.LOGD) {
                Log.d(TAG, "couldn't insert into downloads database");
            }
        }

        return ret;
    }

    /**
     * Starts a database query
     */
    @Override
    public Cursor query(final Uri uri, final String[] projection,
             final String selection, final String[] selectionArgs,
             final String sort) {
        SQLiteDatabase db = mOpenHelper.getReadableDatabase();

        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();

        int match = sURIMatcher.match(uri);
        boolean emptyWhere = true;
        switch (match) {
            case DOWNLOADS: {
                qb.setTables(DB_TABLE);
                break;
            }
            case DOWNLOADS_ID: {
                qb.setTables(DB_TABLE);
                qb.appendWhere(BaseColumns._ID + "=");
                qb.appendWhere(uri.getPathSegments().get(1));
                emptyWhere = false;
                break;
            }
            default: {
                if (Constants.LOGV) {
                    Log.v(TAG, "querying unknown URI: " + uri);
                }
                throw new IllegalArgumentException("Unknown URI: " + uri);
            }
        }

        if (Binder.getCallingPid() != Process.myPid()
                && Binder.getCallingUid() != 0
                && getContext().checkCallingPermission(Constants.UI_PERMISSION)
                        != PackageManager.PERMISSION_GRANTED) {
            if (!emptyWhere) {
                qb.appendWhere(" AND ");
            }
            qb.appendWhere("( " + Downloads.UID + "=" +  Binder.getCallingUid() + " OR "
                    + Downloads.OTHER_UID + "=" +  Binder.getCallingUid() + " )");
            emptyWhere = false;
        }

        if (Constants.LOGVV) {
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
            Log.v(TAG, sb.toString());
        }

        Cursor ret = qb.query(db, projection, selection, selectionArgs,
                              null, null, sort);

        if (ret != null) {
            ret.setNotificationUri(getContext().getContentResolver(), uri);
            if (Constants.LOGVV) {
                Log.v(Constants.TAG,
                        "created cursor " + ret + " on behalf of " + Binder.getCallingPid());
            }
        } else {
            if (Constants.LOGV) {
                Log.v(TAG, "query failed in downloads database");
            }
        }

        return ret;
    }

    /**
     * Updates a row in the database
     */
    @Override
    public int update(final Uri uri, final ContentValues values,
            final String where, final String[] whereArgs) {
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();

        int count;
        long rowId = 0;
        if (values.containsKey(Downloads.UID)) {
            values.remove(Downloads.UID);
        }
        int match = sURIMatcher.match(uri);
        switch (match) {
            case DOWNLOADS:
            case DOWNLOADS_ID: {
                String myWhere;
                if (where != null) {
                    if (match == DOWNLOADS) {
                        myWhere = where;
                    } else {
                        myWhere = where + " AND ";
                    }
                } else {
                    myWhere = "";
                }
                if (match == DOWNLOADS_ID) {
                    String segment = uri.getPathSegments().get(1);
                    rowId = Long.parseLong(segment);
                    myWhere += Downloads._ID + " = " + rowId;
                }
                if (Binder.getCallingPid() != Process.myPid()
                        && Binder.getCallingUid() != 0
                        && getContext().checkCallingPermission(Constants.UI_PERMISSION)
                                != PackageManager.PERMISSION_GRANTED) {
                    myWhere += " AND ( " + Downloads.UID + "=" +  Binder.getCallingUid() + " OR "
                            + Downloads.OTHER_UID + "=" +  Binder.getCallingUid() + " )";
                }
                count = db.update(DB_TABLE, values, myWhere, whereArgs);
                break;
            }
            default: {
                if (Config.LOGD) {
                    Log.d(TAG, "updating unknown/invalid URI: " + uri);
                }
                throw new UnsupportedOperationException("Cannot update URI: " + uri);
            }
        }
        getContext().getContentResolver().notifyChange(uri, null);
        return count;
    }

    /**
     * Deletes a row in the database
     */
    @Override
    public int delete(final Uri uri, final String where,
            final String[] whereArgs) {
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        int count;
        int match = sURIMatcher.match(uri);
        switch (match) {
            case DOWNLOADS:
            case DOWNLOADS_ID: {
                String myWhere;
                if (where != null) {
                    if (match == DOWNLOADS) {
                        myWhere = where;
                    } else {
                        myWhere = where + " AND ";
                    }
                } else {
                    myWhere = "";
                }
                if (match == DOWNLOADS_ID) {
                    String segment = uri.getPathSegments().get(1);
                    long rowId = Long.parseLong(segment);
                    myWhere += Downloads._ID + " = " + rowId;
                }
                if (Binder.getCallingPid() != Process.myPid()
                        && Binder.getCallingUid() != 0
                        && getContext().checkCallingPermission(Constants.UI_PERMISSION)
                                != PackageManager.PERMISSION_GRANTED) {
                    myWhere += " AND ( " + Downloads.UID + "=" +  Binder.getCallingUid() + " OR "
                            + Downloads.OTHER_UID + "=" +  Binder.getCallingUid() + " )";
                }
                count = db.delete(DB_TABLE, myWhere, whereArgs);
                break;
            }
            default: {
                if (Config.LOGD) {
                    Log.d(TAG, "deleting unknown/invalid URI: " + uri);
                }
                throw new UnsupportedOperationException("Cannot delete URI: " + uri);
            }
        }
        getContext().getContentResolver().notifyChange(uri, null);
        return count;
    }

    /**
     * Remotely opens a file
     */
    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode)
            throws FileNotFoundException {
        if (Constants.LOGVV) {
            Log.v(TAG, "openFile uri: " + uri + ", mode: " + mode
                    + ", uid: " + Binder.getCallingUid());
            Cursor cursor = query(Downloads.CONTENT_URI, new String[] { "_id" }, null, null, "_id");
            if (cursor == null) {
                Log.v(TAG, "null cursor in openFile");
            } else {
                if (!cursor.moveToFirst()) {
                    Log.v(TAG, "empty cursor in openFile");
                } else {
                    do {
                        Log.v(TAG, "row " + cursor.getInt(0) + " available");
                    } while(cursor.moveToNext());
                }
                cursor.close();
            }
            cursor = query(uri, new String[] { "_data" }, null, null, null);
            if (cursor == null) {
                Log.v(TAG, "null cursor in openFile");
            } else {
                if (!cursor.moveToFirst()) {
                    Log.v(TAG, "empty cursor in openFile");
                } else {
                    String filename = cursor.getString(0);
                    Log.v(TAG, "filename in openFile: " + filename);
                    if (new java.io.File(filename).isFile()) {
                        Log.v(TAG, "file exists in openFile");
                    }
                }
               cursor.close();
            }
        }
        ParcelFileDescriptor ret = openFileHelper(uri, mode);
        if (ret == null) {
            if (Config.LOGD) {
                Log.d(TAG, "couldn't open file");
            }
        } else {
            ContentValues values = new ContentValues();
            values.put(Downloads.LAST_MODIFICATION, System.currentTimeMillis());
            update(uri, values, null, null);
        }
        return ret;
    }

}
