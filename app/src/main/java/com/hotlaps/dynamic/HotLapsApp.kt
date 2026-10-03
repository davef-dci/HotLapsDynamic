package com.hotlaps.dynamic

import android.app.Application
import com.hotlaps.dynamic.recording.RecordingEngine
import com.hotlaps.dynamic.recording.SessionRecovery
import com.hotlaps.dynamic.util.RecordingHealth
import com.hotlaps.dynamic.viewmodel.DriveViewModel

/**
 * Process-wide owners of recording state.
 *
 * The drive state lives here instead of in an Activity-scoped ViewModelStore so that the
 * drive screen and [com.hotlaps.dynamic.recording.RecordingService] share one recording that
 * survives screen changes and the Activity going to the background.
 */
class HotLapsApp : Application() {

    override fun onCreate() {
        super.onCreate()
        RecordingHealth.init(this)
        // Finish any session that was cut off (crash / Force Stop / dead battery) last time
        SessionRecovery.recoverAsync(this)
    }

    val driveViewModel: DriveViewModel by lazy {
        DriveViewModel().also { it.setAppContext(this) }
    }

    val recordingEngine: RecordingEngine by lazy {
        RecordingEngine(this, driveViewModel)
    }
}
