package com.hotlaps.dynamic.util

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.nio.charset.Charset

/**
 * Manages the BU-353 10 Hz USB GPS puck (Prolific PL2303 chip).
 *
 * Usage:
 *   val source = UsbPuckGpsSource(context)
 *   source.start()
 *   // collect source.fixes for GPS updates
 *   // observe source.isConnected to know if puck is present
 *   source.stop()
 *
 * Each [UsbGpsFix] carries a real UTC timestamp (System.currentTimeMillis())
 * so it can be used directly as utcMs in EventSample.
 */
class UsbPuckGpsSource(private val context: Context) {

    companion object {
        private const val TAG = "UsbPuckGps"
        private const val ACTION_USB_PERMISSION = "com.hotlaps.dynamic.USB_PERMISSION"
        private const val VENDOR_PROLIFIC = 0x067B   // 1659 decimal — PL2303
        private const val BAUD = 115200
    }

    /** A GPS fix from the puck. utcMs is System.currentTimeMillis() at receipt time. */
    data class UsbGpsFix(
        val lat: Double,
        val lon: Double,
        val speedMps: Double?,          // from NMEA $GPRMC speed-over-ground (may be null)
        val utcMs: Long                 // wall-clock UTC ms — use directly as EventSample.utcMs
    )

    // ---- Public state --------------------------------------------------------

    private val _fixes = MutableSharedFlow<UsbGpsFix>(extraBufferCapacity = 32)
    /** Stream of validated GPS fixes at ~10 Hz when puck is connected. */
    val fixes: SharedFlow<UsbGpsFix> = _fixes

    private val _isConnected = MutableStateFlow(false)
    /** True while the puck is connected and producing data. */
    val isConnected: StateFlow<Boolean> = _isConnected

    // ---- Internals -----------------------------------------------------------

    private val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var readJob: Job? = null
    private var scope: CoroutineScope? = null

