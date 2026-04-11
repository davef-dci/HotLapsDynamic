package com.hotlaps.dynamic.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.widget.Toast

/**
 * System broadcast receiver: fires when a USB device matching usb_device_filter.xml
 * (Prolific PL2303, vendor 0x067B) is physically plugged in.
 *
 * The toast is purely informational; actual connection is managed by UsbPuckGpsSource.
 */
class UsbAttachReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED == intent.action) {
            Toast.makeText(context, "GPS puck connected", Toast.LENGTH_SHORT).show()
        }
    }
}
