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
import android.database.CrossProcessCursor;
import android.database.Cursor;
import android.database.CursorWindow;
import android.database.CursorWrapper;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.provider.Downloads;
import android.util.Config;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.HashSet;


/**
 * Allows application to interact with the download manager.
 */
public final class DownloadProvider extends ContentProvider {

    /** Database filename */
    private static final String DB_NAME = "downloads.db";
    /** Current database version */
    private static final int DB_VERSION = 100;
    /** Database version from which upgrading is a nop */
    private static final int DB_VERSION_NOP_UPGRADE_FROM = 31;
    /** Database version to which upgrading is a nop */
    private static final int DB_VERSION_NOP_UPGRADE_TO = 100;
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

    private static final String[] sAppReadableColumnsArray = new String[] {
        Downloads._ID,
        Downloads.COLUMN_APP_DATA,
        Downloads._DATA,
        Downloads.COLUMN_MIME_TYPE,
        Downloads.COLUMN_VISIBILITY,
        Downloads.COLUMN_DESTINATION,
        Downloads.COLUMN_CONTROL,
        Downloads.COLUMN_STATUS,
        Downloads.COLUMN_LAST_MODIFICATION,
        Downloads.COLUMN_NOTIFICATION_PACKAGE,
        Downloads.COLUMN_NOTIFICATION_CLASS,
        Downloads.COLUMN_TOTAL_BYTES,
        Downloads.COLUMN_CURRENT_BYTES,
        Downloads.COLUMN_TITLE,
        Downloads.COLUMN_DESCRIPTION
    };

