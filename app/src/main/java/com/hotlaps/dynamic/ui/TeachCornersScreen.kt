package com.hotlaps.dynamic.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.hotlaps.dynamic.data.TrackStorage
import com.hotlaps.dynamic.model.Corner
import com.hotlaps.dynamic.model.Track
import com.hotlaps.dynamic.viewmodel.DriveViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeachCornersScreen(
    onBack: () -> Unit,
    driveViewModel: DriveViewModel? = null
) {
    val context = LocalContext.current

    // Track name entered by the user
    var trackName by remember { mutableStateOf("") }

    // Capture window settings (ms) – editable by the user
    var captureBeforeMsText by remember { mutableStateOf("5000") }
    var captureAfterMsText by remember { mutableStateOf("5000") }

    // List of corners recorded so far in this session
    val corners = remember { mutableStateListOf<Corner>() }

    // --- LIVE GPS FROM DRIVE VIEWMODEL (IF PRESENT) ---
    // We read the flows *once* here at the top-level Composable.
    // No collectAsState() calls inside helper functions.
    val gpsLat: Double?
    val gpsLon: Double?

    if (driveViewModel != null) {
        val latState by driveViewModel.gpsLat.collectAsState()
        val lonState by driveViewModel.gpsLon.collectAsState()
        gpsLat = latState
        gpsLon = lonState
    } else {
        gpsLat = null
        gpsLon = null
    }

    // Nicely formatted display strings
    val latDisplay = gpsLat?.let { String.format("%.6f", it) } ?: "--"
    val lonDisplay = gpsLon?.let { String.format("%.6f", it) } ?: "--"

    // --- Local helper: record the current GPS as a new corner ---
    fun recordApex() {
        val lat = gpsLat
        val lon = gpsLon

        // Treat null or (0.0, 0.0) as "no fix yet"
        if (lat == null || lon == null || (lat == 0.0 && lon == 0.0)) {
            Toast.makeText(context, "No GPS location available yet", Toast.LENGTH_SHORT).show()
            return
        }

        val nextIndex = corners.size + 1

        // Parse capture windows, falling back to sensible defaults if bad input
        val beforeMs = captureBeforeMsText.toIntOrNull() ?: 5000
        val afterMs = captureAfterMsText.toIntOrNull() ?: 5000

        corners += Corner(
            index = nextIndex,
            officialNumber = null,
            name = null,
            lat = lat,
            lon = lon,
            captureBeforeMs = beforeMs,
            captureAfterMs = afterMs
        )

        Toast.makeText(context, "Recorded Corner $nextIndex", Toast.LENGTH_SHORT).show()
    }

    // --- Local helper: save the Track to JSON and exit ---
    fun saveTrackAndExit() {
        if (trackName.isBlank()) {
            Toast.makeText(context, "Please enter a track name", Toast.LENGTH_SHORT).show()
            return
        }

        if (corners.isEmpty()) {
            Toast.makeText(context, "Record at least one corner", Toast.LENGTH_SHORT).show()
            return
        }

        val trackId = System.currentTimeMillis()
        val track = Track(
            id = trackId,
            name = trackName,
            corners = corners.toList()
        )

        val ok = TrackStorage.saveTrack(context, track)
        if (ok) {
            Toast.makeText(context, "Track saved", Toast.LENGTH_SHORT).show()
            onBack()
        } else {
            Toast.makeText(context, "Error saving track", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Teach Corners While Driving") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.Start
        ) {

            // --- SAFETY DISCLAIMER ---
            Text(
                text = "⚠️ Safety Warning",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Only use this feature in a safe environment. Stop the vehicle before pressing any buttons.",
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(Modifier.height(24.dp))

            // --- TRACK NAME ---
            OutlinedTextField(
                value = trackName,
                onValueChange = { trackName = it },
                label = { Text("Track Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(24.dp))

            // --- CAPTURE WINDOW SETTINGS ---
            Text(
                text = "Capture window around apex (milliseconds):",
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = captureBeforeMsText,
                onValueChange = { captureBeforeMsText = it },
                label = { Text("Capture BEFORE apex (ms)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = captureAfterMsText,
                onValueChange = { captureAfterMsText = it },
                label = { Text("Capture AFTER apex (ms)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(24.dp))

            // --- CURRENT GPS ---
            Text(
                text = "Current GPS: $latDisplay, $lonDisplay",
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(Modifier.height(24.dp))

            // --- RECORD BUTTON ---
            val recordEnabled =
                gpsLat != null && gpsLon != null && !(gpsLat == 0.0 && gpsLon == 0.0)

            Button(
                onClick = { recordApex() },
                enabled = recordEnabled,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Record Corner Apex")
            }

            Spacer(Modifier.height(24.dp))

            // --- LIST OF COLLECTED CORNERS ---
            Text(
                text = "Corners Recorded: ${corners.size}",
                style = MaterialTheme.typography.titleSmall
            )

            Spacer(Modifier.height(12.dp))

            corners.forEach { corner ->
                val cLat = String.format("%.6f", corner.lat)
                val cLon = String.format("%.6f", corner.lon)

                Text(
                    text = "Corner ${corner.index}:  $cLat, $cLon  (before=${corner.captureBeforeMs}ms, after=${corner.captureAfterMs}ms)",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(6.dp))
            }

            Spacer(Modifier.height(24.dp))

            // --- FINISH BUTTON ---
            Button(
                onClick = { saveTrackAndExit() },
                enabled = corners.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Finish Track")
            }
        }
    }
}
