package com.hotlaps.dynamic.util

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * Battery level + charging state, read from the sticky ACTION_BATTERY_CHANGED broadcast
 * (no receiver needs to stay registered).
 *
 * Used for the health-log heartbeat (drain rate / whether a USB-C splitter really charges
 * while the GPS puck is attached) and the drive-screen low-battery warning.
 */
data class BatteryStatus(val percent: Int, val charging: Boolean, val plugged: String) {

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
            return BatteryStatus(level * 100 / scale, charging, plugged)
        }
    }
}
