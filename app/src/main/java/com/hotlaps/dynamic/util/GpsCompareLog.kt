package com.hotlaps.dynamic.util

import android.content.Context
import android.location.Location
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Test mode: logs the phone's own GPS alongside the USB puck, to compare their timing.
 * 2026-10-04 (58 min of driving): puck positions match the phone's within ~20 ms and its RMC
 * speed lags the phone's Doppler speed by ~0.2 s. Off by default; switched on from the Drive
 * screen's Debug Panel.
 *
 * Writes debug_logs/gps_compare_<date>.csv (pull with adb), one row per fix:
 *   src,phoneMs,gpsMs,ageMs,lat,lon,speedMps,rmcSpeedMps,accuracyM
 *  - phoneMs: System.currentTimeMillis() on arrival (same clock as the event CSV's timestampMs)
 *  - gpsMs: the fix's own time (RMC time for the puck, Location.time for the phone)
 *  - ageMs (phone only): how old the fix was on arrival, from the monotonic clock
 *  - speedMps: what the app records; rmcSpeedMps: the puck's RMC speed (same value)
 */
object GpsCompareLog {

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled

    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var file: File? = null

    fun setEnabled(context: Context, on: Boolean) {
        if (on == _enabled.value) return
        if (on) {
            val dir = context.getExternalFilesDir("debug_logs") ?: return
            val name = "gps_compare_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".csv"
            val f = File(dir, name)
            io.execute { f.writeText("src,phoneMs,gpsMs,ageMs,lat,lon,speedMps,rmcSpeedMps,accuracyM\n") }
            file = f
            RecordingHealth.log("GPS COMPARE on: ${f.name}")
        } else {
            file = null
            RecordingHealth.log("GPS COMPARE off")
        }
        _enabled.value = on
    }

    fun puck(fix: UsbPuckGpsSource.UsbGpsFix) {
        val f = file ?: return
        write(f, "puck,${fix.utcMs},${fix.gpsTimeMs ?: ""},,${fix.lat},${fix.lon},${fix.speedMps ?: ""},${fix.rmcSpeedMps ?: ""},")
    }

    fun phone(loc: Location) {
        val f = file ?: return
        val ageMs = (SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1_000_000
        val speed = if (loc.hasSpeed()) loc.speed.toString() else ""
        val acc = if (loc.hasAccuracy()) loc.accuracy.toString() else ""
        write(f, "phone,${System.currentTimeMillis()},${loc.time},$ageMs,${loc.latitude},${loc.longitude},$speed,,$acc")
    }

    private fun write(f: File, line: String) {
        io.execute {
            try { f.appendText(line + "\n") } catch (_: Exception) { }
        }
    }
}
