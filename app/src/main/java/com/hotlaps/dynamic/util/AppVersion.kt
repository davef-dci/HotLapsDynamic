package com.hotlaps.dynamic.util

import com.hotlaps.dynamic.BuildConfig

/**
 * This build, e.g. "0.3.0 bead528" ("bead528+" = built with uncommitted changes).
 * Shown on the main menu, written to the health log at each recording start, and uploaded in
 * each session's corners file so the Analyzer can show which build recorded a session.
 */
object AppVersion {
    val label: String get() = "${BuildConfig.VERSION_NAME} ${BuildConfig.GIT_SHA}"
}
