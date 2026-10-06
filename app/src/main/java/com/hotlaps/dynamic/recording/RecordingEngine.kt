package com.hotlaps.dynamic.recording

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import com.hotlaps.dynamic.data.PullCalibrator
import com.hotlaps.dynamic.data.CalibRepo
import com.hotlaps.dynamic.data.CalibState
import com.hotlaps.dynamic.data.CalibrationMath
import com.hotlaps.dynamic.data.SettingsRepo
import com.hotlaps.dynamic.data.SmoothingLevel
import com.hotlaps.dynamic.util.GForceSmoother
import com.hotlaps.dynamic.util.RecordingHealth
import com.hotlaps.dynamic.util.UsbPuckGpsSource
import com.hotlaps.dynamic.util.GpsCompareLog
import com.hotlaps.dynamic.viewmodel.DriveViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Owns everything that produces telemetry: accelerometer + gravity sensors, phone GPS,
 * the USB GPS puck, and the 20 Hz loop that projects, smooths and records samples.
 *
 * This used to live inside the drive screen (GGScreen), so leaving that screen (Settings,
 * track picker...) silently stopped sampling while the event still said "Recording".
 * Now it lives for the whole process and runs while anyone holds it:
 *  - the drive screen, while visible (live G-G display)
 *  - [RecordingService], while an event is recording (screen off / app in background)
 *
 * Everything runs on its own thread ("RecordingEngine"), never the UI thread: in testing,
 * screen redraws (waking the phone, reopening the app, switching screens) blocked the main
 * thread for up to 2.5 s, and sampling with it. DriveViewModel's recording methods are
 * synchronized for this.
 */