    private static HashSet<String> sAppReadableColumnsSet;
    static {
        sAppReadableColumnsSet = new HashSet<String>();
        for (int i = 0; i < sAppReadableColumnsArray.length; ++i) {
            sAppReadableColumnsSet.add(sAppReadableColumnsArray[i]);
        }
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
        public void onUpgrade(final SQLiteDatabase db, int oldV, final int newV) {
            if (oldV == DB_VERSION_NOP_UPGRADE_FROM) {
                if (newV == DB_VERSION_NOP_UPGRADE_TO) { // that's a no-op upgrade.
                    return;
                }
                // NOP_FROM and NOP_TO are identical, just in different codelines. Upgrading
                //     from NOP_FROM is the same as upgrading from NOP_TO.
                oldV = DB_VERSION_NOP_UPGRADE_TO;
            }
            Log.i(Constants.TAG, "Upgrading downloads database from version " + oldV + " to " + newV
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
                    Downloads._ID + " INTEGER PRIMARY KEY AUTOINCREMENT," +
                    Downloads.COLUMN_URI + " TEXT, " +
                    Constants.RETRY_AFTER_X_REDIRECT_COUNT + " INTEGER, " +
                    Downloads.COLUMN_APP_DATA + " TEXT, " +
                    Downloads.COLUMN_NO_INTEGRITY + " BOOLEAN, " +
                    Downloads.COLUMN_FILE_NAME_HINT + " TEXT, " +
                    Constants.OTA_UPDATE + " BOOLEAN, " +
                    Downloads._DATA + " TEXT, " +
                    Downloads.COLUMN_MIME_TYPE + " TEXT, " +
                    Downloads.COLUMN_DESTINATION + " INTEGER, " +
                    Constants.NO_SYSTEM_FILES + " BOOLEAN, " +
                    Downloads.COLUMN_VISIBILITY + " INTEGER, " +
                    Downloads.COLUMN_CONTROL + " INTEGER, " +
                    Downloads.COLUMN_STATUS + " INTEGER, " +
                    Constants.FAILED_CONNECTIONS + " INTEGER, " +
                    Downloads.COLUMN_LAST_MODIFICATION + " BIGINT, " +
                    Downloads.COLUMN_NOTIFICATION_PACKAGE + " TEXT, " +
                    Downloads.COLUMN_NOTIFICATION_CLASS + " TEXT, " +
                    Downloads.COLUMN_NOTIFICATION_EXTRAS + " TEXT, " +
                    Downloads.COLUMN_COOKIE_DATA + " TEXT, " +
                    Downloads.COLUMN_USER_AGENT + " TEXT, " +
                    Downloads.COLUMN_REFERER + " TEXT, " +
                    Downloads.COLUMN_TOTAL_BYTES + " INTEGER, " +
                    Downloads.COLUMN_CURRENT_BYTES + " INTEGER, " +
                    Constants.ETAG + " TEXT, " +
                    Constants.UID + " INTEGER, " +
                    Downloads.COLUMN_OTHER_UID + " INTEGER, " +
                    Downloads.COLUMN_TITLE + " TEXT, " +
                    Downloads.COLUMN_DESCRIPTION + " TEXT, " +
                    Constants.MEDIA_SCANNED + " BOOLEAN);");
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

        ContentValues filteredValues = new ContentValues();

        copyString(Downloads.COLUMN_URI, values, filteredValues);
        copyString(Downloads.COLUMN_APP_DATA, values, filteredValues);
        copyBoolean(Downloads.COLUMN_NO_INTEGRITY, values, filteredValues);
        copyString(Downloads.COLUMN_FILE_NAME_HINT, values, filteredValues);
        copyString(Downloads.COLUMN_MIME_TYPE, values, filteredValues);
        Integer dest = values.getAsInteger(Downloads.COLUMN_DESTINATION);
        if (dest != null) {
            if (getContext().checkCallingPermission(Downloads.PERMISSION_ACCESS_ADVANCED)
                    != PackageManager.PERMISSION_GRANTED
                    && dest != Downloads.DESTINATION_EXTERNAL
                    && dest != Downloads.DESTINATION_CACHE_PARTITION_PURGEABLE) {
                throw new SecurityException("unauthorized destination code");
            }
            filteredValues.put(Downloads.COLUMN_DESTINATION, dest);
        }
        Integer vis = values.getAsInteger(Downloads.COLUMN_VISIBILITY);
        if (vis == null) {
            if (dest == Downloads.DESTINATION_EXTERNAL) {
                filteredValues.put(Downloads.COLUMN_VISIBILITY,
                        Downloads.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            } else {
                filteredValues.put(Downloads.COLUMN_VISIBILITY, Downloads.VISIBILITY_HIDDEN);
            }
        } else {
            filteredValues.put(Downloads.COLUMN_VISIBILITY, vis);
        }
        copyInteger(Downloads.COLUMN_CONTROL, values, filteredValues);
        filteredValues.put(Downloads.COLUMN_STATUS, Downloads.STATUS_PENDING);
        filteredValues.put(Downloads.COLUMN_LAST_MODIFICATION, System.currentTimeMillis());
        String pckg = values.getAsString(Downloads.COLUMN_NOTIFICATION_PACKAGE);
        String clazz = values.getAsString(Downloads.COLUMN_NOTIFICATION_CLASS);
        if (pckg != null && clazz != null) {
            int uid = Binder.getCallingUid();
            try {
                if (uid == 0 ||
                        getContext().getPackageManager().getApplicationInfo(pckg, 0).uid == uid) {
                    filteredValues.put(Downloads.COLUMN_NOTIFICATION_PACKAGE, pckg);
                    filteredValues.put(Downloads.COLUMN_NOTIFICATION_CLASS, clazz);
                }
            } catch (PackageManager.NameNotFoundException ex) {
                /* ignored for now */
            }
        }
        copyString(Downloads.COLUMN_NOTIFICATION_EXTRAS, values, filteredValues);
        copyString(Downloads.COLUMN_COOKIE_DATA, values, filteredValues);
        copyString(Downloads.COLUMN_USER_AGENT, values, filteredValues);
        copyString(Downloads.COLUMN_REFERER, values, filteredValues);
        if (getContext().checkCallingPermission(Downloads.PERMISSION_ACCESS_ADVANCED)
                == PackageManager.PERMISSION_GRANTED) {
            copyInteger(Downloads.COLUMN_OTHER_UID, values, filteredValues);
        }
        filteredValues.put(Constants.UID, Binder.getCallingUid());
        if (Binder.getCallingUid() == 0) {
            copyInteger(Constants.UID, values, filteredValues);
        }
        copyString(Downloads.COLUMN_TITLE, values, filteredValues);
        copyString(Downloads.COLUMN_DESCRIPTION, values, filteredValues);

        if (Constants.LOGVV) {
            Log.v(Constants.TAG, "initiating download with UID "
                    + filteredValues.getAsInteger(Constants.UID));
            if (filteredValues.containsKey(Downloads.COLUMN_OTHER_UID)) {
                Log.v(Constants.TAG, "other UID " +
                        filteredValues.getAsInteger(Downloads.COLUMN_OTHER_UID));
            }
        }

        Context context = getContext();
        context.startService(new Intent(context, DownloadService.class));

        long rowID = db.insert(DB_TABLE, null, filteredValues);

        Uri ret = null;

        if (rowID != -1) {
            context.startService(new Intent(context, DownloadService.class));
            ret = Uri.parse(Downloads.CONTENT_URI + "/" + rowID);
            context.getContentResolver().notifyChange(uri, null);
        } else {
            if (Config.LOGD) {
                Log.d(Constants.TAG, "couldn't insert into downloads database");
            }
        }

        return ret;
    }

    /**
     * Starts a database query
     */
    @Override
    public Cursor query(final Uri uri, String[] projection,
             final String selection, final String[] selectionArgs,
             final String sort) {

        Helpers.validateSelection(selection, sAppReadableColumnsSet);

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
                qb.appendWhere(Downloads._ID + "=");
                qb.appendWhere(uri.getPathSegments().get(1));
                emptyWhere = false;
                break;
            }
            default: {
                if (Constants.LOGV) {
                    Log.v(Constants.TAG, "querying unknown URI: " + uri);
                }
                throw new IllegalArgumentException("Unknown URI: " + uri);
            }
        }

        if (Binder.getCallingPid() != Process.myPid() && Binder.getCallingUid() != 0 &&
                Process.supportsProcesses()) {
            if (!emptyWhere) {
                qb.appendWhere(" AND ");
            }
            qb.appendWhere("( " + Constants.UID + "=" +  Binder.getCallingUid() + " OR "
                    + Downloads.COLUMN_OTHER_UID + "=" +  Binder.getCallingUid() + " )");
            emptyWhere = false;

            if (projection == null) {
                projection = sAppReadableColumnsArray;
            } else {
                for (int i = 0; i < projection.length; ++i) {
                    if (!sAppReadableColumnsSet.contains(projection[i])) {
                        throw new IllegalArgumentException(
                                "column " + projection[i] + " is not allowed in queries");
                    }
                }
            }
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
            Log.v(Constants.TAG, sb.toString());
        }

        Cursor ret = qb.query(db, projection, selection, selectionArgs,
                              null, null, sort);

        if (ret != null) {
           ret = new ReadOnlyCursorWrapper(ret);
        }

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

    /**
     * Updates a row in the database
     */
    @Override
    public int update(final Uri uri, final ContentValues values,
            final String where, final String[] whereArgs) {

        Helpers.validateSelection(where, sAppReadableColumnsSet);

        SQLiteDatabase db = mOpenHelper.getWritableDatabase();

        int count;
        long rowId = 0;
        boolean startService = false;

        ContentValues filteredValues;
        if (Binder.getCallingPid() != Process.myPid()) {
            filteredValues = new ContentValues();
            copyString(Downloads.COLUMN_APP_DATA, values, filteredValues);
            copyInteger(Downloads.COLUMN_VISIBILITY, values, filteredValues);
            Integer i = values.getAsInteger(Downloads.COLUMN_CONTROL);
            if (i != null) {
                filteredValues.put(Downloads.COLUMN_CONTROL, i);
                startService = true;
            }
            copyInteger(Downloads.COLUMN_CONTROL, values, filteredValues);
            copyString(Downloads.COLUMN_TITLE, values, filteredValues);
            copyString(Downloads.COLUMN_DESCRIPTION, values, filteredValues);
        } else {
            filteredValues = values;
        }
        int match = sURIMatcher.match(uri);
        switch (match) {
            case DOWNLOADS:
            case DOWNLOADS_ID: {
                String myWhere;
                if (where != null) {
                    if (match == DOWNLOADS) {
                        myWhere = "( " + where + " )";
                    } else {
                        myWhere = "( " + where + " ) AND ";
                    }
                } else {
                    myWhere = "";
                }
                if (match == DOWNLOADS_ID) {
                    String segment = uri.getPathSegments().get(1);
                    rowId = Long.parseLong(segment);
                    myWhere += " ( " + Downloads._ID + " = " + rowId + " ) ";
                }
                if (Binder.getCallingPid() != Process.myPid() && Binder.getCallingUid() != 0) {
                    myWhere += " AND ( " + Constants.UID + "=" +  Binder.getCallingUid() + " OR "
                            + Downloads.COLUMN_OTHER_UID + "=" +  Binder.getCallingUid() + " )";
                }
                if (filteredValues.size() > 0) {
                    count = db.update(DB_TABLE, filteredValues, myWhere, whereArgs);
                } else {
                    count = 0;
                }
                break;
            }
            default: {
                if (Config.LOGD) {
                    Log.d(Constants.TAG, "updating unknown/invalid URI: " + uri);
                }
                throw new UnsupportedOperationException("Cannot update URI: " + uri);
            }
        }
        getContext().getContentResolver().notifyChange(uri, null);
        if (startService) {
            Context context = getContext();
            context.startService(new Intent(context, DownloadService.class));
        }
        return count;
    }

    /**
     * Deletes a row in the database
     */
    @Override
    public int delete(final Uri uri, final String where,
            final String[] whereArgs) {

        Helpers.validateSelection(where, sAppReadableColumnsSet);

        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        int count;
        int match = sURIMatcher.match(uri);
        switch (match) {
            case DOWNLOADS:
            case DOWNLOADS_ID: {
                String myWhere;
                if (where != null) {
                    if (match == DOWNLOADS) {
                        myWhere = "( " + where + " )";
                    } else {
                        myWhere = "( " + where + " ) AND ";
                    }
                } else {
                    myWhere = "";
                }
                if (match == DOWNLOADS_ID) {
                    String segment = uri.getPathSegments().get(1);
                    long rowId = Long.parseLong(segment);
                    myWhere += " ( " + Downloads._ID + " = " + rowId + " ) ";
                }
                if (Binder.getCallingPid() != Process.myPid() && Binder.getCallingUid() != 0) {
                    myWhere += " AND ( " + Constants.UID + "=" +  Binder.getCallingUid() + " OR "
                            + Downloads.COLUMN_OTHER_UID + "=" +  Binder.getCallingUid() + " )";
                }
                count = db.delete(DB_TABLE, myWhere, whereArgs);
                break;
            }
            default: {
                if (Config.LOGD) {
                    Log.d(Constants.TAG, "deleting unknown/invalid URI: " + uri);
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
            Log.v(Constants.TAG, "openFile uri: " + uri + ", mode: " + mode
                    + ", uid: " + Binder.getCallingUid());
            Cursor cursor = query(Downloads.CONTENT_URI, new String[] { "_id" }, null, null, "_id");
            if (cursor == null) {
                Log.v(Constants.TAG, "null cursor in openFile");
            } else {
                if (!cursor.moveToFirst()) {
                    Log.v(Constants.TAG, "empty cursor in openFile");
                } else {
                    do {
                        Log.v(Constants.TAG, "row " + cursor.getInt(0) + " available");
                    } while(cursor.moveToNext());
                }
                cursor.close();
            }
            cursor = query(uri, new String[] { "_data" }, null, null, null);
            if (cursor == null) {
                Log.v(Constants.TAG, "null cursor in openFile");
            } else {
                if (!cursor.moveToFirst()) {
                    Log.v(Constants.TAG, "empty cursor in openFile");
                } else {
                    String filename = cursor.getString(0);
                    Log.v(Constants.TAG, "filename in openFile: " + filename);
                    if (new java.io.File(filename).isFile()) {
                        Log.v(Constants.TAG, "file exists in openFile");
                    }
                }
               cursor.close();
            }
        }

        // This logic is mostly copied form openFileHelper. If openFileHelper eventually
        //     gets split into small bits (to extract the filename and the modebits),
        //     this code could use the separate bits and be deeply simplified.
        Cursor c = query(uri, new String[]{"_data"}, null, null, null);
        int count = (c != null) ? c.getCount() : 0;
        if (count != 1) {
            // If there is not exactly one result, throw an appropriate exception.
            if (c != null) {
                c.close();
            }
            if (count == 0) {
                throw new FileNotFoundException("No entry for " + uri);
            }
            throw new FileNotFoundException("Multiple items at " + uri);
        }

        c.moveToFirst();
        String path = c.getString(0);
        c.close();
        if (path == null) {
            throw new FileNotFoundException("No filename found.");
        }
        if (!Helpers.isFilenameValid(path)) {
            throw new FileNotFoundException("Invalid filename.");
        }

        if (!"r".equals(mode)) {
            throw new FileNotFoundException("Bad mode for " + uri + ": " + mode);
        }
        ParcelFileDescriptor ret = ParcelFileDescriptor.open(new File(path),
                ParcelFileDescriptor.MODE_READ_ONLY);

        if (ret == null) {
            if (Constants.LOGV) {
                Log.v(Constants.TAG, "couldn't open file");
            }
            throw new FileNotFoundException("couldn't open file");
        } else {
            ContentValues values = new ContentValues();
            values.put(Downloads.COLUMN_LAST_MODIFICATION, System.currentTimeMillis());
            update(uri, values, null, null);
        }
        return ret;
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

    private class ReadOnlyCursorWrapper extends CursorWrapper implements CrossProcessCursor {
        public ReadOnlyCursorWrapper(Cursor cursor) {
            super(cursor);
            mCursor = (CrossProcessCursor) cursor;
        }

        public boolean deleteRow() {
            throw new SecurityException("Download manager cursors are read-only");
        }

        public boolean commitUpdates() {
            throw new SecurityException("Download manager cursors are read-only");
        }

        public void fillWindow(int pos, CursorWindow window) {
            mCursor.fillWindow(pos, window);
        }

        public CursorWindow getWindow() {
            return mCursor.getWindow();
        }

        public boolean onMove(int oldPosition, int newPosition) {
            return mCursor.onMove(oldPosition, newPosition);
        }

        private CrossProcessCursor mCursor;
    }

}
