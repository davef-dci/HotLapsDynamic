package com.hotlaps.dynamic.data

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File

/**
 * Centralized helpers for all on-disk paths.
 *
 * - App-private root: /Android/data/com.hotlaps.dynamic/files/
 * - Events dir:       .../events/
 * - Public export:    /Download/HotLapsDynamic/events/
 */


private const val PUBLIC_APP_DIR = "ApexDynamics"

object FileHelper {

    private const val TAG = "FileHelper"

    /** App-private root (what getExternalFilesDir(null) gives us). */
    fun appPrivateRoot(context: Context): File? {
        val base = context.getExternalFilesDir(null)
        if (base == null) {
            Log.e(TAG, "appPrivateRoot: getExternalFilesDir(null) returned null")
        }
        return base
    }

    /** Events directory in APP-PRIVATE storage: /Android/data/com.hotlaps.dynamic/files/events */
    fun appEventsDir(context: Context): File? {
        val root = appPrivateRoot(context) ?: return null

        val dir = File(root, "events")
        if (!dir.exists() && !dir.mkdirs()) {
            Log.e(TAG, "appEventsDir: failed to create ${dir.absolutePath}")
            return null
        }
        return dir
    }



    /** Events directory – now in PUBLIC Downloads so users & email can see it easily. */
    fun eventsDir(context: Context): File? {
        // Public Downloads root: /storage/emulated/0/Download
        val downloads = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS
        )

        // Our subfolder: /Download/HotLapsDynamic/events
        val dir = File(downloads, "$PUBLIC_APP_DIR/events")

        if (!dir.exists() && !dir.mkdirs()) {
            Log.e(TAG, "eventsDir: failed to create ${dir.absolutePath}")
            return null
        }
        return dir
    }


    /**
     * Public export location visible to normal file managers:
     *   /storage/emulated/0/Download/ApexDynamics/events/
     */
    fun publicEventsExportDir(): File? {
        val downloads = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS
        )
        val dir = File(downloads, "$PUBLIC_APP_DIR/events")

        if (!dir.exists() && !dir.mkdirs()) {
            Log.e(TAG, "publicEventsExportDir: failed to create ${dir.absolutePath}")
            return null
        }
        return dir
    }

    fun publicTracksDir(context: Context): File {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "$PUBLIC_APP_DIR/tracks"
        )

        if (!dir.exists()) {
            val ok = dir.mkdirs()
            Log.d("FileHelper", "publicTracksDir mkdirs ok=$ok path=${dir.absolutePath}")
        } else {
            Log.d("FileHelper", "publicTracksDir exists path=${dir.absolutePath}")
        }

        return dir
    }




}
