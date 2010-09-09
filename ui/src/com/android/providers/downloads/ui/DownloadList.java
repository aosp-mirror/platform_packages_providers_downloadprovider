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

package com.android.providers.downloads.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.net.DownloadManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Downloads;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.Button;
import android.widget.ExpandableListView;
import android.widget.ExpandableListView.OnChildClickListener;
import android.widget.ListView;
import android.widget.Toast;

import com.android.providers.downloads.ui.DownloadItem.DownloadSelectListener;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 *  View showing a list of all downloads the Download Manager knows about.
 */
public class DownloadList extends Activity
        implements OnChildClickListener, OnItemClickListener, DownloadSelectListener,
        OnClickListener {
    private ExpandableListView mDateOrderedListView;
    private ListView mSizeOrderedListView;
    private View mEmptyView;
    private ViewGroup mSelectionMenuView;
    private Button mSelectionDeleteButton;

    private DownloadManager mDownloadManager;
    private Cursor mDateSortedCursor;
    private DateSortedDownloadAdapter mDateSortedAdapter;
    private Cursor mSizeSortedCursor;
    private DownloadAdapter mSizeSortedAdapter;

    private int mStatusColumnId;
    private int mIdColumnId;
    private int mLocalUriColumnId;
    private int mMediaTypeColumnId;

    private boolean mIsSortedBySize = false;
    private Set<Long> mSelectedIds = new HashSet<Long>();

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        setupViews();

        mDownloadManager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        mDateSortedCursor = mDownloadManager.query(new DownloadManager.Query());
        mSizeSortedCursor = mDownloadManager.query(new DownloadManager.Query()
                                                  .orderBy(DownloadManager.COLUMN_TOTAL_SIZE_BYTES,
                                                          DownloadManager.Query.ORDER_DESCENDING));

        // only attach everything to the listbox if we can access the download database. Otherwise,
        // just show it empty
        if (mDateSortedCursor != null && mSizeSortedCursor != null) {
            startManagingCursor(mDateSortedCursor);
            startManagingCursor(mSizeSortedCursor);

            mStatusColumnId =
                    mDateSortedCursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS);
            mIdColumnId =
                    mDateSortedCursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID);
            mLocalUriColumnId =
                    mDateSortedCursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI);
            mMediaTypeColumnId =
                    mDateSortedCursor.getColumnIndexOrThrow(DownloadManager.COLUMN_MEDIA_TYPE);

            mDateSortedAdapter = new DateSortedDownloadAdapter(this, mDateSortedCursor, this);
            mDateOrderedListView.setAdapter(mDateSortedAdapter);
            mSizeSortedAdapter = new DownloadAdapter(this, mSizeSortedCursor, this);
            mSizeOrderedListView.setAdapter(mSizeSortedAdapter);

            // have the first group be open by default
            mDateOrderedListView.post(new Runnable() {
                public void run() {
                    if (mDateSortedAdapter.getGroupCount() > 0) {
                        mDateOrderedListView.expandGroup(0);
                    }
                }
            });
        }

        chooseListToShow();
    }

    private void setupViews() {
        setContentView(R.layout.download_list);
        setTitle(getText(R.string.download_title));

        mDateOrderedListView = (ExpandableListView) findViewById(R.id.date_ordered_list);
        mDateOrderedListView.setOnChildClickListener(this);
        mSizeOrderedListView = (ListView) findViewById(R.id.size_ordered_list);
        mSizeOrderedListView.setOnItemClickListener(this);
        mEmptyView = findViewById(R.id.empty);

        mSelectionMenuView = (ViewGroup) findViewById(R.id.selection_menu);
        mSelectionDeleteButton = (Button) findViewById(R.id.selection_delete);
        mSelectionDeleteButton.setOnClickListener(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("isSortedBySize", mIsSortedBySize);
        outState.putLongArray("selection", getSelectionAsArray());
    }

    private long[] getSelectionAsArray() {
        long[] selectedIds = new long[mSelectedIds.size()];
        Iterator<Long> iterator = mSelectedIds.iterator();
        for (int i = 0; i < selectedIds.length; i++) {
            selectedIds[i] = iterator.next();
        }
        return selectedIds;
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        mIsSortedBySize = savedInstanceState.getBoolean("isSortedBySize");
        mSelectedIds.clear();
        for (long selectedId : savedInstanceState.getLongArray("selection")) {
            mSelectedIds.add(selectedId);
        }
        chooseListToShow();
        showOrHideSelectionMenu();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        if (mDateSortedCursor != null) {
            MenuInflater inflater = getMenuInflater();
            inflater.inflate(R.menu.download_menu, menu);
        }
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.findItem(R.id.download_menu_sort_by_size).setVisible(!mIsSortedBySize);
        menu.findItem(R.id.download_menu_sort_by_date).setVisible(mIsSortedBySize);
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.download_menu_sort_by_size:
                mIsSortedBySize = true;
                chooseListToShow();
                return true;
            case R.id.download_menu_sort_by_date:
                mIsSortedBySize = false;
                chooseListToShow();
                return true;
        }
        return false;
    }

    /**
     * Show the correct ListView and hide the other, or hide both and show the empty view.
     */
    private void chooseListToShow() {
        mDateOrderedListView.setVisibility(View.GONE);
        mSizeOrderedListView.setVisibility(View.GONE);

        if (mDateSortedCursor.getCount() == 0) {
            mEmptyView.setVisibility(View.VISIBLE);
        } else {
            mEmptyView.setVisibility(View.GONE);
            activeListView().setVisibility(View.VISIBLE);
            activeListView().invalidateViews(); // ensure checkboxes get updated
        }
    }

    /**
     * @return the ListView that should currently be visible.
     */
    private ListView activeListView() {
        if (mIsSortedBySize) {
            return mSizeOrderedListView;
        }
        return mDateOrderedListView;
    }

    /**
     * @return an OnClickListener to delete the given downloadId from the Download Manager
     */
    private DialogInterface.OnClickListener getDeleteClickHandler(final long downloadId) {
        return new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                deleteDownload(downloadId);
            }
        };
    }

    /**
     * Send an Intent to open the download currently pointed to by the given cursor.
     */
    private void openCurrentDownload(Cursor cursor) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        Uri fileUri = Uri.parse(cursor.getString(mLocalUriColumnId));
        intent.setDataAndType(fileUri, cursor.getString(mMediaTypeColumnId));
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException ex) {
            Toast.makeText(this, R.string.download_no_application_title, Toast.LENGTH_LONG).show();
        }
    }

    private void handleItemClick(Cursor cursor) {
        long id = cursor.getInt(mIdColumnId);
        switch (cursor.getInt(mStatusColumnId)) {
            case DownloadManager.STATUS_PENDING:
                new AlertDialog.Builder(this)
                        .setTitle(R.string.dialog_title_not_available)
                        .setMessage("This file is queued for future download.")
                        .setPositiveButton(R.string.keep_queued_download, null)
                        .setNegativeButton(R.string.remove_download, getDeleteClickHandler(id))
                        .show();
                break;

            case DownloadManager.STATUS_RUNNING:
            case DownloadManager.STATUS_PAUSED:
                sendRunningDownloadClickedBroadcast(id);
                break;

            case DownloadManager.STATUS_SUCCESSFUL:
                openCurrentDownload(cursor);
                break;

            case DownloadManager.STATUS_FAILED:
                new AlertDialog.Builder(this)
                        .setTitle(R.string.dialog_title_not_available)
                        .setMessage(getResources().getString(R.string.dialog_failed_body))
                        .setPositiveButton(R.string.remove_download, getDeleteClickHandler(id))
                        // TODO button to retry download
                        .show();
                break;
        }
    }

    /**
     * TODO use constants/shared code?
     */
    private void sendRunningDownloadClickedBroadcast(long id) {
        Intent intent = new Intent("android.intent.action.DOWNLOAD_LIST");
        intent.setClassName("com.android.providers.downloads",
                "com.android.providers.downloads.DownloadReceiver");
        intent.setData(Uri.parse(Downloads.Impl.CONTENT_URI + "/" + id));
        intent.putExtra("multiple", false);
        sendBroadcast(intent);
    }

    // handle a click from the date-sorted list
    @Override
    public boolean onChildClick(ExpandableListView parent, View v,
            int groupPosition, int childPosition, long id) {
        mDateSortedAdapter.moveCursorToChildPosition(groupPosition, childPosition);
        handleItemClick(mDateSortedCursor);
        return true;
    }

    // handle a click from the size-sorted list
    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        mSizeSortedCursor.moveToPosition(position);
        handleItemClick(mSizeSortedCursor);
    }

    // handle a click on one of the download item checkboxes
    @Override
    public void onDownloadSelectionChanged(long downloadId, boolean isSelected) {
        if (isSelected) {
            mSelectedIds.add(downloadId);
        } else {
            mSelectedIds.remove(downloadId);
        }
        showOrHideSelectionMenu();
    }

    private void showOrHideSelectionMenu() {
        boolean shouldBeVisible = !mSelectedIds.isEmpty();
        boolean isVisible = mSelectionMenuView.getVisibility() == View.VISIBLE;
        if (shouldBeVisible) {
            updateSelectionMenu();
            if (!isVisible) {
                // show menu
                mSelectionMenuView.setVisibility(View.VISIBLE);
                mSelectionMenuView.startAnimation(
                        AnimationUtils.loadAnimation(this, R.anim.footer_appear));
            }
        } else if (!shouldBeVisible && isVisible) {
            // hide menu
            mSelectionMenuView.setVisibility(View.GONE);
            mSelectionMenuView.startAnimation(
                    AnimationUtils.loadAnimation(this, R.anim.footer_disappear));
        }
    }

    /**
     * Set up the contents of the selection menu based on the current selection.
     */
    private void updateSelectionMenu() {
        int deleteButtonStringId = R.string.delete_download;
        if (mSelectedIds.size() == 1) {
            Cursor cursor = mDownloadManager.query(new DownloadManager.Query()
                    .setFilterById(mSelectedIds.iterator().next()));
            try {
                cursor.moveToFirst();
                switch (cursor.getInt(mStatusColumnId)) {
                    case DownloadManager.STATUS_FAILED:
                    case DownloadManager.STATUS_PENDING:
                        deleteButtonStringId = R.string.remove_download;
                        break;

                    case DownloadManager.STATUS_PAUSED:
                    case DownloadManager.STATUS_RUNNING:
                        deleteButtonStringId = R.string.cancel_running_download;
                        break;
                }
            } finally {
                cursor.close();
            }
        }
        mSelectionDeleteButton.setText(deleteButtonStringId);
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.selection_delete:
                for (Long downloadId : mSelectedIds) {
                    deleteDownload(downloadId);
                }
                clearSelection();
                return;
        }
    }

    /**
     * Requery the database and update the UI.
     */
    private void refresh() {
        mDateSortedCursor.requery();
        mSizeSortedCursor.requery();
        // Adapters get notification of changes and update automatically
    }

    private void clearSelection() {
        mSelectedIds.clear();
        showOrHideSelectionMenu();
    }

    /**
     * Delete a download from the Download Manager.
     */
    private void deleteDownload(Long downloadId) {
        mDownloadManager.remove(downloadId);
    }

    @Override
    public boolean isDownloadSelected(long id) {
        return mSelectedIds.contains(id);
    }
}
