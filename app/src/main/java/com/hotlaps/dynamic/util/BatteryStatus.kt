package com.hotlaps.dynamic.util

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager

/**
 * Battery level + charging state, read from the sticky ACTION_BATTERY_CHANGED broadcast
 * (no receiver needs to stay registered).
 *
 * Used for the health-log heartbeat (drain rate / whether a USB-C splitter really charges
 * while the GPS puck is attached) and the drive-screen low-battery warning.
 *
 * [tempC] and [thermal] are there to tell whether heat is behind a puck drop-out: with no
 * charger the phone powers the hub + puck itself, and Android may cut that when hot
 * (Road America race, Oct 2026: puck stopped at 45% battery).
 */
data class BatteryStatus(
    val percent: Int,
    val charging: Boolean,
    val plugged: String,
    /** Battery temperature, null if not reported. */
    val tempC: Double? = null,
    /** Android's thermal throttling state ("none", "light", ... "shutdown"); null before Android 10. */
    val thermal: String? = null
) {

    companion object {
        const val LOW_PERCENT = 20

        fun read(context: Context): BatteryStatus? {
            val intent: Intent = context.applicationContext
                .registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level < 0 || scale <= 0) return null
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            val plugged = when (intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
                BatteryManager.BATTERY_PLUGGED_AC -> "ac"
                BatteryManager.BATTERY_PLUGGED_USB -> "usb"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
                else -> "none"
            }
            val tenthsC = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            val tempC = if (tenthsC == Int.MIN_VALUE) null else tenthsC / 10.0
            return BatteryStatus(level * 100 / scale, charging, plugged, tempC, thermalStatus(context))
        }

        private fun thermalStatus(context: Context): String? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return null
            return when (pm.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> "none"
                PowerManager.THERMAL_STATUS_LIGHT -> "light"
                PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
                PowerManager.THERMAL_STATUS_SEVERE -> "severe"
                PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
                PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
                PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
                else -> "unknown"
            }
        }
    }
}
