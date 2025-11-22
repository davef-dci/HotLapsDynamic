// GGUi.kt — Step 3: single file that holds the screen AND the drawing.
// For now, we use placeholders. Next steps will:
//  • Hook to Settings values
//  • Wire “Go!” to navigate here
//  • Feed real sensor data + trail

package com.hotlaps.dynamic.ui

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp

// For reading Settings the same way SettingsScreen does
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import com.hotlaps.dynamic.data.SettingsRepo

// --- GG wedge bands + diagonal labels (ported from HotLapMobile RacingScreen) ---
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.geometry.Size
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import androidx.compose.ui.unit.dp
import android.util.Log
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import com.hotlaps.dynamic.viewmodel.TrackSelectionViewModel
import androidx.compose.runtime.collectAsState

import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.content.Context

import androidx.core.app.ActivityCompat
import android.content.pm.PackageManager

import com.hotlaps.dynamic.viewmodel.DriveViewModel

import androidx.compose.runtime.LaunchedEffect
import com.hotlaps.dynamic.model.Event


import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import com.hotlaps.dynamic.data.CalibRepo
import com.hotlaps.dynamic.data.CalibState

import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.DisposableEffect


import androidx.compose.runtime.collectAsState

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow

import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.collectAsState

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.ArrowDropDown

import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.foundation.background

import androidx.compose.ui.graphics.toArgb
import com.hotlaps.dynamic.AccentLime


