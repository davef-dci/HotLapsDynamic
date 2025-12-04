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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.hotlaps.dynamic.model.Track
import com.hotlaps.dynamic.model.Corner
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import java.io.File
import com.hotlaps.dynamic.data.TrackStorage




// Local UI-only state holder for one corner row in the form.
// We keep everything as strings for now; we'll parse to numbers on Save.
private data class CornerFormState(
    val index: Int,
    val name: String = "",
    val latText: String = "",
    val lonText: String = ""
)




@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateTrackFromCoordinatesScreen(
    onBack: () -> Unit
) {
    // --- Local UI state for this screen ---

    // Track-level
    var trackName by remember { mutableStateOf("") }



// List of corner form rows; start with Corner 1.
    val cornerForms = remember {
        mutableStateListOf(
            CornerFormState(index = 1)
        )
    }

    val context = LocalContext.current
   // val gson = remember { Gson() }


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
                .verticalScroll(rememberScrollState())
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

            Spacer(Modifier.height(24.dp))

// --- Corner list ---
            cornerForms.forEachIndexed { idx, cornerForm ->
                val cornerIndex = cornerForm.index

                Text(
                    text = "Corner $cornerIndex (apex)",
                    style = MaterialTheme.typography.titleMedium
                )

                Spacer(Modifier.height(8.dp))

                // Corner name (optional)
                OutlinedTextField(
                    value = cornerForm.name,
                    onValueChange = { newName ->
                        cornerForms[idx] = cornerForm.copy(name = newName)
                    },
                    label = { Text("Corner Name (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(12.dp))

                // Latitude
                OutlinedTextField(
                    value = cornerForm.latText,
                    onValueChange = { newLat ->
                        cornerForms[idx] = cornerForm.copy(latText = newLat)
                    },
                    label = { Text("Latitude") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(12.dp))

                // Longitude
                OutlinedTextField(
                    value = cornerForm.lonText,
                    onValueChange = { newLon ->
                        cornerForms[idx] = cornerForm.copy(lonText = newLon)
                    },
                    label = { Text("Longitude") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(12.dp))



                Spacer(Modifier.height(12.dp))



                Spacer(Modifier.height(24.dp))
            }

// --- Add Corner button ---
            // --- Add Corner button ---
            Button(
                onClick = {
                    val nextIndex = cornerForms.size + 1
                    cornerForms.add(CornerFormState(index = nextIndex))
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Add Corner")
            }

            Spacer(Modifier.height(24.dp))

// --- Save Track button ---
            Button(
                onClick = {
                    if (trackName.isBlank()) {
                        Toast.makeText(context, "Please enter a track name", Toast.LENGTH_SHORT).show()
                        return@Button
                    }

                    val corners = cornerForms.mapNotNull { form ->
                        val lat = form.latText.toDoubleOrNull()
                        val lon = form.lonText.toDoubleOrNull()


                        if (lat == null || lon == null) {
                            println("SaveTrack: Skipping corner ${form.index} due to invalid data")
                            null
                        } else {
                            Corner(
                                index = form.index,
                                officialNumber = null,
                                name = form.name.ifBlank { null },
                                lat = lat,
                                lon = lon,
                            )
                        }
                    }

                    if (corners.isEmpty()) {
                        Toast.makeText(context, "Please enter at least one valid corner", Toast.LENGTH_SHORT).show()
                        println("SaveTrack: No valid corners, not creating track")
                        return@Button
                    }

                    val trackId = System.currentTimeMillis()
                    val track = Track(
                        id = trackId,
                        name = trackName,
                        corners = corners
                    )

                    val ok = TrackStorage.saveTrack(context, track)
                    if (ok) {
                        Toast.makeText(context, "Track saved", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Error saving track", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save Track")
            }
            Spacer(Modifier.height(16.dp))

// --- Delete all saved tracks (debug) ---
            Button(
                onClick = {
                    try {
                        val tracksDir = File(context.getExternalFilesDir(null), "tracks")
                        if (!tracksDir.exists()) {
                            Toast.makeText(context, "No tracks directory found", Toast.LENGTH_SHORT).show()
                            println("DeleteTracks: tracks directory does not exist")
                            return@Button
                        }

                        val files = tracksDir.listFiles()?.toList().orEmpty()
                        if (files.isEmpty()) {
                            Toast.makeText(context, "No saved tracks to delete", Toast.LENGTH_SHORT).show()
                            println("DeleteTracks: no files found in ${tracksDir.absolutePath}")
                            return@Button
                        }

                        var deletedCount = 0
                        files.forEach { f ->
                            println("DeleteTracks: deleting ${f.absolutePath}")
                            if (f.delete()) {
                                deletedCount++
                            } else {
                                println("DeleteTracks: FAILED to delete ${f.absolutePath}")
                            }
                        }

                        Toast.makeText(context, "Deleted $deletedCount track file(s)", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        e.printStackTrace()
                        Toast.makeText(context, "Error deleting tracks", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Delete All Saved Tracks (Debug)")
            }

            Spacer(Modifier.height(24.dp))

            Text(
                text = "Tracks are saved under Android/data/com.hotlaps.dynamic/files/tracks on your device.",
                style = MaterialTheme.typography.bodySmall
            )


        }
        }
}
