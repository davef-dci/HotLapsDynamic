// app/src/main/java/com/hotlaps/dynamic/ui/calibration/CalibrateScreen.kt
package com.hotlaps.dynamic.ui.calibration

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.layout.*
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
    var hasLinearAccel by remember { mutableStateOf(false) }

    // EMA smoothing (light)
    var sX by remember { mutableStateOf(0f) }
    var sY by remember { mutableStateOf(0f) }
    var sZ by remember { mutableStateOf(0f) }
    val alpha = 0.20f

    // sample buffers
    val xs = remember { mutableStateListOf<Float>() }
    val ys = remember { mutableStateListOf<Float>() }
    val zs = remember { mutableStateListOf<Float>() }

    // Subscribe to sensor only while collecting
    DisposableEffect(collecting) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val lin = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        hasLinearAccel = lin != null

        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                if (!collecting || e.sensor.type != Sensor.TYPE_LINEAR_ACCELERATION) return
                // Smooth for stability
                sX = ema(sX, e.values[0], alpha)
                sY = ema(sY, e.values[1], alpha)
                sZ = ema(sZ, e.values[2], alpha)

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

        if (collecting && lin != null) {
            sm.registerListener(listener, lin, SensorManager.SENSOR_DELAY_GAME)
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

            if (!hasLinearAccel) {
                Text(
                    "Your device doesn’t report linear acceleration. " +
                            "You can still race using manual axis/flip (add later in Settings).",
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

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    enabled = !collecting,
                    onClick = {
                        xs.clear(); ys.clear(); zs.clear()
                        collected = 0
                        forwardVec = null
                        status = "Collecting… 0 / $samplesTarget"
                        collecting = true
                    }
                ) { Text("Start Calibration") }

                OutlinedButton(
                    enabled = collecting,
                    onClick = {
                        collecting = false
                        status = "Calibration cancelled"
                    }
                ) { Text("Cancel") }
            }

            Button(
                enabled = forwardVec != null && !collecting,
                onClick = {
                    val vec = forwardVec ?: return@Button
                    scope.launch {
                        calibRepo.save(vec)
                        status = "Saved ✓  (You can go back)"
                    }
                }
            ) { Text("Use Calibration") }
        }
    }
}

private fun ema(prev: Float, x: Float, alpha: Float): Float =
    if (prev == 0f) x else (alpha * x + (1f - alpha) * prev)

private fun normalize3(x: Float, y: Float, z: Float): FloatArray? {
    val mag = sqrt((x * x + y * y + z * z).toDouble()).toFloat()
    if (mag < 1e-3f) return null
    return floatArrayOf(x / mag, y / mag, z / mag)
}
