package com.hotlaps.dynamic.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateTrackFromCoordinatesScreen(
    onBack: () -> Unit
) {
    // --- Local UI state for this screen ---

    // Track-level
    var trackName by remember { mutableStateOf("") }

    // Corner 1 fields (we'll generalize to a list later)
    var cornerName by remember { mutableStateOf("") }
    var cornerLatText by remember { mutableStateOf("") }
    var cornerLonText by remember { mutableStateOf("") }
    var captureBeforeMsText by remember { mutableStateOf("2000") }  // example default
    var captureAfterMsText by remember { mutableStateOf("1000") }   // example default

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Create From Coordinates") },
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
            Text(
                text = "Create Track From Coordinates",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(Modifier.height(16.dp))

            // --- Track name entry ---
            OutlinedTextField(
                value = trackName,
                onValueChange = { trackName = it },
                label = { Text("Track Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(24.dp))

            Text(
                text = "Corner 1 (apex)",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(Modifier.height(8.dp))

            // Corner name (optional)
            OutlinedTextField(
                value = cornerName,
                onValueChange = { cornerName = it },
                label = { Text("Corner Name (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))

            // Latitude
            OutlinedTextField(
                value = cornerLatText,
                onValueChange = { cornerLatText = it },
                label = { Text("Latitude") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))

            // Longitude
            OutlinedTextField(
                value = cornerLonText,
                onValueChange = { cornerLonText = it },
                label = { Text("Longitude") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))

            // Capture window BEFORE apex (ms)
            OutlinedTextField(
                value = captureBeforeMsText,
                onValueChange = { captureBeforeMsText = it },
                label = { Text("Capture Window Before Apex (ms)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))

            // Capture window AFTER apex (ms)
            OutlinedTextField(
                value = captureAfterMsText,
                onValueChange = { captureAfterMsText = it },
                label = { Text("Capture Window After Apex (ms)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(24.dp))

            Text(
                text = "Next: We'll add support for multiple corners and a Save button that builds a Track object.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