@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GGScreen(
    modifier: Modifier = Modifier,
    trackSelectionViewModel: TrackSelectionViewModel,
    driveViewModel: DriveViewModel,
    onSelectTrack: () -> Unit,
    onOpenDrawer: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCalibrate: () -> Unit
) {

    // Keep screen on while this Composable is visible
    val view = LocalView.current
    DisposableEffect(Unit) {
        val oldFlag = view.keepScreenOn
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = oldFlag
        }
    }




    // === Read Settings (same pattern as SettingsScreen) ===
    val context = LocalContext.current
    val repo = remember(context) { SettingsRepo(context) }

    // Collect the three settings we need
    val ggMaxG        by repo.ggMaxG.collectAsStateWithLifecycle(initialValue = 1.25f)
    val ggTrailWindow by repo.ggTrailWindowS.collectAsStateWithLifecycle(initialValue = 3.0f)
    val trailBrakeG   by repo.trailBrakeG.collectAsStateWithLifecycle(initialValue = 0.30f)

    // --- Sensor hookup: keep the same states you already have ---
    var ticks by remember { mutableStateOf(0L) }
    var latG  by remember { mutableStateOf(0f) }
    var longG by remember { mutableStateOf(0f) }

    // Latest raw linear-accel sample in m/s^2 (device axes)
    var latestX by remember { mutableStateOf(0f) }
    var latestY by remember { mutableStateOf(0f) }
    // (We ignore Z for the G-G plot)

    // === Moving-average smoothing for G-G plot ===
    var smoothingSamples by remember { mutableStateOf(10) }  // user-adjustable
    val ma = remember { MovingAverage2D(smoothingSamples) }

    // === Active track (from TrackSelectionViewModel) ===
    val activeTrack by trackSelectionViewModel
        .selectedTrack
        .collectAsState(initial = null)

    val sensorManager = remember(context) {
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }

    // Calibration repo + current state (forward unit vector)
    val calibRepo = remember(context) { CalibRepo(context) }
    val calibState by calibRepo.state
        .collectAsStateWithLifecycle(initialValue = CalibState(vec = null, savedAtEpochMs = null))

    // TRUE if we have a saved forward vector, FALSE if not calibrated yet
    val isCalibrated = calibState.vec != null


    // Latest sensor readings
    var latestAccelX by remember { mutableStateOf(0f) }
    var latestAccelY by remember { mutableStateOf(0f) }
    var latestAccelZ by remember { mutableStateOf(0f) }

    var latestGravX by remember { mutableStateOf(0f) }
    var latestGravY by remember { mutableStateOf(0f) }
    var latestGravZ by remember { mutableStateOf(0f) }

    // === GPS values in the screen (local copy) ===
    var gpsLat by remember { mutableStateOf(0.0) }
    var gpsLon by remember { mutableStateOf(0.0) }

    // === ViewModel GPS / G values ===
    val vmGpsLat  by driveViewModel.gpsLat.collectAsState()
    val vmGpsLon  by driveViewModel.gpsLon.collectAsState()
    val vmLatG    by driveViewModel.latG.collectAsState()
    val vmLongG   by driveViewModel.longG.collectAsState()
    val currentEvent by driveViewModel.currentEvent.collectAsState()

    // NEW: high-level recording state from DriveViewModel
    val recordingState by driveViewModel.recordingState.collectAsState()

    var showRenameDialog by remember { mutableStateOf(false) }
    var pendingEventName by remember { mutableStateOf("") }


    // First corner (if any)
    val firstCorner = activeTrack?.corners?.firstOrNull()

    // Distance to first corner (based on VM GPS)
    val distanceToFirstCorner: Double? =
        if (firstCorner != null) {
            com.hotlaps.dynamic.util.GeoUtils.haversineMeters(
                vmGpsLat,
                vmGpsLon,
                firstCorner.lat,
                firstCorner.lon
            )
        } else {
            null
        }

    // Nearest-corner logic now handled by DriveViewModel
    val nearestCornerInfo = driveViewModel.computeNearestCorner(
        track = activeTrack,
        gpsLatDeg = vmGpsLat,
        gpsLonDeg = vmGpsLon
    )




    // 1) Register a sensor listener (Linear Acceleration preferred)
    val ctx = LocalContext.current
    // --- Sensor listener: linear accel + gravity (no projection here yet) ---
    DisposableEffect(Unit) {
        val lin = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        val grav = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)

        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                when (e.sensor.type) {
                    Sensor.TYPE_LINEAR_ACCELERATION -> {
                        latestAccelX = e.values[0]
                        latestAccelY = e.values[1]
                        latestAccelZ = e.values[2]
                    }
                    Sensor.TYPE_GRAVITY -> {
                        latestGravX = e.values[0]
                        latestGravY = e.values[1]
                        latestGravZ = e.values[2]
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        if (lin != null) {
            sensorManager.registerListener(
                listener,
                lin,
                SensorManager.SENSOR_DELAY_GAME
            )
        }
        if (grav != null) {
            sensorManager.registerListener(
                listener,
                grav,
                SensorManager.SENSOR_DELAY_GAME
            )
        }

        onDispose {
            sensorManager.unregisterListener(listener)
        }
    }


    // 2) GPS Location Updates
    DisposableEffect(Unit) {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        val listener = LocationListener { loc: Location ->
            val lat = loc.latitude
            val lon = loc.longitude

            gpsLat = lat      // keep UI copy for now
            gpsLon = lon

            driveViewModel.updateGps(lat, lon)   // send to ViewModel
        }

        try {
            if (
                ActivityCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
            ) {
                lm.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    200L,
                    0f,
                    listener
                )
            }

        } catch (e: SecurityException) {
            e.printStackTrace()
        }

        onDispose {
            lm.removeUpdates(listener)
        }
    }

    // 3) 10 Hz publisher: convert to g's + small EMA smoothing, then tick
    // --- 10 Hz loop: project sensors into calibrated car axes, smooth, and publish ---
    LaunchedEffect(calibState.vec) {
        val g = SensorManager.GRAVITY_EARTH           // 9.80665 m/s^2
        val tauMs = 600.0f                       // EMA time constant (~0.5 s) 500.0f

        var latEma = 0f
        var longEma = 0f
        var lastUpdateMs = System.currentTimeMillis()

        while (true) {
            kotlinx.coroutines.delay(50) // ~20 Hz world tick

            // Pick forward vector: use calibration if present, else guess
            val forward = normalize3(
                calibState.vec?.getOrNull(0) ?: 0f,
                calibState.vec?.getOrNull(1) ?: -1f,   // assume -Y is forward if no calib
                calibState.vec?.getOrNull(2) ?: 0f
            ) ?: floatArrayOf(0f, -1f, 0f)

            // Gravity vector → "down" direction
            val down = normalize3(latestGravX, latestGravY, latestGravZ)
                ?: floatArrayOf(0f, 0f, 1f)

            // "Up" is opposite of gravity
            val up = floatArrayOf(-down[0], -down[1], -down[2])

            // Right = up × forward (lateral axis)
            val rightRaw = cross(up, forward)
            val right = normalize3(rightRaw[0], rightRaw[1], rightRaw[2])
                ?: floatArrayOf(1f, 0f, 0f)

            // Current linear acceleration vector
            val ax = latestAccelX
            val ay = latestAccelY
            val az = latestAccelZ

            val longMs2 = dot3(ax, ay, az, forward[0], forward[1], forward[2])
            val latMs2  = dot3(ax, ay, az, right[0], right[1], right[2])

            val longNow = longMs2 / g       // + = accel, - = brake
            val latNow  = latMs2 / g        // + = right, - = left

            // 1) Clamp crazy spikes
            val G_CLAMP = 2.0f            // +/- 2g should be plenty
            val longClamped = longNow.coerceIn(-G_CLAMP, G_CLAMP)
            val latClamped  = latNow.coerceIn(-G_CLAMP, G_CLAMP)

            // 2) Time-aware EMA on clamped values
            val nowMs = System.currentTimeMillis()
            val dtMs = (nowMs - lastUpdateMs).coerceAtLeast(1L)
            lastUpdateMs = nowMs

            val alpha = 1f - kotlin.math.exp(-dtMs.toFloat() / tauMs)

            val longEmaNew = longEma + alpha * (longClamped - longEma)
            val latEmaNew  = latEma  + alpha * (latClamped  - latEma)

            longEma = longEmaNew
            latEma  = latEmaNew

            // 3) Deadband to keep “coast” from jittering
            val DEAD_BAND_G = 0.04f       // tweak; ~0.03–0.05g works well
            val longDb = if (kotlin.math.abs(longEmaNew) < DEAD_BAND_G) 0f else longEmaNew
            val latDb  = if (kotlin.math.abs(latEmaNew)  < DEAD_BAND_G) 0f else latEmaNew

            // 4) Moving-average smoothing on top of EMA + deadband
            //    NOTE: we store lat first, long second
            val (latMa, longMa) = ma.add(latDb, longDb)

            // Final values used by the rest of the UI
            latG  = latMa
            longG = longMa

            // Feed into ViewModel, just like before
            driveViewModel.updateGForces(latG, longG)
            driveViewModel.recordCurrentSample()
            driveViewModel.updateCornerCaptureState(activeTrack)

            ticks++

        }

    }

    // --- Pager state for swipeable Drive / Debug pages ---
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 2 })
    val scope = rememberCoroutineScope()

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = {
                // If they back out, just keep the default name and stop the event
                showRenameDialog = false
                driveViewModel.stopEvent()
            },
            title = {
                Text("Name this session")
            },
            text = {
                OutlinedTextField(
                    value = pendingEventName,
                    onValueChange = { pendingEventName = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Session name") }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        // Save the new name (and update CSV), then stop the event
                        driveViewModel.renameCurrentEvent(context, pendingEventName)
                        showRenameDialog = false
                        driveViewModel.stopEvent()
                    }

                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        // Use the existing name and just stop
                        showRenameDialog = false
                        driveViewModel.stopEvent()
                    }
                ) {
                    Text("Use default")
                }
            }
        )
    }



    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Dynamic G-Force Map",
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(
                            imageVector = Icons.Default.Menu,
                            contentDescription = "Open menu"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings"
                        )
                    }
                }
            )
        }

    ) { inner ->

        Box(
            modifier = modifier
                .padding(inner)
                .fillMaxSize()
        ) {

            // 1) Main Drive / Debug content
            Column(
                modifier = Modifier
                    .fillMaxSize()
            ) {

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    when (page) {
                        // === Page 0: Main driving HUD + G-G plot ===
                        0 -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 12.dp),
                                verticalArrangement = Arrangement.Top,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                // --- Top HUD (trimmed to essentials) ---
                                Column(
                                    modifier = Modifier.padding(top = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {

                                    // Track + Event (clickable to choose/change track)
                                    val t = activeTrack

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 4.dp)
                                            .clickable { onSelectTrack() },
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = if (t != null) {
                                                "Track: ${t.name} (${t.corners.size} corners)"
                                            } else {
                                                "Track: (none selected – tap to choose)"
                                            },
                                            fontSize = 20.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (t != null) MaterialTheme.colorScheme.onSurface else Color.Red
                                        )

                                        Spacer(Modifier.width(6.dp))

                                        Icon(
                                            imageVector = Icons.Default.ArrowDropDown,
                                            contentDescription = "Change track"
                                        )
                                    }

                                    // Recording state
                                    Text(
                                        text = when (recordingState) {
                                            DriveViewModel.RecordingState.Idle -> "Recording: Idle"
                                            DriveViewModel.RecordingState.Recording -> "Recording: LIVE"
                                            DriveViewModel.RecordingState.Paused -> "Recording: Paused"
                                        },
                                        fontSize = 16.sp,
                                        color = when (recordingState) {
                                            DriveViewModel.RecordingState.Idle -> Color.Gray
                                            DriveViewModel.RecordingState.Recording -> Color.Red
                                            DriveViewModel.RecordingState.Paused -> Color(0xFFFFC107)
                                        },
                                        modifier = Modifier.padding(top = 4.dp)
                                    )

                                    // Manual recording controls
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceEvenly,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        when (recordingState) {
                                            DriveViewModel.RecordingState.Idle -> {
                                                Button(
                                                    onClick = {
                                                        val track = activeTrack
                                                        driveViewModel.startManualEvent(context, track)
                                                        Log.d(
                                                            "GGScreen",
                                                            "Record pressed — startManualEvent, track=${track?.name ?: "(none)"}"
                                                        )
                                                    },
                                                    colors = ButtonDefaults.buttonColors(
                                                        containerColor = Color(0xFFDC2626),
                                                        contentColor = Color.White
                                                    )
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Filled.FiberManualRecord,
                                                        contentDescription = "Start recording",
                                                        modifier = Modifier.size(24.dp)
                                                    )
                                                    Spacer(Modifier.width(8.dp))
                                                    Text("Record")
                                                }
                                            }

                                            DriveViewModel.RecordingState.Recording -> {
                                                Button(
                                                    onClick = { driveViewModel.pauseRecording() },
                                                    colors = ButtonDefaults.buttonColors(
                                                        containerColor = Color(0xFFFFC107),
                                                        contentColor = Color.Black
                                                    )
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Filled.Pause,
                                                        contentDescription = "Pause recording",
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                    Spacer(Modifier.width(6.dp))
                                                    Text("Pause")
                                                }

                                                Button(
                                                    onClick = {
                                                        val evt = currentEvent
                                                        if (evt != null) {
                                                            pendingEventName = evt.displayName.ifBlank { evt.name }
                                                            showRenameDialog = true
                                                        } else {
                                                            driveViewModel.stopEvent()
                                                        }
                                                    },
                                                    colors = ButtonDefaults.buttonColors(
                                                        containerColor = Color(0xFFDC2626),
                                                        contentColor = Color.White
                                                    )
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Filled.Stop,
                                                        contentDescription = "Stop recording",
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                    Spacer(Modifier.width(6.dp))
                                                    Text("Stop")
                                                }
                                            }

                                            DriveViewModel.RecordingState.Paused -> {
                                                Button(
                                                    onClick = { driveViewModel.resumeRecording() },
                                                    colors = ButtonDefaults.buttonColors(
                                                        containerColor = Color(0xFF16A34A),
                                                        contentColor = Color.White
                                                    )
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Filled.PlayArrow,
                                                        contentDescription = "Resume recording",
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                    Spacer(Modifier.width(6.dp))
                                                    Text("Resume")
                                                }

                                                Button(
                                                    onClick = {
                                                        val evt = currentEvent
                                                        if (evt != null) {
                                                            pendingEventName = evt.displayName.ifBlank { evt.name }
                                                            showRenameDialog = true
                                                        } else {
                                                            driveViewModel.stopEvent()
                                                        }
                                                    },
                                                    colors = ButtonDefaults.buttonColors(
                                                        containerColor = Color(0xFFDC2626),
                                                        contentColor = Color.White
                                                    )
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Filled.Stop,
                                                        contentDescription = "Stop recording",
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                    Spacer(Modifier.width(6.dp))
                                                    Text("Stop")
                                                }
                                            }
                                        }
                                    }

                                    Text(
                                        text = "Event: ${currentEvent?.name ?: "(none)"}",
                                        fontSize = 16.sp
                                    )

                                    nearestCornerInfo?.let { (label, distM) ->
                                        Text(
                                            text = "Nearest corner: #$label (${String.format("%.1f", distM)} m)",
                                            fontSize = 18.sp,
                                            modifier = Modifier.padding(top = 4.dp)
                                        )
                                    }
                                }

                                Spacer(Modifier.height(8.dp))

                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .verticalScroll(rememberScrollState())
                                        .padding(horizontal = 4.dp)
                                ) {
                                    GGPlot(
                                        maxAbsG = ggMaxG,
                                        latG = latG,
                                        longG = longG,
                                        trailSeconds = ggTrailWindow,
                                        ticks = ticks,
                                        brakeThreshG = trailBrakeG,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(1f),
                                    )

                                    Spacer(Modifier.height(8.dp))
                                }
                            }
                        }

                        // === Page 1: Debug panel ===
                        1 -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.Top,
                                horizontalAlignment = Alignment.Start
                            ) {
                                Text(
                                    text = "Debug Panel",
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )

                                val t = activeTrack

                                Text("Track: ${t?.name ?: "(none)"}")
                                if (t != null) {
                                    Text("Corners: ${t.corners.size}")
                                }

                                Spacer(Modifier.height(8.dp))

                                Text("Raw GPS (screen): ${"%.6f".format(gpsLat)}, ${"%.6f".format(gpsLon)}")
                                Text("VM GPS: ${"%.6f".format(vmGpsLat)}, ${"%.6f".format(vmGpsLon)}")
                                Text("VM G: lat=${"%.2f".format(vmLatG)}, long=${"%.2f".format(vmLongG)}")

                                Spacer(Modifier.height(8.dp))

                                if (t != null && firstCorner != null && distanceToFirstCorner != null) {
                                    Text(
                                        "Corner 1 distance: ${"%.1f".format(distanceToFirstCorner)} m"
                                    )
                                } else {
                                    Text("Corner 1 distance: (n/a)")
                                }

                                nearestCornerInfo?.let { (_, distM) ->
                                    val inside = driveViewModel.isWithinCornerTriggerRadius(distM)
                                    Text("Inside trigger radius: $inside")
                                } ?: run {
                                    Text("Inside trigger radius: (n/a)")
                                }

                                Text(
                                    text = "Corner trigger radius: ${
                                        "%.1f".format(
                                            driveViewModel.getCornerTriggerRadiusMeters()
                                        )
                                    } m",
                                    modifier = Modifier.padding(top = 4.dp)
                                )

                                nearestCornerInfo?.let { (label, distM) ->
                                    Text(
                                        text = "Nearest corner (all): #$label (${String.format("%.1f", distM)} m)",
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                } ?: run {
                                    Text(
                                        text = "Nearest corner (all): (n/a)",
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }

                                Spacer(Modifier.height(12.dp))

                                Text("Event ID: ${currentEvent?.id ?: 0L}")
                                Text("Event name: ${currentEvent?.name ?: "(none)"}")
                                Text("TrackId on Event: ${currentEvent?.trackId ?: 0L}")

                                Spacer(Modifier.height(16.dp))

                                Text(
                                    text = "G-G Smoothing",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Samples: $smoothingSamples (~${
                                        "%.2f".format(
                                            smoothingSamples / 20f
                                        )
                                    } s at 20 Hz)",
                                    fontSize = 14.sp
                                )

                                Slider(
                                    value = smoothingSamples.toFloat(),
                                    onValueChange = { newValue ->
                                        val clamped = newValue.toInt().coerceIn(1, 30)
                                        smoothingSamples = clamped
                                        ma.setWindowSize(clamped)
                                    },
                                    valueRange = 1f..30f,
                                    steps = 30 - 2
                                )

                                if (t != null && firstCorner != null) {
                                    Text(
                                        text = "GPS Simulation (Debug only)",
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(bottom = 4.dp)
                                    )

                                    Button(
                                        onClick = {
                                            driveViewModel.updateGps(firstCorner.lat, firstCorner.lon)
                                        },
                                        modifier = Modifier.padding(bottom = 4.dp)
                                    ) {
                                        Text("Teleport INSIDE corner 1")
                                    }
                                } else {
                                    Text("GPS Simulation: (needs a track with at least one corner)")
                                }
                            }
                        }
                    }
                }
            }

            // 2) Calibration overlay (only when NOT calibrated)
            if (!isCalibrated) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
                        .padding(24.dp)
                ) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Calibration required",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = "Before using the G-Force map, please calibrate the accelerometers so braking and acceleration are oriented correctly.",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                        Button(
                            onClick = { onOpenCalibrate() }
                        ) {
                            Text("Go to Calibration")
                        }
                        Text(
                            text = "Tip: park on a level surface, point the car straight ahead, then follow the on-screen steps.",
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}



