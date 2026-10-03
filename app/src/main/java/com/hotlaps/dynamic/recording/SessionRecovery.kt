package com.hotlaps.dynamic.recording

import android.content.Context
import com.hotlaps.dynamic.data.EventStorage
import com.hotlaps.dynamic.util.DriveUploadHelper
import com.hotlaps.dynamic.util.RecordingHealth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Finishes sessions that were cut off before Stop (crash, Force Stop, battery died).
 *
 * Without this, such a session keeps its raw file (no speed interpolation, no corners/laps),
 * never uploads its complete file, and its live parts stay in Drive, so the Analyzer's Go Live
 * would wait on it forever. At app start, before anything records, every session that still
 * has an "active" marker gets the normal Stop processing: finishing pass, complete-file upload,
 * live-part cleanup.
 */
object SessionRecovery {

    fun recoverAsync(context: Context) {
        val appContext = context.applicationContext
        // Snapshot now: sessions started after this point aren't orphans
        val orphans = EventStorage.unfinishedSessions(appContext)
        if (orphans.isEmpty()) return

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            for (s in orphans) {
                RecordingHealth.log("RECOVER event=${s.eventId}: session was cut off before Stop; finishing it")
                val file = EventStorage.finishEvent(
                    appContext, s.eventId, finalName = null,
                    corners = s.corners.ifEmpty { null }, cornerRadiusM = s.radiusM
                )
                val uploaded = file != null && DriveUploadHelper.uploadBackupFile(appContext, s.eventId)
                if (uploaded) DriveUploadHelper.deleteLiveParts(appContext, s.eventId)
                RecordingHealth.log("RECOVERED event=${s.eventId} file=${file?.name} uploaded=$uploaded")
            }
        }
    }
}
