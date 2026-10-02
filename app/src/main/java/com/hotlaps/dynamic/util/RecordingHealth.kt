package com.hotlaps.dynamic.util

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Field diagnostics for recording reliability, written to
 *   Android/data/com.hotlaps.dynamic/files/debug_logs/recording_health.log
 *
 *  - STALL lines when the sampling loop goes more than [STALL_MS] between ticks
 *    (at Gingerman, Apr 2026, per-corner file rewrites froze it for up to ~9 s)
 *  - a HEARTBEAT line every minute while recording (ticks, worst gap, memory, battery)
 *  - EXIT lines at app start: why the previous processes died (crash / ANR / low memory...)
 *
 * All file writes happen on a private background thread.
 */
object RecordingHealth {

    private const val TAG = "RecordingHealth"
    private const val STALL_MS = 250L
    private const val HEARTBEAT_MS = 60_000L

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "RecordingHealth").apply { isDaemon = true }
    }
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private var logFile: File? = null
    private var appContext: Context? = null

    // Tick statistics (touched only from the sampling loop's thread)
    private var lastTickMs = 0L
    private var lastHeartbeatMs = 0L
    private var ticksSinceHeartbeat = 0
    private var worstGapSinceHeartbeat = 0L
    private var stallsSinceHeartbeat = 0

    fun init(context: Context) {
        if (logFile != null) return
        appContext = context.applicationContext
        val dir = context.getExternalFilesDir("debug_logs") ?: return
        logFile = File(dir, "recording_health.log")
        logPreviousExits(context)
    }

    /** Call once per sampling tick. [label] identifies the loop (e.g. "live", "replay"). */
    fun onTick(label: String, recording: Boolean, nowMs: Long = System.currentTimeMillis()) {
        if (lastTickMs != 0L) {
            val gap = nowMs - lastTickMs
            if (gap > worstGapSinceHeartbeat) worstGapSinceHeartbeat = gap
            if (gap > STALL_MS && recording) {
                stallsSinceHeartbeat++
                write("STALL $label gapMs=$gap")
            }
        }
        lastTickMs = nowMs
        ticksSinceHeartbeat++

        if (lastHeartbeatMs == 0L) lastHeartbeatMs = nowMs
        if (nowMs - lastHeartbeatMs >= HEARTBEAT_MS) {
            if (recording) {
                val rt = Runtime.getRuntime()
                val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
                val maxMb = rt.maxMemory() / (1024 * 1024)
                val battery = appContext?.let { BatteryStatus.read(it) }
                val batteryText = battery?.let {
                    " battery=${it.percent}% charging=${it.charging} plugged=${it.plugged}"
                } ?: ""
                write(
                    "HEARTBEAT $label ticks=$ticksSinceHeartbeat " +
                            "worstGapMs=$worstGapSinceHeartbeat stalls=$stallsSinceHeartbeat " +
                            "heapMb=$usedMb/$maxMb$batteryText"
                )
            }
            lastHeartbeatMs = nowMs
            ticksSinceHeartbeat = 0
            worstGapSinceHeartbeat = 0
            stallsSinceHeartbeat = 0
        }
    }

    /** Resets the gap baseline, e.g. when a loop (re)starts, so startup isn't counted as a stall. */
    fun resetTicks() {
        lastTickMs = 0L
    }

    fun log(message: String) = write(message)

    private fun write(message: String) {
        val file = logFile ?: return
        val line = "${timeFormat.format(Date())} $message\n"
        Log.i(TAG, line.trim())
        io.execute {
            try {
                file.appendText(line)
            } catch (e: Exception) {
                Log.e(TAG, "write failed", e)
            }
        }
    }

    private fun logPreviousExits(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, 5)
            write("APP START (previous exits, newest first: ${exits.size})")
            exits.forEach { e ->
                write(
                    "EXIT at=${timeFormat.format(Date(e.timestamp))} reason=${e.reason} " +
                            "status=${e.status} importance=${e.importance} " +
                            "pssKb=${e.pss} desc=${e.description}"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "logPreviousExits failed", e)
        }
    }
}