class RecordingEngine(
    private val appContext: Context,
    private val drive: DriveViewModel
) {
    private companion object {
        const val TAG = "RecordingEngine"
        const val TICK_MS = 50L          // ~20 Hz world tick
        const val G_CLAMP = 2.0f         // +/- 2g should be plenty
    }

    private val holders = mutableSetOf<String>()
    private var scope: CoroutineScope? = null

    /** Dedicated thread for sensor/GPS callbacks and the tick loop. */
    private val thread = HandlerThread("RecordingEngine", Process.THREAD_PRIORITY_FOREGROUND)
        .apply { start() }
    private val handler = Handler(thread.looper)
    private val dispatcher = handler.asCoroutineDispatcher("RecordingEngine")

    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val calibRepo = CalibRepo(appContext)
    private val settingsRepo = SettingsRepo(appContext)
    val usbGps = UsbPuckGpsSource(appContext)

    // Linear acceleration accumulated since the last tick (device axes, m/s^2). The tick uses
    // the MEAN of all readings in its window, not just the latest one: point-sampling a
    // vibrating car aliases engine/road vibration into false low-frequency G.
    private var accelSumX = 0f
    private var accelSumY = 0f
    private var accelSumZ = 0f
    private var accelCount = 0
    private var accelX = 0f
    private var accelY = 0f
    private var accelZ = 0f
    private var warnedDegenerateAxis = false
    private var gravX = 0f
    private var gravY = 0f
    private var gravZ = 0f

    // ---- State for the UI ---------------------------------------------------

    /** Increments every tick; the G-G trail samples on this. */
    private val _ticks = MutableStateFlow(0L)
    val ticks: StateFlow<Long> get() = _ticks

    /** Raw phone GPS fix (debug display), even while the puck owns recording. */
    private val _phoneGpsLat = MutableStateFlow(0.0)
    val phoneGpsLat: StateFlow<Double> get() = _phoneGpsLat
    private val _phoneGpsLon = MutableStateFlow(0.0)
    val phoneGpsLon: StateFlow<Double> get() = _phoneGpsLon

    /**
     * Calibration status for the UI.
     *  - ready: a calibration is in use (maybe with [warning]: the phone may have moved since)
     *  - calibrating: the driver pressed Calibrate; waiting for a straight-line pull
     *  - otherwise: not calibrated (axes are a guess from how the phone is tilted)
     */
    data class CalibStatus(
        val ready: Boolean,
        val message: String,
        val warning: Boolean = false
    )

    /** Calibrate button flow: 3-2-1 countdown, a 4 s straight-line pull, then the result. */
    sealed class CalPhase {
        object Idle : CalPhase()
        data class Countdown(val secondsLeft: Int) : CalPhase()
        data class Pull(val progress: Float) : CalPhase()
        data class Done(val ok: Boolean, val message: String) : CalPhase()
    }

    private val _calPhase = MutableStateFlow<CalPhase>(CalPhase.Idle)
    val calPhase: StateFlow<CalPhase> get() = _calPhase

    /** When Calibrate was tapped (0 = not calibrating). Timing runs on the engine's tick loop. */
    @Volatile private var calStartMs = 0L
    private var calDoneMs = 0L
    private val pullTicks = ArrayList<PullCalibrator.Tick>()

    /**
     * Gravity during the countdown (car still): the "phone moved?" reference. Gravity measured
     * during the pull leans with the acceleration, which made the check report "moved 17 deg"
     * as soon as the car parked (2026-10-05).
     */
    private val countdownGravity = ArrayList<FloatArray>()

    /** Calibrate button: countdown, then the driver accelerates or brakes firmly in a straight line. */
    fun startCalibration() {
        pullTicks.clear()
        countdownGravity.clear()
        calStartMs = System.currentTimeMillis()
        _calPhase.value = CalPhase.Countdown(3)
        RecordingHealth.log("CALIBRATION started (countdown)")
    }

    fun cancelCalibration() {
        calStartMs = 0L
        _calPhase.value = CalPhase.Idle
    }

    private val _calibStatus = MutableStateFlow(CalibStatus(false, "Starting…"))
    val calibStatus: StateFlow<CalibStatus> get() = _calibStatus


    /** Latest gravity vector (phone frame, m/s^2): calibration screen + mount-change check. */
    private val _gravity = MutableStateFlow<FloatArray?>(null)
    val gravity: StateFlow<FloatArray?> get() = _gravity

    /** Emits once per USB puck fix (debug Hz display). */
    private val _puckFixes = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
    val puckFixes: SharedFlow<Unit> get() = _puckFixes

    /** Debug-page override for the moving-average window (null = use the preset). */
    private val smoothingWindowOverride = MutableStateFlow<Int?>(null)

    // ---- Holders ------------------------------------------------------------

    @MainThread
    fun acquire(holder: String) {
        if (holders.add(holder)) {
            Log.d(TAG, "acquire($holder) holders=$holders")
            if (holders.size == 1) start()
        }
    }

    @MainThread
    fun release(holder: String) {
        if (holders.remove(holder)) {
            Log.d(TAG, "release($holder) holders=$holders")
            if (holders.isEmpty()) stop()
        }
    }

    fun setSmoothingWindow(samples: Int?) {
        smoothingWindowOverride.value = samples
    }

    // ---- Lifecycle ----------------------------------------------------------

    private fun start() {
        val s = CoroutineScope(SupervisorJob() + dispatcher)
        scope = s
        RecordingHealth.log("ENGINE start")

        registerSensors()
        usbGps.start()

        // Puck connection decides who drives GPS (and sample timing)
        s.launch {
            combine(usbGps.isConnected, GpsCompareLog.enabled) { c, cmp -> c to cmp }
                .collect { (connected, compare) ->
                    drive.setUsingExternalGps(connected)
                    // Only power up the internal GPS radio when the puck is not connected,
                    // unless the GPS compare test is logging both
                    if (connected && !compare) stopPhoneGps() else startPhoneGps()
                }
        }

        // GPS-gated sampling: each puck fix drives one sample (replaces the timer trigger)
        s.launch {
            usbGps.fixes.collect { fix ->
                usbGps.onFixConsumed(fix)
                GpsCompareLog.puck(fix)
                _puckFixes.tryEmit(Unit)
                drive.updateGps(lat = fix.lat, lon = fix.lon, speedMps = fix.speedMps)
                drive.recordCurrentSample()
                drive.updateCornerCaptureState()
            }
        }

        // 20 Hz loop, restarted when calibration or smoothing settings change
        s.launch {
            combine(calibRepo.state, settingsRepo.smoothingLevel, smoothingWindowOverride) { calib, level, window ->
                Triple(calib, SmoothingLevel.fromIndex(level), window)
            }.collectLatest { (calib, level, window) ->
                runTickLoop(calib, level, window)
            }
        }
    }

    private fun stop() {
        RecordingHealth.log("ENGINE stop")
        sensorManager.unregisterListener(sensorListener)
        stopPhoneGps()
        usbGps.stop()
        drive.setUsingExternalGps(false)
        scope?.cancel()
        scope = null
    }

    // ---- 20 Hz loop ---------------------------------------------------------

    private suspend fun runTickLoop(calib: CalibState, level: SmoothingLevel, windowOverride: Int?) {
        val g = SensorManager.GRAVITY_EARTH
        val smoother = GForceSmoother(
            tauMs = if (level == SmoothingLevel.Off) null else level.tauMs.coerceAtLeast(1).toFloat(),
            maWindowSize = windowOverride ?: level.windowSize
        )
        RecordingHealth.resetTicks()

        // Mount-change check runs only while parked: in long hard corners the gravity estimate
        // can lean (especially on phones without a gyroscope). It only WARNS: a calibration is
        // never thrown away automatically (2026-10-05: moving the phone from desk to dash
        // discarded a good calibration and left the app waiting for a pull that never came).
        var parkedMs = 0L
        var mountMoved = false

        while (true) {
            delay(TICK_MS)
            RecordingHealth.onTick(
                label = "live",
                recording = drive.recordingState.value == DriveViewModel.RecordingState.Recording
            )

            // Mean linear acceleration over this tick's window (hold the last value if none arrived)
            if (accelCount > 0) {
                accelX = accelSumX / accelCount
                accelY = accelSumY / accelCount
                accelZ = accelSumZ / accelCount
                accelSumX = 0f; accelSumY = 0f; accelSumZ = 0f
                accelCount = 0
            }

            val gravityNow = if (gravX != 0f || gravY != 0f || gravZ != 0f)
                floatArrayOf(gravX, gravY, gravZ) else null

            if (drive.speedMps.value < 1.0) parkedMs += TICK_MS else parkedMs = 0L
            if (!mountMoved && calib.vec != null && parkedMs >= 2_000L) {
                CalibrationMath.mountChangeDeg(calib.gravity, gravityNow)
                    ?.takeIf { it > CalibrationMath.MOUNT_CHANGE_DEG }
                    ?.let { deg ->
                        mountMoved = true
                        RecordingHealth.log("CALIBRATION phone moved %.0f deg since calibration; warning shown (calibration kept)".format(deg))
                    }
            }

            // Forward axis: the saved calibration, else a guess from how the phone is tilted
            val calibForward = calib.vec?.let { normalize3(it[0], it[1], it[2]) }
                ?: CalibrationMath.guessForward(gravityNow)

            val status = when {
                calib.vec == null -> CalibStatus(false, "Not calibrated: tap Calibrate, then accelerate firmly in a straight line.")
                mountMoved -> CalibStatus(true, "Phone may have moved since calibration: tap to recalibrate.", warning = true)
                else -> CalibStatus(true, "Ready · ${calib.summary}")
            }
            if (_calibStatus.value != status) _calibStatus.value = status

            // Calibrate button: countdown, 4 s pull, check against GPS, save or explain why not
            val now = System.currentTimeMillis()
            val calStart = calStartMs
            if (calStart != 0L) {
                val elapsed = now - calStart
                when {
                    elapsed < PullCalibrator.COUNTDOWN_MS -> {
                        if (gravityNow != null && drive.speedMps.value < 1.0) countdownGravity.add(gravityNow)
                        val left = ((PullCalibrator.COUNTDOWN_MS - elapsed + 999) / 1000).toInt()
                        if (_calPhase.value != CalPhase.Countdown(left)) _calPhase.value = CalPhase.Countdown(left)
                    }
                    elapsed < PullCalibrator.COUNTDOWN_MS + PullCalibrator.PULL_MS -> {
                        if (gravityNow != null) {
                            pullTicks.add(
                                PullCalibrator.Tick(
                                    timeMs = now,
                                    accel = floatArrayOf(accelX, accelY, accelZ),
                                    gravity = gravityNow,
                                    speedMps = drive.speedMps.value,
                                    lat = drive.gpsLat.value,
                                    lon = drive.gpsLon.value
                                )
                            )
                        }
                        val p = (elapsed - PullCalibrator.COUNTDOWN_MS).toFloat() / PullCalibrator.PULL_MS
                        _calPhase.value = CalPhase.Pull(p)
                    }
                    else -> {
                        calStartMs = 0L
                        calDoneMs = now
                        when (val r = PullCalibrator.evaluate(pullTicks.toList())) {
                            is PullCalibrator.Result.Ok -> {
                                RecordingHealth.log(
                                    "CALIBRATION measured from straight-line %s: %.2f g, GPS %+.0f mph, forward=(%.2f, %.2f, %.2f)".format(
                                        if (r.braking) "braking" else "acceleration", r.meanG, r.speedChangeMph,
                                        r.forward[0], r.forward[1], r.forward[2]
                                    )
                                )
                                // Mount-change reference: gravity while still (countdown), else during the pull
                                val still = countdownGravity.takeIf { it.size >= 10 }?.let { g ->
                                    floatArrayOf(
                                        g.map { it[0] }.average().toFloat(),
                                        g.map { it[1] }.average().toFloat(),
                                        g.map { it[2] }.average().toFloat()
                                    )
                                }
                                scope?.launch { calibRepo.save(r.forward, CalibrationMath.SOURCE_MEASURED, null, still ?: r.gravity) }
                                _calPhase.value = CalPhase.Done(
                                    true,
                                    "Pull measured %.2f g. Check: brake gently and the dot should move down.".format(r.meanG)
                                )
                            }
                            is PullCalibrator.Result.Fail -> {
                                RecordingHealth.log("CALIBRATION rejected: ${r.reason}")
                                _calPhase.value = CalPhase.Done(false, r.reason)
                            }
                        }
                        pullTicks.clear()
                    }
                }
            } else if (_calPhase.value is CalPhase.Done && now - calDoneMs > 6_000L) {
                _calPhase.value = CalPhase.Idle
            }

            // Gravity vector -> "down"; "up" is opposite
            val down = normalize3(gravX, gravY, gravZ) ?: floatArrayOf(0f, 0f, 1f)
            val up = floatArrayOf(-down[0], -down[1], -down[2])

            // Level the forward axis: remove its vertical component so a tilted mount doesn't
            // mix vertical acceleration (bumps, kerbs, pitch) into longitudinal G
            val fUp = dot3(calibForward[0], calibForward[1], calibForward[2], up[0], up[1], up[2])
            val forward = normalize3(
                calibForward[0] - fUp * up[0],
                calibForward[1] - fUp * up[1],
                calibForward[2] - fUp * up[2]
            ) ?: run {
                // Calibrated "forward" points (almost) straight up/down: wrong preset for this mount
                if (!warnedDegenerateAxis) {
                    warnedDegenerateAxis = true
                    RecordingHealth.log("CALIBRATION forward axis is vertical for this mount; G axes invalid")
                }
                calibForward
            }

            // Right = up x forward (lateral axis)
            val rightRaw = cross(up, forward)
            val right = normalize3(rightRaw[0], rightRaw[1], rightRaw[2]) ?: floatArrayOf(1f, 0f, 0f)

            val longNow = dot3(accelX, accelY, accelZ, forward[0], forward[1], forward[2]) / g  // + accel, - brake
            val latNow = dot3(accelX, accelY, accelZ, right[0], right[1], right[2]) / g        // + right, - left

            // Raw values for logging: clamped, but before EMA/MA
            val rawLongG = longNow.coerceIn(-G_CLAMP, G_CLAMP)
            val rawLatG = latNow.coerceIn(-G_CLAMP, G_CLAMP)

            val smoothed = smoother.addSample(
                rawLatG = rawLatG,
                rawLongG = rawLongG,
                sampleTimeMs = System.currentTimeMillis()
            )

            drive.updateGForces(
                smoothedLat = smoothed.latG,
                smoothedLong = smoothed.longG,
                rawLat = rawLatG,
                rawLong = rawLongG
            )

            // Sample recording is gated by the GPS source:
            //  - External puck present -> each GPS fix triggers the sample (above)
            //  - Internal GPS          -> this timer triggers the sample
            if (!drive.usingExternalGps.value) {
                drive.recordCurrentSample()
                drive.updateCornerCaptureState()
            }

            if (gravityNow != null) _gravity.value = gravityNow
            _ticks.value++
        }
    }

    // ---- Sensors ------------------------------------------------------------

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            when (e.sensor.type) {
                Sensor.TYPE_LINEAR_ACCELERATION -> {
                    accelSumX += e.values[0]; accelSumY += e.values[1]; accelSumZ += e.values[2]
                    accelCount++
                }
                Sensor.TYPE_GRAVITY -> {
                    gravX = e.values[0]; gravY = e.values[1]; gravZ = e.values[2]
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun registerSensors() {
        sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)?.let {
            sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_GAME, handler)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)?.let {
            sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_GAME, handler)
        }
    }

    // ---- Phone GPS ----------------------------------------------------------

    private var phoneGpsActive = false

    private val locationListener = LocationListener { loc: Location ->
        GpsCompareLog.phone(loc)
        _phoneGpsLat.value = loc.latitude
        _phoneGpsLon.value = loc.longitude
        // When the puck is active it owns VM GPS exclusively at 10 Hz
        if (!drive.usingExternalGps.value) {
            drive.updateGps(
                lat = loc.latitude,
                lon = loc.longitude,
                speedMps = if (loc.hasSpeed()) loc.speed.toDouble() else null
            )
        }
    }

    @Synchronized
    private fun startPhoneGps() {
        if (phoneGpsActive) return
        val granted = ContextCompat.checkSelfPermission(
            appContext, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 200L, 0f, locationListener, thread.looper
            )
            phoneGpsActive = true
        } catch (e: SecurityException) {
            Log.e(TAG, "startPhoneGps", e)
        }
    }

    @Synchronized
    private fun stopPhoneGps() {
        if (!phoneGpsActive) return
        locationManager.removeUpdates(locationListener)
        phoneGpsActive = false
    }

    // ---- Vector math --------------------------------------------------------

    private fun normalize3(x: Float, y: Float, z: Float): FloatArray? {
        val n = kotlin.math.sqrt(x * x + y * y + z * z)
        if (n < 1e-4f) return null
        return floatArrayOf(x / n, y / n, z / n)
    }

    private fun dot3(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float): Float =
        ax * bx + ay * by + az * bz

    private fun cross(a: FloatArray, b: FloatArray): FloatArray = floatArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0]
    )
}