@Composable
private fun GGPlot(
    maxAbsG: Float,
    latG: Float,
    longG: Float,
    trailSeconds: Float,
    ticks: Long,
    brakeThreshG: Float,
    modifier: Modifier = Modifier,
    onPeaks: (longMax: Float, longBrakeMax: Float, rightMax: Float, leftMax: Float) -> Unit = { _,_,_,_ -> }
)
 {
     var peaksSinceMs by remember { mutableStateOf(android.os.SystemClock.elapsedRealtime()) }

    // Rolling trail of recent samples (lat, long, tMillis)
    data class TrailPt(val x: Float, val y: Float, val t: Long)
    val trail = remember { mutableStateListOf<TrailPt>() }

// On each 10 Hz tick, append the current point and prune old ones
    LaunchedEffect(ticks) {
        val now = android.os.SystemClock.elapsedRealtime()
        trail.add(TrailPt(latG, longG, now))

        val windowMs = (trailSeconds.coerceAtLeast(0.2f) * 1000f).toLong()
        val cutoff = now - windowMs

        while (trail.isNotEmpty() && trail.first().t < cutoff) trail.removeAt(0)
        if (trail.size > 400) { // hard cap, just in case
            trail.removeRange(0, trail.size - 400)



        }

    }


    Canvas(
        modifier
            .fillMaxWidth()
            .aspectRatio(1f)
    ) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = size.minDimension * 0.48f
        val textSizePx = size.minDimension * 0.045f
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.parseColor("#444444")
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = textSizePx
        }

        // ----- Base grid -----
        drawCircle(Color.Gray, radius, Offset(cx, cy), style = Stroke(width = 3f))
        drawLine(Color.Gray, Offset(cx - radius, cy), Offset(cx + radius, cy), 2f)
        drawLine(Color.Gray, Offset(cx, cy - radius), Offset(cx, cy + radius), 2f)

        // Tick rings at 0.5 G intervals up to maxAbsG
        val tickStep = 0.5f
        var tick = tickStep
        while (tick < maxAbsG) {
            val r = radius * (tick / maxAbsG)
            drawCircle(Color.DarkGray, r, Offset(cx, cy), style = Stroke(1f))
            tick += tickStep
        }

        // Center dot
       // drawCircle(Color.White.copy(alpha = 0.7f), 5f, Offset(cx, cy))




        // Wedges + diagonal labels (ported)
        drawGgRadialsAndLabels(
            latG = latG,
            longG = longG,
            brakeThreshG = brakeThreshG,
            windowStartDeg = 20f,
            windowEndDeg = 70f,
            lineAlpha = 0.30f,
            strokeWidthDp = 1f
        )

        // Axis title labels (same look as HotLapMobile)
        drawGgLabels()

        // Optional numeric scale markers
        listOf(0.5f, 1.0f, 1.5f, 2.0f).filter { it <= maxAbsG }.forEach {
            val r = radius * (it / maxAbsG)
            drawContext.canvas.nativeCanvas.drawText(
                "${"%.1f".format(it)}G",
                cx + r - textSizePx * 0.6f,
                cy - textSizePx,
                paint
            )
        }

