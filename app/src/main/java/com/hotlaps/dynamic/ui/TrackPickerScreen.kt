package com.hotlaps.dynamic.ui

import android.util.Log
import androidx.compose.foundation.clickable
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
import com.hotlaps.dynamic.data.TrackStorage
import com.hotlaps.dynamic.data.TrackStorage.TrackWithFile
import com.hotlaps.dynamic.viewmodel.TrackSelectionViewModel
import com.hotlaps.dynamic.model.Track

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackPickerScreen(
    trackSelectionViewModel: TrackSelectionViewModel,
    onBack: () -> Unit,
    onTrackChosen: () -> Unit
) {
    val context = LocalContext.current

    var tracks by remember { mutableStateOf<List<TrackWithFile>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            tracks = TrackStorage.listTracks(context)
            error = null
        } catch (t: Throwable) {
            Log.e("TrackPickerScreen", "Error loading tracks", t)
            tracks = emptyList()
            error = t.message ?: t.toString()
        } finally {
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Select Track") },
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
                    Text(
                        text = "No tracks saved yet.\nCreate a track first in Track Manager.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                else -> {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(tracks, key = { it.id }) { trackWithFile ->
                            TrackPickerRow(
                                track = trackWithFile.track,
                                onSelect = {
                                    // 1) Update the shared selection ViewModel
                                    trackSelectionViewModel.selectTrack(trackWithFile.track)

                                    // 2) Let the caller (NavHost) pop back to GGScreen
                                    onTrackChosen()
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
private fun TrackPickerRow(
    track: Track,
    onSelect: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
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
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Corners: ${track.corners.size}",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