    // Permission broadcast receiver
    private val permReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            // Nothing to do here — we poll hasPermission() in runReader().
            // The receiver just ensures the system delivers the permission dialog result.
        }
    }

    // ---- Lifecycle -----------------------------------------------------------

    fun start() {
        if (readJob?.isActive == true) return
        val s = CoroutineScope(Dispatchers.IO)
        scope = s

        // Register permission receiver
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(permReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(permReceiver, filter)
        }

        readJob = s.launch { runReader() }
    }

    fun stop() {
        readJob?.cancel()
        readJob = null
        scope?.cancel()
        scope = null
        _isConnected.value = false
        try { context.unregisterReceiver(permReceiver) } catch (_: Exception) {}
    }

    // ---- Core read loop ------------------------------------------------------

    private suspend fun runReader() {
        val device = findProlific()
        if (device == null) {
            Log.d(TAG, "No Prolific PL2303 device found — internal GPS will be used")
            return
        }

        // Request permission if needed
        if (!usb.hasPermission(device)) {
            requestPermission(device)
            // Poll up to 5 seconds for user to grant permission
            var granted = false
            repeat(50) {
                if (usb.hasPermission(device)) { granted = true; return@repeat }
                kotlinx.coroutines.delay(100)
            }
            if (!granted) {
                Log.w(TAG, "USB permission denied by user")
                return
            }
        }

        val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
        if (driver == null) {
            Log.w(TAG, "No driver found for device (unexpected for PL2303)")
            return
        }

        val connection = usb.openDevice(device)
        if (connection == null) {
            Log.w(TAG, "Could not open USB device")
            return
        }

        val port = driver.ports.firstOrNull()
        if (port == null) {
            connection.close()
            return
        }

        try {
            port.open(connection)
            port.setParameters(BAUD, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            port.dtr = true
            port.rts = true

            _isConnected.value = true
            Log.i(TAG, "BU-353 connected at $BAUD baud — GPS-gated recording active")

            val readBuf = ByteArray(1024)
            val sb = StringBuilder()
            val ascii = Charset.forName("US-ASCII")

            while (currentCoroutineContext().isActive) {
                val n = try { port.read(readBuf, 200) } catch (_: Exception) { -1 }
                if (n != null && n > 0) {
                    val chunk = String(readBuf, 0, n, ascii)
                    for (c in chunk) {
                        if (c == '\n') {
                            val line = sb.toString().trim()
                            sb.setLength(0)
                            if (line.isNotEmpty()) handleLine(line)
                        } else if (c != '\r') {
                            sb.append(c)
                        }
                    }
                } else {
                    yield()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "USB read error: ${e.message}")
        } finally {
            _isConnected.value = false
            try { port.close() } catch (_: Exception) {}
            try { connection.close() } catch (_: Exception) {}
            Log.i(TAG, "BU-353 disconnected")
        }
    }

    // ---- NMEA parsing --------------------------------------------------------

    private fun handleLine(line: String) {
        // Accept only $GPRMC — the BU-353 also outputs $GNRMC for the same fix,
        // which would double the apparent rate to ~20 Hz. Pinning to GPRMC gives
        // clean 10 Hz output with one fix per GPS epoch.
        if (!line.startsWith("\$GPRMC,")) return
        if (!checksumOk(line)) return
        parseRmc(line)?.let { fix -> _fixes.tryEmit(fix) }
    }

    private fun checksumOk(s: String): Boolean {
        val star = s.lastIndexOf('*')
        if (star <= 0 || star + 3 > s.length) return false
        var cs = 0
        for (i in 1 until star) cs = cs xor s[i].code
        val want = s.substring(star + 1, star + 3).uppercase()
        val have = "%02X".format(cs)
        return want == have
    }

    private fun parseRmc(s: String): UsbGpsFix? {
        // $--RMC,hhmmss.sss,A,llll.ll,N/S,yyyyy.yy,E/W,speed(knots),course,...*CS
        val star = s.indexOf('*').let { if (it < 0) s.length else it }
        val f = s.substring(1, star).split(',')
        // fields: [0]=--RMC [1]=time [2]=status [3]=lat [4]=N/S [5]=lon [6]=E/W [7]=speed(knots)
        if (f.size < 7) return null
        if (f[2] != "A") return null   // 'A' = Active / valid fix

        val lat = dmToDeg(f[3], f[4]) ?: return null
        val lon = dmToDeg(f[5], f[6]) ?: return null

        // Speed: NMEA RMC gives speed in knots; convert to m/s
        val speedMps = f.getOrNull(7)?.toDoubleOrNull()
            ?.let { knots -> knots * 0.514444 }

        return UsbGpsFix(
            lat = lat,
            lon = lon,
            speedMps = speedMps,
            utcMs = System.currentTimeMillis()
        )
    }

    /** Convert NMEA DDMM.MMMM / DDDMM.MMMM + hemisphere to decimal degrees. */
    private fun dmToDeg(dm: String?, hemi: String?): Double? {
        if (dm.isNullOrBlank() || hemi.isNullOrBlank()) return null
        val dot = dm.indexOf('.')
        if (dot < 2) return null
        // Longitude has 3 degree digits; latitude has 2
        val degDigits = if (dot > 4) 3 else 2
        val deg = dm.substring(0, degDigits).toIntOrNull() ?: return null
        val min = dm.substring(degDigits).toDoubleOrNull() ?: return null
        var v = deg + (min / 60.0)
        if (hemi.equals("S", true) || hemi.equals("W", true)) v = -v
        return v
    }

    // ---- Helpers -------------------------------------------------------------

    private fun findProlific(): UsbDevice? =
        usb.deviceList.values.firstOrNull { it.vendorId == VENDOR_PROLIFIC }

    private fun requestPermission(device: UsbDevice) {
        val intent = Intent(ACTION_USB_PERMISSION).apply { `package` = context.packageName }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pi = PendingIntent.getBroadcast(context, 0, intent, flags)
        usb.requestPermission(device, pi)
    }
}
