// app/src/main/java/com/hotlaps/dynamic/ui/calibration/CalibrateScreen.kt
package com.hotlaps.dynamic.ui.calibration

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hotlaps.dynamic.data.CalibRepo
import kotlinx.coroutines.launch
import kotlin.math.sqrt
import kotlinx.coroutines.delay


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibrateScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val calibRepo = remember(context) { CalibRepo(context) }
    val scope = rememberCoroutineScope()

    // --- UI state
    val samplesTarget = 200
    var collecting by remember { mutableStateOf(false) }
    var collected by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf("Ready to calibrate") }
    var forwardVec by remember { mutableStateOf<FloatArray?>(null) }

    // Countdown before we begin sampling.
    // null = not counting down, otherwise 3..1 displayed to the user.
    var countdown by remember { mutableStateOf<Int?>(null) }

    var hasLinearAccel by remember { mutableStateOf(false) }
    var usingAccelFallback by remember { mutableStateOf(false) }

    // EMA smoothing (for linear accel)
    var sX by remember { mutableStateOf(0f) }
    var sY by remember { mutableStateOf(0f) }
    var sZ by remember { mutableStateOf(0f) }
    val alpha = 0.20f

    // Gravity estimate (for accelerometer fallback)
    var gX by remember { mutableStateOf(0f) }
    var gY by remember { mutableStateOf(0f) }
    var gZ by remember { mutableStateOf(0f) }
    val gravityAlpha = 0.10f   // slower; tracks gravity not quick throttle blips

    // sample buffers
    val xs = remember { mutableStateListOf<Float>() }
    val ys = remember { mutableStateListOf<Float>() }
    val zs = remember { mutableStateListOf<Float>() }

    // Subscribe to sensor only while collecting
    DisposableEffect(collecting) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val lin = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        val usingLinear = lin != null
        hasLinearAccel = usingLinear
        usingAccelFallback = !usingLinear && accel != null

        val sensorToUse: Sensor? = when {
            usingLinear -> lin
            accel != null -> accel
            else -> null
        }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                if (!collecting) return
                if (sensorToUse == null) return

                if (usingLinear && e.sensor.type != Sensor.TYPE_LINEAR_ACCELERATION) return
                if (!usingLinear && e.sensor.type != Sensor.TYPE_ACCELEROMETER) return

                val rawX = e.values[0]
                val rawY = e.values[1]
                val rawZ = e.values[2]

                // Get linear acceleration: direct from sensor if we have it,
                // otherwise derive from accelerometer by subtracting gravity estimate.
                val (linX, linY, linZ) =
                    if (usingLinear) {
                        Triple(rawX, rawY, rawZ)
                    } else {
                        // Update gravity low-pass
                        gX = ema(gX, rawX, gravityAlpha)
                        gY = ema(gY, rawY, gravityAlpha)
                        gZ = ema(gZ, rawZ, gravityAlpha)
                        // High-pass: accel - gravity
                        Triple(rawX - gX, rawY - gY, rawZ - gZ)
                    }

                // Smooth for stability
                sX = ema(sX, linX, alpha)
                sY = ema(sY, linY, alpha)
                sZ = ema(sZ, linZ, alpha)

                xs.add(sX); ys.add(sY); zs.add(sZ)
                collected = xs.size
                status = "Collecting… $collected / $samplesTarget"

                if (collected >= samplesTarget) {
                    // Mean vector → unit forward vector
                    val mx = xs.average().toFloat()
                    val my = ys.average().toFloat()
                    val mz = zs.average().toFloat()
                    forwardVec = normalize3(mx, my, mz)
                    collecting = false
                    status = if (forwardVec != null)
                        "Calibrated ✓  [%.2f, %.2f, %.2f]".format(
                            forwardVec!![0], forwardVec!![1], forwardVec!![2]
                        )
                    else
                        "Calibration failed; try again."
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        if (collecting && sensorToUse != null) {
            sm.registerListener(
                listener,
                sensorToUse,
                SensorManager.SENSOR_DELAY_GAME
            )
        }
        onDispose { sm.unregisterListener(listener) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Calibrate Accelerometers") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { pad ->
        Column(
            modifier = Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Mount device in its normal driving orientation.\n\n" +
                        "1) Find a safe, straight section.\n" +
                        "2) Press Start and smoothly accelerate straight for ~4s.\n" +
                        "3) When it says Calibrated ✓, tap Use Calibration.",
                textAlign = TextAlign.Start,
                lineHeight = 20.sp
            )

            val modeText = when {
                hasLinearAccel -> "Using linear acceleration sensor"
                usingAccelFallback -> "Linear accel not available; using accelerometer fallback"
                else -> "No suitable acceleration sensor found"
            }
            Text(modeText, style = MaterialTheme.typography.bodySmall)

            if (!hasLinearAccel && !usingAccelFallback) {
                Text(
                    "Your device doesn’t report usable acceleration sensors. " +
                            "Calibration may not work on this device.",
                    color = MaterialTheme.colorScheme.error
                )
            }

            Text(status, style = MaterialTheme.typography.titleMedium)
            if (collecting) {
                LinearProgressIndicator(
                    progress = (collected / samplesTarget.toFloat()).coerceIn(0f, 1f),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // -----------------------------
// BIG, CAR-FRIENDLY BUTTON AREA
// -----------------------------

// A shared modifier so all buttons are large and easy to tap in the car.
// Adjust height if you want: 64.dp / 72.dp / 80.dp etc.
            val bigButtonMod = Modifier
                .fillMaxWidth()
                .height(72.dp)

// Convenience flags to keep our enable/disable rules readable.
            val isCountingDown = countdown != null

// Show a large countdown text while we wait to start collecting.
// This gives the driver time to get hands back on the wheel.
            if (isCountingDown) {
                val n = countdown ?: 0

                Text(
                    text = "Starting in $n…",
                    style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.fillMaxWidth()
                )

                // Optional: small visual progress during the countdown.
                // (You can delete this indicator if you prefer.)
                LinearProgressIndicator(
                    progress = ((6 - n) / 6f).coerceIn(0f, 1f),
                    modifier = Modifier.fillMaxWidth()
                )
            }

// ----------------------
// START CALIBRATION BUTTON
// ----------------------
//
// Behavior:
// 1) User taps Start
// 2) We clear buffers + reset filters immediately
// 3) We do a 3-2-1 countdown
// 4) ONLY AFTER countdown ends, we set collecting = true
//
// NOTE: Your sensor subscription is in DisposableEffect(collecting),
// so sensors won't begin streaming until collecting flips true.
// This is exactly what we want (pause first, then collect).
            Button(
                enabled = !collecting && !isCountingDown,
                modifier = bigButtonMod,
                onClick = {

                    // Clear old samples so we start fresh
                    xs.clear(); ys.clear(); zs.clear()
                    collected = 0

                    // Clear last computed vector until we re-finish a new run
                    forwardVec = null

                    // Update status immediately so user gets feedback
                    status = "Get ready…"

                    // Reset filters so EMA/gravity estimates don't carry from a prior run
                    sX = 0f; sY = 0f; sZ = 0f
                    gX = 0f; gY = 0f; gZ = 0f

                    // Start countdown at 3
                    countdown = 5

                    // Run countdown asynchronously
                    scope.launch {
                        // Count down: 3 -> 2 -> 1
                        while ((countdown ?: 0) > 1) {
                            delay(650) // tweak this delay to taste (650..1000ms)
                            countdown = (countdown ?: 2) - 1
                        }

                        // Hold briefly on "1" so it feels intentional
                        delay(650)

                        // Countdown finished; hide countdown UI
                        countdown = null

                        // Begin sampling NOW (sensor subscription will activate here)
                        status = "Collecting… 0 / $samplesTarget"
                        collecting = true
                    }
                }
            ) {
                // Bigger text helps readability in-car
                Text("Start Calibration", fontSize = 20.sp)
            }

// --------------------
// (NO CANCEL BUTTON)
// --------------------
// You requested removing Cancel because calibration is too fast to use it.
// If later you want an emergency stop, we can add a long-press abort,
// but for now we keep the UI clean and large.


// Save the computed forward vector into CalibRepo.
// Only enabled once calibration has produced a forwardVec,
// and while we're not currently collecting / counting down.
            Button(
                enabled = forwardVec != null && !collecting && countdown == null,
                modifier = bigButtonMod,
                onClick = {
                    val vec = forwardVec ?: return@Button
                    scope.launch {
                        calibRepo.save(vec)
                        status = "Saved ✓  (You can go back)"
                    }
                }
            ) {
                Text("Use Calibration", fontSize = 20.sp)
            }



            // ----------------------------------------
            // PRESET POSITIONS (no driving required)
            // ----------------------------------------
            // Each button saves a known forward vector directly, bypassing the
            // sensor-based calibration run entirely.  Useful when:
            //   • The phone is in a well-known fixed mount (e.g. flat on the dash)
            //   • The sensor calibration routine doesn't work reliably on this device
            //
            // The forward vector is expressed in the phone's sensor frame:
            //   +Y = toward top of phone
            //   -Y = toward bottom of phone
            //   +X = toward right edge of phone
            //   -X = toward left edge of phone
            //
            // These work for any physical tilt (flat, upright, angled) — only the
            // edge-of-phone → car-forward relationship matters.

            HorizontalDivider()

            Text(
                "— or choose a known position —",
                style = MaterialTheme.typography.labelLarge
            )
            Text(
                "Pick whichever edge of your phone faces the front of the car. " +
                "The app reads the live gravity sensor to handle tilt automatically, " +
                "so this works whether the phone is flat, upright, or at any angle — " +
                "as long as the chosen edge keeps pointing forward.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Start
            )

            val presetButtonMod = Modifier
                .fillMaxWidth()
                .height(56.dp)

            val presets = listOf(
                CalibPreset("Top of phone  →  front of car",    floatArrayOf( 0f,  1f, 0f)),
                CalibPreset("Bottom of phone  →  front of car", floatArrayOf( 0f, -1f, 0f)),
                CalibPreset("Right edge  →  front of car",      floatArrayOf( 1f,  0f, 0f)),
                CalibPreset("Left edge  →  front of car",       floatArrayOf(-1f,  0f, 0f)),
            )

            presets.forEach { preset ->
                OutlinedButton(
                    enabled = !collecting && countdown == null,
                    modifier = presetButtonMod,
                    onClick = {
                        scope.launch {
                            calibRepo.save(preset.vec)
                            status = "Preset saved ✓  ${preset.label}"
                        }
                    }
                ) {
                    Text(preset.label, fontSize = 15.sp)
                }
            }

            HorizontalDivider()

            // Delete calibration from storage.
            // This can be useful if you want to force a re-calibration.
            OutlinedButton(
                enabled = !collecting && countdown == null,
                modifier = bigButtonMod,
                onClick = {
                    scope.launch {
                        calibRepo.clear()
                        status = "Calibration deleted."
                    }
                }
            ) {
                Text("Delete Calibration", fontSize = 18.sp)
            }


        }
    }
}

private data class CalibPreset(val label: String, val vec: FloatArray)

private fun ema(prev: Float, x: Float, alpha: Float): Float =
    if (prev == 0f) x else (alpha * x + (1f - alpha) * prev)

private fun normalize3(x: Float, y: Float, z: Float): FloatArray? {
    val mag = sqrt((x * x + y * y + z * z).toDouble()).toFloat()
    if (mag < 1e-3f) return null
    return floatArrayOf(x / mag, y / mag, z / mag)
}