// --- after base grid (circle + axes), before trail/dots ---
        run {
            // Proportion of full scale, clamp to rim
            val frac = (kotlin.math.abs(longG) / maxAbsG).coerceIn(0f, 1f)
            val len  = radius * frac

            // Color by direction
            val barColor = if (longG >= 0f)
                Color(0xFF16A34A)   // accel = green
            else
                Color(0xFFDC2626)   // brake = red

            // Make it wider than the thin axis line
            val stroke = 6f

            // Draw from center toward the correct direction
            if (longG >= 0f) {
                // up from center (accel)
                drawLine(
                    color = barColor,
                    start = Offset(cx, cy),
                    end   = Offset(cx, cy - len),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round
                )
            } else {
                // down from center (brake)
                drawLine(
                    color = barColor,
                    start = Offset(cx, cy),
                    end   = Offset(cx, cy + len),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round
                )
            }
        }

// --- horizontal lateral-G bar (drawn under trail/dot) ---
        run {
            // Proportion of full scale (0..1), clamp to rim
            val frac = (kotlin.math.abs(latG) / maxAbsG).coerceIn(0f, 1f)
            val len  = radius * frac

            // Color by direction (match your pure-axis scheme)
            val barColor = if (latG >= 0f)
                Color(0xFFF59E0B)   // right = orange
            else
                Color(0xFFA855F7)   // left = violet

            // Same width as vertical bar
            val stroke = 6f

            // Small deadband to avoid “blob” at center when tiny |g|
            val minLenPx = stroke * 0.6f      // tweak if you like
            if (len >= minLenPx) {
                if (latG >= 0f) {
                    // fill to the right from center
                    drawLine(
                        color = barColor,
                        start = Offset(cx, cy),
                        end   = Offset(cx + len, cy),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round
                    )
                } else {
                    // fill to the left from center
                    drawLine(
                        color = barColor,
                        start = Offset(cx, cy),
                        end   = Offset(cx - len, cy),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round
                    )
                }
            }
        }

