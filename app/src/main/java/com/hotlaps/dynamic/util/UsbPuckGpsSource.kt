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
        val utcMs: Long,                // wall-clock UTC ms — use directly as EventSample.utcMs
        /** When the GPS measured this fix (RMC time + date), UTC ms; null if not parseable. */
        val gpsTimeMs: Long? = null,
        /** RMC speed-over-ground as received (same as [speedMps]; logged by the GPS compare test). */
        val rmcSpeedMps: Double? = null
    )

    // ---- Public state --------------------------------------------------------

    private val _fixes = MutableSharedFlow<UsbGpsFix>(extraBufferCapacity = 32)
    /** Stream of validated GPS fixes at ~10 Hz when puck is connected. */
    val fixes: SharedFlow<UsbGpsFix> = _fixes

    private val _isConnected = MutableStateFlow(false)
    /** True while the puck is connected and producing data. */
    val isConnected: StateFlow<Boolean> = _isConnected

    private val _statusMessage = MutableStateFlow("USB GPS: not started")
    /** Diagnostic status — shown in the debug panel. */
    val statusMessage: StateFlow<String> = _statusMessage

    // ---- Internals -----------------------------------------------------------

    private val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var readJob: Job? = null
    private var scope: CoroutineScope? = null

    // Set when the user answers the USB permission dialog (allow OR deny). The grant itself is
    // read from usb.hasPermission() — the PendingIntent is immutable, so the extras are not reliable.
    @Volatile private var permissionAnswered = false

    // Device instance (deviceName changes on every re-plug) the user denied; not asked again
    // until the puck is unplugged and plugged back in.
    private var deniedDeviceName: String? = null

    // Permission broadcast receiver
    private val permReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            permissionAnswered = true
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

        _statusMessage.value = "USB GPS: scanning..."
        readJob = s.launch { runReader() }
    }

    fun stop() {
        readJob?.cancel()
        readJob = null
        scope?.cancel()
        scope = null
        _isConnected.value = false
        _statusMessage.value = "USB GPS: stopped"
        receivedFirstFix = false
        deniedDeviceName = null
        try { context.unregisterReceiver(permReceiver) } catch (_: Exception) {}
    }

    // ---- Core read loop ------------------------------------------------------

    private suspend fun runReader() {
        // Never gives up while recording: scan → permission → read → (unplug / USB reset) → scan.
        // A USB reset happens e.g. when power is plugged into / pulled from a charge-through hub;
        // the puck re-enumerates and must be picked up again without restarting the app.
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            val device = findProlific()
            if (device == null) {
                deniedDeviceName = null   // unplugged → ask again on the next plug-in
                attempt++
                val allDevices = usb.deviceList.values
                val msg = if (allDevices.isEmpty())
                    "USB GPS: no USB devices — plug in puck (attempt $attempt)"
                else
                    "USB GPS: puck not found. Devices: " + allDevices.joinToString { d ->
                        "${d.productName ?: "unknown"} [vid=0x%04X]".format(d.vendorId)
                    } + " (attempt $attempt)"
                _statusMessage.value = msg
                if (attempt == 1) Log.d(TAG, msg)   // log once to avoid spam
                kotlinx.coroutines.delay(2000)
                continue
            }
            attempt = 0

            if (!usb.hasPermission(device) && !awaitPermission(device)) {
                kotlinx.coroutines.delay(2000)
                continue
            }

            readFrom(device)
            kotlinx.coroutines.delay(1000)   // let a re-enumerating puck settle before rescanning
        }
    }

    /**
     * Shows the system "Allow access to USB device?" dialog and waits — with no timeout — until
     * the user answers or the puck is unplugged. Returns true if access was granted.
     */
    private suspend fun awaitPermission(device: UsbDevice): Boolean {
        if (deniedDeviceName == device.deviceName) {
            _statusMessage.value = "USB GPS: access was denied — unplug and re-plug the puck to be asked again"
            return false
        }
        _statusMessage.value = "USB GPS: puck found — tap OK on the \"Allow access\" prompt"
        permissionAnswered = false
        requestPermission(device)
        while (currentCoroutineContext().isActive) {
            if (usb.hasPermission(device)) return true
            if (permissionAnswered) {
                deniedDeviceName = device.deviceName
                val msg = "USB GPS: access denied — unplug and re-plug the puck to be asked again"
                _statusMessage.value = msg
                Log.w(TAG, msg)
                return false
            }
            if (findProlific()?.deviceName != device.deviceName) return false   // unplugged / reset
            kotlinx.coroutines.delay(200)
        }
        return false
    }

    /** Opens the puck and streams NMEA until it is unplugged or the port fails. */
    private suspend fun readFrom(device: UsbDevice) {
        val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
        if (driver == null) {
            val msg = "USB GPS: no serial driver for device (unexpected for PL2303)"
            _statusMessage.value = msg
            Log.w(TAG, msg)
            return
        }

        val connection = usb.openDevice(device)
        if (connection == null) {
            val msg = "USB GPS: could not open USB connection"
            _statusMessage.value = msg
            Log.w(TAG, msg)
            return
        }

        val port = driver.ports.firstOrNull()
        if (port == null) {
            val msg = "USB GPS: driver has no ports"
            _statusMessage.value = msg
            connection.close()
            return
        }

        try {
            port.open(connection)
            port.setParameters(BAUD, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            port.dtr = true
            port.rts = true

            _isConnected.value = true
            _statusMessage.value = "USB GPS: connected at $BAUD baud — waiting for fix..."
            Log.i(TAG, "BU-353 connected at $BAUD baud — GPS-gated recording active")

            val readBuf = ByteArray(1024)
            val sb = StringBuilder()
            val ascii = Charset.forName("US-ASCII")

            var lastPresenceCheck = System.currentTimeMillis()
            while (currentCoroutineContext().isActive) {
                // A read error used to be swallowed and retried forever (busy loop, puck
                // still shown as connected). Now it ends this connection and we rescan.
                val n = port.read(readBuf, 200)
                val now = System.currentTimeMillis()
                if (now - lastPresenceCheck > 1000) {
                    lastPresenceCheck = now
                    if (findProlific()?.deviceName != device.deviceName) {
                        _statusMessage.value = "USB GPS: puck unplugged — rescanning"
                        break
                    }
                }
                if (n > 0) {
                    diag.onBytes(n)
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
            _statusMessage.value = "USB GPS: connection lost (${e.message}) — rescanning"
        } finally {
            receivedFirstFix = false
            _isConnected.value = false
            try { port.close() } catch (_: Exception) {}
            try { connection.close() } catch (_: Exception) {}
            Log.i(TAG, "BU-353 disconnected")
        }
    }

    // ---- NMEA parsing --------------------------------------------------------

    private var receivedFirstFix = false

    private fun handleLine(line: String) {
        diag.onSentence(line.substringBefore(','))
        // Accept only $GPRMC — the BU-353 also outputs $GNRMC for the same fix,
        // which would double the apparent rate to ~20 Hz. Pinning to GPRMC gives
        // clean 10 Hz output with one fix per GPS epoch.
        if (!line.startsWith("\$GPRMC,")) return
        if (!checksumOk(line)) return
        parseRmc(line)?.let { fix ->
            if (!receivedFirstFix) {
                receivedFirstFix = true
                _statusMessage.value = "USB GPS: receiving fixes — 10 Hz active"
            }
            diag.onFix(fix, emitted = _fixes.tryEmit(fix))
        }
    }

    /** Called by the consumer (RecordingEngine) when it processes a fix: measures queueing delay. */
    fun onFixConsumed(fix: UsbGpsFix) = diag.onConsumed(System.currentTimeMillis() - fix.utcMs)

    // ---- Latency diagnostics (logged to the recording health log every 10 s) ---------------
    //
    // Three delays per fix: GPS measurement time (from the RMC sentence) -> arrival here ->
    // processed by the engine. Plus bytes/s against the link capacity and which sentence types
    // the puck sends, to find where GPS data falls behind the accelerometer.
    private val diag = LatencyDiag()

    private inner class LatencyDiag {
        private val windowMs = 10_000L
        private var windowStart = 0L
        private var bytes = 0L
        private var fixes = 0
        private var dropped = 0
        private val types = HashMap<String, Int>()
        private val gpsToApp = ArrayList<Long>()
        private val appToEngine = ArrayList<Long>()

        @Synchronized fun onBytes(n: Int) { bytes += n }

        @Synchronized fun onSentence(type: String) {
            if (type.startsWith("$")) types[type] = (types[type] ?: 0) + 1
        }

        @Synchronized fun onConsumed(delayMs: Long) { appToEngine.add(delayMs) }

        @Synchronized fun onFix(fix: UsbGpsFix, emitted: Boolean) {
            fixes++
            if (!emitted) dropped++
            fix.gpsTimeMs?.let { gpsToApp.add(fix.utcMs - it) }
            val now = fix.utcMs
            if (windowStart == 0L) windowStart = now
            if (now - windowStart >= windowMs) {
                report(now - windowStart)
                windowStart = now
                bytes = 0; fixes = 0; dropped = 0
                types.clear(); gpsToApp.clear(); appToEngine.clear()
            }
        }

        private fun stat(xs: List<Long>): String {
            if (xs.isEmpty()) return "n/a"
            val s = xs.sorted()
            return "med=${s[s.size / 2]} min=${s.first()} max=${s.last()}ms"
        }

        private fun report(spanMs: Long) {
            val secs = spanMs / 1000.0
            val bps = bytes / secs
            val linkBps = BAUD / 10.0 // 8N1: 10 bits per byte
            val typeList = types.entries.sortedBy { it.key }.joinToString(" ") { "${it.key.drop(1)}:${it.value}" }
            RecordingHealth.log(
                "PUCK %.1f Hz dropped=%d | gps->app %s | app->engine %s | %.0f B/s = %.0f%% of link | %s".format(
                    fixes / secs, dropped, stat(gpsToApp), stat(appToEngine), bps, 100 * bps / linkBps, typeList
                )
            )
        }
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
            utcMs = System.currentTimeMillis(),
            gpsTimeMs = rmcTimeMs(f.getOrNull(1), f.getOrNull(9)),
            rmcSpeedMps = speedMps
        )
    }

    /** RMC time "hhmmss.sss" + date "ddmmyy" -> UTC epoch ms. */
    private fun rmcTimeMs(time: String?, date: String?): Long? {
        if (time == null || date == null || time.length < 6 || date.length != 6) return null
        return try {
            val hh = time.substring(0, 2).toInt()
            val mm = time.substring(2, 4).toInt()
            val ss = time.substring(4).toDouble()
            val day = date.substring(0, 2).toInt()
            val mon = date.substring(2, 4).toInt()
            val yr = 2000 + date.substring(4, 6).toInt()
            val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
                clear()
                set(yr, mon - 1, day, hh, mm, 0)
            }
            cal.timeInMillis + Math.round(ss * 1000)
        } catch (_: Exception) {
            null
        }
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
