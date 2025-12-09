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
    var trackName by remember { mutableStateOf(track.name) }


    val latLonOverrides = remember {
        mutableStateMapOf<Int, Pair<Double, Double>>()
    }

    val nameOverrides = remember {
        mutableStateMapOf<Int, String>()
    }



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
                                val latLonOverride = latLonOverrides[corner.index]
                                val nameOverride = nameOverrides[corner.index]

                                var updated = corner


                                if (latLonOverride != null) {
                                    updated = updated.copy(
                                        lat = latLonOverride.first,
                                        lon = latLonOverride.second
                                    )
                                }

                                if (nameOverride != null) {
                                    val finalName = nameOverride.trim().ifBlank { null }
                                    updated = updated.copy(name = finalName)
                                }

                                updated
                            }


                            // New track with updated corners
                            val updatedTrack = track.copy(
                                name = trackName.trim(),
                                corners = updatedCorners
                            )


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

            OutlinedTextField(
                value = trackName,
                onValueChange = { trackName = it },
                label = { Text("Track Name") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))



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
                    val latLonOverride = latLonOverrides[corner.index]
                    val nameOverride = nameOverrides[corner.index]

                    CornerSummaryCard(
                        corner = corner,
                        overrideLat = latLonOverride?.first,
                        overrideLon = latLonOverride?.second,
                        overrideName = nameOverride,
                        onLatLonChange = { lat, lon ->
                            latLonOverrides[corner.index] = lat to lon
                        },
                        onNameChange = { newName ->
                            nameOverrides[corner.index] = newName
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
    overrideLat: Double?,
    overrideLon: Double?,
    overrideName: String?,
    onLatLonChange: (lat: Double, lon: Double) -> Unit,
    onNameChange: (name: String) -> Unit
)


{




    var latText by remember(
        corner.index,
        overrideLat
    ) {
        mutableStateOf(
            (overrideLat ?: corner.lat).toString()
        )
    }

    var lonText by remember(
        corner.index,
        overrideLon
    ) {
        mutableStateOf(
            (overrideLon ?: corner.lon).toString()
        )
    }






    val latValue: Double = latText.toDoubleOrNull() ?: corner.lat
    val lonValue: Double = lonText.toDoubleOrNull() ?: corner.lon

    var nameText by remember(
        corner.index,
        overrideName
    ) {
        mutableStateOf(
            overrideName ?: (corner.name ?: "")
        )
    }



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


            OutlinedTextField(
                value = nameText,
                onValueChange = { newText ->
                    nameText = newText
                    onNameChange(newText)
                },
                label = { Text("Corner Name (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))


            OutlinedTextField(
                value = latText,
                onValueChange = { newText ->
                    latText = newText
                    val lat = newText.toDoubleOrNull()
                    val lon = lonText.toDoubleOrNull()
                    if (lat != null && lon != null) {
                        onLatLonChange(lat, lon)
                    }
                },
                label = { Text("Corner Latitude") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = lonText,
                onValueChange = { newText ->
                    lonText = newText
                    val lat = latText.toDoubleOrNull()
                    val lon = newText.toDoubleOrNull()
                    if (lat != null && lon != null) {
                        onLatLonChange(lat, lon)
                    }
                },
                label = { Text("Corner Longitude") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))



            Spacer(Modifier.height(8.dp))

        }
    }
}