// --- Peak markers within the current trail window ---
        run {
            // helpers
            fun clampG(g: Float) = g.coerceIn(-maxAbsG, maxAbsG)
            val tickWidthPx = 28f        // half-length of the tick
            val tickStroke  = 8f
            val minShowG    = 0.05f      // ignore tiny noise

            fun drawHPeak(yG: Float, color: Color) {
                val g = clampG(yG)
                if (kotlin.math.abs(g) < minShowG) return
                val y = cy - (g / maxAbsG) * radius
                drawLine(
                    color = color.copy(alpha = 0.95f),
                    start = Offset(cx - tickWidthPx, y),
                    end   = Offset(cx + tickWidthPx, y),
                    strokeWidth = tickStroke,
                    cap = StrokeCap.Round
                )
            }
            fun drawVPeak(xG: Float, color: Color) {
                val g = clampG(xG)
                if (kotlin.math.abs(g) < minShowG) return
                val x = cx + (g / maxAbsG) * radius
                drawLine(
                    color = color.copy(alpha = 0.95f),
                    start = Offset(x, cy - tickWidthPx),
                    end   = Offset(x, cy + tickWidthPx),
                    strokeWidth = tickStroke,
                    cap = StrokeCap.Round
                )
            }

            // compute peaks from the pruned trail (auto-expires with window)
            val maxLong = trail.maxOfOrNull { it.y } ?: 0f   // +accel (up)
            val minLong = trail.minOfOrNull { it.y } ?: 0f   // -brake (down)
            val maxLat  = trail.maxOfOrNull { it.x } ?: 0f   // +right
            val minLat  = trail.minOfOrNull { it.x } ?: 0f   // -left

            // draw ticks (colors match your axis scheme)
            drawHPeak(maxLong, Color(0xFF16A34A)) // accel peak = green
            drawHPeak(minLong, Color(0xFFDC2626)) // brake  peak = red
            drawVPeak(maxLat,  Color(0xFFF59E0B)) // right  peak = orange
            drawVPeak(minLat,  Color(0xFFA855F7)) // left   peak = violet


            // --- Peak magnitudes from the current (pruned) trail window ---
            val maxLongUp   = (trail.maxOfOrNull { it.y } ?: 0f).coerceAtLeast(0f)   // accel +
            val maxLongDown = (-(trail.minOfOrNull { it.y } ?: 0f)).coerceAtLeast(0f) // braking magnitude
            val maxRight    = (trail.maxOfOrNull { it.x } ?: 0f).coerceAtLeast(0f)   // right +
            val maxLeft     = (-(trail.minOfOrNull { it.x } ?: 0f)).coerceAtLeast(0f) // left magnitude

            // Optional: ignore tiny noise
            fun z(v: Float, min: Float = 0.02f) = if (kotlin.math.abs(v) < min) 0f else v

            val peakLongAccel  = z(maxLongUp)
            val peakLongBrake  = z(maxLongDown)
            val peakRightAccel = z(maxRight)
            val peakLeftAccel  = z(maxLeft)



        }



        // --- Fading trail (oldest → youngest) ---
        val now = android.os.SystemClock.elapsedRealtime()
        val windowMs = (trailSeconds.coerceAtLeast(0.2f) * 1000f).toLong()

