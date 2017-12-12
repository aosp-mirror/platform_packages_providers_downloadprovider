/*
 * Copyright (C) 2014 The Android Open Source Project
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
import android.system.ErrnoException;
import android.system.Os;
import android.system.StructStat;

import com.google.android.collect.Lists;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;

/**
 * Utility methods for managing storage space related to
 * {@link DownloadManager}.
 */
public class StorageUtils {
    /**
     * Return list of all normal files under the given directory, traversing
     * directories recursively.
     *
     * @param exclude ignore dirs with this name, or {@code null} to ignore.
     * @param uid only return files owned by this UID, or {@code -1} to ignore.
     */
    static List<ConcreteFile> listFilesRecursive(File startDir, String exclude, int uid) {
        final ArrayList<ConcreteFile> files = Lists.newArrayList();
        final LinkedList<File> dirs = new LinkedList<File>();
        dirs.add(startDir);
        while (!dirs.isEmpty()) {
            final File dir = dirs.removeFirst();
            if (Objects.equals(dir.getName(), exclude)) continue;

            final File[] children = dir.listFiles();
            if (children == null) continue;

            for (File child : children) {
                if (child.isDirectory()) {
                    dirs.add(child);
                } else if (child.isFile()) {
                    try {
                        final ConcreteFile file = new ConcreteFile(child);
                        if (uid == -1 || file.stat.st_uid == uid) {
                            files.add(file);
                        }
                    } catch (ErrnoException ignored) {
                    }
                }
            }
        }
        return files;
    }

    /**
     * Concrete file on disk that has a backing device and inode. Faster than
     * {@code realpath()} when looking for identical files.
     */
    static class ConcreteFile {
        public final File file;
        public final StructStat stat;

        public ConcreteFile(File file) throws ErrnoException {
            this.file = file;
            this.stat = Os.lstat(file.getAbsolutePath());
        }

        @Override
        public int hashCode() {
            int result = 1;
            result = 31 * result + (int) (stat.st_dev ^ (stat.st_dev >>> 32));
            result = 31 * result + (int) (stat.st_ino ^ (stat.st_ino >>> 32));
            return result;
        }

        @Override
        public boolean equals(Object o) {
            if (o instanceof ConcreteFile) {
                final ConcreteFile f = (ConcreteFile) o;
                return (f.stat.st_dev == stat.st_dev) && (f.stat.st_ino == stat.st_ino);
            }
            return false;
        }
    }
}
