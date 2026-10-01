package com.hotlaps.dynamic

import android.app.Application
import com.hotlaps.dynamic.recording.RecordingEngine
import com.hotlaps.dynamic.viewmodel.DriveViewModel

/**
 * Process-wide owners of recording state.
 *
 * The drive state lives here instead of in an Activity-scoped ViewModelStore so that the
 * drive screen and [com.hotlaps.dynamic.recording.RecordingService] share one recording that
 * survives screen changes and the Activity going to the background.
 */
class HotLapsApp : Application() {

    val driveViewModel: DriveViewModel by lazy {
        DriveViewModel().also { it.setAppContext(this) }
    }

    val recordingEngine: RecordingEngine by lazy {
        RecordingEngine(this, driveViewModel)
    }
}