// helper from your dot code (already present below). If it's declared later,
// just re-declare lightweightly here or move the original up.
        val toPx: (Float) -> Float = { g -> (g / maxAbsG) * radius }
        fun clampToCircle(xIn: Float, yIn: Float): Offset {
            var x = xIn
            var y = yIn
            val dx = x - cx
            val dy = y - cy
            val dist = kotlin.math.sqrt(dx * dx + dy * dy)
            if (dist > radius && dist > 0f) {
                val s = radius / dist
                x = cx + dx * s
                y = cy + dy * s
            }
            return Offset(x, y)   // <-- add this
        }


        if (trail.isNotEmpty()) {
            // Build clamped pixel points + alpha (ease fade with square)
            data class RenderPt(val p: Offset, val a: Float)
            val renders = buildList {
                for (pt in trail) {
                    val age = (now - pt.t).coerceAtLeast(0)
                    val frac = 1f - (age.toFloat() / windowMs.toFloat()) // 1 → 0
                    if (frac <= 0f) continue
                    val alpha = (frac * frac).coerceIn(0f, 1f)

                    val px = cx + toPx(pt.x)
                    val py = cy - toPx(pt.y)
                    add(RenderPt(clampToCircle(px, py), alpha))
                }
            }

            // Draw segments between consecutive points with round caps
            for (i in 1 until renders.size) {
                val a = renders[i - 1]
                val b = renders[i]
                val segAlpha = minOf(a.a, b.a)
                if (segAlpha > 0f) {
                    drawLine(
                        // green when accelerating (py above center), red when braking (below)
                        color = if (b.p.y < cy) Color(0xFF34D399).copy(alpha = segAlpha)
                        else            Color(0xFFEF4444).copy(alpha = segAlpha),
                        start = a.p,
                        end = b.p,
                        strokeWidth = 8f,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round
                    )
                }
            }

            // Optional: small dots along the path
            for (r in renders) {
                drawCircle(
                    color = Color(0xFF1E88E5).copy(alpha = r.a),
                    radius = 6f,
                    center = r.p
                )
            }
        }



        // === Moving G-G dot (drawn last, above wedges) ===



        run {
            val px = cx + toPx(latG)   // +X → right
            val py = cy - toPx(longG)  // +Y → up
            val c  = clampToCircle(px, py)
            drawCircle(
                color = Color(0xFF1E88E5), // vivid blue
                radius = 18f,
                center = c
            )
        }




    }
}


