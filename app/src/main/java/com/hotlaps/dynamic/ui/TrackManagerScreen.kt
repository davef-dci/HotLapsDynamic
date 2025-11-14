package com.hotlaps.dynamic.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.util.Log
import com.hotlaps.dynamic.data.TrackStorage
import com.hotlaps.dynamic.data.TrackStorage.TrackWithFile
import com.hotlaps.dynamic.model.Track

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackManagerScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onUseTrack: (Track) -> Unit,
    onEditTrack: (Track) -> Unit,
) {
    val context = LocalContext.current

    var tracks by remember { mutableStateOf<List<TrackWithFile>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            val list = TrackStorage.listTracks(context)
            tracks = list
            error = null
        } catch (t: Throwable) {
            Log.e("TrackManagerScreen", "Error loading tracks", t)
            tracks = emptyList()
            error = t.message ?: t.toString()
        } finally {
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Track Manager") },
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
                                onUseTrack = onUseTrack,
                                onEditTrack = onEditTrack,
                                onDelete = {
                                    if (TrackStorage.deleteTrackFile(trackWithFile)) {
                                        tracks = tracks.filter { it.id != trackWithFile.id }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackRow(
    trackWithFile: TrackWithFile,
    onUseTrack: (Track) -> Unit,
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
                OutlinedButton(
                    onClick = { onUseTrack(track) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Use Track")
                }
                OutlinedButton(
                    onClick = { onEditTrack(track) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Edit")
                }
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
