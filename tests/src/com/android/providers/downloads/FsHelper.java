package com.android.providers.downloads;

import java.io.File;

public class FsHelper {

    /**
     * Deletes all files under a given directory. Deliberately ignores errors, on the assumption
     * that test cleanup is only supposed to be best-effort.
     *
     * @param directory directory to clear its contents
     */
    public static void deleteContents(File directory) {
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteContents(file);
                }
                file.delete();
            }
        }
    }
}