/** Draw wedge boundaries at 20° and 70° in each quadrant, plus diagonal labels. */
fun DrawScope.drawGgRadialsAndLabels(
    latG: Float = 0f,
    longG: Float = 0f,
    brakeThreshG: Float = 0.2f,
    windowStartDeg: Float = 20f,
    windowEndDeg: Float = 70f,
    lineAlpha: Float = 0.30f,
    strokeWidthDp: Float = 1f
) {
    val w = size.width
    val h = size.height
    val cx = w / 2f
    val cy = h / 2f
    val radius = min(w, h) * 0.46f

    // Determine active region
    val isBraking = longG < -brakeThreshG
    val isAccelerating = longG > brakeThreshG
    val isLeft = latG < -0.05f
    val isRight = latG > 0.05f

    // ---- Wedge boundary lines at {20°, 70°} offset from each axis (0, 90, 180, 270)
    val stroke = strokeWidthDp.dp.toPx()
    val lineColor = Color(0xFF666666).copy(alpha = lineAlpha) // darker gray for white bg

    val bases = floatArrayOf(0f, 90f, 180f, 270f)
    val offsets = floatArrayOf(windowStartDeg, windowEndDeg)

    val arcTopLeft = Offset(cx - radius, cy - radius)
    val arcSize    = Size(radius * 2f, radius * 2f)

    val bandSweep = (windowEndDeg - windowStartDeg).coerceAtLeast(1f)

    val (shouldHighlight, startDeg, wedgeColor) = when {
        // Bottom-right (Right→Down): BRAKING + RIGHT
        isBraking && isRight -> Triple(true,   0f + windowStartDeg, Color(0xFF60A5FA).copy(alpha = 0.65f))
        // Bottom-left  (Down→Left):  BRAKING + LEFT
        isBraking && isLeft  -> Triple(true,  90f + windowStartDeg, Color(0xFF60A5FA).copy(alpha = 0.65f))
        // Top-right    (Up→Right):   ACCEL + RIGHT
        isAccelerating && isRight -> Triple(true, 270f + windowStartDeg, Color(0xFF34D399).copy(alpha = 0.55f))
        // Top-left     (Left→Up):    ACCEL + LEFT
        isAccelerating && isLeft  -> Triple(true, 180f + windowStartDeg, Color(0xFF34D399).copy(alpha = 0.55f))
        else -> Triple(false, 0f, Color.Transparent)
    }

    Log.d("GG-HILITE", "brake=$isBraking accel=$isAccelerating left=$isLeft right=$isRight long=$longG lat=$latG")

    if (shouldHighlight) {
        // Filled wedge (bold)
        drawArc(
            color = wedgeColor,
            startAngle = startDeg,
            sweepAngle = bandSweep,
            useCenter = true,
            topLeft = Offset(cx - radius, cy - radius),
            size = Size(radius * 2f, radius * 2f),
            style = Fill
        )
        // Thin outline so it "pops" against the grid
        drawArc(
            color = wedgeColor.copy(alpha = 0.9f),
            startAngle = startDeg,
            sweepAngle = bandSweep,
            useCenter = false,
            topLeft = Offset(cx - radius, cy - radius),
            size = Size(radius * 2f, radius * 2f),
            style = Stroke(width = 4.dp.toPx())
        )
    }

    // ===== PURE-AXIS HIGHLIGHTS (narrow wedges around each axis) =====
    val latNearZero = abs(latG) < 0.05f
    val longNearZero = abs(longG) < 0.05f
    val latThreshG = 0.10f                         // lateral threshold for pure left/right
    val narrowSweep = (windowStartDeg * 2f).coerceAtLeast(6f) // ~±windowStart around axis

    fun normAngle(deg: Float) = ((deg % 360f) + 360f) % 360f
    fun drawAxisWedge(centerDeg: Float, color: Color) {
        drawArc(
            color = color.copy(alpha = 0.85f),
            startAngle = normAngle(centerDeg - narrowSweep / 2f),
            sweepAngle = narrowSweep,
            useCenter = true,
            topLeft = arcTopLeft,
            size = arcSize,
            style = Fill
        )
        drawArc(
            color = Color.Black.copy(alpha = 0.9f),
            startAngle = normAngle(centerDeg - narrowSweep / 2f),
            sweepAngle = narrowSweep,
            useCenter = false,
            topLeft = arcTopLeft,
            size = arcSize,
            style = Stroke(width = 6.dp.toPx())
        )
    }



// Fade strength - adjust this if needed
    val wedgeAlpha = 0.15f

    if (latNearZero && longG > brakeThreshG)
        drawAxisWedge(270f, Color(0xFF06B6D4).copy(alpha = wedgeAlpha))  // Accel

    if (latNearZero && longG < -brakeThreshG)
        drawAxisWedge( 90f, Color(0xFFEF4444).copy(alpha = wedgeAlpha))  // Brake

    if (longNearZero && latG > latThreshG)
        drawAxisWedge( 0f, Color(0xFFF59E0B).copy(alpha = wedgeAlpha))   // Right

    if (longNearZero && latG < -latThreshG)
        drawAxisWedge(180f, Color(0xFFA855F7).copy(alpha = wedgeAlpha))  // Left

    // --- Radial guideline lines (20° / 70° from each axis)
    for (base in bases) {
        for (off in offsets) {
            val deg = base + off
            val rad = Math.toRadians(deg.toDouble())
            val x = cx + radius * cos(rad).toFloat()
            val y = cy - radius * sin(rad).toFloat() // screen Y grows down
            drawLine(
                color = lineColor,
                start = Offset(cx, cy),
                end = Offset(x, y),
                strokeWidth = stroke
            )
        }
    }

    // ---- Diagonal labels along 45°/135°/−45°/−135°
    val diagPaint = Paint().apply {
        isAntiAlias = true
        color = android.graphics.Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textSize = 16.sp.toPx()
        alpha = (255 * 0.85f).toInt()   // brighter → more legible
    }

    fun drawDiagLabel(text: String, angleDeg: Float, rFrac: Float) {
        val rad = Math.toRadians(angleDeg.toDouble())
        val px = cx + (radius * rFrac) * cos(rad).toFloat()
        val py = cy - (radius * rFrac) * sin(rad).toFloat()
        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.save()
            canvas.nativeCanvas.rotate(-angleDeg, px, py)
            canvas.nativeCanvas.drawText(text, px, py, diagPaint)
            canvas.nativeCanvas.restore()
        }
    }

    val rFrac = 0.72f
    // Accelerating quadrants
    drawDiagLabel("Throttle Steering",  45f, rFrac)
    drawDiagLabel("Throttle Steering", 135f, rFrac)
    // Braking quadrants
    drawDiagLabel("Trail Braking",    -45f, rFrac)
    drawDiagLabel("Trail Braking",   -135f, rFrac)
}


