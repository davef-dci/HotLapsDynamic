package com.hotlaps.dynamic.ui.calibration

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hotlaps.dynamic.AccentLime
import com.hotlaps.dynamic.HotLapsApp
import com.hotlaps.dynamic.data.CalibRepo
import com.hotlaps.dynamic.data.CalibState
import com.hotlaps.dynamic.data.CalibrationMath
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val ReadyBg = Color(0xFF1B5E20)
private val ArmedBg = Color(0xFFFFB300)

/**
 * Calibration status and overrides. Nobody HAS to visit this screen: the app calibrates itself
 * on the first clean straight-line pull (AutoCalibrator, in RecordingEngine) and re-arms when the
 * phone is moved. Here you can see the status, check it with live bars, re-arm by hand, or set it
 * in the garage without driving (the app detects flat/upright itself; at most one question).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibrateScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as HotLapsApp
    val calibRepo = remember(context) { CalibRepo(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val engine = app.recordingEngine
    DisposableEffect(engine) {
        engine.acquire("calibrate-screen")
        onDispose { engine.release("calibrate-screen") }
    }
    val status by engine.calibStatus.collectAsState()
    val gravity by engine.gravity.collectAsState()
    val liveLat by app.driveViewModel.latG.collectAsState()
    val liveLong by app.driveViewModel.longG.collectAsState()
    val calib by calibRepo.state.collectAsStateWithLifecycle(initialValue = CalibState(null, null))

    var showManual by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }

    fun saveManual(p: CalibrationMath.Preset) {
        scope.launch {
            calibRepo.save(p.vec, CalibrationMath.SOURCE_PRESET, p.label, gravity)
            showManual = false
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar("Calibration set: ${p.label}")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Calibration") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { pad ->
        Column(
            modifier = Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            StatusBanner(status.ready, status.message, calib)

            LiveBars(lat = liveLat, long = liveLong)

            Text(
                "Calibration is automatic: the first time you accelerate firmly in a straight line " +
                        "(pit exit is perfect), the app learns which way is forward. If the phone is " +
                        "moved, it recalibrates itself on the next straight-line pull.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedButton(
                onClick = {
                    scope.launch {
                        calibRepo.arm()
                        snackbar.currentSnackbarData?.dismiss()
                        snackbar.showSnackbar("Will recalibrate on your next straight-line pull")
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) { Text("Recalibrate on next drive", fontSize = 16.sp) }

            OutlinedButton(
                onClick = { showManual = !showManual },
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) { Text(if (showManual) "Hide" else "Set without driving", fontSize = 16.sp) }

            if (showManual) ManualPanel(gravity = gravity, onSet = ::saveManual)

            TextButton(
                onClick = { showClearDialog = true },
                enabled = calib.vec != null,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) { Text("Clear calibration", color = MaterialTheme.colorScheme.error) }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear calibration?") },
            text = { Text("The app will recalibrate itself on your next straight-line pull.") },
            confirmButton = {
                TextButton(onClick = {
                    showClearDialog = false
                    scope.launch {
                        calibRepo.clear()
                        snackbar.showSnackbar("Calibration cleared")
                    }
                }) { Text("Clear", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showClearDialog = false }) { Text("Cancel") } }
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Status banner: READY / ARMED
// ---------------------------------------------------------------------------------------------

@Composable
private fun StatusBanner(ready: Boolean, message: String, calib: CalibState) {
    val bg = if (ready) ReadyBg else ArmedBg
    val fg = if (ready) Color.White else Color.Black
    Surface(color = bg, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (ready) Icons.Filled.CheckCircle else Icons.Filled.HourglassTop,
                    contentDescription = null, tint = fg, modifier = Modifier.size(26.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(if (ready) "READY" else "ARMED", color = fg, fontWeight = FontWeight.Bold, fontSize = 22.sp)
            }
            Text(
                if (ready) calib.summary else message,
                color = fg, fontSize = 15.sp, fontWeight = FontWeight.Medium
            )
            if (ready) {
                calib.savedAtEpochMs?.let {
                    Text(
                        "Set " + SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(it)),
                        color = fg, fontSize = 13.sp
                    )
                }
            } else {
                Text("Recording works meanwhile; G-forces are exact once it locks in.", color = fg, fontSize = 13.sp)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Live bars
// ---------------------------------------------------------------------------------------------

@Composable
private fun LiveBars(lat: Float, long: Float) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CenteredBar("Lateral", "left", "right", lat)
        CenteredBar("Longitudinal", "brake", "accelerate", long)
        Text(
            "Parked: both bars centred. Accelerating moves the longitudinal bar right; " +
                    "turning right moves the lateral bar right.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun CenteredBar(title: String, negLabel: String, posLabel: String, valueG: Float, rangeG: Float = 1.0f) {
    val frac = (valueG / rangeG).coerceIn(-1f, 1f)
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("%+.2f g".format(valueG), fontWeight = FontWeight.Medium)
        }
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .height(22.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
        ) {
            val half = maxWidth / 2
            val barW = half * kotlin.math.abs(frac)
            Box(
                Modifier
                    .offset(x = if (frac >= 0) half else half - barW)
                    .width(barW)
                    .fillMaxHeight()
                    .background(AccentLime, RoundedCornerShape(6.dp))
            )
            Box(
                Modifier
                    .offset(x = half - 1.dp)
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.onSurface)
            )
        }
        Row(Modifier.fillMaxWidth()) {
            Text("← $negLabel", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text("$posLabel →", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Set without driving
// ---------------------------------------------------------------------------------------------

@Composable
private fun ManualPanel(gravity: FloatArray?, onSet: (CalibrationMath.Preset) -> Unit) {
    fun preset(id: String) = CalibrationMath.PRESETS.first { it.id == id }

    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Put the phone in its mount first.", fontWeight = FontWeight.SemiBold)
            when {
                gravity == null -> Text("Waiting for the phone's sensors…")

                CalibrationMath.isUpright(gravity) -> {
                    Text("Upright mount detected (screen facing the driver). The back of the phone faces forward.")
                    Button(
                        onClick = { onSet(preset("back")) },
                        modifier = Modifier.fillMaxWidth().height(56.dp)
                    ) { Text("Use this", fontSize = 16.sp) }
                }

                else -> {
                    // Flat: one question. A screen-down mount mirrors left/right.
                    val screenDown = gravity[2] < 0f
                    Text("The phone is lying flat. Which way does the TOP of the phone point?")
                    CompassPicker(
                        onForward = { onSet(preset("top")) },
                        onBackward = { onSet(preset("bottom")) },
                        onLeft = { onSet(preset(if (screenDown) "left" else "right")) },
                        onRight = { onSet(preset(if (screenDown) "right" else "left")) }
                    )
                }
            }
        }
    }
}

@Composable
private fun CompassPicker(
    onForward: () -> Unit,
    onBackward: () -> Unit,
    onLeft: () -> Unit,
    onRight: () -> Unit
) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        DirButton("To the front", Icons.Filled.ArrowUpward, onForward)
        Row(verticalAlignment = Alignment.CenterVertically) {
            DirButton("Left", Icons.AutoMirrored.Filled.ArrowBack, onLeft)
            Column(
                Modifier.padding(horizontal = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Filled.DirectionsCar, contentDescription = null, modifier = Modifier.size(44.dp))
                Text("seen from above", fontSize = 11.sp, textAlign = TextAlign.Center)
            }
            DirButton("Right", Icons.AutoMirrored.Filled.ArrowForward, onRight)
        }
        DirButton("To the back", Icons.Filled.ArrowDownward, onBackward)
    }
}

@Composable
private fun DirButton(label: String, icon: ImageVector, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.padding(4.dp).heightIn(min = 52.dp)) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.width(6.dp))
        Text(label)
    }
}
