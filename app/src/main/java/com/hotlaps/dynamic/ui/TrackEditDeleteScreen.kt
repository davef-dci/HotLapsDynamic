package com.hotlaps.dynamic.ui

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hotlaps.dynamic.data.TrackStorage
import com.hotlaps.dynamic.data.TrackStorage.TrackWithFile
import com.hotlaps.dynamic.model.Track
import com.hotlaps.dynamic.viewmodel.TrackSelectionViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackEditDeleteScreen(
    trackSelectionViewModel: TrackSelectionViewModel, // currently unused, kept to avoid breaking callers
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onUseTrack: (Track) -> Unit,                      // currently unused, kept to avoid breaking callers
    onEditTrack: (Track) -> Unit,
    onOpenDrawer: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current

    var tracks by remember { mutableStateOf<List<TrackWithFile>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var trackToDelete by remember { mutableStateOf<TrackWithFile?>(null) }




    LaunchedEffect(Unit) {
        try {
            val list = TrackStorage.listTracks(context)
            tracks = list
            error = null
        } catch (t: Throwable) {
            Log.e("TrackEditDeleteScreen", "Error loading tracks", t)
            tracks = emptyList()
            error = t.message ?: t.toString()
        } finally {
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit or Delete Tracks") },
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
    ) { innerPadding ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            when {
                isLoading -> {
                    CircularProgressIndicator()
                }

                error != null -> {
                    Text(
                        text = "Error loading tracks:\n$error",
                        color = MaterialTheme.colorScheme.error
                    )
                }

                tracks.isEmpty() -> {
                    Text("No tracks saved yet.\nCreate one from coordinates first.")
                }

                else -> {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(tracks, key = { it.id }) { trackWithFile ->
                            TrackRow(
                                trackWithFile = trackWithFile,
                                onEditTrack = onEditTrack,
                                onDelete = {
                                    trackToDelete = trackWithFile
                                }
                            )
                        }
                    }
                }
            }

            if (trackToDelete != null) {
                val pending = trackToDelete!!

                AlertDialog(
                    onDismissRequest = { trackToDelete = null },
                    title = { Text("Delete Track") },
                    text = {
                        Text("Are you sure you want to delete \"${pending.track.name}\"?")
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                if (TrackStorage.deleteTrackFile(pending)) {
                                    tracks = tracks.filter { it.id != pending.id }
                                }
                                trackToDelete = null
                            }
                        ) {
                            Text("Delete")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { trackToDelete = null }) {
                            Text("Cancel")
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun TrackRow(
    trackWithFile: TrackWithFile,
    onEditTrack: (Track) -> Unit,
    onDelete: () -> Unit
) {
    val track = trackWithFile.track

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Text(
                text = track.name,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Corners: ${track.corners.size} • ID: ${trackWithFile.id}",
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                // Edit button
                OutlinedButton(
                    onClick = { onEditTrack(track) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Edit")
                }

                // Delete button
                TextButton(
                    onClick = onDelete,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Delete")
                }
            }
        }
    }
}
