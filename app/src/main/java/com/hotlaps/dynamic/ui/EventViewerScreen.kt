package com.hotlaps.dynamic.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hotlaps.dynamic.data.EventStorage
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import java.io.File



@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventViewerScreen(
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Event Viewer") },
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
                .padding(24.dp)
        ) {

            // --- NEW: Load event files from storage ---
            val context = LocalContext.current
            val eventFiles = remember { EventStorage.listEventFiles(context) }
            // --- NEW: which event file the user has selected (if any) ---
            var selectedFile by remember { mutableStateOf<File?>(null) }

            // --- NEW: Load samples for the currently selected file (or empty if none selected) ---
            val samplesForSelected = remember(selectedFile) {
                selectedFile?.let { file ->
                    EventStorage.loadSamplesFromCsv(file)
                } ?: emptyList()
            }



            // --- NEW: Display simple status message ---
            Text("Found ${eventFiles.size} event file(s)")

            // --- NEW: Show how many samples are in the selected event (if any) ---
            Text(
                text = if (selectedFile == null) {
                    "No event selected"
                } else {
                    "Selected event has ${samplesForSelected.size} sample(s)"
                },
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(Modifier.height(16.dp))

            // --- NEW: Show each event file name in a simple vertical list ---
            LazyColumn {
                items(eventFiles) { file ->
                    // --- NEW: highlight the selected file and allow tapping ---
                    val isSelected = (file == selectedFile)

                    Text(
                        text = if (isSelected) "▶ ${file.name}" else file.name,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                // Update which file is selected when tapped
                                selectedFile = file
                            }
                            .padding(vertical = 4.dp)
                    )

                    Spacer(Modifier.height(4.dp))
                }
            }

            Spacer(Modifier.height(24.dp))

            // --- NEW: Show a small preview of the selected event's samples ---
            if (selectedFile != null) {
                Text(
                    text = "Sample preview (up to 5 rows):",
                    style = MaterialTheme.typography.titleSmall
                )

                Spacer(Modifier.height(8.dp))

                // Show up to the first 5 samples with basic fields
                samplesForSelected.take(5).forEach { sample ->
                    Text(
                        text = "t=${sample.intervalMs}ms, latG=${sample.latG}, longG=${sample.longG}, " +
                                "corner=${sample.cornerIndex}, visit=${sample.visitNumber}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(4.dp))
                }

                if (samplesForSelected.size > 5) {
                    Text(
                        text = "... (${samplesForSelected.size - 5} more samples)",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }


            Spacer(Modifier.height(24.dp))

            // --- NEW: Show a GG plot placeholder when we have a selected event ---
            if (selectedFile != null && samplesForSelected.isNotEmpty()) {
                Text(
                    text = "G-G Plot (entire event):",
                    style = MaterialTheme.typography.titleSmall
                )

                Spacer(Modifier.height(8.dp))

                SimpleGGPlot()
            }





        }

    }
}


// --- NEW: Very simple GG plot placeholder ---
// For now this just draws a box and a label. We'll add real plotting logic next.
@Composable
fun SimpleGGPlot() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)           // square box, like a GG circle
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            .padding(8.dp)
    ) {
        Text(
            text = "GG plot will go here",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}
