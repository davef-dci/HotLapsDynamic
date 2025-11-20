package com.hotlaps.dynamic.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hotlaps.dynamic.model.Track
import com.hotlaps.dynamic.model.Corner
import androidx.compose.runtime.*
import androidx.compose.material3.OutlinedTextField

import androidx.compose.runtime.*
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.platform.LocalContext
import com.hotlaps.dynamic.data.TrackStorage
import androidx.compose.runtime.mutableStateMapOf



@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditTrackScreen(
    track: Track,
    onBack: () -> Unit
)






{
    val context = LocalContext.current
    val cornerOverrides = remember {
        mutableStateMapOf<Int, Pair<Int, Int>>()}

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit Track – ${track.name}") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            // Build new list of corners using overrides where present
                            val updatedCorners = track.corners.map { corner ->
                                val override = cornerOverrides[corner.index]
                                if (override != null) {
                                    corner.copy(
                                        captureBeforeMs = override.first,
                                        captureAfterMs = override.second
                                    )
                                } else {
                                    corner
                                }
                            }

                            // New track with updated corners
                            val updatedTrack = track.copy(corners = updatedCorners)

                            // Persist to JSON
                            TrackStorage.saveTrack(context, updatedTrack)

                            // Go back to Track Manager
                            onBack()
                        }
                    ) {
                        Text("Save")
                    }
                }
            )


        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            Text(
                text = "Corners for this track:",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(Modifier.height(12.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(track.corners) { corner ->
                    val override = cornerOverrides[corner.index]

                    CornerSummaryCard(
                        corner = corner,
                        overrideBeforeMs = override?.first,
                        overrideAfterMs = override?.second,
                        onValuesChange = { beforeMs, afterMs ->
                            cornerOverrides[corner.index] = beforeMs to afterMs
                        }
                    )
                }
            }

        }
    }
}

@Composable
private fun CornerSummaryCard(
    corner: Corner,
    overrideBeforeMs: Int?,
    overrideAfterMs: Int?,
    onValuesChange: (beforeMs: Int, afterMs: Int) -> Unit
)

{
    var beforeText by remember(
        corner.index,
        overrideBeforeMs
    ) {
        mutableStateOf(
            (overrideBeforeMs ?: corner.captureBeforeMs).toString()
        )
    }

    var afterText by remember(
        corner.index,
        overrideAfterMs
    ) {
        mutableStateOf(
            (overrideAfterMs ?: corner.captureAfterMs).toString()
        )
    }


    // Safely parse the text into Ints (or fall back to the original values)
    val beforeMs: Int = beforeText.toIntOrNull() ?: corner.captureBeforeMs
    val afterMs: Int = afterText.toIntOrNull() ?: corner.captureAfterMs

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Text(
                text = "Corner ${corner.index}" +
                        (corner.name?.let { " – $it" } ?: ""),
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(Modifier.height(4.dp))

            Text(
                text = "Lat/Lon: ${corner.lat}, ${corner.lon}",
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = beforeText,
                onValueChange = { newText ->
                    beforeText = newText
                    val beforeMs = newText.toIntOrNull()
                    val afterMs = afterText.toIntOrNull()
                    if (beforeMs != null && afterMs != null) {
                        onValuesChange(beforeMs, afterMs)
                    }
                },
                label = { Text("Capture Before (ms)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = afterText,
                onValueChange = { newText ->
                    afterText = newText
                    val beforeMs = beforeText.toIntOrNull()
                    val afterMs = newText.toIntOrNull()
                    if (beforeMs != null && afterMs != null) {
                        onValuesChange(beforeMs, afterMs)
                    }
                },
                label = { Text("Capture After (ms)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}


