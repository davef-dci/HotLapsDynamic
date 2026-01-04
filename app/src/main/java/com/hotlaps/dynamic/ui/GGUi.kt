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
import com.hotlaps.dynamic.BuildConfig
import com.hotlaps.dynamic.RecordRed

import com.hotlaps.dynamic.data.SmoothingLevel

import com.hotlaps.dynamic.util.GForceSmoother
import com.hotlaps.dynamic.util.GSmoothedSample

enum class GGScaleMode(val label: String, val fixedMaxG: Float?) {
    Auto("Auto scale", null),
    G_0_25("0.25 G", 0.25f),
    G_0_5("0.5 G", 0.5f),
    G_0_75("0.75 G", 0.75f),
    G_1_0("1.0 G", 1.0f),
    G_1_25("1.25 G", 1.25f),
    G_1_5("1.5 G", 1.5f),
    G_1_75("1.75 G", 1.75f),
    G_2_0("2.0 G", 2.0f),
}


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
    val ggMaxG by repo.ggMaxG.collectAsStateWithLifecycle(initialValue = 1.25f)
    val ggTrailWindow by repo.ggTrailWindowS.collectAsStateWithLifecycle(initialValue = 3.0f)
    val trailBrakeG by repo.trailBrakeG.collectAsStateWithLifecycle(initialValue = 0.30f)

    // Smoothing level from settings (0 = Off, 1 = Low, 2 = Medium, 3 = Heavy)
    val smoothingIndex by repo.smoothingLevel.collectAsStateWithLifecycle(initialValue = 1)
    val smoothingLevel = SmoothingLevel.entries.getOrElse(smoothingIndex) { SmoothingLevel.Low }
    val breakawayG by repo.breakawayG.collectAsStateWithLifecycle(initialValue = 1.00f)

    // === G-G scale mode (Auto vs fixed) ===
    var scaleMode by remember { mutableStateOf(GGScaleMode.Auto) }

    // Auto-scale state
    var autoMaxG by remember { mutableStateOf(0.25f) }   // start small
    var observedPeakG by remember { mutableStateOf(0f) }

    fun updateAutoScaleFromPeaks(
        longMax: Float,
        longBrakeMax: Float,
        rightMax: Float,
        leftMax: Float
    ) {
        // Peak magnitude across all directions in this window
        val peak = maxOf(longMax, longBrakeMax, rightMax, leftMax)
        if (peak <= 0f) return

        // Only grow the scale for now (we can add shrink logic later if you want)
        if (peak <= observedPeakG) return

        observedPeakG = peak

        // Snap up to the next "nice" scale
        val target = when {
            peak <= 0.25f -> 0.25f
            peak <= 0.5f  -> 0.5f
            peak <= 0.75f -> 0.75f
            peak <= 1.0f  -> 1.0f
            peak <= 1.25f -> 1.25f
            peak <= 1.5f  -> 1.5f
            peak <= 1.75f -> 1.75f
            else          -> 2.0f
        }
        autoMaxG = target
    }



    // === Shared G-force smoother (EMA + MA) ===
    // UI starts with whatever the current preset says
    var smoothingSamples by remember { mutableStateOf(smoothingLevel.windowSize as Int) }

    // Time constant for EMA; Off = no EMA
    val tauMsOrNull: Float? = when (smoothingLevel) {
        SmoothingLevel.Off -> null
        else -> smoothingLevel.tauMs.coerceAtLeast(1).toFloat()
    }

    // Single “master” smoother for live driving (in this screen)
    val gSmoother = remember(smoothingLevel) {
        GForceSmoother(
            tauMs = tauMsOrNull,
            maWindowSize = smoothingLevel.windowSize
        )
    }

    // Keep UI slider and smoother window in sync with preset
    LaunchedEffect(smoothingLevel) {
        smoothingSamples = smoothingLevel.windowSize
        gSmoother.setWindowSize(smoothingLevel.windowSize)
        gSmoother.reset()
    }




    // --- Sensor hookup: keep the same states you already have ---
    var ticks by remember { mutableStateOf(0L) }
    var latG by remember { mutableStateOf(0f) }
    var longG by remember { mutableStateOf(0f) }

    // Latest raw linear-accel sample in m/s^2 (device axes)
    var latestX by remember { mutableStateOf(0f) }
    var latestY by remember { mutableStateOf(0f) }
    // (We ignore Z for the G-G plot)

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
    val vmGpsLat by driveViewModel.gpsLat.collectAsState()
    val vmGpsLon by driveViewModel.gpsLon.collectAsState()
    val vmLatG by driveViewModel.latG.collectAsState()
    val vmLongG by driveViewModel.longG.collectAsState()
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
            val speedMps: Double? =
                if (loc.hasSpeed()) loc.speed.toDouble() else null

            gpsLat = lat   // UI only
            gpsLon = lon

            // Send all 3 to the ViewModel
            driveViewModel.updateGps(
                lat = lat,
                lon = lon,
                speedMps = speedMps
            )
        }


        try {
            if (
                ActivityCompat.checkSelfPermission(
                    ctx,
                    android.Manifest.permission.ACCESS_FINE_LOCATION
                )
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
    LaunchedEffect(calibState.vec, smoothingLevel) {
        val g = SensorManager.GRAVITY_EARTH           // 9.80665 m/s^2
// If Off: no EMA at all (just pass clamped values through)
        val tauMsOrNull: Float? = when (smoothingLevel) {
            SmoothingLevel.Off -> null
            else -> smoothingLevel.tauMs.coerceAtLeast(1).toFloat()
        }


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
            val latMs2 = dot3(ax, ay, az, right[0], right[1], right[2])

            val longNow = longMs2 / g       // + = accel, - = brake
            val latNow = latMs2 / g        // + = right, - = left

            // 1) Clamp crazy spikes
            val G_CLAMP = 2.0f            // +/- 2g should be plenty
            val longClamped = longNow.coerceIn(-G_CLAMP, G_CLAMP)
            val latClamped = latNow.coerceIn(-G_CLAMP, G_CLAMP)

            // Raw values for logging (clamped, but before EMA/deadband/MA)
            val rawLongG = longClamped
            val rawLatG  = latClamped


            // Use the shared smoother (EMA + moving average)
            val nowMs = System.currentTimeMillis()
            val smoothed = gSmoother.addSample(
                rawLatG = rawLatG,
                rawLongG = rawLongG,
                sampleTimeMs = nowMs
            )

/*
            // 2) Time-aware EMA on clamped values
            val nowMs = System.currentTimeMillis()


            val dtMs = (nowMs - lastUpdateMs).coerceAtLeast(1L)
            lastUpdateMs = nowMs

            // 2) Time-aware EMA on clamped values (or bypass if Off)
            val (longDb, latDb) = if (tauMsOrNull == null) {
                // Off → no EMA: just use clamped values directly
                longEma = longClamped
                latEma = latClamped
                longClamped to latClamped
            } else {
                val alpha = 1f - kotlin.math.exp(-dtMs.toFloat() / tauMsOrNull)

                val longEmaNew = longEma + alpha * (longClamped - longEma)
                val latEmaNew = latEma + alpha * (latClamped - latEma)

                longEma = longEmaNew
                latEma = latEmaNew

                // currently no deadband; just forward EMA outputs
                longEmaNew to latEmaNew
            }

            // 4) Moving-average smoothing on top of EMA + deadband
            //    NOTE: we store lat first, long second
            val (latMa, longMa) = ma.add(latDb, longDb)

            // Final values used by the rest of the UI
            latG = latMa
            longG = longMa


 */

            // Final values used by the rest of the UI
            latG = smoothed.latG
            longG = smoothed.longG

            // Feed into ViewModel, just like before
            driveViewModel.updateGForces(
                smoothedLat = latG,
                smoothedLong = longG,
                rawLat = rawLatG,
                rawLong = rawLongG
            )
            driveViewModel.recordCurrentSample()
            driveViewModel.updateCornerCaptureState(activeTrack)

            ticks++

        }

    }

    // --- Pager state for swipeable Drive / Debug pages ---
    val pagerState = rememberPagerState(initialPage = 0, pageCount = {
        if (BuildConfig.DEBUG) 2 else 1
    })
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
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 12.dp),
                                    verticalArrangement = Arrangement.Top,
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {


// === SESSION HEADER SECTIONS =========================================
                                    val t = activeTrack

// --- Track section ---
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 8.dp),
                                        color = MaterialTheme.colorScheme.surface,
                                        tonalElevation = 4.dp,
                                        shape = MaterialTheme.shapes.medium
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { onSelectTrack() }
                                                .padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "Track:",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                text = t?.name ?: "(none selected – tap to choose)",
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                color = if (t != null)
                                                    MaterialTheme.colorScheme.onSurface
                                                else
                                                    Color.Red
                                            )
                                        }
                                    }