fun DrawScope.drawGgLabels() {
    val w = size.width
    val h = size.height
    val cx = w / 2f
    val cy = h / 2f

    val axisColorArgb = AccentLime.toArgb()
    val quadColorArgb = android.graphics.Color.WHITE

    val paint = Paint().apply {
        isAntiAlias = true
        color = axisColorArgb
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }

    val axisSizePx = 18.sp.toPx()
    val quadSizePx = 14.sp.toPx()
    val faint = 0.45f
    val strong = 0.90f

    drawIntoCanvas { canvas ->
        // Axis labels (Y: Accelerating/Braking; X: Left/Right)
        paint.textSize = axisSizePx
        paint.color = axisColorArgb
        paint.alpha = (255 * strong).roundToInt()

        // Y axis
        canvas.nativeCanvas.drawText("Pure Acceleration", cx, 16.sp.toPx() + 8f, paint)
        canvas.nativeCanvas.drawText("Pure Braking",      cx, h - 8f,                 paint)

        // X axis: Left
        canvas.nativeCanvas.save()
        canvas.nativeCanvas.rotate(-90f, 16.sp.toPx() + 8f, cy)
        canvas.nativeCanvas.drawText("Pure Left", 16.sp.toPx() + 8f, cy, paint)
        canvas.nativeCanvas.restore()

        // X axis: Right
        canvas.nativeCanvas.save()
        canvas.nativeCanvas.rotate(90f, w - (16.sp.toPx() + 8f), cy)
        canvas.nativeCanvas.drawText("Pure Right", w - (16.sp.toPx() + 8f), cy, paint)
        canvas.nativeCanvas.restore()

        // Quadrant labels (if you ever re-enable the non-rotated ones)
        paint.textSize = quadSizePx
        paint.color = quadColorArgb
        paint.alpha = (255 * faint).roundToInt()
        // (currently still commented out)
    }
}




private fun normalize3(x: Float, y: Float, z: Float): FloatArray? {
    val mag = kotlin.math.sqrt(x * x + y * y + z * z)
    if (mag < 1e-4f) return null
    return floatArrayOf(x / mag, y / mag, z / mag)
}

private fun dot3(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float): Float {
    return ax * bx + ay * by + az * bz
}

private fun cross(a: FloatArray, b: FloatArray): FloatArray {
    return floatArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0]
    )
}




class MovingAverage2D(private var maxSamples: Int) {

    private val buffer = ArrayDeque<Pair<Float, Float>>()
    private var sumX = 0f
    private var sumY = 0f

    fun setWindowSize(newSize: Int) {
        maxSamples = newSize.coerceAtLeast(1)
        // Optionally trim if the window shrinks
        while (buffer.size > maxSamples) {
            val (ox, oy) = buffer.removeFirst()
            sumX -= ox
            sumY -= oy
        }
    }

    fun add(x: Float, y: Float): Pair<Float, Float> {
        if (buffer.size == maxSamples) {
            val (ox, oy) = buffer.removeFirst()
            sumX -= ox
            sumY -= oy
        }
        buffer.addLast(x to y)
        sumX += x
        sumY += y

        val size = buffer.size.coerceAtLeast(1)
        return (sumX / size) to (sumY / size)
    }
}