// --- Event section ---
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 8.dp),
                                        color = MaterialTheme.colorScheme.surface,
                                        tonalElevation = 4.dp,
                                        shape = MaterialTheme.shapes.medium
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "Event:",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                text = currentEvent?.displayName
                                                    ?.takeIf { it.isNotBlank() }
                                                    ?: currentEvent?.name
                                                    ?: "(none)",
                                                style = MaterialTheme.typography.bodyMedium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }


// === END SESSION HEADER SECTIONS =====================================

                                    Spacer(modifier = Modifier.height(24.dp))


                                    nearestCornerInfo?.let { (label, distM) ->
                                        Text(
                                            text = "Nearest corner: #$label (${
                                                String.format(
                                                    "%.1f",
                                                    distM
                                                )
                                            } m)",
                                            fontSize = 16.sp,
                                            modifier = Modifier
                                                .align(Alignment.Start)
                                                .padding(horizontal = 16.dp, vertical = 4.dp)
                                        )
                                    }

                                    Spacer(Modifier.height(8.dp))

                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .verticalScroll(rememberScrollState())
                                            .padding(horizontal = 4.dp)
                                    ) {
// Decide what max G to use based on the dropdown
                                        val effectiveMaxG =
                                            when (scaleMode) {
                                                GGScaleMode.Auto -> autoMaxG
                                                else             -> scaleMode.fixedMaxG ?: ggMaxG
                                            }

                                        GGPlot(
                                            maxAbsG = effectiveMaxG,
                                            latG = latG,
                                            longG = longG,
                                            trailSeconds = ggTrailWindow,
                                            ticks = ticks,
                                            brakeThreshG = trailBrakeG,
                                            breakawayG = breakawayG,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .aspectRatio(1f),
                                            onPeaks = { longMax, longBrakeMax, rightMax, leftMax ->
                                                if (scaleMode == GGScaleMode.Auto) {
                                                    updateAutoScaleFromPeaks(longMax, longBrakeMax, rightMax, leftMax)
                                                }
                                            }
                                        )

                                        Spacer(Modifier.height(4.dp))

                                        ScaleSelectorRow(
                                            scaleMode = scaleMode,
                                            onScaleModeChange = { mode ->
                                                scaleMode = mode
                                                if (mode == GGScaleMode.Auto) {
                                                    // Reset auto scaling when switching back to Auto
                                                    observedPeakG = 0f
                                                    autoMaxG = 0.25f
                                                }
                                            },
                                            currentAutoMaxG = autoMaxG
                                        )



                                        Spacer(Modifier.height(8.dp))

                                        // --- Recording status + controls section ---
                                        Surface(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = 8.dp),
                                            color = MaterialTheme.colorScheme.surface,
                                            tonalElevation = 4.dp,
                                            shape = MaterialTheme.shapes.medium
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(12.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                RecordingStatusChip(recordingState)

                                                Row(
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                                                                    containerColor = RecordRed,
                                                                    contentColor = Color.White
                                                                ),
                                                                modifier = Modifier
                                                                    .height(64.dp)           // BIG button
                                                                    .widthIn(min = 160.dp)   // at least this wide
                                                            ) {
                                                                Icon(
                                                                    imageVector = Icons.Filled.FiberManualRecord,
                                                                    contentDescription = "Start recording",
                                                                    modifier = Modifier.size(28.dp)   // bigger icon
                                                                )
                                                                Spacer(Modifier.width(8.dp))
                                                                Text(
                                                                    "Record",
                                                                    style = MaterialTheme.typography.titleMedium
                                                                )
                                                            }
                                                        }

                                                        DriveViewModel.RecordingState.Recording -> {
                                                            Button(
                                                                onClick = { driveViewModel.pauseRecording() },
                                                                modifier = Modifier
                                                                    .height(64.dp)
                                                                    .widthIn(min = 140.dp),
                                                            ) {
                                                                Icon(
                                                                    imageVector = Icons.Filled.Pause,
                                                                    contentDescription = "Pause",
                                                                    modifier = Modifier.size(26.dp)
                                                                )
                                                                Spacer(Modifier.width(8.dp))
                                                                Text(
                                                                    "Pause",
                                                                    style = MaterialTheme.typography.titleMedium
                                                                )
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
                                                                modifier = Modifier
                                                                    .height(64.dp)
                                                                    .widthIn(min = 140.dp),
                                                                colors = ButtonDefaults.buttonColors(
                                                                    containerColor = Color(0xFFB91C1C),
                                                                    contentColor = Color.White
                                                                )
                                                            ) {
                                                                Icon(
                                                                    imageVector = Icons.Filled.Stop,
                                                                    contentDescription = "Stop",
                                                                    modifier = Modifier.size(26.dp)
                                                                )
                                                                Spacer(Modifier.width(8.dp))
                                                                Text(
                                                                    "Stop",
                                                                    style = MaterialTheme.typography.titleMedium
                                                                )
                                                            }
                                                        }

                                                        DriveViewModel.RecordingState.Paused -> {
                                                            Button(
                                                                onClick = { driveViewModel.resumeRecording() },
                                                                modifier = Modifier
                                                                    .height(64.dp)
                                                                    .widthIn(min = 140.dp),
                                                            ) {
                                                                Icon(
                                                                    imageVector = Icons.Filled.PlayArrow,
                                                                    contentDescription = "Resume",
                                                                    modifier = Modifier.size(26.dp)
                                                                )
                                                                Spacer(Modifier.width(8.dp))
                                                                Text(
                                                                    "Resume",
                                                                    style = MaterialTheme.typography.titleMedium
                                                                )
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
                                                                modifier = Modifier
                                                                    .height(64.dp)
                                                                    .widthIn(min = 140.dp),
                                                                colors = ButtonDefaults.buttonColors(
                                                                    containerColor = Color(0xFFB91C1C),
                                                                    contentColor = Color.White
                                                                )
                                                            ) {
                                                                Icon(
                                                                    imageVector = Icons.Filled.Stop,
                                                                    contentDescription = "Stop",
                                                                    modifier = Modifier.size(26.dp)
                                                                )
                                                                Spacer(Modifier.width(8.dp))
                                                                Text(
                                                                    "Stop",
                                                                    style = MaterialTheme.typography.titleMedium
                                                                )
                                                            }
                                                        }
                                                    }

                                                }
                                            }
                                        }

                                    }


                                }
                            }
                        }
                            // === Page 1: Debug panel ===
                            1 -> {
                                if (BuildConfig.DEBUG) {

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

                                        Text(
                                            "Raw GPS (screen): ${"%.6f".format(gpsLat)}, ${
                                                "%.6f".format(
                                                    gpsLon
                                                )
                                            }"
                                        )
                                        Text(
                                            "VM GPS: ${"%.6f".format(vmGpsLat)}, ${
                                                "%.6f".format(
                                                    vmGpsLon
                                                )
                                            }"
                                        )
                                        Text(
                                            "VM G: lat=${"%.2f".format(vmLatG)}, long=${
                                                "%.2f".format(
                                                    vmLongG
                                                )
                                            }"
                                        )

                                        Spacer(Modifier.height(8.dp))

                                        if (t != null && firstCorner != null && distanceToFirstCorner != null) {
                                            Text(
                                                "Corner 1 distance: ${
                                                    "%.1f".format(
                                                        distanceToFirstCorner
                                                    )
                                                } m"
                                            )
                                        } else {
                                            Text("Corner 1 distance: (n/a)")
                                        }

                                        nearestCornerInfo?.let { (_, distM) ->
                                            val inside =
                                                driveViewModel.isWithinCornerTriggerRadius(distM)
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
                                                text = "Nearest corner (all): #$label (${
                                                    String.format(
                                                        "%.1f",
                                                        distM
                                                    )
                                                } m)",
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
                                                gSmoother.setWindowSize(clamped)
                                            },
                                            valueRange = 1f..30f,
                                            steps = 30 - 2
                                        )

                                        // --- Desk Simulation from simulation.csv (truncated file) ---
                                        Spacer(Modifier.height(16.dp))

                                        Text(
                                            text = "Desk Simulation (simulation.csv)",
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(bottom = 4.dp)
                                        )

                                        if (t != null) {
                                            Button(
                                                onClick = {
                                                    driveViewModel.startSimulationFromTruncatedCsv(
                                                        context = context,
                                                        track = activeTrack,
                                                        playbackSpeed = 0.0,
                                                        emaTauMs = tauMsOrNull,
                                                        maWindowSize = smoothingLevel.windowSize
                                                    )
                                                },
                                                modifier = Modifier.padding(bottom = 8.dp)
                                            ) {
                                                Text("Replay simulation.csv")
                                            }
                                        } else {
                                            Text(
                                                text = "Replay simulation.csv: select a track first",
                                                fontSize = 14.sp,
                                                modifier = Modifier.padding(bottom = 8.dp)
                                            )
                                        }

                                        // Existing GPS teleport sim
                                        if (t != null && firstCorner != null) {
                                            Text(
                                                text = "GPS Simulation (Debug only)",
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(bottom = 4.dp)
                                            )

                                            Button(
                                                onClick = {
                                                    driveViewModel.updateGps(
                                                        firstCorner.lat,
                                                        firstCorner.lon
                                                    )
                                                },
                                                modifier = Modifier.padding(bottom = 4.dp)
                                            ) {
                                                Text("Teleport INSIDE corner 1")
                                            }
                                        } else {
                                            Text("GPS Simulation: (needs a track with at least one corner)")
                                        }



                                        if (t != null && firstCorner != null) {
                                            Text(
                                                text = "GPS Simulation (Debug only)",
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(bottom = 4.dp)
                                            )

                                            Button(
                                                onClick = {
                                                    driveViewModel.updateGps(
                                                        firstCorner.lat,
                                                        firstCorner.lon
                                                    )
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
        breakawayG: Float,
        modifier: Modifier = Modifier,
        onPeaks: (Float, Float, Float, Float) -> Unit
    ) {
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

// Light concentric rings at 0.1 G intervals, plus stronger 0.5 G rings
// Minor rings: 0.1 G
            val minorStep = 0.1f
            var minor = minorStep
            while (minor < maxAbsG) {
                val r = radius * (minor / maxAbsG)

                // Skip where a major (0.5 G) ring will be drawn: 0.5 / 0.1 = every 5th step
                val isMajor = ((minor * 10).toInt() % 5) == 0
                if (!isMajor) {
                    drawCircle(
                        color = Color.Gray.copy(alpha = 0.2f),
                        radius = r,
                        center = Offset(cx, cy),
                        style = Stroke(1.5f)
                    )
                }

                minor += minorStep
            }

// Major rings: 0.25 G
            val majorStep = 0.5f
            var major = majorStep
            while (major < maxAbsG) {
                val r = radius * (major / maxAbsG)
                drawCircle(
                    color = Color.Gray.copy(alpha = 0.5f),
                    radius = r,
                    center = Offset(cx, cy),
                    style = Stroke(3.0f)
                )
                major += majorStep
            }

// --- Breakaway G circle (red) ---
// Only draw if the breakaway circle actually fits *inside* the main GG circle.
            if (breakawayG > 0f && maxAbsG > 0f) {
                val fracRaw = breakawayG / maxAbsG

                // If breakawayG is at or beyond the current scale, don't draw it.
                // The 0.98f keeps the red stroke from sitting right on the outer rim.
                if (fracRaw in 0f..0.98f) {
                    val br = radius * fracRaw
                    drawCircle(
                        color = Color(0xFFEF4444), // bright red
                        radius = br,
                        center = Offset(cx, cy),
                        style = Stroke(width = 4f)
                    )
                }
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
                val len = radius * frac

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
                        end = Offset(cx, cy - len),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round
                    )
                } else {
                    // down from center (brake)
                    drawLine(
                        color = barColor,
                        start = Offset(cx, cy),
                        end = Offset(cx, cy + len),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round
                    )
                }
            }

// --- horizontal lateral-G bar (drawn under trail/dot) ---
            run {
                // Proportion of full scale (0..1), clamp to rim
                val frac = (kotlin.math.abs(latG) / maxAbsG).coerceIn(0f, 1f)
                val len = radius * frac

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
                            end = Offset(cx + len, cy),
                            strokeWidth = stroke,
                            cap = StrokeCap.Round
                        )
                    } else {
                        // fill to the left from center
                        drawLine(
                            color = barColor,
                            start = Offset(cx, cy),
                            end = Offset(cx - len, cy),
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
                val tickStroke = 8f
                val minShowG = 0.05f      // ignore tiny noise

                fun drawHPeak(yG: Float, color: Color) {
                    val g = clampG(yG)
                    if (kotlin.math.abs(g) < minShowG) return
                    val y = cy - (g / maxAbsG) * radius
                    drawLine(
                        color = color.copy(alpha = 0.95f),
                        start = Offset(cx - tickWidthPx, y),
                        end = Offset(cx + tickWidthPx, y),
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
                        end = Offset(x, cy + tickWidthPx),
                        strokeWidth = tickStroke,
                        cap = StrokeCap.Round
                    )
                }

                // compute peaks from the pruned trail (auto-expires with window)
                val maxLong = trail.maxOfOrNull { it.y } ?: 0f   // +accel (up)
                val minLong = trail.minOfOrNull { it.y } ?: 0f   // -brake (down)
                val maxLat = trail.maxOfOrNull { it.x } ?: 0f   // +right
                val minLat = trail.minOfOrNull { it.x } ?: 0f   // -left

                // draw ticks (colors match your axis scheme)
                drawHPeak(maxLong, Color(0xFF16A34A)) // accel peak = green
                drawHPeak(minLong, Color(0xFFDC2626)) // brake  peak = red
                drawVPeak(maxLat, Color(0xFFF59E0B)) // right  peak = orange
                drawVPeak(minLat, Color(0xFFA855F7)) // left   peak = violet


                // --- Peak magnitudes from the current (pruned) trail window ---
                val maxLongUp = (trail.maxOfOrNull { it.y } ?: 0f).coerceAtLeast(0f)   // accel +
                val maxLongDown =
                    (-(trail.minOfOrNull { it.y } ?: 0f)).coerceAtLeast(0f) // braking magnitude
                val maxRight = (trail.maxOfOrNull { it.x } ?: 0f).coerceAtLeast(0f)   // right +
                val maxLeft =
                    (-(trail.minOfOrNull { it.x } ?: 0f)).coerceAtLeast(0f) // left magnitude

                // Optional: ignore tiny noise
                fun z(v: Float, min: Float = 0.02f) = if (kotlin.math.abs(v) < min) 0f else v

                val peakLongAccel = z(maxLongUp)
                val peakLongBrake = z(maxLongDown)
                val peakRightAccel = z(maxRight)
                val peakLeftAccel = z(maxLeft)

                // ✅ NEW: notify the caller so Auto mode can update its scale
                onPeaks(
                    peakLongAccel,
                    peakLongBrake,
                    peakRightAccel,
                    peakLeftAccel
                )

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
                            else Color(0xFFEF4444).copy(alpha = segAlpha),
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
                val c = clampToCircle(px, py)
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
        val arcSize = Size(radius * 2f, radius * 2f)

        val bandSweep = (windowEndDeg - windowStartDeg).coerceAtLeast(1f)

        val (shouldHighlight, startDeg, wedgeColor) = when {
            // Bottom-right (Right→Down): BRAKING + RIGHT
            isBraking && isRight -> Triple(
                true,
                0f + windowStartDeg,
                Color(0xFF60A5FA).copy(alpha = 0.65f)
            )
            // Bottom-left  (Down→Left):  BRAKING + LEFT
            isBraking && isLeft -> Triple(
                true,
                90f + windowStartDeg,
                Color(0xFF60A5FA).copy(alpha = 0.65f)
            )
            // Top-right    (Up→Right):   ACCEL + RIGHT
            isAccelerating && isRight -> Triple(
                true,
                270f + windowStartDeg,
                Color(0xFF34D399).copy(alpha = 0.55f)
            )
            // Top-left     (Left→Up):    ACCEL + LEFT
            isAccelerating && isLeft -> Triple(
                true,
                180f + windowStartDeg,
                Color(0xFF34D399).copy(alpha = 0.55f)
            )

            else -> Triple(false, 0f, Color.Transparent)
        }

        Log.d(
            "GG-HILITE",
            "brake=$isBraking accel=$isAccelerating left=$isLeft right=$isRight long=$longG lat=$latG"
        )

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
            drawAxisWedge(90f, Color(0xFFEF4444).copy(alpha = wedgeAlpha))  // Brake

        if (longNearZero && latG > latThreshG)
            drawAxisWedge(0f, Color(0xFFF59E0B).copy(alpha = wedgeAlpha))   // Right

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
        drawDiagLabel("Throttle Steering", 45f, rFrac)
        drawDiagLabel("Throttle Steering", 135f, rFrac)
        // Braking quadrants
        drawDiagLabel("Trail Braking", -45f, rFrac)
        drawDiagLabel("Trail Braking", -135f, rFrac)
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
            canvas.nativeCanvas.drawText("Pure Braking", cx, h - 8f, paint)

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


    @Composable
    fun RecordingStatusChip(state: DriveViewModel.RecordingState) {
        val (label, color) = when (state) {
            DriveViewModel.RecordingState.Idle ->
                "Idle" to Color.Gray

            DriveViewModel.RecordingState.Recording ->
                "LIVE" to RecordRed

            DriveViewModel.RecordingState.Paused ->
                "Paused" to Color(0xFFFFC107)
        }

        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
            tonalElevation = 2.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Canvas(modifier = Modifier.size(8.dp)) {
                    drawCircle(color = color)
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }

@Composable
fun ScaleSelectorRow(
    scaleMode: GGScaleMode,
    onScaleModeChange: (GGScaleMode) -> Unit,
    currentAutoMaxG: Float
) {
    var expanded by remember { mutableStateOf(false) }
    val options = GGScaleMode.values().toList()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (scaleMode == GGScaleMode.Auto)
                "Scale: Auto (${String.format("%.2f", currentAutoMaxG)} G)"
            else
                "Scale: ${scaleMode.label}",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(end = 8.dp)
        )

        Box {
            OutlinedButton(
                onClick = { expanded = true },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = if (scaleMode == GGScaleMode.Auto) "Auto" else scaleMode.label,
                    style = MaterialTheme.typography.bodySmall
                )
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = null
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        onClick = {
                            expanded = false
                            onScaleModeChange(option)
                        }
                    )
                }
            }
        }
    }
}




